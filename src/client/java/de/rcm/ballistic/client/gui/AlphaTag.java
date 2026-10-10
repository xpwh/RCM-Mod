package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.ModRegistry;
import java.util.Set;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * Marks what is still in alpha - the flyable jets, the runway, the AI soldiers' tool: an orange
 * "ALPHA" behind the name and a line saying it is still being worked on and may have bugs.
 */
public final class AlphaTag {
	private static Set<Item> items;

	private AlphaTag() {
	}

	public static void init() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (items == null) {
				items = Set.of(ModRegistry.F35_ITEM, ModRegistry.F22_ITEM, ModRegistry.RUNWAY_ITEM, ModRegistry.RUNWAY_ASPHALT_ITEM,
					ModRegistry.RUNWAY_MARKING_ITEM, ModRegistry.RUNWAY_LIGHT_ITEM, ModRegistry.AI_TOOL);
			}
			if (!items.contains(stack.getItem()) || lines.isEmpty()) {
				return;
			}
			lines.set(0, lines.get(0).copy().append(Component.literal("  ALPHA").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
			lines.add(1, Component.translatable("tooltip.ballisticmissiles.alpha").withStyle(ChatFormatting.GOLD));
		});
	}
}
