package de.rcm.ballistic.client.sound;

import de.rcm.ballistic.entity.MissileEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One-shot launch sound (ignition roar, its low rumble) that rides along with the missile instead of
 * staying behind on the pad, so it is always heard from where the rocket actually is. When the missile is
 * gone (it has hit), the sound dies with it in a few ticks - a short flight must not go on roaring after the
 * impact.
 */
public class MissileFollowSound extends AbstractTickableSoundInstance {
	private final MissileEntity missile;
	private final double audibleRange;
	private final float falloffPower;
	private Vec3 last;
	/** Fly-by sounds stop the moment the missile is gone; launch sounds fade out over a few ticks. */
	private boolean stopWithMissile;
	/** Ticks the sound has been fading since the missile went. */
	private int fading;

	public MissileFollowSound(MissileEntity missile, SoundEvent sound, float pitch, double audibleRange, float falloffPower) {
		this(missile, sound, pitch, audibleRange, falloffPower, false);
	}

	public MissileFollowSound(MissileEntity missile, SoundEvent sound, float pitch, double audibleRange, float falloffPower, boolean stopWithMissile) {
		super(sound, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
		this.stopWithMissile = stopWithMissile;
		this.missile = missile;
		this.audibleRange = audibleRange;
		this.falloffPower = falloffPower;
		this.looping = false;
		this.delay = 0;
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.pitch = pitch;
		this.last = missile.position();
		this.x = this.last.x;
		this.y = this.last.y;
		this.z = this.last.z;
		this.volume = this.volumeAt(this.last);
	}

	private float volumeAt(Vec3 pos) {
		double d = Minecraft.getInstance().gameRenderer.getMainCamera().position().distanceTo(pos);
		return (float) Math.pow(Mth.clamp(1.0 - d / this.audibleRange, 0.0, 1.0), this.falloffPower);
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public void tick() {
		if (this.stopWithMissile && this.missile.isRemoved()) {
			this.stop(); // it has hit: the fly-by is over the moment it explodes
			return;
		}
		if (!this.missile.isRemoved()) {
			this.last = this.missile.position();
		}
		this.x = this.last.x;
		this.y = this.last.y;
		this.z = this.last.z;
		this.volume = this.volumeAt(this.last);
		if (this.missile.isRemoved()) {
			// gone: the roar dies away quickly instead of playing on over the explosion
			this.fading++;
			this.volume *= Math.max(0.0F, 1.0F - this.fading / 6.0F);
			if (this.fading >= 6) {
				this.stop();
			}
		}
	}
}
