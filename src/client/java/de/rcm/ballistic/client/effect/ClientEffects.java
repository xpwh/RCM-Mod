package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.entity.MissileType.Warhead;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client-side explosion spectacle: flash, camera shake, delayed (speed-of-sound) audio,
 * fireballs that roll into black smoke, debris jets, embers, shock spheres, mushroom clouds.
 */
public final class ClientEffects {
	/** Blocks per tick: 343 m/s. */
	private static final double SPEED_OF_SOUND = 17.15;

	private static final List<Effect> EFFECTS = new ArrayList<>();
	private static final RandomSource RANDOM = RandomSource.create();

	private static float flash;
	private static float flashPrev;
	private static int flashColor = 0xFFFFFF;
	private static float shake;
	private static float shakePrev;
	/** 0 = normal hearing, 1 = completely deafened by a blast. */
	private static float deafness;
	private static int deafHold;

	private ClientEffects() {
	}

	// ------------------------------------------------------------------ public API

	public static void detonation(int warheadId, Vec3 pos) {
		Warhead[] all = Warhead.values();
		Warhead warhead = warheadId >= 0 && warheadId < all.length ? all[warheadId] : Warhead.HIGH_EXPLOSIVE;
		EFFECTS.add(switch (warhead) {
			case NUCLEAR -> new NukeEffect(pos, 1.0);
			case HYDROGEN -> new NukeEffect(pos, 1.6);
			case THERMOBARIC -> new ThermobaricEffect(pos);
			case BUNKER_BUSTER -> new BunkerEffect(pos);
			case CRUISE -> new BlastEffect(pos, 0.85);
			case CLUSTER -> new BlastEffect(pos, 0.6);
			case BOMBLET -> new BlastEffect(pos, 0.32);
			case CLUSTER_RELEASE, MIRV_RELEASE -> new ReleaseEffect(pos);
			case HYPERSONIC -> new BlastEffect(pos, 1.25);
			case MIRV -> new NukeEffect(pos, 1.0);
			case MIRV_WARHEAD -> new NukeEffect(pos, 0.62);
			case INTERCEPT -> new InterceptEffect(pos);
			case TACTICAL_NUKE -> new NukeEffect(pos, 0.62);
			case EMP -> new EmpEffect(pos);
			case INCENDIARY -> new BlastEffect(pos, 0.7);
			case INCENDIARY_RELEASE -> new IncendiaryReleaseEffect(pos);
			case ANTI_RADAR -> new BlastEffect(pos, 0.8);
			case DRONE -> new BlastEffect(pos, 0.55);
			case AERIAL_BOMB -> new BlastEffect(pos, 0.5);
			case TSAR -> new NukeEffect(pos, 2.2);
			case ANTIMATTER -> new AntimatterEffect(pos);
			case METEOR_IMPACT -> new BlastEffect(pos, 0.75);
			case METEOR -> mc -> true;
			default -> new BlastEffect(pos, 1.0);
		});
	}

	public static float flashAlpha(float partialTick) {
		return Mth.lerp(partialTick, flashPrev, flash);
	}

	public static int flashColor() {
		return flashColor;
	}

	public static float shake(float partialTick) {
		return Mth.lerp(partialTick, shakePrev, shake);
	}

	public static void addShake(float amount) {
		shake = Math.min(6.0F, Math.max(shake, amount));
	}

	/** Volume multiplier for all non-explosion sounds (1 = normal). */
	public static float hearing() {
		return 1.0F - 0.9F * deafness;
	}

	/** Blast trauma: muffles the world and makes the ears ring. */
	static void deafen(Minecraft mc, float strength, int holdTicks) {
		strength = Mth.clamp(strength, 0.0F, 1.0F);
		if (strength < 0.08F) {
			return;
		}
		if (strength > deafness) {
			deafness = strength;
		}
		deafHold = Math.max(deafHold, holdTicks);
		mc.getSoundManager().play(new SimpleSoundInstance(
			ModRegistry.EAR_RINGING.location(), SoundSource.MASTER, Math.min(1.0F, strength * 1.1F), 1.0F, RANDOM, false, 0, SoundInstance.Attenuation.NONE,
			0.0, 0.0, 0.0, true // relative to the listener: the ringing is inside your head
		));
	}

	public static void clear() {
		EFFECTS.clear();
		deafness = 0;
		deafHold = 0;
		flash = flashPrev = 0;
		shake = shakePrev = 0;
	}

	public static void tick(Minecraft mc) {
		flashPrev = flash;
		shakePrev = shake;
		flash = Math.max(0.0F, flash * 0.93F - 0.004F);
		shake = Math.max(0.0F, shake * 0.95F - 0.01F);
		if (deafHold > 0) {
			deafHold--;
		} else {
			deafness = Math.max(0.0F, deafness * 0.985F - 0.001F);
		}
		if (mc.level == null || mc.player == null) {
			EFFECTS.clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		Iterator<Effect> it = EFFECTS.iterator();
		while (it.hasNext()) {
			if (it.next().tick(mc)) {
				it.remove();
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	public static @Nullable CloudParticle cloud(boolean fire, double x, double y, double z, double dx, double dy, double dz) {
		Particle p = Minecraft.getInstance().particleEngine.createParticle(fire ? ModRegistry.FIRE : ModRegistry.SMOKE, x, y, z, dx, dy, dz);
		return p instanceof CloudParticle c ? c : null;
	}

	static void vanilla(ParticleOptions type, double x, double y, double z, double dx, double dy, double dz) {
		Minecraft.getInstance().particleEngine.createParticle(type, x, y, z, dx, dy, dz);
	}

	static void setFlash(float strength, int color) {
		if (strength > flash) {
			flash = Math.min(1.0F, strength);
			flashColor = color;
		}
	}

	/**
	 * Plays a sound positioned towards the source but close to the listener, so distance can be faked
	 * far beyond the vanilla attenuation range.
	 */
	static void playDistant(Minecraft mc, SoundEvent sound, Vec3 source, float volume, float pitch) {
		if (volume <= 0.01F) {
			return;
		}
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		Vec3 dir = source.subtract(ear);
		double len = dir.length();
		Vec3 at = len < 8 ? source : ear.add(dir.scale(8.0 / len));
		mc.getSoundManager().play(new SimpleSoundInstance(
			sound.location(), SoundSource.BLOCKS, Mth.clamp(volume, 0.0F, 1.0F), pitch, RANDOM, false, 0, SoundInstance.Attenuation.NONE, at.x, at.y, at.z, false
		));
	}

	static double distanceToCamera(Minecraft mc, Vec3 pos) {
		return mc.gameRenderer.getMainCamera().position().distanceTo(pos);
	}

	static double gauss() {
		return RANDOM.nextGaussian();
	}

	public static float rand() {
		return RANDOM.nextFloat();
	}

	static double groundY(Minecraft mc, double x, double fallback, double z) {
		if (mc.level == null) {
			return fallback;
		}
		int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
		return y <= mc.level.getMinY() ? fallback : y;
	}

	static @Nullable BlockState groundBlock(Minecraft mc, Vec3 pos) {
		if (mc.level == null) {
			return null;
		}
		BlockPos p = BlockPos.containing(pos.x, groundY(mc, pos.x, pos.y, pos.z) - 1, pos.z);
		BlockState state = mc.level.getBlockState(p);
		return state.isAir() ? null : state;
	}

	static int mix(int a, int b, float t) {
		t = Mth.clamp(t, 0, 1);
		int r = (int) Mth.lerp(t, a >> 16 & 255, b >> 16 & 255);
		int g = (int) Mth.lerp(t, a >> 8 & 255, b >> 8 & 255);
		int bl = (int) Mth.lerp(t, a & 255, b & 255);
		return r << 16 | g << 8 | bl;
	}

	interface Effect {
		/** @return true when finished */
		boolean tick(Minecraft mc);
	}

	// ------------------------------------------------------------------ building blocks

	/** Fireball whose puffs burn for a moment, then cool into black, sun-lit smoke that keeps rising. */
	static void fireball(Vec3 c, double radius, int count, float size, double speed, int life) {
		for (int i = 0; i < count; i++) {
			Vec3 v = new Vec3(gauss(), Math.abs(gauss()) * 0.9 + 0.25, gauss()).normalize();
			double r = radius * Math.sqrt(rand());
			Vec3 p = c.add(v.scale(r * 0.4));
			CloudParticle fire = cloud(true, p.x, p.y, p.z, v.x * speed * (0.5 + rand()), v.y * speed * (0.5 + rand()), v.z * speed * (0.5 + rand()));
			if (fire != null) {
				fire.configure(life + RANDOM.nextInt(life / 2 + 1), size * 0.5F, size * (1.4F + rand() * 0.6F), 0xFFF3C8, 0xE0500C, 1.0F)
					.physics(0.86F, 0.012F)
					.cooling(0.22F + rand() * 0.15F, mix(0x2A2622, 0x45403A, rand()))
					.litFrom(c.x, c.y, c.z)
					.turbulence(0.03F);
			}
		}
	}

	/** Dirt/rock streaks thrown out of the crater on ballistic arcs. */
	static void debrisJets(Minecraft mc, Vec3 c, int jets, double speed, float size) {
		BlockState ground = groundBlock(mc, c);
		for (int j = 0; j < jets; j++) {
			double a = rand() * Mth.TWO_PI;
			double elev = Math.toRadians(40 + rand() * 45);
			Vec3 dir = new Vec3(Math.cos(a) * Math.cos(elev), Math.sin(elev), Math.sin(a) * Math.cos(elev));
			double s = speed * (0.7 + rand() * 0.6);
			for (int k = 0; k < 7; k++) {
				double f = 0.35 + 0.65 * k / 6.0;
				CloudParticle p = cloud(false, c.x, c.y + 0.5, c.z, dir.x * s * f, dir.y * s * f, dir.z * s * f);
				if (p != null) {
					p.configure(70 + RANDOM.nextInt(40), size * 0.4F, size * (1.0F + rand()), 0x7A6A58, 0x5E5650, 0.85F)
						.physics(0.975F, -0.035F)
						.shade(0.75F + rand() * 0.3F);
				}
			}
			if (ground != null) {
				for (int k = 0; k < 4; k++) {
					double f = 0.5 + rand() * 0.6;
					vanilla(new BlockParticleOption(ParticleTypes.BLOCK, ground), c.x, c.y + 0.5, c.z, dir.x * s * f, dir.y * s * f, dir.z * s * f);
				}
			}
		}
	}

	/** Glowing embers raining back down. */
	static void embers(Vec3 c, int count, double speed) {
		for (int i = 0; i < count; i++) {
			Vec3 v = new Vec3(gauss(), Math.abs(gauss()) + 0.4, gauss()).normalize().scale(speed * (0.4 + rand()));
			CloudParticle p = cloud(true, c.x, c.y + 1, c.z, v.x, v.y, v.z);
			if (p != null) {
				p.configure(40 + RANDOM.nextInt(60), 0.25F + rand() * 0.25F, 0.15F, 0xFFE0A0, 0xFF5010, 1.0F).physics(0.97F, -0.03F);
			}
		}
	}

	/** Brief condensation shell racing outward right after the blast. */
	static void shockSphere(Vec3 c, double radius, int count) {
		for (int i = 0; i < count; i++) {
			Vec3 v = new Vec3(gauss(), Math.abs(gauss()) * 0.7, gauss()).normalize();
			Vec3 p = c.add(v.scale(radius));
			CloudParticle s = cloud(false, p.x, p.y, p.z, v.x * 0.9, v.y * 0.9, v.z * 0.9);
			if (s != null) {
				s.configure(10 + RANDOM.nextInt(6), (float) radius * 0.25F, (float) radius * 0.45F, 0xFFFFFF, 0xEDEDED, 0.28F).physics(0.85F, 0.0F).wind(0);
			}
		}
	}

	/** Dust skirt rolling outward along the ground. */
	static void groundRing(Minecraft mc, Vec3 c, double ring, int count, float size, double speed, int color) {
		for (int i = 0; i < count; i++) {
			double a = rand() * Mth.TWO_PI;
			double x = c.x + Math.cos(a) * ring;
			double z = c.z + Math.sin(a) * ring;
			double y = groundY(mc, x, c.y, z) + 0.6;
			CloudParticle p = cloud(false, x, y, z, Math.cos(a) * speed, 0.03 + rand() * 0.06, Math.sin(a) * speed);
			if (p != null) {
				p.configure(80 + RANDOM.nextInt(60), size * 0.5F, size * (1.0F + rand() * 0.5F), color, mix(color, 0x6E665C, 0.6F), 0.75F)
					.physics(0.93F, 0.003F)
					.litFrom(c.x, y - size, c.z)
					.turbulence(0.02F);
			}
		}
	}

	// ------------------------------------------------------------------ conventional blast

	static final class BlastEffect implements Effect {
		private final Vec3 pos;
		private final double scale;
		private final int soundDelay;
		private final double distance;
		private int age;

		BlastEffect(Vec3 pos, double scale) {
			this.pos = pos;
			this.scale = scale;
			Minecraft mc = Minecraft.getInstance();
			this.distance = distanceToCamera(mc, pos);
			this.soundDelay = (int) (this.distance / SPEED_OF_SOUND);
			setFlash((float) (Mth.clamp(0.8 - this.distance / (500.0 * scale), 0.0, 0.8) * Math.min(1.0, scale * 1.3)), 0xFFE6B0);
		}

		@Override
		public boolean tick(Minecraft mc) {
			int t = this.age++;
			double s = this.scale;
			Vec3 c = this.pos.add(0, 1, 0);
			if (t == this.soundDelay) {
				float pitch = (float) ((0.9F + rand() * 0.2F) / Math.pow(s, 0.25));
				if (this.distance < 170 * s) {
					float vol = (float) Math.min(1.0, s * 1.2);
					playDistant(mc, ModRegistry.EXPLOSION_NEAR, this.pos, vol, pitch);
					playDistant(mc, ModRegistry.EXPLOSION_NEAR, this.pos, vol, pitch * 0.97F); // doubled for real-life loudness
					playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, vol, pitch);
					playDistant(mc, ModRegistry.EXPLOSION_DEBRIS, this.pos, (float) ((1.0 - this.distance / 200.0) * s), 1.0F);
					deafen(mc, (float) ((1.0 - this.distance / (70.0 * s)) * Math.min(1.0, s * 1.2)), (int) (60 * s));
				} else if (this.distance < 700 * s) {
					float vol = (float) ((1.15 - this.distance / (900.0 * s)) * Math.min(1.0, s * 1.3));
					playDistant(mc, ModRegistry.EXPLOSION_MID, this.pos, vol, pitch);
					playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, vol * 0.85F, pitch * 0.9F);
				} else {
					float volume = (float) ((1.3 - this.distance / 1400.0) * Math.min(1.0, s * 1.3));
					playDistant(mc, ModRegistry.EXPLOSION_FAR, this.pos, volume, pitch);
					playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, volume * 0.8F, pitch * 0.9F);
				}
				addShake((float) Mth.clamp((3.0 - this.distance / 60.0) * s, 0.0, 3.0));
			}

			if (t == 0) {
				fireball(c, 5.0 * s, (int) (70 * s) + 8, (float) (6.0 * s) + 1.5F, 1.1 * s + 0.2, 34);
				debrisJets(mc, this.pos, (int) (16 * s) + 3, 1.6 * Math.sqrt(s), (float) (2.5 * s) + 0.8F);
				embers(c, (int) (60 * s) + 6, 0.9 * Math.sqrt(s));
				for (int i = 0; i < 40 * s; i++) {
					vanilla(ParticleTypes.LAVA, c.x + gauss() * 2 * s, c.y, c.z + gauss() * 2 * s, 0, 0, 0);
				}
				vanilla(s > 0.5 ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, c.x, c.y, c.z, 0, 0, 0);
			}
			if (t >= 1 && t <= 3 && s > 0.5) {
				shockSphere(c, (3 + t * 5) * s, (int) (30 * s));
			}
			// Ground shockwave ring of dust
			if (t < 12) {
				groundRing(mc, this.pos, (4 + t * 3.0) * s, (int) (34 * s) + 4, (float) (5.0 * s) + 1, 0.8 * s, 0xB8A890);
			}
			// Rising smoke column, fed by the burning crater
			if (t < 100 * s) {
				int n = (int) ((t < 20 ? 12 : 6) * s) + 1;
				for (int i = 0; i < n; i++) {
					double ox = gauss() * 3.0 * s;
					double oz = gauss() * 3.0 * s;
					CloudParticle p = cloud(false, c.x + ox, c.y + 1 + rand() * 5 * s, c.z + oz, ox * 0.02, (0.32 + rand() * 0.3) * Math.sqrt(s), oz * 0.02);
					if (p != null) {
						p.configure(180 + RANDOM.nextInt(100), (float) (3.5 * s) + 1, (float) ((10.0 + rand() * 4.0) * s) + 2, t < 18 ? 0x5C4434 : 0x4A4642, 0x2E2B28, 0.9F)
							.physics(0.97F, 0.003F)
							.litFrom(c.x, c.y + 4 * s + t * 0.3, c.z)
							.turbulence(0.03F);
					}
				}
				if (t < 60 && t % 3 == 0) {
					CloudParticle f = cloud(true, c.x + gauss() * 2 * s, c.y, c.z + gauss() * 2 * s, 0, 0.15, 0);
					if (f != null) {
						f.configure(30, (float) (2 * s) + 0.5F, (float) (4 * s) + 1, 0xFFC060, 0xC03010, 0.9F).cooling(0.5F, 0x302C28);
					}
				}
			}
			return t > Math.max(this.soundDelay, 100 * s) + 1;
		}
	}

	// ------------------------------------------------------------------ bunker buster

	/** Small entry blast, then the ground heaves and erupts a moment later. */
	static final class BunkerEffect implements Effect {
		private final Vec3 pos;
		private final double distance;
		private final int soundDelay;
		private int age;

		BunkerEffect(Vec3 pos) {
			this.pos = pos;
			Minecraft mc = Minecraft.getInstance();
			this.distance = distanceToCamera(mc, pos);
			this.soundDelay = (int) (this.distance / SPEED_OF_SOUND);
		}

		@Override
		public boolean tick(Minecraft mc) {
			int t = this.age++;
			Vec3 c = this.pos.add(0, 0.5, 0);
			if (t == 0) {
				fireball(c, 1.5, 14, 2.5F, 0.5, 20);
				vanilla(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 0, 0, 0);
			}
			if (t == this.soundDelay) {
				playDistant(mc, ModRegistry.EXPLOSION_FAR, this.pos, (float) (0.8 - this.distance / 1500.0), 1.3F);
			}
			int erupt = 12;
			if (t == erupt + this.soundDelay) {
				playDistant(mc, ModRegistry.EXPLOSION_BUNKER, this.pos, (float) (1.2 - this.distance / 1500.0), 1.0F);
				playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, (float) (1.2 - this.distance / 1500.0), 0.8F);
				deafen(mc, (float) (0.8 - this.distance / 80.0), 50);
				addShake((float) Mth.clamp(4.5 - this.distance / 50.0, 0.3, 4.5));
			}
			if (t == erupt) {
				setFlash((float) Mth.clamp(0.3 - this.distance / 800.0, 0.0, 0.3), 0xFFC080);
				// the ground heaves: a geyser of dirt, rock and smoke
				debrisJets(mc, this.pos, 26, 2.2, 3.5F);
				for (int i = 0; i < 80; i++) {
					double a = rand() * Mth.TWO_PI;
					double r = rand() * 6;
					CloudParticle p = cloud(false, c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r, Math.cos(a) * 0.15, 0.6 + rand() * 1.4, Math.sin(a) * 0.15);
					if (p != null) {
						p.configure(120 + RANDOM.nextInt(80), 3.0F, 9.0F, 0x7C6A56, 0x50483F, 0.9F).physics(0.93F, -0.004F).litFrom(c.x, c.y + 8, c.z).turbulence(0.03F);
					}
				}
				fireball(c.add(0, 2, 0), 3, 20, 4.0F, 0.9, 22);
				groundRing(mc, this.pos, 5, 60, 6.0F, 1.0, 0xA89880);
			}
			if (t > erupt && t < erupt + 80) {
				for (int i = 0; i < 4; i++) {
					CloudParticle p = cloud(false, c.x + gauss() * 3, c.y + rand() * 3, c.z + gauss() * 3, 0, 0.3 + rand() * 0.2, 0);
					if (p != null) {
						p.configure(200, 4.0F, 12.0F, 0x5A5048, 0x3A3632, 0.85F).physics(0.97F, 0.003F).litFrom(c.x, c.y + 10, c.z).turbulence(0.03F);
					}
				}
			}
			return t > Math.max(erupt + this.soundDelay, erupt + 80) + 1;
		}
	}

	// ------------------------------------------------------------------ thermobaric

	/** Fuel-air explosive: a ground-hugging fuel cloud ignites into an enormous rolling fireball. */
	static final class ThermobaricEffect implements Effect {
		private final Vec3 pos;
		private final double distance;
		private final int soundDelay;
		private int age;

		ThermobaricEffect(Vec3 pos) {
			this.pos = pos;
			Minecraft mc = Minecraft.getInstance();
			this.distance = distanceToCamera(mc, pos);
			this.soundDelay = (int) (this.distance / SPEED_OF_SOUND) + 6;
		}

		@Override
		public boolean tick(Minecraft mc) {
			int t = this.age++;
			Vec3 c = this.pos.add(0, 1, 0);
			if (t < 6) {
				// the fuel aerosol spreads out in a grey-white disc
				for (int i = 0; i < 40; i++) {
					double a = rand() * Mth.TWO_PI;
					double r = (t + 1) * 4.0 * Math.sqrt(rand());
					CloudParticle p = cloud(false, c.x + Math.cos(a) * r, c.y + rand() * 3, c.z + Math.sin(a) * r, Math.cos(a) * 0.5, 0.02, Math.sin(a) * 0.5);
					if (p != null) {
						p.configure(14, 3.0F, 6.0F, 0xE6E2DA, 0xD0CCC4, 0.45F).physics(0.9F, 0.0F);
					}
				}
			}
			if (t == 6) {
				setFlash((float) Mth.clamp(1.0 - this.distance / 900.0, 0.1, 0.95), 0xFFD9A0);
				// the whole cloud ignites at once
				for (int i = 0; i < 260; i++) {
					double a = rand() * Mth.TWO_PI;
					double r = 26 * Math.sqrt(rand());
					double h = rand() * 10;
					CloudParticle p = cloud(true, c.x + Math.cos(a) * r, c.y + h, c.z + Math.sin(a) * r, Math.cos(a) * 0.35, 0.25 + rand() * 0.5, Math.sin(a) * 0.35);
					if (p != null) {
						p.configure(50 + RANDOM.nextInt(40), 6.0F, 13.0F + rand() * 6.0F, 0xFFF0C0, 0xE0400A, 1.0F)
							.physics(0.9F, 0.018F)
							.cooling(0.4F + rand() * 0.2F, 0x28241F)
							.litFrom(c.x, c.y + 6, c.z)
							.turbulence(0.05F);
					}
				}
				fireball(c.add(0, 4, 0), 10, 120, 14.0F, 0.9, 50);
				embers(c, 140, 1.4);
				debrisJets(mc, this.pos, 14, 1.8, 3.0F);
			}
			if (t >= 7 && t <= 10) {
				shockSphere(c, 10 + (t - 7) * 9, 70);
			}
			if (t > 6 && t < 26) {
				groundRing(mc, this.pos, 26 + (t - 6) * 3.5, 50, 7.0F, 1.2, 0xB09878);
			}
			if (t > 10 && t < 140) {
				// rising, rolling column of fire turning into black smoke
				for (int i = 0; i < 6; i++) {
					CloudParticle p = cloud(t < 70, c.x + gauss() * 8, c.y + 4 + rand() * 8 + t * 0.25, c.z + gauss() * 8, gauss() * 0.05, 0.35 + rand() * 0.3, gauss() * 0.05);
					if (p != null) {
						p.configure(160 + RANDOM.nextInt(80), 7.0F, 18.0F, t < 70 ? 0xFFB050 : 0x3A3430, 0x221F1C, 0.9F)
							.physics(0.975F, 0.004F)
							.cooling(0.15F, 0x26221E)
							.litFrom(c.x, c.y + 10 + t * 0.3, c.z)
							.turbulence(0.04F);
					}
				}
			}
			if (t == this.soundDelay) {
				if (this.distance < 220) {
					playDistant(mc, ModRegistry.EXPLOSION_THERMOBARIC, this.pos, 1.0F, 1.0F);
					playDistant(mc, ModRegistry.EXPLOSION_THERMOBARIC, this.pos, 1.0F, 0.97F);
					playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, 1.0F, 0.85F);
					deafen(mc, (float) (1.0 - this.distance / 160.0), 100);
					playDistant(mc, ModRegistry.NUKE_WIND, this.pos, 0.7F, 1.25F);
				} else {
					playDistant(mc, ModRegistry.EXPLOSION_FAR, this.pos, (float) (1.4 - this.distance / 1800.0), 0.8F);
					playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, (float) (1.2 - this.distance / 1800.0), 0.75F);
				}
				addShake((float) Mth.clamp(5.0 - this.distance / 70.0, 0.3, 5.0));
			}
			return t > Math.max(140, this.soundDelay + 1);
		}
	}

	// ------------------------------------------------------------------ interception

	/** Missile destroyed in mid-air: flash, fireball, burning debris raining down, a distant crack. */
	static final class InterceptEffect implements Effect {
		private final Vec3 pos;
		private final double distance;
		private final int soundDelay;
		private int age;

		InterceptEffect(Vec3 pos) {
			this.pos = pos;
			Minecraft mc = Minecraft.getInstance();
			this.distance = distanceToCamera(mc, pos);
			this.soundDelay = (int) (this.distance / SPEED_OF_SOUND);
			setFlash((float) Mth.clamp(0.35 - this.distance / 1500.0, 0.0, 0.35), 0xFFE0B0);
		}

		@Override
		public boolean tick(Minecraft mc) {
			int t = this.age++;
			if (t == 0) {
				fireball(this.pos, 3.0, 50, 5.0F, 0.9, 30);
				embers(this.pos, 70, 1.2);
				shockSphere(this.pos, 6, 40);
				// burning fragments falling with smoke trails
				for (int i = 0; i < 24; i++) {
					Vec3 v = new Vec3(gauss(), gauss() * 0.6 + 0.2, gauss()).normalize().scale(0.6 + rand() * 0.9);
					for (int k = 0; k < 5; k++) {
						double f = 0.4 + 0.6 * k / 4.0;
						CloudParticle p = cloud(k == 4, this.pos.x, this.pos.y, this.pos.z, v.x * f, v.y * f, v.z * f);
						if (p != null) {
							p.configure(90 + RANDOM.nextInt(50), 0.8F, 3.2F, k == 4 ? 0xFFB050 : 0x4A4642, 0x2A2826, 0.8F)
								.physics(0.97F, -0.025F)
								.cooling(0.3F, 0x2A2826);
						}
					}
				}
			}
			if (t == this.soundDelay) {
				if (this.distance < 400) {
					playDistant(mc, ModRegistry.EXPLOSION_MID, this.pos, (float) (1.0 - this.distance / 600.0), 1.15F);
				} else {
					playDistant(mc, ModRegistry.EXPLOSION_FAR, this.pos, (float) (1.0 - this.distance / 2500.0), 1.2F);
				}
				addShake((float) Mth.clamp(1.2 - this.distance / 200.0, 0.0, 1.2));
			}
			return t > this.soundDelay + 1;
		}
	}

	// ------------------------------------------------------------------ high-altitude EMP burst

	/** A blinding star high up, an expanding glowing shell and artificial aurora; hardly any sound. */
	static final class EmpEffect implements Effect {
		private final Vec3 pos;
		private final double distance;
		private final int soundDelay;
		private int age;

		EmpEffect(Vec3 pos) {
			this.pos = pos;
			Minecraft mc = Minecraft.getInstance();
			this.distance = distanceToCamera(mc, pos);
			this.soundDelay = (int) (this.distance / SPEED_OF_SOUND);
			setFlash((float) Mth.clamp(1.0 - this.distance / 6000.0, 0.35, 0.95), 0xE8F0FF);
		}

		@Override
		public boolean tick(Minecraft mc) {
			int t = this.age++;
			if (t == 0) {
				fireball(this.pos, 6.0, 120, 14.0F, 1.2, 36);
				shockSphere(this.pos, 10, 80);
			}
			if (t == 3) {
				setFlash((float) Mth.clamp(0.6 - this.distance / 8000.0, 0.15, 0.6), 0xB0C8FF);
			}
			if (t < 70) {
				// glowing shell of ionised air racing outwards
				double r = 6 + t * 3.2;
				int n = 46;
				for (int i = 0; i < n; i++) {
					double a = rand() * Mth.TWO_PI;
					double y = gauss() * 0.25;
					Vec3 v = new Vec3(Math.cos(a), y, Math.sin(a));
					Vec3 p = this.pos.add(v.scale(r));
					CloudParticle c = cloud(true, p.x, p.y, p.z, v.x * 0.4, v.y * 0.4, v.z * 0.4);
					if (c != null) {
						c.configure(40 + RANDOM.nextInt(30), 4.0F, 10.0F, t < 20 ? 0xE0F4FF : 0x9AD8FF, 0x7A40FF, Math.max(0.15F, 0.7F - t * 0.008F))
							.physics(0.96F, 0.0F)
							.wind(0);
					}
				}
			}
			if (t > 15 && t < 260 && t % 2 == 0) {
				// aurora curtains far around the burst
				for (int i = 0; i < 5; i++) {
					double a = rand() * Mth.TWO_PI;
					double r = 60 + rand() * 200;
					double x = this.pos.x + Math.cos(a) * r;
					double z = this.pos.z + Math.sin(a) * r;
					double y = this.pos.y + gauss() * 20;
					int color = rand() < 0.65F ? 0x50FF9A : 0xFF4A80;
					for (int k = 0; k < 4; k++) {
						CloudParticle c = cloud(true, x, y + k * 9, z, 0, 0.05, 0);
						if (c != null) {
							c.configure(120 + RANDOM.nextInt(80), 8.0F, 14.0F, color, mix(color, 0x203060, 0.6F), 0.22F).physics(0.99F, 0.0F).wind(0);
						}
					}
				}
			}
			if (t == this.soundDelay) {
				playDistant(mc, ModRegistry.NUKE_FAR, this.pos, (float) (1.2 - this.distance / 6000.0), 1.35F);
				playDistant(mc, ModRegistry.NUKE_SUB, this.pos, (float) (0.8 - this.distance / 6000.0), 1.2F);
			}
			return t > Math.max(260, this.soundDelay + 1);
		}
	}

	// ------------------------------------------------------------------ antimatter

	/**
	 * Annihilation: a blinding white flash, then a perfect sphere of light that swells to the size of
	 * the hole it leaves and collapses in on itself in a shower of violet sparks.
	 */
	static final class AntimatterEffect implements Effect {
		private static final double RADIUS = 30.0;
		private final Vec3 pos;
		private final double distance;
		private final int soundDelay;
		private int age;

		AntimatterEffect(Vec3 pos) {
			this.pos = pos;
			Minecraft mc = Minecraft.getInstance();
			this.distance = distanceToCamera(mc, pos);
			this.soundDelay = (int) (this.distance / SPEED_OF_SOUND);
			setFlash((float) Mth.clamp(1.0 - this.distance / 5000.0, 0.5, 1.0), 0xFFFFFF);
			addShake((float) Math.max(0.0, 3.0 - this.distance / 150.0));
		}

		@Override
		public boolean tick(Minecraft mc) {
			int t = this.age++;
			if (t < 18) {
				// the sphere of light, growing with the annihilation front
				double r = RADIUS * Math.min(1.0, (t + 1) / 15.0);
				for (int i = 0; i < 70; i++) {
					Vec3 v = new Vec3(gauss(), gauss(), gauss()).normalize();
					Vec3 p = this.pos.add(v.scale(r));
					CloudParticle c = cloud(true, p.x, p.y, p.z, v.x * 0.2, v.y * 0.2, v.z * 0.2);
					if (c != null) {
						c.configure(14 + RANDOM.nextInt(8), 3.5F, 6.0F, 0xFFFFFF, rand() < 0.5F ? 0xB89CFF : 0x8CE8FF, 0.6F).physics(0.9F, 0.0F).wind(0);
					}
				}
			}
			if (t == 18) {
				setFlash(0.45F, 0xD8C8FF);
			}
			if (t >= 18 && t < 40) {
				// collapse: sparks rushing back into the void
				for (int i = 0; i < 40; i++) {
					Vec3 v = new Vec3(gauss(), gauss(), gauss()).normalize();
					Vec3 p = this.pos.add(v.scale(RADIUS * (1.0 - (t - 18) / 24.0)));
					CloudParticle c = cloud(true, p.x, p.y, p.z, -v.x * 1.2, -v.y * 1.2, -v.z * 1.2);
					if (c != null) {
						c.configure(10 + RANDOM.nextInt(6), 0.8F, 0.2F, 0xF0E0FF, 0x7A40FF, 0.9F).physics(0.92F, 0.0F).wind(0);
					}
				}
			}
			if (t == this.soundDelay) {
				playDistant(mc, ModRegistry.NUKE_NEAR, this.pos, (float) (1.3 - this.distance / 2500.0), 1.6F);
				playDistant(mc, ModRegistry.NUKE_SUB, this.pos, (float) (1.0 - this.distance / 3000.0), 1.4F);
			}
			return t > Math.max(40, this.soundDelay + 1);
		}
	}

	// ------------------------------------------------------------------ incendiary airburst

	/** White-hot streamers arcing down from the opening warhead. */
	static final class IncendiaryReleaseEffect implements Effect {
		private final Vec3 pos;
		private int age;

		IncendiaryReleaseEffect(Vec3 pos) {
			this.pos = pos;
			Minecraft mc = Minecraft.getInstance();
			setFlash((float) Mth.clamp(0.3 - distanceToCamera(mc, pos) / 900.0, 0.0, 0.3), 0xFFF0C0);
		}

		@Override
		public boolean tick(Minecraft mc) {
			if (this.age++ == 0) {
				fireball(this.pos, 2.0, 24, 3.0F, 0.6, 18);
				for (int j = 0; j < 36; j++) {
					Vec3 dir = new Vec3(gauss(), -0.2 - rand() * 0.6, gauss()).normalize();
					double s = 0.6 + rand() * 0.9;
					for (int k = 0; k < 8; k++) {
						double f = 0.25 + 0.75 * k / 7.0;
						boolean fire = k >= 5;
						CloudParticle p = cloud(fire, this.pos.x, this.pos.y, this.pos.z, dir.x * s * f, dir.y * s * f, dir.z * s * f);
						if (p != null) {
							p.configure(fire ? 40 + RANDOM.nextInt(20) : 120 + RANDOM.nextInt(60), fire ? 0.9F : 1.2F, fire ? 0.4F : 4.0F,
									fire ? 0xFFFFF0 : 0xFFFFFF, fire ? 0xFFB040 : 0xD8D6D2, fire ? 1.0F : 0.75F)
								.physics(0.975F, fire ? -0.03F : -0.004F);
						}
					}
				}
			}
			return true;
		}
	}

	// ------------------------------------------------------------------ cluster opening

	static final class ReleaseEffect implements Effect {
		private final Vec3 pos;
		private int age;

		ReleaseEffect(Vec3 pos) {
			this.pos = pos;
		}

		@Override
		public boolean tick(Minecraft mc) {
			if (this.age++ == 0) {
				for (int i = 0; i < 30; i++) {
					Vec3 v = new Vec3(gauss(), gauss() * 0.4, gauss()).normalize().scale(0.6);
					CloudParticle p = cloud(false, this.pos.x, this.pos.y, this.pos.z, v.x, v.y, v.z);
					if (p != null) {
						p.configure(80, 1.5F, 5.0F, 0xE8E4DE, 0xB0ACA6, 0.7F).physics(0.9F, 0.0F);
					}
				}
				fireball(this.pos, 1.0, 8, 1.6F, 0.4, 12);
			}
			return true;
		}
	}

	// ------------------------------------------------------------------ nuclear blast

	static final class NukeEffect implements Effect {
		private static final int DURATION = 900;

		private final Vec3 pos;
		private final double scale;
		private final double cloudHeight;
		private final double capRadius;
		private final double distance;
		private final int soundDelay;
		private int age;

		NukeEffect(Vec3 pos, double scale) {
			this.pos = pos;
			this.scale = scale;
			this.cloudHeight = 130.0 * scale;
			this.capRadius = 42.0 * scale;
			Minecraft mc = Minecraft.getInstance();
			this.distance = distanceToCamera(mc, pos);
			this.soundDelay = (int) (this.distance / SPEED_OF_SOUND);
			setFlash((float) Mth.clamp(1.25 - this.distance / (2400.0 * scale), 0.25, 1.0), 0xFFFFFF);
			addShake((float) Mth.clamp((1.5 - this.distance / 600.0) * scale, 0.0, 2.0)); // ground tremor arrives first
		}

		private double capHeight(double t) {
			return this.cloudHeight * (1.0 - Math.exp(-t / (160.0 * Math.sqrt(this.scale))));
		}

		private double capRadiusAt(double t) {
			return 8.0 * this.scale + this.capRadius * (1.0 - Math.exp(-t / (190.0 * Math.sqrt(this.scale))));
		}

		@Override
		public boolean tick(Minecraft mc) {
			int t = this.age++;
			double s = this.scale;
			float ps = (float) Math.sqrt(s); // particles grow slower than the cloud, more of them fill it
			double cx = this.pos.x;
			double cy = this.pos.y;
			double cz = this.pos.z;

			// ---- audio + shock arrival
			if (t == this.soundDelay) {
				float pitch = s > 1.2 ? 0.8F : 1.0F;
				if (this.distance < 320 * s) {
					playDistant(mc, ModRegistry.NUKE_NEAR, this.pos, 1.0F, pitch);
					playDistant(mc, ModRegistry.NUKE_NEAR, this.pos, 1.0F, pitch * 0.96F); // doubled for real-life loudness
					playDistant(mc, ModRegistry.NUKE_SUB, this.pos, 1.0F, pitch);
					playDistant(mc, ModRegistry.NUKE_WIND, this.pos, 1.0F, pitch);
					playDistant(mc, ModRegistry.EXPLOSION_DEBRIS, this.pos, 1.0F, 0.8F);
					if (s > 1.2) {
						playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, 1.0F, 0.6F);
					}
					deafen(mc, (float) (1.05 - this.distance / (400.0 * s)), (int) (160 * s));
				} else if (this.distance < 1300 * s) {
					float volume = (float) (1.2 - this.distance / (2200.0 * s));
					playDistant(mc, ModRegistry.NUKE_MID, this.pos, volume, pitch);
					playDistant(mc, ModRegistry.NUKE_SUB, this.pos, volume, pitch * 0.9F);
					playDistant(mc, ModRegistry.NUKE_WIND, this.pos, volume * 0.6F, pitch * 0.9F);
					deafen(mc, (float) (0.5 - this.distance / (2000.0 * s)), 40);
				} else {
					float volume = (float) (1.4 - this.distance / (3000.0 * s));
					playDistant(mc, ModRegistry.NUKE_FAR, this.pos, volume, pitch);
					playDistant(mc, ModRegistry.NUKE_SUB, this.pos, volume * 0.9F, pitch * 0.9F);
				}
				addShake((float) Mth.clamp((6.0 - this.distance / (90.0 * s)), 0.4, 6.0));
			}
			if (t == 2) {
				setFlash((float) Mth.clamp(0.9 - this.distance / (3000.0 * s), 0.15, 0.9), 0xFFC890);
			}
			if (s > 1.2 && t == 8) {
				setFlash((float) Mth.clamp(0.75 - this.distance / (4000.0 * s), 0.1, 0.75), 0xFFE0B0); // thermonuclear second stage
			}

			// ---- initial fireball, rolling into the stem
			if (t < 50) {
				double radius = (6.0 + 26.0 * Math.sqrt(t / 50.0)) * s;
				double lift = t * 0.6 * s;
				int n = t < 10 ? 45 : 22;
				for (int i = 0; i < n; i++) {
					Vec3 v = new Vec3(gauss(), gauss() * 0.7 + 0.3, gauss()).normalize();
					double r = radius * (0.4 + 0.6 * rand());
					CloudParticle p = cloud(true, cx + v.x * r, cy + lift + Math.abs(v.y) * r * 0.8, cz + v.z * r, v.x * 0.4 * s, 0.3 + v.y * 0.2, v.z * 0.4 * s);
					if (p != null) {
						int color = t < 8 ? 0xFFFFF0 : 0xFFE38A;
						p.configure(50 + RANDOM.nextInt(40), 7.0F * ps, (15.0F + rand() * 6.0F) * ps, color, 0xC02808, 1.0F)
							.physics(0.9F, 0.01F)
							.cooling(0.55F + rand() * 0.25F, 0x4A3A30)
							.litFrom(cx, cy + lift, cz)
							.turbulence(0.04F);
					}
				}
				if (t < 20) {
					for (int i = 0; i < 30; i++) {
						vanilla(ParticleTypes.LAVA, cx + gauss() * 10 * s, cy + 2, cz + gauss() * 10 * s, 0, 0, 0);
					}
				}
				if (t == 0) {
					debrisJets(mc, this.pos, 40, 3.0 * ps, 6.0F * ps);
					embers(this.pos, 200, 2.2 * ps);
				}
			}
			if (t >= 1 && t <= 5) {
				shockSphere(new Vec3(cx, cy + 5, cz), (15 + t * 14) * s, 90);
			}

			// ---- ground surge / shockwave ring
			if (t < 70) {
				groundRing(mc, this.pos, (10 + t * 4.2) * s, 45, 11.0F * ps, 1.4 * ps, 0xC9B79C);
			}

			// ---- condensation (Wilson) ring around the stem
			if (t > 25 && t < 90 && t % 2 == 0) {
				double h = this.capHeight(t) * 0.55;
				double ring = (25 + (t - 25) * 0.9) * s;
				for (int i = 0; i < 26; i++) {
					double a = rand() * Mth.TWO_PI;
					CloudParticle p = cloud(false, cx + Math.cos(a) * ring, cy + h + gauss(), cz + Math.sin(a) * ring, Math.cos(a) * 0.3, 0.0, Math.sin(a) * 0.3);
					if (p != null) {
						p.configure(70, 5.0F * ps, 9.0F * ps, 0xF4F4F4, 0xDADADA, 0.55F).physics(0.97F, 0.0F);
					}
				}
			}

			// ---- mushroom cloud
			if (t < 640) {
				double h = this.capHeight(t);
				double rc = this.capRadiusAt(t);
				double rise = this.capHeight(t + 1) - h;
				boolean hot = t < 160;
				float heat = (float) Mth.clamp(1.0 - t / 260.0, 0.0, 1.0);
				double capY = cy + h;

				// cap: a torus that rolls outward on top and inward underneath
				int capCount = t < 200 ? 28 : 15;
				for (int i = 0; i < capCount; i++) {
					double a = rand() * Mth.TWO_PI;
					double minor = rand() * Mth.TWO_PI;
					double ringR = rc * 0.62;
					double tube = rc * 0.42 * Math.sqrt(rand());
					double radial = ringR + Math.cos(minor) * tube;
					double vertical = Math.sin(minor) * tube * 0.75;
					double x = cx + Math.cos(a) * radial;
					double z = cz + Math.sin(a) * radial;
					double y = capY + vertical;
					double roll = 0.08 * s;
					double vx = Math.cos(a) * -Math.sin(minor) * roll;
					double vz = Math.sin(a) * -Math.sin(minor) * roll;
					double vy = rise + Math.cos(minor) * roll;
					boolean fire = hot && vertical < 0 && rand() < heat;
					CloudParticle p = cloud(fire, x, y, z, vx, vy, vz);
					if (p != null) {
						int start = fire ? 0xFF8A2A : mix(0x9A6A4A, 0x7C7068, 1.0F - heat);
						int end = fire ? 0x6A3020 : mix(0x6A584C, 0x56504C, 1.0F - heat);
						float up = (float) (Math.sin(minor) * 0.5 + 0.5);
						p.configure(190 + RANDOM.nextInt(130), 9.0F * ps, (16.0F + rand() * 8.0F) * ps, start, end, 0.92F)
							.physics(0.985F, 0.0F)
							.shade(fire ? 1.0F : 0.7F + 0.35F * up)
							.cooling(0.35F, 0x5E504A)
							.litFrom(cx + Math.cos(a) * ringR, capY, cz + Math.sin(a) * ringR)
							.turbulence(0.03F)
							.wind(0.6F);
					}
				}
				// center of cap (fills the hole of the torus)
				for (int i = 0; i < 6; i++) {
					double r = rc * 0.45 * Math.sqrt(rand());
					double a = rand() * Mth.TWO_PI;
					CloudParticle p = cloud(hot && rand() < heat * 0.6, cx + Math.cos(a) * r, capY + rc * 0.15 + gauss() * 2, cz + Math.sin(a) * r, 0, rise, 0);
					if (p != null) {
						p.configure(200 + RANDOM.nextInt(80), 10.0F * ps, 18.0F * ps, mix(0xB07A50, 0x847A72, 1.0F - heat), 0x605854, 0.9F)
							.physics(0.985F, 0.0F)
							.cooling(0.35F, 0x605854)
							.litFrom(cx, capY - rc * 0.2, cz)
							.wind(0.6F);
					}
				}
				// stem
				int stemCount = t < 300 ? 10 : 5;
				for (int i = 0; i < stemCount; i++) {
					double y = rand() * h * 0.9;
					double stemR = (4.0 + 7.0 * (y / Math.max(1.0, h)) + (y < 12 * s ? (12 * s - y) * 0.9 : 0)) * s;
					double a = rand() * Mth.TWO_PI;
					double r = stemR * Math.sqrt(rand());
					boolean fire = hot && rand() < heat * 0.5;
					CloudParticle p = cloud(fire, cx + Math.cos(a) * r, cy + y, cz + Math.sin(a) * r, Math.cos(a) * -0.02, rise * 0.8 + 0.25, Math.sin(a) * -0.02);
					if (p != null) {
						int start = fire ? 0xFF7020 : mix(0x8A6A54, 0x6E6660, 1.0F - heat);
						p.configure(120 + RANDOM.nextInt(60), 6.0F * ps, 11.0F * ps, start, 0x5A5450, 0.88F)
							.physics(0.97F, 0.0F)
							.cooling(0.3F, 0x5A5450)
							.litFrom(cx, cy + y, cz)
							.turbulence(0.04F)
							.wind(0.4F);
					}
				}
				// glowing core early on lights the cloud from inside
				if (hot && t % 2 == 0) {
					CloudParticle p = cloud(true, cx + gauss() * rc * 0.2, capY - rc * 0.15, cz + gauss() * rc * 0.2, 0, rise, 0);
					if (p != null) {
						p.configure(40, 14.0F * ps, 22.0F * ps, 0xFFC060, 0xB03010, 0.85F * heat);
					}
				}
			}

			// ---- drifting dust skirt around ground zero
			if (t > 40 && t < 500 && t % 3 == 0) {
				for (int i = 0; i < 6; i++) {
					double a = rand() * Mth.TWO_PI;
					double r = (20 + rand() * 70) * s;
					double x = cx + Math.cos(a) * r;
					double z = cz + Math.sin(a) * r;
					CloudParticle p = cloud(false, x, groundY(mc, x, cy, z) + 1 + rand() * 3, z, gauss() * 0.05, 0.03, gauss() * 0.05);
					if (p != null) {
						p.configure(200, 6.0F * ps, 14.0F * ps, 0x8E8270, 0x6E665C, 0.5F).turbulence(0.03F);
					}
				}
			}

			return t > Math.max(DURATION, this.soundDelay + 2);
		}
	}
}
