package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.client.sound.JetSound;
import de.rcm.ballistic.entity.JetEntity;
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

		// hot exhaust haze behind both nozzles
		Vec3 side = new Vec3(-dir.z, 0, dir.x).normalize();
		for (int s = -1; s <= 1; s += 2) {
			if (!burner && jet.tickCount % 2 != 0) {
				continue;
			}
			Vec3 tail = center.subtract(dir.scale(7.3)).add(side.scale(0.5 * s));
			CloudParticle p = ClientEffects.cloud(false, tail.x, tail.y, tail.z, 0, 0, 0);
			if (p != null) {
				p.configure(30 + (int) (ClientEffects.rand() * 25), burner ? 1.0F : 0.5F, burner ? 3.0F : 1.8F, 0xB0B0B0, 0x959595, burner ? 0.3F : 0.2F)
					.physics(0.95F, 0.0F);
			}
		}
		// wingtip vortices while pulling hard (banked turns, the climb-out)
		if (Math.abs(jet.getBank()) > 0.35F || burner && jet.getMach() < 1.1F) {
			for (int s = -1; s <= 1; s += 2) {
				Vec3 tip = center.subtract(dir.scale(3.4)).add(side.scale(5.3 * s));
				CloudParticle p = ClientEffects.cloud(false, tip.x, tip.y, tip.z, 0, 0, 0);
				if (p != null) {
					p.configure(14 + (int) (ClientEffects.rand() * 8), 0.25F, 0.6F, 0xF4F4F4, 0xE8E8E8, 0.45F).physics(0.9F, 0.0F);
				}
			}
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
