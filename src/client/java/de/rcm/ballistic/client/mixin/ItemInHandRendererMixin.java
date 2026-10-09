package de.rcm.ballistic.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.gun.AkArms;
import de.rcm.ballistic.client.gun.AkFirstPerson;
import de.rcm.ballistic.client.item.RpgClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** First-person recoil and reload animation of the RPG-7 and the AK, and the arms holding the AK. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
	/** Flying a drone: the goggles show the drone's camera, not your hands. */
	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$noHandsInGoggles(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swingProgress,
		ItemStack stack, float equipProgress, PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (de.rcm.ballistic.client.drone.DroneClient.isFlying()) {
			ci.cancel();
		}
	}

	@Inject(
		method = "renderArmWithItem",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V"
		)
	)
	private void ballisticmissiles$rpgAnimation(AbstractClientPlayer player, float partialTick, float pitch, InteractionHand hand, float swingProgress,
		ItemStack stack, float equipProgress, PoseStack poseStack, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (stack.is(ModRegistry.ROCKET_LAUNCHER)) {
			Matrix4f base = new Matrix4f(poseStack.last().pose());
			RpgClient.transform(poseStack, partialTick);
			if (hand == InteractionHand.MAIN_HAND && player.getMainArm() == HumanoidArm.RIGHT) {
				RpgClient.renderArms(player, stack, base, poseStack, collector, light, partialTick);
			}
		} else if (stack.is(ModRegistry.GRENADE)) {
			Matrix4f base = new Matrix4f(poseStack.last().pose());
			de.rcm.ballistic.client.gun.GrenadeClient.transform(poseStack, player, partialTick);
			if (hand == InteractionHand.MAIN_HAND && player.getMainArm() == HumanoidArm.RIGHT) {
				de.rcm.ballistic.client.gun.GrenadeClient.renderArms(player, base, poseStack, collector, light, partialTick);
			}
		} else if (stack.is(ModRegistry.AK47)) {
			Matrix4f base = new Matrix4f(poseStack.last().pose());
			AkFirstPerson.transform(poseStack, partialTick, stack);
			if (hand == InteractionHand.MAIN_HAND && player.getMainArm() == HumanoidArm.RIGHT) {
				AkArms.render(player, stack, base, poseStack, collector, light, partialTick);
			}
		}
	}
}
