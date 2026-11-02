package de.rcm.ballistic.client.gun;

import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.gun.GunState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One frame of the rifle's moving parts and of the hands that work them, in item space (origin at
 * the pistol grip, -Z forward, +Y up, +X right). Shared by the item renderer (bolt, magazine) and the
 * first-person arms, so a hand that holds the magazine and the magazine itself never drift apart.
 * <p>
 * The motions are keyframed in Blender ({@link AkClips}); the server fires the sounds at the same ticks
 * ({@link AkItem#T_MAG_OUT}, {@link AkItem#T_MAG_IN}, {@link AkItem#T_CHARGE}, {@link AkItem#T_TAC_OUT},
 * {@link AkItem#T_TAC_IN}). A reload with rounds left goes magazine to magazine - the fresh one knocks the
 * old one out of the well; an empty one rocks the magazine out and lets it fall, seats a fresh one and
 * racks the charging handle. Firing cycles the bolt on its own.
 */
public final class AkAnim {
	/** Where the fist sits on the pistol grip. */
	public static final Vector3f GRIP = new Vector3f(0.0F, -0.04F, 0.082F);
	/** Where the support hand cups the lower handguard. */
	public static final Vector3f HANDGUARD = new Vector3f(-0.004F, 0.026F, -0.335F);
	/** Off-screen spot by the chest rig where magazines come from and go to. */
	static final Vector3f POUCH = new Vector3f(-0.26F, -0.46F, 0.3F);
	/** Bolt travel at full recoil. */
	public static final float BOLT_TRAVEL = 0.115F;

	public float bolt;
	public boolean magVisible;
	public int magTracer;
	/** Rounds showing in the feed lips. */
	public boolean magLoaded;
	public final Matrix4f mag = new Matrix4f();
	/** A second magazine in the hand (the fresh one, during a magazine-to-magazine reload). */
	public final Matrix4f mag2 = new Matrix4f();
	public boolean mag2Visible;
	public int mag2Tracer;
	public final Vector3f leftHand = new Vector3f();
	public final Vector3f rightHand = new Vector3f();
	/** Which way the hands are turned (item space): the turn of what they hold, a forearm rolled over. */
	public final Quaternionf leftTurn = new Quaternionf();
	public final Quaternionf rightTurn = new Quaternionf();
	private final float[] sample = new float[AkClips.STRIDE];

	/** Pose the hands, magazines and bolt from a clip at {@code t} ticks; returns the sample. */
	private float[] play(AkClips.Clip clip, float t) {
		float[] s = clip.at(t, this.sample);
		AkClips.matrix(s, 6, this.mag);
		AkClips.matrix(s, 18, this.mag2);
		AkClips.vec(s, 30, this.leftHand);
		AkClips.vec(s, 33, this.rightHand);
		AkClips.turn(s, AkClips.LEFT_TURN, this.leftTurn);
		AkClips.turn(s, AkClips.RIGHT_TURN, this.rightTurn);
		this.bolt = Math.max(this.bolt, s[36]);
		return s;
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
		return this.compute(state, now, lastShot, check, -1.0F);
	}

	/**
	 * @param inspect ticks into looking the rifle over (local player, first person), or -1
	 */
	public AkAnim compute(GunState state, float now, long lastShot, float check, float inspect) {
		float shot = now - lastShot;
		this.bolt = shot >= 0.0F && shot < AkItem.CYCLE ? Mth.sin(shot / AkItem.CYCLE * Mth.PI) : 0.0F;
		this.magVisible = state.hasMag();
		this.magTracer = state.ammo();
		this.mag.identity();
		this.magLoaded = state.rounds() > 0;
		this.leftHand.set(HANDGUARD);
		this.rightHand.set(GRIP);
		this.leftTurn.identity();
		this.rightTurn.identity();
		this.mag2Visible = false;
		if (!state.reloading()) {
			if (check >= 0.0F && state.hasMag()) {
				this.play(AkClips.get("check"), check);
			} else if (inspect >= 0.0F) {
				this.play(AkClips.get("inspect"), inspect);
			}
			return this;
		}
		float r = now - state.reloadStart();
		float[] s = this.play(AkClips.reload(state, r), r);
		boolean fresh = s[39] > 0.5F;
		this.magVisible = s[37] > 0.5F;
		this.mag2Visible = s[38] > 0.5F;
		this.mag2Tracer = state.reloadAmmo();
		if (fresh) {
			this.magTracer = state.reloadAmmo();
			this.magLoaded = true;
		} else {
			// the old magazine: knocked out still full on a magazine-to-magazine reload, empty otherwise
			this.magLoaded = state.reloadKind() != GunState.EMPTY;
		}
		return this;
	}
}
