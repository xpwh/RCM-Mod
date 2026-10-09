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
import de.rcm.ballistic.entity.MissileStages;
import de.rcm.ballistic.entity.MissileTrajectory;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
		if (state == MissileEntity.FLIGHT || state == MissileEntity.EJECT) {
			// punching up (or falling) through the cloud layer: a hole along the path, the exhaust spreading out in it
			boolean burning = missile.isBoosterBurning();
			VolumetricClouds.rocket(new Vec3(missile.xo, missile.yo, missile.zo), missile.position(), (burning ? 9.0F : 6.0F) + 5.0F * scale,
				burning ? 0.7F + 0.15F * scale : 0.0F, burning ? 1.0F : 0.0F);
		}
		missile.lastSeenState = state;
	}

	private static void countdown(MissileEntity missile, float scale) {
		int remaining = missile.getMissileType().countdownTicks - missile.clientStateAge;
		if (onPad(missile) && missile.clientStateAge % 2 == 0) {
			// liquid oxygen boiling off: a white plume from the sphere's vent stack, sinking as it drifts
			double vx = missile.getX() - 9.5;
			double vy = Math.floor(missile.getY()) + 7.4;
			double vz = missile.getZ() - 8.5;
			CloudParticle p = ClientEffects.cloud(false, vx, vy, vz, ClientEffects.gauss() * 0.02, 0.06, ClientEffects.gauss() * 0.02);
			if (p != null) {
				p.configure(120 + (int) (ClientEffects.rand() * 60), 0.5F, 3.5F, 0xFFFFFF, 0xEEF2F4, 0.55F).physics(0.95F, -0.002F).turbulence(0.015F);
			}
		}
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

	/** Height of the water surface at or below {@code pos} (within 40 blocks), or NaN if there is none. */
	private static double waterSurfaceBelow(Minecraft mc, Vec3 pos) {
		BlockPos.MutableBlockPos p = BlockPos.containing(pos).mutable();
		for (int i = 0; i < 40 && mc.level != null; i++, p.move(0, -1, 0)) {
			if (!mc.level.getFluidState(p).isEmpty()) {
				return p.getY() + mc.level.getFluidState(p).getHeight(mc.level, p);
			}
		}
		return Double.NaN;
	}

	/**
	 * Submarine launch: a burst of gas from the opened tube, the missile rising in a column of
	 * bubbles, then breaking the surface in a dome of water and a tall column of spray, leaving a
	 * ring of foam behind - and water streaming off it as it hangs in the air before the motor lights.
	 */
	private static void seaEject(Minecraft mc, MissileEntity missile, float scale) {
		Vec3 pos = missile.position();
		Vec3 top = missile.getSiloTop();
		int age = missile.clientStateAge;
		float len = missile.getMissileType().length * missile.getMissileType().scale;
		if (age < 5) {
			for (int i = 0; i < 40; i++) {
				ClientEffects.vanilla(ParticleTypes.BUBBLE, top.x + ClientEffects.gauss() * 0.6, top.y + ClientEffects.rand(), top.z + ClientEffects.gauss() * 0.6,
					ClientEffects.gauss() * 0.15, 0.3 + ClientEffects.rand() * 0.4, ClientEffects.gauss() * 0.15);
			}
		}
		boolean submerged = !mc.level.getFluidState(BlockPos.containing(pos)).isEmpty();
		if (submerged) {
			for (int i = 0; i < 14; i++) {
				double along = ClientEffects.rand() * len;
				ClientEffects.vanilla(i % 3 == 0 ? ParticleTypes.BUBBLE : ParticleTypes.BUBBLE_COLUMN_UP,
					pos.x + ClientEffects.gauss() * 0.4, pos.y + along, pos.z + ClientEffects.gauss() * 0.4, 0, 0.2, 0);
			}
			for (int i = 0; i < 6; i++) {
				ClientEffects.vanilla(ParticleTypes.BUBBLE, pos.x + ClientEffects.gauss() * 0.7, pos.y - ClientEffects.rand(), pos.z + ClientEffects.gauss() * 0.7, 0, 0.4, 0);
			}
			return;
		}
		if (!missile.clientBroached) {
			missile.clientBroached = true;
			double sy = waterSurfaceBelow(mc, pos);
			if (!Double.isNaN(sy)) {
				broach(mc, new Vec3(pos.x, sy, pos.z), scale);
			}
		}
		// water streaming off the body while it hangs above the sea
		for (int i = 0; i < 4; i++) {
			ClientEffects.vanilla(ParticleTypes.FALLING_WATER, pos.x + ClientEffects.gauss() * 0.3, pos.y + ClientEffects.rand() * len, pos.z + ClientEffects.gauss() * 0.3, 0, 0, 0);
		}
	}

	private static void broach(Minecraft mc, Vec3 s, float scale) {
		// tall column of spray thrown up with the missile, falling back
		for (int i = 0; i < 70; i++) {
			CloudParticle p = ClientEffects.cloud(false, s.x + ClientEffects.gauss() * 0.5, s.y, s.z + ClientEffects.gauss() * 0.5,
				ClientEffects.gauss() * 0.12, 0.5 + ClientEffects.rand() * 1.3, ClientEffects.gauss() * 0.12);
			if (p != null) {
				p.configure(40 + (int) (ClientEffects.rand() * 40), 0.8F * scale, 3.5F * scale, 0xFFFFFF, 0xDCE4E8, 0.85F).physics(0.95F, -0.035F).wind(0.3F);
			}
		}
		// the dome of water bursting outwards
		for (int i = 0; i < 46; i++) {
			double a = ClientEffects.rand() * Mth.TWO_PI;
			double sp = 0.35 + ClientEffects.rand() * 0.4;
			CloudParticle p = ClientEffects.cloud(false, s.x + Math.cos(a), s.y + 0.3, s.z + Math.sin(a), Math.cos(a) * sp, 0.35 + ClientEffects.rand() * 0.35, Math.sin(a) * sp);
			if (p != null) {
				p.configure(30 + (int) (ClientEffects.rand() * 25), 0.7F * scale, 2.6F * scale, 0xF4F8FA, 0xC8D4DA, 0.8F).physics(0.93F, -0.03F);
			}
			ClientEffects.vanilla(ParticleTypes.SPLASH, s.x + Math.cos(a) * 1.5, s.y + 0.2, s.z + Math.sin(a) * 1.5, Math.cos(a) * sp, 0.5, Math.sin(a) * sp);
		}
		// ring of white foam spreading on the water
		for (int i = 0; i < 30; i++) {
			double a = ClientEffects.rand() * Mth.TWO_PI;
			CloudParticle p = ClientEffects.cloud(false, s.x + Math.cos(a) * 1.4, s.y + 0.15, s.z + Math.sin(a) * 1.4, Math.cos(a) * 0.12, 0.0, Math.sin(a) * 0.12);
			if (p != null) {
				p.configure(220 + (int) (ClientEffects.rand() * 80), 1.6F * scale, 5.5F * scale, 0xFFFFFF, 0xE6EEF0, 0.55F).physics(0.96F, 0.0F);
			}
		}
		for (int i = 0; i < 30; i++) {
			ClientEffects.vanilla(ParticleTypes.FALLING_WATER, s.x + ClientEffects.gauss() * 1.5, s.y + 2 + ClientEffects.rand() * 6, s.z + ClientEffects.gauss() * 1.5, 0, 0, 0);
		}
		ClientEffects.playDistant(mc, net.minecraft.sounds.SoundEvents.GENERIC_SPLASH, s, 1.0F, 0.45F);
		ClientEffects.playDistant(mc, net.minecraft.sounds.SoundEvents.GENERIC_SPLASH, s, 0.9F, 0.6F);
		ClientEffects.playDistant(mc, ModRegistry.EXPLOSION_SUB, s, (float) Math.max(0.0, 0.7 - ClientEffects.distanceToCamera(mc, s) / 400.0), 0.6F);
		ClientEffects.addShake((float) Math.max(0.0, 0.8 - ClientEffects.distanceToCamera(mc, s) / 80.0));
	}

	/** Cold launch: the gas generator blasts steam and smoke out of the silo shaft. */
	private static void eject(Minecraft mc, MissileEntity missile, float scale) {
		Vec3 top = missile.getSiloTop();
		if (!mc.level.getFluidState(BlockPos.containing(top.x, top.y + 0.5, top.z)).isEmpty()) {
			seaEject(mc, missile, scale);
			return;
		}
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
		// the gas generator's cloud boils out of the shaft and hangs over the silo
		SmokeField.burst(new Vec3(top.x, top.y + 1.0, top.z), age < 6 ? 5 : 2, 1.5 * scale, new Vec3(0, 0.25, 0), 0.6, 2000,
			1.8F * scale, 10.0F * scale, 0xF2F0EC, 0.8F, 0.002F);
	}

	/** True when the missile stands (or stood) on a launch pad, whose flame trench points along +X. */
	private static boolean onPad(MissileEntity missile) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.level.getBlockState(BlockPos.containing(missile.getLaunchPos())).is(ModRegistry.LAUNCH_PAD);
	}

	/**
	 * Launch pad at ignition: the deluge system floods the table with water, and the exhaust, turned
	 * into a wall of steam, is blasted down the flame trench and thrown up by the deflector at its end.
	 */
	private static void padIgnition(double x, double y, double z, int age, float scale) {
		// the steam and smoke blasted down the trench and up off the deflector hang about for minutes
		if (age % 2 == 0) {
			SmokeField.burst(new Vec3(x + 4.0, y + 0.5, z), 2, 2.0 * scale, new Vec3(0.8, 0.12, 0), 0.6, 2200,
				2.0F * scale, 12.0F * scale, 0xF4F4F2, 0.8F, 0.003F);
		}
		int plume = 5 + Math.min(14, age / 2);
		for (int i = 0; i < plume; i++) {
			double speed = 1.1 + ClientEffects.rand() * 1.1;
			CloudParticle p = ClientEffects.cloud(false, x + 0.6 + ClientEffects.rand() * 0.8, y - 0.1, z + ClientEffects.gauss() * 0.3,
				speed, 0.12 + ClientEffects.rand() * 0.35, ClientEffects.gauss() * 0.12);
			if (p != null) {
				p.configure(160 + (int) (ClientEffects.rand() * 100), 1.6F * scale, 9.0F * scale, 0xF6F6F4, 0xB4B2AE, 0.9F)
					.physics(0.93F, 0.007F)
					.shade(0.78F + ClientEffects.rand() * 0.25F)
					.turbulence(0.03F);
			}
		}
		// fire licking out of the trench mouth
		for (int i = 0; i < 2; i++) {
			CloudParticle f = ClientEffects.cloud(true, x + 1.0 + ClientEffects.rand() * 2.5, y, z + ClientEffects.gauss() * 0.3, 1.2, 0.15, 0);
			if (f != null) {
				f.configure(10 + (int) (ClientEffects.rand() * 8), 1.0F * scale, 2.6F * scale, 0xFFE8B0, 0xFF6010, 0.95F).physics(0.85F, 0.0F);
			}
		}
		// rainbirds: the two water cannons on the deck hosing the table
		for (int side = -1; side <= 1; side += 2) {
			double nx = x + 1.7;
			double nz = z + side * 2.4;
			for (int i = 0; i < 3; i++) {
				ClientEffects.vanilla(ParticleTypes.SPLASH, nx, y + 0.8, nz, -0.45 + ClientEffects.gauss() * 0.05, 0.25, -side * 0.55 + ClientEffects.gauss() * 0.05);
			}
			CloudParticle w = ClientEffects.cloud(false, nx, y + 0.8, nz, -0.3, 0.12, -side * 0.38);
			if (w != null) {
				w.configure(25 + (int) (ClientEffects.rand() * 15), 0.4F * scale, 2.0F * scale, 0xFFFFFF, 0xDDE6EA, 0.7F).physics(0.92F, -0.02F);
			}
		}
		// deluge: water gushing from the ring nozzles, flashing to steam around the table
		for (int i = 0; i < 6; i++) {
			double a = ClientEffects.rand() * Mth.TWO_PI;
			double nx = x + Math.cos(a) * 0.9;
			double nz = z + Math.sin(a) * 0.9;
			ClientEffects.vanilla(ParticleTypes.SPLASH, nx, y + 0.1, nz, Math.cos(a) * 0.4, 0.3, Math.sin(a) * 0.4);
			ClientEffects.vanilla(ParticleTypes.FALLING_WATER, nx, y + 0.3, nz, 0, 0, 0);
		}
		for (int i = 0; i < 3; i++) {
			double a = ClientEffects.rand() * Mth.TWO_PI;
			CloudParticle s = ClientEffects.cloud(false, x + Math.cos(a) * 1.2, y + 0.2, z + Math.sin(a) * 1.2, Math.cos(a) * 0.35, 0.08, Math.sin(a) * 0.35);
			if (s != null) {
				s.configure(90 + (int) (ClientEffects.rand() * 50), 1.2F * scale, 5.0F * scale, 0xFFFFFF, 0xE2E2E0, 0.7F).physics(0.9F, 0.006F);
			}
		}
	}

	private static void ignition(MissileEntity missile, float scale) {
		double x = missile.getX();
		double y = missile.getY();
		double z = missile.getZ();
		int age = missile.clientStateAge;
		boolean pad = onPad(missile);
		if (pad) {
			padIgnition(x, y, z, age, scale);
		}
		int count = (4 + Math.min(14, age / 2)) / (pad ? 3 : 1);

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

		SmokeField.trail(missile.getId() * 4 + 1, nozzle.subtract(dir.scale(1.0 + MissileMesh.CRUISE_BOOSTER_LENGTH)),
			SmokeField.Style.smallRocket(1.0F), missile.isBoosterBurning() ? 1.0F : 0.0F);
		if (missile.isBoosterBurning()) {
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

	/** A launch gone wrong: tumbling with the motor still roaring (thick exhaust), or dead and trailing black smoke. */
	private static void failedFlight(MissileEntity missile, float scale) {
		Vec3 nozzle = missile.position();
		Vec3 dir = missile.getNoseDirection(1.0F);
		boolean burning = missile.isBoosterBurning();
		if (burning) {
			SmokeField.trail(missile.getId() * 4 + 2, nozzle.subtract(dir.scale(1.0)), SmokeField.Style.exhaust(scale), 1.0F);
			nozzleFire(nozzle.x, nozzle.y, nozzle.z, dir, scale, 3);
		} else {
			SmokeField.trail(missile.getId() * 4 + 2, nozzle.add(dir.scale(missile.getMissileType().length * 0.4)),
				new SmokeField.Style(0.9F * scale, 3.0F * scale, 1600, 0x2C2A28, 0.75F, 0.3F, 0.002F, 1.0F), 1.0F);
			Vec3 fire = nozzle.add(dir.scale(missile.getMissileType().length * 0.4));
			CloudParticle glow = ClientEffects.cloud(true, fire.x, fire.y, fire.z, 0, 0.02, 0);
			if (glow != null) {
				glow.configure(8, 0.6F * scale, 0.3F, 0xFFB060, 0xFF5020, 0.7F);
			}
		}
	}

	private static void flight(Minecraft mc, MissileEntity missile, float scale) {
		if (missile.getFailure() != MissileEntity.FAIL_NONE) {
			failedFlight(missile, scale);
			return;
		}
		if (missile.getMissileType().isCruise()) {
			cruiseFlight(mc, missile, scale);
			return;
		}
		MissileTrajectory path = missile.getTrajectory();
		int age = missile.clientStateAge;
		Vec3 dir = path.direction(age);
		MissileType type = missile.getMissileType();
		if (missile.getBusWarheads() >= 0) {
			// the post-boost vehicle: no main engine, just puffs from its attitude thrusters
			Vec3 bus = missile.position().add(dir.scale(9.4 * type.scale));
			if (age % 3 == 0) {
				Vec3 side = new Vec3(ClientEffects.gauss(), ClientEffects.gauss(), ClientEffects.gauss()).normalize();
				CloudParticle p = ClientEffects.cloud(false, bus.x, bus.y, bus.z, side.x * 0.15, side.y * 0.15, side.z * 0.15);
				if (p != null) {
					p.configure(30, 0.3F, 1.2F, 0xFFFFFF, 0xDDDDDD, 0.5F).physics(0.9F, 0.0F).wind(0.0F);
				}
			}
			missile.lastNozzlePos = null;
			return;
		}
		// staging: the stack loses its lower stages, the flame comes from the stage still burning
		int dropped = MissileStages.dropped(type, path, age);
		Vec3 nozzle = missile.position().add(dir.scale(MissileStages.bottom(type, dropped) * type.scale));
		if (dropped > missile.clientStagesSeen) {
			missile.clientStagesSeen = dropped;
			// separation: the joint's charges fire, a ring of smoke, the next stage lights with a flash
			for (int i = 0; i < 30; i++) {
				double a = ClientEffects.rand() * Mth.TWO_PI;
				Vec3 out = new Vec3(Math.cos(a), 0, Math.sin(a));
				CloudParticle p = ClientEffects.cloud(false, nozzle.x, nozzle.y, nozzle.z, out.x * 0.5, out.y * 0.5 - dir.y * 0.2, out.z * 0.5);
				if (p != null) {
					p.configure(70 + (int) (ClientEffects.rand() * 40), 0.8F * scale, 4.0F * scale, 0xF4F2EE, 0xB8B4AE, 0.8F).physics(0.9F, 0.0F);
				}
			}
			for (int i = 0; i < 10; i++) {
				CloudParticle f = ClientEffects.cloud(true, nozzle.x, nozzle.y, nozzle.z, ClientEffects.gauss() * 0.3, ClientEffects.gauss() * 0.3, ClientEffects.gauss() * 0.3);
				if (f != null) {
					f.configure(6 + (int) (ClientEffects.rand() * 6), 1.5F * scale, 4.0F * scale, 0xFFF4D0, 0xFF7020, 1.0F).physics(0.8F, 0.0F);
				}
			}
			missile.lastNozzlePos = nozzle;
		}
		Vec3 prev = missile.lastNozzlePos != null && missile.lastSeenState == MissileEntity.FLIGHT ? missile.lastNozzlePos : nozzle;
		missile.lastNozzlePos = nozzle;

		double speed = path.velocity(age).length();
		// the exhaust trail: a thick, billowing column that hangs in the air for minutes, kinks and
		// spreads in the wind; high up it turns into the contrail instead
		float exhaust = missile.isBoosterBurning() ? (float) (1.0 - 0.75 * Contrails.altitudeFactor(nozzle.y)) : 0.0F;
		SmokeField.trail(missile.getId() * 4 + 1, nozzle.subtract(dir.scale(1.5)), SmokeField.Style.exhaust(scale), exhaust);
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
			if (onPad(missile)) {
				// still roaring out of the flame trench and boiling up off the deflector
				padIgnition(pad.x, pad.y, pad.z, 30, scale * (1.0F - age / 70.0F));
			}
			double sea = age < 30 ? waterSurfaceBelow(mc, pad) : Double.NaN;
			if (!Double.isNaN(sea) && pad.y - sea < 25.0) {
				// lit just above the sea: the exhaust hits the water and flashes it to steam
				for (int i = 0; i < 6; i++) {
					double a = ClientEffects.rand() * Mth.TWO_PI;
					double sp = 0.4 + ClientEffects.rand() * 0.6;
					CloudParticle p = ClientEffects.cloud(false, pad.x + Math.cos(a), sea + 0.3, pad.z + Math.sin(a), Math.cos(a) * sp, 0.08 + ClientEffects.rand() * 0.1, Math.sin(a) * sp);
					if (p != null) {
						p.configure(160 + (int) (ClientEffects.rand() * 80), 1.5F * scale, 8.0F * scale, 0xFFFFFF, 0xD6DADC, 0.8F).physics(0.94F, 0.006F).turbulence(0.02F);
					}
				}
			}
			// the launch cloud: rolls out over the ground and hangs over the site for minutes
			SmokeField.burst(new Vec3(pad.x, pad.y + 0.8, pad.z), onPad(missile) ? 2 : 4, 2.5 * scale, Vec3.ZERO, 0.9, 2400,
				2.5F * scale, 13.0F * scale, 0xE2DED8, 0.82F, 0.0015F);
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
			if (!interceptor.clientLaunchShown) {
				interceptor.clientLaunchShown = true;
				// back to where it left the launcher (the client sees it a tick or two after launch)
				double flown = interceptor.tickCount <= 1 ? 0.0 : InterceptorEntity.reach(InterceptorEntity.START_SPEED, interceptor.tickCount - 1);
				if (flown < 30.0) {
					ClientEffects.canisterLaunch(pos.subtract(dir.scale(flown)), dir, interceptor.getLauncher());
				}
			}
			// the interceptor's motor trail: a thin white column that hangs where it climbed
			SmokeField.trail(entity.getId() * 4 + 1, pos.subtract(dir.scale(0.8)), SmokeField.Style.smallRocket(0.6F), 1.0F);
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
