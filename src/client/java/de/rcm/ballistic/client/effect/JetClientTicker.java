package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.client.sound.DistantBurstSound;
import de.rcm.ballistic.client.sound.EntityFollowSound;
import de.rcm.ballistic.client.sound.JetSound;
import de.rcm.ballistic.entity.JetEntity;
import de.rcm.ballistic.entity.JetType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Client side of the strike jet: engine sound layers, exhaust trail, wingtip vortices, sonic boom. */
public final class JetClientTicker {
	private JetClientTicker() {
	}

	/**
	 * A-10 cannon, cut to the real burst: the BRRRT (a real GAU-8 recording) starts when the sound of
	 * the first rounds reaches us - you see the impacts first - and stops exactly as long after the
	 * gun stops, then the rumble rolls away. Loud: two layered instances, never distance-faded away.
	 */
	private static void warthogGun(Minecraft mc, JetEntity jet, Vec3 center, Vec3 dir, Vec3 ear) {
		boolean firing = jet.isFiring();
		if (firing && !jet.clientWasFiring) {
			jet.clientGunSoundFrom = center.add(dir.scale(7.5));
			jet.clientGunSoundIn = (int) (jet.clientGunSoundFrom.distanceTo(ear) / JetEntity.SOUND_SPEED);
			jet.clientGunStopIn = -1;
		} else if (!firing && jet.clientWasFiring) {
			jet.clientGunStopIn = (int) (center.add(dir.scale(7.5)).distanceTo(ear) / JetEntity.SOUND_SPEED);
		}
		jet.clientWasFiring = firing;
		double d = jet.clientGunSoundFrom.distanceTo(ear);
		float volume = (float) Math.max(0.35, 1.25 - d / 1600.0);
		if (jet.clientGunSoundIn >= 0 && jet.clientGunSoundIn-- == 0) {
			DistantBurstSound a = new DistantBurstSound(ModRegistry.A10_GUN, jet.clientGunSoundFrom, volume, 1.0F);
			DistantBurstSound b = new DistantBurstSound(ModRegistry.A10_GUN, jet.clientGunSoundFrom, volume, 0.985F);
			mc.getSoundManager().play(a);
			mc.getSoundManager().play(b);
			jet.clientGunSounds = new DistantBurstSound[] {a, b};
			ClientEffects.addShake((float) Math.max(0.0, 1.1 - d / 300.0));
		}
		if (jet.clientGunSoundIn < 0 && jet.clientGunStopIn >= 0 && jet.clientGunStopIn-- == 0) {
			if (jet.clientGunSounds instanceof DistantBurstSound[] sounds) {
				for (DistantBurstSound s : sounds) {
					s.release();
				}
			}
			jet.clientGunSounds = null;
			ClientEffects.playDistant(mc, ModRegistry.A10_GUN_TAIL, jet.clientGunSoundFrom, volume, 1.0F);
		}
	}

	/** Recorded A-10 pass, started so that its loudest moment comes when the jet is closest to us. */
	private static void warthogFlyby(Minecraft mc, JetEntity jet, Vec3 center, Vec3 ear) {
		Vec3 v = new Vec3(jet.getX() - jet.xo, jet.getY() - jet.yo, jet.getZ() - jet.zo);
		double speed2 = v.lengthSqr();
		if (speed2 < 0.25 || jet.tickCount - jet.clientFlybyAt < 220) {
			return;
		}
		Vec3 rel = ear.subtract(center);
		double ticksToClosest = rel.dot(v) / speed2;
		double miss = rel.subtract(v.scale(ticksToClosest)).length();
		// the recording peaks 3.9 s (78 ticks) in; the sound itself needs miss / 17 ticks to arrive
		double lead = 78 - miss / JetEntity.SOUND_SPEED;
		if (miss < 160 && ticksToClosest > lead - 2 && ticksToClosest <= lead + 2) {
			jet.clientFlybyAt = jet.tickCount;
			mc.getSoundManager().play(new EntityFollowSound(jet, ModRegistry.A10_FLYBY, 1.0F, 420.0));
		}
	}

	public static void tick(JetEntity jet) {
		Minecraft mc = Minecraft.getInstance();
		if (jet.tickCount == 1) { // the client entity is created when it comes into tracking range
			for (JetSound.Layer layer : JetSound.Layer.values()) {
				mc.getSoundManager().play(new JetSound(jet, layer));
			}
		}
		Vec3 dir = jet.getDir();
		Vec3 center = jet.position().add(0, 0.8, 0);
		boolean burner = jet.isAfterburner();
		JetType type = jet.getJetType();
		// nozzle position (behind, sideways, up) and wingtip half-span of each airframe
		double tailBack = switch (type) { case STRIKE -> 7.3; case WARTHOG -> 3.9; case SPIRIT -> 2.7; case GUNSHIP -> 2.1; case REAPER -> 4.6; case APACHE -> 2.6; };
		double tailSide = switch (type) { case STRIKE -> 0.5; case WARTHOG -> 1.25; case SPIRIT -> 2.6; case GUNSHIP -> 0.55; case REAPER -> 0.0; case APACHE -> 1.1; };
		double tailUp = switch (type) { case STRIKE -> 0.0; case WARTHOG -> 1.05; case SPIRIT -> 0.3; case GUNSHIP -> 1.4; case REAPER -> 0.0; case APACHE -> 1.2; };
		double tipSpan = switch (type) { case STRIKE -> 5.3; case WARTHOG -> 8.7; case SPIRIT -> 11.8; case GUNSHIP -> 0.0; case REAPER -> 8.6; case APACHE -> 0.0; };

		// hot exhaust haze behind both nozzles
		Vec3 side = new Vec3(-dir.z, 0, dir.x).normalize();
		for (int s = -1; s <= 1; s += 2) {
			if (!burner && jet.tickCount % 2 != 0) {
				continue;
			}
			Vec3 tail = center.subtract(dir.scale(tailBack)).add(side.scale(tailSide * s)).add(0, tailUp, 0);
			CloudParticle p = ClientEffects.cloud(false, tail.x, tail.y, tail.z, 0, 0, 0);
			if (p != null) {
				p.configure(30 + (int) (ClientEffects.rand() * 25), burner ? 1.0F : 0.5F, burner ? 3.0F : 1.8F, 0xB0B0B0, 0x959595, burner ? 0.3F : 0.2F)
					.physics(0.95F, 0.0F);
			}
		}
		// condensation trails high up: one per engine pair, merging and spreading behind the aircraft
		boolean jetAircraft = type == JetType.STRIKE || type == JetType.WARTHOG || type == JetType.SPIRIT;
		double contrail = jetAircraft ? Contrails.altitudeFactor(center.y) : 0.0;
		for (int s = -1; s <= 1; s += 2) {
			Vec3 tail = center.subtract(dir.scale(tailBack + 1.5)).add(side.scale(tailSide * s)).add(0, tailUp, 0);
			Contrails.trail(jet.getId() * 4 + (s + 1) / 2, tail, type == JetType.SPIRIT ? 1.3 : 0.9, contrail);
		}
		// wingtip vortices while pulling hard (banked turns, the climb-out)
		if (jetAircraft && (Math.abs(jet.getBank()) > 0.35F || burner && jet.getMach() < 1.1F)) {
			for (int s = -1; s <= 1; s += 2) {
				Vec3 tip = center.subtract(dir.scale(type == JetType.SPIRIT ? 4.0 : 3.4)).add(side.scale(tipSpan * s));
				CloudParticle p = ClientEffects.cloud(false, tip.x, tip.y, tip.z, 0, 0, 0);
				if (p != null) {
					p.configure(14 + (int) (ClientEffects.rand() * 8), 0.25F, 0.6F, 0xF4F4F4, 0xE8E8E8, 0.45F).physics(0.9F, 0.0F);
				}
			}
		}

		Vec3 earNow = mc.gameRenderer.getMainCamera().position();
		if (type == JetType.WARTHOG) {
			warthogGun(mc, jet, center, dir, earNow);
			warthogFlyby(mc, jet, center, earNow);
		}

		// sonic boom: heard the moment the Mach cone trailing the jet sweeps over the listener
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		float mach = jet.getMach();
		boolean inCone = false;
		if (mach > 1.0F) {
			Vec3 toEar = ear.subtract(center);
			double d = toEar.length();
			double coneHalfAngle = Math.asin(1.0 / mach);
			double angle = d > 1.0E-3 ? Math.acos(Math.max(-1.0, Math.min(1.0, dir.scale(-1).dot(toEar.scale(1.0 / d))))) : 0.0;
			inCone = angle < coneHalfAngle;
			if (inCone && !jet.clientInMachCone) {
				float volume = (float) (1.25 - d / 1400.0);
				ClientEffects.playDistant(mc, ModRegistry.JET_BOOM, center, volume, 0.95F + ClientEffects.rand() * 0.1F);
				ClientEffects.addShake((float) Math.max(0.0, 2.2 - d / 250.0));
			}
		}
		jet.clientInMachCone = inCone || jet.clientInMachCone && mach > 1.0F;
	}
}
