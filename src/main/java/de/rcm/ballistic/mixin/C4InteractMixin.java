package de.rcm.ballistic.mixin;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.gun.C4Mount;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Right-clicking something with C4 in hand sticks the charge to it (C4Item, C4Mount) - it does not climb into the
 * jet, trade with the villager or mount the horse. The click passes the thing by, on to the item.
 */
@Mixin(Player.class)
public abstract class C4InteractMixin {
	@Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$c4Sticks(Entity entity, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
		Player self = (Player) (Object) this;
		if (self.getItemInHand(hand).is(ModRegistry.C4) && C4Mount.canHost(entity, self)) {
			cir.setReturnValue(InteractionResult.PASS);
		}
	}
}
