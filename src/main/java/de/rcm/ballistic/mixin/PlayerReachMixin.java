package de.rcm.ballistic.mixin;

import de.rcm.ballistic.block.LargeBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A big structure is in reach when any of it is: the click range grows by how far it reaches ({@link LargeBlocks}). */
@Mixin(Player.class)
public abstract class PlayerReachMixin {
	@Inject(method = "isWithinBlockInteractionRange(Lnet/minecraft/core/BlockPos;D)Z", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$largeBlocks(BlockPos pos, double buffer, CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		double extra = LargeBlocks.reach(self.level().getBlockState(pos));
		if (extra > 0.0) {
			double range = self.blockInteractionRange() + buffer + extra;
			cir.setReturnValue(new AABB(pos).distanceToSqr(self.getEyePosition()) < range * range);
		}
	}
}
