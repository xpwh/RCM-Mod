package de.rcm.ballistic.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * A loud sound from a fixed place (a cannon burst) that can be cut off at any moment: heard from
 * the direction of the source but placed right next to the listener, so it isn't faded away by
 * vanilla distance attenuation, and {@link #release()} ends it with a quick fade instead of a click.
 */
public class DistantBurstSound extends AbstractTickableSoundInstance {
	private final Vec3 source;
	private final float fullVolume;
	private int fading = -1;

	public DistantBurstSound(SoundEvent sound, Vec3 source, float volume, float pitch) {
		super(sound, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
		this.source = source;
		this.fullVolume = Math.min(1.0F, volume);
		this.volume = this.fullVolume;
		this.pitch = pitch;
		this.looping = false;
		this.delay = 0;
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.place();
	}

	private void place() {
		Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		Vec3 dir = this.source.subtract(ear);
		double len = dir.length();
		Vec3 at = len < 8.0 ? this.source : ear.add(dir.scale(8.0 / len));
		this.x = at.x;
		this.y = at.y;
		this.z = at.z;
	}

	/** The burst is over: fade out over two ticks. */
	public void release() {
		if (this.fading < 0) {
			this.fading = 2;
		}
	}

	@Override
	public void tick() {
		this.place();
		if (this.fading >= 0) {
			if (this.fading == 0) {
				this.stop();
				return;
			}
			this.volume = this.fullVolume * this.fading / 3.0F;
			this.fading--;
		}
	}
}
