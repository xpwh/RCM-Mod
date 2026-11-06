package de.rcm.ballistic.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Launch table. The block is the table the missile stands on; the launch complex around it (apron,
 * flame trench, service tower, floodlights) is drawn by its block entity renderer.
 */
public class LaunchPadBlock extends Block implements EntityBlock {
	/** Height of the pad deck, where a missile stands. */
	public static final double TOP_HEIGHT = 6.0 / 16.0;

	private static final VoxelShape SHAPE = Shapes.or(
		Block.box(0, 0, 0, 16, 6, 16),
		Block.box(0, 6, 0, 3, 12, 3),
		Block.box(13, 6, 0, 16, 12, 3),
		Block.box(0, 6, 13, 3, 12, 16),
		Block.box(13, 6, 13, 16, 12, 16)
	);

	public LaunchPadBlock(Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new LaunchPadBlockEntity(pos, state);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}
}
