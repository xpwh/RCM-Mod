package de.rcm.ballistic.explosion;

import net.minecraft.util.Mth;

/**
 * The wind, the same on server and client because it only depends on the game time: smoke,
 * mushroom clouds and contrails drift with it, and fallout is carried downwind of ground zero.
 * The direction veers slowly over the day, gusts come every few seconds, the speed grows with
 * height (wind shear) and the direction turns a little with height too, so tall plumes lean over
 * and long contrails twist.
 */
public final class Wind {
	/** Mean wind speed at ground level, blocks per tick (about 0.5 m/s). */
	private static final double BASE = 0.022;

	private Wind() {
	}

	/** Wind direction (radians, in the x-z plane) at a height. */
	public static double direction(long gameTime, double y) {
		double t = gameTime;
		double veer = Mth.clamp((y - 70.0) / 520.0, 0.0, 1.1); // up to about 60 degrees at the top of the sky
		return 0.4 + 0.9 * Math.sin(t / 24000.0 * Mth.TWO_PI * 0.7) + 0.25 * Math.sin(t / 3100.0) + veer;
	}

	/** Wind speed in blocks per tick at a height, with gusts. */
	public static double speed(long gameTime, double y) {
		double t = gameTime;
		double gust = 1.0 + 0.35 * Math.sin(t * 0.047) + 0.2 * Math.sin(t * 0.131 + 1.3);
		double shear = Mth.clamp(1.0 + (y - 70.0) / 110.0, 0.6, 3.2);
		return BASE * gust * shear;
	}

	/** Wind velocity {x, z} in blocks per tick. */
	public static double[] at(long gameTime, double y) {
		double direction = direction(gameTime, y);
		double speed = speed(gameTime, y);
		return new double[] {Math.cos(direction) * speed, Math.sin(direction) * speed};
	}
}
