package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 30-round AK magazine of 7.62x39 mm: ball rounds, or tracers that burn green for the first 800 m.
 * Remembers how many rounds are left when it comes out of the gun; refill it with gunpowder and
 * copper at a crafting table.
 */
public class AkMagazineItem extends Item {
	private final boolean tracer;

	public AkMagazineItem(Properties properties, boolean tracer) {
		super(properties);
		this.tracer = tracer;
	}

	public boolean tracer() {
		return this.tracer;
	}

	public static int rounds(ItemStack stack) {
		return stack.getOrDefault(ModRegistry.MAG_ROUNDS, AkItem.MAG_CAPACITY);
	}

	public static void setRounds(ItemStack stack, int rounds) {
		stack.set(ModRegistry.MAG_ROUNDS, Mth.clamp(rounds, 0, AkItem.MAG_CAPACITY));
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		return rounds(stack) < AkItem.MAG_CAPACITY;
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		return Math.round(13.0F * rounds(stack) / AkItem.MAG_CAPACITY);
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return this.tracer ? 0x66FF55 : 0xE0C060;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.ak_mag.rounds", rounds(stack), AkItem.MAG_CAPACITY).withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable(this.tracer ? "tooltip.ballisticmissiles.ak_mag.tracer" : "tooltip.ballisticmissiles.ak_mag.ball")
			.withStyle(ChatFormatting.GRAY));
	}
}
