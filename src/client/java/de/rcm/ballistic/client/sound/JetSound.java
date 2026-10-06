package de.rcm.ballistic.client.sound;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.JetEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Engine roar of the strike jet. Audible from far away, with a Doppler shift: high and rising as
 * it comes at you, dropping to a low rumble once it has passed. The afterburner adds volume.
 */
public class JetSound extends AbstractTickableSoundInstance {
	private static final double AUDIBLE_RANGE = 700.0;
	/** Speed of sound in blocks per tick (343 m/s). */
	private static final double SOUND_SPEED = 17.15;

	private final JetEntity jet;
	private int age;

	public JetSound(JetEntity jet) {
		super(ModRegistry.JET_FIGHTER, SoundSource.HOSTILE, SoundInstance.createUnseededRandom());
		this.jet = jet;
		this.looping = true;
		this.delay = 0;
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.volume = 0.01F;
		this.x = jet.getX();
		this.y = jet.getY();
		this.z = jet.getZ();
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public void tick() {
		if (this.jet.isRemoved()) {
			this.stop();
			return;
		}
		this.age++;
		this.x = this.jet.getX();
		this.y = this.jet.getY();
		this.z = this.jet.getZ();

		Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		Vec3 toEar = ear.subtract(this.jet.position());
		double d = toEar.length();
		float falloff = (float) Math.pow(Mth.clamp(1.0 - d / AUDIBLE_RANGE, 0.0, 1.0), 1.6);
		float ramp = Mth.clamp(this.age / 20.0F, 0.0F, 1.0F);
		this.volume = falloff * ramp * (this.jet.isAfterburner() ? 1.0F : 0.8F);

		double approach = d > 1.0E-3 ? this.jet.getDir().scale(JetEntity.SPEED).dot(toEar.scale(1.0 / d)) : 0.0;
		float doppler = (float) (SOUND_SPEED / Math.max(4.0, SOUND_SPEED - approach));
		this.pitch = Mth.clamp(0.85F * doppler, 0.5F, 1.6F);
	}
}
