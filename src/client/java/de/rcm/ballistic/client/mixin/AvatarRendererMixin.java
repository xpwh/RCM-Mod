package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Players hold the RPG-7 shouldered in both hands, aimed where they look (seen from F5 and by others). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(
		method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
		at = @At("RETURN"),
		cancellable = true
	)
	private static void ballisticmissiles$shoulderRpg(Avatar avatar, ItemStack stack, InteractionHand hand, CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
		if (stack.is(ModRegistry.ROCKET_LAUNCHER) || stack.is(ModRegistry.AK47)) {
			cir.setReturnValue(HumanoidModel.ArmPose.CROSSBOW_HOLD);
		} else if (stack.is(ModRegistry.GRENADE) && avatar.isUsingItem() && avatar.getUsedItemHand() == hand) {
			cir.setReturnValue(HumanoidModel.ArmPose.THROW_TRIDENT); // arm drawn back to throw
		}
	}
}
