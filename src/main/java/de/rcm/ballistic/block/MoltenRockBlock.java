package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Rock molten by a nuclear fireball, still glowing on the crater floor. Burns whatever touches it,
 * hisses and smokes, and over a few minutes cools into black crater glass (faster in the rain).
 */
public class MoltenRockBlock extends Block {
	public MoltenRockBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected boolean isRandomlyTicking(BlockState state) {
		return true;
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (level.isRainingAt(pos.above()) || random.nextInt(4) == 0) {
			level.setBlockAndUpdate(pos, ModRegistry.CRATER_GLASS.defaultBlockState());
			level.playSound(null, pos, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.4F, 0.8F + random.nextFloat() * 0.4F);
		}
	}

	@Override
	public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
		if (entity instanceof LivingEntity && level instanceof ServerLevel server) {
			entity.hurtServer(server, level.damageSources().hotFloor(), 3.0F);
			if (!entity.isSteppingCarefully()) {
				entity.igniteForSeconds(3.0F);
			}
		}
		super.stepOn(level, pos, state, entity);
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		if (!level.getBlockState(pos.above()).isAir()) {
			return;
		}
		double x = pos.getX() + random.nextDouble();
		double y = pos.getY() + 1.02;
		double z = pos.getZ() + random.nextDouble();
		if (random.nextInt(3) == 0) {
			level.addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 0.04, 0.0);
		}
		if (random.nextInt(25) == 0) {
			level.addParticle(ParticleTypes.LAVA, x, y, z, 0.0, 0.0, 0.0);
		}
		if (random.nextInt(120) == 0) {
			level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.LAVA_POP, SoundSource.BLOCKS, 0.3F, 0.8F + random.nextFloat() * 0.3F, false);
		}
	}
}
