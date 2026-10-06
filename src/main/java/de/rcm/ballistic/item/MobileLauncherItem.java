package de.rcm.ballistic.item;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.MobileLauncherEntity;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

/** Places the mobile launcher truck, facing the way the player looks. */
public class MobileLauncherItem extends Item {
	public MobileLauncherItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level)) {
			return InteractionResult.SUCCESS;
		}
		BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
		MobileLauncherEntity truck = ModRegistry.MOBILE_LAUNCHER.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (truck == null) {
			return InteractionResult.FAIL;
		}
		Player player = context.getPlayer();
		float yaw = player != null ? player.getYRot() : 0.0F;
		truck.snapTo(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5), yaw, 0.0F);
		if (!level.noCollision(truck, truck.getBoundingBox())) {
			return InteractionResult.FAIL;
		}
		level.addFreshEntity(truck);
		level.playSound(null, pos, SoundEvents.NETHERITE_BLOCK_PLACE, SoundSource.NEUTRAL, 1.5F, 0.5F);
		if (player == null || !player.getAbilities().instabuild) {
			context.getItemInHand().shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.truck_1").withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.truck_2").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.truck_3").withStyle(ChatFormatting.GRAY));
	}
}
