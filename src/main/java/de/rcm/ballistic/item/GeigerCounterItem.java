package de.rcm.ballistic.item;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.RadiationManager;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

/** Clicks faster the more radioactive the surroundings are and shows the dose rate. */
public class GeigerCounterItem extends Item {
	public GeigerCounterItem(Properties properties) {
		super(properties);
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot) {
		if (!(entity instanceof ServerPlayer player) || (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND)) {
			return;
		}
		double rate = RadiationManager.doseRate(level, player.position());
		// clicks per tick follow the counting statistics of a real tube
		double expected = Math.min(3.0, 0.03 + rate / 400.0);
		int clicks = 0;
		double p = level.getRandom().nextDouble();
		double acc = Math.exp(-expected);
		double sum = acc;
		while (p > sum && clicks < 4) {
			clicks++;
			acc *= expected / clicks;
			sum += acc;
		}
		for (int i = 0; i < clicks; i++) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.GEIGER_CLICK, SoundSource.PLAYERS, 0.5F,
				0.9F + level.getRandom().nextFloat() * 0.25F);
		}
		if (level.getGameTime() % 10 == 0) {
			ChatFormatting color = rate < 1 ? ChatFormatting.GREEN : rate < 100 ? ChatFormatting.YELLOW : rate < 2000 ? ChatFormatting.GOLD : ChatFormatting.DARK_RED;
			String value = rate >= 1000 ? String.format(Locale.ROOT, "%.2f mSv/h", rate / 1000.0) : String.format(Locale.ROOT, "%.2f µSv/h", rate);
			player.displayClientMessage(Component.literal("☢ " + value).withStyle(color, ChatFormatting.BOLD), true);
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.geiger").withStyle(ChatFormatting.GRAY));
	}
}
