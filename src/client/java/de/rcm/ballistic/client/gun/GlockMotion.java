package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.render.GlockItemRenderer;
import de.rcm.ballistic.gun.GlockItem;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * How the Glock moves in first person, as a pure function of what is going on ({@link Frame}): the game feeds
 * it the live state ({@link GlockClient}), the offline animation studio scripted scenes - the same code either
 * way. Springs (the snap of the recoil, the gun's weight lagging behind the view, settling onto the sights)
 * are state, stepped with {@link #step}.
 * <p>
 * Held in two hands, arms out: at the "ready" low in the middle of the view, at the sights the rear notch
 * close under the eye with the front dot sitting in it. A shot snaps the muzzle up and the slide back and
 * forward in a blink, the case spinning out to the right; the gun comes straight back down onto the target.
 * The reload, the inspect with its press check, the dry click, the draw and the idle look are keyframed in
 * Blender ({@link GlockClips}): the pistol's pose, both magazines, both hands and the slide come from the clip;
 * the springs, the aim, the step, the sprint and the landing stay worked out here, on top of it.
 */
public final class GlockMotion {
	/** Rear notch (middle of the U) and the front dot: what the eye lines up. */
	static final Vector3f NOTCH = new Vector3f(0.0F, GlockItemRenderer.NOTCH_Y + 0.0014F, GlockItemRenderer.NOTCH_Z);
	static final Vector3f DOT = new Vector3f(0.0F, GlockItemRenderer.DOT_Y, GlockItemRenderer.FRONT_SIGHT_Z);
	static final Vector3f SIGHT_LINE = new Vector3f(DOT).sub(NOTCH).normalize();
	/** The eye on the sight line, this far behind the notch: the arms out, the gun a forearm's length off. */
	static final float EYE_RELIEF = 0.27F;
	static final Vector3f EYE = new Vector3f(NOTCH).sub(new Vector3f(SIGHT_LINE).mul(EYE_RELIEF));
	static final Vector3f CAMERA = new Vector3f(-0.56F, 0.52F, 0.72F);
	/** The item's origin (the grip) as the first-person display transform places it. */
	static final Vector3f GRIP_PIVOT = new Vector3f(-3.2F / 16.0F, 2.4F / 16.0F, 0.5F / 16.0F);
	/** Low ready: from vanilla's hand place (far right, low) to low in the middle, nearer. */
	static final Vector3f READY = new Vector3f(-0.22F, 0.2F, 0.35F);
	/** The hands (item space): the right fist round the grip, the left wrapped over it from the other side. */
	public static final Vector3f GRIP = new Vector3f(0.0F, -0.004F, 0.006F);
	public static final Vector3f SUPPORT = new Vector3f(-0.022F, -0.016F, -0.006F);
	/** The magazine pouch on the left of the belt, as it lies from the gun drawn in for the reload. */
	static final Vector3f POUCH = new Vector3f(-0.12F, -0.32F, -0.14F);
	/** The inspect: how long it takes and the press check in the middle of it (the clip's timing, for the sounds). */
	public static final int INSPECT = 62;
	static final float PRESS_BACK = 26.0F;
	static final float PRESS_HOME = 32.0F;
	/** The idle look: this long after anything happened, for this long. */
	static final float IDLE_AFTER = 260.0F;
	static final int IDLE = 50;
	static final int DRAW = 11;
	static final int DRY = 22;

	/**
	 * Everything the pose depends on. Times are in ticks since the event (negative or large: not happening).
	 *
	 * @param now       game time (ticks, with the partial tick)
	 * @param aim       0 at the ready, 1 on the sights (eased)
	 * @param shot      ticks since the last shot
	 * @param locked    the slide is locked back (the last round has gone)
	 * @param reload    ticks since the reload started; -1: none
	 * @param empty     the reload is from slide lock (the slide has to be racked at the end)
	 * @param hadMag    there was a magazine in the gun to drop
	 * @param oldKind   the magazine coming out (standard/extended)
	 * @param newKind   the magazine going in
	 * @param dry       ticks since the trigger was pulled on nothing
	 * @param inspect   ticks into looking the pistol over; -1: not
	 * @param idle      ticks since anything happened with the gun
	 * @param drawn     ticks since it was taken in hand
	 * @param run       0..1 sprinting (eased)
	 * @param stride    the walk animation's position
	 * @param walk      0..1 walking speed (eased)
	 * @param lean      -1..1 stepping sideways
	 * @param landed    ticks since landing from a jump or fall
	 * @param landHard  0..1 how hard
	 * @param tired     0..1 out of breath
	 * @param breath    breathing phase
	 * @param arm       0..2 a wounded arm's tremor
	 * @param variant   how the reload goes (GunState.CLEAN, FUMBLE, DROPPED)
	 * @param start     the tick the reload started (picks its take)
	 */
	public record Frame(float now, float aim, float shot, boolean locked, float reload, boolean empty, boolean hadMag, int oldKind, int newKind, float dry,
		float inspect, float idle, float drawn, float run, float stride, float walk, float lean, float landed, float landHard, float tired, float breath,
		int arm, int variant, long start) {
	}

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

	// a 9 mm in two hands: a sharp snap - the muzzle flips, the hands come back a little - and it is down again at once
	private final Spring back = new Spring(1.6F, 0.6F);
	private final Spring pitch = new Spring(1.35F, 0.55F);
	private final Spring roll = new Spring(1.3F, 0.5F);
	private final Spring yaw = new Spring(1.2F, 0.5F);
	private final Spring lagYaw = new Spring(0.8F, 0.55F);
	private final Spring lagPitch = new Spring(0.85F, 0.6F);
	/** Coming up onto the sights: it rises a touch past the line and settles. */
	private final Spring settle = new Spring(1.2F, 0.45F);
	private boolean wasAimed;
	/** The clip's pose carrying on (FollowThrough), and how long the last step was. */
	private final FollowThrough follow = new FollowThrough();
	private float lastDt;
	/** Reloading: the hold is loose, the gun lags far more as you turn. */
	private boolean loose;

	/** A shot: {@code braced} 1 at the ready, less on the sights; {@code side} -1..1 which way it rolls; {@code r} 0..1 random. */
	public void kick(float braced, float side, float r) {
		this.back.v += 0.13F * braced;
		this.pitch.v += 13.0F * braced * (0.85F + 0.3F * r);
		this.roll.v += 5.0F * side * braced;
		this.yaw.v += (2.0F * r - 0.6F) * braced;
	}

	/** {@code dt} ticks on; the view turned by {@code dYaw}, {@code dPitch} degrees meanwhile. */
	public void step(float dt, float dYaw, float dPitch, float aim) {
		this.lastDt = dt;
		float hold = (1.0F - 0.8F * aim) * (this.loose ? 1.9F : 1.0F);
		this.lagYaw.v += Mth.clamp(dYaw, -25.0F, 25.0F) * 0.09F * hold;
		this.lagPitch.v += Mth.clamp(dPitch, -25.0F, 25.0F) * 0.09F * hold;
		boolean aimed = aim > 0.97F;
		if (aimed && !this.wasAimed) {
			this.settle.v += 1.0F;
		}
		this.wasAimed = aimed;
		for (Spring sp : new Spring[] {this.back, this.pitch, this.roll, this.yaw, this.lagYaw, this.lagPitch, this.settle}) {
			sp.step(dt);
		}
		this.lagYaw.x = Mth.clamp(this.lagYaw.x, -8.0F, 8.0F);
		this.lagPitch.x = Mth.clamp(this.lagPitch.x, -7.0F, 7.0F);
	}

	static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** 0 before {@code a}, 1 after {@code b}, eased between. */
	static float ramp(float t, float a, float b) {
		return smooth((t - a) / (b - a));
	}

	/** A quick rise and a ringing decay over {@code length} ticks. */
	static float jolt(float t, float length) {
		if (t < 0.0F || t > length) {
			return 0.0F;
		}
		float f = t / length;
		return Mth.sin(Math.min(1.0F, f * 3.0F) * Mth.PI * 0.5F) * (1.0F - f) * (1.0F - f);
	}

	// ------------------------------------------------------------------ the moving parts

	private static final float[] NO_CLIP = new float[6];

	/** The clip playing now and how far into it (null: none), first come first served. */
	private static AkClips.Clip clip(Frame f) {
		if (f.reload() >= 0.0F) {
			return GlockClips.reload(f.empty(), f.newKind(), f.variant(), f.start());
		}
		if (f.inspect() >= 0.0F && f.inspect() < INSPECT) {
			return GlockClips.get("inspect");
		}
		if (f.dry() >= 0.0F && f.dry() < DRY) {
			return GlockClips.get("dry");
		}
		if (f.drawn() >= 0.0F && f.drawn() < DRAW) {
			return GlockClips.get("draw");
		}
		float idle = f.idle() - IDLE_AFTER;
		if (idle >= 0.0F && idle < IDLE && f.aim() < 0.05F) {
			return GlockClips.get("idle");
		}
		return null;
	}

	private static float clipTime(Frame f) {
		if (f.reload() >= 0.0F) {
			return f.reload();
		}
		if (f.inspect() >= 0.0F && f.inspect() < INSPECT) {
			return f.inspect();
		}
		if (f.dry() >= 0.0F && f.dry() < DRY) {
			return f.dry();
		}
		if (f.drawn() >= 0.0F && f.drawn() < DRAW) {
			return f.drawn();
		}
		return f.idle() - IDLE_AFTER;
	}

	/** The clip's sample for this frame (null: no clip playing). */
	static float[] sample(Frame f) {
		AkClips.Clip c = clip(f);
		return c == null ? null : c.at(clipTime(f), new float[AkClips.STRIDE]);
	}

	/** How far back the slide is (0..1, a little past 1 when the hand pulls it against the stop). */
	public static float slide(Frame f) {
		float[] s = f.reload() >= 0.0F || f.inspect() >= 0.0F ? sample(f) : null;
		if (s != null) {
			return s[36];
		}
		if (f.locked()) {
			// the last shot: back it goes, and it stays
			return f.shot() < 0.45F ? smooth(f.shot() / 0.45F) : 1.0F;
		}
		// a shot: the slide snaps back on the recoil and the spring drives it home again - a tenth of a second
		float s0 = f.shot();
		if (s0 >= 0.0F && s0 < 1.3F) {
			return s0 < 0.4F ? smooth(s0 / 0.4F) : 1.0F - smooth((s0 - 0.4F) / 0.9F);
		}
		return 0.0F;
	}

	/** The old magazine during a reload: seated until the thumb drops it, then falling away - its transform from the seat (null: none to show). */
	public static Matrix4f oldMag(Frame f) {
		if (f.reload() < 0.0F || !f.hadMag()) {
			return null;
		}
		float[] s = sample(f);
		return s[37] > 0.5F ? AkClips.matrix(s, 6, new Matrix4f()) : null;
	}

	/** The new magazine during a reload, from the pouch to home: its transform from the seat (null: not out yet). */
	public static Matrix4f newMag(Frame f) {
		if (f.reload() < 0.0F) {
			return null;
		}
		float[] s = sample(f);
		return s[38] > 0.5F ? AkClips.matrix(s, 18, new Matrix4f()) : null;
	}

	/** Where the support (left) hand is, item space. */
	static Vector3f supportHand(Frame f) {
		float[] s = sample(f);
		if (s != null) {
			return AkClips.vec(s, 30, new Vector3f());
		}
		// sprinting: one hand on the gun, the other off it, pumping
		if (f.run() > 0.01F) {
			Vector3f off = new Vector3f(SUPPORT).add(-0.16F, -0.22F + 0.05F * Mth.sin(f.stride() * Mth.PI * 0.6F), 0.08F);
			return lerp(SUPPORT, off, f.run());
		}
		return new Vector3f(SUPPORT);
	}

	// ------------------------------------------------------------------ the gun's pose

	/** The gun's pose for this frame, applied to {@code poseStack} (first person, before the item is drawn). */
	public void apply(PoseStack poseStack, Frame f) {
		float now = f.now();
		float a = f.aim();
		// low ready: brought in from vanilla's far-right hand place to the middle, the muzzle a touch down
		float ready = 1.0F - a;
		poseStack.translate(READY.x * ready, READY.y * ready, READY.z * ready);
		poseStack.mulPose(Axis.XP.rotationDegrees(-3.0F * ready));
		poseStack.mulPose(Axis.YP.rotationDegrees(9.0F * ready));
		poseStack.mulPose(Axis.ZP.rotationDegrees(4.0F * ready));

		// on the sights: the notch under the eye, the front dot sitting in it in the middle of the view
		if (a > 0.0F) {
			Quaternionf display = Axis.YP.rotationDegrees(3.0F);
			Vector3f line = display.transform(new Vector3f(SIGHT_LINE));
			Quaternionf toView = new Quaternionf().rotationTo(line, new Vector3f(0.0F, 0.0F, -1.0F));
			Vector3f eye = display.transform(new Vector3f(EYE)).add(-3.2F / 16.0F, 2.4F / 16.0F, 0.5F / 16.0F);
			Vector3f shift = new Vector3f(CAMERA).sub(toView.transform(new Vector3f(eye)));
			poseStack.translate(shift.x * a, shift.y * a, shift.z * a);
			poseStack.mulPose(new Quaternionf().slerp(toView, a));
			// pushed out along an arc: up and forward through the middle of the move, not straight across
			float arc = Mth.sin(a * Mth.PI);
			poseStack.translate(0.0F, 0.02F * arc, -0.03F * arc);
		}

		// from here on everything turns about the grip (the item's origin, past the display transform's offset),
		// not about vanilla's hand point
		poseStack.translate(GRIP_PIVOT.x, GRIP_PIVOT.y, GRIP_PIVOT.z);

		// its weight lagging behind the view
		poseStack.translate(-this.lagYaw.x * 0.002F, this.lagPitch.x * 0.0018F, 0.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(this.lagYaw.x * 0.5F));
		poseStack.mulPose(Axis.XP.rotationDegrees(this.lagPitch.x * 0.45F));

		// breathing, and a wounded arm's tremor (a pistol held out shows both more than a shouldered rifle)
		float steady = 1.0F - 0.7F * a;
		if (f.arm() > 0) {
			poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 1.9F) * 0.45F + Mth.sin(now * 0.47F) * 0.7F) * f.arm()));
			poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 1.4F) * 0.4F + Mth.sin(now * 0.39F + 1.0F) * 0.6F) * f.arm()));
		}
		poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 0.05F) * 0.45F + Mth.sin(f.breath()) * 2.0F * f.tired()) * steady));
		poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 0.034F) * 0.4F + Mth.cos(f.breath() * 0.5F) * 1.2F * f.tired()) * steady));
		if (f.drawn() != 100.0F) {
			Tremor.apply(poseStack, now, f.aim()); // your own hands only (another player's frame has no draw time)
		}

		// the step: a bob and a sway, the arms soaking up most of it on the sights
		float hold = 1.0F - 0.75F * a;
		float w = f.walk() * hold * (1.0F - f.run());
		if (w > 0.01F) {
			float stride = f.stride() * 0.6F;
			poseStack.translate(Mth.sin(stride) * 0.006F * w, -Math.abs(Mth.sin(stride)) * 0.014F * w, 0.0F);
			poseStack.mulPose(Axis.XP.rotationDegrees(Math.abs(Mth.sin(stride)) * 1.6F * w));
			poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.sin(stride) * 1.4F * w));
		}
		poseStack.mulPose(Axis.ZP.rotationDegrees(-4.0F * f.lean() * hold));
		// up onto the sights: a hair past the line, settling back
		poseStack.translate(0.0F, 0.006F * this.settle.x, 0.0F);
		poseStack.mulPose(Axis.XP.rotationDegrees(1.2F * this.settle.x));

		// the clip playing (reload, inspect, dry click, draw, idle look): the pistol's pose in the hands
		float[] clip = sample(f);
		if (clip != null) {
			poseStack.translate(clip[0], clip[1], clip[2]);
			poseStack.mulPose(Axis.ZP.rotationDegrees(clip[3]));
			poseStack.mulPose(Axis.XP.rotationDegrees(clip[4]));
			poseStack.mulPose(Axis.YP.rotationDegrees(clip[5]));
		}
		// the gun's weight carrying on past where the clip stops it; the head moving with the clip (your own gun only)
		this.loose = f.reload() >= 0.0F;
		this.follow.step(clip != null ? clip : NO_CLIP, this.lastDt);
		this.lastDt = 0.0F;
		this.follow.apply(poseStack);
		if (f.drawn() != 100.0F) {
			HeadMotion.begin();
			if (clip != null) {
				HeadMotion.add(clip, 1.0F);
			}
		}

		// sprinting: pulled in to the chest, muzzle down and forward, bouncing with the stride
		float run = f.run();
		if (run > 0.0F) {
			float stride = f.stride() * 0.3F;
			poseStack.translate(-0.02F * run, -0.02F * run + Math.abs(Mth.sin(stride * Mth.PI)) * 0.025F * run, 0.05F * run);
			poseStack.mulPose(Axis.XP.rotationDegrees(-30.0F * run + Mth.sin(stride * Mth.PI * 2.0F) * 3.0F * run));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-12.0F * run + Mth.cos(stride * Mth.PI) * 4.0F * run));
			poseStack.mulPose(Axis.YP.rotationDegrees(10.0F * run));
		}

		// landing: the knees give and the gun dips
		float land = f.landed();
		if (land >= 0.0F && land < 12.0F) {
			float dip = Mth.sin(land / 12.0F * Mth.PI) * (1.0F - land / 12.0F) * f.landHard();
			poseStack.translate(0.0F, -0.07F * dip, 0.01F * dip);
			poseStack.mulPose(Axis.XP.rotationDegrees(-6.0F * dip));
		}

		// recoil: the muzzle flips about the wrists (below and behind the bore), the hands give a little
		poseStack.translate(0.0F, 0.0F, Math.max(-0.02F, this.back.x));
		poseStack.translate(0.0F, -0.01F, 0.02F);
		poseStack.mulPose(Axis.XP.rotationDegrees(this.pitch.x));
		poseStack.mulPose(Axis.YP.rotationDegrees(-this.yaw.x));
		poseStack.mulPose(Axis.ZP.rotationDegrees(this.roll.x));
		poseStack.translate(0.0F, 0.01F, -0.02F);
		poseStack.translate(-GRIP_PIVOT.x, -GRIP_PIVOT.y, -GRIP_PIVOT.z);
	}

	// ------------------------------------------------------------------ the arms

	/** Where the hands are (item space), and the shoulders and the ways the elbows point (first-person base space, as AkArms takes them). */
	public record Hands(Vector3f right, Vector3f left, Vector3f rightShoulder, Vector3f rightPole, Vector3f leftShoulder, Vector3f leftPole,
		Quaternionf rightTurn, Quaternionf leftTurn) {
	}

	/** The arms drawn for the pistol: slimmer than the model's, the forearm (and fist) shorter, so the hands sit on a small gun. */
	public static final float ARM_THICK = 0.27F;
	public static final float FOREARM = 0.56F;

	/** Minecraft's first-person shoulders (base space): far out to the lower right and left. */
	public static final Vector3f RIGHT_SHOULDER = new Vector3f(0.0F, -0.5F, 0.6F);
	public static final Vector3f LEFT_SHOULDER = new Vector3f(-0.72F, -0.48F, 0.5F);
	public static final Vector3f RIGHT_POLE = new Vector3f(0.6F, -1.0F, 0.2F);
	public static final Vector3f LEFT_POLE = new Vector3f(-0.65F, -1.0F, 0.2F);
	/** On the sights both shoulders square to the target, the elbows out. */
	public static final Vector3f AIM_RIGHT_SHOULDER = new Vector3f(0.2F - 0.56F, -0.56F + 0.52F, 0.08F + 0.72F);
	public static final Vector3f AIM_LEFT_SHOULDER = new Vector3f(-0.2F - 0.56F, -0.56F + 0.52F, 0.08F + 0.72F);

	public static Hands hands(Frame f) {
		float a = f.aim();
		Vector3f rs = new Vector3f(RIGHT_SHOULDER).lerp(AIM_RIGHT_SHOULDER, a);
		Vector3f ls = new Vector3f(LEFT_SHOULDER).lerp(AIM_LEFT_SHOULDER, a);
		// reloading, the shooting elbow comes up and out so the forearm runs off to the side, clear of the magwell
		float r = f.reload();
		int length = f.variant() == GlockClips.CLEARING ? GlockItem.CLEAR_LENGTH
			: f.empty() ? GlockItem.RELOAD_EMPTY : GlockItem.RELOAD_TACTICAL + GlockItem.delay(f.variant());
		float in = r < 0.0F ? 0.0F : ramp(r, 0.0F, 4.0F) * (1.0F - ramp(r, length - 6.0F, length));
		Vector3f rightPole = new Vector3f(RIGHT_POLE).lerp(new Vector3f(1.0F, 0.1F, 0.5F), in);
		float[] clip = sample(f);
		Vector3f grip = clip != null ? AkClips.vec(clip, 33, new Vector3f()) : new Vector3f(GRIP);
		Quaternionf rightTurn = clip != null ? AkClips.turn(clip, AkClips.RIGHT_TURN, new Quaternionf()) : new Quaternionf();
		Quaternionf leftTurn = clip != null ? AkClips.turn(clip, AkClips.LEFT_TURN, new Quaternionf()) : new Quaternionf();
		return new Hands(grip, supportHand(f), rs, rightPole, ls, new Vector3f(LEFT_POLE), rightTurn, leftTurn);
	}

	private static Vector3f lerp(Vector3f a, Vector3f b, float t) {
		return new Vector3f(a).lerp(b, smooth(t));
	}
}
