package de.rcm.ballistic.client.sound;

import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.MissileType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import java.util.function.Predicate;
import net.minecraft.world.phys.Vec3;

/**
 * Looping rocket roar glued to the missile's nozzle. Distance falloff is computed manually so
 * the engine stays audible far beyond the vanilla 16-block attenuation range.
 */
public class MissileEngineSound extends AbstractTickableSoundInstance {
	private final MissileEntity missile;
	private final double audibleRange;
	private final float falloffPower;
	private final Predicate<MissileEntity> active;
	private int age;

	/**
	 * @param audibleRange distance at which this layer fades out completely
	 * @param falloffPower higher = layer dies off faster with distance (crackle is a close-range sound)
	 */
	public MissileEngineSound(MissileEntity missile, SoundEvent sound, double audibleRange, float falloffPower, Predicate<MissileEntity> active) {
		super(sound, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
		this.missile = missile;
		this.audibleRange = audibleRange;
		this.falloffPower = falloffPower;
		this.active = active;
		this.looping = true;
		this.delay = 0;
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.volume = 0.01F;
		this.pitch = this.basePitch();
		this.x = missile.getX();
		this.y = missile.getY();
		this.z = missile.getZ();
	}

	private float basePitch() {
		var type = this.missile.getMissileType();
		if (type.model == MissileType.Model.DRONE) {
			return 0.55F; // the drone's two-stroke drone: low, buzzing "moped" sound
		}
		if (type.isCruise()) {
			return 1.15F;
		}
		return type.isNuclear() ? 0.95F - 0.15F * type.scale : 0.95F;
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public void tick() {
		if (this.missile.isRemoved() || !this.active.test(this.missile)) {
			this.stop();
			return;
		}
		this.age++;
		this.x = this.missile.getX();
		this.y = this.missile.getY();
		this.z = this.missile.getZ();

		Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		double d = ear.distanceTo(this.missile.position());
		float falloff = (float) Math.pow(Mth.clamp(1.0 - d / this.audibleRange, 0.0, 1.0), this.falloffPower);
		float ramp = Mth.clamp(this.age / 25.0F, 0.0F, 1.0F);
		this.volume = Math.max(0.0F, falloff * ramp);

		// Slight pitch drop as it climbs away; rough Doppler-ish feel.
		float basePitch = this.basePitch();
		this.pitch = basePitch * (float) Mth.clamp(1.0 - d / 4000.0, 0.75, 1.0);
	}
}
