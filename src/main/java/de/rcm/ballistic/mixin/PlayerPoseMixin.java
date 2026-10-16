package de.rcm.ballistic.mixin;

import de.rcm.ballistic.injury.Injuries;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player with a leg shot away, or dying, is down on the ground: crawling, or lying there. */
@Mixin(Player.class)
public abstract class PlayerPoseMixin {
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$down(CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (!self.isSpectator() && !self.isPassenger() && Injuries.get(self).down()) {
			self.setPose(Pose.SWIMMING);
			ci.cancel();
		}
	}
}
