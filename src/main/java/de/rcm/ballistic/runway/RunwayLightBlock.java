package de.rcm.ballistic.runway;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A runway light: a squat fixture with a coloured glass dome. White along the edges, green across the
 * threshold you land over, red across the far end. Low and frangible, so a wheel rolls over it.
 */
public class RunwayLightBlock extends Block {
	public static final MapCodec<RunwayLightBlock> CODEC = simpleCodec(RunwayLightBlock::new);
	public static final int WHITE = 0;
	public static final int GREEN = 1;
	public static final int RED = 2;
	public static final IntegerProperty COLOR = IntegerProperty.create("color", 0, 2);
	private static final VoxelShape SHAPE = Block.box(5.0, 0.0, 5.0, 11.0, 4.0, 11.0);

	public RunwayLightBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(COLOR, WHITE));
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(COLOR);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}
}
