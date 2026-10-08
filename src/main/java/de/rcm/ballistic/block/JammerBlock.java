package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Electronic-warfare jammer. Right-click to switch it on or off. The antenna masts and the shelter
 * are drawn by the block entity renderer.
 */
public class JammerBlock extends Block implements EntityBlock {
	public JammerBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof JammerBlockEntity jammer) {
			jammer.toggle();
			player.displayClientMessage(jammer.status(), true);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (placer != null && level.getBlockEntity(pos) instanceof JammerBlockEntity jammer) {
			jammer.setOwner(placer.getUUID());
		}
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new JammerBlockEntity(pos, state);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (type != ModRegistry.JAMMER_BE) {
			return null;
		}
		return level.isClientSide()
			? (BlockEntityTicker<T>) (BlockEntityTicker<JammerBlockEntity>) JammerBlockEntity::clientTick
			: (BlockEntityTicker<T>) (BlockEntityTicker<JammerBlockEntity>) JammerBlockEntity::serverTick;
	}
}
