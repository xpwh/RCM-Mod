package de.rcm.ballistic.base;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** The blocks military bases are built from. */
public final class BaseBlocks {
	private BaseBlocks() {
	}

	/** A block turned to face where it was placed, with its own outline (along X when facing north/south). */
	public static class Shaped extends Block {
		public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
		private final VoxelShape alongX;
		private final VoxelShape alongZ;

		public Shaped(Properties properties, VoxelShape alongX, VoxelShape alongZ) {
			super(properties);
			this.alongX = alongX;
			this.alongZ = alongZ;
			this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(FACING);
		}

		@Override
		public BlockState getStateForPlacement(BlockPlaceContext context) {
			return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return state.getValue(FACING).getAxis() == Direction.Axis.Z ? this.alongX : this.alongZ;
		}

		@Override
		protected BlockState rotate(BlockState state, Rotation rotation) {
			return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
		}

		@Override
		protected BlockState mirror(BlockState state, Mirror mirror) {
			return state.rotate(mirror.getRotation(state.getValue(FACING)));
		}
	}

	/** The jersey barrier's profile: a wide foot, sloping in to a narrow top. */
	public static Shaped jersey(Block.Properties properties) {
		VoxelShape x = Shapes.or(Block.box(0, 0, 2, 16, 4, 14), Block.box(0, 4, 4, 16, 9, 12), Block.box(0, 9, 5.5, 16, 15, 10.5));
		VoxelShape z = Shapes.or(Block.box(2, 0, 0, 14, 4, 16), Block.box(4, 4, 0, 12, 9, 16), Block.box(5.5, 9, 0, 10.5, 15, 16));
		return new Shaped(properties, x, z);
	}

	/** Razor wire: it catches and cuts whoever pushes into it. */
	public static class BarbedWire extends Block {
		private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 12, 16);

		public BarbedWire(Properties properties) {
			super(properties);
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return SHAPE;
		}

		@Override
		protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return Shapes.empty();
		}

		@Override
		protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean intersects) {
			entity.makeStuckInBlock(state, new Vec3(0.3, 0.4, 0.3));
			if (level instanceof ServerLevel server && entity instanceof LivingEntity living && server.getGameTime() % 15 == 0) {
				living.hurtServer(server, server.damageSources().cactus(), 1.5F);
			}
		}
	}

	/** Camouflage netting stretched overhead: you walk under and through it; it hangs at the top of its block. */
	public static class CamoNet extends Block {
		private static final VoxelShape SHAPE = Block.box(0, 13, 0, 16, 14, 16);

		public CamoNet(Properties properties) {
			super(properties);
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

	/** A 200-litre drum of fuel: shoot it, burn it or blow it up and it goes up in a fireball. */
	public static class FuelDrum extends Block {
		private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 15, 14);

		public FuelDrum(Properties properties) {
			super(properties);
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return SHAPE;
		}

		@Override
		public void wasExploded(ServerLevel level, BlockPos pos, Explosion explosion) {
			level.getServer().execute(() -> ignite(level, pos, false));
		}

		/** It goes up: a fireball and burning fuel thrown round. */
		public static void ignite(ServerLevel level, BlockPos pos, boolean remove) {
			if (remove) {
				level.removeBlock(pos, false);
			}
			Vec3 c = Vec3.atCenterOf(pos);
			de.rcm.ballistic.explosion.DetonationManager.fuelExplosion(level, c, null, 3.2F);
		}
	}

	/** Lights with a lamp that can be on or off (floodlights, warning beacons) and a block entity that makes it shine. */
	public static class Lamp extends Shaped implements EntityBlock {
		public static final BooleanProperty LIT = BlockStateProperties.LIT;
		private final boolean alarm;

		public Lamp(Properties properties, boolean alarm, VoxelShape shape) {
			super(properties.lightLevel(s -> s.getValue(LIT) ? (alarm ? 9 : 15) : 0), shape, shape);
			this.alarm = alarm;
			this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, !alarm));
		}

		public boolean isAlarm() {
			return this.alarm;
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(FACING, LIT);
		}

		@Override
		public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new LampBlockEntity(pos, state);
		}
	}

	public static class LampBlockEntity extends BlockEntity {
		public LampBlockEntity(BlockPos pos, BlockState state) {
			super(BaseRegistry.LAMP_BE, pos, state);
		}
	}

	/** The base's diesel field generator: it feeds the floodlights. Wreck it and the base goes dark. */
	public static class FieldGenerator extends Shaped {
		public static final BooleanProperty LIT = BlockStateProperties.LIT;

		public FieldGenerator(Properties properties) {
			super(properties.lightLevel(s -> s.getValue(LIT) ? 4 : 0), Block.box(1, 0, 3, 15, 13, 13), Block.box(3, 0, 1, 13, 13, 15));
			this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, true));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(FACING, LIT);
		}

		@Override
		public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
			if (!state.getValue(LIT)) {
				return;
			}
			if (random.nextInt(2) == 0) {
				level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE, pos.getX() + 0.5 + random.nextGaussian() * 0.08, pos.getY() + 0.95,
					pos.getZ() + 0.5 + random.nextGaussian() * 0.08, 0, 0.05, 0);
			}
			if (random.nextInt(40) == 0) {
				level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, net.minecraft.sounds.SoundEvents.MINECART_RIDING,
					net.minecraft.sounds.SoundSource.BLOCKS, 0.25F, 0.55F + random.nextFloat() * 0.1F, false);
			}
		}
	}

	/** The flag in the middle of a base: the base's brain (alarm, power, guards, capture). */
	public static class Flag extends Block implements EntityBlock {
		public static final BooleanProperty CAPTURED = BooleanProperty.create("captured");
		private static final VoxelShape SHAPE = Shapes.or(Block.box(4, 0, 4, 12, 2, 12), Block.box(7, 2, 7, 9, 16, 9));

		public Flag(Properties properties) {
			super(properties);
			this.registerDefaultState(this.stateDefinition.any().setValue(CAPTURED, false));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(CAPTURED);
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return SHAPE;
		}

		@Override
		public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new BaseFlagBlockEntity(pos, state);
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T extends BlockEntity> net.minecraft.world.level.block.entity.@Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
			net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
			if (level.isClientSide() || type != BaseRegistry.FLAG_BE) {
				return null;
			}
			return (net.minecraft.world.level.block.entity.BlockEntityTicker<T>) (net.minecraft.world.level.block.entity.BlockEntityTicker<BaseFlagBlockEntity>) BaseFlagBlockEntity::serverTick;
		}
	}
}
