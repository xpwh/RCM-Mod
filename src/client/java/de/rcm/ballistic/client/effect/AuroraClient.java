package de.rcm.ballistic.client.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/** Client side of the northern lights ({@link de.rcm.ballistic.explosion.Aurora}): whether they are out, eased in and out. */
public final class AuroraClient {
	private static boolean active;
	private static boolean forced;
	private static boolean random = true;
	private static float shown;

	private AuroraClient() {
	}

	public static void receive(boolean value, boolean isForced, boolean isRandom) {
		active = value;
		forced = isForced;
		random = isRandom;
	}

	public static boolean forced() {
		return forced;
	}

	public static boolean random() {
		return random;
	}

	public static void clear() {
		active = false;
		shown = 0.0F;
	}

	public static void tick(Minecraft mc) {
		boolean overworld = mc.level != null && mc.level.dimension() == Level.OVERWORLD;
		float target = active && overworld ? 1.0F : 0.0F;
		shown += Mth.clamp(target - shown, -0.004F, 0.004F); // they come out (and fade) over half a minute
	}

	/** How strong they are now, 0-1. */
	public static float amount() {
		return shown;
	}
}
