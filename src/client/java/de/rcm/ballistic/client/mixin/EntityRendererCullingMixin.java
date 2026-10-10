package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.injury.CorpseHits;
import de.rcm.ballistic.injury.CorpsePose;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A body lying stretched out reaches well beyond the box it stands in: it is not left undrawn while any of it is in view. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererCullingMixin {
	@Inject(method = "getBoundingBoxForCulling(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/AABB;", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$lyingBody(Entity entity, CallbackInfoReturnable<AABB> cir) {
		if (entity instanceof LivingEntity body && CorpseHits.isCorpse(entity)) {
			cir.setReturnValue(cir.getReturnValue().minmax(CorpsePose.bounds(body).inflate(0.4)));
		}
	}
}
