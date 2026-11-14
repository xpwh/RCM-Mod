package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * The lower jaw shot away, in three versions. The lower front of the head is cut out of the model itself
 * ({@link HoledBox} with {@link #cut}): below a ragged line across the face and back along the cheeks there is
 * no face left. In its place, in the head's own space (pixels: x -4..4 with +X the player's left, y -8 crown ..
 * 0 chin, z -4 face .. 4 back): the roof of the mouth, the upper teeth hanging from it (some broken, some
 * gone), the throat at the back, the tongue hanging out and down over the neck, what is left of the jawbone
 * dangling from one side with teeth still in it, flaps of skin and strings of blood; the torn edge of the face
 * round it painted by tools/gen_gore.py from the same lines ({@link #jawY}, {@link #back}).
 */
public final class GoreJaw {
	private static final GoreMesh[] INSIDE = new GoreMesh[3];
	private static final GoreMesh[] RIM = new GoreMesh[3];

	static {
		for (int v = 0; v < 3; v++) {
			INSIDE[v] = inside(v);
			RIM[v] = rim(v);
		}
	}

	private GoreJaw() {
	}

	/** Where the face is torn off across the front (below this, y down, it is gone). As jaw_y in gen_gore.py. */
	public static float jawY(float x, int v) {
		return -2.75F + 0.35F * Mth.sin(1.3F * x + 2.1F * v) + 0.18F * Mth.sin(3.7F * x + 1.3F * v);
	}

	private static float jawSideY(float z, float side, int v) {
		return jawY(4.0F * side, v) + 0.25F * Mth.sin(2.7F * z + v);
	}

	/** How far back along the cheeks it is torn away (z). As jaw_back in gen_gore.py. */
	public static float back(float y, int v) {
		return -0.9F + 0.35F * Mth.sin(2.2F * y + 1.7F * v) + 0.2F * Mth.sin(4.1F * y + v);
	}

	/** What of the head is gone, version {@code v}. */
	public static GoreMesh.Cut cut(int v) {
		return (x, y, z) -> {
			if (z > back(Mth.clamp(y, -8.0F, 0.0F), v)) {
				return false;
			}
			float line = Math.abs(x) >= 3.9F ? jawSideY(z, Math.signum(x), v) : jawY(x, v);
			return y > line;
		};
	}

	/** Version {@code v} picked by the wound seed. */
	public static int version(int seed) {
		return Math.floorMod(seed >> 16, 3);
	}

	/** In the head's space: the torn edge of the face (with {@code cut} applied) and what lies inside. */
	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, int v) {
		RIM[v].submit(poseStack, collector, light, cut(v));
		INSIDE[v].submit(poseStack, collector, light);
	}

	private static GoreMesh rim(int v) {
		GoreMesh.Builder b = new GoreMesh.Builder();
		// just beyond the hat layer, as the graze is
		GoreHead.face(b, "front", GoreMesh.T_JAW_RIM + v * 3, 0.56F);
		GoreHead.face(b, "left", GoreMesh.T_JAW_RIM + v * 3 + 1, 0.56F);
		GoreHead.face(b, "right", GoreMesh.T_JAW_RIM + v * 3 + 2, 0.56F);
		return b.build();
	}

	private static GoreMesh inside(int v) {
		RandomSource r = RandomSource.create(4400L + v * 131L);
		GoreMesh.Builder b = new GoreMesh.Builder();
		int nx = 16;
		// the roof of the mouth, following the torn line across, from the face back to the throat
		for (int i = 0; i < nx; i++) {
			float x0 = -4.0F + 8.0F * i / nx;
			float x1 = -4.0F + 8.0F * (i + 1) / nx;
			float y0 = jawY(x0, v) - 0.05F;
			float y1 = jawY(x1, v) - 0.05F;
			float zb0 = back(y0, v);
			float zb1 = back(y1, v);
			Vector3f down = new Vector3f(0, 1, 0);
			b.quad(new Vector3f[] {new Vector3f(x0, y0, -4.0F), new Vector3f(x1, y1, -4.0F), new Vector3f(x1, y1, zb1), new Vector3f(x0, y0, zb0)},
				new float[][] {{GoreMesh.u(GoreMesh.T_PALATE, i / (float) nx), GoreMesh.v(GoreMesh.T_PALATE, 0)},
					{GoreMesh.u(GoreMesh.T_PALATE, (i + 1) / (float) nx), GoreMesh.v(GoreMesh.T_PALATE, 0)},
					{GoreMesh.u(GoreMesh.T_PALATE, (i + 1) / (float) nx), GoreMesh.v(GoreMesh.T_PALATE, 1)},
					{GoreMesh.u(GoreMesh.T_PALATE, i / (float) nx), GoreMesh.v(GoreMesh.T_PALATE, 1)}}, new Vector3f[] {down, down, down, down});
		}
		// the throat: the back of the hole, down to where the neck begins
		int ny = 8;
		float top = jawY(0.0F, v) - 0.6F;
		for (int j = 0; j < ny; j++) {
			float y0 = top + (0.2F - top) * j / ny;
			float y1 = top + (0.2F - top) * (j + 1) / ny;
			float z0 = back(y0, v) - 0.02F;
			float z1 = back(y1, v) - 0.02F;
			Vector3f n = new Vector3f(0, 0, -1);
			b.quad(new Vector3f[] {new Vector3f(-3.95F, y0, z0), new Vector3f(3.95F, y0, z0), new Vector3f(3.95F, y1, z1), new Vector3f(-3.95F, y1, z1)},
				new float[][] {{GoreMesh.u(GoreMesh.T_THROAT, 0), GoreMesh.v(GoreMesh.T_THROAT, j / (float) ny)},
					{GoreMesh.u(GoreMesh.T_THROAT, 1), GoreMesh.v(GoreMesh.T_THROAT, j / (float) ny)},
					{GoreMesh.u(GoreMesh.T_THROAT, 1), GoreMesh.v(GoreMesh.T_THROAT, (j + 1) / (float) ny)},
					{GoreMesh.u(GoreMesh.T_THROAT, 0), GoreMesh.v(GoreMesh.T_THROAT, (j + 1) / (float) ny)}}, new Vector3f[] {n, n, n, n});
		}
		// the cheeks' torn inside, either side: raw meat
		for (int s = -1; s <= 1; s += 2) {
			b.blob(new Vector3f(s * 3.5F, jawY(s * 3.5F, v) + 0.6F, (back(-2.0F, v) - 4.0F) * 0.5F), 0.6F, 0.7F, 1.6F, 0.4F, 4410L + v * 3 + s, GoreMesh.T_MEAT);
		}
		// the gum along the front, and the upper teeth hanging from it - some broken short, some gone
		for (int k = 0; k < 10; k++) {
			float x = -3.0F + k * 0.667F;
			float z = -3.55F + 0.05F * x * x;
			float y = jawY(x, v);
			b.blob(new Vector3f(x, y + 0.05F, z + 0.1F), 0.4F, 0.28F, 0.4F, 0.2F, 4420L + v * 17 + k, GoreMesh.T_PALATE);
			if (r.nextFloat() < 0.18F) {
				continue;
			}
			float len = r.nextFloat() < 0.25F ? 0.45F + r.nextFloat() * 0.3F : 0.95F + r.nextFloat() * 0.35F;
			b.blob(new Vector3f(x + (r.nextFloat() - 0.5F) * 0.1F, y + len * 0.5F, z), 0.28F, len * 0.5F, 0.22F, 0.08F, 4440L + v * 17 + k, GoreMesh.T_TEETH);
		}
		// the back teeth along the sides
		for (int s = -1; s <= 1; s += 2) {
			for (int k = 0; k < 3; k++) {
				float z = -2.9F + k * 0.7F;
				if (z > back(jawY(s * 3.3F, v), v) - 0.2F || r.nextFloat() < 0.2F) {
					continue;
				}
				float x = s * 3.25F;
				float y = jawY(x, v);
				b.blob(new Vector3f(x, y + 0.42F, z), 0.34F, 0.42F, 0.34F, 0.12F, 4470L + v * 11 + k + s * 5, GoreMesh.T_TEETH);
			}
		}
		// the tongue: from the throat, out and down over the neck
		float side = (v - 1) * 0.5F;
		Vector3f[] tongue = {new Vector3f(side * 0.3F, top + 0.9F, back(top + 0.9F, v) - 0.3F), new Vector3f(side * 0.5F, -0.4F, -2.0F),
			new Vector3f(side * 0.8F, 0.9F, -3.1F), new Vector3f(side * 1.1F, 2.3F, -3.5F), new Vector3f(side * 1.2F, 3.3F, -3.4F)};
		b.path(tongue, new float[] {1.15F, 1.2F, 1.05F, 0.85F, 0.6F}, GoreMesh.T_TONGUE);
		// what is left of the jawbone, hanging from one side by the flesh, teeth still in it
		float js = v == 1 ? 1.0F : -1.0F;
		Vector3f[] bone = {new Vector3f(js * 3.4F, 0.1F, back(0.0F, v) - 0.2F), new Vector3f(js * 3.3F, 1.4F, -1.6F), new Vector3f(js * 2.7F, 2.6F, -2.6F),
			new Vector3f(js * 1.7F, 3.2F, -3.0F)};
		b.path(bone, new float[] {0.55F, 0.5F, 0.45F, 0.4F}, GoreMesh.T_BONE);
		b.blob(new Vector3f(js * 3.4F, 0.4F, back(0.0F, v) - 0.4F), 0.8F, 0.9F, 0.9F, 0.4F, 4480L + v, GoreMesh.T_MEAT);
		for (int k = 0; k < 3; k++) {
			float t = 0.35F + k * 0.25F;
			Vector3f p = new Vector3f(bone[1]).lerp(bone[3], t);
			b.blob(new Vector3f(p.x, p.y - 0.45F, p.z), 0.26F, 0.4F, 0.24F, 0.1F, 4490L + v * 7 + k, GoreMesh.T_TEETH);
		}
		b.shard(bone[3], new Vector3f(-js * 0.6F, 0.4F, -0.5F).normalize(), new Vector3f(0, 0, 1), 1.0F, 0.6F, GoreMesh.T_BONE_THIN, 0.1F);
		// the other side: just a splintered stump of bone
		b.tube(new Vector3f(-js * 3.4F, 0.1F, back(0.0F, v) - 0.2F), new Vector3f(-js * 3.3F, 0.9F, -1.4F), 0.5F, 0.4F, GoreMesh.T_BONE);
		b.shard(new Vector3f(-js * 3.3F, 0.9F, -1.4F), new Vector3f(0, 0.8F, -0.6F).normalize(), new Vector3f(1, 0, 0), 0.9F, 0.5F, GoreMesh.T_BONE_THIN, 0.1F);
		// flaps of skin hanging from the torn edge, and strings of blood
		for (int k = 0; k < 6; k++) {
			float x = -3.3F + k * 1.3F + (r.nextFloat() - 0.5F) * 0.5F;
			float y = jawY(x, v) + 0.1F;
			b.shard(new Vector3f(x, y, -4.15F), new Vector3f((r.nextFloat() - 0.5F) * 0.3F, 1.0F, -0.25F).normalize(), new Vector3f(1, 0, 0),
				1.2F + r.nextFloat() * 1.8F, 1.0F + r.nextFloat() * 0.6F, k % 3 == 1 ? GoreMesh.T_STRAND : GoreMesh.T_FLAP, 0.15F);
		}
		for (int s = -1; s <= 1; s += 2) {
			float z = -2.6F + r.nextFloat();
			b.shard(new Vector3f(s * 4.1F, jawSideY(z, s, v) + 0.1F, z), new Vector3f(s * 0.2F, 1.0F, 0).normalize(), new Vector3f(0, 0, 1),
				1.6F + r.nextFloat() * 1.4F, 1.3F, GoreMesh.T_FLAP, 0.2F);
		}
		return b.build();
	}
}
