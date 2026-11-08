package de.rcm.ballistic.base;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The city's lights. They keep their own hours, looked up on their random ticks (every minute or so each), so a
 * block of flats lights up window by window in the evening and goes dark the same way; and they hang on the
 * grid ({@link CityPower}) - when it fails they stutter and die, a few emergency lights coming on red, and they
 * stutter back on when the power returns.
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

	/** Sets the block to {@code want} if it is not already. */
	static void set(ServerLevel level, BlockPos pos, BlockState now, BlockState want) {
		if (now != want) {
			level.setBlock(pos, want, Block.UPDATE_ALL);
		}
	}

	// ------------------------------------------------------------------ room lights

	/** A ceiling light: a bright panel when on, a pale diffuser when off, red when it is an emergency light in a power cut. */
	public static class RoomLight extends Block implements CityPower.Powered {
		public static final BooleanProperty LIT = BlockStateProperties.LIT;
		public static final BooleanProperty EMERGENCY = BooleanProperty.create("emergency");

		public RoomLight(Properties properties) {
			super(properties.lightLevel(s -> s.getValue(LIT) ? 15 : s.getValue(EMERGENCY) ? 6 : 0)
				.emissiveRendering((s, g, p) -> s.getValue(LIT) || s.getValue(EMERGENCY)).randomTicks());
			this.registerDefaultState(this.stateDefinition.any().setValue(LIT, true).setValue(EMERGENCY, false));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(LIT, EMERGENCY);
		}

		BlockState settled(ServerLevel level, BlockPos pos, BlockState state) {
			if (CityPower.outage(level, pos) != null) {
				// one light in fifteen runs on a battery: the exit signs, the stairwells
				return state.setValue(LIT, false).setValue(EMERGENCY, hash(pos, 0L, 4) < 0.07F);
			}
			return state.setValue(LIT, roomLit(level, pos)).setValue(EMERGENCY, false);
		}

		@Override
		protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			set(level, pos, state, this.settled(level, pos, state));
		}

		@Override
		protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			boolean lit = state.getValue(LIT);
			if (CityPower.flicker(level, pos, random, lit, () -> level.setBlock(pos, state.setValue(LIT, !lit).setValue(EMERGENCY, false), Block.UPDATE_ALL))) {
				return;
			}
			set(level, pos, state, this.settled(level, pos, state));
		}
	}

	// ------------------------------------------------------------------ neon

	/** A neon sign: lit through the night in one of six colours, now and then a flicker; dark in a power cut. */
	public static class Neon extends Block implements CityPower.Powered {
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

		static boolean wanted(ServerLevel level, BlockPos pos) {
			return night(level) && CityPower.outage(level, pos) == null;
		}

		@Override
		protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			boolean want = wanted(level, pos);
			if (want && state.getValue(LIT) && random.nextInt(10) == 0) {
				// a tube flickers: out for a moment, back on
				level.setBlock(pos, state.setValue(LIT, false), Block.UPDATE_ALL);
				level.scheduleTick(pos, this, 2 + random.nextInt(8));
				return;
			}
			set(level, pos, state, state.setValue(LIT, want));
		}

		@Override
		protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			boolean lit = state.getValue(LIT);
			if (CityPower.flicker(level, pos, random, lit, () -> level.setBlock(pos, state.setValue(LIT, !lit), Block.UPDATE_ALL))) {
				return;
			}
			set(level, pos, state, state.setValue(LIT, wanted(level, pos)));
		}
	}

	// ------------------------------------------------------------------ street lamps

	/**
	 * A street lamp's head on top of its post: lit from dusk to dawn, throwing its cone of light down onto the
	 * street (drawn by the client); the old sodium lamps buzz and flicker now and then; dark in a power cut.
	 */
	public static class StreetLamp extends BaseEntityBlock implements CityPower.Powered {
		public static final MapCodec<StreetLamp> CODEC = simpleCodec(StreetLamp::new);
		public static final BooleanProperty LIT = BlockStateProperties.LIT;
		private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 14, 13);

		public StreetLamp(Properties properties) {
			super(properties.lightLevel(s -> s.getValue(LIT) ? 15 : 0).emissiveRendering((s, g, p) -> s.getValue(LIT)).randomTicks());
			this.registerDefaultState(this.stateDefinition.any().setValue(LIT, true));
		}

		@Override
		protected MapCodec<? extends BaseEntityBlock> codec() {
			return CODEC;
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(LIT);
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return SHAPE;
		}

		@Override
		protected RenderShape getRenderShape(BlockState state) {
			return RenderShape.MODEL;
		}

		@Override
		public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new Glow(BaseRegistry.STREET_LAMP_BE, pos, state);
		}

		@Override
		protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			boolean want = Neon.wanted(level, pos);
			if (want && state.getValue(LIT) && random.nextInt(25) == 0) {
				level.setBlock(pos, state.setValue(LIT, false), Block.UPDATE_ALL);
				level.scheduleTick(pos, this, 2 + random.nextInt(6));
				return;
			}
			set(level, pos, state, state.setValue(LIT, want));
		}

		@Override
		protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			boolean lit = state.getValue(LIT);
			if (CityPower.flicker(level, pos, random, lit, () -> level.setBlock(pos, state.setValue(LIT, !lit), Block.UPDATE_ALL))) {
				return;
			}
			set(level, pos, state, state.setValue(LIT, Neon.wanted(level, pos)));
		}
	}

	/** The block entity of a lamp that throws a cone of light (the client draws it). */
	public static class Glow extends BlockEntity {
		public Glow(BlockEntityType<?> type, BlockPos pos, BlockState state) {
			super(type, pos, state);
		}
	}

	// ------------------------------------------------------------------ traffic lights

	/**
	 * A traffic light's head: signals on all four faces, the ones facing east and west for the traffic along x,
	 * north and south for the traffic along z. The two run against each other on one clock (the game's time),
	 * so every crossing in the city keeps the same rhythm: green, amber, red. In a power cut it goes dark.
	 */
	public static class TrafficLight extends Block implements CityPower.Powered {
		/** 0: x green, z red; 1: x amber, z red; 2: x red, z green; 3: x red, z amber; 4: dark. */
		public static final IntegerProperty PHASE = IntegerProperty.create("phase", 0, 4);
		static final int[] ENDS = {250, 300, 550, 600};
		static final int CYCLE = 600;

		public TrafficLight(Properties properties) {
			super(properties.lightLevel(s -> s.getValue(PHASE) < 4 ? 5 : 0).emissiveRendering((s, g, p) -> s.getValue(PHASE) < 4).randomTicks());
			this.registerDefaultState(this.stateDefinition.any().setValue(PHASE, 0));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(PHASE);
		}

		@Override
		protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
			if (!level.isClientSide()) {
				level.scheduleTick(pos, this, 1);
			}
		}

		@Override
		protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			// in case its scheduled tick was lost: back on the clock
			this.tick(state, level, pos, random);
		}

		@Override
		protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			boolean dark = state.getValue(PHASE) == 4;
			if (CityPower.flicker(level, pos, random, !dark,
				() -> level.setBlock(pos, state.setValue(PHASE, dark ? phase(level)[0] : 4), Block.UPDATE_ALL))) {
				return;
			}
			if (CityPower.outage(level, pos) != null) {
				set(level, pos, state, state.setValue(PHASE, 4));
				return;
			}
			int[] p = phase(level);
			set(level, pos, state, state.setValue(PHASE, p[0]));
			level.scheduleTick(pos, this, Math.max(1, p[1]));
		}

		/** The phase now and the ticks until it changes. */
		static int[] phase(Level level) {
			int t = (int) Math.floorMod(level.getGameTime(), (long) CYCLE);
			for (int i = 0; i < ENDS.length; i++) {
				if (t < ENDS[i]) {
					return new int[] {i, ENDS[i] - t};
				}
			}
			return new int[] {0, 1};
		}
	}

	// ------------------------------------------------------------------ car headlights

	/**
	 * A car's front with its headlights, facing the way the car points: on at night in the cars whose engine
	 * runs (half of them), throwing two beams down the road (drawn by the client). Cars have their own battery:
	 * a power cut does not touch them.
	 */
	public static class Headlight extends BaseEntityBlock {
		public static final MapCodec<Headlight> CODEC = simpleCodec(Headlight::new);
		public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
		public static final BooleanProperty LIT = BlockStateProperties.LIT;
		/** Whether the car's engine runs (set when the city is built): only then are its lights on at night. */
		public static final BooleanProperty RUNNING = BooleanProperty.create("running");

		public Headlight(Properties properties) {
			super(properties.lightLevel(s -> s.getValue(LIT) ? 9 : 0).emissiveRendering((s, g, p) -> s.getValue(LIT)).randomTicks());
			this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false).setValue(RUNNING, false));
		}

		@Override
		protected MapCodec<? extends BaseEntityBlock> codec() {
			return CODEC;
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(FACING, LIT, RUNNING);
		}

		@Override
		public BlockState getStateForPlacement(BlockPlaceContext context) {
			return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
		}

		@Override
		protected RenderShape getRenderShape(BlockState state) {
			return RenderShape.MODEL;
		}

		@Override
		public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new Glow(BaseRegistry.HEADLIGHT_BE, pos, state);
		}

		@Override
		protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			set(level, pos, state, state.setValue(LIT, state.getValue(RUNNING) && night(level)));
		}
	}
}
