package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.effect.Vision;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A warm body glows white in a thermal camera, whatever the colour of its skin or fur. */
@Mixin(LivingEntityRenderer.class)
public abstract class ThermalLivingMixin {
	@Inject(method = "getWhiteOverlayProgress", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$bodyHeat(LivingEntityRenderState state, CallbackInfoReturnable<Float> cir) {
		float heat = Vision.thermal(0.0F);
		if (heat > 0.0F) {
			cir.setReturnValue(Math.max(cir.getReturnValueF(), 0.8F * heat));
		}
	}
}
