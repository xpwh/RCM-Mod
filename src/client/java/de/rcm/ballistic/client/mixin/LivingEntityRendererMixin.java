package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.effect.CorpseFx;
import de.rcm.ballistic.client.render.CorpseAge;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dead bodies left lying: not tinted red as if being hurt the whole time (see {@code MobCorpses}), and the
 * skin going pale and grey-blue as the blood sinks out of it.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
		at = @At("TAIL"))
	private void ballisticmissiles$noRedOnBodies(LivingEntity entity, LivingEntityRenderState state, float partialTick, CallbackInfo ci) {
		if (entity.deathTime > 20) {
			state.hasRedOverlay = false;
		}
		((CorpseAge) state).ballisticmissiles$setCorpseAge(CorpseFx.age(entity, partialTick));
	}

	@Inject(method = "getModelTint(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)I", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$pallor(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
		float pale = CorpseFx.pallor(((CorpseAge) state).ballisticmissiles$corpseAge());
		if (pale > 0.0F) {
			cir.setReturnValue(CorpseFx.tint(cir.getReturnValueI(), pale));
		}
	}
}
