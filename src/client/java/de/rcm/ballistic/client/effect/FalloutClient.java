package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.explosion.RadiationManager.Plume;
import de.rcm.ballistic.explosion.Wind;
import de.rcm.ballistic.network.ModNetworking.FalloutPayload;
import de.rcm.ballistic.network.ModNetworking.FalloutPlume;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Fallout you can see: inside a plume, grey radioactive dust snows down out of the drifting cloud,
 * carried sideways by the wind, and the air turns hazy - the denser, the closer to ground zero and
 * the fresher the fallout.
 */
public final class FalloutClient {
	private static final List<Plume> PLUMES = new ArrayList<>();
	private static long clock;
	private static ClientLevel level;

	private FalloutClient() {
	}

	public static void receive(FalloutPayload payload) {
		PLUMES.clear();
		for (FalloutPlume p : payload.plumes()) {
			PLUMES.add(new Plume(null, new Vec3(p.x(), 0, p.z()), p.dirX(), p.dirZ(), p.length(), p.width(), p.peak(), clock - p.age(), p.duration()));
		}
	}

	public static void clear() {
		PLUMES.clear();
	}

	/** Fallout density at a position, 0-1. */
	public static double density(double x, double z) {
		double sum = 0.0;
		for (Plume p : PLUMES) {
			sum += p.strength(x, z, clock) * p.decay(clock);
		}
		return Math.min(1.0, sum);
	}

	public static void tick(Minecraft mc) {
		if (mc.level != level) {
			level = mc.level;
			PLUMES.clear(); // other dimension or world: the server sends this one's plumes
		}
		if (mc.level == null || mc.player == null || mc.isPaused() || PLUMES.isEmpty()) {
			return;
		}
		clock++;
		PLUMES.removeIf(p -> clock - p.start() > p.duration());
		Vec3 eye = mc.gameRenderer.getMainCamera().position();
		double density = density(eye.x, eye.z);
		if (density < 0.015) {
			return;
		}
		double[] wind = Wind.at(mc.level.getGameTime(), eye.y + 10);
		int flakes = Mth.ceil(density * 14);
		for (int i = 0; i < flakes; i++) {
			double x = eye.x + (ClientEffects.rand() - 0.5) * 40.0;
			double z = eye.z + (ClientEffects.rand() - 0.5) * 40.0;
			double y = eye.y + 4.0 + ClientEffects.rand() * 18.0;
			if (mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z)) > y) {
				continue; // under a roof
			}
			// vanilla ash particles drift and tumble on their own; give them the wind and a slow fall
			ClientEffects.vanilla(ClientEffects.rand() < 0.7F ? ParticleTypes.WHITE_ASH : ParticleTypes.ASH, x, y, z, wind[0] * 4.0, -0.05, wind[1] * 4.0);
		}
		// dusty haze hanging in the air
		if (density > 0.12 && ClientEffects.rand() < density) {
			double a = ClientEffects.rand() * Mth.TWO_PI;
			double r = 12.0 + ClientEffects.rand() * 30.0;
			double x = eye.x + Math.cos(a) * r;
			double z = eye.z + Math.sin(a) * r;
			double y = Math.max(eye.y - 2.0, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z))) + ClientEffects.rand() * 10.0;
			CloudParticle c = ClientEffects.cloud(false, x, y, z, 0, -0.01, 0);
			if (c != null) {
				c.configure(160 + (int) (ClientEffects.rand() * 80), 5.0F, 11.0F, 0x9C978C, 0x86827A, (float) (0.08 + 0.14 * density)).physics(0.95F, 0.0F);
			}
		}
	}
}
