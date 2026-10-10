package de.rcm.ballistic.injury;

import java.util.ArrayList;
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

/**
 * Bodies are heavy and limp: they fall, tumble and slide. A person's body (a mannequin, which by itself
 * does not move) is given gravity, the drag of a body scraping over the ground, and what blasts and rounds
 * push into it; a dead creature moves by itself already. Both slide off a ledge they lie half over and on
 * down a slope of steps, as a limp body does. (How they tumble and flop while they move is the client's.)
 */
public final class CorpsePhysics {
	private CorpsePhysics() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(CorpsePhysics::tick);
	}

	private static void tick(MinecraftServer server) {
		for (Mannequin m : new ArrayList<>(Corpses.bodies())) {
			if (!m.isRemoved()) {
				person(m);
			}
		}
		for (LivingEntity e : new ArrayList<>(MobCorpses.lying())) {
			if (e != null && !e.isRemoved() && e.deathTime > 0 && e.onGround()) {
				Vec3 slide = slope(e);
				if (slide != null) {
					e.setDeltaMovement(e.getDeltaMovement().add(slide));
				}
			}
		}
	}

	private static void person(Mannequin m) {
		Vec3 v = m.getDeltaMovement();
		Vec3 slide = m.onGround() ? slope(m) : null;
		if (m.onGround() && slide == null && v.lengthSqr() < 1.0E-5) {
			return; // lying still
		}
		v = v.add(0, -0.08, 0);
		if (slide != null) {
			v = v.add(slide);
		}
		m.setDeltaMovement(v);
		m.move(MoverType.SELF, v);
		v = m.getDeltaMovement();
		// a body drags over the ground: it stops quickly once it is down
		double drag = m.onGround() ? 0.55 : 0.98;
		v = new Vec3(v.x * drag, v.y * 0.98, v.z * drag);
		if (m.onGround() && v.horizontalDistanceSqr() < 1.0E-4) {
			v = new Vec3(0, Math.max(0.0, v.y), 0);
		}
		m.setDeltaMovement(v);
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
	 * - up and over, as the shock lifts them - before the blast's own push sends them away from it.
	 */
	public static void blast(Level level, Vec3 centre, float radius) {
		float reach = radius * 2.0F;
		AABB box = new AABB(centre, centre).inflate(reach + 1.0F);
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
			body.push(dir.x * power * 0.5, power * (0.35 + Math.min(0.5, radius * 0.04)), dir.z * power * 0.5);
			body.hurtMarked = true;
		}
	}
}
