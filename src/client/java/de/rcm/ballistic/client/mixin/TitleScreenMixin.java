package de.rcm.ballistic.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.rcm.ballistic.client.gui.TitleMenu;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The mod's own title screen: see {@link TitleMenu}. The buttons and what they do stay vanilla. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/TitleScreen;renderPanorama(Lnet/minecraft/client/gui/GuiGraphics;F)V"))
	private void ballisticmissiles$background(TitleScreen screen, GuiGraphics g, float partialTick, Operation<Void> original) {
		TitleMenu.background(g, screen.width, screen.height);
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/LogoRenderer;renderLogo(Lnet/minecraft/client/gui/GuiGraphics;IF)V"))
	private void ballisticmissiles$logo(LogoRenderer logo, GuiGraphics g, int width, float alpha, Operation<Void> original) {
		TitleMenu.logo(g, width, alpha);
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/SplashRenderer;render(Lnet/minecraft/client/gui/GuiGraphics;ILnet/minecraft/client/gui/Font;F)V"))
	private void ballisticmissiles$splash(SplashRenderer splash, GuiGraphics g, int width, Font font, float alpha, Operation<Void> original) {
		TitleMenu.call(g, width, alpha);
	}
}
