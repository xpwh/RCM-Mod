package de.rcm.ballistic.item;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** An item that only explains itself: tooltip lines {@code tooltip.ballisticmissiles.<key>.1..n}. */
public class InfoItem extends Item {
	private final String key;
	private final int lines;

	public InfoItem(Properties properties, String key, int lines) {
		super(properties);
		this.key = key;
		this.lines = lines;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		for (int i = 1; i <= this.lines; i++) {
			tooltip.accept(Component.translatable("tooltip.ballisticmissiles." + this.key + "." + i).withStyle(i == this.lines ? ChatFormatting.GOLD : ChatFormatting.GRAY));
		}
	}
}
