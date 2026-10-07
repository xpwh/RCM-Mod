package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.client.sound.JetSound;
import de.rcm.ballistic.entity.JetEntity;
import de.rcm.ballistic.entity.JetType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Client side of the strike jet: engine sound layers, exhaust trail, wingtip vortices, sonic boom. */
public final class JetClientTicker {
	private JetClientTicker() {
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
		double tailBack = switch (type) { case STRIKE -> 7.3; case WARTHOG -> 3.9; case SPIRIT -> 2.7; case GUNSHIP -> 1.0; case REAPER -> 4.6; case APACHE -> 2.6; };
		double tailSide = switch (type) { case STRIKE -> 0.5; case WARTHOG -> 1.25; case SPIRIT -> 2.6; case GUNSHIP -> 7.6; case REAPER -> 0.0; case APACHE -> 1.1; };
		double tailUp = switch (type) { case STRIKE -> 0.0; case WARTHOG -> 1.05; case SPIRIT -> 0.3; case GUNSHIP -> 1.3; case REAPER -> 0.0; case APACHE -> 1.2; };
		double tipSpan = switch (type) { case STRIKE -> 5.3; case WARTHOG -> 8.7; case SPIRIT -> 11.8; case GUNSHIP -> 16.0; case REAPER -> 8.6; case APACHE -> 0.0; };

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
		// wingtip vortices while pulling hard (banked turns, the climb-out)
		boolean jetAircraft = type == JetType.STRIKE || type == JetType.WARTHOG || type == JetType.SPIRIT;
		if (jetAircraft && (Math.abs(jet.getBank()) > 0.35F || burner && jet.getMach() < 1.1F)) {
			for (int s = -1; s <= 1; s += 2) {
				Vec3 tip = center.subtract(dir.scale(type == JetType.SPIRIT ? 4.0 : 3.4)).add(side.scale(tipSpan * s));
				CloudParticle p = ClientEffects.cloud(false, tip.x, tip.y, tip.z, 0, 0, 0);
				if (p != null) {
					p.configure(14 + (int) (ClientEffects.rand() * 8), 0.25F, 0.6F, 0xF4F4F4, 0xE8E8E8, 0.45F).physics(0.9F, 0.0F);
				}
			}
		}

		// A-10 cannon: the burst is heard only once its sound has travelled to us (you see the impacts
		// first, then the BRRRT rolls in), and loud: two layered instances, never distance-faded away
		boolean firing = jet.isFiring();
		Vec3 earNow = mc.gameRenderer.getMainCamera().position();
		if (firing && !jet.clientWasFiring) {
			jet.clientGunSoundFrom = center.add(dir.scale(7.5));
			jet.clientGunSoundIn = (int) (jet.clientGunSoundFrom.distanceTo(earNow) / JetEntity.SOUND_SPEED);
		}
		jet.clientWasFiring = firing;
		if (jet.clientGunSoundIn >= 0 && jet.clientGunSoundIn-- == 0) {
			double d = jet.clientGunSoundFrom.distanceTo(earNow);
			float volume = (float) Math.max(0.35, 1.25 - d / 1600.0);
			ClientEffects.playDistant(mc, ModRegistry.A10_GUN, jet.clientGunSoundFrom, volume, 1.0F);
			ClientEffects.playDistant(mc, ModRegistry.A10_GUN, jet.clientGunSoundFrom, volume, 0.985F);
			ClientEffects.addShake((float) Math.max(0.0, 1.1 - d / 300.0));
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
