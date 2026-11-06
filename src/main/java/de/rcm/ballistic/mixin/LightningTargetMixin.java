package de.rcm.ballistic.mixin;

import de.rcm.ballistic.explosion.LightningStrikes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lightning goes for what stands highest round where it comes down (see LightningStrikes). */
@Mixin(ServerLevel.class)
public abstract class LightningTargetMixin {
	@Inject(method = "findLightningTargetAround", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$highest(BlockPos around, CallbackInfoReturnable<BlockPos> cir) {
		BlockPos better = LightningStrikes.target((ServerLevel) (Object) this, around, cir.getReturnValue());
		if (better != null) {
			cir.setReturnValue(better);
		}
	}
}
