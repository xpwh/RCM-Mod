package de.rcm.ballistic.bunker;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The bunker's NBC air filter. It runs while an emergency generator stands within a few blocks
 * (both light up), and while it runs everyone in the bunker around it is sheltered.
 */
public class AirFilterBlockEntity extends BlockEntity {
	private static final int GENERATOR_RANGE = 6;

	public AirFilterBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.AIR_FILTER_BE, pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, AirFilterBlockEntity filter) {
		if (!(level instanceof ServerLevel server) || server.getGameTime() % 20 != 0) {
			return;
		}
		BlockPos generator = null;
		for (BlockPos p : BlockPos.betweenClosed(pos.offset(-GENERATOR_RANGE, -2, -GENERATOR_RANGE), pos.offset(GENERATOR_RANGE, 2, GENERATOR_RANGE))) {
			if (level.getBlockState(p).is(ModRegistry.EMERGENCY_GENERATOR)) {
				generator = p.immutable();
				break;
			}
		}
		boolean running = generator != null;
		BunkerManager.setRunning(level, pos, running);
		if (state.getValue(AirFilterBlock.LIT) != running) {
			level.setBlock(pos, state.setValue(AirFilterBlock.LIT, running), Block.UPDATE_ALL);
		}
		if (generator != null) {
			BlockState g = level.getBlockState(generator);
			if (!g.getValue(GeneratorBlock.LIT)) {
				level.setBlock(generator, g.setValue(GeneratorBlock.LIT, true), Block.UPDATE_ALL);
			}
			if (server.getGameTime() % 80 == 0) {
				// the plant running: the filter's fan, and the diesel generator feeding it
				level.playSound(null, pos, de.rcm.ballistic.ModRegistry.BUNKER_VENT, SoundSource.BLOCKS, 0.45F, 1.0F);
				level.playSound(null, generator, de.rcm.ballistic.ModRegistry.BUNKER_GENERATOR, SoundSource.BLOCKS, 0.6F, 1.0F);
			}
		}
	}

	@Override
	public void setRemoved() {
		if (this.level != null && !this.level.isClientSide()) {
			BunkerManager.setRunning(this.level, this.worldPosition, false);
		}
		super.setRemoved();
	}
}
