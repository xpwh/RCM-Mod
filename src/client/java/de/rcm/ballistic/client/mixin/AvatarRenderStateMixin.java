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
	@Unique
	private float ballisticmissiles$collapseT = -1.0F;
	@Unique
	private int ballisticmissiles$fallDir;
	@Unique
	private int ballisticmissiles$torsoWounds;
	@Unique
	private int ballisticmissiles$woundSeed;
	@Unique
	private int ballisticmissiles$woundExtra;

	@Override
	public int ballisticmissiles$extra() {
		return this.ballisticmissiles$woundExtra;
	}

	@Override
	public void ballisticmissiles$setExtra(int extra) {
		this.ballisticmissiles$woundExtra = extra;
	}

	@Override
	public int ballisticmissiles$torso() {
		return this.ballisticmissiles$torsoWounds;
	}

	@Override
	public int ballisticmissiles$seed() {
		return this.ballisticmissiles$woundSeed;
	}

	@Override
	public void ballisticmissiles$setTorso(int torso, int seed) {
		this.ballisticmissiles$torsoWounds = torso;
		this.ballisticmissiles$woundSeed = seed;
	}

	@Override
	public float ballisticmissiles$collapse() {
		return this.ballisticmissiles$collapseT;
	}

	@Override
	public int ballisticmissiles$fall() {
		return this.ballisticmissiles$fallDir;
	}

	@Override
	public void ballisticmissiles$setCollapse(float t, int fall) {
		this.ballisticmissiles$collapseT = t;
		this.ballisticmissiles$fallDir = fall;
	}

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
