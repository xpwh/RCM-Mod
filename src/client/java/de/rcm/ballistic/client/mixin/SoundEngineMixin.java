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
import net.minecraft.client.sounds.SoundEngine.PlayResult;

/**
 * Blast deafness: after a close explosion every other sound in the game is muffled while the ears
 * ring, then hearing slowly returns. The explosion itself and the ringing are exempt.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
	/** The dragon's roar echoing off the void (EndFx). */
	@Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"))
	private void ballisticmissiles$echo(SoundInstance sound, CallbackInfoReturnable<PlayResult> cir) {
		de.rcm.ballistic.client.effect.EndFx.played(sound);
	}

	@Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$deafness(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		// Minecraft's own thunder, heard everywhere at once: the storm plays its own, when sound would arrive
		if (de.rcm.ballistic.client.effect.Storm.enabled() && "minecraft".equals(sound.getIdentifier().getNamespace())
			&& sound.getIdentifier().getPath().startsWith("entity.lightning_bolt.thunder")) {
			cir.setReturnValue(0.0F);
			return;
		}
		float muffle = ClientEffects.hearing() * ClientEffects.silence();
		if (muffle >= 0.999F) {
			return;
		}
		Identifier id = sound.getIdentifier();
		if (BallisticMissiles.MOD_ID.equals(id.getNamespace())) {
			String path = id.getPath();
			if (path.startsWith("explosion.") || path.startsWith("flashbang.") || path.startsWith("nuke.") || path.startsWith("ear.") || path.startsWith("a10.gun")) {
				return;
			}
		}
		cir.setReturnValue(cir.getReturnValue() * muffle);
	}
}
