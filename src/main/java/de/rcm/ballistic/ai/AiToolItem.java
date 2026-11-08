package de.rcm.ballistic.ai;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

/**
 * The AI tool - only from {@code /bmai werkzeug}. While held, the debug view of every soldier near you
 * is on. Right-click the ground: an enemy soldier there (sneaking: a friendly one). Right-click a
 * soldier: his full report in chat - what he knows, sees and has heard.
 */
public class AiToolItem extends Item {
	public AiToolItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
			if (!de.rcm.ballistic.network.ModNetworking.mayConfigure(player)) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.ai_tool_ops").withStyle(ChatFormatting.RED), true);
				return InteractionResult.FAIL;
			}
			Vec3 at = Vec3.atBottomCenterOf(context.getClickedPos().relative(context.getClickedFace()));
			int team = player.isShiftKeyDown() ? SoldierEntity.TEAM_FRIENDLY : SoldierEntity.TEAM_HOSTILE;
			SoldierCommand.spawnAt(level, at, team, player.getYRot() + 180.0F);
			player.displayClientMessage(Component.translatable(team == SoldierEntity.TEAM_HOSTILE ? "message.ballisticmissiles.ai_spawned_hostile"
				: "message.ballisticmissiles.ai_spawned_friendly", 1), true);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
		if (target instanceof SoldierEntity soldier && player instanceof ServerPlayer sp && soldier.level() instanceof ServerLevel level) {
			for (String line : soldier.report(level)) {
				sp.sendSystemMessage(Component.literal(line));
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.ai_tool_1").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.ai_tool_2").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.ai_tool_3").withStyle(ChatFormatting.DARK_GRAY));
	}
}
