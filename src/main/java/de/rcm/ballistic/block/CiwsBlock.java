package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Close-in weapon system: a radar-directed gatling gun that shreds missiles and drones that get
 * through the outer air defense. The pedestal is a normal block model, the turret is drawn by the
 * block entity renderer so it can track its target.
 */
public class CiwsBlock extends Block implements EntityBlock {
	private static final VoxelShape SHAPE = Shapes.or(Block.box(0, 0, 0, 16, 4, 16), Block.box(3, 4, 3, 13, 16, 13));

	public CiwsBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CiwsBlockEntity gun) {
			player.displayClientMessage(gun.status(), true);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new CiwsBlockEntity(pos, state);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (type != ModRegistry.CIWS_BE) {
			return null;
		}
		return level.isClientSide()
			? (BlockEntityTicker<T>) (BlockEntityTicker<CiwsBlockEntity>) CiwsBlockEntity::clientTick
			: (BlockEntityTicker<T>) (BlockEntityTicker<CiwsBlockEntity>) CiwsBlockEntity::serverTick;
	}
}
