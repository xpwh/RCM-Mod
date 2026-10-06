package de.rcm.ballistic.defense;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Areas whose electronics were knocked out by an electromagnetic pulse. */
public final class EmpManager {
	private record Zone(ResourceKey<Level> dimension, Vec3 center, double radius, long until) {
	}

	private static final List<Zone> ZONES = new ArrayList<>();

	private EmpManager() {
	}

	public static void addZone(ServerLevel level, Vec3 center, double radius, int durationTicks) {
		ZONES.add(new Zone(level.dimension(), center, radius, level.getGameTime() + durationTicks));
	}

	/** Remaining ticks the electronics at this position stay dead, 0 when working. */
	public static int jammedTicks(Level level, BlockPos pos) {
		if (ZONES.isEmpty()) {
			return 0;
		}
		long now = level.getGameTime();
		ZONES.removeIf(z -> z.until() < now);
		long best = 0;
		for (Zone z : ZONES) {
			if (z.dimension() != level.dimension()) {
				continue;
			}
			double dx = pos.getX() + 0.5 - z.center().x;
			double dz = pos.getZ() + 0.5 - z.center().z;
			if (dx * dx + dz * dz < z.radius() * z.radius()) {
				best = Math.max(best, z.until() - now);
			}
		}
		return (int) best;
	}

	public static boolean isJammed(Level level, BlockPos pos) {
		return jammedTicks(level, pos) > 0;
	}

	public static void clear() {
		ZONES.clear();
	}
}
