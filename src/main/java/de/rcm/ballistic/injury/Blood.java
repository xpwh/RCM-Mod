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
}
