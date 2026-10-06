package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.effect.ClientEffects;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blast deafness: after a close explosion every other sound in the game is muffled while the ears
 * ring, then hearing slowly returns. The explosion itself and the ringing are exempt.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	@Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$deafness(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		float muffle = ClientEffects.hearing();
		if (muffle >= 0.999F) {
			return;
		}
		Identifier id = sound.getIdentifier();
		if (BallisticMissiles.MOD_ID.equals(id.getNamespace())) {
			String path = id.getPath();
			if (path.startsWith("explosion.") || path.startsWith("nuke.") || path.startsWith("ear.") || path.equals("a10.gun")) {
				return;
			}
		}
		cir.setReturnValue(cir.getReturnValue() * muffle);
	}
}
