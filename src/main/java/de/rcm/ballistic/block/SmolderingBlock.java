package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Smoldering ground left by a fireball: glowing embers under a crust of ash. It smokes, burns the
 * feet of anyone walking on it, sets anything flammable nearby alight (so the fires spread out from
 * the blast zone), and slowly burns out into scorched earth.
 */
public class SmolderingBlock extends Block {
	public static final IntegerProperty AGE = BlockStateProperties.AGE_3;

	public SmolderingBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(AGE, 0));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(AGE);
	}

	/** Light of the embers: bright when fresh, a dim glow before they go out. */
	public static int light(BlockState state) {
		return 7 - state.getValue(AGE) * 2;
	}

	@Override
	protected boolean isRandomlyTicking(BlockState state) {
		return true;
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		boolean rain = level.isRainingAt(pos.above());
		if (!rain && level.getGameRules().get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) != 0) {
			// flying sparks: a few tries at something flammable within two blocks
			for (int i = 0; i < 3; i++) {
				BlockPos p = pos.offset(random.nextInt(5) - 2, random.nextInt(3), random.nextInt(5) - 2);
				if (level.isLoaded(p) && level.getBlockState(p).isAir() && touchesFlammable(level, p)) {
					level.setBlockAndUpdate(p, BaseFireBlock.getState(level, p));
					break;
				}
			}
			BlockPos above = pos.above();
			if (state.getValue(AGE) == 0 && random.nextInt(3) == 0 && level.getBlockState(above).isAir()) {
				level.setBlockAndUpdate(above, BaseFireBlock.getState(level, above)); // a flame flaring up
			}
		}
		int age = state.getValue(AGE);
		if (rain || random.nextInt(3) == 0) {
			if (age >= 3) {
				level.setBlockAndUpdate(pos, ModRegistry.SCORCHED_EARTH.defaultBlockState());
			} else {
				level.setBlockAndUpdate(pos, state.setValue(AGE, age + 1));
			}
		}
	}

	private static boolean touchesFlammable(Level level, BlockPos p) {
		for (Direction d : Direction.values()) {
			if (level.getBlockState(p.relative(d)).ignitedByLava()) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
		if (!entity.isSteppingCarefully() && entity instanceof LivingEntity && level instanceof ServerLevel server && state.getValue(AGE) < 3) {
			entity.hurtServer(server, level.damageSources().hotFloor(), 1.0F);
		}
		super.stepOn(level, pos, state, entity);
	}

	@Override
	public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
		int age = state.getValue(AGE);
		double x = pos.getX() + random.nextDouble();
		double y = pos.getY() + 1.02;
		double z = pos.getZ() + random.nextDouble();
		if (random.nextInt(4 + age * 2) == 0) {
			level.addParticle(random.nextInt(5) == 0 ? ParticleTypes.CAMPFIRE_COSY_SMOKE : ParticleTypes.SMOKE, x, y, z, 0.0, 0.03, 0.0);
		}
		if (age < 2 && random.nextInt(12) == 0) {
			level.addParticle(ParticleTypes.SMALL_FLAME, x, y, z, 0.0, 0.01, 0.0);
		}
		if (age == 0 && random.nextInt(40) == 0) {
			level.addParticle(ParticleTypes.LAVA, x, y, z, 0.0, 0.0, 0.0);
		}
		if (random.nextInt(90) == 0) {
			level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 0.4F, 0.8F + random.nextFloat() * 0.4F, false);
		}
	}
}
