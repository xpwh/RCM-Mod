package de.rcm.ballistic.mixin;

import de.rcm.ballistic.entity.FpvDroneEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Entities are sent to a drone pilot by their distance from the drone, not from the pilot's body. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
	@Redirect(
		method = "updatePlayer",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;position()Lnet/minecraft/world/phys/Vec3;"),
		require = 0
	)
	private Vec3 ballisticmissiles$droneView(ServerPlayer player) {
		return FpvDroneEntity.viewPosition(player);
	}
}
