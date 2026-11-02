package de.rcm.ballistic.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.rcm.ballistic.client.gui.HotbarFade;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The hotbar, the experience bar and everything drawn with them slide down together as {@link HotbarFade} hides
 * the hotbar: the bars go off the bottom of the screen, the hearts, food and armour come to rest where they were.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method = "renderHotbarAndDecorations", at = @At("HEAD"))
	private void ballisticmissiles$slideDown(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
		g.pose().pushMatrix();
		g.pose().translate(0.0F, HotbarFade.hidden() * HotbarFade.DROP);
	}

	@Inject(method = "renderHotbarAndDecorations", at = @At("RETURN"))
	private void ballisticmissiles$slideDownEnd(GuiGraphics g, DeltaTracker deltaTracker, CallbackInfo ci) {
		g.pose().popMatrix();
	}

	@WrapOperation(method = "renderHotbarAndDecorations", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;renderExperienceLevel(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;I)V"))
	private void ballisticmissiles$levelOut(GuiGraphics g, Font font, int level, Operation<Void> original) {
		g.pose().pushMatrix();
		g.pose().translate(0.0F, HotbarFade.hidden() * HotbarFade.LEVEL_EXTRA);
		original.call(g, font, level);
		g.pose().popMatrix();
	}
}
