package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.effect.SmokeField;
import de.rcm.ballistic.client.render.ShotgunItemRenderer;
import de.rcm.ballistic.gun.GunState;
import de.rcm.ballistic.gun.ShotgunItem;
import de.rcm.ballistic.injury.Injuries;
import de.rcm.ballistic.network.ModNetworking.GunInputPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Client side of the pump shotgun: the trigger (left click), the heavy kick of a 12-gauge into the
 * shoulder and the muzzle flipping up, the slide racked back and forward by the left hand after every
 * shot, bringing the bead to the eye, and the loading - the gun rolled over so its loading port faces
 * you, the left hand going down to the pouch for a shell and thumbing it up into the tube, one after
 * another, then for an empty gun the slide racked to chamber the first.
 */
public final class ShotgunClient {
	private static float aim;
	private static float prevAim;
	private static float load;
	private static float prevLoad;
	private static float sprint;
	private static float prevSprint;
	private static boolean held;
	private static long equipTick = -100L;
	/** Our own shot and dry click (game ticks), as the client saw them. */
	private static long lastFire = -100L;
	private static long dryTick = -100L;

	/** The springs and poses: shared with the offline animation studio. */
	private static final ShotgunMotion MOTION = new ShotgunMotion();
	private static int pendingKicks;
	private static float kickRoll;
	// moving: landing, the stride, a step to the side
	private static long landTick = -100L;
	private static float landHard;
	private static boolean wasOnGround = true;
	private static double fallFrom;
	private static float walk;
	private static float prevWalk;
	private static float strafe;
	private static float prevStrafe;
	/** The last tick anything happened with the gun (a shot, loading, aiming, walking): long idle, a look at it. */
	private static long busyTick;
	private static float lastFrame = -1.0F;
	private static float lastYaw;
	private static float lastPitch;

	private ShotgunClient() {
	}

	public static void init() {
		// aiming (the right button held) is "using" the shotgun, and while an item is in use Minecraft swallows
		// every left click: take them first, they are the trigger
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (mc.player != null && mc.screen == null && holding(mc.player) && mc.player.isUsingItem()) {
				boolean fired = false;
				while (mc.options.keyAttack.consumeClick()) {
					if (!fired) {
						trigger(mc.player);
						fired = true;
					}
				}
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(ShotgunClient::tick);
		HudElementRegistry.addLast(BallisticMissiles.id("shotgun_ammo"), (graphics, tickCounter) -> hud(graphics));
	}

	public static boolean holding(Player player) {
		return player != null && player.getMainHandItem().getItem() instanceof ShotgunItem;
	}

	/** 0 at the hip, 1 with the bead on target. */
	public static float aimProgress(float partialTick) {
		float a = Mth.lerp(partialTick, prevAim, aim);
		return a * a * (3.0F - 2.0F * a);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static float partial() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/** One stroke of the slide: snapped back to hit the stop at {@code back}, driven home at {@code forward} (when the clacks sound). */
	private static float stroke(float t, float back, float forward) {
		float s = 1.6F;
		if (t < back - s || t > forward) {
			return 0.0F;
		}
		if (t < back) {
			float f = (t - back + s) / s;
			return f * f * (2.0F - f) * 1.0F; // fast at the start, braking into the stop
		}
		if (t < forward - s) {
			// a little bounce off the rear stop
			return 1.0F - 0.04F * Mth.sin(Math.min(1.0F, (t - back) / 2.0F) * Mth.PI);
		}
		float f = (t - forward + s) / s;
		return 1.0F - f * f * (2.0F - f);
	}

	/** How far back the forend is on this gun right now (0 forward, 1 fully back). */
	public static float pumpFor(ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return 0.0F;
		}
		GunState s = ShotgunItem.state(stack);
		float now = mc.level.getGameTime() + partial();
		if (s.reloading() && s.reloadKind() == 2) {
			return stroke(now - s.reloadStart(), ShotgunItem.RACK_BACK, ShotgunItem.RACK_FORWARD);
		}
		return stroke(now - s.lastShot(), ShotgunItem.PUMP_BACK, ShotgunItem.PUMP_FORWARD);
	}

	/** Everything that moves on this gun right now, for its model. */
	public static ShotgunItemRenderer.Pose poseFor(ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return ShotgunItemRenderer.Pose.REST;
		}
		GunState s = ShotgunItem.state(stack);
		float since = mc.level.getGameTime() + partial() - s.lastShot();
		// the hull leaves the port as the bolt comes back off the shell
		float eject = since - (ShotgunItem.PUMP_BACK - 0.4F);
		return new ShotgunItemRenderer.Pose(pumpFor(stack), s.rounds() > 0, eject >= 0.0F && eject < 7.0F ? eject : -1.0F);
	}

	// ------------------------------------------------------------------ input

	/** Left click with the shotgun in hand. */
	public static void trigger(LocalPlayer player) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || Injuries.get(player).incapacitated()) {
			return;
		}
		long now = mc.level.getGameTime();
		GunState s = ShotgunItem.state(player.getMainHandItem());
		if (now - lastFire < ShotgunItem.READY || now - s.lastShot() < ShotgunItem.READY || (s.reloading() && s.reloadKind() == 2)) {
			return; // still working the slide
		}
		ClientPlayNetworking.send(new GunInputPayload(GunInputPayload.SHOTGUN_FIRE));
		if (s.rounds() <= 0) {
			dryTick = now;
			lastFire = now - ShotgunItem.READY + 5;
			return;
		}
		lastFire = now;
		GunAudio.shotgun(AkClient.muzzle(player, 1.0F), true);
		kickRoll = ClientEffects.rand() * 2.0F - 0.7F;
		pendingKicks++;
		// the muzzle flips up and the view with it
		float climb = (2.6F + ClientEffects.rand() * 0.8F) * (ShotgunItem.isAiming(player) ? 0.75F : 1.0F);
		player.setXRot(Mth.clamp(player.getXRot() - climb, -90.0F, 90.0F));
		player.setYRot(player.getYRot() + (ClientEffects.rand() - 0.4F) * 0.8F);
		ClientEffects.addShake(0.32F);
		Vec3 muzzle = AkClient.muzzle(player, 1.0F);
		Vec3 look = player.getLookAngle();
		de.rcm.ballistic.client.effect.DynamicLights.flash(muzzle.add(look.scale(0.4)), 0xFFC070, 1.2F, 12.0F, 60.0F + ClientEffects.rand() * 20.0F);
		Vec3 m = muzzle.add(look.scale(0.3));
		SmokeField.puff(m.x, m.y, m.z, look.x * 0.18, look.y * 0.18 + 0.01, look.z * 0.18, 200 + (int) (ClientEffects.rand() * 80), 0.12F, 1.1F,
			0xDAD6D0, 0.3F, 0.6F, 0.0008F);
	}

	/** Someone's shotgun went off (our own is heard and seen already). */
	static void remoteShot(de.rcm.ballistic.network.ModNetworking.GunshotPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || (mc.player != null && p.shooter() == mc.player.getId())) {
			return;
		}
		Vec3 muzzle = new Vec3(p.x(), p.y(), p.z());
		Vec3 look = new Vec3(p.dx(), p.dy(), p.dz());
		GunAudio.shotgun(muzzle, false);
		de.rcm.ballistic.client.effect.DynamicLights.flash(muzzle.add(look.scale(0.4)), 0xFFC070, 1.2F, 12.0F, 60.0F + ClientEffects.rand() * 20.0F);
		if (muzzle.distanceTo(mc.gameRenderer.getMainCamera().position()) < 96.0) {
			Vec3 m = muzzle.add(look.scale(0.3));
			SmokeField.puff(m.x, m.y, m.z, look.x * 0.18, look.y * 0.18 + 0.01, look.z * 0.18, 200 + (int) (ClientEffects.rand() * 80), 0.12F, 1.1F,
				0xDAD6D0, 0.3F, 0.6F, 0.0008F);
		}
	}

	/** R with the shotgun in hand. */
	static void reload(LocalPlayer player) {
		ClientPlayNetworking.send(new GunInputPayload(GunInputPayload.SHOTGUN_LOAD));
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		prevAim = aim;
		prevLoad = load;
		prevSprint = sprint;
		if (player == null || mc.level == null) {
			held = false;
			return;
		}
		long now = mc.level.getGameTime();
		boolean has = holding(player);
		if (has && !held) {
			equipTick = now;
		}
		held = has;
		GunState s = has ? ShotgunItem.state(player.getMainHandItem()) : null;
		boolean loading = s != null && s.reloading() && s.reloadKind() == 1;
		boolean aiming = s != null && ShotgunItem.isAiming(player) && !s.reloading();
		aim = aiming ? Math.min(1.0F, aim + 0.2F) : Math.max(0.0F, aim - 0.22F);
		load = loading ? Math.min(1.0F, load + 0.17F) : Math.max(0.0F, load - 0.14F);
		boolean busy = s != null && (s.reloading() || now - s.lastShot() < ShotgunItem.READY);
		float target = has && player.isSprinting() && !busy && !aiming ? 1.0F : 0.0F;
		sprint += (target - sprint) * 0.25F;
		if (has) {
			if (!player.onGround()) {
				if (wasOnGround) {
					fallFrom = player.getY();
				}
				fallFrom = Math.max(fallFrom, player.getY());
			} else if (!wasOnGround) {
				landTick = now;
				landHard = (float) Mth.clamp((fallFrom - player.getY()) / 3.0, 0.3, 1.0);
			}
			wasOnGround = player.onGround();
			prevWalk = walk;
			prevStrafe = strafe;
			var vel = player.getDeltaMovement();
			float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
			double side = vel.x * Math.cos(yawRad) + vel.z * Math.sin(yawRad);
			strafe += ((float) Mth.clamp(side / 0.13, -1.0, 1.0) - strafe) * 0.18F;
			walk += ((player.onGround() ? (float) Mth.clamp(vel.horizontalDistance() / 0.12, 0.0, 1.0) : 0.0F) - walk) * 0.2F;
			if (busy || aiming || loading || walk > 0.1F || mc.options.keyAttack.isDown() || now - equipTick < 20) {
				busyTick = now;
			}
		}
		// shouldering it: the sling and the gun against your jacket
		if (has && now == equipTick) {
			GunAudio.play(ModRegistry.SHOTGUN_HANDLE, player.getEyePosition(), 0.45F, 0.95F + ClientEffects.rand() * 0.1F);
		}
	}

	// ------------------------------------------------------------------ first person

	private static void physics(Minecraft mc, float now, float partialTick) {
		var player = mc.player;
		float yaw = player.getViewYRot(partialTick);
		float pitch = player.getViewXRot(partialTick);
		float dt = now - lastFrame;
		if (lastFrame < 0.0F || dt < 0.0F || dt > 2.0F) {
			dt = 0.0F;
			lastYaw = yaw;
			lastPitch = pitch;
		}
		lastFrame = now;
		float aiming = aimProgress(partialTick);
		while (pendingKicks > 0) {
			pendingKicks--;
			MOTION.kick(1.0F - 0.3F * aiming - (player.isCrouching() ? 0.15F : 0.0F), kickRoll, ClientEffects.rand());
		}
		float dy = Mth.wrapDegrees(yaw - lastYaw);
		float dp = pitch - lastPitch;
		lastYaw = yaw;
		lastPitch = pitch;
		MOTION.step(dt, dy, dp, aiming);
	}

	/** What the gun is doing this frame, for {@link ShotgunMotion}. */
	private static ShotgunMotion.Frame frame(Minecraft mc, float partialTick, ItemStack stack) {
		float now = mc.level.getGameTime() + partialTick;
		GunState state = ShotgunItem.state(stack);
		boolean racking = state.reloading() && state.reloadKind() == 2;
		float slide = racking ? now - state.reloadStart() : now - state.lastShot();
		float shell = state.reloading() && state.reloadKind() == 1 ? now - state.reloadStart() : -1.0F;
		return new ShotgunMotion.Frame(now, aimProgress(partialTick), pumpFor(stack), slide, racking, now - dryTick,
			ShotgunMotion.smooth(Mth.lerp(partialTick, prevLoad, load)), shell, now - busyTick, now - equipTick,
			ShotgunMotion.smooth(Mth.lerp(partialTick, prevSprint, sprint)), mc.player.walkAnimation.position(partialTick),
			Mth.lerp(partialTick, prevWalk, walk), Mth.lerp(partialTick, prevStrafe, strafe), now - landTick, landHard,
			Fatigue.level(), Fatigue.breathPhase(partialTick), Injuries.get(mc.player).arm());
	}

	public static void transform(PoseStack poseStack, float partialTick, ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		physics(mc, mc.level.getGameTime() + partialTick, partialTick);
		MOTION.apply(poseStack, frame(mc, partialTick, stack));
	}

	private static float jolt(float t, float length) {
		if (t < 0.0F || t > length) {
			return 0.0F;
		}
		float f = t / length;
		return Mth.sin(Math.min(1.0F, f * 3.0F) * Mth.PI * 0.5F) * (1.0F - f) * (1.0F - f);
	}

	private static Vector3f lerp(Vector3f a, Vector3f b, float t) {
		return new Vector3f(a).lerp(b, smooth(t));
	}

	/** An arc from {@code a} to {@code b}, dipping on the way (a hand going down to the belt and back). */
	private static Vector3f swing(Vector3f a, Vector3f b, float t) {
		Vector3f p = lerp(a, b, t);
		return p.add(0.0F, -0.04F * Mth.sin(Mth.clamp(t, 0.0F, 1.0F) * Mth.PI), 0.0F);
	}

	/** The arms holding the gun, and the shell in the left hand while loading. */
	public static void renderArms(AbstractClientPlayer player, ItemStack stack, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light,
		float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		ShotgunMotion.Hands hands = ShotgunMotion.hands(frame(mc, partialTick, stack));
		Vector3f left = hands.left();
		Vector3f shellBase = hands.shell();
		float shellTilt = hands.shellTilt();
		poseStack.pushPose();
		poseStack.translate(-3.2F / 16.0F, 2.4F / 16.0F, 0.5F / 16.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(3.0F));
		if (shellBase != null) {
			poseStack.pushPose();
			poseStack.translate(shellBase.x, shellBase.y, shellBase.z);
			poseStack.mulPose(Axis.XP.rotationDegrees(shellTilt));
			ShotgunItemRenderer.submitShell(poseStack, collector, light);
			poseStack.popPose();
		}
		AkArms.draw(player, base, poseStack, collector, light, hands.right(), left, hands.rightShoulder(), AkArms.LEFT_SHOULDER, hands.rightPole(),
			AkArms.LEFT_POLE);
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ ammo

	private static void hud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (!holding(player) || mc.options.hideGui) {
			return;
		}
		GunState s = ShotgunItem.state(player.getMainHandItem());
		int shells = player.getInventory().countItem(ModRegistry.SHOTGUN_SHELL);
		String line1 = s.rounds() + " / " + ShotgunItem.CAPACITY;
		String line2 = "12/70 00-BUCK   " + shells;
		int w = g.guiWidth();
		int h = g.guiHeight();
		int color = s.rounds() == 0 ? 0xFFFF5040 : s.rounds() <= 2 ? 0xFFFFC040 : 0xFFF0F0F0;
		g.drawString(mc.font, line1, w - 8 - mc.font.width(line1), h - 34, color, true);
		g.drawString(mc.font, line2, w - 8 - mc.font.width(line2), h - 22, 0xFFB8B8B8, true);
		// the shells in the gun, drawn as little red hulls
		for (int i = 0; i < ShotgunItem.CAPACITY; i++) {
			int x = w - 8 - mc.font.width(line1) - 10 - i * 5;
			int c = i < s.rounds() ? 0xFFC02020 : 0x60404040;
			g.fill(x, h - 33, x + 3, h - 28, c);
			g.fill(x, h - 28, x + 3, h - 26, i < s.rounds() ? 0xFFD0A040 : 0x60404040);
		}
		if (s.reloading()) {
			String r = s.reloadKind() == 2 ? "Durchladen..." : "Laden...";
			g.drawString(mc.font, r, w - 8 - mc.font.width(r), h - 46, 0xFFFFE080, true);
		}
	}
}
