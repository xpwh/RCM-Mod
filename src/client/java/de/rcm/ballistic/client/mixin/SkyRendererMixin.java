package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.effect.NewMoonClient;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** A new-moon night: the moon goes out (fading, as the cutscene shows it). */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
	@ModifyArg(method = "renderSunMoonAndStars", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SkyRenderer;renderMoon(Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V"), index = 1)
	private float ballisticmissiles$moonOut(float alpha) {
		return alpha * (1.0F - NewMoonClient.moonGone());
	}
}
