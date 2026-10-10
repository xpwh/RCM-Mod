package de.rcm.ballistic.client.gun;

import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * Keyframed animation curves: smooth (Catmull-Rom) through every key, so a motion flows on into the
 * next one instead of stopping dead at each key. Before the first key and after the last the curve
 * holds the end value.
 */
final class Keys {
	private final float[] times;
	private final float[][] values;

	/** @param keys rows of {time, value, value, ...}, times rising */
	Keys(float[]... keys) {
		this.times = new float[keys.length];
		this.values = new float[keys.length][];
		for (int i = 0; i < keys.length; i++) {
			this.times[i] = keys[i][0];
			this.values[i] = java.util.Arrays.copyOfRange(keys[i], 1, keys[i].length);
		}
	}

	/** Channel {@code c} at time {@code t}. */
	float at(float t, int c) {
		float[] ts = this.times;
		int n = ts.length;
		if (t <= ts[0]) {
			return this.values[0][c];
		}
		if (t >= ts[n - 1]) {
			return this.values[n - 1][c];
		}
		int i = 0;
		while (t > ts[i + 1]) {
			i++;
		}
		float t0 = ts[i];
		float t1 = ts[i + 1];
		float h = t1 - t0;
		float u = (t - t0) / h;
		float p0 = this.values[i][c];
		float p1 = this.values[i + 1][c];
		// tangents from the neighbouring keys (non-uniform Catmull-Rom); flat at the ends
		float m0 = i > 0 ? (p1 - this.values[i - 1][c]) / (t1 - ts[i - 1]) * h : 0.0F;
		float m1 = i + 2 < n ? (this.values[i + 2][c] - p0) / (ts[i + 2] - t0) * h : 0.0F;
		float u2 = u * u;
		float u3 = u2 * u;
		return (2 * u3 - 3 * u2 + 1) * p0 + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * p1 + (u3 - u2) * m1;
	}

	Vector3f at(float t, Vector3f out) {
		return out.set(this.at(t, 0), this.at(t, 1), this.at(t, 2));
	}

	/** Eased 0..1 between {@code a} and {@code b}. */
	static float ease(float t, float a, float b) {
		float x = Mth.clamp((t - a) / (b - a), 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}
}
