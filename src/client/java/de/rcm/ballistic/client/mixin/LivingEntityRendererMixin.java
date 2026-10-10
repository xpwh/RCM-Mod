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
		CorpseAge body = (CorpseAge) state;
		float age = CorpseFx.age(entity, partialTick);
		body.ballisticmissiles$setCorpseAge(age);
		float[] pose = age < 0.0F ? null : CorpseFx.pose(entity, partialTick);
		body.ballisticmissiles$setPose(pose);
		if (pose != null && pose[8] > 0.05F && !(entity instanceof net.minecraft.world.entity.Avatar)) {
			// thrown or rolling, a dead animal's legs swing loose
			state.walkAnimationPos = state.ageInTicks * 1.3F;
			state.walkAnimationSpeed = Math.min(1.0F, pose[8] * 1.2F);
		}
	}

	/**
	 * A body lies where and as it lies ({@code CorpsePose}): lifted to lie on the ground rather than half in it,
	 * and turned about its middle as it has been thrown and rolled - before anything else turns it.
	 */
	@Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
		at = @At("HEAD"))
	private void ballisticmissiles$lying(LivingEntityRenderState state, com.mojang.blaze3d.vertex.PoseStack poseStack, float bodyRot, float scale,
		CallbackInfo ci) {
		float[] p = ((CorpseAge) state).ballisticmissiles$pose();
		if (p != null) {
			CorpseFx.apply(poseStack, p, Math.max(0.01F, scale));
		}
	}

	@Inject(method = "getModelTint(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;)I", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$pallor(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
		float pale = CorpseFx.pallor(((CorpseAge) state).ballisticmissiles$corpseAge());
		if (pale > 0.0F) {
			cir.setReturnValue(CorpseFx.tint(cir.getReturnValueI(), pale));
		}
	}
}
