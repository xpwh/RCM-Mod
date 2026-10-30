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

	/**
	 * Line of sight (item space): level with the tops of the rear sight's ears, to the tip of the front
	 * post - the classic sight picture, the post standing in the U of the notch, its tip flush with the
	 * notch's top edges, with a sliver of light either side of it.
	 */
	private static final Vector3f NOTCH = new Vector3f(0.0F, 0.1473F, -0.2025F);
	private static final Vector3f SIGHT_LINE = new Vector3f(0.0F, -0.002F, -0.4764F).normalize();
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

	/** The rifle's pose in the hand from a Blender clip (scratch sample). */
	private static final float[] POSE = new float[AkClips.STRIDE];
	/** The clip poses applied this frame, added up (gun channels), for the follow-through. */
	private static final float[] CLIP_POSE = new float[6];
	private static final FollowThrough FOLLOW = new FollowThrough();
	private static float frameDt;

	/** The last pull of the trigger on an empty gun. */
	private static long dryTick = -100L;

	static void dryFire(long now) {
		dryTick = now;
	}

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
	private static void physics(Minecraft mc, float now, float partialTick, boolean loose) {
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
		frameDt = dt;
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
		// at the shoulder, the sights held on the point of aim: hardly any lag; reloading, held loosely: a lot
		float hold = (1.0F - 0.85F * aiming) * (loose ? 1.9F : 1.0F);
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
			physics(mc, now, partialTick, state.reloading());
		}
		HeadMotion.begin();
		java.util.Arrays.fill(CLIP_POSE, 0.0F);

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
		float lean = Mth.lerp(partialTick, prevStrafe, strafe) * (1.0F - 0.9F * aim);
		poseStack.mulPose(Axis.ZP.rotationDegrees(-3.5F * lean));
		poseStack.translate(-0.008F * lean, 0.0F, 0.0F);

		// breathing: a slow heave, heavy and quick when out of breath
		float tired = Fatigue.level();
		float breath = Fatigue.breathPhase(partialTick);
		float steady = 1.0F - 0.8F * aim;
		// a wounded arm: a tremor the hold cannot steady
		int arm = mc.player == null ? 0 : de.rcm.ballistic.injury.Injuries.get(mc.player).arm();
		if (arm > 0) {
			poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 1.7F) * 0.35F + Mth.sin(now * 0.43F) * 0.6F) * arm));
			poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 1.3F) * 0.3F + Mth.sin(now * 0.37F + 1.0F) * 0.55F) * arm));
		}
		poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 0.045F) * 0.35F + Mth.sin(breath) * 1.6F * tired) * steady));
		poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 0.031F) * 0.3F + Mth.cos(breath * 0.5F) * 0.9F * tired) * steady));
		poseStack.translate(0.0F, Mth.sin(breath) * 0.012F * tired * steady, 0.0F);
		// shaking hands: stress, cold
		Tremor.apply(poseStack, now, aim);

		// checking the magazine: canted and brought in so you can look at it
		float checkT = AkClient.checkTime(now);
		if (checkT >= 0.0F) {
			pose(poseStack, AkClips.get("check"), checkT);
		}

		// looking it over
		float insp = AkClient.inspectTime(now);
		if (insp >= 0.0F) {
			pose(poseStack, AkClips.get("inspect"), insp);
		}

		// a click instead of a bang: the trigger finger's twitch, then a quick cant to look at the empty rifle
		float dry = now - dryTick;
		if (dry >= 0.0F && dry < 22.0F) {
			pose(poseStack, AkClips.get("dry"), dry);
		}

		// brought up into the shoulder when drawn: from low and canted to on target
		float drawn = now - equipTick;
		if (drawn >= 0.0F && drawn < 12.0F) {
			pose(poseStack, AkClips.get("draw"), drawn);
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

		// reloading: the rifle drawn in and canted so the well faces you, jolted as the magazines change and
		// the bolt slams home, rolled over for the charging handle on an empty gun, back on target
		if (state.reloading()) {
			float r = now - state.reloadStart();
			pose(poseStack, AkClips.reload(state, r), r);
		}

		// the rifle's weight carrying on past where the clip stops it, and lagging as it starts
		FOLLOW.step(CLIP_POSE, frameDt);
		FOLLOW.apply(poseStack);

		// recoil: the springs, kicked by every shot
		poseStack.translate(0.0F, 0.12F * Math.max(0.0F, PITCH.x) * 0.02F, Math.max(-0.02F, BACK.x));
		poseStack.mulPose(Axis.XP.rotationDegrees(PITCH.x));
		poseStack.mulPose(Axis.YP.rotationDegrees(YAW.x));
		poseStack.mulPose(Axis.ZP.rotationDegrees(ROLL.x));
	}

	/** The rifle's pose in the hand from a clip: moved, then rolled, pitched and turned. */
	private static void pose(PoseStack poseStack, AkClips.Clip clip, float t) {
		float[] p = clip.at(t, POSE);
		for (int i = 0; i < 6; i++) {
			CLIP_POSE[i] += p[i];
		}
		HeadMotion.add(p, 1.0F);
		poseStack.translate(p[0], p[1], p[2]);
		poseStack.mulPose(Axis.ZP.rotationDegrees(p[3]));
		poseStack.mulPose(Axis.XP.rotationDegrees(p[4]));
		poseStack.mulPose(Axis.YP.rotationDegrees(p[5]));
	}
}
