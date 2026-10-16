package de.rcm.ballistic.sun;

import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.item.TargetData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The Helios launch complex: a fifty-metre coil accelerator standing over a loading shaft, with its capacitor
 * banks, cooling towers and the control bunker - one block, the whole structure drawn round it. Right-click with
 * a Sun Destroyer to load it, with a target designator to give it a target; right-click with an empty hand opens
 * the launch console.
 */
public class SunForgeBlock extends Block implements EntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

	public SunForgeBlock(Properties properties) {
		super(properties);
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
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (placer != null && level.getBlockEntity(pos) instanceof SunForgeBlockEntity forge) {
			forge.setOwner(placer.getUUID());
		}
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof SunForgeBlockEntity forge)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (stack.is(ModRegistry.SUN_DESTROYER)) {
			if (player instanceof ServerPlayer sp && !de.rcm.ballistic.launch.Ownership.refuse(sp, forge)) {
				if (forge.load()) {
					if (!player.getAbilities().instabuild) {
						stack.shrink(1);
					}
					sp.displayClientMessage(Component.translatable("message.ballisticmissiles.sun_loaded").withStyle(ChatFormatting.GOLD), true);
				} else {
					sp.displayClientMessage(Component.translatable("message.ballisticmissiles.sun_already_loaded").withStyle(ChatFormatting.YELLOW), true);
				}
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.is(ModRegistry.TARGET_DESIGNATOR)) {
			if (player instanceof ServerPlayer sp && !de.rcm.ballistic.launch.Ownership.refuse(sp, forge)) {
				TargetData target = stack.get(ModRegistry.TARGET);
				if (target == null) {
					sp.displayClientMessage(Component.translatable("message.ballisticmissiles.no_target").withStyle(ChatFormatting.YELLOW), true);
				} else {
					forge.action(sp, SunNetwork.ACTION_TARGET, target.pos().getX(), target.pos().getZ());
				}
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide()) {
			ClientHooks.openSunForge.accept(pos);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SunForgeBlockEntity(pos, state);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (type != ModRegistry.SUN_FORGE_BE) {
			return null;
		}
		return level.isClientSide()
			? (BlockEntityTicker<T>) (BlockEntityTicker<SunForgeBlockEntity>) SunForgeBlockEntity::clientTick
			: (BlockEntityTicker<T>) (BlockEntityTicker<SunForgeBlockEntity>) SunForgeBlockEntity::serverTick;
	}
}
