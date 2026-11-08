package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.effect.Vision;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Through a thermal camera everything that moves - bodies, engines - stands out bright against the cold land. */
@Mixin(EntityRenderer.class)
public abstract class ThermalEntityMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void ballisticmissiles$warm(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
		if (Vision.thermalActive()) {
			state.lightCoords = LightTexture.FULL_BRIGHT;
		}
	}
}
