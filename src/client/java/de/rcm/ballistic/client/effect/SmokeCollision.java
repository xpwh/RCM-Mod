package de.rcm.ballistic.client.effect;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Smoke meeting the world. Rising smoke that runs into a ceiling - a roof, an overhang, the top of
 * a tunnel - stops rising, pools under it and spreads sideways, feeling its way to the nearest edge
 * where it can climb again and pours out round it; a wall turns drifting smoke aside instead of
 * letting it through. Shared by the smoke puffs ({@link SmokeField}) and the explosion clouds.
 * <p>
 * Cheap where it does not matter: smoke above everything in its column (open sky) is not checked.
 */
public final class SmokeCollision {
	/** Bits of the result: stopped by something above / below / a wall across X / across Z. */
	public static final int CEILING = 1;
	public static final int FLOOR = 2;
	public static final int WALL_X = 4;
	public static final int WALL_Z = 8;

	private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
	private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

	private SmokeCollision() {
	}

	public static boolean solid(Level level, double x, double y, double z) {
		POS.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
		if (!level.isLoaded(POS)) {
			return false;
		}
		BlockState state = level.getBlockState(POS);
		if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
			return false; // smoke drifts up through a canopy
		}
		return state.blocksMotion() || state.isSolid();
	}

	private static int top(Level level, double x, double z) {
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
	}

	/**
	 * Clips the move (mx, my, mz) of smoke of half-thickness {@code body} at (x, y, z) against the
	 * blocks, writing the allowed move into {@code out}; returns what stopped it (bits above).
	 */
	public static int move(Level level, double x, double y, double z, double mx, double my, double mz, double body, double[] out) {
		out[0] = mx;
		out[1] = my;
		out[2] = mz;
		// in the open, above everything in this column and the next: nothing to hit
		double low = y + Math.min(my, 0.0) - 0.3;
		if (low > top(level, x, z) + 0.05 && low > top(level, x + mx, z + mz) + 0.05) {
			return 0;
		}
		int hit = 0;
		// started inside a block (a puff born in a wall, a block placed into smoke): let it ooze out upward
		if (solid(level, x, y, z)) {
			out[1] = Math.max(my, 0.12);
			return CEILING;
		}
		double head = Math.min(body * 0.35, 1.2) + 0.35;
		if (my > 0.0 && solid(level, x, y + my + head, z)) {
			out[1] = 0.0;
			hit |= CEILING;
		} else if (my < 0.0 && solid(level, x, y + my - 0.3, z)) {
			out[1] = 0.0;
			hit |= FLOOR;
		}
		double ny = y + out[1];
		if (solid(level, x + mx, ny, z) || solid(level, x + mx, ny + head * 0.5, z)) {
			out[0] = 0.0;
			hit |= WALL_X;
		}
		if (solid(level, x + out[0], ny, z + mz) || solid(level, x + out[0], ny + head * 0.5, z + mz)) {
			out[2] = 0.0;
			hit |= WALL_Z;
		}
		return hit;
	}

	/**
	 * The way out from under a ceiling: the nearest horizontal direction (within a few blocks) where
	 * the smoke is not walled in and the space above opens up. Written to {@code out} as a unit
	 * vector (x, z); if there is none, a direction from {@code seed} so it still spreads.
	 */
	public static void escape(Level level, double x, double y, double z, float seed, double body, double[] out) {
		double head = Math.min(body * 0.35, 1.2) + 0.35;
		int start = Math.floorMod((int) (seed * 7.0F), DIRS.length);
		for (int reach = 1; reach <= 6; reach++) {
			for (int k = 0; k < DIRS.length; k++) {
				int[] d = DIRS[(start + k) % DIRS.length];
				double len = d[0] != 0 && d[1] != 0 ? 0.7071 : 1.0;
				double ex = x + d[0] * len * reach;
				double ez = z + d[1] * len * reach;
				if (solid(level, ex, y, ez)) {
					continue; // walled off that way
				}
				if (!solid(level, ex, y + head + 0.6, ez)) {
					out[0] = d[0] * len;
					out[1] = d[1] * len;
					return;
				}
			}
		}
		out[0] = Mth.cos(seed * 3.1F);
		out[1] = Mth.sin(seed * 3.1F);
	}
}
