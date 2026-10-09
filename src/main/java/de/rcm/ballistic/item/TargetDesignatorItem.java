package de.rcm.ballistic.item;

import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.block.LaunchPadBlock;
import de.rcm.ballistic.block.MissileSiloBlock;
import de.rcm.ballistic.launch.RemoteLaunch;
import de.rcm.ballistic.network.ModNetworking.DesignatorActionPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * Right-click: lock the block you are looking at - beyond render distance the line of sight is
 * extended to the horizon.
 * Sneak + right-click: open the targeting computer (map, target memory, players, remote launch).
 * Right-click a launch pad, or sneak + right-click a silo, truck or missile: link it for remote launch.
 * Right-click an idle missile, silo or truck with it: arm it and start the countdown.
 */
public class TargetDesignatorItem extends Item {
	public static final int MAX_SAVED = 10;
	public static final int MAX_LINKS = 12;

	public TargetDesignatorItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			if (player.isShiftKeyDown()) {
				ClientHooks.openTargetScreen.accept(hand);
			} else {
				ClientHooks.designateLookedAtBlock.accept(hand);
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		Block block = level.getBlockState(pos).getBlock();
		Player player = context.getPlayer();
		LauncherLink link = null;
		if (block instanceof LaunchPadBlock) {
			link = LauncherLink.pad(pos);
		} else if (block instanceof MissileSiloBlock && player != null && player.isShiftKeyDown()) {
			link = LauncherLink.silo(pos);
		}
		if (link == null) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			toggleLink(serverPlayer, context.getItemInHand(), link);
		}
		return InteractionResult.SUCCESS;
	}

	public static void toggleLink(ServerPlayer player, ItemStack stack, LauncherLink link) {
		List<LauncherLink> links = new ArrayList<>(stack.getOrDefault(ModRegistry.LINKS, List.of()));
		boolean removed = links.removeIf(l -> l.sameLauncher(link));
		if (!removed) {
			if (links.size() >= MAX_LINKS) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.links_full", MAX_LINKS).withStyle(ChatFormatting.YELLOW), true);
				return;
			}
			links.add(link);
		}
		stack.set(ModRegistry.LINKS, List.copyOf(links));
		player.displayClientMessage(
			Component.translatable(removed ? "message.ballisticmissiles.unlinked" : "message.ballisticmissiles.linked", link.describe(), links.size())
				.withStyle(removed ? ChatFormatting.GRAY : ChatFormatting.AQUA),
			true
		);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.RADIO_CLICK, SoundSource.PLAYERS, 0.6F, removed ? 0.8F : 1.1F);
	}

	/** Called on the server when a client sends a target. */
	public static void applyTarget(ServerPlayer player, InteractionHand hand, BlockPos pos, boolean surface) {
		ItemStack stack = player.getItemInHand(hand);
		if (!stack.is(ModRegistry.TARGET_DESIGNATOR)) {
			return;
		}
		TargetData data = new TargetData(pos, surface);
		stack.set(ModRegistry.TARGET, data);
		int distance = (int) Math.sqrt(player.blockPosition().distSqr(new BlockPos(pos.getX(), player.getBlockY(), pos.getZ())));
		player.displayClientMessage(
			Component.translatable("message.ballisticmissiles.target_set", data.describe(), distance).withStyle(ChatFormatting.RED), true
		);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.TARGET_LOCK, SoundSource.PLAYERS, 1.0F, 1.0F);
	}

	/** Targeting computer actions sent by the client. */
	public static void handleAction(ServerPlayer player, InteractionHand hand, int action, int index, String text) {
		ItemStack stack = player.getItemInHand(hand);
		if (!stack.is(ModRegistry.TARGET_DESIGNATOR)) {
			return;
		}
		TargetData target = stack.get(ModRegistry.TARGET);
		List<SavedTarget> saved = new ArrayList<>(stack.getOrDefault(ModRegistry.SAVED_TARGETS, List.of()));
		List<LauncherLink> links = new ArrayList<>(stack.getOrDefault(ModRegistry.LINKS, List.of()));
		switch (action) {
			case DesignatorActionPayload.SAVE_TARGET -> {
				if (target == null) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.no_target").withStyle(ChatFormatting.YELLOW), true);
					return;
				}
				if (saved.size() >= MAX_SAVED) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.memory_full", MAX_SAVED).withStyle(ChatFormatting.YELLOW), true);
					return;
				}
				String name = text.strip();
				if (name.length() > 24) {
					name = name.substring(0, 24);
				}
				if (name.isEmpty()) {
					name = "#" + (saved.size() + 1);
				}
				saved.add(new SavedTarget(name, target.pos(), target.surface()));
				stack.set(ModRegistry.SAVED_TARGETS, List.copyOf(saved));
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.target_saved", name).withStyle(ChatFormatting.GREEN), true);
			}
			case DesignatorActionPayload.DELETE_SAVED -> {
				if (index >= 0 && index < saved.size()) {
					saved.remove(index);
					stack.set(ModRegistry.SAVED_TARGETS, List.copyOf(saved));
				}
			}
			case DesignatorActionPayload.TARGET_PLAYER -> {
				ServerPlayer other = player.level().getServer().getPlayerList().getPlayerByName(text);
				if (other == null || other.level() != player.level()) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.player_unknown", text).withStyle(ChatFormatting.YELLOW), true);
					return;
				}
				applyTarget(player, hand, other.blockPosition(), false);
			}
			case DesignatorActionPayload.UNLINK -> {
				if (index >= 0 && index < links.size()) {
					links.remove(index);
					stack.set(ModRegistry.LINKS, List.copyOf(links));
				}
			}
			case DesignatorActionPayload.FIRE, DesignatorActionPayload.ABORT -> {
				List<LauncherLink> selected = new ArrayList<>();
				for (int i = 0; i < links.size() && i < 31; i++) {
					if ((index & 1 << i) != 0) {
						selected.add(links.get(i));
					}
				}
				if (selected.isEmpty()) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.no_launchers").withStyle(ChatFormatting.YELLOW), true);
					return;
				}
				if (action == DesignatorActionPayload.ABORT) {
					RemoteLaunch.abort(player, selected);
					return;
				}
				if (target == null) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.no_target").withStyle(ChatFormatting.YELLOW), true);
					return;
				}
				RemoteLaunch.fire(player, selected, target);
			}
			default -> {
			}
		}
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		TargetData data = stack.get(ModRegistry.TARGET);
		if (data != null) {
			tooltip.accept(Component.translatable("tooltip.ballisticmissiles.target", data.describe()).withStyle(ChatFormatting.RED));
		} else {
			tooltip.accept(Component.translatable("tooltip.ballisticmissiles.no_target").withStyle(ChatFormatting.DARK_GRAY));
		}
		int links = stack.getOrDefault(ModRegistry.LINKS, List.of()).size();
		int saved = stack.getOrDefault(ModRegistry.SAVED_TARGETS, List.of()).size();
		if (links > 0 || saved > 0) {
			tooltip.accept(Component.translatable("tooltip.ballisticmissiles.designator_memory", saved, links).withStyle(ChatFormatting.AQUA));
		}
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.designator_1").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.designator_2").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.designator_3").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.designator_4").withStyle(ChatFormatting.GRAY));
	}

	@Override
	public boolean isFoil(ItemStack stack) {
		return stack.has(ModRegistry.TARGET);
	}
}
