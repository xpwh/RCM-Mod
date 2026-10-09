package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.drone.DroneClient;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While flying a drone the movement keys are its sticks: your own body stands still. Aiming, you walk slowly. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void ballisticmissiles$droneSticks(CallbackInfo ci) {
		if (DroneClient.isFlying()) {
			this.keyPresses = Input.EMPTY;
			this.moveVector = Vec2.ZERO;
			return;
		}
		// aiming down the sights (AK-47 or RPG-7): a slow, careful walk, no sprinting
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player != null && (de.rcm.ballistic.gun.AkItem.isAiming(player) || de.rcm.ballistic.item.RocketLauncherItem.isAiming(player))) {
			this.moveVector = this.moveVector.scale(0.42F);
			Input k = this.keyPresses;
			this.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), k.shift(), false);
		}
	}
}
