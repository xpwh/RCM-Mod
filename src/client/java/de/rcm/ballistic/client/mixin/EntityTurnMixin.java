package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.fighter.FighterClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Flying a fighter, the mouse turns the pilot's head inside the cockpit rather than his body in the world. */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$headInCockpit(double yRot, double xRot, CallbackInfo ci) {
		if ((Object) this == Minecraft.getInstance().player && FighterClient.isFlying()) {
			FighterClient.look((float) yRot * 0.15F, (float) xRot * 0.15F);
			ci.cancel();
		}
	}
}
