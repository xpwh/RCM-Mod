package de.rcm.ballistic.client.gun;

import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.gun.GunState;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The rifle's animations, keyframed in Blender (tools/ak47_blender/anim.py) and sampled four times a tick:
 * the rifle's pose in the hand, both magazines, both hands, the bolt and which magazine shows, for every
 * reload, the magazine check, looking it over, the dry click and the draw. Played back here with the
 * samples blended, so they run smooth at any frame rate.
 */
public final class AkClips {
	/**
	 * Floats per sample: gun x y z roll pitch yaw | mag 3x4 | mag2 3x4 | left xyz | right xyz | bolt | mag shown | mag2 shown | fresh |
	 * left hand turn xyzw | right hand turn xyzw | head pitch yaw roll.
	 */
	static final int STRIDE = 51;
	static final int LEFT_TURN = 40;
	static final int RIGHT_TURN = 44;
	static final int HEAD = 48;
	private static final Map<String, Clip> CLIPS = load("/assets/ballisticmissiles/meshes/ak47.anim.gz");
	/** The reload (by its start tick) during which the rifle still had a magazine in it. */
	private static long hadMag = Long.MIN_VALUE;

	private AkClips() {
	}

	/** One clip: {@code length} ticks, {@code perTick} samples a tick. */
	record Clip(int length, int perTick, float[] data) {
		/** The sample at {@code t} ticks (clamped to the clip), blended between its neighbours; the flags are not blended. */
		float[] at(float t, float[] out) {
			int count = this.data.length / STRIDE;
			float s = Mth.clamp(t, 0.0F, this.length) * this.perTick;
			int i = Math.min((int) s, count - 1);
			int j = Math.min(i + 1, count - 1);
			float f = s - i;
			for (int k = 0; k < STRIDE; k++) {
				float a = this.data[i * STRIDE + k];
				out[k] = k >= 37 && k < LEFT_TURN ? a : a + (this.data[j * STRIDE + k] - a) * f;
			}
			return out;
		}
	}

	static Clip get(String name) {
		return CLIPS.get(name);
	}

	/**
	 * One of a reload's takes (the clip, "@1", "@2": the same events, the motion a little different), picked by
	 * {@code seed} - the reload's start, so every player sees the same take.
	 */
	static Clip take(Map<String, Clip> clips, String name, long seed) {
		long h = seed * 0x9E3779B97F4A7C15L;
		int n = (int) Long.remainderUnsigned(h ^ (h >>> 29), 3L);
		Clip c = n == 0 ? null : clips.get(name + "@" + n);
		return c != null ? c : clips.get(name);
	}

	/** A hand's turn from a sample (at LEFT_TURN or RIGHT_TURN), normalised (the blend between samples is not). */
	static Quaternionf turn(float[] s, int at, Quaternionf out) {
		out.set(s[at], s[at + 1], s[at + 2], s[at + 3]);
		float l = out.lengthSquared();
		return l < 1.0E-8F ? out.identity() : out.normalize();
	}

	/**
	 * The clip for the reload under way: magazine to magazine with rounds left; for an empty rifle, with the
	 * old magazine to let fall or, when there was none, straight to the pouch.
	 */
	static Clip reload(GunState state, float r) {
		if (state.reloadKind() == GunState.CLEAR) {
			return take(CLIPS, "clear_jam", state.reloadStart());
		}
		if (state.reloadKind() != GunState.EMPTY) {
			// under pressure it can go wrong: fumbled at the well, or the fresh magazine dropped
			return take(CLIPS, switch (state.reloadVariant()) {
				case GunState.FUMBLE -> "reload_tactical_fumble";
				case GunState.DROPPED -> "reload_tactical_drop";
				default -> "reload_tactical";
			}, state.reloadStart());
		}
		if (r < AkItem.T_MAG_DROP && state.hasMag()) {
			hadMag = state.reloadStart();
		}
		return take(CLIPS, state.hasMag() || hadMag == state.reloadStart() ? "reload_empty" : "reload_nomag", state.reloadStart());
	}

	/** A magazine's matrix from a sample (3x4, row by row, from {@code at}). */
	static Matrix4f matrix(float[] s, int at, Matrix4f out) {
		out.identity();
		out.m00(s[at]).m10(s[at + 1]).m20(s[at + 2]).m30(s[at + 3]);
		out.m01(s[at + 4]).m11(s[at + 5]).m21(s[at + 6]).m31(s[at + 7]);
		out.m02(s[at + 8]).m12(s[at + 9]).m22(s[at + 10]).m32(s[at + 11]);
		return out;
	}

	static Vector3f vec(float[] s, int at, Vector3f out) {
		return out.set(s[at], s[at + 1], s[at + 2]);
	}

	static Map<String, Clip> load(String path) {
		Map<String, Clip> out = new HashMap<>();
		try (InputStream raw = AkClips.class.getResourceAsStream(path)) {
			if (raw == null) {
				throw new IllegalStateException("missing " + path);
			}
			DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)));
			int clips = in.readInt();
			for (int c = 0; c < clips; c++) {
				String name = in.readUTF();
				int length = in.readInt();
				int perTick = in.readInt();
				int samples = in.readInt();
				float[] data = new float[samples * STRIDE];
				for (int i = 0; i < data.length; i++) {
					data[i] = in.readFloat();
				}
				out.put(name, new Clip(length, perTick, data));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return out;
	}
}
