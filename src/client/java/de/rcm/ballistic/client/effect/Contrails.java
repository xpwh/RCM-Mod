package de.rcm.ballistic.client.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Condensation trails high up: a thin bright line a little behind the engines that slowly spreads
 * into a wide, soft band, drifts and twists with the wind and stays in the sky for minutes. Drawn
 * by the {@link SmokeField}, which spaces the puffs evenly along the path and thins them out when
 * the sky gets crowded.
 */
public final class Contrails {
	/** No contrail below this height, fully persistent from {@link #FULL_Y} up. */
	private static final double MIN_Y = 110.0;
	private static final double FULL_Y = 190.0;
	/** Contrail keys live in their own range so they never collide with exhaust trails. */
	private static final int KEY_OFFSET = 0x40000000;

	private Contrails() {
	}

	/** 0 below the contrail level, 1 high up where every trail persists. */
	public static double altitudeFactor(double y) {
		return Mth.clamp((y - MIN_Y) / (FULL_Y - MIN_Y), 0.0, 1.0);
	}

	/**
	 * Continues the trail {@code key} to {@code now}.
	 *
	 * @param width    width of the fresh trail in blocks
	 * @param strength 0-1, how dense and long-living (usually the altitude factor)
	 */
	public static void trail(int key, Vec3 now, double width, double strength) {
		SmokeField.trail(key ^ KEY_OFFSET, now, SmokeField.Style.contrail((float) width, (float) strength), (float) strength);
	}

	/** Forgets the trail {@code key} (the next call starts a new one). */
	public static void cut(int key) {
		SmokeField.cut(key ^ KEY_OFFSET);
	}

	public static void tick(Minecraft mc) {
	}
}
