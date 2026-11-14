package de.rcm.ballistic.entity;

/**
 * Staging of the multi-stage strategic missiles. A Minuteman-style ICBM burns three solid stages,
 * a Titan-style heavy ICBM two liquid stages: each spent stage separates at its burnout and falls
 * away, the next one lights. Cuts are heights in model space (before the missile's scale) where the
 * stages join; separation times are fractions of the powered flight.
 */
public final class MissileStages {
	private static final float[] ICBM_CUTS = {5.26F, 8.05F};
	private static final float[] ICBM_RADII = {0.575F, 0.53F};
	private static final double[] ICBM_TIMES = {0.4, 0.72};
	private static final float[] HEAVY_CUTS = {7.4F};
	private static final float[] HEAVY_RADII = {0.58F};
	private static final double[] HEAVY_TIMES = {0.55};
	private static final float[] NONE = {};
	private static final double[] NO_TIMES = {};

	private MissileStages() {
	}

	public static float[] cuts(MissileType type) {
		return switch (type.model) {
			case ICBM -> ICBM_CUTS;
			case HEAVY_ICBM -> HEAVY_CUTS;
			default -> NONE;
		};
	}

	/** Body radius at each cut (model space). */
	public static float[] radii(MissileType type) {
		return switch (type.model) {
			case ICBM -> ICBM_RADII;
			case HEAVY_ICBM -> HEAVY_RADII;
			default -> NONE;
		};
	}

	private static double[] times(MissileType type) {
		return switch (type.model) {
			case ICBM -> ICBM_TIMES;
			case HEAVY_ICBM -> HEAVY_TIMES;
			default -> NO_TIMES;
		};
	}

	/** How many stages have separated at {@code age} ticks into the flight. */
	public static int dropped(MissileType type, MissileTrajectory path, double age) {
		double[] t = times(type);
		int n = 0;
		while (n < t.length && age >= t[n] * path.boostTicks()) {
			n++;
		}
		return n;
	}

	/** Model-space height of the bottom of what is still flying after {@code dropped} stages. */
	public static float bottom(MissileType type, int dropped) {
		float[] c = cuts(type);
		return dropped <= 0 || c.length == 0 ? 0.0F : c[Math.min(dropped, c.length) - 1];
	}
}
