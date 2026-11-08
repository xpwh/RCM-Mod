package de.rcm.ballistic.mixin;

import de.rcm.ballistic.injury.MobCorpses;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A dead creature's body stays lying (see {@link MobCorpses}) instead of going in a puff after a second. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityCorpseMixin {
	@Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
	private void ballisticmissiles$lingerDead(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (!MobCorpses.lingers(self)) {
			return;
		}
		self.deathTime++;
		if (!self.level().isClientSide() && self.deathTime >= MobCorpses.TICKS && !self.isRemoved()) {
			self.level().broadcastEntityEvent(self, (byte) 60);
			self.remove(Entity.RemovalReason.KILLED);
		}
		ci.cancel();
	}
}
