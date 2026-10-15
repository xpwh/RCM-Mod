package de.rcm.ballistic.client.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Client side of the new-moon night ({@link de.rcm.ballistic.explosion.NewMoon}): as night falls the moon,
 * the stars and the sky's own light go, and whatever nothing lights sinks into black ({@link DynamicLights}
 * draws the darkness, so torches, fires and the dynamic lights still show).
 */
public final class NewMoonClient {
	private static boolean active;
	/** The event eased in and out over a few seconds. */
	private static float shown;

	private NewMoonClient() {
	}

	public static void receive(boolean value) {
		active = value;
	}

	public static boolean active() {
		return active;
	}

	public static void clear() {
		active = false;
		shown = 0.0F;
	}

	public static void tick(Minecraft mc) {
		boolean overworld = mc.level != null && mc.level.dimension() == Level.OVERWORLD;
		float target = active && overworld ? 1.0F : 0.0F;
		shown += Mth.clamp(target - shown, -0.01F, 0.01F);
	}

	/** How pitch black it is now, 0-1: the event, as far as night has fallen (dusk darkens into it). */
	public static float amount() {
		Minecraft mc = Minecraft.getInstance();
		if (shown <= 0.0F || mc.level == null) {
			return 0.0F;
		}
		float dark = Mth.clamp(mc.level.getSkyDarken() / 11.0F, 0.0F, 1.0F);
		float x = Mth.clamp((dark - 0.3F) / 0.55F, 0.0F, 1.0F);
		return shown * x * x * (3.0F - 2.0F * x);
	}
}
