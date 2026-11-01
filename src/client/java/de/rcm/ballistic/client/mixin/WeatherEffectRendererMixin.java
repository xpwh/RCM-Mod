package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.effect.RealRain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.WeatherRenderState;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The realistic rain: drawn drop by drop instead of the blue sheets, and its own splashes where the drops land. */
@Mixin(WeatherEffectRenderer.class)
public class WeatherEffectRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void ballisticmissiles$realRain(MultiBufferSource buffers, Vec3 cam, WeatherRenderState state, CallbackInfo ci) {
		if (RealRain.active() && !state.rainColumns.isEmpty()) {
			RealRain.render(buffers, cam, state);
			// Minecraft's sheets are left out (its snow is still drawn)
			state.rainColumns.clear();
		}
	}

	@Redirect(method = "tickRainParticles", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
	private void ballisticmissiles$splash(ClientLevel level, ParticleOptions options, double x, double y, double z, double dx, double dy, double dz) {
		RealRain.splash(level, options, x, y, z, dx, dy, dz);
	}
}
