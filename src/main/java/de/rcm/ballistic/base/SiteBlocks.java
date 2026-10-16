package de.rcm.ballistic.base;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The blocks the newer sites need: buried mines, keycard readers on the security doors, the lab's lift. */
public final class SiteBlocks {
	private SiteBlocks() {
	}

	/**
	 * An anti-personnel mine, pressed into the ground with only its fuse showing. Step on it and it clicks;
	 * a moment later it goes off. Creative players can walk over them (to lay a field), and a blast nearby
	 * sets them off too.
	 */
	public static class Landmine extends Block {
		public static final BooleanProperty TRIGGERED = BlockStateProperties.TRIGGERED;
		private static final VoxelShape SHAPE = Block.box(4, 0, 4, 12, 1.5, 12);

		public Landmine(Properties properties) {
			super(properties);
			this.registerDefaultState(this.stateDefinition.any().setValue(TRIGGERED, false));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(TRIGGERED);
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
			if (!(level instanceof ServerLevel server) || state.getValue(TRIGGERED)) {
				return;
			}
			if (!(entity instanceof LivingEntity || entity instanceof VehicleEntity) || entity instanceof Player p && (p.isCreative() || p.isSpectator())) {
				return;
			}
			this.arm(server, pos, state, 9);
		}

		private void arm(ServerLevel level, BlockPos pos, BlockState state, int delay) {
			level.setBlock(pos, state.setValue(TRIGGERED, true), Block.UPDATE_CLIENTS);
			level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_ON, SoundSource.BLOCKS, 0.7F, 0.55F);
			level.scheduleTick(pos, this, delay);
		}

		@Override
		protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			level.removeBlock(pos, false);
			de.rcm.ballistic.explosion.DetonationManager.detonateGrenade(level, Vec3.atBottomCenterOf(pos).add(0, 0.2, 0), null);
		}

		@Override
		public void wasExploded(ServerLevel level, BlockPos pos, Explosion explosion) {
			// a blast in the field sets off its neighbours - but not all at once
			level.setBlock(pos, this.defaultBlockState().setValue(TRIGGERED, true), Block.UPDATE_CLIENTS);
			level.scheduleTick(pos, this, 4 + level.getRandom().nextInt(10));
		}
	}

	/**
	 * A keycard reader beside a security door. Swipe a keycard (use it on the reader) and every iron door
	 * within two blocks opens for a few seconds, then closes and locks again. Without a card: a red light and
	 * a buzz.
	 */
	public static class CardReader extends BaseBlocks.Shaped {
		public CardReader(Properties properties) {
			super(properties, Block.box(5, 3, 5, 11, 13, 11), Block.box(5, 3, 5, 11, 13, 11));
		}

		@Override
		protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
			if (!stack.is(BaseRegistry.KEYCARD)) {
				return InteractionResult.TRY_WITH_EMPTY_HAND;
			}
			if (level instanceof ServerLevel server) {
				server.playSound(null, pos, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 0.6F, 1.9F);
				doors(server, pos, player, true);
				server.scheduleTick(pos, this, 70);
			}
			return InteractionResult.SUCCESS;
		}

		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			if (level instanceof ServerLevel server) {
				server.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.8F, 0.6F);
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.card_reader.denied").withStyle(ChatFormatting.RED), true);
			}
			return InteractionResult.SUCCESS;
		}

		@Override
		protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			doors(level, pos, null, false);
		}

		private static void doors(ServerLevel level, BlockPos pos, Entity who, boolean open) {
			for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 1, 2))) {
				BlockState s = level.getBlockState(p);
				if (s.getBlock() instanceof DoorBlock door && s.is(Blocks.IRON_DOOR)
					&& s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER && s.getValue(DoorBlock.OPEN) != open) {
					door.setOpen(who, level, s, p.immutable(), open);
				}
			}
		}
	}

	/**
	 * The lift's call panel. Every panel in the same shaft (straight above or below, within 96 blocks) is a
	 * floor; use it to ride down one floor - from the lowest back to the top - or sneak and use it to ride up.
	 * You step out in front of the panel on the new floor.
	 */
	public static class ElevatorPanel extends BaseBlocks.Shaped {
		public ElevatorPanel(Properties properties) {
			super(properties, Block.box(3, 2, 3, 13, 14, 13), Block.box(3, 2, 3, 13, 14, 13));
		}

		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			if (!(level instanceof ServerLevel server)) {
				return InteractionResult.SUCCESS;
			}
			java.util.List<BlockPos> floors = new java.util.ArrayList<>();
			for (int dy = 96; dy >= -96; dy--) {
				BlockPos p = pos.offset(0, dy, 0);
				if (level.isInWorldBounds(p) && level.getBlockState(p).is(this)) {
					floors.add(p);
				}
			}
			int here = floors.indexOf(pos);
			if (floors.size() < 2 || here < 0) {
				server.playSound(null, pos, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.BLOCKS, 0.6F, 0.7F);
				return InteractionResult.SUCCESS;
			}
			int to = player.isShiftKeyDown() ? Math.max(0, here - 1) : here + 1 >= floors.size() ? 0 : here + 1;
			if (to == here) {
				return InteractionResult.SUCCESS;
			}
			BlockPos target = floors.get(to);
			Direction out = level.getBlockState(target).getValue(FACING);
			Vec3 at = Vec3.atBottomCenterOf(target.relative(out));
			server.playSound(null, pos, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.7F, 0.8F);
			player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 25, 0, false, false, false));
			player.teleportTo(at.x, at.y, at.z);
			player.setYRot(out.toYRot());
			server.playSound(null, target, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 0.7F, 1.6F);
			server.playSound(null, target, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 0.7F, 0.8F);
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.elevator.floor", to == 0 ? "0" : "-" + to)
				.withStyle(ChatFormatting.GRAY), true);
			return InteractionResult.SUCCESS;
		}
	}
}
