package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * A karambit: a short claw of a blade curving out of a handle with a ring for the index finger. Quick in the
 * hand (faster than a sword, a bit lighter a cut - see its registration), and you hear it bite. Drawing it,
 * looking it over (the inspect key) and its swing are all animated on the client.
 */
public class KarambitItem extends Item {
	public KarambitItem(Properties properties) {
		super(properties);
	}

	@Override
	public void hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		super.hurtEnemy(stack, target, attacker);
		target.level().playSound(null, target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(), ModRegistry.KNIFE_HIT, SoundSource.PLAYERS, 0.7F,
			0.95F + attacker.getRandom().nextFloat() * 0.12F);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.karambit").withStyle(ChatFormatting.GRAY));
	}
}
