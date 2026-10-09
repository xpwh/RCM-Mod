package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.injury.Wounds;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * The head wounds, drawn in the head's own space - not out of blocks but painted and modelled:
 * <ul>
 *   <li>every face of the head carries a 16x-resolution decal (textures/entity/gore_head.png, painted by
 *       tools/gen_gore_head.py from the same 3D fields, so a wound runs on round the edge): the torn skin,
 *       the fat under it, the raw flesh, the bone, blood running down the face, spatter;</li>
 *   <li>the skull blown open adds round, soft shapes: the brain bulging out of the crater (a smooth, lumpy
 *       dome lit vertex by vertex), splinters of skull standing up round the rim and flaps of scalp torn
 *       back and hanging, both cut out of the texture with ragged edges.</li>
 * </ul>
 */
public final class GoreHead {
	private static final float P = 1.0F / 16.0F;
	private static final RenderType TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/entity/gore_head.png"));
	/** Tiles of the 4 x 4 atlas. */
	private static final int T_BRAIN = 9;
	private static final int T_BONE = 10;
	private static final int T_FLAP = 11;
	/** The crater's centre on the head (as in gen_gore_head.py). */
	private static final Vector3f CRATER = new Vector3f(-3.4F, -7.9F, 2.7F);
	private static final Mesh GRAZED = grazed();
	private static final Mesh SHATTERED = shattered();

	private GoreHead() {
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, int head) {
		Mesh mesh = head == Wounds.SHATTERED ? SHATTERED : GRAZED;
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> mesh.emit(pose, consumer, light));
	}

	// ------------------------------------------------------------------ mesh

	/** Quads with a normal per corner (smooth shading) and their own texture coordinates. */
	static final class Mesh {
		private final float[] data;

		Mesh(float[] data) {
			this.data = data;
		}

		void emit(PoseStack.Pose pose, VertexConsumer consumer, int light) {
			float[] d = this.data;
			for (int i = 0; i < d.length; i += 8) {
				consumer.addVertex(pose, d[i], d[i + 1], d[i + 2]).setColor(-1).setUv(d[i + 3], d[i + 4]).setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light).setNormal(pose, d[i + 5], d[i + 6], d[i + 7]);
			}
		}
	}

	private static final class Builder {
		final FloatArrayList out = new FloatArrayList();

		void vertex(Vector3f p, float u, float v, Vector3f n) {
			this.out.add(p.x * P);
			this.out.add(p.y * P);
			this.out.add(p.z * P);
			this.out.add(u);
			this.out.add(v);
			this.out.add(n.x);
			this.out.add(n.y);
			this.out.add(n.z);
		}

		/** A quad a, b, c, d (in pixels) with texture coordinates and normals per corner, wound to face along the normals. */
		void quad(Vector3f[] p, float[][] uv, Vector3f[] n) {
			Vector3f cross = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0]));
			Vector3f avg = new Vector3f(n[0]).add(n[1]).add(n[2]).add(n[3]);
			int[] order = cross.dot(avg) >= 0 ? new int[] {0, 1, 2, 3} : new int[] {3, 2, 1, 0};
			for (int i : order) {
				this.vertex(p[i], uv[i][0], uv[i][1], n[i]);
			}
		}

		Mesh build() {
			return new Mesh(this.out.toFloatArray());
		}
	}

	private static float tileU(int tile, float u) {
		return ((tile % 4) + Mth.clamp(u, 0.004F, 0.996F)) / 4.0F;
	}

	private static float tileV(int tile, float v) {
		return ((tile / 4) + Mth.clamp(v, 0.004F, 0.996F)) / 4.0F;
	}

	/** A decal over one whole face of the head, {@code out} pixels proud of it, mapped as gen_gore_head.py paints it. */
	private static void face(Builder b, String face, int tile, float out) {
		float o = 4.0F + out;
		Vector3f[] p;
		Vector3f n;
		float[][] uv;
		switch (face) {
			case "front" -> {
				p = new Vector3f[] {new Vector3f(-4, -8, -o), new Vector3f(4, -8, -o), new Vector3f(4, 0, -o), new Vector3f(-4, 0, -o)};
				uv = new float[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
				n = new Vector3f(0, 0, -1);
			}
			case "back" -> {
				p = new Vector3f[] {new Vector3f(-4, -8, o), new Vector3f(4, -8, o), new Vector3f(4, 0, o), new Vector3f(-4, 0, o)};
				uv = new float[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
				n = new Vector3f(0, 0, 1);
			}
			case "left" -> {
				p = new Vector3f[] {new Vector3f(o, -8, -4), new Vector3f(o, -8, 4), new Vector3f(o, 0, 4), new Vector3f(o, 0, -4)};
				uv = new float[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
				n = new Vector3f(1, 0, 0);
			}
			case "right" -> {
				p = new Vector3f[] {new Vector3f(-o, -8, -4), new Vector3f(-o, -8, 4), new Vector3f(-o, 0, 4), new Vector3f(-o, 0, -4)};
				uv = new float[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
				n = new Vector3f(-1, 0, 0);
			}
			default -> {
				// top: (x, z)
				float y = -4.0F - o;
				p = new Vector3f[] {new Vector3f(-4, y, -4), new Vector3f(4, y, -4), new Vector3f(4, y, 4), new Vector3f(-4, y, 4)};
				uv = new float[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
				n = new Vector3f(0, -1, 0);
			}
		}
		float[][] t = new float[4][];
		for (int i = 0; i < 4; i++) {
			t[i] = new float[] {tileU(tile, uv[i][0]), tileV(tile, uv[i][1])};
		}
		b.quad(p, t, new Vector3f[] {n, n, n, n});
	}

	/** The graze: decals only - the hat layer sits half a pixel out, so they go just beyond it. */
	private static Mesh grazed() {
		Builder b = new Builder();
		face(b, "front", 0, 0.56F);
		face(b, "left", 1, 0.56F);
		face(b, "top", 2, 0.56F);
		face(b, "back", 3, 0.56F);
		return b.build();
	}

	private static Mesh shattered() {
		Builder b = new Builder();
		// the hat layer is hidden with this wound: the decals lie right on the skin
		face(b, "front", 4, 0.03F);
		face(b, "right", 5, 0.03F);
		face(b, "top", 6, 0.03F);
		face(b, "back", 7, 0.03F);
		face(b, "left", 8, 0.03F);
		RandomSource r = RandomSource.create(1911L);
		// the brain bulging out of the crater: over the crown, and out of the side
		dome(b, new Vector3f(-2.6F, -8.0F, 2.4F), new Vector3f(0, -1, 0), new Vector3f(1, 0, 0), 1.35F, 1.25F, 0.55F, 31L);
		dome(b, new Vector3f(-4.0F, -7.0F, 2.6F), new Vector3f(-1, 0, 0), new Vector3f(0, 0, 1), 1.0F, 0.85F, 0.45F, 32L);
		dome(b, new Vector3f(-2.9F, -7.1F, 4.0F), new Vector3f(0, 0, 1), new Vector3f(1, 0, 0), 0.8F, 0.75F, 0.35F, 33L);
		// splinters of skull standing out round the rim: around the crater, where it meets the surfaces
		for (int i = 0; i < 16; i++) {
			if (r.nextFloat() < 0.35F) {
				continue; // the rim is broken unevenly: splinters here and there
			}
			float a = i / 16.0F * Mth.TWO_PI + r.nextFloat() * 0.3F;
			// a direction round the crater centre, kept to the head's surface
			Vector3f dir = new Vector3f(Mth.cos(a), Mth.sin(a) * 0.6F + 0.45F, Mth.sin(a) * 0.9F).normalize();
			Vector3f rim = surface(new Vector3f(CRATER).add(new Vector3f(dir).mul(2.3F + r.nextFloat() * 0.4F)));
			if (rim == null) {
				continue;
			}
			Vector3f normal = surfaceNormal(rim);
			// leaning out of the hole, away from its centre
			Vector3f away = new Vector3f(rim).sub(CRATER).normalize();
			// mostly lying back along the surface, bent outward by the blast
			Vector3f up = new Vector3f(normal).mul(0.45F + r.nextFloat() * 0.3F).add(new Vector3f(away).mul(0.9F)).normalize();
			float len = 0.4F + r.nextFloat() * 0.6F;
			float wid = 0.4F + r.nextFloat() * 0.45F;
			shard(b, rim, up, away, len, wid, T_BONE);
		}
		// flaps of scalp torn back, hanging down the side and the back of the head
		for (int i = 0; i < 4; i++) {
			Vector3f rim = i < 2 ? new Vector3f(-4.05F, -5.7F + r.nextFloat() * 0.5F, 1.5F + i * 1.3F)
				: new Vector3f(-3.6F + (i - 2) * 1.2F, -5.6F + r.nextFloat() * 0.5F, 4.05F);
			Vector3f normal = surfaceNormal(rim);
			Vector3f down = new Vector3f(normal).mul(0.35F).add(0, 1, 0).normalize();
			shard(b, rim, down, new Vector3f(0, 1, 0), 0.9F + r.nextFloat() * 0.7F, 0.8F + r.nextFloat() * 0.4F, T_FLAP);
		}
		return b.build();
	}

	/** Moves a point onto the head's surface (the 8 px cube), or null if it is not near one. */
	private static Vector3f surface(Vector3f p) {
		Vector3f q = new Vector3f(Mth.clamp(p.x, -4, 4), Mth.clamp(p.y, -8, 0), Mth.clamp(p.z, -4, 4));
		// push out to the nearest face
		float dx = 4 - Math.abs(q.x);
		float dy = Math.min(q.y + 8, -q.y);
		float dz = 4 - Math.abs(q.z);
		if (dx <= dy && dx <= dz) {
			q.x = Math.signum(q.x == 0 ? 1 : q.x) * 4;
		} else if (dy <= dz) {
			q.y = q.y + 8 < -q.y ? -8 : 0;
		} else {
			q.z = Math.signum(q.z == 0 ? 1 : q.z) * 4;
		}
		return q.y >= -0.01F ? null : q;
	}

	private static Vector3f surfaceNormal(Vector3f q) {
		if (Math.abs(Math.abs(q.x) - 4) < 0.01F) {
			return new Vector3f(Math.signum(q.x), 0, 0);
		}
		if (Math.abs(q.y + 8) < 0.01F) {
			return new Vector3f(0, -1, 0);
		}
		return new Vector3f(0, 0, Math.signum(q.z));
	}

	/**
	 * A soft, lumpy dome (brain spilling out): {@code centre} on a face, rising along {@code out}, an ellipse
	 * {@code rx} by {@code ry} across, {@code h} high; its surface rippled and smooth-shaded.
	 */
	private static void dome(Builder b, Vector3f centre, Vector3f out, Vector3f across, float rx, float ry, float h, long seed) {
		Vector3f side = new Vector3f(out).cross(across).normalize();
		int rings = 9;
		int segs = 22;
		Vector3f[][] pos = new Vector3f[rings + 1][segs + 1];
		float[][][] uv = new float[rings + 1][segs + 1][];
		RandomSource r = RandomSource.create(seed);
		float[] lump = new float[segs];
		for (int s = 0; s < segs; s++) {
			lump[s] = 0.85F + r.nextFloat() * 0.3F;
		}
		for (int i = 0; i <= rings; i++) {
			float rr = i / (float) rings;
			for (int s = 0; s <= segs; s++) {
				float a = s / (float) segs * Mth.TWO_PI;
				float l = lump[s % segs];
				float rad = rr * l;
				float x = Mth.cos(a) * rx * rad;
				float y = Mth.sin(a) * ry * rad;
				// a dome, its surface rippling in folds (gyri) - and sunk a little at the very edge, into the hole
				float height = h * (float) Math.pow(Math.max(0.0F, 1.0F - rr * rr), 0.6) * (0.9F + 0.18F * Mth.sin(a * 5.0F + rr * 7.0F)) - 0.08F;
				pos[i][s] = new Vector3f(centre).add(new Vector3f(across).mul(x)).add(new Vector3f(side).mul(y)).add(new Vector3f(out).mul(height));
				uv[i][s] = new float[] {tileU(T_BRAIN, 0.5F + 0.45F * rr * Mth.cos(a)), tileV(T_BRAIN, 0.5F + 0.45F * rr * Mth.sin(a))};
			}
		}
		// normals from the neighbours, for smooth light over the lumps
		Vector3f[][] nrm = new Vector3f[rings + 1][segs + 1];
		for (int i = 0; i <= rings; i++) {
			for (int s = 0; s <= segs; s++) {
				Vector3f du = new Vector3f(pos[Math.min(rings, i + 1)][s]).sub(pos[Math.max(0, i - 1)][s]);
				Vector3f dv = new Vector3f(pos[i][(s + 1) % segs]).sub(pos[i][(s + segs - 1) % segs]);
				Vector3f n = new Vector3f(du).cross(dv);
				if (n.dot(out) < 0) {
					n.negate();
				}
				nrm[i][s] = n.lengthSquared() < 1.0E-8F ? new Vector3f(out) : n.normalize();
			}
		}
		for (int i = 0; i < rings; i++) {
			for (int s = 0; s < segs; s++) {
				b.quad(new Vector3f[] {pos[i][s], pos[i][s + 1], pos[i + 1][s + 1], pos[i + 1][s]},
					new float[][] {uv[i][s], uv[i][s + 1], uv[i + 1][s + 1], uv[i + 1][s]},
					new Vector3f[] {nrm[i][s], nrm[i][s + 1], nrm[i + 1][s + 1], nrm[i + 1][s]});
			}
		}
	}

	/**
	 * A thin piece standing out of {@code base} along {@code dir}: a splinter of bone or a flap of scalp, its
	 * outline cut ragged by the texture's alpha. Bent a little half way, so it is not a flat card.
	 */
	private static void shard(Builder b, Vector3f base, Vector3f dir, Vector3f hint, float length, float width, int tile) {
		Vector3f w = new Vector3f(dir).cross(hint);
		if (w.lengthSquared() < 1.0E-4F) {
			w = new Vector3f(dir).cross(0, 0, 1);
		}
		w.normalize().mul(width * 0.5F);
		Vector3f n = new Vector3f(w).cross(dir).normalize();
		Vector3f mid = new Vector3f(base).add(new Vector3f(dir).mul(length * 0.5F)).add(new Vector3f(n).mul(length * 0.08F));
		Vector3f tip = new Vector3f(base).add(new Vector3f(dir).mul(length)).add(new Vector3f(n).mul(length * 0.05F));
		Vector3f[] row0 = {new Vector3f(base).sub(w), new Vector3f(base).add(w)};
		Vector3f[] row1 = {new Vector3f(mid).sub(new Vector3f(w).mul(0.8F)), new Vector3f(mid).add(new Vector3f(w).mul(0.8F))};
		Vector3f[] row2 = {new Vector3f(tip).sub(new Vector3f(w).mul(0.5F)), new Vector3f(tip).add(new Vector3f(w).mul(0.5F))};
		// texture: base at v = 1, ragged tip at v = 0
		b.quad(new Vector3f[] {row0[0], row0[1], row1[1], row1[0]},
			new float[][] {{tileU(tile, 0), tileV(tile, 1)}, {tileU(tile, 1), tileV(tile, 1)}, {tileU(tile, 1), tileV(tile, 0.5F)}, {tileU(tile, 0), tileV(tile, 0.5F)}},
			new Vector3f[] {n, n, n, n});
		b.quad(new Vector3f[] {row1[0], row1[1], row2[1], row2[0]},
			new float[][] {{tileU(tile, 0), tileV(tile, 0.5F)}, {tileU(tile, 1), tileV(tile, 0.5F)}, {tileU(tile, 1), tileV(tile, 0)}, {tileU(tile, 0), tileV(tile, 0)}},
			new Vector3f[] {n, n, n, n});
	}
}
