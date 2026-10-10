package de.rcm.ballistic.bunker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps track of the bunkers whose air filter is running. Anyone underground inside one is
 * sheltered: the blast passes overhead and the filtered air keeps the fallout out.
 */
public final class BunkerManager {
	/** How far from its filter a bunker room reaches. */
	private static final double REACH = 11.0;
	private static final Map<ResourceKey<Level>, Set<BlockPos>> RUNNING = new HashMap<>();

	private BunkerManager() {
	}

	public static void setRunning(Level level, BlockPos filter, boolean running) {
		Set<BlockPos> set = RUNNING.computeIfAbsent(level.dimension(), k -> new HashSet<>());
		if (running) {
			set.add(filter.immutable());
		} else {
			set.remove(filter);
		}
	}

	/** Whether {@code pos} is inside a bunker with a running air filter (and not open to the sky). */
	public static boolean sheltered(Level level, Vec3 pos) {
		Set<BlockPos> set = RUNNING.get(level.dimension());
		if (set == null || set.isEmpty()) {
			return false;
		}
		BlockPos p = BlockPos.containing(pos);
		if (level.canSeeSky(p.above())) {
			return false;
		}
		for (BlockPos f : set) {
			double dy = pos.y - f.getY();
			if (dy > -1.5 && dy < 5.0 && Math.hypot(pos.x - f.getX() - 0.5, pos.z - f.getZ() - 0.5) < REACH) {
				return true;
			}
		}
		return false;
	}

	public static void clear() {
		RUNNING.clear();
	}
}
