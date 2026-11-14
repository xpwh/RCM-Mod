package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.MissileType;
import de.rcm.ballistic.item.MissileItem;
import de.rcm.ballistic.item.TargetData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Silo hatch, set flush into the ground. Right-click with a missile to load it, with the target
 * designator to launch (or abort), sneak + empty hand to unload. Sneak + designator links it.
 * <p>
 * Placing it digs the real silo: a 3x3 launch tube {@value #SHAFT_DEPTH} blocks deep, lined with
 * reinforced concrete, the missile standing on its floor. The block itself is drawn by its renderer
 * (headworks, sliding doors, the shaft fittings); the closed doors can be walked on, the open ones
 * leave the tube open - mind your step.
 */
public class MissileSiloBlock extends Block implements EntityBlock {
	public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
	/** Depth of the launch tube below the hatch block, blocks (long enough for the Tsar Bomba). */
	public static final int SHAFT_DEPTH = 21;

	private static final VoxelShape HATCH = Block.box(0, 12, 0, 16, 16, 16);
	/** The two closed door halves, spanning the whole 3x3 tube mouth at deck height. */
	private static final VoxelShape DOORS = Shapes.or(HATCH, Block.box(-16, 23, -16, 32, 29.5, 32));

	public MissileSiloBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(OPEN, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(OPEN);
	}

	/** A land silo digs its tube; the submarine carries its own. */
	public boolean hasShaft() {
		return true;
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return this.hasShaft() ? RenderShape.INVISIBLE : RenderShape.MODEL;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return this.hasShaft() ? HATCH : Shapes.block();
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		if (!this.hasShaft()) {
			return Shapes.block();
		}
		return state.getValue(OPEN) ? HATCH : DOORS;
	}

	/**
	 * Digs the launch tube under the hatch: 3x3 open inside, a ring of reinforced concrete around it
	 * and a concrete floor. Unbreakable blocks (bedrock) and containers are left in place.
	 */
	public static void digShaft(Level level, BlockPos pos) {
		BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
		for (int dy = 0; dy <= SHAFT_DEPTH + 1; dy++) {
			int y = pos.getY() - dy;
			if (y <= level.getMinY()) {
				break;
			}
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (dy == 0 && dx == 0 && dz == 0) {
						continue; // the hatch block itself
					}
					m.set(pos.getX() + dx, y, pos.getZ() + dz);
					BlockState old = level.getBlockState(m);
					if (old.getDestroySpeed(level, m) < 0.0F || old.hasBlockEntity()) {
						continue;
					}
					boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
					BlockState want = wall || dy == SHAFT_DEPTH + 1 ? concrete : Blocks.AIR.defaultBlockState();
					if (!old.equals(want)) {
						level.setBlock(m, want, flags);
					}
				}
			}
		}
	}

	@Override
	protected InteractionResult useItemOn(
		ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit
	) {
		if (!(level.getBlockEntity(pos) instanceof MissileSiloBlockEntity silo)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if ((stack.getItem() instanceof MissileItem || stack.is(ModRegistry.TARGET_DESIGNATOR)) && player instanceof ServerPlayer owner
			&& de.rcm.ballistic.launch.Ownership.refuse(owner, silo)) {
			return InteractionResult.SUCCESS; // someone else's silo
		}
		if (stack.getItem() instanceof MissileItem missileItem) {
			if (!level.isClientSide()) {
				MissileType type = missileItem.getMissileType();
				if (silo.load(type)) {
					if (!player.getAbilities().instabuild) {
						stack.shrink(1);
					}
					level.playSound(null, pos, ModRegistry.METAL_THUD, SoundSource.BLOCKS, 1.5F, 0.85F);
					level.playSound(null, pos, ModRegistry.HYDRAULIC_RETRACT, SoundSource.BLOCKS, 1.2F, 0.9F);
					player.displayClientMessage(silo.status(), true);
				} else {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.silo_full").withStyle(ChatFormatting.YELLOW), true);
				}
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.is(ModRegistry.POSEIDON_ITEM) && this instanceof SubmarineBlock) {
			if (player instanceof ServerPlayer serverPlayer && level instanceof net.minecraft.server.level.ServerLevel server) {
				de.rcm.ballistic.item.PoseidonItem.launchFromSubmarine(server, serverPlayer, stack, pos, state);
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.is(ModRegistry.TARGET_DESIGNATOR)) {
			if (player instanceof ServerPlayer serverPlayer) {
				if (silo.isCounting()) {
					silo.abort();
					serverPlayer.displayClientMessage(Component.translatable("message.ballisticmissiles.aborted").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), true);
				} else {
					TargetData target = stack.get(ModRegistry.TARGET);
					if (target == null) {
						serverPlayer.displayClientMessage(Component.translatable("message.ballisticmissiles.no_target").withStyle(ChatFormatting.YELLOW), true);
					} else {
						serverPlayer.displayClientMessage(silo.arm(serverPlayer, target), false);
					}
				}
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof MissileSiloBlockEntity silo)) {
			return InteractionResult.SUCCESS;
		}
		if (player.isShiftKeyDown() && player.getMainHandItem().isEmpty() && silo.getMissile() != null) {
			if (player instanceof ServerPlayer owner && de.rcm.ballistic.launch.Ownership.refuse(owner, silo)) {
				return InteractionResult.SUCCESS;
			}
			MissileType type = silo.unload();
			if (type != null) {
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModRegistry.missileItem(type)));
				level.playSound(null, pos, ModRegistry.HYDRAULIC_EXTEND, SoundSource.BLOCKS, 1.2F, 0.95F);
			}
		}
		player.displayClientMessage(silo.status(), true);
		return InteractionResult.SUCCESS;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MissileSiloBlockEntity(pos, state);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (type != ModRegistry.MISSILE_SILO_BE) {
			return null;
		}
		return level.isClientSide()
			? (BlockEntityTicker<T>) (BlockEntityTicker<MissileSiloBlockEntity>) MissileSiloBlockEntity::clientTick
			: (BlockEntityTicker<T>) (BlockEntityTicker<MissileSiloBlockEntity>) MissileSiloBlockEntity::serverTick;
	}
}
