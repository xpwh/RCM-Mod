package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.explosion.NuclearWinter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Client side of the nuclear winter: the soot level from the server drives the grey, dim grade of
 * the blast shader, and ash and grey snow drift down everywhere in the open.
 */
public final class WinterClient {
	private static float soot;
	private static float shown;

	private WinterClient() {
	}

	public static void receive(float value) {
		soot = Mth.clamp(value, 0.0F, 1.0F);
	}

	public static void clear() {
		soot = 0.0F;
		shown = 0.0F;
	}

	/** Strength of the winter look, 0-1 (eased in, only in the overworld). */
	public static float amount() {
		return shown;
	}

	public static void tick(Minecraft mc) {
		boolean overworld = mc.level != null && mc.level.dimension() == Level.OVERWORLD;
		float target = overworld ? Mth.clamp((soot - 0.03F) / 0.6F, 0.0F, 1.0F) : 0.0F;
		shown += Mth.clamp(target - shown, -0.005F, 0.005F);
		if (mc.level == null || mc.isPaused() || soot < NuclearWinter.ONSET * 0.6F || !overworld) {
			return;
		}
		Vec3 eye = mc.gameRenderer.getMainCamera().position();
		int flakes = Mth.ceil(soot * 12);
		for (int i = 0; i < flakes; i++) {
			double x = eye.x + (ClientEffects.rand() - 0.5) * 36.0;
			double z = eye.z + (ClientEffects.rand() - 0.5) * 36.0;
			double y = eye.y + 3.0 + ClientEffects.rand() * 16.0;
			if (mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z)) > y) {
				continue;
			}
			float r = ClientEffects.rand();
			ClientEffects.vanilla(r < 0.45F ? ParticleTypes.WHITE_ASH : r < 0.7F ? ParticleTypes.ASH : ParticleTypes.SNOWFLAKE, x, y, z, 0.0, -0.04, 0.0);
		}
	}
}
