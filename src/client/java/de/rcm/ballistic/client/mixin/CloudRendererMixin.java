package de.rcm.ballistic.client.mixin;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The flat blocky clouds give way to the volumetric ones ({@link de.rcm.ballistic.client.effect.VolumetricClouds}). */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$volumetricInstead(int color, CloudStatus status, float height, Vec3 camera, long gameTime, float partialTick,
		CallbackInfo ci) {
		ci.cancel();
	}
}
