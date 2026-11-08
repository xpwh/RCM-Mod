package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.injury.Wounds;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * A round right through the trunk. The hole is cut out of the body itself ({@link HoledBox} with {@link #cut}),
 * front and back: small and neat where it went in, ragged and much bigger where it came out - you can see the
 * daylight through it. Between them the wound channel, raw; round both holes the shirt soaked with blood,
 * and out of the exit, shreds of tissue blown out with it. In the body's space (pixels: x -4..4 with +X the
 * player's left, y 0 neck .. 12, z -2 chest .. 2 back).
 */
public final class GoreHole {
	/** Built meshes, by the hole they are for: {channel and shreds, the soaked rings round the holes}. */
	private static final Map<Long, GoreMesh[]> MESHES = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, GoreMesh[]> eldest) {
			return this.size() > 48;
		}
	};

	private GoreHole() {
	}

	/** The hole of {@code w}: where it goes in and out, how big, how ragged. */
	private record Shape(float ix, float iy, float ox, float oy, float in, float out, float entry, float a1, float a2) {
	}

	private static Shape shape(Wounds w, int seed) {
		RandomSource r = RandomSource.create(seed * 31L + (w.extra() & 0xFFFC));
		int size = Math.max(1, w.holeSize());
		float ix = w.holeX();
		float iy = w.holeY();
		// it tumbles going through: out a little off the line it went in on
		float ox = Mth.clamp(ix + (r.nextFloat() - 0.5F) * 1.4F, -3.2F, 3.2F);
		float oy = Mth.clamp(iy + (r.nextFloat() - 0.3F) * 1.4F, 1.5F, 10.5F);
		float in = 0.7F + 0.3F * (size - 1);
		float out = 1.35F + 0.6F * (size - 1);
		return new Shape(ix, iy, ox, oy, in, out, w.holeFromBehind() ? 1.0F : -1.0F, r.nextFloat() * Mth.TWO_PI, r.nextFloat() * Mth.TWO_PI);
	}

	/** A hole's ragged outline: its radius {@code r} at angle {@code a}. */
	private static float ragged(float r, float a, Shape s, boolean exit) {
		return r * (1.0F + (exit ? 0.24F : 0.1F) * Mth.sin(3.0F * a + s.a1) + (exit ? 0.12F : 0.05F) * Mth.sin(5.0F * a + s.a2));
	}

	/** What of the body is gone: the holes in the front and back faces (the jacket's too). */
	public static GoreMesh.Cut cut(Wounds w, int seed) {
		Shape s = shape(w, seed);
		return (x, y, z) -> {
			if (Math.abs(z) < 1.9F) {
				return false;
			}
			boolean entrySide = Math.signum(z) == s.entry;
			float cx = entrySide ? s.ix : s.ox;
			float cy = entrySide ? s.iy : s.oy;
			float dx = x - cx;
			float dy = y - cy;
			float a = (float) Mth.atan2(dy, dx);
			float r = ragged(entrySide ? s.in : s.out, a, s, !entrySide);
			return dx * dx + dy * dy < r * r;
		};
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, Wounds w, int seed) {
		long key = (long) (w.extra() & 0xFFFC) << 32 | (seed & 0xFFFFFFFFL);
		GoreMesh[] m = MESHES.computeIfAbsent(key, k -> build(w, seed));
		m[0].submit(poseStack, collector, light);
		m[1].submit(poseStack, collector, light, cut(w, seed));
	}

	private static GoreMesh[] build(Wounds w, int seed) {
		Shape s = shape(w, seed);
		RandomSource r = RandomSource.create(seed * 17L + 5);
		GoreMesh.Builder b = new GoreMesh.Builder();
		// the channel, from hole to hole: its mouths just inside the holes' ragged edges (a raw rim round each),
		// wider within, so nothing of the body's inside shows past it
		float[] ts = {0.0F, 0.08F, 0.3F, 0.5F, 0.7F, 0.92F, 1.0F};
		Vector3f[] pts = new Vector3f[ts.length];
		float[] rad = new float[ts.length];
		for (int i = 0; i < ts.length; i++) {
			float t = ts[i];
			float wob = Mth.sin(t * Mth.PI) * 0.25F;
			pts[i] = new Vector3f(Mth.lerp(t, s.ix, s.ox) + wob, Mth.lerp(t, s.iy, s.oy) - wob, s.entry * (2.25F - 4.5F * t));
			rad[i] = i == 0 ? s.in * 0.85F : i == ts.length - 1 ? s.out * 0.62F : Mth.lerp(t * t, s.in * 1.15F, s.out * 1.38F);
		}
		b.path(pts, rad, GoreMesh.T_MEAT);
		// shreds of tissue blown out of the exit, and a string or two of blood
		float zOut = -s.entry * 2.35F;
		for (int i = 0; i < 6; i++) {
			float a = i / 6.0F * Mth.TWO_PI + r.nextFloat() * 0.6F;
			float rr = ragged(s.out, a, s, true);
			Vector3f base = new Vector3f(s.ox + Mth.cos(a) * rr * 0.92F, s.oy + Mth.sin(a) * rr * 0.92F, zOut);
			Vector3f dir = new Vector3f(Mth.cos(a) * 0.5F, Mth.sin(a) * 0.5F + 0.35F, -s.entry * 0.8F).normalize();
			b.shard(base, dir, new Vector3f(0, 0, 1), 0.6F + r.nextFloat() * 1.0F, 0.7F + r.nextFloat() * 0.4F,
				i % 3 == 2 ? GoreMesh.T_STRAND : i % 2 == 0 ? GoreMesh.T_FLAP : GoreMesh.T_CLOTH, 0.3F);
		}
		// a bit of bone splintered out with it
		float a = r.nextFloat() * Mth.TWO_PI;
		b.shard(new Vector3f(s.ox + Mth.cos(a) * s.out * 0.5F, s.oy + Mth.sin(a) * s.out * 0.5F, zOut * 0.9F),
			new Vector3f(Mth.cos(a) * 0.4F, Mth.sin(a) * 0.4F, -s.entry).normalize(), new Vector3f(0, 1, 0), 0.9F, 0.45F, GoreMesh.T_BONE_THIN, 0.1F);
		GoreMesh inside = b.build();
		// the shirt soaked round both holes, just beyond the jacket layer; the wound tile's middle is cut away with the hole
		GoreMesh.Builder rings = new GoreMesh.Builder();
		ring(rings, s.ix, s.iy, s.entry * 2.32F, s.in * 3.4F, GoreMesh.T_MOB + Math.floorMod(seed, 4), s.entry);
		ring(rings, s.ox, s.oy, -s.entry * 2.32F, s.out * 3.0F, GoreMesh.T_MOB + Math.floorMod(seed + 1, 4), -s.entry);
		return new GoreMesh[] {inside, rings.build()};
	}

	/** A soaked patch {@code size} across centred on (x, y), on the face at z, facing {@code side} (+1 back, -1 front). */
	private static void ring(GoreMesh.Builder b, float x, float y, float z, float size, int tile, float side) {
		float h = size * 0.5F;
		// the tile's run of blood goes down (+y); seen from the back, mirrored so it still runs down the right way
		Vector3f origin = side < 0 ? new Vector3f(x - h, y - h * 0.6F, z) : new Vector3f(x + h, y - h * 0.6F, z);
		Vector3f a = side < 0 ? new Vector3f(size, 0, 0) : new Vector3f(-size, 0, 0);
		b.decalGrid(origin, a, new Vector3f(0, size, 0), tile, new Vector3f(0, 0, side), 0.5F);
	}
}
