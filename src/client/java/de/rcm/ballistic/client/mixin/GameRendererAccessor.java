package de.rcm.ballistic.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the blast shader switch the screen post effect (vanilla only does that for spectating mobs). */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Invoker("setPostEffect")
	void ballisticmissiles$setPostEffect(Identifier id);
}
