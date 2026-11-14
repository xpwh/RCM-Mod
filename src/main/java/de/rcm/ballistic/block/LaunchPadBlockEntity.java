package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Holds no data: it only exists so the launch complex around the pad can be drawn by its renderer. */
public class LaunchPadBlockEntity extends BlockEntity {
	public LaunchPadBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.LAUNCH_PAD_BE, pos, state);
	}
}
