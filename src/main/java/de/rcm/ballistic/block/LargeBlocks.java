package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The mod's structures that are one block but look far bigger (a launch complex, a silo's headworks, a radar
 * station...). The client aims at them by their whole model; the server, which checks that a click lands within
 * reach of the clicked block, allows it as far off as the structure reaches.
 */
public final class LargeBlocks {
	private static Map<Block, Double> reach;

	private LargeBlocks() {
	}

	/** How far beyond the block itself its structure reaches (blocks; 0 for any other block). */
	public static double reach(BlockState state) {
		if (reach == null) {
			Map<Block, Double> m = new IdentityHashMap<>();
			m.put(ModRegistry.LAUNCH_PAD, 32.0);
			m.put(ModRegistry.SUBMARINE, 32.0);
			m.put(ModRegistry.MISSILE_SILO, 10.0);
			m.put(ModRegistry.RADAR, 11.0);
			m.put(ModRegistry.JAMMER, 9.0);
			m.put(ModRegistry.LASER_DEFENSE, 8.0);
			m.put(ModRegistry.IRON_DOME, 10.0);
			m.put(ModRegistry.AIR_DEFENSE, 8.0);
			m.put(ModRegistry.ORBITAL_UPLINK, 7.0);
			m.put(ModRegistry.DECOY_LAUNCHER, 4.0);
			m.put(ModRegistry.CIWS, 4.0);
			m.put(ModRegistry.SEA_MINE, 1.5);
			reach = m;
		}
		return reach.getOrDefault(state.getBlock(), 0.0);
	}
}
