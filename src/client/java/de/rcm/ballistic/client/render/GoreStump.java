package de.rcm.ballistic.client.render;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * What is left of a limb shot off - each built its own way from a seed, so no two are alike: the raw
 * cross-section of the limb (skin, fat, muscle, bone) bulging out of the cut, the two bones sticking out
 * splintered (shin and fibula, or radius and ulna), the trouser leg or sleeve and the skin torn into ragged,
 * blood-soaked rags round it, tags of muscle hanging, strings of blood. In the limb's space, in pixels: the
 * limb (4 x 4 px) ran down +Y and is cut off at y = 0.
 */
public final class GoreStump {
	public static final int VERSIONS = 4;
	private static final GoreMesh[] LEG = new GoreMesh[VERSIONS];
	private static final GoreMesh[] ARM = new GoreMesh[VERSIONS];
	public static final GoreMesh DROP = new GoreMesh.Builder().blob(new Vector3f(0, 0.25F, 0), 0.17F, 0.3F, 0.17F, 0.1F, 5L, GoreMesh.T_HEART).build();

	static {
		for (int v = 0; v < VERSIONS; v++) {
			LEG[v] = build(1000L + v * 31L, true);
			ARM[v] = build(2000L + v * 37L, false);
		}
	}

	private GoreStump() {
	}

	public static GoreMesh leg(int version) {
		return LEG[Math.floorMod(version, VERSIONS)];
	}

	public static GoreMesh arm(int version) {
		return ARM[Math.floorMod(version, VERSIONS)];
	}

	/** A cut-off end with no bones sticking out (a limb torn away from a body blown apart): skin, fat, muscle. */
	public static GoreMesh cap(float rx, float rz, long seed) {
		GoreMesh.Builder b = new GoreMesh.Builder();
		b.dome(new Vector3f(0, 0, 0), new Vector3f(0, 1, 0), new Vector3f(1, 0, 0), rx, rz, 0.4F + Math.min(rx, rz) * 0.15F, seed, GoreMesh.T_SECTION);
		RandomSource r = RandomSource.create(seed);
		for (int i = 0; i < 4; i++) {
			float a = r.nextFloat() * Mth.TWO_PI;
			Vector3f rim = new Vector3f(Mth.cos(a) * rx, -0.2F, Mth.sin(a) * rz);
			Vector3f out = new Vector3f(Mth.cos(a), 0, Mth.sin(a));
			b.shard(rim, new Vector3f(out).mul(0.4F).add(0, 1, 0).normalize(), new Vector3f(0, 1, 0), 0.6F + r.nextFloat() * 1.2F,
				0.6F + r.nextFloat() * 0.6F, r.nextBoolean() ? GoreMesh.T_FLAP : GoreMesh.T_STRAND, 0.2F);
		}
		return b.build();
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	private static GoreMesh build(long seed, boolean leg) {
		RandomSource r = RandomSource.create(seed);
		GoreMesh.Builder b = new GoreMesh.Builder();
		// the end of the limb raw: torn meat over the whole cut, the cross-section bulging out of it
		GoreHead.decal(b, v(-2.3F, 0.02F, -2.3F), v(4.6F, 0, 0), v(0, 0, 4.6F), GoreMesh.T_MEAT, v(0, 1, 0));
		b.dome(v((r.nextFloat() - 0.5F) * 0.4F, 0.0F, (r.nextFloat() - 0.5F) * 0.4F), v(0, 1, 0), v(1, 0, 0), 2.15F + r.nextFloat() * 0.2F,
			2.1F + r.nextFloat() * 0.2F, 0.45F + r.nextFloat() * 0.35F, seed, GoreMesh.T_SECTION);
		// the big bone, off the middle, broken off in splinters
		float bx = (r.nextFloat() - 0.3F) * 0.9F;
		float bz = -0.3F - r.nextFloat() * 0.5F;
		float len = 1.4F + r.nextFloat() * 1.8F;
		Vector3f tip = v(bx + (r.nextFloat() - 0.5F) * 0.5F, len, bz + (r.nextFloat() - 0.5F) * 0.5F);
		float rad = leg ? 0.55F : 0.45F;
		b.tube(v(bx, -0.3F, bz), tip, rad, rad * 0.85F, GoreMesh.T_BONE);
		b.dome(new Vector3f(tip).add(0, 0.02F, 0), v(0, 1, 0), v(1, 0, 0), rad * 0.8F, rad * 0.8F, 0.05F, seed + 1, GoreMesh.T_LIVER); // the marrow
		int splinters = 2 + r.nextInt(3);
		for (int i = 0; i < splinters; i++) {
			float a = r.nextFloat() * Mth.TWO_PI;
			Vector3f base = new Vector3f(tip).add(Mth.cos(a) * rad * 0.7F, -0.1F, Mth.sin(a) * rad * 0.7F);
			Vector3f dir = v(Mth.cos(a) * 0.35F, 1, Mth.sin(a) * 0.35F).normalize();
			b.shard(base, dir, v(Mth.cos(a), 0, Mth.sin(a)), 0.5F + r.nextFloat() * 1.1F, 0.35F + r.nextFloat() * 0.25F,
				r.nextBoolean() ? GoreMesh.T_BONE : GoreMesh.T_BONE_THIN, 0.1F);
		}
		// the thin bone beside it, snapped shorter (sometimes not out at all)
		if (r.nextFloat() < 0.8F) {
			float fx = (r.nextBoolean() ? -1 : 1) * (1.0F + r.nextFloat() * 0.3F);
			float fz = 0.5F + r.nextFloat() * 0.4F;
			float flen = 0.6F + r.nextFloat() * 1.3F;
			b.tube(v(fx, -0.3F, fz), v(fx + (r.nextFloat() - 0.5F) * 0.4F, flen, fz), 0.24F, 0.2F, GoreMesh.T_BONE);
			b.shard(v(fx, flen - 0.1F, fz), v(fx * 0.2F, 1, 0.2F).normalize(), v(1, 0, 0), 0.4F + r.nextFloat() * 0.6F, 0.25F, GoreMesh.T_BONE_THIN, 0.1F);
		}
		// the trouser leg / sleeve and the skin under it torn into rags round the edge
		int rags = 7 + r.nextInt(5);
		for (int i = 0; i < rags; i++) {
			float t = (i + r.nextFloat() * 0.6F) / rags;
			Vector3f rim = rimPoint(t * 4.0F, 2.28F);
			Vector3f out = new Vector3f(rim.x, 0, rim.z).normalize();
			Vector3f dir = new Vector3f(out).mul(0.25F + r.nextFloat() * 0.45F).add(0, 1, 0).normalize();
			boolean cloth = r.nextFloat() < 0.6F;
			b.shard(new Vector3f(rim).add(0, -0.4F - r.nextFloat() * 0.5F, 0), dir, new Vector3f(out).cross(0, 1, 0), 0.4F + r.nextFloat() * r.nextFloat() * 2.6F,
				0.7F + r.nextFloat() * 0.8F, cloth ? GoreMesh.T_CLOTH : GoreMesh.T_FLAP, 0.15F + r.nextFloat() * 0.2F);
		}
		// tags of muscle hanging off the cut
		int tags = 2 + r.nextInt(3);
		for (int i = 0; i < tags; i++) {
			float a = r.nextFloat() * Mth.TWO_PI;
			float d = 1.0F + r.nextFloat() * 0.9F;
			b.blob(v(Mth.cos(a) * d, 0.5F + r.nextFloat() * 0.5F, Mth.sin(a) * d), 0.35F + r.nextFloat() * 0.3F, 0.5F + r.nextFloat() * 0.5F,
				0.35F + r.nextFloat() * 0.25F, 0.25F, seed + 10 + i, GoreMesh.T_MEAT);
		}
		// strings of blood
		int strands = 3 + r.nextInt(3);
		for (int i = 0; i < strands; i++) {
			float a = r.nextFloat() * Mth.TWO_PI;
			float d = r.nextFloat() * 1.8F;
			Vector3f base = v(Mth.cos(a) * d, 0.4F, Mth.sin(a) * d);
			b.shard(base, v((r.nextFloat() - 0.5F) * 0.2F, 1, (r.nextFloat() - 0.5F) * 0.2F).normalize(), v(Mth.cos(a), 0, Mth.sin(a)),
				1.0F + r.nextFloat() * 2.8F, 0.3F + r.nextFloat() * 0.25F, GoreMesh.T_STRAND, 0.05F);
		}
		return b.build();
	}

	/** A point round the edge of the square limb, {@code t} 0..4 going round its four sides. */
	private static Vector3f rimPoint(float t, float h) {
		int side = Math.min(3, (int) t);
		float f = (t - side) * 2 * h - h;
		return switch (side) {
			case 0 -> v(f, 0, -h);
			case 1 -> v(h, 0, f);
			case 2 -> v(-f, 0, h);
			default -> v(-h, 0, -f);
		};
	}
}
