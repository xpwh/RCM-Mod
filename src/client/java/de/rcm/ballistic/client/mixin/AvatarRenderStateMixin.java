package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.render.LostLeg;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements LostLeg {
	@Unique
	private int ballisticmissiles$lost;

	@Override
	public int ballisticmissiles$lostLeg() {
		return this.ballisticmissiles$lost;
	}

	@Override
	public void ballisticmissiles$setLostLeg(int side) {
		this.ballisticmissiles$lost = side;
	}
}
