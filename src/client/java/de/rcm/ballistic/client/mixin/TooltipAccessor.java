package de.rcm.ballistic.client.mixin;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A tooltip's text (vanilla only hands it out split into lines). */
@Mixin(Tooltip.class)
public interface TooltipAccessor {
	@Accessor("message")
	Component ballisticmissiles$message();
}
