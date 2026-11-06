package de.rcm.ballistic.mixin;

import de.rcm.ballistic.explosion.CraterLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Inside a vanilla explosion, every block it changes is remembered ({@link CraterLog}). */
@Mixin(Level.class)
public abstract class CraterLogMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
	private void ballisticmissiles$remember(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
		if (CraterLog.recording()) {
			CraterLog.record((Level) (Object) this, pos);
		}
	}
}
