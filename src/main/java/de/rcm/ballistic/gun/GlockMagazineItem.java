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
 * A Glock 19 magazine of 9x19 mm: the flush-fitting 15-rounder, or the long 33-round extended magazine that
 * hangs far out of the grip. Remembers what is left in it when it comes out of the pistol; refill it with
 * gunpowder and copper at a crafting table.
 */
public class GlockMagazineItem extends Item {
	public static final int STANDARD = 0;
	public static final int EXTENDED = 1;
	private final int kind;

	/** @param kind {@link #STANDARD} or {@link #EXTENDED} */
	public GlockMagazineItem(Properties properties, int kind) {
		super(properties);
		this.kind = kind;
	}

	public int kind() {
		return this.kind;
	}

	/** Rounds a magazine of this kind holds. */
	public static int capacity(int kind) {
		return kind == EXTENDED ? GlockItem.EXT_CAPACITY : GlockItem.MAG_CAPACITY;
	}

	/** The magazine item of this kind. */
	public static Item of(int kind) {
		return kind == EXTENDED ? ModRegistry.GLOCK_MAG_EXTENDED : ModRegistry.GLOCK_MAG;
	}

	public static int rounds(ItemStack stack) {
		int cap = stack.getItem() instanceof GlockMagazineItem m ? capacity(m.kind) : GlockItem.MAG_CAPACITY;
		return Mth.clamp(stack.getOrDefault(ModRegistry.MAG_ROUNDS, cap), 0, cap);
	}

	public static void setRounds(ItemStack stack, int rounds) {
		int cap = stack.getItem() instanceof GlockMagazineItem m ? capacity(m.kind) : GlockItem.MAG_CAPACITY;
		stack.set(ModRegistry.MAG_ROUNDS, Mth.clamp(rounds, 0, cap));
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		return rounds(stack) < capacity(this.kind);
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		return Math.round(13.0F * rounds(stack) / capacity(this.kind));
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return 0xE0C060;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.ak_mag.rounds", rounds(stack), capacity(this.kind)).withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable(this.kind == EXTENDED ? "tooltip.ballisticmissiles.glock_mag.extended" : "tooltip.ballisticmissiles.glock_mag.standard")
			.withStyle(ChatFormatting.GRAY));
	}
}
