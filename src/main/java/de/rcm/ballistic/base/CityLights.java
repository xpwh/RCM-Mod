package de.rcm.ballistic.base;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * The city's lights that keep their own hours. They look at the time on their random ticks (every minute or
 * so each), so a block of flats lights up window by window in the evening and goes dark the same way.
 */
public final class CityLights {
	private CityLights() {
	}

	/** A number in [0, 1) fixed for a spot, a night and a purpose. */
	static float hash(BlockPos pos, long night, int salt) {
		long h = pos.asLong() * 0x9E3779B97F4A7C15L ^ night * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
		h ^= h >>> 33;
		h *= 0xff51afd7ed558ccdL;
		h ^= h >>> 33;
		h *= 0xc4ceb9fe1a85ec53L;
		h ^= h >>> 33;
		return (h >>> 40) / (float) (1 << 24);
	}

	/** Dark outside (sunset to sunrise, roughly). */
	public static boolean night(Level level) {
		long t = level.getDayTime() % 24000L;
		return t >= 12300L && t < 23300L;
	}

	/**
	 * Whether a room's ceiling light is on now: some rooms are lit day and night (offices that never sleep),
	 * some stay dark; the rest come on at some point of the evening and go off at some point of the night or
	 * in the early morning - a different time every night.
	 */
	public static boolean roomLit(Level level, BlockPos pos) {
		long time = level.getDayTime();
		long night = Math.floorDiv(time + 6000L, 24000L);
		long t = Math.floorMod(time, 24000L);
		float kind = hash(pos, 0L, 1);
		if (kind < 0.12F) {
			return true;
		}
		if (kind > 0.86F) {
			return false;
		}
		float on = 12000.0F + hash(pos, night, 2) * 3600.0F;
		float off = 18000.0F + hash(pos, night, 3) * 7500.0F;
		float tt = t < 6000L ? t + 24000L : t;
		return tt >= on && tt < off;
	}

	/** A ceiling light: a bright panel when on, a pale diffuser when off. */
	public static class RoomLight extends Block {
		public static final BooleanProperty LIT = BlockStateProperties.LIT;

		public RoomLight(Properties properties) {
			super(properties.lightLevel(s -> s.getValue(LIT) ? 15 : 0).randomTicks());
			this.registerDefaultState(this.stateDefinition.any().setValue(LIT, true));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(LIT);
		}

		@Override
		protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			boolean want = roomLit(level, pos);
			if (want != state.getValue(LIT)) {
				level.setBlock(pos, state.setValue(LIT, want), Block.UPDATE_ALL);
			}
		}
	}

	/** A neon sign: lit through the night in one of six colours, now and then a flicker. */
	public static class Neon extends Block {
		public static final BooleanProperty LIT = BlockStateProperties.LIT;
		public static final IntegerProperty COLOR = IntegerProperty.create("color", 0, 5);

		public Neon(Properties properties) {
			super(properties.lightLevel(s -> s.getValue(LIT) ? 11 : 0).emissiveRendering((s, g, p) -> s.getValue(LIT)).randomTicks());
			this.registerDefaultState(this.stateDefinition.any().setValue(LIT, true).setValue(COLOR, 0));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(LIT, COLOR);
		}

		@Override
		protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			boolean want = night(level);
			if (want && state.getValue(LIT) && random.nextInt(10) == 0) {
				// a tube flickers: out for a moment, back on
				level.setBlock(pos, state.setValue(LIT, false), Block.UPDATE_ALL);
				level.scheduleTick(pos, this, 2 + random.nextInt(8));
				return;
			}
			if (want != state.getValue(LIT)) {
				level.setBlock(pos, state.setValue(LIT, want), Block.UPDATE_ALL);
			}
		}

		@Override
		protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			if (night(level) && !state.getValue(LIT)) {
				level.setBlock(pos, state.setValue(LIT, true), Block.UPDATE_ALL);
			}
		}
	}
}
