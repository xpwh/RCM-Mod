package de.rcm.ballistic.injury;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Shooting at a body that already lies dead. Every round tears it further: on a person's body the skull
 * is blown open or the jaw torn off, rounds go right through the trunk, buckshot takes off what limbs are
 * left; an animal's body is torn wider and wider, meat and gut thrown out of it. Enough of it, and there is
 * no body left - it comes apart in pieces, as a blast would tear it.
 */
public final class CorpseHits {
	/** What a body has taken so far (a rifle round 1, a pellet less). */
	private static final Map<Entity, Float> TAKEN = new WeakHashMap<>();

	private CorpseHits() {
	}

	/** A person's body left lying ({@link Corpses}), or a dead creature's. */
	public static boolean isCorpse(Entity e) {
		if (e instanceof Mannequin m) {
			return m.hasAttached(Corpses.UNTIL);
		}
		return e instanceof LivingEntity l && !(e instanceof Player) && l.deathTime > 0 && !e.isRemoved();
	}

	/**
	 * Where the line from {@code from} to {@code to} meets the body, grown by {@code grow}: a body left lying is
	 * hit where it really lies, however it has been turned ({@link CorpsePose}); anything else by its box.
	 */
	public static java.util.Optional<Vec3> clip(Entity e, Vec3 from, Vec3 to, float grow) {
		if (isCorpse(e) && e instanceof LivingEntity body) {
			return CorpsePose.clip(body, from, to, grow);
		}
		return e.getBoundingBox().inflate(grow).clip(from, to);
	}

	/** How much a body can take before it comes apart. */
	private static float limit(LivingEntity body) {
		if (body instanceof Mannequin) {
			return 10.0F;
		}
		return Mth.clamp(3.0F + body.getMaxHealth() / 4.0F, 4.0F, 12.0F);
	}

	/**
	 * A round into a body at {@code at}, travelling along {@code line}. {@code range}: how far it flew.
	 * @return whether it came apart
	 */
	public static boolean hit(ServerLevel level, LivingEntity body, Vec3 at, Vec3 line, boolean pellet, double range) {
		if (!Blood.bleeds(body)) {
			return false;
		}
		float blow = pellet ? (range < 5.0 ? 0.7F : 0.35F) : 1.0F;
		float taken = TAKEN.merge(body, blow, Float::sum);
		if (body instanceof Mannequin m) {
			person(level, m, at, line, pellet, range);
		} else {
			// on the body as it lies, facing back at the shooter
			Blood.wound(body, at, 0.0, pellet, false, level.getRandom().nextFloat() < 0.3F ? Blood.ENTRAILS : Blood.GAPING, line.scale(-1.0));
			Injuries.bleedCreature(body, 400);
		}
		Blood.send(level, at, line, pellet ? 10 : 20, Blood.SPRAY);
		Blood.send(level, at, new Vec3(0, 0.35, 0), pellet ? 4 : 8, Blood.BURST);
		// knocked about by it - and, struck off its middle, turned
		Vec3 push = line.scale(pellet ? 0.04 : 0.09);
		body.push(push.x, 0.03, push.z);
		body.hurtMarked = true;
		CorpsePhysics.spin(body, at, push);
		if (taken >= limit(body)) {
			Blood.gib(body, at.subtract(line), 0.8F);
			TAKEN.remove(body);
			body.discard();
			return true;
		}
		return false;
	}

	/** A person's body: where on it, as it lies, and what that round does there. */
	private static void person(ServerLevel level, Mannequin m, Vec3 at, Vec3 line, boolean pellet, double range) {
		Wounds w = Injuries.get(m);
		// where on the body, as it lies (however it has been turned): along it from the feet, across it
		org.joml.Vector3f local = CorpsePose.toBody(m, at);
		boolean faceDown = w.fall() != Wounds.BACKWARD;
		double along = Corpses.HALF + (faceDown ? -local.z : local.z);
		double side = -local.x; // the body frame is the model's mirrored: +x the right
		var r = level.getRandom();
		if (along > 1.3) {
			// the head: the jaw torn away, or the skull blown open
			w = !w.jaw() && r.nextFloat() < 0.45F ? w.withJaw() : w.withHead(Wounds.SHATTERED);
		} else if (along < 0.72) {
			if (pellet && range < 6.0) {
				w = w.withLost(side > 0 ? Wounds.LEFT : Wounds.RIGHT).withBleed(0);
			} else {
				w = w.withLeg(2);
			}
		} else if (Math.abs(side) > 0.27) {
			if (pellet && range < 6.0) {
				w = w.withArmLost(side > 0 ? Wounds.LEFT : Wounds.RIGHT).withBleed(0);
			} else {
				w = w.withArm(2);
			}
		} else {
			float y = (float) ((1.5 - along) / 0.75 * 12.0);
			w = w.withTorso(w.torso() + 1);
			if (!pellet || range < 3.0) {
				// shot from above: face down, in through the back
				w = w.through((float) side * 16.0F, y, faceDown);
				exit(level, m, at, line);
			}
		}
		Injuries.set(m, w.withBleed(0));
	}

	/**
	 * A round out through the far side of a body: a mist of blood thrown out after it, onto whatever is
	 * behind - the wall, the ground.
	 */
	public static void exit(ServerLevel level, LivingEntity body, Vec3 at, Vec3 line) {
		var box = body.getBoundingBox().inflate(0.1);
		Vec3 far = at.add(line.scale(box.getXsize() + box.getYsize() + box.getZsize()));
		Vec3 out = box.clip(far, at).orElse(at.add(line.scale(0.4)));
		Blood.send(level, out, line, 26, Blood.EXIT_SPATTER);
	}
}
