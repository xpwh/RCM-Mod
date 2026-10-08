package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.gun.GunState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The rifle in first person: a slow breathing sway, its weight lagging behind as you turn and
 * leaning into a sidestep, the stock punching back into the shoulder and the muzzle jumping with
 * every shot (springs, so a burst stacks up and shakes), and the reload - the rifle brought
 * in to the middle of the view and rolled so the magazine well faces you, the old magazine rocked out,
 * the new one rocked in and slapped home, then for an empty gun the rifle turned right and the
 * charging handle racked, and back up on target.
 */
public final class AkFirstPerson {
	private AkFirstPerson() {
	}

	/** Line of sight: from the rear notch to the tip of the front post, and the eye behind it (item space). */
	private static final Vector3f NOTCH = new Vector3f(0.0F, 0.175F, -0.126F);
	private static final Vector3f SIGHT_LINE = new Vector3f(0.0F, 0.01F, -0.518F).normalize();
	/** Cheek on the stock: the eye well behind the rear sight, as on the real rifle. */
	private static final Vector3f EYE = new Vector3f(NOTCH).sub(new Vector3f(SIGHT_LINE).mul(0.32F));
	/** Camera position in the hand space vanilla sets up for the main hand. */
	private static final Vector3f CAMERA = new Vector3f(-0.56F, 0.52F, 0.72F);

	// motion state, stepped every client tick
	private static float sprint;
	private static float prevSprint;
	private static long equipTick = -100L;
	private static long landTick = -100L;
	private static float landHard;
	private static long modeTick = -100L;
	private static int lastMode = -1;
	private static boolean held;
	private static boolean wasOnGround = true;
	private static double fallFrom;
	/** Random lean of the current shot's kick (set per shot). */
	static float kickSide;
	static float kickRoll;
	/** Shots fired since the springs last took them in. */
	private static int pendingKicks;
	private static float strafe;
	private static float prevStrafe;

	/**
	 * A damped spring (time in ticks): the rifle's recoil and its lag behind the view are a handful of
	 * these, kicked by shots and by turning, so a burst stacks up and shakes and every movement
	 * overshoots a little and settles, the way a rifle hanging off your shoulder and hands does.
	 */
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
			// semi-implicit Euler in small steps: stable at any frame rate
			int n = Math.max(1, (int) Math.ceil(dt / 0.1F));
			float h = dt / n;
			for (int i = 0; i < n; i++) {
				this.v += (-this.stiffness * this.x - this.damping * this.v) * h;
				this.x += this.v * h;
			}
		}
	}

	// recoil: straight back into the shoulder, muzzle up, a twist and roll to the side
	private static final Spring BACK = new Spring(1.25F, 0.5F);
	private static final Spring PITCH = new Spring(1.0F, 0.45F);
	private static final Spring YAW = new Spring(0.9F, 0.4F);
	private static final Spring ROLL = new Spring(1.1F, 0.4F);
	// the rifle's weight lagging behind the view as you turn
	private static final Spring LAG_YAW = new Spring(0.75F, 0.55F);
	private static final Spring LAG_PITCH = new Spring(0.8F, 0.6F);
	private static float lastFrame = -1.0F;
	private static float lastYaw;
	private static float lastPitch;

	/** A shot of our own: the springs take it in on the next frame. */
	static void kick() {
		pendingKicks++;
	}

	/** Called every client tick. */
	static void tick(Minecraft mc) {
		var player = mc.player;
		prevSprint = sprint;
		if (player == null || mc.level == null) {
			return;
		}
		long now = mc.level.getGameTime();
		ItemStack stack = player.getMainHandItem();
		boolean ak = stack.getItem() instanceof AkItem;
		if (ak && !held) {
			equipTick = now;
		}
		held = ak;
		GunState state = ak ? AkItem.state(stack) : null;
		boolean busy = state != null && state.reloading();
		boolean firing = now - AkClient.lastShotTick < 6;
		float target = ak && player.isSprinting() && !busy && !firing && !AkItem.isAiming(player) ? 1.0F : 0.0F;
		sprint += (target - sprint) * 0.25F;
		if (state != null) {
			if (lastMode >= 0 && state.mode() != lastMode) {
				modeTick = now;
			}
			lastMode = state.mode();
		}
		// landing from a jump or a fall dips the rifle
		if (!player.onGround()) {
			if (wasOnGround) {
				fallFrom = player.getY();
			}
			fallFrom = Math.max(fallFrom, player.getY());
		} else if (!wasOnGround) {
			landTick = now;
			landHard = (float) Mth.clamp((fallFrom - player.getY()) / 3.0, 0.25, 1.0);
		}
		wasOnGround = player.onGround();
		// stepping sideways leans the rifle into the step
		prevStrafe = strafe;
		float yaw = player.getYRot() * Mth.DEG_TO_RAD;
		double side = player.getDeltaMovement().x * Math.cos(yaw) + player.getDeltaMovement().z * Math.sin(yaw);
		strafe += ((float) Mth.clamp(side / 0.13, -1.0, 1.0) - strafe) * 0.2F;
	}

	/** Steps the springs to this frame and feeds them the shots fired and the turn of the view since the last. */
	private static void physics(Minecraft mc, float now, float partialTick) {
		var player = mc.player;
		float yaw = player.getViewYRot(partialTick);
		float pitch = player.getViewXRot(partialTick);
		float dt = now - lastFrame;
		if (lastFrame < 0.0F || dt < 0.0F || dt > 2.0F) {
			// first frame with the rifle in hand (again): nothing to catch up on
			dt = 0.0F;
			lastYaw = yaw;
			lastPitch = pitch;
		}
		lastFrame = now;
		float aiming = AkClient.aimProgress(partialTick);
		while (pendingKicks > 0) {
			pendingKicks--;
			float build = 1.0F + Math.min(AkClient.burst, 10) * 0.05F;
			float braced = 1.0F - 0.35F * aiming - (player.isCrouching() ? 0.15F : 0.0F);
			BACK.v += 0.15F * braced * (0.9F + 0.2F * (float) Math.random());
			PITCH.v += 6.0F * braced * build * (0.85F + 0.3F * (float) Math.random());
			YAW.v += -1.8F * kickSide * braced;
			ROLL.v += 3.2F * kickRoll * braced;
		}
		// the view turned: the rifle stays behind for a moment (a little, it is held tight)
		float dy = Mth.wrapDegrees(yaw - lastYaw);
		float dp = pitch - lastPitch;
		lastYaw = yaw;
		lastPitch = pitch;
		float hold = 1.0F - 0.55F * aiming;
		LAG_YAW.v += Mth.clamp(dy, -25.0F, 25.0F) * 0.09F * hold;
		LAG_PITCH.v += Mth.clamp(dp, -25.0F, 25.0F) * 0.09F * hold;
		for (Spring sp : new Spring[] {BACK, PITCH, YAW, ROLL, LAG_YAW, LAG_PITCH}) {
			sp.step(dt);
		}
		LAG_YAW.x = Mth.clamp(LAG_YAW.x, -7.0F, 7.0F);
		LAG_PITCH.x = Mth.clamp(LAG_PITCH.x, -6.0F, 6.0F);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	public static void transform(PoseStack poseStack, float partialTick, ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		float now = mc.level.getGameTime() + partialTick;
		GunState state = AkItem.state(stack);
		if (mc.player != null) {
			physics(mc, now, partialTick);
		}

		// up to the eye: rear notch and front post lined up on the middle of the view
		float aim = AkClient.aimProgress(partialTick);
		if (aim > 0.0F) {
			Quaternionf display = Axis.YP.rotationDegrees(3.0F);
			Vector3f line = display.transform(new Vector3f(SIGHT_LINE));
			Quaternionf toView = new Quaternionf().rotationTo(line, new Vector3f(0.0F, 0.0F, -1.0F));
			Vector3f eye = display.transform(new Vector3f(EYE)).add(-3.2F / 16.0F, 2.4F / 16.0F, 0.5F / 16.0F);
			Vector3f shift = new Vector3f(CAMERA).sub(toView.transform(new Vector3f(eye)));
			poseStack.translate(shift.x * aim, shift.y * aim, shift.z * aim);
			poseStack.mulPose(new Quaternionf().slerp(toView, aim));
			// on the way: the muzzle dips and the rifle cants as the stock comes up into the shoulder
			float arc = Mth.sin(aim * Mth.PI);
			poseStack.translate(0.0F, -0.02F * arc, 0.015F * arc);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-6.0F * arc));
			poseStack.mulPose(Axis.XP.rotationDegrees(-2.5F * arc));
		}

		// its weight: lagging behind as you turn, leaning into a sidestep
		poseStack.translate(-LAG_YAW.x * 0.0022F, LAG_PITCH.x * 0.0018F, 0.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(LAG_YAW.x * 0.5F));
		poseStack.mulPose(Axis.XP.rotationDegrees(LAG_PITCH.x * 0.45F));
		float lean = Mth.lerp(partialTick, prevStrafe, strafe) * (1.0F - 0.6F * aim);
		poseStack.mulPose(Axis.ZP.rotationDegrees(-3.5F * lean));
		poseStack.translate(-0.008F * lean, 0.0F, 0.0F);

		// breathing: a slow heave, heavy and quick when out of breath
		float tired = Fatigue.level();
		float breath = Fatigue.breathPhase(partialTick);
		float steady = 1.0F - 0.5F * aim;
		poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 0.045F) * 0.35F + Mth.sin(breath) * 1.6F * tired) * steady));
		poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 0.031F) * 0.3F + Mth.cos(breath * 0.5F) * 0.9F * tired) * steady));
		poseStack.translate(0.0F, Mth.sin(breath) * 0.012F * tired * steady, 0.0F);

		// checking the magazine: canted and brought in a little so you can look at it
		float checkT = AkClient.checkTime(now);
		if (checkT >= 0.0F) {
			float c = smooth(checkT / 7.0F) * (1.0F - smooth((checkT - 31.0F) / 8.0F));
			poseStack.translate(-0.06F * c, 0.13F * c, -0.06F * c);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-24.0F * c));
			poseStack.mulPose(Axis.XP.rotationDegrees(5.0F * c));
			poseStack.mulPose(Axis.YP.rotationDegrees(28.0F * c));
		}

		// brought up into the shoulder when drawn: from low and canted to on target
		float draw = 1.0F - smooth((now - equipTick) / 12.0F);
		if (draw > 0.0F) {
			poseStack.translate(0.04F * draw, -0.25F * draw, 0.1F * draw);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-35.0F * draw));
			poseStack.mulPose(Axis.XP.rotationDegrees(-30.0F * draw));
		}

		// sprinting: low ready - muzzle down and across the body, bouncing with the stride
		float run = smooth(Mth.lerp(partialTick, prevSprint, sprint));
		if (run > 0.0F && mc.player != null) {
			float stride = mc.player.walkAnimation.position(partialTick) * 0.3F;
			poseStack.translate(-0.06F * run, -0.07F * run + Math.abs(Mth.sin(stride * Mth.PI)) * 0.025F * run, 0.04F * run);
			poseStack.mulPose(Axis.YP.rotationDegrees(38.0F * run + Mth.sin(stride * Mth.PI) * 3.0F * run));
			poseStack.mulPose(Axis.XP.rotationDegrees(-22.0F * run));
			poseStack.mulPose(Axis.ZP.rotationDegrees(14.0F * run + Mth.cos(stride * Mth.PI) * 2.0F * run));
		}

		// landing: the rifle dips with your knees and comes back up
		float land = now - landTick;
		if (land >= 0.0F && land < 10.0F) {
			float dip = Mth.sin(land / 10.0F * Mth.PI) * (1.0F - land / 10.0F) * landHard;
			poseStack.translate(0.0F, -0.07F * dip, 0.0F);
			poseStack.mulPose(Axis.XP.rotationDegrees(-5.0F * dip));
		}

		// working the selector: the rifle rolls a touch as the thumb rides the lever
		float sel = now - modeTick;
		if (sel >= 0.0F && sel < 7.0F) {
			float flick = Mth.sin(sel / 7.0F * Mth.PI);
			poseStack.mulPose(Axis.ZP.rotationDegrees(7.0F * flick));
			poseStack.translate(0.01F * flick, 0.008F * flick, 0.0F);
		}

		// reload choreography
		if (state.reloading()) {
			float r = now - state.reloadStart();
			int duration = state.reloadKind() == GunState.EMPTY ? AkItem.RELOAD_EMPTY : AkItem.RELOAD_TACTICAL;
			float tilt = smooth(r / 9.0F) * (1.0F - smooth((r - (duration - 9)) / 9.0F));
			// the slap of the new magazine going home
			float slap = r > AkItem.T_MAG_IN + 2 && r < AkItem.T_MAG_IN + 7 ? Mth.sin((r - (AkItem.T_MAG_IN + 2)) / 5.0F * Mth.PI) : 0.0F;
			// racking the handle: the rifle is turned right to bring the handle under your hand
			float rack = 0.0F;
			if (state.reloadKind() == GunState.EMPTY) {
				rack = smooth((r - (AkItem.T_CHARGE - 12)) / 8.0F) * (1.0F - smooth((r - (AkItem.T_CHARGE + 6)) / 8.0F));
			}
			// the old magazine wrenched out of the well: the rifle tugged down and forward with it
			float tug = jolt(r - AkItem.T_MAG_OUT, 4.0F);
			// the bolt slamming home once the handle is let go: a jolt forward
			float slam = state.reloadKind() == GunState.EMPTY ? jolt(r - (AkItem.T_CHARGE + 3), 5.0F) : 0.0F;
			// the slap overshoots and springs back
			float settle = r > AkItem.T_MAG_IN + 4 ? jolt(r - (AkItem.T_MAG_IN + 4), 6.0F) * -0.4F : 0.0F;
			// brought in towards the middle of the view and up, rolled so the magazine well faces you
			poseStack.translate(-0.1F * tilt + 0.06F * rack, 0.2F * tilt + 0.012F * (slap + settle) - 0.014F * tug + 0.006F * slam,
				-0.12F * tilt - 0.01F * tug - 0.018F * slam);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-30.0F * tilt + 52.0F * rack + 4.0F * tug));
			poseStack.mulPose(Axis.XP.rotationDegrees(5.0F * tilt - 3.0F * (slap + settle) - 4.0F * tug + 2.5F * slam));
			poseStack.mulPose(Axis.YP.rotationDegrees(40.0F * tilt + 8.0F * rack));
		}

		// recoil: the springs, kicked by every shot
		poseStack.translate(0.0F, 0.12F * Math.max(0.0F, PITCH.x) * 0.02F, Math.max(-0.02F, BACK.x));
		poseStack.mulPose(Axis.XP.rotationDegrees(PITCH.x));
		poseStack.mulPose(Axis.YP.rotationDegrees(YAW.x));
		poseStack.mulPose(Axis.ZP.rotationDegrees(ROLL.x));
	}

	/** A quick knock that starts at t = 0 and has died away by t = length (0 outside). */
	private static float jolt(float t, float length) {
		if (t < 0.0F || t > length) {
			return 0.0F;
		}
		float f = t / length;
		return Mth.sin(Math.min(1.0F, f * 3.0F) * Mth.PI * 0.5F) * (1.0F - f) * (1.0F - f);
	}
}
