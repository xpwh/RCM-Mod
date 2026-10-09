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
	private final int kind;

	public FpvDroneItem(Properties properties, int kind) {
		super(properties);
		this.kind = kind;
	}

	/** Sneak + right-click on the ground: set the drone down there, ready to be flown later. */
	@Override
	public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null || !player.isSecondaryUseActive()) {
			return InteractionResult.PASS;
		}
		if (context.getLevel() instanceof ServerLevel server) {
			net.minecraft.world.phys.Vec3 at = context.getClickLocation();
			if (context.getClickedFace() != net.minecraft.core.Direction.UP) {
				at = at.add(net.minecraft.world.phys.Vec3.atLowerCornerOf(context.getClickedFace().getUnitVec3i()).scale(0.35));
			}
			if (FpvDroneEntity.place(server, player, at, this.kind) != null && !player.getAbilities().instabuild) {
				context.getItemInHand().shrink(1);
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
			if (FpvDroneEntity.flownBy(serverPlayer) != null) {
				return InteractionResult.FAIL;
			}
			if (FpvDroneEntity.launch(server, serverPlayer, this.kind) != null && !player.getAbilities().instabuild) {
				player.getItemInHand(hand).shrink(1);
			}
			player.getCooldowns().addCooldown(player.getItemInHand(hand), 20);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		if (this.kind == FpvDroneEntity.KIND_RACER) {
			tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fpv_racer").withStyle(ChatFormatting.RED));
		}
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fpv_drone.1").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fpv_drone.2").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fpv_drone.4").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable(this.kind == FpvDroneEntity.KIND_RACER ? "tooltip.ballisticmissiles.fpv_racer.3"
			: "tooltip.ballisticmissiles.fpv_drone.3").withStyle(ChatFormatting.GOLD));
	}
}
