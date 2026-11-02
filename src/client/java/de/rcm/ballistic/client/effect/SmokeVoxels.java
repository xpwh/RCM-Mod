package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Voxel smoke: the puffs of the {@link SmokeField} built out of blocks, like the world they hang over. Each tick
 * every puff fills the cells of a world-aligned grid that its (lumpy) ball covers; the grid is finer for a small
 * puff and coarser for a big one (power-of-two sizes, from an eighth of a block to 64 blocks), and coarser again
 * far off. Puffs on the same grid size share their cells, so a trail is one blocky column, not a string of boxes.
 * <p>
 * Drawn solid, each cube face flat-shaded: bright on top, darker on the sides, darkest underneath, lit on the side
 * the sun is on, and shaded below the cubes stacked over it. Fresh exhaust at the nozzle glows orange; as smoke
 * thins it does not fade but crumbles - its ball shrinks and falls apart into ever fewer, smaller cubes. Only the
 * faces towards the camera with no cube of the same size next to them are drawn.
 */
public final class SmokeVoxels {
	private static final RenderType TYPE = RenderTypes.entityCutoutNoCull(BallisticMissiles.id("textures/effect/smoke_voxel.png"));
	private static final int MAX_CELLS = 120000;
	/** Cell sizes: 1/8 block << level. */
	private static final int MAX_LEVEL = 9;
	private static final double ORIGIN_STEP = 64.0;

	private static final Long2IntOpenHashMap CELLS = new Long2IntOpenHashMap();
	private static int[] ix = new int[4096];
	private static int[] iy = new int[4096];
	private static int[] iz = new int[4096];
	private static byte[] level = new byte[4096];
	private static int[] rgb = new int[4096];
	private static float[] hot = new float[4096];
	private static float[] shade = new float[4096];
	/** The light on each cube (packed lightmap, as for blocks), and whether it burns (glows by itself). */
	private static int[] light = new int[4096];
	private static boolean[] burns = new boolean[4096];
	// what the puff being added brings with it
	private static int addLight = LightTexture.FULL_BRIGHT;
	private static boolean addBurns;
	private static int count;
	private static double ox;
	private static double oy;
	private static double oz;

	static {
		CELLS.defaultReturnValue(-1);
	}

	private SmokeVoxels() {
	}

	private static float size(int l) {
		return 0.125F * (1 << l);
	}

	private static long key(int l, int x, int y, int z) {
		return (long) l << 60 | (long) (x & 0xFFFFF) << 40 | (long) (y & 0xFFFFF) << 20 | z & 0xFFFFF;
	}

	private static float hash(int x, int y, int z, int l) {
		int h = x * 374761393 + y * 668265263 + z * 1274126177 + l * 144665;
		h = (h ^ (h >>> 13)) * 1103515245;
		return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0F;
	}

	/** A new grid round the camera (cell indices count from a corner on a 64-block step, so they stay small). */
	public static void begin(Vec3 cam) {
		CELLS.clear();
		count = 0;
		ox = Math.floor(cam.x / ORIGIN_STEP) * ORIGIN_STEP;
		oy = Math.floor(cam.y / ORIGIN_STEP) * ORIGIN_STEP;
		oz = Math.floor(cam.z / ORIGIN_STEP) * ORIGIN_STEP;
	}

	public static boolean full() {
		return count >= MAX_CELLS;
	}

	private static void grow() {
		int n = ix.length * 2;
		ix = java.util.Arrays.copyOf(ix, n);
		iy = java.util.Arrays.copyOf(iy, n);
		iz = java.util.Arrays.copyOf(iz, n);
		level = java.util.Arrays.copyOf(level, n);
		rgb = java.util.Arrays.copyOf(rgb, n);
		hot = java.util.Arrays.copyOf(hot, n);
		shade = java.util.Arrays.copyOf(shade, n);
		light = java.util.Arrays.copyOf(light, n);
		burns = java.util.Arrays.copyOf(burns, n);
	}

	/**
	 * One puff: centre (world), the radius its smoke fills, its colour as lit today (rgb), how hot it still glows
	 * (0-1), how far it is from the camera.
	 */
	public static void add(double x, double y, double z, float radius, int colour, float glow, double distance) {
		add(x, y, z, radius, colour, glow, distance, LightTexture.FULL_BRIGHT, false);
	}

	/**
	 * A puff in the world's own light ({@code packedLight}, its colour unlit), or one that is fire and glows by itself
	 * ({@code burning}: its colour as it is, unshaded).
	 */
	public static void add(double x, double y, double z, float radius, int colour, float glow, double distance, int packedLight, boolean burning) {
		addLight = packedLight;
		addBurns = burning;
		addCells(x, y, z, radius, colour, glow, distance);
	}

	private static void addCells(double x, double y, double z, float radius, int colour, float glow, double distance) {
		if (radius < 0.03F) {
			return;
		}
		// fine for a small puff, coarse for a big one, coarser far off (never so coarse a cube outgrows the puff)
		float target = Math.max(radius * 0.45F, Math.min((float) distance / 100.0F, radius * 1.0F));
		int l = Mth.clamp(Math.round((float) (Math.log(target / 0.125F) / Math.log(2.0))), 0, MAX_LEVEL);
		float s = size(l);
		double lx = (x - ox) / s;
		double ly = (y - oy) / s;
		double lz = (z - oz) / s;
		float rr = radius / s;
		int cx = Mth.floor(lx);
		int cy = Mth.floor(ly);
		int cz = Mth.floor(lz);
		// world cell indices for the lumps, so they stay put as the grid's corner moves with the camera
		int wx = (int) Math.round(ox / s);
		int wy = (int) Math.round(oy / s);
		int wz = (int) Math.round(oz / s);
		if (radius / distance < 0.004) {
			// a speck on the screen: one cube
			put(l, cx, cy, cz, colour, glow);
			return;
		}
		// only the cells whose centre can lie inside the lumpiest ball
		float reach = rr * 1.22F;
		float reach2 = reach * reach;
		int i1 = Mth.floor(lx + reach - 0.5);
		int j1 = Mth.floor(ly + reach - 0.5);
		int k1 = Mth.floor(lz + reach - 0.5);
		for (int i = Mth.ceil(lx - reach - 0.5); i <= i1; i++) {
			double dx = i + 0.5 - lx;
			for (int j = Mth.ceil(ly - reach - 0.5); j <= j1; j++) {
				double dy = j + 0.5 - ly;
				double dxy = dx * dx + dy * dy;
				if (dxy > reach2) {
					continue;
				}
				for (int k = Mth.ceil(lz - reach - 0.5); k <= k1; k++) {
					double dz = k + 0.5 - lz;
					if (i != cx || j != cy || k != cz) {
						// a lumpy ball: each cell its own threshold
						float lim = rr * (0.72F + 0.5F * hash(i + wx, j + wy, k + wz, l));
						if (dxy + dz * dz > lim * lim) {
							continue;
						}
					}
					if (!put(l, i, j, k, colour, glow)) {
						return;
					}
				}
			}
		}
		put(l, cx, cy, cz, colour, glow);
	}

	/** Claims a cell (the first puff to claim it gives it its colour); false when the grid is full. */
	private static boolean put(int l, int i, int j, int k, int colour, float glow) {
		long key = key(l, i, j, k);
		int at = CELLS.get(key);
		if (at >= 0) {
			hot[at] = Math.max(hot[at], glow);
			return true;
		}
		if (count >= MAX_CELLS) {
			return false;
		}
		if (count == ix.length) {
			grow();
		}
		ix[count] = i;
		iy[count] = j;
		iz[count] = k;
		level[count] = (byte) l;
		rgb[count] = colour;
		hot[count] = glow;
		light[count] = addLight;
		burns[count] = addBurns;
		CELLS.put(key, count);
		count++;
		return true;
	}

	/** After the puffs: every cube with others of its size stacked over it lies in their shade. */
	public static void finish() {
		for (int n = 0; n < count; n++) {
			int l = level[n];
			int over = 0;
			for (int up = 1; up <= 3; up++) {
				if (CELLS.containsKey(key(l, ix[n], iy[n] + up, iz[n]))) {
					over++;
				}
			}
			shade[n] = 1.0F - 0.07F * over;
		}
	}

	// face order: +x, -x, +y, -y, +z, -z
	private static final int[][] STEP = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
	/** The four corners of each face (unit cube), counter-clockwise seen from outside. */
	private static final float[][][] FACE = {
		{{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
		{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}},
		{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
		{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}},
		{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}},
		{{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}};
	private static final float[][] UV = {{0, 1}, {0, 0}, {1, 0}, {1, 1}};

	/**
	 * Draws the cubes: {@code sun} the direction the light comes from, {@code strength} how strongly it shines (0 at
	 * night, under heavy rain).
	 */
	public static void draw(WorldRenderContext context, Vec3 cam, Vector3f fwd, Vector3f sun, float strength) {
		if (count == 0) {
			return;
		}
		float[] faceShade = new float[6];
		for (int f = 0; f < 6; f++) {
			int[] d = STEP[f];
			float sky = 0.5F + 0.5F * d[1];
			float lit = Math.max(0.0F, d[0] * sun.x + d[1] * sun.y + d[2] * sun.z);
			faceShade[f] = Math.min(1.05F, 0.68F + 0.22F * sky + 0.28F * lit * strength);
		}
		PoseStack.Pose pose = context.matrices().last();
		VertexConsumer consumer = context.consumers().getBuffer(TYPE);
		float bx = (float) (ox - cam.x);
		float by = (float) (oy - cam.y);
		float bz = (float) (oz - cam.z);
		for (int n = 0; n < count; n++) {
			int l = level[n];
			float s = size(l);
			float x0 = bx + ix[n] * s;
			float y0 = by + iy[n] * s;
			float z0 = bz + iz[n] * s;
			float h = s * 0.5F;
			// behind the camera
			if ((x0 + h) * fwd.x + (y0 + h) * fwd.y + (z0 + h) * fwd.z < -s) {
				continue;
			}
			int c = rgb[n];
			float sh = burns[n] ? 1.0F : shade[n];
			float r = (c >> 16 & 255) / 255.0F * sh;
			float g = (c >> 8 & 255) / 255.0F * sh;
			float b = (c & 255) / 255.0F * sh;
			float glow = hot[n];
			for (int f = 0; f < 6; f++) {
				int[] d = STEP[f];
				// only faces turned to the camera (it sits at 0, 0, 0)
				boolean towards = switch (f) {
					case 0 -> x0 + s < 0.0F;
					case 1 -> x0 > 0.0F;
					case 2 -> y0 + s < 0.0F;
					case 3 -> y0 > 0.0F;
					case 4 -> z0 + s < 0.0F;
					default -> z0 > 0.0F;
				};
				if (!towards || CELLS.containsKey(key(l, ix[n] + d[0], iy[n] + d[1], iz[n] + d[2]))) {
					continue;
				}
				float k = burns[n] ? 0.86F + 0.14F * faceShade[f] / 1.05F : faceShade[f];
				float fr = r * k;
				float fg = g * k;
				float fb = b * k;
				if (glow > 0.0F) {
					// glowing from within: no shading, orange going to yellow-white
					fr = Mth.lerp(glow, fr, 1.0F);
					fg = Mth.lerp(glow, fg, 0.55F + 0.2F * glow);
					fb = Mth.lerp(glow, fb, 0.22F);
				}
				int argb = 0xFF000000 | (int) (Mth.clamp(fr, 0.0F, 1.0F) * 255.0F) << 16 | (int) (Mth.clamp(fg, 0.0F, 1.0F) * 255.0F) << 8
					| (int) (Mth.clamp(fb, 0.0F, 1.0F) * 255.0F);
				float[][] corners = FACE[f];
				for (int v = 0; v < 4; v++) {
					float[] p = corners[v];
					consumer.addVertex(pose, x0 + p[0] * s, y0 + p[1] * s, z0 + p[2] * s).setColor(argb).setUv(UV[v][0], UV[v][1])
						.setOverlay(OverlayTexture.NO_OVERLAY).setLight(burns[n] ? LightTexture.FULL_BRIGHT : light[n]).setNormal(pose, 0.0F, 1.0F, 0.0F);
				}
			}
		}
		if (context.consumers() instanceof MultiBufferSource.BufferSource buffers) {
			buffers.endBatch(TYPE);
		}
	}
}
