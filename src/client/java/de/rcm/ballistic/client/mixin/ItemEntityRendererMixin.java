package de.rcm.ballistic.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.client.render.GroundPropState;
import de.rcm.ballistic.client.render.GroundProps;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Dropped guns and ammunition lie on the ground ({@link GroundProps}) instead of hovering and spinning. */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin {
	@Inject(
		method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
		at = @At("TAIL")
	)
	private void ballisticmissiles$remember(ItemEntity entity, ItemEntityRenderState state, float partialTick, CallbackInfo ci) {
		((GroundPropState) state).ballisticmissiles$set(entity.getId(), entity.getItem().getItem(), partialTick);
	}

	@Inject(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void ballisticmissiles$lying(ItemEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera,
		CallbackInfo ci) {
		GroundPropState s = (GroundPropState) state;
		if (GroundProps.submit(state, s.ballisticmissiles$id(), s.ballisticmissiles$item(), poseStack, collector, s.ballisticmissiles$partial())) {
			ci.cancel();
		}
	}
}
