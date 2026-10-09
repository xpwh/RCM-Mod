package de.rcm.ballistic.item;

import de.rcm.ballistic.entity.FpvDroneEntity;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * FPV kamikaze drone with its goggles and radio: right-click to arm it and throw it into the air,
 * then fly it through its camera. Left-click sets the warhead off, right-click cuts the link.
 */
public class FpvDroneItem extends Item {
	public FpvDroneItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
			boolean flying = !server.getEntities(de.rcm.ballistic.ModRegistry.FPV_DRONE, d -> d.getPilotId() == player.getId()).isEmpty();
			if (flying) {
				return InteractionResult.FAIL;
			}
			if (FpvDroneEntity.launch(server, serverPlayer) != null && !player.getAbilities().instabuild) {
				player.getItemInHand(hand).shrink(1);
			}
			player.getCooldowns().addCooldown(player.getItemInHand(hand), 20);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fpv_drone.1").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fpv_drone.2").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fpv_drone.3").withStyle(ChatFormatting.GOLD));
	}
}
