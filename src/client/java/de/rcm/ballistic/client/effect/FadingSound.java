package de.rcm.ballistic.client.effect;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * A sound heard everywhere alike (no position) with its own envelope: it swells in over {@code attack}
 * ticks, holds, and dies away over {@code release} ticks before it stops - so a long recording can be
 * used for just its opening, without being cut off hard or droning on.
 */
public final class FadingSound extends AbstractTickableSoundInstance {
	private final float peak;
	private final int attack;
	private final int hold;
	private final int release;
	private int age;

	public FadingSound(SoundEvent sound, float volume, float pitch, int attack, int hold, int release) {
		super(sound, SoundSource.MASTER, SoundInstance.createUnseededRandom());
		this.peak = volume;
		this.pitch = pitch;
		this.attack = Math.max(1, attack);
		this.hold = hold;
		this.release = Math.max(1, release);
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.relative = true;
		this.looping = false;
		this.volume = this.envelope(0);
	}

	private float envelope(int t) {
		if (t < this.attack) {
			return this.peak * Mth.clamp(t / (float) this.attack, 0.02F, 1.0F);
		}
		if (t < this.attack + this.hold) {
			return this.peak;
		}
		float x = Mth.clamp((t - this.attack - this.hold) / (float) this.release, 0.0F, 1.0F);
		// an even, natural die-away rather than a straight ramp
		return this.peak * (1.0F - x) * (1.0F - x);
	}

	@Override
	public void tick() {
		this.age++;
		this.volume = this.envelope(this.age);
		if (this.age >= this.attack + this.hold + this.release) {
			this.stop();
		}
	}
}
