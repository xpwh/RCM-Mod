package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Only there so the moored mine, its chain and sinker can be drawn by a renderer. */
public class SeaMineBlockEntity extends BlockEntity {
	public SeaMineBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.SEA_MINE_BE, pos, state);
	}
}
