package de.rcm.ballistic.mixin;

import de.rcm.ballistic.explosion.NuclearWinter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Nuclear winter: crops, stems and saplings in the open lose most of their growth ticks. */
@Mixin({CropBlock.class, StemBlock.class, SaplingBlock.class})
public abstract class GrowthMixin {
	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$winter(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (NuclearWinter.stuntsGrowth(level, pos, random)) {
			ci.cancel();
		}
	}
}
