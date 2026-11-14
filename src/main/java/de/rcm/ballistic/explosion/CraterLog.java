package de.rcm.ballistic.explosion;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the blasts destroyed, to put it back ({@code /bmworld krater reparieren}). Every block the mod's
 * explosions, craters, scorching, fires, tsunamis change - and every block a vanilla explosion takes - is
 * remembered as it was before the first change. A repair puts the remembered blocks back, bottom up, a
 * few thousand a tick so a nuclear crater does not freeze the server.
 * <p>
 * Kept while the server runs (a world reopened starts with an empty log), at most {@link #LIMIT} blocks a
 * dimension. What a chest held is not kept, only the block.
 */
public final class CraterLog {
	private static final int LIMIT = 3_000_000;
	private static final int PER_TICK = 6000;
	private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<BlockState>> LOG = new HashMap<>();
	private static final List<Repair> REPAIRS = new ArrayList<>();
	/** Inside a vanilla explosion: every block it changes is remembered too. */
	private static int depth;

	private CraterLog() {
	}

	private record Repair(ServerLevel level, long[] positions, int[] next) {
	}

	/** Remembers the block at {@code pos} as it is now, unless it was already remembered. */
	public static void record(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) {
			return;
		}
		var log = LOG.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
		long key = pos.asLong();
		if (log.size() < LIMIT && !log.containsKey(key)) {
			log.put(key, level.getBlockState(pos));
		}
	}

	/** {@link Level#setBlock}, remembering what was there. */
	public static boolean set(Level level, BlockPos pos, BlockState state, int flags) {
		record(level, pos);
		return level.setBlock(pos, state, flags);
	}

	/** {@link Level#destroyBlock}, remembering what was there. */
	public static boolean destroy(Level level, BlockPos pos, boolean drop) {
		record(level, pos);
		return level.destroyBlock(pos, drop);
	}

	public static boolean recording() {
		return depth > 0;
	}

	public static void begin() {
		depth++;
	}

	public static void end() {
		depth = Math.max(0, depth - 1);
	}

	/** How many destroyed blocks are remembered in this dimension. */
	public static int remembered(ServerLevel level) {
		var log = LOG.get(level.dimension());
		return log == null ? 0 : log.size();
	}

	/**
	 * Starts putting back everything remembered within {@code radius} blocks (across, any height) of
	 * ({@code x}, {@code z}). Returns how many blocks it will put back.
	 */
	public static int repair(ServerLevel level, double x, double z, int radius) {
		var log = LOG.get(level.dimension());
		if (log == null || log.isEmpty()) {
			return 0;
		}
		long r2 = (long) radius * radius;
		List<Long> found = new ArrayList<>();
		var it = log.long2ObjectEntrySet().fastIterator();
		while (it.hasNext()) {
			long key = it.next().getLongKey();
			double dx = BlockPos.getX(key) + 0.5 - x;
			double dz = BlockPos.getZ(key) + 0.5 - z;
			if (dx * dx + dz * dz <= r2) {
				found.add(key);
			}
		}
		// bottom up: the ground first, then what stands on it
		found.sort((a, b) -> Integer.compare(BlockPos.getY(a), BlockPos.getY(b)));
		long[] positions = new long[found.size()];
		for (int i = 0; i < positions.length; i++) {
			positions[i] = found.get(i);
		}
		REPAIRS.add(new Repair(level, positions, new int[] {0}));
		return positions.length;
	}

	public static void tick(ServerLevel level) {
		if (REPAIRS.isEmpty()) {
			return;
		}
		Iterator<Repair> it = REPAIRS.iterator();
		while (it.hasNext()) {
			Repair r = it.next();
			if (r.level() != level) {
				continue;
			}
			var log = LOG.get(level.dimension());
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			int end = Math.min(r.positions().length, r.next()[0] + PER_TICK);
			for (int i = r.next()[0]; i < end; i++) {
				long key = r.positions()[i];
				BlockState was = log == null ? null : log.remove(key);
				if (was != null) {
					pos.set(key);
					level.setBlock(pos, was, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
				}
			}
			r.next()[0] = end;
			if (end >= r.positions().length) {
				it.remove();
			}
		}
	}

	public static void clear() {
		LOG.clear();
		REPAIRS.clear();
		depth = 0;
	}
}
