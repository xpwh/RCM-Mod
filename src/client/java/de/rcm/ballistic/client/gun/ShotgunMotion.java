package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.render.ShotgunItemRenderer;
import de.rcm.ballistic.gun.ShotgunItem;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * How the pump shotgun moves in first person, as a pure function of what is going on ({@link Frame}): the game
 * feeds it the live state ({@link ShotgunClient}), the offline animation studio feeds it scripted scenes - the
 * same code either way. The springs (recoil, the gun's weight lagging behind the view, settling into the
 * shoulder) are state, stepped with {@link #step}.
 */
public final class ShotgunMotion {
	/** Line of sight (item space): the groove on top of the receiver to the brass bead at the muzzle. */
	static final Vector3f NOTCH = new Vector3f(0.0F, ShotgunItemRenderer.SIGHT_REAR_Y, 0.05F);
	static final Vector3f SIGHT_LINE = new Vector3f(0.0F, ShotgunItemRenderer.BEAD_Y - ShotgunItemRenderer.SIGHT_REAR_Y,
		ShotgunItemRenderer.MUZZLE_Z + 0.014F - 0.05F).normalize();
	/** The brass bead at the muzzle: what the eye lines up on the target. */
	static final Vector3f BEAD = new Vector3f(0.0F, ShotgunItemRenderer.BEAD_Y, ShotgunItemRenderer.MUZZLE_Z + 0.014F);
	/**
	 * Cheek down on the comb, the eye well back behind the receiver and a little above the rib, looking down it
	 * onto the bead: the receiver and the hand sit low in the view instead of filling it.
	 */
	static final Vector3f EYE = new Vector3f(NOTCH).sub(new Vector3f(SIGHT_LINE).mul(0.42F)).add(0.0F, 0.03F, 0.0F);
	static final Vector3f CAMERA = new Vector3f(-0.56F, 0.52F, 0.72F);
	/** The hands (item space): right fist round the wrist of the stock, left under the forend. */
	public static final Vector3f GRIP = new Vector3f(0.0F, 0.065F, 0.115F);
	public static final Vector3f FOREND = new Vector3f(-0.004F, 0.035F, -0.415F);
	/** Fingers under the loading port, a shell pushed up and in; the pouch on the belt (gun rolled over). */
	static final Vector3f PORT = new Vector3f(-0.002F, 0.05F, -0.075F);
	static final Vector3f APPROACH = new Vector3f(-0.01F, 0.0F, 0.0F);
	/** The pouch on the belt, as it lies from the gun rolled over for loading: down past its right side and back. */
	static final Vector3f POUCH = new Vector3f(0.42F, -0.3F, 0.38F);

	/**
	 * Everything the pose depends on. Times are in ticks since the event (negative or large: not happening).
	 *
	 * @param now       game time (ticks, with the partial tick)
	 * @param aim       0 at the hip, 1 with the bead on target (eased)
	 * @param pump      how far back the forend is, 0..1
	 * @param slide     ticks since the slide started working (the shot, or the rack after loading); -1: not
	 * @param racking   the slide is racking the first shell in after loading (otherwise: pumping after a shot)
	 * @param dry       ticks since a click on an empty chamber
	 * @param load      0..1 how far rolled over for loading (eased)
	 * @param shell     ticks since the loading started (each shell takes {@link ShotgunItem#SHELL_CYCLE}); -1: not loading
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
	 */
	public record Frame(float now, float aim, float pump, float slide, boolean racking, float dry, float load, float shell, float idle, float drawn,
		float run, float stride, float walk, float lean, float landed, float landHard, float tired, float breath, int arm) {
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

	// a 12-gauge kicks hard: back into the shoulder, the muzzle flipping high, a roll, out to the right, the
	// upper body rocking up with it
	private final Spring back = new Spring(1.0F, 0.55F);
	private final Spring pitch = new Spring(0.85F, 0.5F);
	private final Spring roll = new Spring(0.9F, 0.45F);
	private final Spring yaw = new Spring(0.8F, 0.45F);
	private final Spring lift = new Spring(0.7F, 0.6F);
	// its weight lagging behind the view
	private final Spring lagYaw = new Spring(0.7F, 0.55F);
	private final Spring lagPitch = new Spring(0.75F, 0.6F);
	/** Coming up onto the shoulder: it swings up a little past the line and settles back. */
	private final Spring settle = new Spring(1.15F, 0.4F);
	private boolean wasAimed;

	/** A shot: {@code braced} 1 at the hip, less shouldered or crouched; {@code side} -1..1 which way it rolls; {@code r} 0..1 random. */
	public void kick(float braced, float side, float r) {
		this.back.v += 0.38F * braced;
		this.pitch.v += 17.0F * braced * (0.9F + 0.2F * r);
		this.roll.v += 6.0F * side * braced;
		this.yaw.v += (3.0F + 2.0F * r) * braced;
		this.lift.v += 0.06F * braced;
	}

	/** {@code dt} ticks on; the view turned by {@code dYaw}, {@code dPitch} degrees meanwhile. */
	public void step(float dt, float dYaw, float dPitch, float aim) {
		float hold = 1.0F - 0.85F * aim;
		this.lagYaw.v += Mth.clamp(dYaw, -25.0F, 25.0F) * 0.1F * hold;
		this.lagPitch.v += Mth.clamp(dPitch, -25.0F, 25.0F) * 0.1F * hold;
		boolean aimed = aim > 0.97F;
		if (aimed && !this.wasAimed) {
			this.settle.v += 1.0F; // the stock beds into the shoulder
		}
		this.wasAimed = aimed;
		for (Spring sp : new Spring[] {this.back, this.pitch, this.roll, this.yaw, this.lift, this.lagYaw, this.lagPitch, this.settle}) {
			sp.step(dt);
		}
		this.lagYaw.x = Mth.clamp(this.lagYaw.x, -8.0F, 8.0F);
		this.lagPitch.x = Mth.clamp(this.lagPitch.x, -7.0F, 7.0F);
	}

	static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** A quick rise and a ringing decay over {@code length} ticks. */
	static float jolt(float t, float length) {
		if (t < 0.0F || t > length) {
			return 0.0F;
		}
		float f = t / length;
		return Mth.sin(Math.min(1.0F, f * 3.0F) * Mth.PI * 0.5F) * (1.0F - f) * (1.0F - f);
	}

	/** One stroke of the slide: snapped back to hit the stop at {@code back}, driven home at {@code forward} (when the clacks sound). */
	public static float stroke(float t, float back, float forward) {
		float s = 1.6F;
		if (t < back - s || t > forward) {
			return 0.0F;
		}
		if (t < back) {
			float f = (t - back + s) / s;
			return f * f * (2.0F - f); // fast at the start, braking into the stop
		}
		if (t < forward - s) {
			// a little bounce off the rear stop
			return 1.0F - 0.04F * Mth.sin(Math.min(1.0F, (t - back) / 2.0F) * Mth.PI);
		}
		float f = (t - forward + s) / s;
		return 1.0F - f * f * (2.0F - f);
	}

	/** The gun's pose for this frame, applied to {@code poseStack} (first person, before the item is drawn). */
	public void apply(PoseStack poseStack, Frame f) {
		float now = f.now();
		float a = f.aim();

		// the bead brought onto the middle of the view over the groove in the receiver
		if (a > 0.0F) {
			Quaternionf display = Axis.YP.rotationDegrees(3.0F);
			Vector3f line = display.transform(new Vector3f(BEAD).sub(EYE).normalize());
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
		poseStack.translate(-this.lagYaw.x * 0.0024F, this.lagPitch.x * 0.002F, 0.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(this.lagYaw.x * 0.55F));
		poseStack.mulPose(Axis.XP.rotationDegrees(this.lagPitch.x * 0.5F));

		// breathing, and a wounded arm's tremor
		float steady = 1.0F - 0.8F * a;
		if (f.arm() > 0) {
			poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 1.7F) * 0.35F + Mth.sin(now * 0.43F) * 0.6F) * f.arm()));
			poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 1.3F) * 0.3F + Mth.sin(now * 0.37F + 1.0F) * 0.55F) * f.arm()));
		}
		poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 0.045F) * 0.4F + Mth.sin(f.breath()) * 1.7F * f.tired()) * steady));
		poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 0.031F) * 0.35F + Mth.cos(f.breath() * 0.5F) * 1.0F * f.tired()) * steady));

		// the gun's weight in the step: a slow heave up and down and a lean into a sidestep (much less shouldered)
		float hipHold = 1.0F - 0.8F * a;
		float w = f.walk() * hipHold * (1.0F - f.run());
		if (w > 0.01F) {
			float stride = f.stride() * 0.6F;
			poseStack.translate(0.0F, -Math.abs(Mth.sin(stride)) * 0.016F * w, -Math.abs(Mth.sin(stride)) * 0.006F * w);
			poseStack.mulPose(Axis.XP.rotationDegrees(Math.abs(Mth.sin(stride)) * 1.8F * w));
			poseStack.mulPose(Axis.YP.rotationDegrees(Mth.sin(stride) * 0.9F * w));
		}
		poseStack.mulPose(Axis.ZP.rotationDegrees(-5.0F * f.lean() * hipHold));
		// bedding into the shoulder: up past the line, settling back
		poseStack.translate(0.0F, 0.008F * this.settle.x, 0.012F * this.settle.x);
		poseStack.mulPose(Axis.XP.rotationDegrees(1.6F * this.settle.x));

		// the pump, ridden out of the recoil: the left arm rips the forend back - the gun dragged down and canted
		// over to the left with it, a twitch to the right at the stop as the hull flicks out - then slams it home,
		// which throws the muzzle forward and back down onto the target
		float since = f.slide();
		float backAt = f.racking() ? ShotgunItem.RACK_BACK : ShotgunItem.PUMP_BACK;
		float homeAt = f.racking() ? ShotgunItem.RACK_FORWARD : ShotgunItem.PUMP_FORWARD;
		float span = f.racking() ? ShotgunItem.RACK : ShotgunItem.READY;
		if (since >= 0.0F && since < span + 5.0F) {
			float work = smooth((since - backAt + 3.0F) / 2.0F) * (1.0F - smooth((since - homeAt) / 4.5F));
			float eject = jolt(since - backAt, 3.0F);
			float home = jolt(since - homeAt, 4.5F);
			float hip = 1.0F - 0.6F * a;
			poseStack.translate(0.012F * work * hip, -0.018F * work * hip, 0.02F * f.pump() * hip - 0.016F * home);
			poseStack.mulPose(Axis.XP.rotationDegrees((-5.0F * f.pump() + 3.0F * home) * hip));
			poseStack.mulPose(Axis.ZP.rotationDegrees((9.0F * work - 4.0F * eject) * hip));
			poseStack.mulPose(Axis.YP.rotationDegrees((-3.0F * f.pump() + 1.5F * eject) * hip));
		}

		// a click instead of a bang: a jerk of surprise, then the gun turned over to look at the empty tube
		float dry = f.dry();
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
		float l = f.load();
		if (l > 0.0F) {
			float c = f.shell() >= 0.0F ? f.shell() % ShotgunItem.SHELL_CYCLE : 0.0F;
			boolean loading = f.shell() >= 0.0F;
			float push = loading ? jolt(c - ShotgunItem.SHELL_IN + 1.5F, 5.0F) : 0.0F;
			float reach = loading ? Mth.sin(Mth.clamp(c / (ShotgunItem.SHELL_IN - 1.5F), 0.0F, 1.0F) * Mth.PI) : 0.0F;
			poseStack.translate(-0.11F * l, 0.17F * l + 0.012F * push - 0.006F * reach, -0.09F * l - 0.016F * push);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-68.0F * l + 3.0F * push));
			poseStack.mulPose(Axis.XP.rotationDegrees(16.0F * l - 3.5F * push + 1.0F * reach));
			poseStack.mulPose(Axis.YP.rotationDegrees(30.0F * l + 2.0F * reach));
		}

		// a long while doing nothing: the gun lifted and turned to look along the receiver, then back
		float idle = f.idle() - 240.0F;
		if (idle >= 0.0F && idle < 60.0F && a < 0.05F) {
			float look = smooth(idle / 12.0F) * (1.0F - smooth((idle - 40.0F) / 18.0F));
			float turn = Mth.sin(Mth.clamp((idle - 10.0F) / 28.0F, 0.0F, 1.0F) * Mth.PI);
			poseStack.translate(-0.05F * look, 0.06F * look, -0.03F * look);
			poseStack.mulPose(Axis.YP.rotationDegrees(28.0F * look + 10.0F * turn));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-20.0F * look - 25.0F * turn));
			poseStack.mulPose(Axis.XP.rotationDegrees(6.0F * look));
		}

		// drawn: brought up from low at the side - muzzle down and the gun rolled in towards you, swinging up and
		// levelling out onto the line, a little overshoot as it comes to rest
		float drawT = f.drawn() / 14.0F;
		float draw = 1.0F - smooth(drawT);
		if (draw > 0.0F) {
			float over = drawT > 0.6F && drawT < 1.0F ? Mth.sin((drawT - 0.6F) / 0.4F * Mth.PI) * 0.35F : 0.0F;
			poseStack.translate(0.06F * draw, -0.34F * draw, 0.08F * draw);
			poseStack.mulPose(Axis.XP.rotationDegrees(-38.0F * draw + 3.0F * over));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-30.0F * draw));
			poseStack.mulPose(Axis.YP.rotationDegrees(-12.0F * draw));
		}

		// sprinting: port arms - the gun carried diagonally across the chest, muzzle up and to the left,
		// bouncing with the stride
		float run = f.run();
		if (run > 0.0F) {
			float stride = f.stride() * 0.3F;
			poseStack.translate(-0.06F * run, -0.12F * run + Math.abs(Mth.sin(stride * Mth.PI)) * 0.03F * run, 0.05F * run);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-48.0F * run + Mth.cos(stride * Mth.PI) * 3.0F * run));
			poseStack.mulPose(Axis.XP.rotationDegrees(18.0F * run + Mth.sin(stride * Mth.PI * 2.0F) * 2.0F * run));
			poseStack.mulPose(Axis.YP.rotationDegrees(22.0F * run));
		}

		// landing: knees give, the gun dips and comes back up
		float land = f.landed();
		if (land >= 0.0F && land < 12.0F) {
			float dip = Mth.sin(land / 12.0F * Mth.PI) * (1.0F - land / 12.0F) * f.landHard();
			poseStack.translate(0.0F, -0.09F * dip, 0.012F * dip);
			poseStack.mulPose(Axis.XP.rotationDegrees(-7.0F * dip));
		}

		// recoil: shouldered, the stock is against you and the gun cannot come back at the eye - the shove goes
		// into the body and shows as the muzzle flip; from the hip it slides back freely
		float shove = Math.max(-0.03F, this.back.x) * (1.0F - 0.8F * a);
		poseStack.translate(0.0F, 0.004F * Math.max(0.0F, this.pitch.x) * 0.1F, shove);
		poseStack.translate(0.0F, this.lift.x, 0.0F);
		poseStack.mulPose(Axis.XP.rotationDegrees(this.pitch.x));
		poseStack.mulPose(Axis.YP.rotationDegrees(-this.yaw.x));
		poseStack.mulPose(Axis.ZP.rotationDegrees(this.roll.x));
	}

	/**
	 * Where the hands are (item space), the shell the left hand carries while loading (null: none), and the
	 * right shoulder and the way its elbow points (first-person base space, as AkArms takes them).
	 */
	public record Hands(Vector3f right, Vector3f left, Vector3f shell, float shellTilt, Vector3f rightShoulder, Vector3f rightPole) {
	}

	/** Minecraft's first-person shoulders (base space): far out to the lower right - right for a gun at the hip. */
	public static final Vector3f HIP_RIGHT_SHOULDER = new Vector3f(0.34F, -0.48F, 0.52F);
	public static final Vector3f HIP_RIGHT_POLE = new Vector3f(0.65F, -1.0F, 0.25F);
	/**
	 * Shouldered: the shoulder pulled in under the cheek, just behind the eye (base space = the view moved by
	 * the hand's place, 0.56 right, 0.52 down, 0.72 ahead), and the elbow raised out to the side.
	 */
	public static final Vector3f AIM_RIGHT_SHOULDER = new Vector3f(0.24F - 0.56F, -0.45F + 0.52F, 0.22F + 0.72F);
	public static final Vector3f AIM_RIGHT_POLE = new Vector3f(0.55F, -1.0F, 0.35F);

	public static Hands hands(Frame f) {
		Vector3f forend = new Vector3f(FOREND).add(0.0F, 0.0F, f.pump() * ShotgunItemRenderer.PUMP_TRAVEL);
		Vector3f left = forend;
		Vector3f shellBase = null;
		float shellTilt = 0.0F;
		float l = f.load();
		if (l > 0.0F && f.shell() >= 0.0F) {
			float t = f.shell();
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
		float a = f.aim();
		Vector3f shoulder = new Vector3f(HIP_RIGHT_SHOULDER).lerp(AIM_RIGHT_SHOULDER, a);
		Vector3f pole = new Vector3f(HIP_RIGHT_POLE).lerp(AIM_RIGHT_POLE, a);
		return new Hands(new Vector3f(GRIP), left, shellBase, shellTilt, shoulder, pole);
	}

	private static Vector3f lerp(Vector3f a, Vector3f b, float t) {
		return new Vector3f(a).lerp(b, smooth(t));
	}

	/** An arc from {@code a} to {@code b}, dipping on the way (a hand going down to the belt and back). */
	private static Vector3f swing(Vector3f a, Vector3f b, float t) {
		Vector3f p = lerp(a, b, t);
		return p.add(0.0F, -0.04F * Mth.sin(Mth.clamp(t, 0.0F, 1.0F) * Mth.PI), 0.0F);
	}
}
