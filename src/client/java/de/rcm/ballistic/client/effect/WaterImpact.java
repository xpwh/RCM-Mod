package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A round striking water: no hole, but a thin white column shooting up and collapsing back, drops flung
 * off it and a crown of them thrown out round it (more the way the round went when it came in flat), fine
 * spray hanging a moment, rings spreading over the surface and a trail of bubbles where it went in.
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

		// the spout: a thin white column shooting up and falling back in on itself
		double height = (0.22 + 0.85 * steep) * (0.8 + 0.4 * random.nextDouble());
		level.addParticle(ModRegistry.WATER_JET, at.x, top - 0.03, at.z, height, 0.032 + 0.016 * random.nextDouble(), 0.0);
		// drops flung off the column, the fastest from its tip
		double tipSpeed = Math.sqrt(2.0 * 0.04 * height);
		int spout = 8 + random.nextInt(5);
		for (int i = 0; i < spout; i++) {
			double up = tipSpeed * (0.55 + 0.5 * random.nextDouble());
			double lean = (1.0 - steep) * 0.08 * random.nextDouble();
			level.addParticle(ModRegistry.WATER_DROP, at.x + (random.nextDouble() - 0.5) * 0.04, top + random.nextDouble() * height * 0.3,
				at.z + (random.nextDouble() - 0.5) * 0.04, ahead.x * lean + (random.nextDouble() - 0.5) * 0.04, up,
				ahead.z * lean + (random.nextDouble() - 0.5) * 0.04);
		}
		// the crown: a ring of drops thrown out low all round, more of them the way the round was going
		int crown = 14 + random.nextInt(8);
		for (int i = 0; i < crown; i++) {
			double a = Math.PI * 2.0 * (i + random.nextDouble() * 0.7) / crown;
			double out = 0.035 + random.nextDouble() * 0.06;
			double cx = Math.cos(a);
			double cz = Math.sin(a);
			double bias = 1.0 + (1.0 - steep) * 0.8 * Math.max(0.0, cx * ahead.x + cz * ahead.z);
			level.addParticle(ModRegistry.WATER_DROP, at.x + cx * 0.04, top, at.z + cz * 0.04, cx * out * bias, 0.07 + random.nextDouble() * 0.11,
				cz * out * bias);
		}
		// fine spray hanging over it a moment
		for (int i = 0; i < 6; i++) {
			double h = i < 4 ? 0.04 : height * (0.3 + 0.4 * random.nextDouble());
			level.addParticle(ModRegistry.WATER_MIST, at.x + (random.nextDouble() - 0.5) * 0.12, top + h, at.z + (random.nextDouble() - 0.5) * 0.12,
				(random.nextDouble() - 0.5) * 0.04 + ahead.x * 0.02, 0.01 + random.nextDouble() * 0.02, (random.nextDouble() - 0.5) * 0.04 + ahead.z * 0.02);
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
