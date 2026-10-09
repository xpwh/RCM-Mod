package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.render.LostLeg;
import de.rcm.ballistic.injury.Wounds;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A leg shot away below the knee, an arm below the elbow: only the upper half is left (its trouser leg or sleeve goes with it). */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void ballisticmissiles$halfLeg(AvatarRenderState state, CallbackInfo ci) {
		LostLeg gore = (LostLeg) state;
		int lost = gore.ballisticmissiles$lostLeg();
		int arms = gore.ballisticmissiles$lostArm();
		if ((lost == 0 && arms == 0 && gore.ballisticmissiles$head() == 0) || !de.rcm.ballistic.client.ModConfig.gore) {
			return;
		}
		PlayerModel self = (PlayerModel) (Object) this;
		if ((lost & Wounds.LEFT) != 0) {
			self.leftLeg.yScale = 0.5F;
		}
		if ((lost & Wounds.RIGHT) != 0) {
			self.rightLeg.yScale = 0.5F;
		}
		// an arm gone below the elbow: the upper arm is left (the sleeve is part of it)
		if ((arms & Wounds.LEFT) != 0) {
			self.leftArm.yScale = 0.5F;
		}
		if ((arms & Wounds.RIGHT) != 0) {
			self.rightArm.yScale = 0.5F;
		}
		// the skull blown open: the hair/hat layer is torn away with it
		if (gore.ballisticmissiles$head() == Wounds.SHATTERED) {
			self.hat.visible = false;
		}
	}
}
