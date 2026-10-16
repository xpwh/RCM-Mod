package de.rcm.ballistic.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets a drone re-centre what the server sends its pilot. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Invoker("updateChunkTracking")
	void ballisticmissiles$updateChunkTracking(ServerPlayer player);
}
