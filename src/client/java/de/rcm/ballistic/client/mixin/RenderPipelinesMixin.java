package de.rcm.ballistic.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The terrain pipelines also take our wind uniform block (see Wind). */
@Mixin(RenderPipelines.class)
public abstract class RenderPipelinesMixin {
	@WrapOperation(method = "<clinit>", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withUniform(Ljava/lang/String;Lcom/mojang/blaze3d/shaders/UniformType;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"))
	private static RenderPipeline.Builder ballisticmissiles$wind(RenderPipeline.Builder builder, String name, UniformType type,
		Operation<RenderPipeline.Builder> original) {
		RenderPipeline.Builder result = original.call(builder, name, type);
		return "ChunkSection".equals(name) ? result.withUniform("BmWind", UniformType.UNIFORM_BUFFER) : result;
	}
}
