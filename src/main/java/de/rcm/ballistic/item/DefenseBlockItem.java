package de.rcm.ballistic.item;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * Block item of a defense system, with a short profile in its tooltip: what it is good against,
 * what it can't stop, and its key figures ({@code tooltip.ballisticmissiles.<name>.good/.bad/.info}).
 */
public class DefenseBlockItem extends BlockItem {
	private final String key;

	public DefenseBlockItem(Block block, Properties properties, String name) {
		super(block, properties);
		this.key = "tooltip.ballisticmissiles." + name;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.good").withStyle(ChatFormatting.GREEN)
			.append(Component.translatable(this.key + ".good").withStyle(ChatFormatting.GRAY)));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.bad").withStyle(ChatFormatting.RED)
			.append(Component.translatable(this.key + ".bad").withStyle(ChatFormatting.GRAY)));
		tooltip.accept(Component.translatable(this.key + ".info").withStyle(ChatFormatting.DARK_AQUA));
	}
}
