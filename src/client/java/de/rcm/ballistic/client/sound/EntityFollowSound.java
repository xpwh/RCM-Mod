package de.rcm.ballistic.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * One-shot sound that rides along with an entity (a falling bomb's whistle) and stops the moment the
 * entity is gone, so it is always heard from where the thing actually is - never after the impact.
 */
public class EntityFollowSound extends AbstractTickableSoundInstance {
	private final Entity entity;
	private final double audibleRange;

	public EntityFollowSound(Entity entity, SoundEvent sound, float pitch, double audibleRange) {
		super(sound, SoundSource.HOSTILE, SoundInstance.createUnseededRandom());
		this.entity = entity;
		this.audibleRange = audibleRange;
		this.looping = false;
		this.delay = 0;
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.pitch = pitch;
		this.update();
	}

	private void update() {
		this.x = this.entity.getX();
		this.y = this.entity.getY();
		this.z = this.entity.getZ();
		double d = Minecraft.getInstance().gameRenderer.getMainCamera().position().distanceTo(this.entity.position());
		this.volume = (float) Mth.clamp(1.0 - d / this.audibleRange, 0.0, 1.0);
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public void tick() {
		if (this.entity.isRemoved()) {
			this.stop();
			return;
		}
		this.update();
	}
}
