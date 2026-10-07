package de.rcm.ballistic.block;

import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
 * A simple defense site (Iron Dome battery, decoy launcher): a facing block whose block entity does
 * the work every server tick; right-click shows its status.
 */
public class DefenseSiteBlock extends Block implements EntityBlock {
	/** What the block entity of a defense site offers the block. */
	public interface Site {
		void serverTick(ServerLevel level);

		Component status();

		void setOwner(@Nullable UUID owner);
	}

	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	private final Supplier<BlockEntityType<?>> type;
	private final BiFunction<BlockPos, BlockState, BlockEntity> factory;

	public DefenseSiteBlock(Properties properties, Supplier<BlockEntityType<?>> type, BiFunction<BlockPos, BlockState, BlockEntity> factory) {
		super(properties);
		this.type = type;
		this.factory = factory;
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
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof Site site) {
			player.displayClientMessage(site.status(), true);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (placer != null && level.getBlockEntity(pos) instanceof Site site) {
			site.setOwner(placer.getUUID());
		}
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return this.factory.apply(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide() || type != this.type.get()) {
			return null;
		}
		return (lvl, pos, st, be) -> {
			if (lvl instanceof ServerLevel server && be instanceof Site site) {
				site.serverTick(server);
			}
		};
	}
}
