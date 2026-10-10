package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.item.RpgClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Looking over the sights of the RPG-7 or the AK narrows the view a little. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$aimZoom(boolean firstPerson, float fovEffectScale, CallbackInfoReturnable<Float> cir) {
		float aim = RpgClient.aimProgress(0.0F);
		float ak = de.rcm.ballistic.client.gun.AkClient.aimProgress(0.0F);
		if (aim > 0.0F || ak > 0.0F) {
			// the RPG: over the iron sights a little, then into the 2.7x optical sight
			float scope = de.rcm.ballistic.client.gun.AimOverlay.scope(0.0F);
			float rpg = Mth.lerp(scope, 1.0F - 0.22F * aim, 1.0F / de.rcm.ballistic.client.gun.AimOverlay.SCOPE_ZOOM);
			cir.setReturnValue(cir.getReturnValueF() * rpg * (1.0F - 0.2F * ak));
		}
	}
}
