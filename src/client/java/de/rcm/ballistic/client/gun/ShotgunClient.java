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
	/** Line of sight (item space): the groove on top of the receiver to the brass bead at the muzzle. */
	private static final Vector3f NOTCH = new Vector3f(0.0F, ShotgunItemRenderer.SIGHT_REAR_Y, 0.05F);
	private static final Vector3f SIGHT_LINE = new Vector3f(0.0F, ShotgunItemRenderer.BEAD_Y - ShotgunItemRenderer.SIGHT_REAR_Y,
		ShotgunItemRenderer.MUZZLE_Z + 0.014F - 0.05F).normalize();
	/** Cheek down on the comb, the eye a hand's width behind the receiver. */
	private static final Vector3f EYE = new Vector3f(NOTCH).sub(new Vector3f(SIGHT_LINE).mul(0.2F));
	private static final Vector3f CAMERA = new Vector3f(-0.56F, 0.52F, 0.72F);

	/** The hands (item space): right fist round the wrist of the stock, left under the forend. */
	private static final Vector3f GRIP = new Vector3f(0.0F, 0.065F, 0.115F);
	private static final Vector3f FOREND = new Vector3f(-0.004F, 0.035F, -0.415F);
	/** Fingers under the loading port, a shell pushed up and in; the pouch on the belt (gun rolled over). */
	private static final Vector3f PORT = new Vector3f(-0.002F, 0.05F, -0.075F);
	private static final Vector3f APPROACH = new Vector3f(-0.01F, 0.0F, 0.0F);
	private static final Vector3f POUCH = new Vector3f(-0.16F, -0.3F, 0.16F);

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

	private static final class Spring {
		final float stiffness;
		final float damping;
		float x;
		float v;

		Spring(float frequency, float ratio) {
			this.stiffness = frequency * frequency;
			this.damping = 2.0F * ratio * frequency;
		}

		void step(float dt) {
			int n = Math.max(1, (int) Math.ceil(dt / 0.1F));
			float h = dt / n;
			for (int i = 0; i < n; i++) {
				this.v += (-this.stiffness * this.x - this.damping * this.v) * h;
				this.x += this.v * h;
			}
		}
	}

	// a 12-gauge kicks hard: back into the shoulder, the muzzle flipping high, a roll
	private static final Spring BACK = new Spring(1.0F, 0.55F);
	private static final Spring PITCH = new Spring(0.85F, 0.5F);
	private static final Spring ROLL = new Spring(0.9F, 0.45F);
	private static final Spring LAG_YAW = new Spring(0.7F, 0.55F);
	private static final Spring LAG_PITCH = new Spring(0.75F, 0.6F);
	private static int pendingKicks;
	private static float kickRoll;
	/** A 12-gauge also shoves the gun to the side, not only up: and the shooter's upper body rocks with it. */
	private static final Spring YAW = new Spring(0.8F, 0.45F);
	private static final Spring LIFT = new Spring(0.7F, 0.6F);
	/** Coming up onto the shoulder: it swings up a little past the line and settles back. */
	private static final Spring SETTLE = new Spring(1.15F, 0.4F);
	private static boolean wasAimed;
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
			float braced = 1.0F - 0.3F * aiming - (player.isCrouching() ? 0.15F : 0.0F);
			// a 12-gauge: a hard shove straight back into the shoulder, the muzzle flipping up and out to the
			// right, the upper body rocking up with it
			BACK.v += 0.38F * braced;
			PITCH.v += 17.0F * braced * (0.9F + 0.2F * ClientEffects.rand());
			ROLL.v += 6.0F * kickRoll * braced;
			YAW.v += (3.0F + 2.0F * ClientEffects.rand()) * braced;
			LIFT.v += 0.06F * braced;
		}
		float dy = Mth.wrapDegrees(yaw - lastYaw);
		float dp = pitch - lastPitch;
		lastYaw = yaw;
		lastPitch = pitch;
		float hold = 1.0F - 0.85F * aiming;
		LAG_YAW.v += Mth.clamp(dy, -25.0F, 25.0F) * 0.1F * hold;
		LAG_PITCH.v += Mth.clamp(dp, -25.0F, 25.0F) * 0.1F * hold;
		boolean aimed = aiming > 0.97F;
		if (aimed && !wasAimed) {
			SETTLE.v += 1.0F; // the stock beds into the shoulder
		}
		wasAimed = aimed;
		for (Spring sp : new Spring[] {BACK, PITCH, ROLL, LAG_YAW, LAG_PITCH, YAW, LIFT, SETTLE}) {
			sp.step(dt);
		}
		LAG_YAW.x = Mth.clamp(LAG_YAW.x, -8.0F, 8.0F);
		LAG_PITCH.x = Mth.clamp(LAG_PITCH.x, -7.0F, 7.0F);
	}

	public static void transform(PoseStack poseStack, float partialTick, ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		float now = mc.level.getGameTime() + partialTick;
		GunState state = ShotgunItem.state(stack);
		physics(mc, now, partialTick);

		// the bead brought onto the middle of the view over the groove in the receiver
		float a = aimProgress(partialTick);
		if (a > 0.0F) {
			Quaternionf display = Axis.YP.rotationDegrees(3.0F);
			Vector3f line = display.transform(new Vector3f(SIGHT_LINE));
			Quaternionf toView = new Quaternionf().rotationTo(line, new Vector3f(0.0F, 0.0F, -1.0F));
			Vector3f eye = display.transform(new Vector3f(EYE)).add(-3.2F / 16.0F, 2.4F / 16.0F, 0.5F / 16.0F);
			Vector3f shift = new Vector3f(CAMERA).sub(toView.transform(new Vector3f(eye)));
			poseStack.translate(shift.x * a, shift.y * a, shift.z * a);
			poseStack.mulPose(new Quaternionf().slerp(toView, a));
			float arc = Mth.sin(a * Mth.PI);
			poseStack.translate(0.0F, -0.025F * arc, 0.02F * arc);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-7.0F * arc));
		}

		// its weight lagging behind the view
		poseStack.translate(-LAG_YAW.x * 0.0024F, LAG_PITCH.x * 0.002F, 0.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(LAG_YAW.x * 0.55F));
		poseStack.mulPose(Axis.XP.rotationDegrees(LAG_PITCH.x * 0.5F));

		// breathing, and a wounded arm's tremor
		float tired = Fatigue.level();
		float breath = Fatigue.breathPhase(partialTick);
		float steady = 1.0F - 0.8F * a;
		int arm = Injuries.get(mc.player).arm();
		if (arm > 0) {
			poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 1.7F) * 0.35F + Mth.sin(now * 0.43F) * 0.6F) * arm));
			poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 1.3F) * 0.3F + Mth.sin(now * 0.37F + 1.0F) * 0.55F) * arm));
		}
		poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 0.045F) * 0.4F + Mth.sin(breath) * 1.7F * tired) * steady));
		poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 0.031F) * 0.35F + Mth.cos(breath * 0.5F) * 1.0F * tired) * steady));

		// the gun's weight in the step: a slow heave up and down and a lean into a sidestep (much less shouldered)
		float hipHold = 1.0F - 0.8F * a;
		float w = Mth.lerp(partialTick, prevWalk, walk) * hipHold * (1.0F - smooth(Mth.lerp(partialTick, prevSprint, sprint)));
		if (w > 0.01F) {
			float stride = mc.player.walkAnimation.position(partialTick) * 0.6F;
			poseStack.translate(0.0F, -Math.abs(Mth.sin(stride)) * 0.016F * w, -Math.abs(Mth.sin(stride)) * 0.006F * w);
			poseStack.mulPose(Axis.XP.rotationDegrees(Math.abs(Mth.sin(stride)) * 1.8F * w));
			poseStack.mulPose(Axis.YP.rotationDegrees(Mth.sin(stride) * 0.9F * w));
		}
		float lean = Mth.lerp(partialTick, prevStrafe, strafe) * hipHold;
		poseStack.mulPose(Axis.ZP.rotationDegrees(-5.0F * lean));
		// bedding into the shoulder: up past the line, settling back
		poseStack.translate(0.0F, 0.008F * SETTLE.x, 0.012F * SETTLE.x);
		poseStack.mulPose(Axis.XP.rotationDegrees(1.6F * SETTLE.x));

		// the pump, ridden out of the recoil: the shot still rocking the gun, the left arm rips the forend back -
		// the gun dragged down and canted over to the left with it, the empty hull flicking out with a twitch
		// of the receiver to the right at the stop - then slams it home, which throws the muzzle forward and
		// back down onto the target
		float pump = pumpFor(stack);
		boolean racking = state.reloading() && state.reloadKind() == 2;
		float since = racking ? now - state.reloadStart() : now - state.lastShot();
		float backAt = racking ? ShotgunItem.RACK_BACK : ShotgunItem.PUMP_BACK;
		float homeAt = racking ? ShotgunItem.RACK_FORWARD : ShotgunItem.PUMP_FORWARD;
		float span = racking ? ShotgunItem.RACK : ShotgunItem.READY;
		if (since >= 0.0F && since < span + 5.0F) {
			float work = smooth((since - backAt + 3.0F) / 2.0F) * (1.0F - smooth((since - homeAt) / 4.5F));
			float eject = jolt(since - backAt, 3.0F);
			float home = jolt(since - homeAt, 4.5F);
			float hip = 1.0F - 0.6F * a;
			poseStack.translate(0.012F * work * hip, -0.018F * work * hip, 0.02F * pump * hip - 0.016F * home);
			poseStack.mulPose(Axis.XP.rotationDegrees((-5.0F * pump + 3.0F * home) * hip));
			poseStack.mulPose(Axis.ZP.rotationDegrees((9.0F * work - 4.0F * eject) * hip));
			poseStack.mulPose(Axis.YP.rotationDegrees((-3.0F * pump + 1.5F * eject) * hip));
		}

		// a click instead of a bang: a jerk of surprise, then the gun turned over to look at the empty tube
		float dry = now - dryTick;
		if (dry >= 0.0F && dry < 24.0F) {
			float twitch = jolt(dry, 4.0F);
			float look = dry < 4.0F ? 0.0F : smooth((dry - 4.0F) / 5.0F) * (1.0F - smooth((dry - 15.0F) / 9.0F));
			poseStack.translate(-0.04F * look, 0.05F * look - 0.006F * twitch, -0.02F * look - 0.01F * twitch);
			poseStack.mulPose(Axis.XP.rotationDegrees(-2.0F * twitch + 8.0F * look));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-55.0F * look));
			poseStack.mulPose(Axis.YP.rotationDegrees(15.0F * look));
		}

		// loading: rolled right over, belly up and towards you so the loading port faces the eyes, muzzle raised;
		// each shell: the thumb drives it into the tube - the gun shoved up and away a little - and the hand
		// goes back to the pouch, the gun sinking back
		float l = smooth(Mth.lerp(partialTick, prevLoad, load));
		if (l > 0.0F) {
			float c = state.reloading() ? (now - state.reloadStart()) % ShotgunItem.SHELL_CYCLE : 0.0F;
			float push = state.reloading() ? jolt(c - ShotgunItem.SHELL_IN + 1.5F, 5.0F) : 0.0F;
			float reach = state.reloading() ? Mth.sin(Mth.clamp(c / (ShotgunItem.SHELL_IN - 1.5F), 0.0F, 1.0F) * Mth.PI) : 0.0F;
			poseStack.translate(-0.11F * l, 0.17F * l + 0.012F * push - 0.006F * reach, -0.09F * l - 0.016F * push);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-68.0F * l + 3.0F * push));
			poseStack.mulPose(Axis.XP.rotationDegrees(16.0F * l - 3.5F * push + 1.0F * reach));
			poseStack.mulPose(Axis.YP.rotationDegrees(30.0F * l + 2.0F * reach));
		}

		// a long while doing nothing: the gun lifted and turned to look along the receiver, then back
		float idle = now - busyTick - 240.0F;
		if (idle >= 0.0F && idle < 60.0F && a < 0.05F) {
			float look = smooth(idle / 12.0F) * (1.0F - smooth((idle - 40.0F) / 18.0F));
			float turn = Mth.sin(Mth.clamp((idle - 10.0F) / 28.0F, 0.0F, 1.0F) * Mth.PI);
			poseStack.translate(-0.05F * look, 0.06F * look, -0.03F * look);
			poseStack.mulPose(Axis.YP.rotationDegrees(28.0F * look + 10.0F * turn));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-20.0F * look - 25.0F * turn));
			poseStack.mulPose(Axis.XP.rotationDegrees(6.0F * look));
		}

		// drawn: slung off the back - it comes over the right shoulder, butt high and muzzle down, and swings
		// down into the hands
		float draw = 1.0F - smooth((now - equipTick) / 15.0F);
		if (draw > 0.0F) {
			float swing = Mth.sin(draw * Mth.PI * 0.5F);
			poseStack.translate(0.12F * draw, 0.2F * swing - 0.32F * draw * draw, 0.18F * draw);
			poseStack.mulPose(Axis.XP.rotationDegrees(-55.0F * swing));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-40.0F * draw));
			poseStack.mulPose(Axis.YP.rotationDegrees(25.0F * draw));
		}

		// sprinting: port arms - the gun carried diagonally across the chest, muzzle up and to the left,
		// bouncing with the stride
		float run = smooth(Mth.lerp(partialTick, prevSprint, sprint));
		if (run > 0.0F) {
			float stride = mc.player.walkAnimation.position(partialTick) * 0.3F;
			poseStack.translate(-0.1F * run, -0.03F * run + Math.abs(Mth.sin(stride * Mth.PI)) * 0.03F * run, 0.07F * run);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-48.0F * run + Mth.cos(stride * Mth.PI) * 3.0F * run));
			poseStack.mulPose(Axis.XP.rotationDegrees(18.0F * run + Mth.sin(stride * Mth.PI * 2.0F) * 2.0F * run));
			poseStack.mulPose(Axis.YP.rotationDegrees(22.0F * run));
		}

		// landing: knees give, the gun dips and comes back up
		float land = now - landTick;
		if (land >= 0.0F && land < 12.0F) {
			float dip = Mth.sin(land / 12.0F * Mth.PI) * (1.0F - land / 12.0F) * landHard;
			poseStack.translate(0.0F, -0.09F * dip, 0.012F * dip);
			poseStack.mulPose(Axis.XP.rotationDegrees(-7.0F * dip));
		}

		// recoil
		poseStack.translate(0.0F, 0.004F * Math.max(0.0F, PITCH.x) * 0.1F, Math.max(-0.03F, BACK.x));
		poseStack.translate(0.0F, LIFT.x, 0.0F);
		poseStack.mulPose(Axis.XP.rotationDegrees(PITCH.x));
		poseStack.mulPose(Axis.YP.rotationDegrees(-YAW.x));
		poseStack.mulPose(Axis.ZP.rotationDegrees(ROLL.x));
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
		float now = mc.level.getGameTime() + partialTick;
		GunState state = ShotgunItem.state(stack);
		Vector3f forend = new Vector3f(FOREND).add(0.0F, 0.0F, pumpFor(stack) * ShotgunItemRenderer.PUMP_TRAVEL);
		Vector3f left = forend;
		Vector3f shellBase = null;
		float shellTilt = 0.0F;
		float l = smooth(Mth.lerp(partialTick, prevLoad, load));
		if (l > 0.0F && state.reloading() && state.reloadKind() == 1) {
			float t = now - state.reloadStart();
			float c = t % ShotgunItem.SHELL_CYCLE;
			// down to the pouch (from the forend on the first one), up under the port, the shell pushed in
			Vector3f from = t < ShotgunItem.SHELL_CYCLE ? forend : PORT;
			Vector3f hand;
			if (c < 5.0F) {
				hand = swing(from, POUCH, c / 5.0F);
			} else if (c < 9.0F) {
				hand = swing(POUCH, APPROACH, (c - 5.0F) / 4.0F);
			} else if (c < ShotgunItem.SHELL_IN + 0.5F) {
				hand = lerp(APPROACH, PORT, (c - 9.0F) / 1.5F);
			} else {
				hand = new Vector3f(PORT);
			}
			left = new Vector3f(forend).lerp(hand, l);
			if (c > 3.0F && c < ShotgunItem.SHELL_IN + 0.5F) {
				shellTilt = c < 9.0F ? 22.0F : 22.0F * (1.0F - smooth((c - 9.0F) / 1.5F));
				shellBase = new Vector3f(left).add(0.002F, 0.03F, 0.03F);
				if (c >= 9.0F) {
					// the last of it: the shell going into the tube ahead of the fingers
					shellBase.add(0.0F, 0.012F * smooth((c - 9.0F) / 1.5F), -0.03F * smooth((c - 9.0F) / 1.5F));
				}
			}
		}

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
		AkArms.draw(player, base, poseStack, collector, light, GRIP, left, AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER);
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
