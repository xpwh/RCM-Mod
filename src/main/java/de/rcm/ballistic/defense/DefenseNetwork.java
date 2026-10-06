package de.rcm.ballistic.defense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Known radar, air-defense and silo sites per dimension (server side), so they can find each other. */
public final class DefenseNetwork {
	public enum Kind {
		RADAR,
		AIR_DEFENSE,
		SILO
	}

	private static final Map<ResourceKey<Level>, Map<BlockPos, Kind>> SITES = new HashMap<>();

	private DefenseNetwork() {
	}

	public static void register(Level level, BlockPos pos, Kind kind) {
		SITES.computeIfAbsent(level.dimension(), k -> new HashMap<>()).put(pos.immutable(), kind);
	}

	public static void unregister(Level level, BlockPos pos) {
		Map<BlockPos, Kind> map = SITES.get(level.dimension());
		if (map != null) {
			map.remove(pos);
		}
	}

	/** Sites of the given kind within a horizontal radius, closest first. */
	public static List<BlockPos> find(Level level, Kind kind, Vec3 near, double radius) {
		Map<BlockPos, Kind> map = SITES.get(level.dimension());
		List<BlockPos> list = new ArrayList<>();
		if (map == null) {
			return list;
		}
		double r2 = radius * radius;
		for (Map.Entry<BlockPos, Kind> e : map.entrySet()) {
			if (e.getValue() != kind) {
				continue;
			}
			BlockPos p = e.getKey();
			double dx = p.getX() + 0.5 - near.x;
			double dz = p.getZ() + 0.5 - near.z;
			if (dx * dx + dz * dz <= r2) {
				list.add(p);
			}
		}
		list.sort((a, b) -> Double.compare(a.distToCenterSqr(near), b.distToCenterSqr(near)));
		return list;
	}

	public static void clear() {
		SITES.clear();
	}
}
