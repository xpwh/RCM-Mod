package de.rcm.ballistic.client.drone;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.FpvDroneEntity;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * The drone's buzz: the four motors, higher and louder as they work harder. Heard from outside at
 * the drone; in the goggles the pilot hears the camera's own recording of it, not placed anywhere.
 */
public class DroneSound extends AbstractTickableSoundInstance {
	private final FpvDroneEntity drone;
	private final boolean goggles;
	private float throttle;

	public DroneSound(FpvDroneEntity drone, boolean goggles) {
		super(goggles ? ModRegistry.DRONE_FPV : ModRegistry.DRONE_BUZZ, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
		this.drone = drone;
		this.goggles = goggles;
		this.looping = true;
		this.delay = 0;
		if (goggles) {
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.x = 0.0;
			this.y = 0.0;
			this.z = 0.0;
		}
		this.update();
	}

	private void update() {
		this.throttle = Mth.lerp(0.25F, this.throttle, this.drone.getThrottle());
		if (!this.goggles) {
			this.x = this.drone.getX();
			this.y = this.drone.getY();
			this.z = this.drone.getZ();
		}
		boolean own = DroneClient.isLocalPilot(this.drone);
		float level = 0.45F + 0.55F * this.throttle;
		this.volume = this.goggles ? 0.55F * level : own ? 0.0F : level;
		this.pitch = (0.85F + 0.45F * this.throttle) * (this.drone.isRacer() ? 1.3F : 1.0F);
	}

	public void release() {
		this.stop();
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public void tick() {
		if (this.drone.isRemoved() || this.goggles && !DroneClient.isLocalPilot(this.drone)) {
			this.stop();
			return;
		}
		this.update();
	}
}
