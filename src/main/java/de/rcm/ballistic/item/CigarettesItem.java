package de.rcm.ballistic.item;

import de.rcm.ballistic.ModRegistry;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * A pack of twenty. Hold use to light one: the pack comes up, the thumb flips the lid, a cigarette is drawn
 * out and put between the lips, the pack goes away and the Zippo comes out - flicked open and struck in one
 * movement - the flame held to the tip, the first drag, the lid snapped shut. Let go before the end and you
 * have put it back. You need a lighter somewhere on you. A lit cigarette calms the nerves (see the stress
 * on the client) and burns for about three quarters of a minute.
 */
public class CigarettesItem extends Item {
	/** The whole lighting-up, in ticks; the client's animation runs on the same clock. */
	public static final int LIGHT_TIME = 72;
	public static final int T_LID = 8;
	public static final int T_PULL = 12;
	public static final int T_STRIKE = 40;
	public static final int T_DRAG = 46;
	public static final int T_CLOSE = 58;

	public CigarettesItem(Properties properties) {
		super(properties);
	}

	public static boolean hasLighter(Player player) {
		if (player.getAbilities().instabuild) {
			return true;
		}
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (inv.getItem(i).is(ModRegistry.LIGHTER)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!hasLighter(player)) {
			if (!level.isClientSide()) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.cigarettes.no_lighter").withStyle(ChatFormatting.GRAY), true);
			}
			return InteractionResult.FAIL;
		}
		if (player instanceof ServerPlayer sp && Smoking.isSmoking(sp)) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.cigarettes.already").withStyle(ChatFormatting.GRAY), true);
			return InteractionResult.FAIL;
		}
		if (player.isUnderWater()) {
			return InteractionResult.FAIL;
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return LIGHT_TIME;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.NONE;
	}

	@Override
	public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
		if (level.isClientSide()) {
			return;
		}
		int t = LIGHT_TIME - remaining;
		SoundEvent sound = switch (t) {
			case T_LID -> ModRegistry.SMOKE_PACK_OPEN;
			case T_PULL -> ModRegistry.SMOKE_PACK_PULL;
			case T_STRIKE -> ModRegistry.SMOKE_ZIPPO_LIGHT;
			case T_DRAG -> ModRegistry.SMOKE_DRAG;
			case T_CLOSE -> ModRegistry.SMOKE_ZIPPO_CLOSE;
			default -> null;
		};
		if (sound != null) {
			float volume = sound == ModRegistry.SMOKE_DRAG ? 0.5F : 0.7F;
			level.playSound(null, entity.getX(), entity.getEyeY() - 0.2, entity.getZ(), sound, SoundSource.PLAYERS, volume, 0.95F + entity.getRandom().nextFloat() * 0.1F);
		}
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
		if (entity instanceof ServerPlayer p) {
			Smoking.start(p, Smoking.DURATION);
			if (!p.getAbilities().instabuild) {
				stack.hurtAndBreak(1, p, p.getUsedItemHand() == InteractionHand.OFF_HAND ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND);
			}
		}
		return stack;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		int left = stack.getMaxDamage() - stack.getDamageValue();
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.cigarettes", left).withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.cigarettes.use").withStyle(ChatFormatting.DARK_GRAY));
	}
}
