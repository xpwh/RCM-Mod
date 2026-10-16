package de.rcm.ballistic.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.effect.DynamicLights;
import de.rcm.ballistic.client.effect.Stress;
import de.rcm.ballistic.client.gun.GunAudio;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.item.Smoking;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Everyone smoking that you can see: the cigarette between their lips, burning down over its three quarters
 * of a minute, its ember glowing - bright with every drag, a dull red in between, lighting the face at night
 * - a thin blue thread of smoke curling off it, and after each drag the grey breath of smoke blown out. You
 * hear the crackle of the drag and the breath out from where they stand. Your own cigarette sits at the
 * bottom of your view, and its smoke drifts across it; every drag settles your nerves a little.
 */
public final class SmokingClient {
	/** When the next drags are taken (ticks after lighting); the first one was taken lighting up. */
	private static final int[] DRAGS = {150, 270, 390, 510, 630, 750, 860};
	private static final int DRAG_LEN = 28;
	/** How long after a drag starts the smoke is breathed out. */
	private static final int EXHALE_AFTER = 42;
	/** Camera-relative position of the lips in your own first-person view (x right, y up, -z ahead). */
	public static final Vector3f OWN_MOUTH = new Vector3f(0.0F, -0.1F, -0.1F);
	/** How much of the cigarette is in the mouth (out of your own sight). */
	public static final float IN_LIPS = 0.01F;

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
		ClientTickEvents.END_CLIENT_TICK.register(SmokingClient::tick);
		WorldRenderEvents.END_MAIN.register(SmokingClient::render);
	}

	public static boolean isSmoking(Player player) {
		return age(player, 0.0F) >= 0.0F;
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

	/** How brightly the ember glows (0 smouldering .. 1 a drag being taken). */
	private static float glow(float age) {
		float g = age < 12.0F ? 1.0F - age / 12.0F : 0.0F; // still bright from the first drag
		for (int d : DRAGS) {
			float t = age - d;
			if (t > 0.0F && t < DRAG_LEN + 10.0F) {
				g = Math.max(g, t < 4.0F ? t / 4.0F : t < DRAG_LEN ? 1.0F : 1.0F - (t - DRAG_LEN) / 10.0F);
			}
		}
		return g;
	}

	private static boolean ownView(Minecraft mc, Player player) {
		return player == mc.player && mc.options.getCameraType().isFirstPerson();
	}

	/**
	 * Where the cigarette sits and which way it points (its mesh runs along +z from the filter). For others:
	 * at the lips, from the head's turn. For yourself in first person: fixed at the bottom of the view (moved
	 * out by the field of view, so it stays where the hand pass puts it while you light up).
	 */
	private static Quaternionf place(Minecraft mc, Player player, float partialTick, Vector3f outPos) {
		if (ownView(mc, player)) {
			Camera camera = mc.gameRenderer.getMainCamera();
			Vector3f local = new Vector3f(OWN_MOUTH).add(new Quaternionf().rotationY(Mth.PI + 0.1F).rotateX(0.22F).transform(new Vector3f(0.0F, 0.0F, -IN_LIPS)));
			float fov = mc.options.fov().get().floatValue() * mc.player.getFieldOfViewModifier(true, mc.options.fovEffectScale().get().floatValue());
			float k = (float) (Math.tan(Math.toRadians(fov * 0.5)) / Math.tan(Math.toRadians(35.0)));
			local.x *= k;
			local.y *= k;
			Vector3f w = camera.rotation().transform(local);
			Vec3 at = camera.position().add(w.x, w.y, w.z);
			outPos.set((float) at.x, (float) at.y, (float) at.z);
			return new Quaternionf(camera.rotation()).rotateY(Mth.PI + 0.1F).rotateX(0.05F);
		}
		float yaw = Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
		float pitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());
		Vec3 eye = player.getEyePosition(partialTick);
		Quaternionf head = new Quaternionf().rotationY(-yaw * Mth.DEG_TO_RAD).rotateX(pitch * Mth.DEG_TO_RAD);
		Vector3f lips = head.transform(new Vector3f(-0.04F, -0.13F, 0.255F));
		outPos.set((float) (eye.x + lips.x), (float) (eye.y + lips.y), (float) (eye.z + lips.z));
		// drooping from the corner of the mouth
		return head.rotateY(-0.18F).rotateX(0.32F);
	}

	private static Vec3 tip(Minecraft mc, Player player, float age, float partialTick) {
		Vector3f p = new Vector3f();
		Quaternionf q = place(mc, player, partialTick, p);
		Vector3f t = q.transform(new Vector3f(0.0F, 0.0F, SmokeKit.tipAt(burnt(player, age))));
		return new Vec3(p.x + t.x, p.y + t.y, p.z + t.z);
	}

	private static Vec3 mouth(Minecraft mc, Player player) {
		Vector3f p = new Vector3f();
		place(mc, player, 1.0F, p);
		return new Vec3(p.x, p.y, p.z);
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
				if (a == d + EXHALE_AFTER && near > 0.0F) {
					GunAudio.play(ModRegistry.SMOKE_EXHALE, mouth(mc, p), 0.45F * near, 0.95F + p.getRandom().nextFloat() * 0.1F);
				}
				if (a == d + DRAG_LEN && me) {
					Stress.relief(0.07F);
				}
			}
			if (a == 6 && near > 0.0F) {
				GunAudio.play(ModRegistry.SMOKE_EXHALE, mouth(mc, p), 0.45F * near, 1.0F);
			}
			if (dist > 48.0) {
				continue;
			}
			// the breath of smoke blown out
			boolean exhaling = age >= 6.0F && age < 22.0F;
			for (int d : DRAGS) {
				exhaling |= age >= d + EXHALE_AFTER && age < d + EXHALE_AFTER + 18;
			}
			if (exhaling) {
				exhale(mc, p);
			} else if (a % 3 == 0) {
				// the thin thread off the ember
				Vec3 t = tip(mc, p, age, 0.0F);
				CloudParticle c = ClientEffects.cloud(false, t.x, t.y + 0.01, t.z, (p.getRandom().nextFloat() - 0.5F) * 0.002, 0.008, (p.getRandom().nextFloat() - 0.5F) * 0.002);
				if (c != null) {
					c.configure(45, 0.012F, 0.14F, 0x9AA6B8, 0xB8BEC8, 0.22F).physics(0.93F, 0.0012F).turbulence(0.0025F);
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
			if (p == mc.player && mc.player.isUsingItem() && mc.player.getUseItem().is(ModRegistry.CIGARETTES)) {
				continue;
			}
			Vector3f pos = new Vector3f();
			Quaternionf q = place(mc, p, partial, pos);
			float burnt = burnt(p, age);
			float glow = glow(age) * (0.9F + 0.1F * Mth.sin(age * 1.7F));
			int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos.x, pos.y, pos.z));
			poseStack.pushPose();
			poseStack.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
			poseStack.mulPose(q);
			SmokeKit.submit(poseStack, context.commandQueue(), SmokeKit.cigarette(burnt), light);
			SmokeKit.ember(poseStack, context.commandQueue(), burnt, glow);
			poseStack.popPose();
			Vector3f t = q.transform(new Vector3f(0.0F, 0.0F, SmokeKit.tipAt(burnt)));
			DynamicLights.lamp(p.getId() * 16L + 9L, new Vec3(pos.x + t.x, pos.y + t.y, pos.z + t.z), 0xFF6A20, 0.08F + 0.3F * glow, 1.2F + 1.2F * glow);
		}
	}
}
