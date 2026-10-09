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
	private void ballisticmissiles$shotgunTrigger(CallbackInfoReturnable<Boolean> cir) {
		if (this.player != null && this.player.getMainHandItem().getItem() instanceof de.rcm.ballistic.gun.ShotgunItem) {
			de.rcm.ballistic.client.gun.ShotgunClient.trigger(this.player);
			cir.setReturnValue(false);
		}
	}

	/** Bleeding out on the ground: too weak to fight, to use anything. */
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$downNoAttack(CallbackInfoReturnable<Boolean> cir) {
		if (this.player != null && de.rcm.ballistic.injury.Injuries.get(this.player).incapacitated()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$downNoUse(CallbackInfo ci) {
		if (this.player != null && de.rcm.ballistic.injury.Injuries.get(this.player).incapacitated()) {
			ci.cancel();
		}
	}

	/** Dead: no death screen - you are carried straight back to your spawn, as after a blackout. */
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$noDeathScreen(net.minecraft.client.gui.screens.Screen screen, CallbackInfo ci) {
		if (screen instanceof net.minecraft.client.gui.screens.DeathScreen && this.player != null && this.player.level() != null
			&& !this.player.level().getLevelData().isHardcore()) {
			de.rcm.ballistic.client.gui.DyingOverlay.died();
			this.player.respawn();
			ci.cancel();
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$akTrigger(CallbackInfoReturnable<Boolean> cir) {
		if (this.player != null && this.player.getMainHandItem().getItem() instanceof AkItem) {
			cir.setReturnValue(false); // the trigger is worked from AkClient
		}
	}

	/** Left-click with the pin out: cook the grenade. */
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$grenadeCook(CallbackInfoReturnable<Boolean> cir) {
		if (this.player != null && this.player.isUsingItem() && this.player.getUseItem().getItem() instanceof de.rcm.ballistic.gun.GrenadeItem) {
			de.rcm.ballistic.client.gun.GrenadeClient.cook();
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
				new de.rcm.ballistic.network.ModNetworking.GunInputPayload(de.rcm.ballistic.network.ModNetworking.GunInputPayload.GRENADE_COOK));
			cir.setReturnValue(false);
		}
	}

	/** In a fighter's cockpit the mouse buttons fire its weapons: no punching, mining or using items. */
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$fighterNoAttack(CallbackInfoReturnable<Boolean> cir) {
		if (de.rcm.ballistic.client.fighter.FighterClient.isFlying()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$fighterNoUse(CallbackInfo ci) {
		if (de.rcm.ballistic.client.fighter.FighterClient.isFlying()) {
			ci.cancel();
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$fighterNoMining(boolean attacking, CallbackInfo ci) {
		if (de.rcm.ballistic.client.fighter.FighterClient.isFlying()) {
			ci.cancel();
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$rpgNoMining(boolean attacking, CallbackInfo ci) {
		if (this.player != null && (this.player.getMainHandItem().getItem() instanceof RocketLauncherItem
			|| this.player.getMainHandItem().getItem() instanceof AkItem
			|| this.player.getMainHandItem().getItem() instanceof de.rcm.ballistic.gun.ShotgunItem)) {
			ci.cancel();
		}
	}
}
