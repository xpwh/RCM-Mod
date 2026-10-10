package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Command center console: right-click opens the situation map. */
public class CommandCenterBlock extends DefenseSiteBlock {
	public CommandCenterBlock(Properties properties) {
		super(properties, () -> ModRegistry.COMMAND_CENTER_BE, CommandCenterBlockEntity::new);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof CommandCenterBlockEntity center) {
			center.open(serverPlayer);
		}
		return InteractionResult.SUCCESS;
	}
}
