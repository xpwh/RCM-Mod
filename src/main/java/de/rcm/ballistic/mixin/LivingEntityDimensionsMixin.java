package de.rcm.ballistic.mixin;

import de.rcm.ballistic.injury.Corpses;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A person's body left lying is no standing man's height: low and broad, so it lies, slides and falls as a body does. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDimensionsMixin {
	@Inject(method = "getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$lyingBody(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		if ((Object) this instanceof Mannequin m && m.hasAttached(Corpses.UNTIL)) {
			cir.setReturnValue(EntityDimensions.scalable(0.8F, 0.4F));
		}
	}
}
