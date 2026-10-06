package de.rcm.ballistic.explosion;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Radioactive contamination left behind by nuclear detonations. Dose rate falls off with distance
 * from ground zero and decays over time; read by the Geiger counter.
 */
public final class RadiationManager {
	/** Natural background radiation in microsievert per hour. */
	public static final double BACKGROUND = 0.12;

	private record Zone(ResourceKey<Level> dimension, Vec3 center, double radius, double peak, long start, long duration) {
	}

	private static final List<Zone> ZONES = new ArrayList<>();

	private RadiationManager() {
	}

	public static void addZone(ServerLevel level, Vec3 center, double radius, double peakMicroSievert, long durationTicks) {
		ZONES.add(new Zone(level.dimension(), center, radius, peakMicroSievert, level.getGameTime(), durationTicks));
	}

	/** Dose rate in microsievert per hour at a position. */
	public static double doseRate(ServerLevel level, Vec3 pos) {
		long now = level.getGameTime();
		ZONES.removeIf(z -> now - z.start() > z.duration());
		double rate = BACKGROUND;
		for (Zone z : ZONES) {
			if (z.dimension() != level.dimension()) {
				continue;
			}
			double d = pos.distanceTo(z.center());
			if (d >= z.radius()) {
				continue;
			}
			double age = (double) (now - z.start()) / z.duration();
			double decay = Math.pow(1.0 - age, 2.0); // fresh fallout is by far the worst
			double falloff = Math.pow(1.0 - d / z.radius(), 2.2);
			rate += z.peak() * falloff * decay;
		}
		return rate;
	}
}
