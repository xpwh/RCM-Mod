package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.render.LostLeg;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements LostLeg {
	@Unique
	private int ballisticmissiles$lost;
	@Unique
	private int ballisticmissiles$arms;
	@Unique
	private int ballisticmissiles$headWound;

	@Override
	public int ballisticmissiles$lostArm() {
		return this.ballisticmissiles$arms;
	}

	@Override
	public void ballisticmissiles$setLostArm(int side) {
		this.ballisticmissiles$arms = side;
	}

	@Override
	public int ballisticmissiles$head() {
		return this.ballisticmissiles$headWound;
	}

	@Override
	public void ballisticmissiles$setHead(int head) {
		this.ballisticmissiles$headWound = head;
	}

	@Override
	public int ballisticmissiles$lostLeg() {
		return this.ballisticmissiles$lost;
	}

	@Override
	public void ballisticmissiles$setLostLeg(int side) {
		this.ballisticmissiles$lost = side;
	}
}
