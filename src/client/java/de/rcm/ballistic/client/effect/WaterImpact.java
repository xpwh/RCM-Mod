package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A round striking water: no hole, but a thin spout jumping up (leaning the way the round went when it came
 * in flat), a crown of drops thrown out round it, fine spray, rings spreading over the surface, and a
 * trail of bubbles where it went in.
 */
public final class WaterImpact {
	private WaterImpact() {
	}

	/** @param at where it struck the surface; @param dir which way it was flying */
	public static void bullet(Vec3 at, Vec3 dir) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		var random = level.getRandom();
		Vec3 d = dir.lengthSqr() < 1.0E-6 ? new Vec3(0, -1, 0) : dir.normalize();
		// steep in: a tall narrow spout; in flat: lower, thrown forward
		float steep = (float) Mth.clamp(-d.y, 0.0, 1.0);
		Vec3 ahead = new Vec3(d.x, 0.0, d.z);
		ahead = ahead.lengthSqr() < 1.0E-6 ? Vec3.ZERO : ahead.normalize();
		double top = at.y + 0.02;

		// the spout: a column of drops, the faster ones on top
		int spout = 10 + random.nextInt(5);
		for (int i = 0; i < spout; i++) {
			double up = (0.18 + 0.32 * steep) * (0.45 + 0.55 * random.nextDouble());
			double lean = (1.0 - steep) * 0.1 * random.nextDouble();
			level.addParticle(ModRegistry.WATER_DROP, at.x + (random.nextDouble() - 0.5) * 0.04, top, at.z + (random.nextDouble() - 0.5) * 0.04,
				ahead.x * lean + (random.nextDouble() - 0.5) * 0.025, up, ahead.z * lean + (random.nextDouble() - 0.5) * 0.025);
		}
		// the crown: drops thrown out all round, low
		int crown = 12 + random.nextInt(6);
		for (int i = 0; i < crown; i++) {
			double a = Math.PI * 2.0 * (i + random.nextDouble() * 0.6) / crown;
			double out = 0.04 + random.nextDouble() * 0.07;
			level.addParticle(ModRegistry.WATER_DROP, at.x + Math.cos(a) * 0.05, top, at.z + Math.sin(a) * 0.05,
				Math.cos(a) * out + ahead.x * 0.03, 0.08 + random.nextDouble() * 0.12, Math.sin(a) * out + ahead.z * 0.03);
		}
		// fine spray settling back
		for (int i = 0; i < 4; i++) {
			level.addParticle(ParticleTypes.SPLASH, at.x + (random.nextDouble() - 0.5) * 0.2, top, at.z + (random.nextDouble() - 0.5) * 0.2, 0.0, 0.0, 0.0);
		}
		// rings over the surface: a quick small one and a wide slow one
		level.addParticle(ModRegistry.WATER_RIPPLE, at.x, top, at.z, 0.35 + random.nextDouble() * 0.1, 0.0, 0.0);
		level.addParticle(ModRegistry.WATER_RIPPLE, at.x, top + 0.002, at.z, 0.7 + random.nextDouble() * 0.25, 0.0, 0.0);
		// bubbles along the way in
		for (int i = 0; i < 6; i++) {
			Vec3 p = at.add(d.scale(0.15 + random.nextDouble() * 1.1));
			if (!level.getFluidState(BlockPos.containing(p)).isEmpty()) {
				level.addParticle(ParticleTypes.BUBBLE, p.x, p.y, p.z, d.x * 0.2, 0.05, d.z * 0.2);
			}
		}
	}
}
