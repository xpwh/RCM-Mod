package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.item.RpgClient;
import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.item.RocketLauncherItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** With the RPG-7 or the AK in hand, the attack button is the trigger: no punching, no mining. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow
	public LocalPlayer player;

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$rpgTrigger(CallbackInfoReturnable<Boolean> cir) {
		if (this.player != null && this.player.getMainHandItem().getItem() instanceof RocketLauncherItem) {
			RpgClient.trigger(this.player);
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$akTrigger(CallbackInfoReturnable<Boolean> cir) {
		if (this.player != null && this.player.getMainHandItem().getItem() instanceof AkItem) {
			cir.setReturnValue(false); // the trigger is worked from AkClient
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$rpgNoMining(boolean attacking, CallbackInfo ci) {
		if (this.player != null && (this.player.getMainHandItem().getItem() instanceof RocketLauncherItem
			|| this.player.getMainHandItem().getItem() instanceof AkItem)) {
			ci.cancel();
		}
	}
}
