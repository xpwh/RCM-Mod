package de.rcm.ballistic.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic ballistic flight path shared by server and client, modelled on a real missile:
 * <ol>
 *   <li><b>Boost</b>: the motor burns for the first part of the flight. The missile leaves the pad
 *       vertically and slowly, accelerates harder and harder and pitches over downrange (gravity
 *       turn) until burnout.</li>
 *   <li><b>Free flight</b>: after burnout only gravity acts, so the rest is an exact parabola. The
 *       missile slows down while it climbs to the apogee and speeds up again on the way down; the
 *       gravity of this little world is chosen so the apogee has the intended height and the arc
 *       ends exactly on the target.</li>
 * </ol>
 * Past the end of the path it keeps its final velocity until it hits something.
 */
public final class MissileTrajectory {
	/** Fraction of the flight the motor burns. */
	private static final double BOOST_FRACTION = 0.24;
	/**
	 * The free flight is flown in this fraction of the time the parabola would take. The time is
	 * warped so the missile leaves burnout at the parabola's own speed and keeps getting faster all
	 * the way down (about 2.6x at the end) instead of being fast from the start.
	 */
	private static final double COAST_SPEEDUP = 0.55;
	/** Time easing of the boost: thrust builds up, the missile creeps off the pad. */
	private static final double EASE = 1.5;

	private final Vec3 start;
	private final Vec3 burnout;
	/** Boost path as a cubic Hermite curve: tangents at the pad and at burnout (per unit of s). */
	private final Vec3 m0;
	private final Vec3 m1;
	private final Vec3 burnoutVelocity;
	private final double gravity;
	private final int boostTicks;
	private final int duration;
	/** Parabola time of the free flight, and the (shorter) real time it is flown in. */
	private final double coastParam;
	private final double coastTime;
	private final Vec3 endVelocity;

	public MissileTrajectory(Vec3 start, Vec3 target, double apexScale, double durationScale) {
		double dx = target.x - start.x;
		double dz = target.z - start.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		// every ballistic missile climbs very high - roughly 750 to 1300 blocks above its launch
		// point - whatever the range; the moon rocket leaves for space
		double height = apexScale > 2.0
			? Mth.clamp(150.0 + horizontal * 0.35, 180.0, 1200.0) * apexScale
			: Mth.clamp(800.0 + horizontal * 0.04, 800.0, 950.0) * Mth.clamp(apexScale, 0.8, 1.2);
		double apexY = Math.max(start.y, target.y) + height * 1.15;
		double nx = horizontal > 1.0E-3 ? dx / horizontal : 0.0;
		double nz = horizontal > 1.0E-3 ? dz / horizontal : 0.0;

		this.start = start;
		// the lift-off keeps its slow, heavy pace; once up in the air the missile is much faster
		// a higher arc takes longer, like a real throw (time grows with the square root of the height)
		double base = Math.max(Mth.clamp(160.0 + horizontal * 0.12, 200.0, 900.0), 13.0 * Math.sqrt(apexY - Math.min(start.y, target.y)));
		int full = (int) Math.max(60.0, base * durationScale);
		this.boostTicks = Math.max(30, (int) (full * BOOST_FRACTION));
		double coast = Math.max(20, full - this.boostTicks);
		this.duration = this.boostTicks + Math.max(12, (int) Math.round(coast * COAST_SPEEDUP));
		this.coastParam = coast;
		this.coastTime = this.duration - this.boostTicks;

		// burnout point: high above the pad, a little way downrange
		double climb = Math.max(20.0, (apexY - start.y) * 0.32);
		double downrange = Math.min(horizontal * 0.1, climb * 0.6);
		this.burnout = new Vec3(start.x + nx * downrange, start.y + climb, start.z + nz * downrange);

		// free flight from burnout to the target in `coast` ticks under gravity g, reaching apexY:
		// vy0 = dy/T + gT/2 and vy0^2 = 2gA  ->  g = (A - dy/2 + sqrt(A(A - dy))) / (T/2)^2
		double a = Math.max(1.0, apexY - this.burnout.y);
		double dy = target.y - this.burnout.y;
		a = Math.max(a, dy + 1.0);
		double k = coast / 2.0;
		this.gravity = (a - dy / 2.0 + Math.sqrt(a * (a - dy))) / (k * k);
		double vy0 = dy / coast + this.gravity * coast / 2.0;
		this.burnoutVelocity = new Vec3((target.x - this.burnout.x) / coast, vy0, (target.z - this.burnout.z) / coast);

		// boost tangents: straight up off the pad, matching the burnout velocity at the end
		// (position is a function of s = (tick / boost)^EASE, so d/dtick = d/ds * EASE / boost at s = 1)
		this.m0 = new Vec3(0, climb * 0.9, 0);
		this.m1 = this.burnoutVelocity.scale(this.boostTicks / EASE);
		this.endVelocity = this.velocity(this.duration - 0.001);
	}

	public int duration() {
		return this.duration;
	}

	/** Ticks of powered flight. */
	public int boostTicks() {
		return this.boostTicks;
	}

	public Vec3 position(double tick) {
		if (tick >= this.duration) {
			return this.position(this.duration - 0.001).add(this.endVelocity.scale(tick - this.duration));
		}
		if (tick < this.boostTicks) {
			double s = Math.pow(Math.max(0.0, tick) / this.boostTicks, EASE);
			double s2 = s * s;
			double s3 = s2 * s;
			double h00 = 2 * s3 - 3 * s2 + 1;
			double h10 = s3 - 2 * s2 + s;
			double h01 = -2 * s3 + 3 * s2;
			double h11 = s3 - s2;
			return this.start.scale(h00).add(this.m0.scale(h10)).add(this.burnout.scale(h01)).add(this.m1.scale(h11));
		}
		double t = this.warp(tick - this.boostTicks);
		return this.burnout.add(this.burnoutVelocity.scale(t)).add(0, -0.5 * this.gravity * t * t, 0);
	}

	/**
	 * Real coast time to parabola time: tau = C (k u + (1 - k) u^2) with u = t / D and k = D / C, so
	 * the speed at burnout is unchanged (no jump, no loop in the boost curve) and rises steadily.
	 */
	private double warp(double t) {
		double u = t / this.coastTime;
		double k = this.coastTime / this.coastParam;
		return this.coastParam * (k * u + (1.0 - k) * u * u);
	}

	/** d(tau)/dt of {@link #warp}. */
	private double warpRate(double t) {
		double u = t / this.coastTime;
		double k = this.coastTime / this.coastParam;
		return this.coastParam / this.coastTime * (k + 2.0 * (1.0 - k) * u);
	}

	/** Velocity in blocks per tick. */
	public Vec3 velocity(double tick) {
		if (tick >= this.duration) {
			return this.endVelocity;
		}
		if (tick < this.boostTicks) {
			double f = Math.max(1.0E-4, tick / this.boostTicks);
			double s = Math.pow(f, EASE);
			double dsdTick = EASE * Math.pow(f, EASE - 1.0) / this.boostTicks;
			double s2 = s * s;
			double d00 = 6 * s2 - 6 * s;
			double d10 = 3 * s2 - 4 * s + 1;
			double d01 = -6 * s2 + 6 * s;
			double d11 = 3 * s2 - 2 * s;
			return this.start.scale(d00).add(this.m0.scale(d10)).add(this.burnout.scale(d01)).add(this.m1.scale(d11)).scale(dsdTick);
		}
		double real = tick - this.boostTicks;
		double t = this.warp(real);
		return this.burnoutVelocity.add(0, -this.gravity * t, 0).scale(this.warpRate(real));
	}

	/** Direction the nose points; straight up right at liftoff. */
	public Vec3 direction(double tick) {
		Vec3 v = this.velocity(Math.max(tick, 0.5));
		double len = v.length();
		return len < 1.0E-6 ? new Vec3(0, 1, 0) : v.scale(1.0 / len);
	}

	/** Whether the motor is still burning at this point of the flight. */
	public boolean boosting(double tick) {
		return tick < this.boostTicks;
	}
}
