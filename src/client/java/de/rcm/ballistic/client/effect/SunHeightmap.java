package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.platform.NativeImage;
import de.rcm.ballistic.BallisticMissiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The height of the world round the camera, column by column (the top of whatever stops motion there:
 * ground, roofs, walls, trees in leaf), as a texture for the sun's shadows: a pixel is in shadow when the
 * line from it to the sun passes beneath the top of a column on the way. Unlike shadows found in the
 * picture, these do not depend on what is on screen - a roof overhead shades the room under it wherever
 * you look, and the sun falls in through the doorway.
 * <p>
 * 256 x 256 blocks centred near the camera; rebuilt in full when the camera has moved far enough, and
 * refreshed a band at a time every tick so placed and broken blocks show.
 */
public final class SunHeightmap {
	public static final int SIZE = 256;
	public static final Identifier ID = BallisticMissiles.id("textures/environment/sun_heightmap");
	private static DynamicTexture texture;
	/** World x, z of the map's corner (a multiple of 16); Integer.MIN_VALUE before the first build. */
	private static int originX = Integer.MIN_VALUE;
	private static int originZ;
	private static int refreshRow;
	/** The highest column top in the map (a ray above it can hit nothing). */
	private static int maxTop = 320;
	private static int bandMax = -64;

	private SunHeightmap() {
	}

	public static int originX() {
		return originX;
	}

	public static int originZ() {
		return originZ;
	}

	public static int maxTop() {
		return maxTop;
	}

	public static boolean ready() {
		return texture != null && originX != Integer.MIN_VALUE;
	}

	/** Every frame the shadows are drawn: keeps the map round the camera and up to date. */
	public static void update(Minecraft mc) {
		if (mc.level == null) {
			return;
		}
		if (texture == null) {
			texture = new DynamicTexture(() -> "Ballistic Missiles sun heightmap", SIZE, SIZE, false);
			mc.getTextureManager().register(ID, texture);
		}
		var cam = mc.gameRenderer.getMainCamera().position();
		int cx = Mth.floor(cam.x);
		int cz = Mth.floor(cam.z);
		int wantX = Math.floorDiv(cx, 16) * 16 - SIZE / 2;
		int wantZ = Math.floorDiv(cz, 16) * 16 - SIZE / 2;
		if (originX == Integer.MIN_VALUE || Math.abs(wantX - originX) > 32 || Math.abs(wantZ - originZ) > 32) {
			originX = wantX;
			originZ = wantZ;
			bandMax = -64;
			fill(mc, 0, SIZE);
			maxTop = bandMax;
			bandMax = -64;
			texture.upload();
			refreshRow = 0;
			return;
		}
		// a band of rows a frame: the whole map is fresh again within a few seconds
		fill(mc, refreshRow, refreshRow + 16);
		refreshRow = (refreshRow + 16) % SIZE;
		// a full sweep done: what it found is the new highest top (meanwhile never lower than seen)
		maxTop = Math.max(maxTop, bandMax);
		if (refreshRow == 0) {
			maxTop = bandMax;
			bandMax = -64;
		}
		texture.upload();
	}

	/** Rows {@code from}..{@code to} (map z), chunk by chunk. */
	private static void fill(Minecraft mc, int from, int to) {
		NativeImage img = texture.getPixels();
		if (img == null) {
			return;
		}
		var level = mc.level;
		int minY = level.getMinY();
		for (int z0 = from; z0 < to; z0 += 16) {
			for (int x0 = 0; x0 < SIZE; x0 += 16) {
				int chunkX = (originX + x0) >> 4;
				int chunkZ = (originZ + z0) >> 4;
				ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
				for (int dz = 0; dz < 16 && z0 + dz < to; dz++) {
					for (int dx = 0; dx < 16; dx++) {
						// the first free block above the column (unloaded: no shadow from it)
						int top = chunk == null ? minY : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, dx, dz) + 1;
						bandMax = Math.max(bandMax, top);
						int v = Mth.clamp(top + 64, 0, 65535);
						img.setPixel(x0 + dx, z0 + dz, 0xFF000000 | (v & 255) << 16 | (v >> 8 & 255) << 8);
					}
				}
			}
		}
	}

	public static void clear() {
		originX = Integer.MIN_VALUE;
	}
}
