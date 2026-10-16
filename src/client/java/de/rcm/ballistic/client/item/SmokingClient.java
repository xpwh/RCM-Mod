package de.rcm.ballistic.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.effect.DynamicLights;
import de.rcm.ballistic.client.effect.Stress;
import de.rcm.ballistic.client.gun.AkArms;
import de.rcm.ballistic.client.gun.GunAudio;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.item.Smoking;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Smoking, the way it is done: the cigarette held between two fingers at chest height, its ember glowing a dull
 * red, a thin blue thread of smoke curling off it and now and then a tap of the finger to knock the ash off.
 * Every so often the hand comes up, the cigarette goes to the lips, the ember flares with the drag (and
 * crackles), the hand takes it away again - and the smoke is blown out in a grey breath. Others see the same:
 * the arm coming up to the face and going down again.
 * <p>
 * While a cigarette is going your hand is busy: no weapon comes up, no swinging or shooting, and you walk
 * slower (the server sees to that). Attack flicks the cigarette away. Every drag settles your nerves a little.
 */
public final class SmokingClient {
	/** When the drags are taken (ticks after lighting); the first one was taken lighting up. */
	private static final int[] DRAGS = {150, 270, 390, 510, 630, 750, 860};
	private static final int DRAG_LEN = 26;
	/** The hand coming up before a drag, and going down after it. */
	private static final int RISE = 16;
	private static final int FALL = 12;
	/** Knocking the ash off. */
	private static final int[] TAPS = {90, 330, 570, 700, 810};
	/** Camera-relative position of the lips in your own first-person view (x right, y up, -z ahead). */
	public static final Vector3f OWN_MOUTH = new Vector3f(0.0F, -0.082F, -0.115F);
	/** How much of the cigarette is in the mouth. */
	public static final float IN_LIPS = 0.01F;

	/** Your own hand: down at the chest, and at the lips (camera space), and where the filter sits relative to it. */
	private static final Vector3f HAND_LOW = new Vector3f(0.2F, -0.215F, -0.4F);
	private static final Vector3f HAND_AWAY = new Vector3f(0.3F, -0.65F, -0.36F);
	private static final Vector3f FILTER_LOW = new Vector3f(-0.032F, 0.03F, -0.018F);
	private static final Vector3f HAND_MOUTH = new Vector3f(OWN_MOUTH).add(0.032F, -0.036F, -0.012F);
	private static final Vector3f REST_L = new Vector3f(-0.48F, -0.62F, -0.36F);
	private static final Quaternionf Q_LOW = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(-0.55F, 0.32F, -0.78F).normalize());
	private static final Quaternionf Q_MOUTH = new Quaternionf().rotationTo(new Vector3f(0, 0, 1), new Vector3f(-0.12F, -0.22F, -1.0F).normalize());

	private record Smoker(long lit, int ticks) {
	}

	private static final Map<Integer, Smoker> SMOKERS = new HashMap<>();

	private SmokingClient() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(Smoking.SmokePayload.TYPE, (payload, context) -> {
			var level = context.client().level;
			if (level == null) {
				return;
			}
			if (payload.ticks() <= 0) {
				SMOKERS.remove(payload.entity());
			} else {
				SMOKERS.put(payload.entity(), new Smoker(level.getGameTime(), payload.ticks()));
			}
		});
		ClientTickEvents.START_CLIENT_TICK.register(SmokingClient::busyHands);
		ClientTickEvents.END_CLIENT_TICK.register(SmokingClient::busyHands);
		ClientTickEvents.END_CLIENT_TICK.register(SmokingClient::tick);
		WorldRenderEvents.END_MAIN.register(SmokingClient::render);
	}

	public static boolean isSmoking(Player player) {
		return age(player, 0.0F) >= 0.0F;
	}

	/** How far the smoker's hand is raised to the lips (0 down .. 1 at the mouth), or -1 if not smoking: for the arm in third person. */
	public static float raise(int entityId, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !(mc.level.getEntity(entityId) instanceof Player p)) {
			return -1.0F;
		}
		float a = age(p, partialTick);
		return a < 0.0F ? -1.0F : raise(a);
	}

	/** Ticks since the player lit up, or -1 if not smoking. */
	private static float age(Player player, float partialTick) {
		Smoker s = SMOKERS.get(player.getId());
		if (s == null || player.level() == null) {
			return -1.0F;
		}
		float a = player.level().getGameTime() - s.lit() + partialTick;
		return a < s.ticks() ? a : -1.0F;
	}

	private static float burnt(Player player, float age) {
		Smoker s = SMOKERS.get(player.getId());
		return s == null ? 1.0F : age / s.ticks();
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** 0 the hand down at the chest .. 1 the cigarette at the lips. */
	private static float raise(float a) {
		float r = 1.0F - smooth((a - 10.0F) / FALL); // just lit: still at the lips
		for (int d : DRAGS) {
			float t = a - d;
			if (t > -RISE && t < DRAG_LEN + FALL) {
				r = Math.max(r, t < 0.0F ? smooth((t + RISE) / RISE) : t < DRAG_LEN ? 1.0F : 1.0F - smooth((t - DRAG_LEN) / FALL));
			}
		}
		return r;
	}

	/** How brightly the ember glows (0 smouldering .. 1 a drag being taken). */
	private static float glow(float age) {
		float g = age < 12.0F ? 1.0F - age / 12.0F : 0.0F;
		for (int d : DRAGS) {
			float t = age - d;
			if (t > 0.0F && t < DRAG_LEN + 10.0F) {
				g = Math.max(g, t < 4.0F ? t / 4.0F : t < DRAG_LEN ? 1.0F : 1.0F - (t - DRAG_LEN) / 10.0F);
			}
		}
		return g;
	}

	/** The smoke goes out as the cigarette comes away from the lips. */
	private static boolean exhaling(float a) {
		if (a >= 12.0F && a < 30.0F) {
			return true;
		}
		for (int d : DRAGS) {
			if (a >= d + DRAG_LEN + 3 && a < d + DRAG_LEN + 22) {
				return true;
			}
		}
		return false;
	}

	/** A tap of the finger knocking the ash off: 0 .. 1 .. 0 over six ticks, or 0. */
	private static float tap(float a) {
		for (int t : TAPS) {
			float u = (a - t) / 6.0F;
			if (u > 0.0F && u < 1.0F) {
				return Mth.sin(u * Mth.PI);
			}
		}
		return 0.0F;
	}

	/** Your hand and the cigarette in it (camera space): where the hand is, where the filter is, how the cigarette points. */
	record Pose(Vector3f hand, Vector3f filter, Quaternionf q) {
	}

	static Pose own(float a, float time) {
		float r = raise(a);
		Vector3f hand = new Vector3f(HAND_LOW).lerp(HAND_MOUTH, r);
		// the hand comes up in a little arc, out and round to the mouth
		hand.add(0.035F * Mth.sin(r * Mth.PI), -0.01F * Mth.sin(r * Mth.PI), 0.015F * Mth.sin(r * Mth.PI));
		Vector3f off = new Vector3f(FILTER_LOW).lerp(new Vector3f(OWN_MOUTH).sub(HAND_MOUTH), r);
		Quaternionf q = new Quaternionf(Q_LOW).slerp(Q_MOUTH, r);
		if (a < 8.0F) {
			// just lit: the hand comes up from below to take it from the lips
			hand = new Vector3f(HAND_AWAY).lerp(HAND_MOUTH, smooth(a / 8.0F));
			off = new Vector3f(OWN_MOUTH).sub(hand);
		}
		// idle: breathing; a tap now and then knocks the ash off
		float k = tap(a);
		hand.add(0.002F * Mth.sin(time * 0.09F), 0.003F * Mth.sin(time * 0.13F) - 0.012F * k, 0.0F);
		q.rotateX(0.25F * k);
		float nerves = Stress.level() * Stress.level() * 0.004F;
		hand.add(nerves * Mth.sin(time * 1.9F), nerves * Mth.sin(time * 2.7F + 1.0F), 0.0F);
		return new Pose(hand, new Vector3f(hand).add(off), q);
	}

	/** Your own hand with the cigarette, drawn instead of whatever you hold while it is going. */
	public static void renderHand(AbstractClientPlayer player, PoseStack poseStack, SubmitNodeCollector collector, int light, float partialTick) {
		float a = age(player, partialTick);
		if (a < 0.0F) {
			return;
		}
		float time = player.level().getGameTime() + partialTick;
		Pose p = own(a, time);
		Matrix4f base = HandSpace.enter(poseStack);
		AkArms.draw(player, base, poseStack, collector, light, HandSpace.hand(p.hand()), HandSpace.hand(REST_L), AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER);
		float burnt = burnt(player, a);
		CigaretteHands.place(poseStack, p.filter(), p.q());
		SmokeKit.submit(poseStack, collector, SmokeKit.cigarette(burnt), light);
		SmokeKit.ember(poseStack, collector, burnt, glow(a) * (0.9F + 0.1F * Mth.sin(a * 1.7F)));
		poseStack.popPose();
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ hands busy

	/** While smoking: no shooting, no swinging, no using, no sprinting. Attack flicks the cigarette away. */
	private static void busyHands(Minecraft mc) {
		var p = mc.player;
		if (p == null || mc.screen != null || !isSmoking(p)) {
			return;
		}
		boolean flick = false;
		while (mc.options.keyAttack.consumeClick()) {
			flick = true;
		}
		while (mc.options.keyUse.consumeClick()) {
			// the hand is busy
		}
		mc.options.keyAttack.setDown(false);
		mc.options.keyUse.setDown(false);
		mc.options.keySprint.setDown(false);
		p.setSprinting(false);
		if (flick) {
			ClientPlayNetworking.send(new Smoking.StopPayload());
			p.displayClientMessage(Component.translatable("message.ballisticmissiles.cigarettes.flicked").withStyle(ChatFormatting.GRAY), true);
		}
	}

	// ------------------------------------------------------------------ in the world

	private static boolean ownView(Minecraft mc, Player player) {
		return player == mc.player && mc.options.getCameraType().isFirstPerson();
	}

	/** A camera-space point of your own first-person view, in the world (moved out by the field of view, as the hand pass draws it). */
	private static Vec3 fromView(Minecraft mc, Vector3f local) {
		Camera camera = mc.gameRenderer.getMainCamera();
		float fov = mc.options.fov().get().floatValue() * mc.player.getFieldOfViewModifier(true, mc.options.fovEffectScale().get().floatValue());
		float k = (float) (Math.tan(Math.toRadians(fov * 0.5)) / Math.tan(Math.toRadians(35.0)));
		Vector3f w = camera.rotation().transform(new Vector3f(local.x * k, local.y * k, local.z));
		return camera.position().add(w.x, w.y, w.z);
	}

	/** Where someone else's cigarette is and which way it points (its mesh runs along +z from the filter). */
	private static Quaternionf place(Player player, float a, float partialTick, Vector3f outPos) {
		float r = raise(a);
		// at the lips, from the head's turn
		float yaw = Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
		float pitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());
		Vec3 eye = player.getEyePosition(partialTick);
		Quaternionf head = new Quaternionf().rotationY(-yaw * Mth.DEG_TO_RAD).rotateX(pitch * Mth.DEG_TO_RAD);
		Vector3f lips = head.transform(new Vector3f(-0.04F, -0.13F, 0.255F)).add((float) eye.x, (float) eye.y, (float) eye.z);
		Quaternionf qLips = new Quaternionf(head).rotateY(-0.18F).rotateX(0.32F);
		// down in the hand in front of the chest, from the body's turn
		float body = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
		Vec3 pos = player.getPosition(partialTick);
		Quaternionf torso = new Quaternionf().rotationY(-body * Mth.DEG_TO_RAD);
		float k = tap(a);
		Vector3f low = torso.transform(new Vector3f(-0.25F, (player.isCrouching() ? 0.72F : 0.92F) - 0.03F * k, 0.36F)).add((float) pos.x, (float) pos.y, (float) pos.z);
		Quaternionf qLow = new Quaternionf(torso).rotationTo(new Vector3f(0, 0, 1), torso.transform(new Vector3f(0.35F, 0.45F, 0.82F).normalize()));
		outPos.set(low).lerp(lips, r);
		return new Quaternionf(qLow).slerp(qLips, r);
	}

	private static Vec3 tip(Minecraft mc, Player player, float age, float partialTick) {
		if (ownView(mc, player)) {
			Pose p = own(age, player.level().getGameTime() + partialTick);
			return fromView(mc, new Vector3f(p.filter()).add(p.q().transform(new Vector3f(0.0F, 0.0F, SmokeKit.tipAt(burnt(player, age))))));
		}
		Vector3f p = new Vector3f();
		Quaternionf q = place(player, age, partialTick, p);
		Vector3f t = q.transform(new Vector3f(0.0F, 0.0F, SmokeKit.tipAt(burnt(player, age))));
		return new Vec3(p.x + t.x, p.y + t.y, p.z + t.z);
	}

	private static Vec3 mouth(Minecraft mc, Player player) {
		if (ownView(mc, player)) {
			return fromView(mc, new Vector3f(OWN_MOUTH).add(0.0F, 0.0F, -0.05F));
		}
		float yaw = player.yHeadRot;
		Quaternionf head = new Quaternionf().rotationY(-yaw * Mth.DEG_TO_RAD).rotateX(player.getXRot() * Mth.DEG_TO_RAD);
		Vector3f lips = head.transform(new Vector3f(0.0F, -0.13F, 0.3F));
		return player.getEyePosition().add(lips.x, lips.y, lips.z);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) {
			if (mc.level == null) {
				SMOKERS.clear();
			}
			return;
		}
		long now = mc.level.getGameTime();
		SMOKERS.entrySet().removeIf(e -> now - e.getValue().lit() > e.getValue().ticks() + 40);
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		for (Map.Entry<Integer, Smoker> e : SMOKERS.entrySet()) {
			if (!(mc.level.getEntity(e.getKey()) instanceof Player p) || p.isSpectator()) {
				continue;
			}
			float age = age(p, 0.0F);
			if (age < 0.0F) {
				continue;
			}
			int a = Math.round(age);
			boolean me = p == mc.player;
			double dist = p.getEyePosition().distanceTo(ear);
			float near = me ? 1.0F : (float) Math.max(0.0, 1.0 - dist / 14.0);
			for (int d : DRAGS) {
				if (a == d && near > 0.0F) {
					GunAudio.play(ModRegistry.SMOKE_DRAG, tip(mc, p, age, 0.0F), 0.4F * near, 0.95F + p.getRandom().nextFloat() * 0.1F);
				}
				if (a == d + DRAG_LEN + 3 && near > 0.0F) {
					GunAudio.play(ModRegistry.SMOKE_EXHALE, mouth(mc, p), 0.45F * near, 0.95F + p.getRandom().nextFloat() * 0.1F);
				}
				if (a == d + DRAG_LEN && me) {
					Stress.relief(0.07F);
				}
			}
			if (a == 12 && near > 0.0F) {
				GunAudio.play(ModRegistry.SMOKE_EXHALE, mouth(mc, p), 0.45F * near, 1.0F);
			}
			if (dist > 48.0) {
				continue;
			}
			if (exhaling(age)) {
				exhale(mc, p);
			} else if (a % 3 == 0) {
				// the thin thread off the ember
				Vec3 t = tip(mc, p, age, 0.0F);
				CloudParticle c = ClientEffects.cloud(false, t.x, t.y + 0.01, t.z, (p.getRandom().nextFloat() - 0.5F) * 0.002, 0.008, (p.getRandom().nextFloat() - 0.5F) * 0.002);
				if (c != null) {
					c.configure(45, 0.012F, 0.14F, 0x9AA6B8, 0xB8BEC8, 0.22F).physics(0.93F, 0.0012F).turbulence(0.0025F);
				}
			}
			// the ash knocked off falls away
			for (int t : TAPS) {
				if (a == t + 3) {
					Vec3 tp = tip(mc, p, age, 0.0F);
					CloudParticle c = ClientEffects.cloud(false, tp.x, tp.y, tp.z, 0.0, -0.05, 0.0);
					if (c != null) {
						c.configure(14, 0.012F, 0.03F, 0x8A8884, 0x9A9894, 0.8F).physics(0.98F, -0.004F);
					}
				}
			}
		}
	}

	private static void exhale(Minecraft mc, Player p) {
		Vec3 m = mouth(mc, p);
		Vec3 look = p.getViewVector(1.0F);
		for (int i = 0; i < 2; i++) {
			double speed = 0.045 + p.getRandom().nextFloat() * 0.035;
			Vec3 v = look.scale(speed).add((p.getRandom().nextFloat() - 0.5F) * 0.02, -0.006 + p.getRandom().nextFloat() * 0.01, (p.getRandom().nextFloat() - 0.5F) * 0.02);
			Vec3 at = m.add(look.scale(p == mc.player ? 0.12 : 0.02));
			CloudParticle c = ClientEffects.cloud(false, at.x, at.y, at.z, v.x, v.y, v.z);
			if (c != null) {
				c.configure(55 + p.getRandom().nextInt(20), 0.05F, 0.55F, 0xD4D8DC, 0xC4C8CE, 0.32F).physics(0.9F, 0.0018F).turbulence(0.004F);
			}
		}
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || SMOKERS.isEmpty()) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.matrices();
		for (Map.Entry<Integer, Smoker> e : SMOKERS.entrySet()) {
			if (!(mc.level.getEntity(e.getKey()) instanceof Player p) || p.isSpectator() || p.isInvisible()) {
				continue;
			}
			float age = age(p, partial);
			if (age < 0.0F || p.distanceToSqr(cam) > 64.0 * 64.0) {
				continue;
			}
			float burnt = burnt(p, age);
			float glow = glow(age) * (0.9F + 0.1F * Mth.sin(age * 1.7F));
			Vec3 tipAt;
			if (ownView(mc, p)) {
				// drawn by the hand pass; only its glow is lit here
				tipAt = tip(mc, p, age, partial);
			} else {
				Vector3f pos = new Vector3f();
				Quaternionf q = place(p, age, partial, pos);
				int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos.x, pos.y, pos.z));
				poseStack.pushPose();
				poseStack.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
				poseStack.mulPose(q);
				SmokeKit.submit(poseStack, context.commandQueue(), SmokeKit.cigarette(burnt), light);
				SmokeKit.ember(poseStack, context.commandQueue(), burnt, glow);
				poseStack.popPose();
				Vector3f t = q.transform(new Vector3f(0.0F, 0.0F, SmokeKit.tipAt(burnt)));
				tipAt = new Vec3(pos.x + t.x, pos.y + t.y, pos.z + t.z);
			}
			DynamicLights.lamp(p.getId() * 16L + 9L, tipAt, 0xFF6A20, 0.08F + 0.3F * glow, 1.2F + 1.2F * glow);
		}
	}
}
