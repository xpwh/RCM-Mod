package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.render.GlockItemRenderer;
import de.rcm.ballistic.gun.GlockItem;
import de.rcm.ballistic.gun.GlockMagazineItem;
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
 * The reload is a speed reload: the gun canted and drawn in, the thumb drops the old magazine out of the grip
 * while the support hand is already on its way to the belt for the new one, brings it up, indexes it into the
 * magwell and slaps it home; from slide lock the support hand then comes over the top and racks the slide.
 */
public final class GlockMotion {
	/** Rear notch (middle of the U) and the front dot: what the eye lines up. */
	static final Vector3f NOTCH = new Vector3f(0.0F, GlockItemRenderer.NOTCH_Y + 0.0014F, GlockItemRenderer.REAR_SIGHT_Z + 0.0075F);
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
	/** Over the top of the slide, fingers on the rear serrations (overhand rack). */
	static final Vector3f OVERHAND = new Vector3f(-0.04F, GlockItemRenderer.SLIDE_TOP - 0.006F, GlockItemRenderer.SLIDE_REAR - 0.006F);
	/** Fingers on the front serrations, the slide eased back a little to see the brass (press check). */
	static final Vector3f PRESS = new Vector3f(-0.012F, GlockItemRenderer.SLIDE_TOP - 0.004F, -0.072F);
	/** The inspect: how long it takes and the press check in the middle of it. */
	public static final int INSPECT = 62;
	static final float PRESS_BACK = 26.0F;
	static final float PRESS_HOME = 32.0F;
	/** How far the slide comes back for a press check. */
	static final float PRESS_TRAVEL = 0.38F;

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
	 */
	public record Frame(float now, float aim, float shot, boolean locked, float reload, boolean empty, boolean hadMag, int oldKind, int newKind, float dry,
		float inspect, float idle, float drawn, float run, float stride, float walk, float lean, float landed, float landHard, float tired, float breath,
		int arm) {
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

	/** A shot: {@code braced} 1 at the ready, less on the sights; {@code side} -1..1 which way it rolls; {@code r} 0..1 random. */
	public void kick(float braced, float side, float r) {
		this.back.v += 0.13F * braced;
		this.pitch.v += 13.0F * braced * (0.85F + 0.3F * r);
		this.roll.v += 5.0F * side * braced;
		this.yaw.v += (2.0F * r - 0.6F) * braced;
	}

	/** {@code dt} ticks on; the view turned by {@code dYaw}, {@code dPitch} degrees meanwhile. */
	public void step(float dt, float dYaw, float dPitch, float aim) {
		float hold = 1.0F - 0.8F * aim;
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

	/** How far back the slide is (0..1, a little past 1 when the hand pulls it against the stop). */
	public static float slide(Frame f) {
		float r = f.reload();
		if (r >= 0.0F && f.empty()) {
			// locked back until the overhand rack: pulled a touch further, let go - it slams home
			if (r < GlockItem.T_RACK_BACK - 2.0F) {
				return 1.0F;
			}
			if (r < GlockItem.T_RACK_BACK) {
				return 1.0F + 0.12F * ramp(r, GlockItem.T_RACK_BACK - 2.0F, GlockItem.T_RACK_BACK);
			}
			if (r < GlockItem.T_RACK_FORWARD - 0.6F) {
				return 1.12F;
			}
			return 1.12F * (1.0F - ramp(r, GlockItem.T_RACK_FORWARD - 0.6F, GlockItem.T_RACK_FORWARD));
		}
		if (f.locked()) {
			// the last shot: back it goes, and it stays
			return f.shot() < 0.45F ? smooth(f.shot() / 0.45F) : 1.0F;
		}
		float i = f.inspect();
		if (i >= 0.0F) {
			return PRESS_TRAVEL * ramp(i, PRESS_BACK - 1.5F, PRESS_BACK + 1.0F) * (1.0F - ramp(i, PRESS_HOME - 1.0F, PRESS_HOME));
		}
		// a shot: the slide snaps back on the recoil and the spring drives it home again - a tenth of a second
		float s = f.shot();
		if (s >= 0.0F && s < 1.3F) {
			return s < 0.4F ? smooth(s / 0.4F) : 1.0F - smooth((s - 0.4F) / 0.9F);
		}
		return 0.0F;
	}

	/** The old magazine leaving the grip: its transform from the seat (null: none to show). */
	public static Matrix4f oldMag(Frame f) {
		float t = f.reload() - GlockItem.T_RELEASE;
		if (f.reload() < 0.0F || !f.hadMag() || t < 0.0F || t > 11.0F) {
			return null;
		}
		// it slides out down the grip under its own weight, then tumbles away below
		float d = 0.006F * t + 0.5F * 0.0245F * t * t;
		Matrix4f m = new Matrix4f().translate(new Vector3f(GlockItemRenderer.MAG_DOWN).mul(Math.min(d, 0.12F)));
		if (d > 0.12F) {
			float fall = d - 0.12F;
			m.translate(0.01F * fall / 0.1F, -fall, 0.02F * fall / 0.1F);
			m.rotate(Axis.XP.rotationDegrees(-80.0F * fall / 0.3F));
			m.rotate(Axis.ZP.rotationDegrees(40.0F * fall / 0.3F));
		}
		return m;
	}

	/** The new magazine on its way in, in the support hand: its transform from the seat (null: seated, or not yet out). */
	public static Matrix4f newMag(Frame f) {
		float r = f.reload();
		if (r < 7.0F || r >= GlockItem.T_IN) {
			return null;
		}
		float length = f.newKind() == GlockMagazineItem.EXTENDED ? 0.2F : 0.115F;
		// held by its base in the support hand, then indexed into the well and pushed home
		Vector3f hand = supportHand(f);
		Vector3f seat = GlockItemRenderer.MAG_SEAT;
		Vector3f held = new Vector3f(hand).sub(new Vector3f(GlockItemRenderer.MAG_DOWN).mul(length - 0.02F)).sub(seat);
		// fresh out of the pouch it is tipped over; lined up with the well as it comes up
		float tip = 1.0F - ramp(r, 9.0F, 14.5F);
		Matrix4f m = new Matrix4f().translate(held);
		Vector3f base = new Vector3f(GlockItemRenderer.MAG_DOWN).mul(length - 0.02F);
		m.translate(base).rotate(Axis.XP.rotationDegrees(-55.0F * tip)).rotate(Axis.ZP.rotationDegrees(-25.0F * tip)).translate(new Vector3f(base).negate());
		return m;
	}

	/** Where the support (left) hand is, item space. */
	static Vector3f supportHand(Frame f) {
		float r = f.reload();
		if (r >= 0.0F) {
			float length = f.newKind() == GlockMagazineItem.EXTENDED ? 0.2F : 0.115F;
			Vector3f seat = GlockItemRenderer.MAG_SEAT;
			// the hand under the well, the magazine's base in the palm: the top 4 cm short of home, and home
			Vector3f below = new Vector3f(seat).add(new Vector3f(GlockItemRenderer.MAG_DOWN).mul(length - 0.02F + 0.045F));
			Vector3f home = new Vector3f(seat).add(new Vector3f(GlockItemRenderer.MAG_DOWN).mul(length - 0.02F));
			Vector3f slap = new Vector3f(home).add(new Vector3f(GlockItemRenderer.MAG_DOWN).mul(-0.006F));
			if (r < 7.0F) {
				return swing(SUPPORT, POUCH, ramp(r, 1.0F, 7.0F));
			}
			if (r < 9.0F) {
				return new Vector3f(POUCH).add(0.0F, 0.015F * Mth.sin((r - 7.0F) / 2.0F * Mth.PI), 0.0F); // the pouch flap, the magazine out
			}
			if (r < 15.0F) {
				return swing(POUCH, below, ramp(r, 9.0F, 15.0F));
			}
			if (r < GlockItem.T_IN) {
				return lerp(below, slap, ramp(r, 15.0F, GlockItem.T_IN));
			}
			if (!f.empty()) {
				return lerp(slap, SUPPORT, ramp(r, GlockItem.T_IN + 1.0F, GlockItem.RELOAD_TACTICAL - 1.0F));
			}
			// from slide lock: over the top of the slide, rack it, let it fly, back onto the grip
			Vector3f racked = new Vector3f(OVERHAND).add(-0.01F, 0.0F, 0.04F);
			if (r < GlockItem.T_RACK_BACK - 2.0F) {
				return swing(slap, OVERHAND, ramp(r, GlockItem.T_IN + 1.0F, GlockItem.T_RACK_BACK - 2.0F));
			}
			if (r < GlockItem.T_RACK_BACK) {
				return lerp(OVERHAND, racked, ramp(r, GlockItem.T_RACK_BACK - 2.0F, GlockItem.T_RACK_BACK));
			}
			if (r < GlockItem.T_RACK_FORWARD + 1.0F) {
				// let go: the hand flies on back and out to the left
				return lerp(racked, new Vector3f(racked).add(-0.05F, 0.02F, 0.05F), ramp(r, GlockItem.T_RACK_BACK, GlockItem.T_RACK_FORWARD + 1.0F));
			}
			return swing(new Vector3f(racked).add(-0.05F, 0.02F, 0.05F), SUPPORT, ramp(r, GlockItem.T_RACK_FORWARD + 1.0F, GlockItem.RELOAD_EMPTY - 1.0F));
		}
		float i = f.inspect();
		if (i >= 0.0F) {
			// off the grip as the gun turns, onto the front serrations for the press check, and back
			float off = ramp(i, 2.0F, 9.0F) * (1.0F - ramp(i, 50.0F, 58.0F));
			Vector3f away = new Vector3f(SUPPORT).add(-0.05F, -0.05F, 0.03F);
			Vector3f hand = lerp(SUPPORT, away, off);
			if (i > 18.0F && i < 40.0F) {
				float on = ramp(i, 19.0F, 24.0F) * (1.0F - ramp(i, 33.0F, 39.0F));
				Vector3f pull = new Vector3f(PRESS).add(0.0F, 0.0F, PRESS_TRAVEL * GlockItemRenderer.SLIDE_TRAVEL
					* ramp(i, PRESS_BACK - 1.5F, PRESS_BACK + 1.0F) * (1.0F - ramp(i, PRESS_HOME - 1.0F, PRESS_HOME)));
				hand = new Vector3f(hand).lerp(pull, on);
			}
			return hand;
		}
		// drawn: the support hand comes in from the side to meet the gun
		float d = ramp(f.drawn(), 5.0F, 11.0F);
		if (d < 1.0F) {
			return lerp(new Vector3f(SUPPORT).add(-0.12F, -0.16F, 0.1F), SUPPORT, d);
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

		// the reload: canted over to the left and drawn in close, the magwell turned towards the support hand;
		// the thumb's stab at the release, the slap of the new magazine, and from slide lock the overhand rack
		float r = f.reload();
		if (r >= 0.0F) {
			float end = f.empty() ? GlockItem.RELOAD_EMPTY : GlockItem.RELOAD_TACTICAL;
			float in = ramp(r, 0.0F, 4.0F) * (1.0F - ramp(r, end - 6.0F, end));
			float release = jolt(r - GlockItem.T_RELEASE + 0.5F, 4.0F);
			float slap = jolt(r - GlockItem.T_IN + 0.3F, 5.0F);
			float rack = f.empty() ? ramp(r, GlockItem.T_IN + 1.0F, GlockItem.T_RACK_BACK - 1.0F) * (1.0F - ramp(r, GlockItem.T_RACK_FORWARD, end - 3.0F)) : 0.0F;
			float yank = f.empty() ? jolt(r - GlockItem.T_RACK_BACK + 1.5F, 4.0F) : 0.0F;
			float home = f.empty() ? jolt(r - GlockItem.T_RACK_FORWARD + 0.4F, 5.0F) : 0.0F;
			poseStack.translate(-0.06F * in + 0.02F * rack, 0.14F * in + 0.012F * slap - 0.06F * rack, 0.03F * in - 0.16F * rack - 0.012F * yank);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-40.0F * in + 6.0F * release + 20.0F * rack - 4.0F * home));
			poseStack.mulPose(Axis.XP.rotationDegrees(12.0F * in + 4.0F * release - 3.0F * slap + 6.0F * rack + 3.0F * home));
			poseStack.mulPose(Axis.YP.rotationDegrees(12.0F * in - 4.0F * yank));
		}

		// looking it over: turned to show the right side and the port, the press check (brass in the chamber),
		// turned over to the left side, and back on line
		float i = f.inspect();
		if (i >= 0.0F && i < INSPECT) {
			float up = ramp(i, 0.0F, 9.0F) * (1.0F - ramp(i, INSPECT - 10.0F, INSPECT));
			float right = ramp(i, 2.0F, 12.0F) * (1.0F - ramp(i, 36.0F, 44.0F));
			float left = ramp(i, 38.0F, 46.0F) * (1.0F - ramp(i, INSPECT - 12.0F, INSPECT - 2.0F));
			float check = ramp(i, 20.0F, 25.0F) * (1.0F - ramp(i, 33.0F, 38.0F));
			float tug = jolt(i - PRESS_BACK + 1.0F, 4.0F);
			poseStack.translate(-0.06F * up, 0.06F * up, 0.08F * up);
			poseStack.mulPose(Axis.YP.rotationDegrees(48.0F * right - 32.0F * left - 10.0F * check));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-18.0F * right - 25.0F * left + 8.0F * check));
			poseStack.mulPose(Axis.XP.rotationDegrees(10.0F * up + 6.0F * left - 2.0F * tug));
		}

		// a click instead of a bang: the jerk, then a cant to look at the slide
		float dry = f.dry();
		if (dry >= 0.0F && dry < 22.0F) {
			float twitch = jolt(dry, 3.0F);
			float look = dry < 3.0F ? 0.0F : smooth((dry - 3.0F) / 5.0F) * (1.0F - smooth((dry - 14.0F) / 8.0F));
			poseStack.translate(0.0F, 0.03F * look - 0.004F * twitch, 0.02F * look);
			poseStack.mulPose(Axis.XP.rotationDegrees(-1.5F * twitch + 6.0F * look));
			poseStack.mulPose(Axis.ZP.rotationDegrees(35.0F * look));
		}

		// a long while doing nothing: a turn of the wrist to look at it, then back
		float idle = f.idle() - 260.0F;
		if (idle >= 0.0F && idle < 50.0F && a < 0.05F) {
			float look = smooth(idle / 10.0F) * (1.0F - smooth((idle - 32.0F) / 16.0F));
			poseStack.translate(0.0F, 0.03F * look, 0.03F * look);
			poseStack.mulPose(Axis.YP.rotationDegrees(30.0F * look));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-25.0F * look));
		}

		// drawn: out of the holster at the right hip - up, muzzle swinging from the ground onto the line,
		// punched out to the ready with a little overshoot
		float drawT = f.drawn() / 11.0F;
		float draw = 1.0F - smooth(drawT);
		if (draw > 0.0F) {
			float over = drawT > 0.55F && drawT < 1.0F ? Mth.sin((drawT - 0.55F) / 0.45F * Mth.PI) * 0.4F : 0.0F;
			float clear = Mth.sin(Mth.clamp(drawT * 1.3F, 0.0F, 1.0F) * Mth.PI);
			poseStack.translate(0.12F * draw, -0.36F * draw + 0.04F * clear, 0.14F * draw - 0.02F * over);
			poseStack.mulPose(Axis.XP.rotationDegrees(-75.0F * draw + 3.0F * over));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-20.0F * draw));
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
	public record Hands(Vector3f right, Vector3f left, Vector3f rightShoulder, Vector3f rightPole, Vector3f leftShoulder, Vector3f leftPole) {
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
		float in = r < 0.0F ? 0.0F : ramp(r, 0.0F, 4.0F) * (1.0F - ramp(r, (f.empty() ? GlockItem.RELOAD_EMPTY : GlockItem.RELOAD_TACTICAL) - 6.0F,
			f.empty() ? GlockItem.RELOAD_EMPTY : GlockItem.RELOAD_TACTICAL));
		Vector3f rightPole = new Vector3f(RIGHT_POLE).lerp(new Vector3f(1.0F, 0.1F, 0.5F), in);
		Vector3f grip = new Vector3f(GRIP).add(0.0F, 0.012F * in, 0.004F * in);
		return new Hands(grip, supportHand(f), rs, rightPole, ls, new Vector3f(LEFT_POLE));
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
