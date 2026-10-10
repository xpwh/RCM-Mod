package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.render.CorpseAge;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LivingEntityRenderState.class)
public abstract class LivingEntityRenderStateMixin implements CorpseAge {
	@Unique
	private float ballisticmissiles$corpse = -1.0F;
	@Unique
	private float[] ballisticmissiles$lying;

	@Override
	public float ballisticmissiles$corpseAge() {
		return this.ballisticmissiles$corpse;
	}

	@Override
	public void ballisticmissiles$setCorpseAge(float ticks) {
		this.ballisticmissiles$corpse = ticks;
	}

	@Override
	public float[] ballisticmissiles$pose() {
		return this.ballisticmissiles$lying;
	}

	@Override
	public void ballisticmissiles$setPose(float[] pose) {
		this.ballisticmissiles$lying = pose;
	}
}
