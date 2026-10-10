package de.rcm.ballistic.injury;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Bodies are heavy and limp, and move like it. A body has weight (it falls, and lands with a thud and a
 * small bounce, not a rebound), it drags over the ground (stopping quickly when it slides along its length,
 * less so across it, when it rolls), it bounces a little off a wall it is thrown against, it slides off a
 * ledge or down steps it lies half over. And it turns ({@link CorpsePose#TURN}): flung by a blast or struck off
 * its middle by a round it tumbles; rolling over the ground it turns over its long axis; at rest it tips to
 * lie flat again - on whichever side it came down, face up or face down. A person's body (a mannequin, which
 * by itself does not move at all) is moved entirely here; a dead creature moves by itself, and is only given
 * the rest.
 */
public final class CorpsePhysics {
	/** How each body is turning: radians a tick, about an axis in the world. */
	private static final Map<LivingEntity, Vector3f> SPIN = new WeakHashMap<>();
	/** How fast each body was falling a tick ago, for the landing. */
	private static final Map<LivingEntity, Double> FALLING = new WeakHashMap<>();

	private CorpsePhysics() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(CorpsePhysics::tick);
	}

	public static void clear() {
		SPIN.clear();
		FALLING.clear();
	}

	private static void tick(MinecraftServer server) {
		for (Mannequin m : new ArrayList<>(Corpses.bodies())) {
			if (!m.isRemoved()) {
				person(m);
				turn(m);
			}
		}
		for (LivingEntity e : new ArrayList<>(MobCorpses.lying())) {
			if (e != null && !e.isRemoved() && e.deathTime > 0) {
				creature(e);
				if (e.deathTime >= 20) {
					turn(e);
				}
			}
		}
	}

	/** A person's body: moved here, start to finish. */
	private static void person(Mannequin m) {
		Vec3 v = m.getDeltaMovement();
		Vec3 slide = m.onGround() ? slope(m) : null;
		Vector3f spin = SPIN.get(m);
		if (m.onGround() && slide == null && v.lengthSqr() < 1.0E-5 && (spin == null || spin.lengthSquared() < 1.0E-6)) {
			return; // lying still
		}
		v = v.add(0, -0.08, 0);
		if (slide != null) {
			v = v.add(slide);
		}
		Vec3 before = m.position();
		boolean wasAir = !m.onGround();
		m.setDeltaMovement(v);
		m.move(MoverType.SELF, v);
		Vec3 moved = m.position().subtract(before);
		double vx = v.x;
		double vy = v.y;
		double vz = v.z;
		// thrown against a wall: it stops dead there, and comes a little way back off it
		if (Math.abs(vx) > 0.02 && Math.abs(moved.x) < Math.abs(vx) * 0.5) {
			vx = -vx * 0.25;
		}
		if (Math.abs(vz) > 0.02 && Math.abs(moved.z) < Math.abs(vz) * 0.5) {
			vz = -vz * 0.25;
		}
		if (m.onGround()) {
			// down: a heavy, dull landing - only a hard fall bounces it, and only a little
			vy = wasAir && vy < -0.35 ? -vy * 0.18 : 0.0;
			Vec3 drag = drag(m, new Vec3(vx, 0, vz));
			vx = drag.x;
			vz = drag.z;
		} else {
			vx *= 0.98;
			vy *= 0.98;
			vz *= 0.98;
		}
		if (m.onGround() && vx * vx + vz * vz < 1.0E-4) {
			vx = 0.0;
			vz = 0.0;
		}
		m.setDeltaMovement(vx, vy, vz);
	}

	/** A dead creature moves by itself: given its slide off ledges, its landing, its drag. */
	private static void creature(LivingEntity e) {
		Vec3 v = e.getDeltaMovement();
		Double was = FALLING.put(e, v.y);
		if (e.onGround()) {
			if (was != null && was < -0.4) {
				// landing hard: a dull bounce
				e.setDeltaMovement(v.x * 0.7, -was * 0.18, v.z * 0.7);
				e.hurtMarked = true;
				return;
			}
			Vec3 slide = slope(e);
			if (slide != null) {
				e.setDeltaMovement(v.add(slide));
			}
		}
	}

	/** Dragging over the ground: hard going along the body's length, easier across it, where it rolls. */
	private static Vec3 drag(LivingEntity e, Vec3 v) {
		Vector3f axis = longAxis(e);
		double along = v.x * axis.x + v.z * axis.z;
		Vec3 alongV = new Vec3(axis.x * along, 0, axis.z * along);
		Vec3 across = v.subtract(alongV);
		return alongV.scale(0.5).add(across.scale(0.72));
	}

	/** The body's long axis in the world (head to foot), as it lies now. */
	private static Vector3f longAxis(LivingEntity e) {
		Vector3f a = CorpsePose.facing(e).transform(new Vector3f(0, 0, 1));
		CorpsePose.turn(e).transform(a);
		return a.normalize();
	}

	/**
	 * Turning: tumbling in the air, rolling over the ground, tipping back flat at rest. Rolling, a body turns
	 * as far as it moves sideways over its own thickness.
	 */
	private static void turn(LivingEntity e) {
		Vector3f w = SPIN.computeIfAbsent(e, k -> new Vector3f());
		Quaternionf q = CorpsePose.turn(e);
		Vec3 v = e.getDeltaMovement();
		Vector3f up = new Vector3f(0, 1, 0);
		if (e.onGround()) {
			Vector3f axis = longAxis(e);
			Vector3f lateral = new Vector3f((float) v.x, 0, (float) v.z);
			lateral.sub(new Vector3f(axis).mul(lateral.dot(axis)));
			float radius = e instanceof Mannequin ? 0.22F : Math.max(0.15F, e.getBbWidth() * 0.45F);
			float roll = new Vector3f(up).cross(lateral).dot(axis) / radius;
			w.mul(0.5F).add(new Vector3f(axis).mul(roll * 0.5F));
			// its weight tips it back to lie flat: whichever is nearer, as it lay or turned right over
			Vector3f n = q.transform(new Vector3f(up));
			Vector3f target = n.y >= 0.0F ? new Vector3f(up) : new Vector3f(0, -1, 0);
			Vector3f tip = new Vector3f(n).cross(target);
			float angle = (float) Math.acos(Math.max(-1.0F, Math.min(1.0F, n.dot(target))));
			if (tip.lengthSquared() > 1.0E-8F) {
				w.add(tip.normalize().mul(Math.min(0.2F, angle * 0.35F)));
			}
			w.mul(0.6F);
			if (angle < 0.03F && w.lengthSquared() < 4.0E-4F && v.horizontalDistanceSqr() < 1.0E-4) {
				// settled: lying flat (and nothing more to tell the clients once it lies still)
				w.zero();
				if (angle > 1.0E-4F) {
					q.premul(new Quaternionf().rotationTo(n, target));
					CorpsePose.setTurn(e, q.normalize());
				}
				return;
			}
		} else {
			w.mul(0.99F);
		}
		float rate = w.length();
		if (rate > 1.0E-3F) {
			q.premul(new Quaternionf().rotationAxis(rate, w.x / rate, w.y / rate, w.z / rate)).normalize();
			CorpsePose.setTurn(e, q);
		}
	}

	/** A push into the body at {@code at} (world): off its middle, it sets it turning. */
	public static void spin(LivingEntity e, Vec3 at, Vec3 push) {
		Vector3f c = CorpsePose.centre(e);
		Vector3f lever = new Vector3f((float) (at.x - e.getX()), (float) (at.y - e.getY()), (float) (at.z - e.getZ())).sub(c);
		Vector3f torque = lever.cross(new Vector3f((float) push.x, (float) push.y, (float) push.z));
		// a person's body is long and thin: it turns easily about its length, hard end over end
		float ease = e instanceof Mannequin ? 2.5F : 4.0F;
		SPIN.computeIfAbsent(e, k -> new Vector3f()).add(torque.mul(ease));
	}

	/** Lying half over a drop - a ledge, a step, the edge of a crater: pulled over it by its own weight. */
	private static Vec3 slope(LivingEntity e) {
		Level level = e.level();
		double c = ground(level, e.getX(), e.getY(), e.getZ());
		double reach = Math.max(0.6, e.getBbWidth() * 0.9);
		double xp = Math.min(c, ground(level, e.getX() + reach, e.getY(), e.getZ()));
		double xm = Math.min(c, ground(level, e.getX() - reach, e.getY(), e.getZ()));
		double zp = Math.min(c, ground(level, e.getX(), e.getY(), e.getZ() + reach));
		double zm = Math.min(c, ground(level, e.getX(), e.getY(), e.getZ() - reach));
		double gx = (xp - xm) / (2 * reach);
		double gz = (zp - zm) / (2 * reach);
		double g = Math.sqrt(gx * gx + gz * gz);
		if (g < 0.35) {
			return null;
		}
		return new Vec3(gx, 0, gz).scale(-0.035 * Math.min(g, 1.5) / g);
	}

	/** The height of the ground under (x, z), looking down from a little above y. */
	private static double ground(Level level, double x, double y, double z) {
		BlockPos.MutableBlockPos p = BlockPos.containing(x, y + 0.4, z).mutable();
		for (int i = 0; i < 4; i++) {
			BlockState state = level.getBlockState(p);
			VoxelShape shape = state.getCollisionShape(level, p);
			if (!shape.isEmpty()) {
				return p.getY() + shape.max(Direction.Axis.Y);
			}
			p.move(0, -1, 0);
		}
		return y - 4.0;
	}

	/**
	 * A blast near bodies lying about: close by, what is left is torn to pieces; further off they are thrown
	 * - up and over, as the shock lifts them, tumbling - before the blast's own push sends them away from it.
	 */
	public static void blast(Level level, Vec3 centre, float radius) {
		float reach = radius * 2.0F;
		AABB box = new AABB(centre, centre).inflate(reach + 2.0F);
		var random = level.getRandom();
		for (LivingEntity body : level.getEntitiesOfClass(LivingEntity.class, box, CorpseHits::isCorpse)) {
			double d = Math.sqrt(body.distanceToSqr(centre)) / reach;
			if (d > 1.0) {
				continue;
			}
			double seen = net.minecraft.world.level.ServerExplosion.getSeenPercent(centre, body);
			double power = (1.0 - d) * seen;
			if (radius >= 2.5F && power > 0.55 && Blood.bleeds(body)) {
				Blood.gib(body, centre, (float) Math.min(2.5, power * radius / 3.0));
				body.discard();
				continue;
			}
			Vec3 dir = body.position().add(0, 0.3, 0).subtract(centre);
			dir = dir.lengthSqr() < 1.0E-4 ? new Vec3(0, 1, 0) : dir.normalize();
			Vec3 push = new Vec3(dir.x * power * 0.5, power * (0.35 + Math.min(0.5, radius * 0.04)), dir.z * power * 0.5);
			body.push(push.x, push.y, push.z);
			body.hurtMarked = true;
			// struck nearer one end than the other, it goes over and over
			Vector3f c = CorpsePose.centre(body);
			Vector3f axis = longAxis(body);
			float off = (random.nextFloat() - 0.5F) * 1.2F;
			Vec3 at = body.position().add(c.x + axis.x * off, c.y - 0.1, c.z + axis.z * off);
			spin(body, at, push.scale(1.0 + random.nextDouble()));
		}
	}
}
