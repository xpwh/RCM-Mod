package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.item.RpgClient;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Looking through the RPG-7's sights narrows the view a little. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$aimZoom(boolean firstPerson, float fovEffectScale, CallbackInfoReturnable<Float> cir) {
		float aim = RpgClient.aimProgress(0.0F);
		if (aim > 0.0F) {
			cir.setReturnValue(cir.getReturnValueF() * (1.0F - 0.22F * aim));
		}
	}
}
