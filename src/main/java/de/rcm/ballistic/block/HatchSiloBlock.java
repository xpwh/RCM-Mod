package de.rcm.ballistic.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The second silo variant: no headworks, just a flat armoured hatch flush with the ground - two heavy
 * leaves that swing up on hinges either side of the tube, like a launch-facility closure door. Works
 * exactly like the {@link MissileSiloBlock} (same shaft, same loading and launch); only its look and
 * the walkable lid differ.
 */
public class HatchSiloBlock extends MissileSiloBlock {
	private static final VoxelShape RIM = Block.box(0, 12, 0, 16, 16, 16);
	/** The closed leaves cover the whole 3x3 tube mouth, flush with the ground. */
	private static final VoxelShape LID = Shapes.or(RIM, Block.box(-16, 12, -16, 32, 16, 32));

	public HatchSiloBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return RIM;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(OPEN) ? Shapes.empty() : LID;
	}
}
