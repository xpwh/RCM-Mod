package de.rcm.ballistic.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A thin layer of dust settled on the ground: grey ash from the fires, or radioactive fallout that
 * rained out of a mushroom cloud downwind of ground zero. Fallout makes the Geiger counter crackle
 * and makes you sick if you stay in it; both are blown and washed away over time (rain is quicker).
 */
public class DustLayerBlock extends CarpetBlock {
	private final boolean radioactive;

	public DustLayerBlock(boolean radioactive, Properties properties) {
		super(properties);
		this.radioactive = radioactive;
	}

	public boolean isRadioactive() {
		return this.radioactive;
	}

	@Override
	protected boolean isRandomlyTicking(BlockState state) {
		return true;
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		// fallout lasts about an hour of play on average, ash a little less
		int odds = level.isRainingAt(pos.above()) ? 6 : this.radioactive ? 60 : 40;
		if (random.nextInt(odds) == 0) {
			level.removeBlock(pos, false);
		}
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (random.nextInt(this.radioactive ? 14 : 24) == 0) {
			level.addParticle(this.radioactive ? ParticleTypes.WHITE_ASH : ParticleTypes.ASH,
				pos.getX() + random.nextDouble(), pos.getY() + 0.1, pos.getZ() + random.nextDouble(), 0.0, 0.0, 0.0);
		}
	}

	/** True for both kinds of dust, so layers aren't stacked on each other. */
	public static boolean isDust(BlockState state) {
		return state.getBlock() instanceof DustLayerBlock;
	}
}
