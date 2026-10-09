package de.rcm.ballistic.client.sound;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.FighterEntity;
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
 * The engines of a flyable F-35 or F-22, from real recordings (F/A-18 and F-16 engines, a CF-18 on
 * afterburner). Outside, the sound travels at the speed of sound: each layer plays from the point of
 * the flight path whose sound is reaching you now, Doppler-shifted by how fast the jet was closing in
 * then - you see a jet before you hear it, and a supersonic one passes in silence until its boom. The
 * F-35's single F135 sounds deeper and rougher than the F-22's twin F119s. Inside, the pilot hears the
 * muffled cockpit roar that rises with the throttle, and the afterburner rumble behind him.
 */
public class FighterSound extends AbstractTickableSoundInstance {
	public enum Layer {
		NEAR,
		FAR,
		AFTERBURNER,
		SUB,
		COCKPIT,
		/** Airflow over the canopy, rising with speed. */
		WIND,
		/** The missile seeker's growl while it searches and tracks. */
		GROWL,
		/** The steady tone of a lock. */
		LOCK
	}

	private static final double AUDIBLE_RANGE = 1600.0;
	private static final int MAX_HISTORY = 400;

	private final FighterEntity jet;
	private final Layer layer;
	private final List<Sample> history = new ArrayList<>();
	private int age;

	private record Sample(int time, Vec3 pos, boolean afterburner, float throttle) {
	}

	public FighterSound(FighterEntity jet, Layer layer) {
		super(sound(layer), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
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
			case FAR, COCKPIT -> ModRegistry.JET_FIGHTER_FAR;
			case AFTERBURNER -> ModRegistry.JET_AFTERBURNER;
			case SUB -> ModRegistry.JET_SUB;
			case WIND -> ModRegistry.JET_WIND;
			case GROWL -> ModRegistry.JET_GROWL;
			case LOCK -> ModRegistry.JET_LOCK;
		};
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public void tick() {
		this.age++;
		Minecraft mc = Minecraft.getInstance();
		boolean inside = mc.player != null && this.jet.pilot() == mc.player;
		float enginePitch = this.jet.type().enginePitch;
		// starting up, the roar fades in under the start-up sound as the engine reaches idle
		float spool = this.jet.spool();
		float running = smoothstep(0.7F, 1.0F, spool);
		if (this.layer == Layer.WIND || this.layer == Layer.GROWL || this.layer == Layer.LOCK) {
			if (this.jet.isRemoved() || !inside) {
				this.stop();
				return;
			}
			this.x = mc.player.getX();
			this.y = mc.player.getEyeY();
			this.z = mc.player.getZ();
			int seeker = de.rcm.ballistic.client.fighter.FighterClient.seeker();
			switch (this.layer) {
				case WIND -> {
					float v = Mth.clamp(this.jet.speed() / 30.0F, 0.0F, 1.0F);
					this.volume = 0.6F * v * v;
					this.pitch = 0.7F + 0.6F * v;
				}
				case GROWL -> {
					// a low growl while it searches, louder and higher once something is in the seeker
					this.volume = seeker == 1 ? 0.07F : seeker == 2 ? 0.22F : 0.0F;
					this.pitch = seeker == 2 ? 1.0F + 0.25F * de.rcm.ballistic.client.fighter.FighterClient.lockProgress() : 0.95F;
				}
				default -> {
					this.volume = seeker == 3 ? 0.25F : 0.0F;
					this.pitch = 1.0F;
				}
			}
			return;
		}
		if (this.layer == Layer.COCKPIT) {
			if (this.jet.isRemoved() || !inside) {
				this.stop();
				return;
			}
			this.x = mc.player.getX();
			this.y = mc.player.getEyeY();
			this.z = mc.player.getZ();
			float t = this.jet.throttle();
			this.volume = running * (this.jet.isCrashing() ? 0.15F : 0.35F + 0.4F * t + (this.jet.isAfterburner() ? 0.2F : 0.0F));
			this.pitch = Mth.clamp(enginePitch * (0.75F + 0.35F * t) * (0.7F + 0.3F * running), 0.5F, 2.0F);
			return;
		}
		if (!this.jet.isRemoved()) {
			this.history.add(new Sample(this.age, this.jet.position(), this.jet.isAfterburner(), this.jet.isCrashing() ? 0.0F : this.jet.throttle()));
			if (this.history.size() > MAX_HISTORY) {
				this.history.remove(0);
			}
		}
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		int n = this.history.size();
		int found = -1;
		for (int i = n - 1; i >= 0; i--) {
			Sample sample = this.history.get(i);
			if (sample.pos().distanceTo(ear) <= (this.age - sample.time() + 1) * FighterEntity.SOUND_SPEED) {
				found = i;
				break;
			}
		}
		if (found < 0) {
			this.volume = 0.0F;
			if (this.jet.isRemoved() && (n == 0 || this.age - this.history.get(n - 1).time() > AUDIBLE_RANGE / FighterEntity.SOUND_SPEED)) {
				this.stop();
			}
			return;
		}
		if (this.jet.isRemoved() && found == n - 1) {
			this.stop();
			return;
		}
		Sample s = this.history.get(found);
		Vec3 p = s.pos();
		this.x = p.x;
		this.y = p.y;
		this.z = p.z;
		Vec3 toEar = ear.subtract(p);
		double d = toEar.length();
		Vec3 velocity = found + 1 < n ? this.history.get(found + 1).pos().subtract(p) : found > 0 ? p.subtract(this.history.get(found - 1).pos()) : Vec3.ZERO;
		double closing = d > 1.0E-3 ? velocity.dot(toEar.scale(1.0 / d)) : 0.0;
		float doppler = (float) (FighterEntity.SOUND_SPEED / Math.max(3.0, FighterEntity.SOUND_SPEED - closing));
		float falloff = (float) Math.pow(Mth.clamp(1.0 - d / AUDIBLE_RANGE, 0.0, 1.0), 1.3);
		float near = 1.0F - smoothstep(50.0F, 320.0F, (float) d);
		float weight = switch (this.layer) {
			case NEAR -> near;
			case FAR -> 0.35F + 0.65F * (1.0F - near);
			case AFTERBURNER -> s.afterburner() ? 1.0F - 0.5F * smoothstep(150.0F, 900.0F, (float) d) : 0.0F;
			case SUB -> 1.0F - smoothstep(60.0F, 450.0F, (float) d);
			default -> 0.0F;
		};
		// idling on the ground it is a whine; at full power a roar
		float power = this.layer == Layer.AFTERBURNER ? 1.0F : 0.3F + 0.7F * s.throttle();
		float ramp = Mth.clamp(this.age / 15.0F, 0.0F, 1.0F) * running;
		this.volume = Mth.clamp(falloff * weight * ramp * power * (inside ? 0.12F : 1.0F), 0.0F, 1.0F);
		float base = (this.layer == Layer.FAR ? 0.9F : 1.0F) * enginePitch * (0.85F + 0.2F * s.throttle());
		this.pitch = Mth.clamp(base * (inside ? 1.0F : doppler), 0.5F, 2.0F);
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
