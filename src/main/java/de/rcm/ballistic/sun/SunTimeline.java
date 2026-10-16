package de.rcm.ballistic.sun;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The Sun Destroyer's whole strike as a function of time, the same on the server and every client: from the
 * moment the launch is ordered, the countdown at the Helios launch complex, the shot out of the accelerator,
 * the flight, and the artificial sun at the target - so the server only has to say when and where, and
 * everyone sees the same thing at the same moment. Times are ticks since the launch order.
 */
public final class SunTimeline {
	// ---- the countdown at the launch complex
	/** The blast doors over the shaft open. */
	public static final int DOORS_OPEN = 80;
	/** The service crane swings away. */
	public static final int CRANE_FROM = 30;
	public static final int CRANE_TO = 130;
	/** The round rises on its cradle from the shaft into the breech of the accelerator. */
	public static final int LIFT_FROM = 90;
	public static final int LIFT_TO = 210;
	/** The coils charge, ring after ring from the bottom up. */
	public static final int CHARGE_FROM = 220;
	public static final int CHARGE_TO = 520;
	/** Fire. */
	public static final int FIRE = 600;
	/** Height of the accelerator's muzzle above the complex's block, and how many coil rings the tower has. */
	public static final float MUZZLE = 52.0F;
	public static final int RINGS = 14;
	public static final float RING_BASE = 6.0F;
	public static final float RING_STEP = 3.2F;

	// ---- the strike, after impact
	/** Light bends and everything is drawn in: the core is collapsing into a star. */
	public static final int IMPLODE = 40;
	/** The star grows to full size. */
	public static final int GROWN = 120;
	/** It burns. */
	public static final int BURN_END = 460;
	/** It collapses... */
	public static final int COLLAPSED = 500;
	/** ...and goes nova: the shock races out; the glow and the smoke linger. */
	public static final int NOVA = 500;
	public static final int AFTER_END = 980;
	/** Full radius of the sun. */
	public static final float RADIUS = 34.0F;
	/** How far the nova's shock and heat reach. */
	public static final float NOVA_REACH = 260.0F;

	private SunTimeline() {
	}

	/** The muzzle of the accelerator at the complex in {@code launcher}. */
	public static Vec3 muzzle(BlockPos launcher) {
		return new Vec3(launcher.getX() + 0.5, launcher.getY() + MUZZLE, launcher.getZ() + 0.5);
	}

	/** Flight time from the muzzle to the target, ticks. */
	public static int flightTicks(BlockPos launcher, Vec3 target) {
		double d = Math.hypot(target.x - launcher.getX(), target.z - launcher.getZ());
		return Mth.clamp((int) (90 + d / 9.0), 110, 520);
	}

	/** How high the flight arcs over the ground. */
	private static double apex(BlockPos launcher, Vec3 target) {
		double d = Math.hypot(target.x - launcher.getX(), target.z - launcher.getZ());
		return Mth.clamp(200.0 + d * 0.1, 200.0, 520.0);
	}

	/** Where the round is {@code f} (0-1) of the way through its flight: straight up out of the muzzle, over, down onto the target. */
	public static Vec3 flight(BlockPos launcher, Vec3 target, double f) {
		Vec3 p0 = muzzle(launcher);
		double h = apex(launcher, target);
		Vec3 p1 = new Vec3(p0.x, p0.y + h, p0.z);
		Vec3 p2 = new Vec3(target.x + (p0.x - target.x) * 0.15, target.y + h * 0.75, target.z + (p0.z - target.z) * 0.15);
		Vec3 p3 = target;
		// eased: a hard shot out of the barrel that slows over the top and speeds into the dive
		double s = f < 0.5 ? 0.5 * (1.0 - Math.pow(1.0 - 2.0 * f, 1.6)) : 0.5 + 0.5 * Math.pow(2.0 * f - 1.0, 1.35);
		double u = 1.0 - s;
		return p0.scale(u * u * u).add(p1.scale(3 * u * u * s)).add(p2.scale(3 * u * s * s)).add(p3.scale(s * s * s));
	}

	/** Direction of flight at {@code f}. */
	public static Vec3 heading(BlockPos launcher, Vec3 target, double f) {
		Vec3 a = flight(launcher, target, Math.max(0.0, f - 0.004));
		Vec3 b = flight(launcher, target, Math.min(1.0, f + 0.004));
		Vec3 d = b.subtract(a);
		return d.lengthSqr() < 1.0E-8 ? new Vec3(0, 1, 0) : d.normalize();
	}

	/** Centre of the sun over the impact point: low enough that it digs into the ground. */
	public static Vec3 sunCentre(Vec3 target) {
		return target.add(0.0, RADIUS * 0.45, 0.0);
	}

	/** Radius of the sun {@code d} ticks after impact (0 before ignition and after the nova). */
	public static float sunRadius(float d) {
		if (d < IMPLODE) {
			return RADIUS * 0.06F * smooth(d / IMPLODE); // the collapsing core, a dark speck
		}
		if (d < GROWN) {
			float x = (d - IMPLODE) / (GROWN - IMPLODE);
			return RADIUS * (0.06F + 0.94F * (1.0F - (1.0F - x) * (1.0F - x) * (1.0F - x)));
		}
		if (d < BURN_END) {
			return RADIUS * (1.0F + 0.025F * Mth.sin(d * 0.21F) + 0.015F * Mth.sin(d * 0.53F));
		}
		if (d < COLLAPSED) {
			float x = (d - BURN_END) / (COLLAPSED - BURN_END);
			return RADIUS * (1.0F - 0.72F * x * x);
		}
		return 0.0F;
	}

	/** Radius of the nova's shock front {@code d} ticks after impact (0 before it). */
	public static float shockRadius(float d) {
		if (d < NOVA) {
			return 0.0F;
		}
		float x = Mth.clamp((d - NOVA) / 90.0F, 0.0F, 1.0F);
		return NOVA_REACH * (1.0F - (1.0F - x) * (1.0F - x));
	}

	public static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	// ---- the cutscene for whoever gave the order

	/** When it starts and ends (ticks since the launch order). */
	public static int cutsceneFrom() {
		return FIRE - 140;
	}

	public static int cutsceneTo(int flight) {
		return FIRE + flight + NOVA + 170;
	}

	/** Where the target shot's camera stands, for the server to load the land round it: between it and the target. */
	public static Vec3 wideShotAnchor(BlockPos launcher, Vec3 target) {
		Vec3 away = new Vec3(launcher.getX() - target.x, 0.0, launcher.getZ() - target.z);
		away = away.lengthSqr() < 1.0 ? new Vec3(1, 0, 0) : away.normalize();
		return target.add(away.scale(100.0));
	}

	/** Where the camera is and where it looks, {@code t} ticks after the order: {x, y, z, yaw, pitch}. */
	public static double[] camera(BlockPos launcher, Vec3 target, int flight, float t) {
		Vec3 base = new Vec3(launcher.getX() + 0.5, launcher.getY(), launcher.getZ() + 0.5);
		Vec3 eye;
		Vec3 look;
		float fire = FIRE;
		float impact = FIRE + flight;
		if (t < fire + 8) {
			// a slow orbit round the tower, low, the camera climbing as the coils charge
			float a = (t - cutsceneFrom()) * 0.012F + 0.6F;
			float r = 46.0F - (t - cutsceneFrom()) * 0.06F;
			eye = base.add(Mth.cos(a) * r, 10.0 + (t - cutsceneFrom()) * 0.09, Mth.sin(a) * r);
			look = base.add(0.0, 26.0 + Math.max(0.0F, t - fire + 8) * 3.0, 0.0);
		} else if (t < fire + 40) {
			// the shot: from close under the tower, the view torn upwards after the round
			float a = (fire - cutsceneFrom()) * 0.012F + 0.6F;
			eye = base.add(Mth.cos(a) * 34.0, 6.0, Mth.sin(a) * 34.0);
			double f = Mth.clamp((t - fire) / (double) flight, 0.0, 1.0);
			look = flight(launcher, target, f);
		} else if (t < impact - 70) {
			// chasing it: behind and a little above, the world falling away beneath
			double f = Mth.clamp((t - fire) / (double) flight, 0.0, 1.0);
			Vec3 at = flight(launcher, target, f);
			Vec3 dir = heading(launcher, target, f);
			Vec3 side = dir.cross(new Vec3(0, 1, 0));
			side = side.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : side.normalize();
			eye = at.subtract(dir.scale(26.0)).add(0.0, 7.0, 0.0).add(side.scale(6.0));
			look = at.add(dir.scale(30.0));
		} else {
			// the target, from afar: the round comes down, the sun is born, burns, and goes nova
			Vec3 away = new Vec3(launcher.getX() - target.x, 0.0, launcher.getZ() - target.z);
			away = away.lengthSqr() < 1.0 ? new Vec3(1, 0, 0) : away.normalize();
			float push = Mth.clamp((t - (impact - 70)) / 600.0F, 0.0F, 1.0F);
			double dist = 230.0 - 60.0 * push;
			Vec3 side = new Vec3(-away.z, 0, away.x);
			eye = target.add(away.scale(dist)).add(side.scale(40.0)).add(0.0, 70.0 - 20.0 * push, 0.0);
			look = sunCentre(target).add(0.0, t < impact ? 30.0 : 0.0, 0.0);
		}
		Vec3 d = look.subtract(eye);
		double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
		double pitch = Math.toDegrees(-Math.atan2(d.y, Math.hypot(d.x, d.z)));
		return new double[] {eye.x, eye.y, eye.z, yaw, pitch};
	}
}
