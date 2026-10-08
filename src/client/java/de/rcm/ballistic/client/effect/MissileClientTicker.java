package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.client.sound.MissileEngineSound;
import de.rcm.ballistic.client.sound.MissileFollowSound;
import de.rcm.ballistic.client.render.MissileMesh;
import de.rcm.ballistic.entity.InterceptorEntity;
import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.MissileType;
import de.rcm.ballistic.entity.ReentryVehicleEntity;
import net.minecraft.world.entity.Entity;
import de.rcm.ballistic.entity.MissileTrajectory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Per-tick client visuals and audio for a missile: vapor, exhaust, smoke trail, engine roar. */
public final class MissileClientTicker {
	private MissileClientTicker() {
	}

	public static void tick(MissileEntity missile) {
		Minecraft mc = Minecraft.getInstance();
		int state = missile.getState();
		float scale = missile.getMissileType().radius / 0.45F;

		// ignition: the launch roar follows the rocket (not left behind on the pad)
		boolean lit = state == MissileEntity.IGNITION && missile.lastSeenState != MissileEntity.IGNITION
			|| state == MissileEntity.FLIGHT && missile.lastSeenState == MissileEntity.EJECT;
		if (state == MissileEntity.EJECT && missile.lastSeenState != MissileEntity.EJECT) {
			mc.getSoundManager().play(new MissileFollowSound(missile, ModRegistry.IGNITION_SUB, 0.55F, 700.0, 1.2F)); // gas generator
		}
		if (lit) {
			MissileType type = missile.getMissileType();
			float pitch = type.isCruise() && state == MissileEntity.IGNITION ? 1.25F : type == MissileType.HYDROGEN ? 0.72F : type.isNuclear() ? 0.85F : 1.0F;
			mc.getSoundManager().play(new MissileFollowSound(missile, ModRegistry.IGNITION, pitch, 900.0, 1.2F));
			mc.getSoundManager().play(new MissileFollowSound(missile, ModRegistry.IGNITION_SUB, pitch, 1400.0, 1.0F));
		}

		if (missile.isBoosterBurning() && !missile.engineSoundStarted) {
			missile.engineSoundStarted = true;
			double range = missile.getMissileType().isCruise() ? 700.0 : 1400.0;
			mc.getSoundManager().play(new MissileEngineSound(missile, ModRegistry.ENGINE_LOOP, range, 1.5F, MissileEntity::isBoosterBurning));
			mc.getSoundManager().play(new MissileEngineSound(missile, ModRegistry.ENGINE_CRACKLE, range * 0.36, 2.2F, MissileEntity::isBoosterBurning));
		}
		if (!missile.isEngineOn()) {
			missile.engineSoundStarted = false;
		}
		if (missile.isJetRunning() && !missile.jetSoundStarted) {
			missile.jetSoundStarted = true;
			mc.getSoundManager().play(new MissileEngineSound(missile, ModRegistry.JET_LOOP, 600.0, 1.6F, MissileEntity::isJetRunning));
		}

		switch (state) {
			case MissileEntity.COUNTDOWN -> countdown(missile, scale);
			case MissileEntity.IGNITION -> ignition(missile, scale);
			case MissileEntity.FLIGHT -> flight(mc, missile, scale);
			case MissileEntity.EJECT -> eject(mc, missile, scale);
			default -> {
			}
		}
		missile.lastSeenState = state;
	}

	private static void countdown(MissileEntity missile, float scale) {
		int remaining = missile.getMissileType().countdownTicks - missile.clientStateAge;
		// Cryogenic vapor venting from the side valves during the last seconds.
		if (remaining < 100 && missile.clientStateAge % 2 == 0) {
			double h = missile.getMissileType().length * 0.55;
			double a = ClientEffects.rand() * Mth.TWO_PI;
			CloudParticle p = ClientEffects.cloud(
				false, missile.getX() + Math.cos(a) * 0.6 * scale, missile.getY() + h, missile.getZ() + Math.sin(a) * 0.6 * scale,
				Math.cos(a) * 0.12, -0.02, Math.sin(a) * 0.12
			);
			if (p != null) {
				p.configure(40, 0.5F, 2.2F, 0xFFFFFF, 0xE6EEF2, 0.6F).physics(0.92F, -0.002F);
			}
		}
	}

	/** Cold launch: the gas generator blasts steam and smoke out of the silo shaft. */
	private static void eject(Minecraft mc, MissileEntity missile, float scale) {
		Vec3 top = missile.getSiloTop();
		int age = missile.clientStateAge;
		int count = age < 6 ? 30 : 10;
		for (int i = 0; i < count; i++) {
			double a = ClientEffects.rand() * Mth.TWO_PI;
			double out = 0.3 + ClientEffects.rand() * (age < 6 ? 1.2 : 0.6);
			double up = 0.4 + ClientEffects.rand() * 0.9;
			CloudParticle p = ClientEffects.cloud(false, top.x + Math.cos(a) * 0.4, top.y + 0.2, top.z + Math.sin(a) * 0.4, Math.cos(a) * out, up, Math.sin(a) * out);
			if (p != null) {
				p.configure(90 + (int) (ClientEffects.rand() * 70), 1.2F * scale, 6.0F * scale, 0xFFFFFF, 0xC8C6C2, 0.8F)
					.physics(0.9F, 0.004F)
					.shade(0.8F + ClientEffects.rand() * 0.2F)
					.turbulence(0.02F);
			}
		}
		if (age == 0) {
			ClientEffects.addShake((float) Math.max(0.0, 1.6 - mc.gameRenderer.getMainCamera().position().distanceTo(top) / 60.0));
		}
	}

	private static void ignition(MissileEntity missile, float scale) {
		double x = missile.getX();
		double y = missile.getY();
		double z = missile.getZ();
		int age = missile.clientStateAge;
		int count = 4 + Math.min(14, age / 2);

		// Smoke rolling out along the ground from the pad.
		for (int i = 0; i < count; i++) {
			double a = ClientEffects.rand() * Mth.TWO_PI;
			double speed = 0.5 + ClientEffects.rand() * 0.9;
			CloudParticle p = ClientEffects.cloud(false, x, y + 0.3, z, Math.cos(a) * speed, 0.03 + ClientEffects.rand() * 0.05, Math.sin(a) * speed);
			if (p != null) {
				p.configure(140 + (int) (ClientEffects.rand() * 90), 1.8F * scale, 7.5F * scale, 0xE0DCD6, 0xA8A49E, 0.88F)
					.physics(0.93F, 0.004F)
					.shade(0.7F + ClientEffects.rand() * 0.3F);
			}
		}
		nozzleFire(x, y, z, new Vec3(0, 1, 0), scale, 3);
		if (age % 2 == 0) {
			ClientEffects.vanilla(ParticleTypes.LAVA, x, y + 0.2, z, 0, 0, 0);
		}
		ClientEffects.addShake(0.35F);
	}

	/** Cruise missile: booster smoke during the climb-out, then an almost invisible turbofan heat trail. */
	private static void cruiseFlight(Minecraft mc, MissileEntity missile, float scale) {
		Vec3 nozzle = missile.position();
		Vec3 dir = missile.getNoseDirection(1.0F);
		Vec3 prev = missile.lastNozzlePos != null && missile.lastSeenState == MissileEntity.FLIGHT ? missile.lastNozzlePos : nozzle;
		missile.lastNozzlePos = nozzle;
		double speed = nozzle.distanceTo(prev);

		if (missile.isBoosterBurning()) {
			int puffs = Mth.clamp((int) (speed / 0.7), 2, 10);
			for (int i = 0; i < puffs; i++) {
				double f = ClientEffects.rand();
				Vec3 p = prev.lerp(nozzle, f).subtract(dir.scale(1.0 + MissileMesh.CRUISE_BOOSTER_LENGTH));
				CloudParticle c = ClientEffects.cloud(false, p.x, p.y, p.z, -dir.x * 0.1 + ClientEffects.gauss() * 0.02, -dir.y * 0.1, -dir.z * 0.1 + ClientEffects.gauss() * 0.02);
				if (c != null) {
					c.configure(200 + (int) (ClientEffects.rand() * 100), 1.0F, 4.5F, 0xF4F1EC, 0xB8B4AE, 0.85F)
						.physics(0.9F, 0.0015F)
						.shade(0.82F + ClientEffects.rand() * 0.22F)
						.turbulence(0.02F);
				}
			}
			Vec3 b = nozzle.subtract(dir.scale(MissileMesh.CRUISE_BOOSTER_LENGTH + 0.3));
			nozzleFire(b.x, b.y, b.z, dir, scale, 3);
		} else {
			// faint heat shimmer + exhaust haze
			CloudParticle c = ClientEffects.cloud(false, nozzle.x - dir.x, nozzle.y - dir.y, nozzle.z - dir.z, -dir.x * 0.2, -dir.y * 0.2, -dir.z * 0.2);
			if (c != null) {
				c.configure(40, 0.4F, 1.8F, 0xD8D6D2, 0xC0BEBA, 0.16F).physics(0.9F, 0.0F);
			}
			CloudParticle glow = ClientEffects.cloud(true, nozzle.x - dir.x * 0.3, nozzle.y - dir.y * 0.3, nozzle.z - dir.z * 0.3, 0, 0, 0);
			if (glow != null) {
				glow.configure(3, 0.35F, 0.25F, 0xFFC890, 0xFF7030, 0.5F);
			}
		}

		Vec3 target = Vec3.atBottomCenterOf(missile.getTarget());
		double horizontal = Math.hypot(target.x - nozzle.x, target.z - nozzle.z);
		// the fly-in recording lasts 2.5 s and ends at the moment of impact
		if (!missile.incomingPlayed && horizontal < 220) {
			double d = mc.gameRenderer.getMainCamera().position().distanceTo(target);
			if (d < 350) {
				missile.incomingPlayed = true;
				mc.getSoundManager().play(new MissileFollowSound(missile, ModRegistry.INCOMING, 1.05F, 450.0, 1.0F, true));
			}
		}
	}

	private static void flight(Minecraft mc, MissileEntity missile, float scale) {
		if (missile.getMissileType().isCruise()) {
			cruiseFlight(mc, missile, scale);
			return;
		}
		MissileTrajectory path = missile.getTrajectory();
		int age = missile.clientStateAge;
		Vec3 nozzle = missile.position();
		Vec3 dir = path.direction(age);
		Vec3 prev = missile.lastNozzlePos != null && missile.lastSeenState == MissileEntity.FLIGHT ? missile.lastNozzlePos : nozzle;
		missile.lastNozzlePos = nozzle;

		double speed = path.velocity(age).length();
		int puffs = Mth.clamp((int) (speed / 0.9), 2, 16);
		for (int i = 0; i < puffs; i++) {
			double f = ClientEffects.rand();
			double px = Mth.lerp(f, prev.x, nozzle.x) - dir.x * 1.5;
			double py = Mth.lerp(f, prev.y, nozzle.y) - dir.y * 1.5;
			double pz = Mth.lerp(f, prev.z, nozzle.z) - dir.z * 1.5;
			double sx = ClientEffects.gauss() * 0.35;
			double sz = ClientEffects.gauss() * 0.35;
			CloudParticle p = ClientEffects.cloud(false, px + sx, py, pz + sz, -dir.x * 0.15 + sx * 0.04, -dir.y * 0.15, -dir.z * 0.15 + sz * 0.04);
			if (p != null) {
				p.configure(240 + (int) (ClientEffects.rand() * 140), 1.4F * scale, 6.0F * scale, 0xF4F1EC, 0xB8B4AE, 0.85F)
					.physics(0.9F, 0.0015F)
					.shade(0.82F + ClientEffects.rand() * 0.22F);
			}
			if (i % 2 == 0) {
				// thin outer sheath that spreads wider and lingers
				CloudParticle o = ClientEffects.cloud(false, px, py, pz, ClientEffects.gauss() * 0.06, -dir.y * 0.08, ClientEffects.gauss() * 0.06);
				if (o != null) {
					o.configure(320 + (int) (ClientEffects.rand() * 160), 2.5F * scale, 10.0F * scale, 0xE6E3DE, 0xA8A49E, 0.38F)
						.physics(0.94F, 0.0008F)
						.shade(0.75F + ClientEffects.rand() * 0.2F);
				}
			}
		}
		nozzleFire(nozzle.x, nozzle.y, nozzle.z, dir, scale, 3);
		// high up the exhaust trail freezes into a contrail that hangs in the sky and twists in the wind
		Contrails.trail(missile.getId() * 4, nozzle.subtract(dir.scale(1.5)), 2.2 * scale,
			missile.isBoosterBurning() ? Contrails.altitudeFactor(nozzle.y) : 0.0);

		float t = (float) age / Math.max(1, path.duration());
		boolean hypersonic = missile.getMissileType().warhead == MissileType.Warhead.HYPERSONIC;
		if (hypersonic ? t > 0.2F : t > 0.75F) {
			// ionised air streaming off the nose
			Vec3 nose = nozzle.add(dir.scale(missile.getMissileType().length * 0.92));
			for (int i = 0; i < 3; i++) {
				CloudParticle p = ClientEffects.cloud(true, nose.x + ClientEffects.gauss() * 0.2, nose.y + ClientEffects.gauss() * 0.2, nose.z + ClientEffects.gauss() * 0.2,
					-dir.x * speed * 0.3, -dir.y * speed * 0.3, -dir.z * speed * 0.3);
				if (p != null) {
					p.configure(6 + (int) (ClientEffects.rand() * 6), 0.8F * scale, 2.2F * scale, 0xFFE6F0, 0xFF7A3A, 0.85F).physics(0.8F, 0.0F);
				}
			}
		}
		if (hypersonic && !missile.sonicBoomPlayed) {
			double d = mc.gameRenderer.getMainCamera().position().distanceTo(nozzle);
			if (d < 260) {
				missile.sonicBoomPlayed = true;
				ClientEffects.playDistant(mc, ModRegistry.SONIC_BOOM, nozzle, (float) (1.1 - d / 300.0), 1.0F);
				ClientEffects.addShake((float) (1.5 - d / 200.0));
			}
		}

		// Launch cloud keeps growing for the first seconds of the climb.
		if (age < 50) {
			Vec3 pad = path.position(0);
			for (int i = 0; i < 8; i++) {
				double a = ClientEffects.rand() * Mth.TWO_PI;
				double sp = 0.6 + ClientEffects.rand() * 0.8;
				CloudParticle p = ClientEffects.cloud(false, pad.x, pad.y + 0.5, pad.z, Math.cos(a) * sp, 0.05, Math.sin(a) * sp);
				if (p != null) {
					p.configure(180, 2.2F * scale, 9.0F * scale, 0xDCD8D2, 0xA4A09A, 0.85F).physics(0.93F, 0.004F).shade(0.72F + ClientEffects.rand() * 0.3F);
				}
			}
			ClientEffects.addShake((float) Math.max(0.0, 0.5 - mc.gameRenderer.getMainCamera().position().distanceTo(pad) / 200.0));
		}

		// Terminal-phase whistle for anyone near the impact point.
		int remaining = path.duration() - age;
		if (!missile.incomingPlayed && remaining < 50 && dir.y < 0) {
			Vec3 target = path.position(path.duration());
			double d = mc.gameRenderer.getMainCamera().position().distanceTo(target);
			if (d < 450) {
				missile.incomingPlayed = true;
				mc.getSoundManager().play(new MissileFollowSound(missile, ModRegistry.INCOMING, 1.0F, 500.0, 1.0F, true));
			}
		}
	}

	/** Interceptors and MIRV re-entry vehicles. */
	public static void projectileTick(Entity entity) {
		Vec3 pos = entity.position();
		if (entity instanceof InterceptorEntity interceptor) {
			Vec3 dir = interceptor.getDir();
			for (int i = 0; i < 3; i++) {
				double back = 0.4 + ClientEffects.rand() * 3.0;
				CloudParticle p = ClientEffects.cloud(false, pos.x - dir.x * back, pos.y - dir.y * back, pos.z - dir.z * back,
					ClientEffects.gauss() * 0.02, 0.0, ClientEffects.gauss() * 0.02);
				if (p != null) {
					p.configure(110 + (int) (ClientEffects.rand() * 60), 0.5F, 2.6F, 0xF4F2EE, 0xB4B0AA, 0.75F).physics(0.92F, 0.001F).turbulence(0.01F);
				}
			}
			nozzleFire(pos.x, pos.y, pos.z, dir, 0.3F, 2);
			Contrails.trail(entity.getId() * 4, pos.subtract(dir.scale(0.8)), 1.0, Contrails.altitudeFactor(pos.y));
		} else if (entity instanceof ReentryVehicleEntity) {
			Vec3 v = entity.getDeltaMovement();
			Vec3 dir = v.lengthSqr() < 1.0E-6 ? new Vec3(0, -1, 0) : v.normalize();
			for (int i = 0; i < 4; i++) {
				double back = ClientEffects.rand() * v.length();
				CloudParticle p = ClientEffects.cloud(true, pos.x - dir.x * back, pos.y - dir.y * back, pos.z - dir.z * back, 0, 0, 0);
				if (p != null) {
					p.configure(10 + (int) (ClientEffects.rand() * 8), 0.9F, 2.0F, 0xFFF0E0, 0xFF5020, 0.9F).cooling(0.5F, 0x6A6460);
				}
			}
		}
	}

	private static void nozzleFire(double x, double y, double z, Vec3 dir, float scale, int count) {
		for (int i = 0; i < count; i++) {
			double back = ClientEffects.rand() * 2.0;
			CloudParticle p = ClientEffects.cloud(
				true, x - dir.x * back, y - dir.y * back, z - dir.z * back,
				-dir.x * 0.6 + ClientEffects.gauss() * 0.05, -dir.y * 0.6, -dir.z * 0.6 + ClientEffects.gauss() * 0.05
			);
			if (p != null) {
				p.configure(5 + (int) (ClientEffects.rand() * 5), 1.1F * scale, 2.4F * scale, 0xFFF4C8, 0xFF6A10, 1.0F).physics(0.8F, 0.0F);
			}
		}
	}
}
