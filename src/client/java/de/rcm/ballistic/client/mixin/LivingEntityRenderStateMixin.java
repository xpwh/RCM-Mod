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
	private float ballisticmissiles$tumbleX;
	@Unique
	private float ballisticmissiles$tumbleZ;
	@Unique
	private float ballisticmissiles$flail;

	@Override
	public float ballisticmissiles$corpseAge() {
		return this.ballisticmissiles$corpse;
	}

	@Override
	public void ballisticmissiles$setCorpseAge(float ticks) {
		this.ballisticmissiles$corpse = ticks;
	}

	@Override
	public float ballisticmissiles$tumbleX() {
		return this.ballisticmissiles$tumbleX;
	}

	@Override
	public float ballisticmissiles$tumbleZ() {
		return this.ballisticmissiles$tumbleZ;
	}

	@Override
	public float ballisticmissiles$flail() {
		return this.ballisticmissiles$flail;
	}

	@Override
	public void ballisticmissiles$setTumble(float x, float z, float flail) {
		this.ballisticmissiles$tumbleX = x;
		this.ballisticmissiles$tumbleZ = z;
		this.ballisticmissiles$flail = flail;
	}
}
