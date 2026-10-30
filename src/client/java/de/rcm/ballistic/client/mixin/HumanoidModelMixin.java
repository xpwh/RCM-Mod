package de.rcm.ballistic.client.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Your own body seen from inside (FirstPersonBody): the body and the armour on it posed alike, head and arms gone. */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
	private void ballisticmissiles$ownBody(HumanoidRenderState state, CallbackInfo ci) {
		de.rcm.ballistic.client.render.FirstPersonBody.pose((HumanoidModel<?>) (Object) this, state);
	}
}
