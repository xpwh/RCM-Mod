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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Silo hatch, set flush into the ground. Right-click with a missile to load it, with the target
 * designator to launch (or abort), sneak + empty hand to unload. Sneak + designator links it.
 */
public class MissileSiloBlock extends Block implements EntityBlock {
	public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

	public MissileSiloBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(OPEN, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(OPEN);
	}

	@Override
	protected InteractionResult useItemOn(
		ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit
	) {
		if (!(level.getBlockEntity(pos) instanceof MissileSiloBlockEntity silo)) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (stack.getItem() instanceof MissileItem missileItem) {
			if (!level.isClientSide()) {
				MissileType type = missileItem.getMissileType();
				if (silo.load(type)) {
					if (!player.getAbilities().instabuild) {
						stack.shrink(1);
					}
					level.playSound(null, pos, SoundEvents.NETHERITE_BLOCK_PLACE, SoundSource.BLOCKS, 1.5F, 0.5F);
					level.playSound(null, pos, SoundEvents.PISTON_CONTRACT, SoundSource.BLOCKS, 1.0F, 0.5F);
					player.displayClientMessage(silo.status(), true);
				} else {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.silo_full").withStyle(ChatFormatting.YELLOW), true);
				}
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
			MissileType type = silo.unload();
			if (type != null) {
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModRegistry.missileItem(type)));
				level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 1.0F, 0.6F);
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
		if (level.isClientSide() || type != ModRegistry.MISSILE_SILO_BE) {
			return null;
		}
		return (BlockEntityTicker<T>) (BlockEntityTicker<MissileSiloBlockEntity>) MissileSiloBlockEntity::serverTick;
	}
}
