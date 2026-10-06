package de.rcm.ballistic.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic ballistic flight path shared by server and client.
 * <p>
 * The path is a cubic Bezier curve: vertical boost out of the pad, a high arc, and a steep
 * re-entry onto the target. Time is eased (t = s^1.7) so the missile lifts off slowly and
 * is fastest during re-entry. Past the end of the curve it keeps its final velocity until it
 * hits something.
 */
public final class MissileTrajectory {
	private static final double EASE = 1.7;

	private final Vec3 p0;
	private final Vec3 p1;
	private final Vec3 p2;
	private final Vec3 p3;
	private final int duration;
	private final Vec3 endVelocity;

	public MissileTrajectory(Vec3 start, Vec3 target, double apexScale, double durationScale) {
		double dx = target.x - start.x;
		double dz = target.z - start.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double height = Mth.clamp(150.0 + horizontal * 0.35, 180.0, 1200.0) * apexScale;
		double topY = Math.max(start.y, target.y) + height * 1.33;

		double nx = horizontal > 1.0E-3 ? dx / horizontal : 0.0;
		double nz = horizontal > 1.0E-3 ? dz / horizontal : 0.0;
		double back = horizontal * 0.12;

		this.p0 = start;
		this.p1 = new Vec3(start.x, topY, start.z);
		this.p2 = new Vec3(target.x - nx * back, topY, target.z - nz * back);
		this.p3 = target;
		this.duration = (int) Math.max(60.0, Mth.clamp(160.0 + horizontal * 0.12, 200.0, 900.0) * durationScale);
		this.endVelocity = this.velocity(this.duration - 0.001);
	}

	public int duration() {
		return this.duration;
	}

	public Vec3 position(double tick) {
		if (tick >= this.duration) {
			return this.p3.add(this.endVelocity.scale(tick - this.duration));
		}
		double t = Math.pow(Math.max(0.0, tick) / this.duration, EASE);
		double u = 1.0 - t;
		double a = u * u * u;
		double b = 3.0 * u * u * t;
		double c = 3.0 * u * t * t;
		double d = t * t * t;
		return new Vec3(
			a * p0.x + b * p1.x + c * p2.x + d * p3.x,
			a * p0.y + b * p1.y + c * p2.y + d * p3.y,
			a * p0.z + b * p1.z + c * p2.z + d * p3.z
		);
	}

	/** Velocity in blocks per tick. */
	public Vec3 velocity(double tick) {
		if (tick >= this.duration) {
			return this.endVelocity;
		}
		double s = Math.max(1.0E-4, tick / this.duration);
		double t = Math.pow(s, EASE);
		double dtdTick = EASE * Math.pow(s, EASE - 1.0) / this.duration;
		double u = 1.0 - t;
		double a = 3.0 * u * u;
		double b = 6.0 * u * t;
		double c = 3.0 * t * t;
		return new Vec3(
			(a * (p1.x - p0.x) + b * (p2.x - p1.x) + c * (p3.x - p2.x)) * dtdTick,
			(a * (p1.y - p0.y) + b * (p2.y - p1.y) + c * (p3.y - p2.y)) * dtdTick,
			(a * (p1.z - p0.z) + b * (p2.z - p1.z) + c * (p3.z - p2.z)) * dtdTick
		);
	}

	/** Direction the nose points; straight up right at liftoff. */
	public Vec3 direction(double tick) {
		Vec3 v = this.velocity(Math.max(tick, 0.5));
		double len = v.length();
		return len < 1.0E-6 ? new Vec3(0, 1, 0) : v.scale(1.0 / len);
	}
}
