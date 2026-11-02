package de.rcm.ballistic.client.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import de.rcm.ballistic.client.effect.Wind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every render pass gets the wind uniform block next to the default ones (see Wind). */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
	@Inject(method = "bindDefaultUniforms", at = @At("TAIL"))
	private static void ballisticmissiles$wind(RenderPass pass, CallbackInfo ci) {
		Wind.bind(pass);
	}
}
