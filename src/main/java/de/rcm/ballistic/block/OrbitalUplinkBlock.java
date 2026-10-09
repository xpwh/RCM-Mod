package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.item.LauncherLink;
import de.rcm.ballistic.item.TargetData;
import de.rcm.ballistic.item.TargetDesignatorItem;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
 * Ground station for the orbital kinetic bombardment platform: a large tracking dish on its mount
 * next to an equipment shelter. Right-click with tungsten rods to put them into orbit; right-click
 * with a target designator to order a strike on its target, sneak + right-click to link it for
 * remote launch from the targeting computer.
 */
public class OrbitalUplinkBlock extends Block implements EntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

	public OrbitalUplinkBlock(Properties properties) {
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
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
		if (!(level.getBlockEntity(pos) instanceof OrbitalUplinkBlockEntity uplink)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (stack.is(ModRegistry.TUNGSTEN_ROD)) {
			if (!level.isClientSide()) {
				if (uplink.loadRod()) {
					if (!player.getAbilities().instabuild) {
						stack.shrink(1);
					}
					// the rod goes up on a supply rocket from the pad beside the station
					if (level instanceof net.minecraft.server.level.ServerLevel server) {
						de.rcm.ballistic.entity.SupplyRocketEntity.launch(server, pos, state.getValue(FACING));
					}
					level.playSound(null, pos, ModRegistry.UPLINK_CONFIRM, SoundSource.BLOCKS, 1.0F, 1.0F);
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.uplink_rod_loaded", uplink.rods(), OrbitalUplinkBlockEntity.CAPACITY)
						.withStyle(ChatFormatting.AQUA), true);
				} else {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.uplink_full").withStyle(ChatFormatting.YELLOW), true);
				}
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.is(ModRegistry.TARGET_DESIGNATOR)) {
			if (player instanceof ServerPlayer sp && de.rcm.ballistic.launch.Ownership.refuse(sp, uplink)) {
				return InteractionResult.SUCCESS;
			}
			if (player instanceof ServerPlayer sp) {
				if (player.isShiftKeyDown()) {
					TargetDesignatorItem.toggleLink(sp, stack, LauncherLink.uplink(pos));
				} else {
					TargetData target = stack.get(ModRegistry.TARGET);
					if (target == null) {
						sp.displayClientMessage(Component.translatable("message.ballisticmissiles.no_target").withStyle(ChatFormatting.YELLOW), true);
					} else {
						sp.displayClientMessage(uplink.strike(sp, target), false);
					}
				}
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof OrbitalUplinkBlockEntity uplink) {
			player.displayClientMessage(uplink.status(), true);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new OrbitalUplinkBlockEntity(pos, state);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (type != ModRegistry.ORBITAL_UPLINK_BE) {
			return null;
		}
		return level.isClientSide()
			? (BlockEntityTicker<T>) (BlockEntityTicker<OrbitalUplinkBlockEntity>) OrbitalUplinkBlockEntity::clientTick
			: (BlockEntityTicker<T>) (BlockEntityTicker<OrbitalUplinkBlockEntity>) OrbitalUplinkBlockEntity::serverTick;
	}
}
