package de.rcm.ballistic.client.gun;

import com.mojang.math.Axis;
import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.gun.GunState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * One frame of the rifle's moving parts and of the hands that work them, in item space (origin at
 * the pistol grip, -Z forward, +Y up, +X right). Shared by the item renderer (bolt, magazine) and the
 * first-person arms, so a hand that holds the magazine and the magazine itself never drift apart.
 * <p>
 * Reload, in ticks from its start (the sounds are fired by the server at {@link AkItem#T_MAG_OUT},
 * {@link AkItem#T_MAG_IN} and {@link AkItem#T_CHARGE}):
 * <pre>
 *  0- 8  left hand leaves the handguard and takes hold of the magazine
 *  8-12  thumb on the release paddle
 * 12-17  magazine rocked forward round its front lug (the bottom swings forward) and out of the well
 * 17-24  old magazine carried down to the pouch (off screen) ...
 * 24-30  ... the fresh one brought up, nose first into the well
 * 30-33  rocked back until the catch snaps
 * 33-36  a tug to check it is seated
 * 36-44  left hand back to the handguard
 * empty gun only:
 * 40-50  right hand leaves the grip, reaches over to the charging handle
 * 52-55  handle pulled all the way back ... 55-57 let go: it slams home
 * 57-66  right hand back on the grip
 * </pre>
 */
public final class AkAnim {
	/** Where the fist sits on the pistol grip. */
	public static final Vector3f GRIP = new Vector3f(0.0F, -0.04F, 0.088F);
	/** Where the support hand cups the lower handguard. */
	public static final Vector3f HANDGUARD = new Vector3f(-0.004F, 0.012F, -0.37F);
	/** The point of the magazine (in its own space) the hand closes on. */
	static final Vector3f MAG_HOLD = new Vector3f(-0.006F, -0.105F, -0.098F);
	/** The ball of the charging handle, bolt forward. */
	static final Vector3f HANDLE = new Vector3f(0.092F, 0.098F, 0.004F);
	/** Off-screen spot by the chest rig where magazines come from and go to. */
	static final Vector3f POUCH = new Vector3f(-0.26F, -0.46F, 0.3F);
	/** The way to the pouch and back, over {@code away} = 0..1: {away, x, y, z, roll, pitch}. */
	private static final Keys POUCH_PATH = new Keys(
		new float[] {0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F},
		new float[] {0.3F, -0.03F, -0.1F, -0.005F, -8.0F, -6.0F},
		new float[] {0.65F, -0.12F, -0.27F, 0.08F, -22.0F, -16.0F},
		new float[] {1.0F, POUCH.x, POUCH.y, POUCH.z, -35.0F, -25.0F});
	/** The old magazine rocking out of the well: thumb on the paddle, a firm push forward, out. */
	private static final Keys OLD_OUT = new Keys(
		new float[] {0.0F, 0.0F}, new float[] {11.0F, 0.0F}, new float[] {13.0F, 0.35F}, new float[] {15.5F, 0.95F}, new float[] {17.0F, 1.0F});
	/** When the fresh magazine takes over from the old one (both out of view by then). */
	private static final float FRESH = 23.0F;
	/** Reload (by its start) during which the rifle still had a magazine in before it was let fall. */
	private long hadMag = Long.MIN_VALUE;

	/** Bolt travel at full recoil. */
	public static final float BOLT_TRAVEL = 0.115F;

	public float bolt;
	public boolean magVisible;
	public boolean magTracer;
	/** Rounds showing in the feed lips. */
	public boolean magLoaded;
	public final Matrix4f mag = new Matrix4f();
	/** A second magazine in the hand (the fresh one, during a magazine-to-magazine reload). */
	public final Matrix4f mag2 = new Matrix4f();
	public boolean mag2Visible;
	public boolean mag2Tracer;
	public final Vector3f leftHand = new Vector3f();
	public final Vector3f rightHand = new Vector3f();

	static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** Magazine seated (out = 0), rocked forward around the front catch (out = 1), then carried away (away = 0..1). */
	private static void magPose(Matrix4f m, float out, float away) {
		m.identity();
		if (away > 0.0F) {
			// down past the trigger guard, then back and out of view to the pouch: {x, y, z, roll, pitch}
			float a = Mth.clamp(away, 0.0F, 1.0F);
			m.translate(POUCH_PATH.at(a, 0), POUCH_PATH.at(a, 1), POUCH_PATH.at(a, 2));
			m.rotate(Axis.ZP.rotationDegrees(POUCH_PATH.at(a, 3)));
			m.rotate(Axis.XP.rotationDegrees(POUCH_PATH.at(a, 4)));
		}
		m.translate(0.0F, 0.03F, -0.085F);
		m.rotate(Axis.XP.rotationDegrees(28.0F * out)); // round the front lug: the bottom swings forward
		m.translate(0.0F, -0.03F - 0.05F * out * out, 0.085F);
	}

	/**
	 * Checking the magazine: the hand takes it, thumbs the release, rocks it out and tips it back so the
	 * rounds in the lips are in view, holds it there a moment, and rocks it home again.
	 */
	private void magCheck(float t) {
		float out;
		float show;
		if (t < 11.0F) {
			out = 0.0F;
			show = 0.0F;
		} else if (t < 16.0F) {
			out = smooth((t - 11.0F) / 5.0F);
			show = smooth((t - 12.0F) / 5.0F);
		} else if (t < 25.0F) {
			out = 1.0F;
			show = 1.0F;
		} else if (t < 29.0F) {
			out = 1.0F - smooth((t - 25.0F) / 4.0F);
			show = 1.0F - smooth((t - 24.0F) / 4.0F);
		} else {
			out = 0.0F;
			show = 0.0F;
		}
		Matrix4f m = this.mag;
		magPose(m, out * 0.8F, 0.0F);
		if (show > 0.0F) {
			// pulled clear and tipped back, top towards the eye
			m.translate(0.0F, -0.045F * show, 0.03F * show);
			m.translate(0.0F, 0.036F, -0.06F);
			m.rotate(Axis.XP.rotationDegrees(38.0F * show));
			m.rotate(Axis.ZP.rotationDegrees(-12.0F * show));
			m.translate(0.0F, -0.036F, 0.06F);
		}
		Vector3f hold = m.transformPosition(MAG_HOLD, new Vector3f());
		if (t < 7.0F) {
			Matrix4f seated = new Matrix4f();
			magPose(seated, 0.0F, 0.0F);
			swing(HANDGUARD, seated.transformPosition(MAG_HOLD, new Vector3f()), t / 7.0F, this.leftHand);
		} else if (t < 31.0F) {
			this.leftHand.set(hold);
		} else {
			swing(hold, HANDGUARD, (t - 31.0F) / 8.0F, this.leftHand);
		}
	}

	private static Vector3f lerp(Vector3f a, Vector3f b, float t, Vector3f out) {
		return out.set(a).lerp(b, smooth(t));
	}

	/** The support hand between the handguard and the magazine: around the outside and under, not through the rifle. */
	private static Vector3f swing(Vector3f a, Vector3f b, float t, Vector3f out) {
		lerp(a, b, t, out);
		float bulge = Mth.sin(Mth.clamp(t, 0.0F, 1.0F) * Mth.PI);
		return out.add(-0.035F * bulge, -0.03F * bulge, 0.0F);
	}

	/** The fresh magazine through a magazine-to-magazine reload: {tick, x, y, z, roll, pitch (about its top), rocked out}. */
	private static final Keys TAC_FRESH = new Keys(
		new float[] {5.0F, POUCH.x, POUCH.y, POUCH.z, -35.0F, -25.0F, 0.0F},
		new float[] {8.0F, -0.12F, -0.27F, 0.12F, -22.0F, -16.0F, 0.0F},
		// up behind the old one, parallel to it ...
		new float[] {11.0F, -0.006F, -0.05F, 0.08F, -5.0F, -6.0F, 0.0F},
		// ... drawn back a hair for the swing ...
		new float[] {12.8F, -0.006F, -0.058F, 0.095F, -5.0F, -3.0F, 0.0F},
		// ... and struck forward against it: the paddle gives and the old one is knocked away
		new float[] {AkItem.T_TAC_OUT, -0.003F, -0.034F, 0.045F, -2.0F, -10.0F, 0.0F},
		// on through into the space it left, nose forward
		new float[] {15.5F, -0.002F, -0.048F, 0.02F, 0.0F, -4.0F, 0.6F},
		new float[] {17.3F, 0.0F, -0.028F, 0.0F, 0.0F, 0.0F, 1.0F},
		new float[] {19.0F, 0.0F, -0.004F, 0.0F, 0.0F, 0.0F, 1.0F},
		new float[] {AkItem.T_TAC_IN, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F});
	/** Where a magazine's top sits (the pivot for tilting one in the hand). */
	private static final Vector3f MAG_TOP = new Vector3f(0.0F, 0.036F, -0.06F);
	/** The middle of a magazine, about which a knocked-out one tumbles. */
	private static final Vector3f MAG_MIDDLE = new Vector3f(0.0F, -0.1F, -0.11F);

	/**
	 * A reload with rounds left, magazine to magazine: the left hand fetches a full one from the pouch,
	 * brings it up behind the old one and strikes forward with it - the paddle gives and the old one is
	 * knocked out of the well and away, tumbling forward to the ground - and carries straight on into the
	 * well, nose first, rocking back until it clicks; then the hand goes back to the handguard.
	 */
	private AkAnim magToMag(GunState state, float r) {
		if (r < AkItem.T_TAC_OUT + 1 && state.hasMag()) {
			this.hadMag = state.reloadStart();
		}
		boolean oldMag = state.hasMag() || this.hadMag == state.reloadStart();

		Matrix4f fresh = this.mag2;
		fresh.identity().translate(TAC_FRESH.at(r, 0), TAC_FRESH.at(r, 1), TAC_FRESH.at(r, 2));
		fresh.translate(MAG_TOP).rotate(Axis.ZP.rotationDegrees(TAC_FRESH.at(r, 3))).rotate(Axis.XP.rotationDegrees(TAC_FRESH.at(r, 4)));
		fresh.translate(-MAG_TOP.x, -MAG_TOP.y, -MAG_TOP.z);
		Matrix4f rock = new Matrix4f();
		magPose(rock, Mth.clamp(TAC_FRESH.at(r, 5), 0.0F, 1.0F), 0.0F);
		fresh.mul(rock);
		this.mag2Visible = r >= 5.5F && r < AkItem.T_TAC_IN;
		this.mag2Tracer = state.reloadAmmo() == GunState.TRACER;

		// the old one: knocked out of the well, flying off forward and down, tumbling
		Matrix4f old = this.mag;
		float hit = AkItem.T_TAC_OUT - 0.4F;
		magPose(old, Keys.ease(r, hit, hit + 1.2F), 0.0F);
		float f = Math.max(0.0F, r - (hit + 0.8F));
		if (f > 0.0F) {
			old.translateLocal(-0.006F * f, 0.01F * f - 0.016F * f * f, -0.055F * f);
			old.translate(MAG_MIDDLE).rotate(Axis.XP.rotationDegrees(22.0F * f)).rotate(Axis.ZP.rotationDegrees(5.0F * f));
			old.translate(-MAG_MIDDLE.x, -MAG_MIDDLE.y, -MAG_MIDDLE.z);
		}
		this.magVisible = oldMag && f < 8.0F;
		this.magLoaded = true;
		if (r >= AkItem.T_TAC_IN) {
			// the fresh one is in the rifle now
			magPose(this.mag, 0.0F, 0.0F);
			if (r < AkItem.T_TAC_IN + 3) {
				// the tug against the catch
				this.mag.translate(0.0F, -0.006F * Mth.sin((r - AkItem.T_TAC_IN) / 3.0F * Mth.PI), 0.0F);
			}
			this.magVisible = true;
			this.magTracer = state.reloadAmmo() == GunState.TRACER;
		}

		Matrix4f seated = new Matrix4f();
		magPose(seated, 0.0F, 0.0F);
		if (r < 5.0F) {
			// down to the pouch
			Matrix4f pouch = new Matrix4f();
			magPose(pouch, 0.0F, 1.0F);
			swing(HANDGUARD, pouch.transformPosition(MAG_HOLD, new Vector3f()), r / 5.0F, this.leftHand);
		} else if (r < AkItem.T_TAC_IN) {
			fresh.transformPosition(MAG_HOLD, this.leftHand);
		} else if (r < AkItem.T_TAC_IN + 3) {
			this.mag.transformPosition(MAG_HOLD, this.leftHand);
		} else {
			swing(this.mag.transformPosition(MAG_HOLD, new Vector3f()), HANDGUARD, (r - (AkItem.T_TAC_IN + 3)) / 8.0F, this.leftHand);
		}
		return this;
	}

	/**
	 * @param lastShot game tick of the last shot (the client's own for the local player)
	 */
	public AkAnim compute(GunState state, float now, long lastShot) {
		return this.compute(state, now, lastShot, -1.0F);
	}

	/**
	 * @param check ticks into a magazine check (local player, first person), or -1
	 */
	public AkAnim compute(GunState state, float now, long lastShot, float check) {
		float shot = now - lastShot;
		this.bolt = shot >= 0.0F && shot < AkItem.CYCLE ? Mth.sin(shot / AkItem.CYCLE * Mth.PI) : 0.0F;
		this.magVisible = state.hasMag();
		this.magTracer = state.ammo() == GunState.TRACER;
		magPose(this.mag, 0.0F, 0.0F);
		this.magLoaded = state.rounds() > 0;
		this.leftHand.set(HANDGUARD);
		this.rightHand.set(GRIP);
		this.mag2Visible = false;
		if (!state.reloading()) {
			if (check >= 0.0F && state.hasMag()) {
				this.magCheck(check);
			}
			return this;
		}

		float r = now - state.reloadStart();
		boolean empty = state.reloadKind() == GunState.EMPTY;
		if (!empty) {
			return this.magToMag(state, r);
		}
		if (r < AkItem.T_MAG_DROP && state.hasMag()) {
			this.hadMag = state.reloadStart();
		}
		boolean oldMag = state.hasMag() || this.hadMag == state.reloadStart();
		// a speed reload lets the old magazine go once it is out of the well
		boolean dropped = empty && oldMag && r >= AkItem.T_MAG_DROP;
		Matrix4f m = this.mag;
		Vector3f hold = new Vector3f();
		Matrix4f seated = new Matrix4f();
		magPose(seated, 0.0F, 0.0F);
		Vector3f seatedHold = seated.transformPosition(MAG_HOLD, new Vector3f());

		if (r < FRESH) {
			// the old magazine: rocked forward out of the well, then away to the pouch (or let fall)
			float out = Math.max(0.0F, OLD_OUT.at(r, 0));
			if (dropped) {
				magPose(m, OLD_OUT.at(AkItem.T_MAG_DROP, 0), 0.0F);
				float f = r - AkItem.T_MAG_DROP;
				// falling free: tumbling forward as it drops away below the rifle
				m.translateLocal(0.0F, -0.017F * f * f, 0.008F * f);
				m.translate(0.0F, -0.1F, -0.06F);
				m.rotate(Axis.XP.rotationDegrees(9.0F * f));
				m.rotate(Axis.ZP.rotationDegrees(3.0F * f));
				m.translate(0.0F, 0.1F, 0.06F);
				this.magVisible = f < 7.0F;
				this.magLoaded = false;
			} else {
				magPose(m, out, Keys.ease(r, 15.5F, FRESH));
				this.magVisible = oldMag;
			}
		} else {
			// the fresh one: up from the pouch nose first, hooked in at the front and rocked back until it clicks
			float away = 1.0F - Keys.ease(r, FRESH, 29.0F);
			float out = 1.0F - Keys.ease(r, 28.6F, 31.2F);
			magPose(m, out, away);
			if (r >= AkItem.T_MAG_IN + 3 && r < AkItem.T_MAG_IN + 6) {
				// the tug: pulled down a hair against the catch
				m.translate(0.0F, -0.006F * Mth.sin((r - AkItem.T_MAG_IN - 3) / 3.0F * Mth.PI), 0.0F);
			}
			this.magVisible = true;
			this.magLoaded = true;
			this.magTracer = state.reloadAmmo() == GunState.TRACER;
		}
		m.transformPosition(MAG_HOLD, hold);

		// the support hand: round the outside to the magazine, with it all the way, and back to the handguard
		if (r < 9.0F) {
			swing(HANDGUARD, oldMag ? seatedHold : new Vector3f(seatedHold).add(0.0F, -0.04F, 0.0F), r / 9.0F, this.leftHand);
		} else if (dropped && r < FRESH) {
			// let go: straight on down to the pouch for a full one
			Matrix4f pouch = new Matrix4f();
			magPose(pouch, 1.0F, Keys.ease(r, AkItem.T_MAG_DROP, FRESH));
			pouch.transformPosition(MAG_HOLD, this.leftHand);
		} else if (!oldMag && r < FRESH) {
			Matrix4f pouch = new Matrix4f();
			magPose(pouch, 1.0F, Keys.ease(r, 9.0F, FRESH));
			pouch.transformPosition(MAG_HOLD, this.leftHand);
		} else if (r < 36.0F) {
			this.leftHand.set(hold);
		} else {
			swing(hold, HANDGUARD, (r - 36.0F) / 9.0F, this.leftHand);
		}

		if (state.reloadKind() == GunState.EMPTY) {
			float c = r - AkItem.T_CHARGE;
			Vector3f handle = new Vector3f(HANDLE);
			if (c >= 0.0F && c < 3.0F) {
				this.bolt = smooth(c / 2.6F);
			} else if (c >= 3.0F && c < 5.2F) {
				this.bolt = 1.0F - (c - 3.0F) / 2.2F;
			}
			if (c < 0.0F) {
				// up and over the receiver to the handle
				float f = (r - 40.0F) / 10.0F;
				lerp(GRIP, handle, f, this.rightHand).add(0.02F * Mth.sin(Mth.clamp(f, 0.0F, 1.0F) * Mth.PI), 0.045F * Mth.sin(Mth.clamp(f, 0.0F, 1.0F) * Mth.PI), 0.0F);
			} else if (c < 3.0F) {
				this.rightHand.set(handle).add(0.0F, 0.0F, BOLT_TRAVEL * this.bolt);
			} else if (c < 5.0F) {
				// let go: the hand stays back, opening, while the carrier flies home
				this.rightHand.set(handle).add(0.012F * (c - 3.0F), 0.006F * (c - 3.0F), BOLT_TRAVEL);
			} else {
				Vector3f back = new Vector3f(handle).add(0.024F, 0.012F, BOLT_TRAVEL);
				lerp(back, GRIP, (c - 5.0F) / 9.0F, this.rightHand);
			}
		}
		return this;
	}
}
