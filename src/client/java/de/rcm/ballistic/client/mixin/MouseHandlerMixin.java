package de.rcm.ballistic.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.rcm.ballistic.client.effect.SmoothCamera;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The mouse's turn goes through {@link SmoothCamera}'s short filter before it reaches the player. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void ballisticmissiles$smoothTurn(LocalPlayer player, double dx, double dy, Operation<Void> original) {
		SmoothCamera.turn(player, dx, dy, (x, y) -> original.call(player, x, y));
	}
}
