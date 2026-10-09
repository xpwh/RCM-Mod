package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.render.LostLeg;
import de.rcm.ballistic.injury.Wounds;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A leg shot away below the knee: only the thigh is left (the trouser leg is part of it and goes with it). */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void ballisticmissiles$halfLeg(AvatarRenderState state, CallbackInfo ci) {
		int lost = ((LostLeg) state).ballisticmissiles$lostLeg();
		if (lost == 0 || !de.rcm.ballistic.client.ModConfig.gore) {
			return;
		}
		PlayerModel self = (PlayerModel) (Object) this;
		if ((lost & Wounds.LEFT) != 0) {
			self.leftLeg.yScale = 0.5F;
		}
		if ((lost & Wounds.RIGHT) != 0) {
			self.rightLeg.yScale = 0.5F;
		}
	}
}
