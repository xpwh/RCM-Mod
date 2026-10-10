package de.rcm.ballistic.item;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.DestroyerEntity;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Puts a destroyer to sea where you aim at open water, bow pointing the way you look. */
public class DestroyerItem extends Item {
	public DestroyerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
		if (hit.getType() != HitResult.Type.BLOCK || level.getFluidState(hit.getBlockPos()).isEmpty()) {
			if (!level.isClientSide()) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.ship_needs_water").withStyle(ChatFormatting.YELLOW), true);
			}
			return InteractionResult.FAIL;
		}
		if (level instanceof ServerLevel server) {
			DestroyerEntity ship = ModRegistry.DESTROYER.create(server, EntitySpawnReason.SPAWN_ITEM_USE);
			if (ship == null) {
				return InteractionResult.FAIL;
			}
			var p = hit.getLocation();
			ship.setPos(p.x, hit.getBlockPos().getY() + 0.3, p.z);
			ship.setHeading(-player.getYRot() * Mth.DEG_TO_RAD);
			ship.setOwner(player.getUUID());
			server.addFreshEntity(ship);
			if (!player.getAbilities().instabuild) {
				player.getItemInHand(hand).shrink(1);
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.destroyer.1").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.destroyer.2").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.destroyer.3").withStyle(ChatFormatting.DARK_AQUA));
	}
}
