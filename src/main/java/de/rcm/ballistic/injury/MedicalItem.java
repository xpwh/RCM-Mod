package de.rcm.ballistic.injury;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * Hold right-click to treat yourself. The first aid kit (four dressings) stops bleeding - all but an
 * artery - dresses the wounds a step better and gives a little health back; the tourniquet stops any
 * bleeding at once, an artery included, and is used up.
 */
public class MedicalItem extends Item {
	private final boolean tourniquet;

	public MedicalItem(boolean tourniquet, Properties properties) {
		super(properties);
		this.tourniquet = tourniquet;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		Wounds w = Injuries.get(player);
		boolean useful = this.tourniquet ? w.bleed() > 0 : w.any() || player.getHealth() < player.getMaxHealth();
		if (!useful) {
			if (!level.isClientSide()) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.no_wounds").withStyle(ChatFormatting.GRAY), true);
			}
			return InteractionResult.FAIL;
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return this.tourniquet ? 30 : 60;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.BRUSH;
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
		if (entity instanceof ServerPlayer p) {
			boolean done = this.tourniquet ? Injuries.tourniquet(p) : Injuries.dress(p);
			if (done && !p.getAbilities().instabuild) {
				if (this.tourniquet) {
					stack.shrink(1);
				} else {
					stack.hurtAndBreak(1, p, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
				}
			}
		}
		return stack;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable(this.tourniquet ? "tooltip.ballisticmissiles.tourniquet" : "tooltip.ballisticmissiles.first_aid_kit")
			.withStyle(ChatFormatting.GRAY));
	}
}
