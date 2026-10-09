package de.rcm.ballistic.injury;

import de.rcm.ballistic.network.ModNetworking.BloodPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Tells the players nearby to draw blood: a spray from a wound, drops falling, a pool spreading. */
public final class Blood {
	public static final int SPRAY = 0;
	public static final int DRIP = 1;
	public static final int POOL = 2;
	public static final int BURST = 3;
	/** A spurt from a cut artery: a jet thrown out along {@code dir}, as hard as {@code dir} is long. */
	public static final int JET = 4;

	private Blood() {
	}

	/** Whether this creature has blood to lose (not the skeletons, golems, slimes, blazes and the like). */
	public static boolean bleeds(LivingEntity e) {
		EntityType<?> t = e.getType();
		return !(t.is(EntityTypeTags.SKELETONS) || t == EntityType.ARMOR_STAND || t == EntityType.IRON_GOLEM || t == EntityType.SNOW_GOLEM
			|| t == EntityType.SLIME || t == EntityType.MAGMA_CUBE || t == EntityType.BLAZE || t == EntityType.BREEZE || t == EntityType.VEX
			|| t == EntityType.ALLAY || t == EntityType.SHULKER || t == EntityType.CREAKING || t == EntityType.WITHER || t == EntityType.COPPER_GOLEM);
	}

	public static void send(ServerLevel level, Vec3 at, Vec3 dir, int amount, int kind) {
		BloodPayload p = new BloodPayload(at.x, at.y, at.z, (float) dir.x, (float) dir.y, (float) dir.z, amount, kind);
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(at) < 96.0 * 96.0) {
				ServerPlayNetworking.send(player, p);
			}
		}
	}

	/**
	 * A round went into a creature at {@code at} (on its hitbox grown by {@code grown}): tell those who can see
	 * it, so the wound shows where it went in - on the face of the box it struck, moved in to the body.
	 */
	/** Kinds of wound on a creature: a hole; where a round came out; torn open, meat hanging out; the belly opened, gut spilling. */
	public static final int HOLE = 0;
	public static final int EXIT = 1;
	public static final int GAPING = 2;
	public static final int ENTRAILS = 3;
	/** A body torn open by what killed it: the flank open, ribs broken, the organs out on the ground. */
	public static final int OPEN = 4;

	/** A creature killed by a round or a blast: its body lies there torn open. */
	public static void open(LivingEntity e) {
		if (!(e.level() instanceof ServerLevel level) || !bleeds(e)) {
			return;
		}
		var payload = new de.rcm.ballistic.network.ModNetworking.WoundPayload(e.getId(), 0, 0, 0, 1, 0, 0, e.getBbWidth(),
			level.getRandom().nextInt(3) | OPEN << 4);
		for (ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(e)) {
			ServerPlayNetworking.send(player, payload);
		}
		send(level, e.position().add(0, e.getBbHeight() * 0.4, 0), new Vec3(0, 0.4, 0), 40, BURST);
	}

	public static void wound(LivingEntity e, Vec3 at, double grown, boolean pellet, boolean head) {
		wound(e, at, grown, pellet, head, HOLE);
	}

	public static void wound(LivingEntity e, Vec3 at, double grown, boolean pellet, boolean head, int kind) {
		if (!(e.level() instanceof ServerLevel level) || !bleeds(e)) {
			return;
		}
		var box = e.getBoundingBox().inflate(grown);
		double[] d = {at.x - box.minX, box.maxX - at.x, at.y - box.minY, box.maxY - at.y, at.z - box.minZ, box.maxZ - at.z};
		int k = 0;
		for (int i = 1; i < 6; i++) {
			if (d[i] < d[k]) {
				k = i;
			}
		}
		Vec3 n = switch (k) {
			case 0 -> new Vec3(-1, 0, 0);
			case 1 -> new Vec3(1, 0, 0);
			case 2 -> new Vec3(0, -1, 0);
			case 3 -> new Vec3(0, 1, 0);
			case 4 -> new Vec3(0, 0, -1);
			default -> new Vec3(0, 0, 1);
		};
		Vec3 onBody = at.subtract(n.scale(grown));
		Vec3 rel = onBody.subtract(e.position());
		float size = (pellet ? 0.1F : 0.16F) * (head ? 1.4F : 1.0F) * (0.85F + level.getRandom().nextFloat() * 0.3F)
			* (kind == EXIT ? 1.7F : kind == GAPING ? 1.9F : kind == ENTRAILS ? 1.8F : 1.0F);
		var payload = new de.rcm.ballistic.network.ModNetworking.WoundPayload(e.getId(), (float) rel.x, (float) rel.y, (float) rel.z, (float) n.x,
			(float) n.y, (float) n.z, size, (kind == EXIT ? 3 : level.getRandom().nextInt(3)) | kind << 4);
		for (ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(e)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/** A body torn apart by a blast at {@code from}: the pieces for everyone near, and a great deal of blood. */
	public static void gib(LivingEntity e, Vec3 from, float force) {
		if (!(e.level() instanceof ServerLevel level)) {
			return;
		}
		Vec3 mid = e.position().add(0, e.getBbHeight() * 0.5, 0);
		Vec3 dir = from == null ? new Vec3(0, 1, 0) : mid.subtract(from);
		dir = dir.lengthSqr() < 1.0E-4 ? new Vec3(0, 1, 0) : dir.normalize();
		var payload = new de.rcm.ballistic.network.ModNetworking.GibPayload(e.getId(), e.getX(), e.getY(), e.getZ(), e.getBbWidth(), e.getBbHeight(),
			(float) dir.x, (float) dir.y, (float) dir.z, force);
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(mid) < 128.0 * 128.0) {
				ServerPlayNetworking.send(player, payload);
			}
		}
		send(level, mid, new Vec3(0, 0.6, 0), 60, BURST);
		send(level, mid, dir.add(0, 0.5, 0), 60, BURST);
		send(level, mid, dir, 50, SPRAY);
		send(level, e.position().add(0, 0.2, 0), Vec3.ZERO, 40, POOL);
	}
}
