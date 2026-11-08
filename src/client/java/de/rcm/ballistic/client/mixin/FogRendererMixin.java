package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.effect.Sky;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The fog takes the colour of the physically based sky at the horizon (see {@link Sky}). */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Inject(method = "computeFogColor", at = @At("RETURN"))
	private void ballisticmissiles$skyFog(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darken,
		CallbackInfoReturnable<Vector4f> cir) {
		Sky.fogColor(cir.getReturnValue(), partialTick);
	}
}
