package de.rcm.ballistic.item;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.block.LaunchPadBlock;
import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.MissileType;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

public class MissileItem extends Item {
	private final MissileType missileType;

	public MissileItem(MissileType missileType, Item.Properties properties) {
		super(properties);
		this.missileType = missileType;
	}

	public MissileType getMissileType() {
		return this.missileType;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		Player player = context.getPlayer();
		if (!(level.getBlockState(pos).getBlock() instanceof LaunchPadBlock)) {
			if (player != null && !level.isClientSide()) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.needs_pad").withStyle(ChatFormatting.YELLOW), true);
			}
			return InteractionResult.FAIL;
		}

		double x = pos.getX() + 0.5;
		double y = pos.getY() + LaunchPadBlock.TOP_HEIGHT;
		double z = pos.getZ() + 0.5;
		AABB occupied = new AABB(x - 0.4, y, z - 0.4, x + 0.4, y + 2, z + 0.4);
		if (!level.getEntitiesOfClass(MissileEntity.class, occupied).isEmpty()) {
			return InteractionResult.FAIL;
		}

		if (level instanceof ServerLevel serverLevel) {
			EntityType<MissileEntity> type = ModRegistry.missileEntity(this.missileType);
			MissileEntity missile = type.create(serverLevel, net.minecraft.world.entity.EntitySpawnReason.SPAWN_ITEM_USE);
			if (missile == null) {
				return InteractionResult.FAIL;
			}
			missile.setPos(x, y, z);
			serverLevel.addFreshEntity(missile);
			serverLevel.playSound(null, x, y, z, ModRegistry.METAL_THUD, SoundSource.BLOCKS, 1.5F, 0.9F);
			serverLevel.playSound(null, x, y, z, ModRegistry.HYDRAULIC_EXTEND, SoundSource.BLOCKS, 1.2F, 1.0F);
			if (player == null || !player.getAbilities().instabuild) {
				context.getItemInHand().shrink(1);
			}
			if (player != null) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.placed").withStyle(ChatFormatting.GRAY), true);
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles." + this.missileType.id).withStyle(this.missileType.isNuclear() ? ChatFormatting.RED : ChatFormatting.GOLD));
		tooltip.accept(Component.translatable(this.missileType.isCruise() ? "tooltip.ballisticmissiles.flight_cruise" : "tooltip.ballisticmissiles.flight_ballistic").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.missile_usage").withStyle(ChatFormatting.GRAY));
	}
}
