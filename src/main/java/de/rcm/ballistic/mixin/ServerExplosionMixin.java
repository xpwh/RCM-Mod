package de.rcm.ballistic.mixin;

import de.rcm.ballistic.injury.CorpsePhysics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bodies lying in a blast's reach are thrown, or torn apart (see {@link CorpsePhysics#blast}). */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;
	@Shadow
	@Final
	private Vec3 center;
	@Shadow
	@Final
	private float radius;

	@Inject(method = "hurtEntities", at = @At("HEAD"))
	private void ballisticmissiles$throwBodies(CallbackInfo ci) {
		CorpsePhysics.blast(this.level, this.center, this.radius);
	}
}
