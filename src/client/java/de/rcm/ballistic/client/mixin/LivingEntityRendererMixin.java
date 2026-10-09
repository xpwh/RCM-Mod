package de.rcm.ballistic.client.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A body lying dead a while (see {@code MobCorpses}) is not tinted red as if it were being hurt the whole time. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
		at = @At("TAIL"))
	private void ballisticmissiles$noRedOnBodies(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
		if (entity.deathTime > 20) {
			state.hasRedOverlay = false;
		}
	}
}
