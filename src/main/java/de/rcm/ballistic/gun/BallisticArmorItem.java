package de.rcm.ballistic.gun;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** A piece of ballistic armour ({@link BallisticArmor}): its tier, shown in the tooltip with what it stops and what it weighs. */
public class BallisticArmorItem extends Item {
	private final BallisticArmor.Tier tier;

	public BallisticArmorItem(Properties properties, BallisticArmor.Tier tier) {
		super(properties);
		this.tier = tier;
	}

	public BallisticArmor.Tier tier() {
		return this.tier;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.armor." + this.tier.key).withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.armor.stops", Math.round(this.tier.stop * 100.0F)).withStyle(ChatFormatting.GOLD));
		if (this.tier.weight < 0.0F) {
			tooltip.accept(Component.translatable("tooltip.ballisticmissiles.armor.weight", Math.round(-this.tier.weight * 100.0F))
				.withStyle(ChatFormatting.DARK_GRAY));
		}
	}
}
