package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Holds no data: it only exists so the launcher's frame and canister can be drawn by its renderer. */
public class InclinedLauncherBlockEntity extends BlockEntity {
	public InclinedLauncherBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.INCLINED_LAUNCHER_BE, pos, state);
	}
}
