package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.DetonationManager;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * The radio firing device for C4. Right-click: the thumb flicks the red safety cover up and presses FIRE; the
 * charges it is set to get the signal and go off a moment later. Sneak + right-click: the selector - all charges at
 * once, or one of them at a time (numbered in the order they were placed). It reaches {@value C4ChargeEntity#RADIO_RANGE}
 * blocks; a jammer near a charge keeps the signal from getting through.
 */
public class C4DetonatorItem extends Item {
	/** Ticks from the click to the signal going out: the cover flicked up, the button pressed (C4Client draws it). */
	public static final int FIRE_TICKS = 7;
	public static final int COOLDOWN = 18;

	public C4DetonatorItem(Properties properties) {
		super(properties);
	}

	/** 0: all charges; n: the n-th. */
	public static int mode(ItemStack stack) {
		return stack.getOrDefault(ModRegistry.DETONATOR_MODE, 0);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (player.getCooldowns().isOnCooldown(stack)) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide()) {
			de.rcm.ballistic.ClientHooks.detonatorUsed(player.isShiftKeyDown());
		}
		player.getCooldowns().addCooldown(stack, player.isShiftKeyDown() ? 6 : COOLDOWN);
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer sp)) {
			return InteractionResult.SUCCESS;
		}
		List<C4ChargeEntity> charges = C4ChargeEntity.charges(sp);
		if (player.isShiftKeyDown()) {
			// the selector: all, 1, 2, ... and round again
			int next = mode(stack) + 1;
			if (next > charges.size()) {
				next = 0;
			}
			stack.set(ModRegistry.DETONATOR_MODE, next);
			server.playSound(null, player.getX(), player.getEyeY(), player.getZ(), ModRegistry.C4_SWITCH, SoundSource.PLAYERS, 0.6F, 1.2F);
			player.displayClientMessage(describe(next, charges.size()), true);
			return InteractionResult.SUCCESS;
		}
		server.playSound(null, player.getX(), player.getEyeY(), player.getZ(), ModRegistry.C4_SWITCH, SoundSource.PLAYERS, 0.7F, 0.85F);
		int mode = mode(stack);
		DetonationManager.later(server, FIRE_TICKS - 1, () -> {
			server.playSound(null, player.getX(), player.getEyeY(), player.getZ(), ModRegistry.C4_CLICK, SoundSource.PLAYERS, 0.8F, 1.0F);
			fire(server, sp, mode);
		});
		return InteractionResult.SUCCESS;
	}

	/** FIRE pressed with the selector at {@code mode}: the signal goes out to the charges. */
	private static void fire(ServerLevel level, ServerPlayer player, int mode) {
		List<C4ChargeEntity> charges = C4ChargeEntity.charges(player);
		if (charges.isEmpty()) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.c4_none").withStyle(ChatFormatting.RED), true);
			return;
		}
		List<C4ChargeEntity> targets = mode <= 0 || mode > charges.size() ? charges : List.of(charges.get(mode - 1));
		int fired = 0;
		int jammed = 0;
		for (C4ChargeEntity c : targets) {
			if (de.rcm.ballistic.defense.EmpManager.isJammed(level, c.blockPosition())
				|| de.rcm.ballistic.defense.EmpManager.isJammed(level, player.blockPosition())) {
				jammed++;
				continue;
			}
			// the radio's lag, a little different for each receiver
			c.trigger(3 + level.getRandom().nextInt(3), true);
			fired++;
		}
		if (jammed > 0) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.c4_jammed", jammed).withStyle(ChatFormatting.RED), true);
		} else {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.c4_fired", fired).withStyle(ChatFormatting.GOLD), true);
		}
	}

	private static Component describe(int mode, int count) {
		if (count == 0) {
			return Component.translatable("message.ballisticmissiles.c4_none").withStyle(ChatFormatting.GRAY);
		}
		return mode == 0 ? Component.translatable("message.ballisticmissiles.c4_mode_all", count).withStyle(ChatFormatting.GOLD)
			: Component.translatable("message.ballisticmissiles.c4_mode_one", mode, count).withStyle(ChatFormatting.GOLD);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		int mode = mode(stack);
		tooltip.accept((mode == 0 ? Component.translatable("item.ballisticmissiles.c4_detonator.all")
			: Component.translatable("item.ballisticmissiles.c4_detonator.one", mode)).withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable("item.ballisticmissiles.c4_detonator.tooltip").withStyle(ChatFormatting.GRAY));
	}
}
