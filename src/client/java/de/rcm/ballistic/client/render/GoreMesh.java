package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * Soft, organic geometry for the gore, textured from textures/entity/gore.png (painted by tools/gen_gore.py,
 * an 8 x 8 grid of tiles): quads with a normal per corner, so lumps and folds shade smoothly. Positions are
 * built in skin pixels and stored in blocks. The pieces: decals over a face, lumpy domes (a stump's raw end,
 * brain), closed lumps (meat, organs), tubes (bones, gut), thin torn pieces cut out by the texture's ragged
 * alpha (splinters, flaps of skin, rags of cloth, strings of blood).
 */
public final class GoreMesh {
	public static final RenderType TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/entity/gore.png"));
	public static final float P = 1.0F / 16.0F;

	// tiles (see gen_gore.py)
	public static final int T_BRAIN = 30;
	public static final int T_BONE = 31;
	public static final int T_BONE_THIN = 32;
	public static final int T_FLAP = 33;
	public static final int T_SECTION = 34;
	public static final int T_CLOTH = 35;
	public static final int T_STRAND = 36;
	public static final int T_MEAT = 37;
	public static final int T_GUT = 38;
	public static final int T_LIVER = 39;
	public static final int T_HEART = 40;
	public static final int T_LUNG = 41;
	public static final int T_TORSO = 42;
	public static final int T_MOB = 60;

	private final float[] data;

	private GoreMesh(float[] data) {
		this.data = data;
	}

	public void emit(PoseStack.Pose pose, VertexConsumer consumer, int light) {
		float[] d = this.data;
		for (int i = 0; i < d.length; i += 8) {
			consumer.addVertex(pose, d[i], d[i + 1], d[i + 2]).setColor(-1).setUv(d[i + 3], d[i + 4]).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light).setNormal(pose, d[i + 5], d[i + 6], d[i + 7]);
		}
	}

	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> this.emit(pose, consumer, light));
	}

	public static float u(int tile, float u) {
		return ((tile % 8) + Mth.clamp(u, 0.004F, 0.996F)) / 8.0F;
	}

	public static float v(int tile, float v) {
		return ((tile / 8) + Mth.clamp(v, 0.004F, 0.996F)) / 8.0F;
	}

	public static final class Builder {
		private final FloatArrayList out = new FloatArrayList();

		private void vertex(Vector3f p, float u, float v, Vector3f n) {
			this.out.add(p.x * P);
			this.out.add(p.y * P);
			this.out.add(p.z * P);
			this.out.add(u);
			this.out.add(v);
			this.out.add(n.x);
			this.out.add(n.y);
			this.out.add(n.z);
		}

		/** A quad (corners in pixels) with texture coordinates and a normal per corner, wound to face along the normals. */
		public Builder quad(Vector3f[] p, float[][] uv, Vector3f[] n) {
			Vector3f cross = new Vector3f(p[1]).sub(p[0]).cross(new Vector3f(p[2]).sub(p[0]));
			if (cross.lengthSquared() < 1.0E-10F) {
				cross = new Vector3f(p[2]).sub(p[0]).cross(new Vector3f(p[3]).sub(p[0]));
			}
			Vector3f avg = new Vector3f(n[0]).add(n[1]).add(n[2]).add(n[3]);
			int[] order = cross.dot(avg) >= 0 ? new int[] {0, 1, 2, 3} : new int[] {3, 2, 1, 0};
			for (int i : order) {
				this.vertex(p[i], uv[i][0], uv[i][1], n[i]);
			}
			return this;
		}

		/** A flat quad over a rectangle: {@code o} a corner, {@code a} and {@code b} its sides; the tile's u along a, v along b. */
		public Builder decal(Vector3f o, Vector3f a, Vector3f b, int tile) {
			Vector3f n = new Vector3f(a).cross(b).normalize();
			Vector3f[] p = {new Vector3f(o), new Vector3f(o).add(a), new Vector3f(o).add(a).add(b), new Vector3f(o).add(b)};
			float[][] uv = {{u(tile, 0), v(tile, 0)}, {u(tile, 1), v(tile, 0)}, {u(tile, 1), v(tile, 1)}, {u(tile, 0), v(tile, 1)}};
			return this.quad(p, uv, new Vector3f[] {n, n, n, n});
		}

		/**
		 * A soft, lumpy dome standing on a face: centre on it, rising along {@code out}, an ellipse {@code rx} by
		 * {@code ry} across, {@code h} high. The tile is laid over it from above (its centre at the top).
		 */
		public Builder dome(Vector3f centre, Vector3f out, Vector3f across, float rx, float ry, float h, long seed, int tile) {
			Vector3f side = new Vector3f(out).cross(across).normalize();
			int rings = 8;
			int segs = 20;
			Vector3f[][] pos = new Vector3f[rings + 1][segs + 1];
			float[][][] uv = new float[rings + 1][segs + 1][];
			RandomSource r = RandomSource.create(seed);
			float[] lump = new float[segs];
			for (int s = 0; s < segs; s++) {
				lump[s] = 0.85F + r.nextFloat() * 0.3F;
			}
			float phase = r.nextFloat() * Mth.TWO_PI;
			for (int i = 0; i <= rings; i++) {
				float rr = i / (float) rings;
				for (int s = 0; s <= segs; s++) {
					float a = s / (float) segs * Mth.TWO_PI;
					float rad = rr * lump[s % segs];
					float x = Mth.cos(a) * rx * rad;
					float y = Mth.sin(a) * ry * rad;
					float height = h * (float) Math.pow(Math.max(0.0F, 1.0F - rr * rr), 0.6) * (0.9F + 0.18F * Mth.sin(a * 5.0F + rr * 7.0F + phase)) - 0.06F;
					pos[i][s] = new Vector3f(centre).add(new Vector3f(across).mul(x)).add(new Vector3f(side).mul(y)).add(new Vector3f(out).mul(height));
					uv[i][s] = new float[] {u(tile, 0.5F + 0.48F * rr * Mth.cos(a)), v(tile, 0.5F + 0.48F * rr * Mth.sin(a))};
				}
			}
			this.grid(pos, uv, out, rings, segs);
			return this;
		}

		/** A closed lump (a piece of meat, an organ): an ellipsoid with half-axes rx, ry, rz, its surface knobbly. */
		public Builder blob(Vector3f c, float rx, float ry, float rz, float rough, long seed, int tile) {
			int rings = 8;
			int segs = 12;
			RandomSource r = RandomSource.create(seed);
			float[] k = new float[6];
			for (int i = 0; i < k.length; i++) {
				k[i] = r.nextFloat() * Mth.TWO_PI;
			}
			Vector3f[][] pos = new Vector3f[rings + 1][segs + 1];
			float[][][] uv = new float[rings + 1][segs + 1][];
			for (int i = 0; i <= rings; i++) {
				float th = i / (float) rings * Mth.PI;
				for (int s = 0; s <= segs; s++) {
					float ph = s / (float) segs * Mth.TWO_PI;
					float bump = 1.0F + rough * (Mth.sin(th * 3 + k[0]) * Mth.cos(ph * 2 + k[1]) * 0.6F + Mth.sin(th * 5 + ph * 3 + k[2]) * 0.4F);
					Vector3f d = new Vector3f(Mth.sin(th) * Mth.cos(ph), Mth.cos(th), Mth.sin(th) * Mth.sin(ph));
					pos[i][s] = new Vector3f(c).add(d.x * rx * bump, d.y * ry * bump, d.z * rz * bump);
					uv[i][s] = new float[] {u(tile, s / (float) segs), v(tile, i / (float) rings)};
				}
			}
			this.grid(pos, uv, null, rings, segs);
			// normals of a closed lump point away from its middle
			return this;
		}

		/** A round tube from {@code a} to {@code b}, radius r0 to r1 (a bone, a length of gut), the tile wrapped round it. */
		public Builder tube(Vector3f a, Vector3f b, float r0, float r1, int tile) {
			return this.path(new Vector3f[] {a, b}, new float[] {r0, r1}, tile);
		}

		/** A round tube along a path of points with a radius at each (a coil of gut). */
		public Builder path(Vector3f[] pts, float[] radius, int tile) {
			int segs = 8;
			int n = pts.length;
			Vector3f[][] pos = new Vector3f[n][segs + 1];
			float[][][] uv = new float[n][segs + 1][];
			Vector3f[][] nrm = new Vector3f[n][segs + 1];
			Vector3f prevSide = null;
			for (int i = 0; i < n; i++) {
				Vector3f dir = new Vector3f(pts[Math.min(n - 1, i + 1)]).sub(pts[Math.max(0, i - 1)]).normalize();
				Vector3f side = prevSide != null ? new Vector3f(prevSide).sub(new Vector3f(dir).mul(prevSide.dot(dir))) : new Vector3f(dir).cross(0, 1, 0);
				if (side.lengthSquared() < 1.0E-4F) {
					side = new Vector3f(dir).cross(1, 0, 0);
				}
				side.normalize();
				prevSide = side;
				Vector3f up = new Vector3f(side).cross(dir).normalize();
				for (int s = 0; s <= segs; s++) {
					float a = s / (float) segs * Mth.TWO_PI;
					Vector3f d = new Vector3f(side).mul(Mth.cos(a)).add(new Vector3f(up).mul(Mth.sin(a)));
					pos[i][s] = new Vector3f(pts[i]).add(new Vector3f(d).mul(radius[i]));
					nrm[i][s] = d;
					uv[i][s] = new float[] {u(tile, s / (float) segs), v(tile, i / (float) Math.max(1, n - 1))};
				}
			}
			for (int i = 0; i + 1 < n; i++) {
				for (int s = 0; s < segs; s++) {
					this.quad(new Vector3f[] {pos[i][s], pos[i][s + 1], pos[i + 1][s + 1], pos[i + 1][s]},
						new float[][] {uv[i][s], uv[i][s + 1], uv[i + 1][s + 1], uv[i + 1][s]},
						new Vector3f[] {nrm[i][s], nrm[i][s + 1], nrm[i + 1][s + 1], nrm[i + 1][s]});
				}
			}
			return this;
		}

		/**
		 * A thin piece standing out of {@code base} along {@code dir}: a splinter of bone, a flap of skin, a rag of
		 * cloth, a string of blood - its outline cut ragged by the tile's alpha, bent a little half way and again near the end.
		 */
		public Builder shard(Vector3f base, Vector3f dir, Vector3f hint, float length, float width, int tile, float bend) {
			Vector3f w = new Vector3f(dir).cross(hint);
			if (w.lengthSquared() < 1.0E-4F) {
				w = new Vector3f(dir).cross(0, 0, 1);
				if (w.lengthSquared() < 1.0E-4F) {
					w = new Vector3f(1, 0, 0);
				}
			}
			w.normalize().mul(width * 0.5F);
			Vector3f n = new Vector3f(w).cross(dir).normalize();
			Vector3f[] rows = new Vector3f[4];
			float[] vs = {1.0F, 0.66F, 0.33F, 0.0F};
			float[] wid = {1.0F, 0.85F, 0.7F, 0.55F};
			for (int i = 0; i < 4; i++) {
				float t = 1.0F - vs[i];
				rows[i] = new Vector3f(base).add(new Vector3f(dir).mul(length * t)).add(new Vector3f(n).mul(length * bend * t * t));
			}
			for (int i = 0; i < 3; i++) {
				Vector3f a0 = new Vector3f(rows[i]).sub(new Vector3f(w).mul(wid[i]));
				Vector3f a1 = new Vector3f(rows[i]).add(new Vector3f(w).mul(wid[i]));
				Vector3f b1 = new Vector3f(rows[i + 1]).add(new Vector3f(w).mul(wid[i + 1]));
				Vector3f b0 = new Vector3f(rows[i + 1]).sub(new Vector3f(w).mul(wid[i + 1]));
				this.quad(new Vector3f[] {a0, a1, b1, b0},
					new float[][] {{u(tile, 0), v(tile, vs[i])}, {u(tile, 1), v(tile, vs[i])}, {u(tile, 1), v(tile, vs[i + 1])}, {u(tile, 0), v(tile, vs[i + 1])}},
					new Vector3f[] {n, n, n, n});
			}
			return this;
		}

		/** Quads between the rows of a grid of points; normals from the neighbours (pointing along {@code out}, or away from the middle). */
		private void grid(Vector3f[][] pos, float[][][] uv, Vector3f out, int rows, int segs) {
			Vector3f centre = new Vector3f();
			int count = 0;
			for (Vector3f[] row : pos) {
				for (Vector3f p : row) {
					centre.add(p);
					count++;
				}
			}
			centre.div(count);
			Vector3f[][] nrm = new Vector3f[rows + 1][segs + 1];
			for (int i = 0; i <= rows; i++) {
				for (int s = 0; s <= segs; s++) {
					Vector3f du = new Vector3f(pos[Math.min(rows, i + 1)][s]).sub(pos[Math.max(0, i - 1)][s]);
					Vector3f dv = new Vector3f(pos[i][(s + 1) % segs]).sub(pos[i][(s + segs - 1) % segs]);
					Vector3f n = new Vector3f(du).cross(dv);
					Vector3f ref = out != null ? out : new Vector3f(pos[i][s]).sub(centre);
					if (n.dot(ref) < 0) {
						n.negate();
					}
					nrm[i][s] = n.lengthSquared() < 1.0E-8F ? (out != null ? new Vector3f(out) : new Vector3f(ref).normalize()) : n.normalize();
				}
			}
			for (int i = 0; i < rows; i++) {
				for (int s = 0; s < segs; s++) {
					this.quad(new Vector3f[] {pos[i][s], pos[i][s + 1], pos[i + 1][s + 1], pos[i + 1][s]},
						new float[][] {uv[i][s], uv[i][s + 1], uv[i + 1][s + 1], uv[i + 1][s]},
						new Vector3f[] {nrm[i][s], nrm[i][s + 1], nrm[i + 1][s + 1], nrm[i + 1][s]});
				}
			}
		}

		public GoreMesh build() {
			return new GoreMesh(this.out.toFloatArray());
		}
	}
}
