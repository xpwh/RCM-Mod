package de.rcm.ballistic.mixin;

import de.rcm.ballistic.entity.FpvDroneEntity;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * While a player flies an FPV drone, the world they are sent is the world round the drone, so the
 * drone can fly far beyond their own view distance. (Their own surroundings stay loaded on the server.)
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	@Redirect(
		method = "updateChunkTracking",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()Lnet/minecraft/world/level/ChunkPos;"),
		require = 0
	)
	private ChunkPos ballisticmissiles$droneView(ServerPlayer player) {
		return FpvDroneEntity.viewChunk(player);
	}
}
