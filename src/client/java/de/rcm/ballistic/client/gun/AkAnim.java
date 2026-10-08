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
 * 12-17  magazine rocked forward and out of the well
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
	/** Bolt travel at full recoil. */
	public static final float BOLT_TRAVEL = 0.115F;

	public float bolt;
	public boolean magVisible;
	public boolean magTracer;
	public final Matrix4f mag = new Matrix4f();
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
			float a = smooth(away);
			m.translate(POUCH.x * a, POUCH.y * a + 0.06F * Mth.sin(a * Mth.PI), POUCH.z * a);
			m.rotate(Axis.ZP.rotationDegrees(-35.0F * a));
			m.rotate(Axis.XP.rotationDegrees(-25.0F * a));
		}
		m.translate(0.0F, 0.03F, -0.085F);
		m.rotate(Axis.XP.rotationDegrees(-28.0F * out));
		m.translate(0.0F, -0.03F - 0.05F * out * out, 0.085F);
	}

	private static Vector3f lerp(Vector3f a, Vector3f b, float t, Vector3f out) {
		return out.set(a).lerp(b, smooth(t));
	}

	/**
	 * @param lastShot game tick of the last shot (the client's own for the local player)
	 */
	public AkAnim compute(GunState state, float now, long lastShot) {
		float shot = now - lastShot;
		this.bolt = shot >= 0.0F && shot < AkItem.CYCLE ? Mth.sin(shot / AkItem.CYCLE * Mth.PI) : 0.0F;
		this.magVisible = state.hasMag();
		this.magTracer = state.ammo() == GunState.TRACER;
		magPose(this.mag, 0.0F, 0.0F);
		this.leftHand.set(HANDGUARD);
		this.rightHand.set(GRIP);
		if (!state.reloading()) {
			return this;
		}

		float r = now - state.reloadStart();
		Vector3f hold = new Vector3f();
		Matrix4f m = this.mag;
		float out = 0.0F;
		float away = 0.0F;
		boolean fresh = false;
		if (r < AkItem.T_MAG_OUT) {
			// reaching for the magazine
			magPose(m, 0.0F, 0.0F);
			m.transformPosition(MAG_HOLD, hold);
			lerp(HANDGUARD, hold, r / 8.0F, this.leftHand);
		} else if (r < 17.0F) {
			out = (r - AkItem.T_MAG_OUT) / 5.0F;
		} else if (r < 24.0F) {
			out = 1.0F;
			away = (r - 17.0F) / 7.0F;
		} else if (r < AkItem.T_MAG_IN) {
			fresh = true;
			out = 1.0F;
			away = 1.0F - (r - 24.0F) / 6.0F;
		} else if (r < AkItem.T_MAG_IN + 3) {
			fresh = true;
			out = 1.0F - smooth((r - AkItem.T_MAG_IN) / 3.0F);
		} else {
			fresh = true;
		}
		if (r >= AkItem.T_MAG_OUT) {
			magPose(m, smooth(out), away);
			if (r >= AkItem.T_MAG_IN + 3 && r < AkItem.T_MAG_IN + 6) {
				// the tug: pulled down a hair against the catch
				m.translate(0.0F, -0.006F * Mth.sin((r - AkItem.T_MAG_IN - 3) / 3.0F * Mth.PI), 0.0F);
			}
			m.transformPosition(MAG_HOLD, hold);
			if (r < 36.0F) {
				this.leftHand.set(hold);
			} else {
				lerp(hold, HANDGUARD, (r - 36.0F) / 8.0F, this.leftHand);
			}
		}
		if (fresh) {
			this.magVisible = true;
			this.magTracer = state.reloadAmmo() == GunState.TRACER;
		} else if (r >= AkItem.T_MAG_OUT && !state.hasMag()) {
			this.magVisible = false;
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
				lerp(GRIP, handle, (r - 40.0F) / 10.0F, this.rightHand);
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
