package de.rcm.ballistic.client.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the control panel read a widget's tooltip, to show it as the setting's description. */
@Mixin(AbstractWidget.class)
public interface AbstractWidgetAccessor {
	@Accessor("tooltip")
	WidgetTooltipHolder ballisticmissiles$tooltip();
}
