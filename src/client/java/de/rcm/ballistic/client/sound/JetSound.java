package de.rcm.ballistic.client.sound;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.JetEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * One layer of the strike jet's engine noise, played with real sound propagation: every tick the
 * layer looks back through the jet's flight path for the point whose sound is reaching the listener
 * right now (it left there distance / 343 m/s ago). The sound comes from that retarded position, its
 * Doppler shift follows from how fast the jet was closing in back then, and nothing is heard until
 * the first sound arrives - so you see the jet before you hear it, and a supersonic jet is silent
 * until it has passed.
 * <p>
 * Three layers are crossfaded: a close, crackling full-range roar, a dull low rumble that carries
 * far, and the afterburner, which only plays while the burners are lit.
 */
public class JetSound extends AbstractTickableSoundInstance {
	public enum Layer {
		NEAR,
		FAR,
		AFTERBURNER,
		/** Chest-thumping low end close by (its own instance, so it adds to the per-sound volume cap). */
		SUB
	}

	private static final double AUDIBLE_RANGE = 1500.0;
	private static final int MAX_HISTORY = 400;

	private final JetEntity jet;
	private final Layer layer;
	private final List<Sample> history = new ArrayList<>();
	private int age;

	/** Where the jet was at tick {@code time} of this sound's life. */
	private record Sample(int time, Vec3 pos, boolean afterburner) {
	}

	public JetSound(JetEntity jet, Layer layer) {
		super(sound(layer), SoundSource.HOSTILE, SoundInstance.createUnseededRandom());
		this.jet = jet;
		this.layer = layer;
		this.looping = true;
		this.delay = 0;
		this.attenuation = SoundInstance.Attenuation.NONE;
		this.volume = 0.0F;
		this.x = jet.getX();
		this.y = jet.getY();
		this.z = jet.getZ();
	}

	private static SoundEvent sound(Layer layer) {
		return switch (layer) {
			case NEAR -> ModRegistry.JET_FIGHTER;
			case FAR -> ModRegistry.JET_FIGHTER_FAR;
			case AFTERBURNER -> ModRegistry.JET_AFTERBURNER;
			case SUB -> ModRegistry.JET_SUB;
		};
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public void tick() {
		this.age++;
		if (!this.jet.isRemoved()) {
			this.history.add(new Sample(this.age, this.jet.position(), this.jet.isAfterburner()));
			if (this.history.size() > MAX_HISTORY) {
				this.history.remove(0);
			}
		}
		Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		int n = this.history.size();
		// newest emission whose sound has already travelled to the listener
		int found = -1;
		for (int i = n - 1; i >= 0; i--) {
			Sample sample = this.history.get(i);
			if (sample.pos().distanceTo(ear) <= (this.age - sample.time() + 1) * JetEntity.SOUND_SPEED) {
				found = i;
				break;
			}
		}
		if (found < 0) {
			// nothing has arrived yet; once the jet is gone and its last sound has passed, stop
			this.volume = 0.0F;
			if (this.jet.isRemoved() && (n == 0 || this.age - this.history.get(n - 1).time() > AUDIBLE_RANGE / JetEntity.SOUND_SPEED)) {
				this.stop();
			}
			return;
		}
		if (this.jet.isRemoved() && found == n - 1) {
			this.stop(); // the very last sound the jet made has reached us
			return;
		}

		Sample s = this.history.get(found);
		Vec3 p = s.pos();
		this.x = p.x;
		this.y = p.y;
		this.z = p.z;
		Vec3 toEar = ear.subtract(p);
		double d = toEar.length();

		// Doppler: how fast the jet was closing in on us when it made this sound
		Vec3 velocity = found + 1 < n ? this.history.get(found + 1).pos().subtract(p) : found > 0 ? p.subtract(this.history.get(found - 1).pos()) : Vec3.ZERO;
		double closing = d > 1.0E-3 ? velocity.dot(toEar.scale(1.0 / d)) : 0.0;
		float doppler = (float) (JetEntity.SOUND_SPEED / Math.max(3.0, JetEntity.SOUND_SPEED - closing));

		float falloff = (float) Math.pow(Mth.clamp(1.0 - d / AUDIBLE_RANGE, 0.0, 1.0), 1.3);
		float near = 1.0F - smoothstep(50.0F, 320.0F, (float) d);
		float weight = switch (this.layer) {
			case NEAR -> near;
			case FAR -> 0.35F + 0.65F * (1.0F - near);
			case AFTERBURNER -> s.afterburner() ? 1.0F - 0.5F * smoothstep(150.0F, 900.0F, (float) d) : 0.0F;
			case SUB -> 1.0F - smoothstep(60.0F, 450.0F, (float) d);
		};
		float ramp = Mth.clamp(this.age / 15.0F, 0.0F, 1.0F);
		this.volume = Mth.clamp(falloff * weight * ramp, 0.0F, 1.0F);
		float base = this.layer == Layer.FAR ? 0.9F : 1.0F;
		this.pitch = Mth.clamp(base * doppler, 0.5F, 2.0F);
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
