package de.rcm.ballistic.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.BufferBuilder;
import de.rcm.ballistic.client.effect.Wind;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Marks the vertices of plants and leaves in their alpha while the mesher builds them (see Wind). */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin implements Wind.Target {
	@Unique
	private int ballisticmissiles$kind;
	@Unique
	private float ballisticmissiles$baseY;

	@Override
	public void ballisticmissiles$sway(int kind, float baseY) {
		this.ballisticmissiles$kind = kind;
		this.ballisticmissiles$baseY = baseY;
	}

	@ModifyVariable(method = "addVertex(FFFIFFIIFFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int ballisticmissiles$mark(int color, @Local(argsOnly = true, ordinal = 1) float y) {
		return this.ballisticmissiles$kind == Wind.NONE ? color : Wind.mark(color, this.ballisticmissiles$kind, y - this.ballisticmissiles$baseY);
	}
}
