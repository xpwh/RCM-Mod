package de.rcm.ballistic.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In a thunderstorm's downpour the air thickens: the far land fades into the grey much sooner. */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogMixin {
	@Inject(method = "setupFog", at = @At("TAIL"))
	private void ballisticmissiles$downpour(FogData fog, Camera camera, ClientLevel level, float renderDistance, DeltaTracker delta, CallbackInfo ci) {
		if (!de.rcm.ballistic.client.effect.Storm.enabled()) {
			return;
		}
		float partial = delta.getGameTimeDeltaPartialTick(false);
		float rain = de.rcm.ballistic.client.effect.Storm.rainHere(partial);
		float k = rain * level.getThunderLevel(partial);
		if (k > 0.0F) {
			fog.environmentalEnd = Math.min(fog.environmentalEnd, net.minecraft.util.Mth.lerp(k, fog.environmentalEnd, 110.0F));
			fog.environmentalStart = Math.min(fog.environmentalStart, net.minecraft.util.Mth.lerp(k, fog.environmentalStart, -20.0F));
		}
	}
}
