package de.rcm.ballistic.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.client.effect.Wind;
import java.util.List;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Tells the buffer when it is meshing a plant or leaves, so their vertices are marked for the wind (see Wind). */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLjava/util/List;)V"))
	private void ballisticmissiles$sway(BlockRenderDispatcher dispatcher, BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack poseStack,
		VertexConsumer consumer, boolean cull, List<BlockModelPart> parts, Operation<Void> original) {
		int kind = Wind.kind(state);
		if (kind == Wind.NONE || !(consumer instanceof Wind.Target target)) {
			original.call(dispatcher, state, pos, level, poseStack, consumer, cull, parts);
			return;
		}
		target.ballisticmissiles$sway(kind, SectionPos.sectionRelative(pos.getY()));
		try {
			original.call(dispatcher, state, pos, level, poseStack, consumer, cull, parts);
		} finally {
			target.ballisticmissiles$sway(Wind.NONE, 0.0F);
		}
	}
}
