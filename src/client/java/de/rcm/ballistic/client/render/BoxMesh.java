package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * Hard-surface mesh made of boxes, beams and cylinders, textured from a patch atlas: the texture is
 * a grid of 8x8 square patches and every face shows one whole patch. Faces are wound counter-clockwise
 * seen from outside, so it works with back-face culling.
 */
public final class BoxMesh {
	private static final int STRIDE = 8; // x y z u v nx ny nz
	private static final int GRID = 8;
	private static final float INSET = 0.5F / 128.0F;

	private final float[] data;

	private BoxMesh(float[] data) {
		this.data = data;
	}

	public void emit(PoseStack.Pose pose, VertexConsumer consumer, int light) {
		float[] d = this.data;
		for (int i = 0; i < d.length; i += STRIDE) {
			consumer.addVertex(pose, d[i], d[i + 1], d[i + 2])
				.setColor(-1)
				.setUv(d[i + 3], d[i + 4])
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, d[i + 5], d[i + 6], d[i + 7]);
		}
	}

	public static final class Builder {
		private final FloatArrayList out = new FloatArrayList();

		public BoxMesh build() {
			return new BoxMesh(this.out.toFloatArray());
		}

		/** Axis-aligned box. */
		public Builder box(float x0, float y0, float z0, float x1, float y1, float z1, int patch) {
			Vector3f[] c = {
				new Vector3f(x0, y0, z0), new Vector3f(x1, y0, z0), new Vector3f(x1, y1, z0), new Vector3f(x0, y1, z0),
				new Vector3f(x0, y0, z1), new Vector3f(x1, y0, z1), new Vector3f(x1, y1, z1), new Vector3f(x0, y1, z1)
			};
			this.cuboid(c, patch);
			return this;
		}

		/** Box of the given cross-section stretched between two points. */
		public Builder beam(Vector3f a, Vector3f b, float width, float height, int patch) {
			Vector3f axis = new Vector3f(b).sub(a);
			if (axis.lengthSquared() < 1.0E-8F) {
				return this;
			}
			axis.normalize();
			Vector3f side = new Vector3f(axis).cross(0, 1, 0);
			if (side.lengthSquared() < 1.0E-4F) {
				side.set(1, 0, 0);
			}
			side.normalize().mul(width * 0.5F);
			Vector3f up = new Vector3f(side).cross(axis).normalize().mul(height * 0.5F);
			// the four corners of each end, ordered around the outline like box()
			float[][] signs = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
			Vector3f[] c = new Vector3f[8];
			for (int i = 0; i < 8; i++) {
				Vector3f base = i < 4 ? a : b;
				float[] s = signs[i & 3];
				c[i] = new Vector3f(base).add(new Vector3f(side).mul(s[0])).add(new Vector3f(up).mul(s[1]));
			}
			this.cuboid(c, patch);
			return this;
		}

		/**
		 * Any convex six-sided solid: corners 0-3 outline one end, 4-7 the other end in the same order
		 * (corner i is joined to corner i + 4). Gives tapered noses, swept wings, canted fins.
		 */
		public Builder hexa(int patch, Vector3f... corners) {
			if (corners.length != 8) {
				throw new IllegalArgumentException("hexa needs 8 corners");
			}
			this.cuboid(corners, patch);
			return this;
		}

		/**
		 * Round shell of revolution around {@code axis} through {@code origin}: {@code profile} lists
		 * {distance along the axis, radius} points. Built from thin six-sided segments, so it is
		 * closed and shades correctly from outside. A radius of 0 closes a dome or cap.
		 */
		public Builder revolve(int patch, Vector3f origin, Vector3f axis, float[][] profile, int segments) {
			Vector3f a = new Vector3f(axis).normalize();
			Vector3f u = Math.abs(a.y) < 0.9F ? new Vector3f(a).cross(0, 1, 0).normalize() : new Vector3f(a).cross(1, 0, 0).normalize();
			Vector3f w = new Vector3f(a).cross(u).normalize();
			for (int p = 0; p + 1 < profile.length; p++) {
				for (int s = 0; s < segments; s++) {
					float t0 = Mth.TWO_PI * s / segments;
					float t1 = Mth.TWO_PI * (s + 1) / segments;
					Vector3f[] c = new Vector3f[8];
					int i = 0;
					for (float shell : new float[] {1.0F, 0.88F}) {
						c[i++] = ring(origin, a, u, w, profile[p][0], profile[p][1] * shell, t0);
						c[i++] = ring(origin, a, u, w, profile[p][0], profile[p][1] * shell, t1);
						c[i++] = ring(origin, a, u, w, profile[p + 1][0], profile[p + 1][1] * shell, t1);
						c[i++] = ring(origin, a, u, w, profile[p + 1][0], profile[p + 1][1] * shell, t0);
					}
					this.cuboid(c, patch);
				}
			}
			return this;
		}

		private static Vector3f ring(Vector3f origin, Vector3f a, Vector3f u, Vector3f w, float t, float r, float angle) {
			return new Vector3f(origin).add(new Vector3f(a).mul(t)).add(new Vector3f(u).mul(Mth.cos(angle) * r)).add(new Vector3f(w).mul(Mth.sin(angle) * r));
		}

		/** Cylinder around the X axis (a wheel), centred on (cx, cy, cz). */
		public Builder cylinderX(float cx, float cy, float cz, float radius, float width, int segments, int sidePatch, int capPatch) {
			float h = width * 0.5F;
			for (int s = 0; s < segments; s++) {
				float a0 = Mth.TWO_PI * s / segments;
				float a1 = Mth.TWO_PI * (s + 1) / segments;
				Vector3f p0 = new Vector3f(cx - h, cy + Mth.sin(a0) * radius, cz + Mth.cos(a0) * radius);
				Vector3f p1 = new Vector3f(cx - h, cy + Mth.sin(a1) * radius, cz + Mth.cos(a1) * radius);
				Vector3f p2 = new Vector3f(cx + h, cy + Mth.sin(a1) * radius, cz + Mth.cos(a1) * radius);
				Vector3f p3 = new Vector3f(cx + h, cy + Mth.sin(a0) * radius, cz + Mth.cos(a0) * radius);
				float am = (a0 + a1) * 0.5F;
				this.quad(p0, p1, p2, p3, new Vector3f(0, Mth.sin(am), Mth.cos(am)), sidePatch);
				// caps as thin pie slices
				Vector3f c0 = new Vector3f(cx - h, cy, cz);
				Vector3f c1 = new Vector3f(cx + h, cy, cz);
				this.quad(c0, new Vector3f(c0), p0, p1, new Vector3f(-1, 0, 0), capPatch);
				this.quad(c1, new Vector3f(c1), p2, p3, new Vector3f(1, 0, 0), capPatch);
			}
			return this;
		}

		/** Corners 0-3 at one end, 4-7 at the other, each end ordered around its outline. */
		private void cuboid(Vector3f[] c, int patch) {
			Vector3f center = new Vector3f();
			for (Vector3f v : c) {
				center.add(v);
			}
			center.div(8.0F);
			int[][] faces = {{0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7}, {1, 5, 6, 2}, {3, 2, 6, 7}, {4, 5, 1, 0}};
			for (int[] f : faces) {
				Vector3f fc = new Vector3f(c[f[0]]).add(c[f[1]]).add(c[f[2]]).add(c[f[3]]).div(4.0F);
				Vector3f n = new Vector3f(fc).sub(center);
				if (n.lengthSquared() < 1.0E-10F) {
					continue;
				}
				this.quad(c[f[0]], c[f[1]], c[f[2]], c[f[3]], n.normalize(), patch);
			}
		}

		/** Adds a quad, fixing its winding so it faces along {@code normal}. */
		private void quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, Vector3f normal, int patch) {
			Vector3f e1 = new Vector3f(b).sub(a);
			Vector3f e2 = new Vector3f(c).sub(b);
			Vector3f cross = new Vector3f(e1).cross(e2);
			if (cross.lengthSquared() < 1.0E-12F) {
				// degenerate first corner (pie slice): use the other diagonal
				cross = new Vector3f(c).sub(a).cross(new Vector3f(d).sub(a));
			}
			float u0 = (patch % GRID) / (float) GRID + INSET;
			float v0 = (patch / GRID) / (float) GRID + INSET;
			float u1 = u0 + 1.0F / GRID - 2 * INSET;
			float v1 = v0 + 1.0F / GRID - 2 * INSET;
			if (cross.dot(normal) >= 0) {
				this.vertex(a, u0, v0, normal);
				this.vertex(b, u1, v0, normal);
				this.vertex(c, u1, v1, normal);
				this.vertex(d, u0, v1, normal);
			} else {
				this.vertex(d, u0, v1, normal);
				this.vertex(c, u1, v1, normal);
				this.vertex(b, u1, v0, normal);
				this.vertex(a, u0, v0, normal);
			}
		}

		private void vertex(Vector3f p, float u, float v, Vector3f n) {
			this.out.add(p.x);
			this.out.add(p.y);
			this.out.add(p.z);
			this.out.add(u);
			this.out.add(v);
			this.out.add(n.x);
			this.out.add(n.y);
			this.out.add(n.z);
		}
	}
}
