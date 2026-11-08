package de.rcm.ballistic.defense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * Registry of everything flying around that radars and batteries care about. Threats report in every
 * tick while they fly, so lookups never have to scan all entities of a level.
 */
public final class ThreatTracker {
	private static final Map<ResourceKey<Level>, Map<Integer, AirThreat>> ACTIVE = new HashMap<>();

	private ThreatTracker() {
	}

	public static void report(ServerLevel level, AirThreat threat) {
		ACTIVE.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>()).put(threat.asEntity().getId(), threat);
	}

	/** All threats currently flying in this level. */
	public static List<AirThreat> threats(ServerLevel level) {
		Map<Integer, AirThreat> map = ACTIVE.get(level.dimension());
		if (map == null || map.isEmpty()) {
			return List.of();
		}
		List<AirThreat> list = new ArrayList<>(map.size());
		Iterator<AirThreat> it = map.values().iterator();
		while (it.hasNext()) {
			AirThreat t = it.next();
			Entity e = t.asEntity();
			if (e.isRemoved() || !e.isAlive() || e.level() != level || !t.isActiveThreat()) {
				it.remove();
				continue;
			}
			list.add(t);
		}
		return list;
	}

	public static void clear() {
		ACTIVE.clear();
	}
}
