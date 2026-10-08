package de.rcm.ballistic.client.effect;

import static de.rcm.ballistic.client.effect.ClientEffects.addShake;
import static de.rcm.ballistic.client.effect.ClientEffects.cloud;
import static de.rcm.ballistic.client.effect.ClientEffects.debrisJets;
import static de.rcm.ballistic.client.effect.ClientEffects.deafen;
import static de.rcm.ballistic.client.effect.ClientEffects.distanceToCamera;
import static de.rcm.ballistic.client.effect.ClientEffects.embers;
import static de.rcm.ballistic.client.effect.ClientEffects.fireball;
import static de.rcm.ballistic.client.effect.ClientEffects.gauss;
import static de.rcm.ballistic.client.effect.ClientEffects.groundBlock;
import static de.rcm.ballistic.client.effect.ClientEffects.groundRing;
import static de.rcm.ballistic.client.effect.ClientEffects.groundY;
import static de.rcm.ballistic.client.effect.ClientEffects.mix;
import static de.rcm.ballistic.client.effect.ClientEffects.playDistant;
import static de.rcm.ballistic.client.effect.ClientEffects.rand;
import static de.rcm.ballistic.client.effect.ClientEffects.setFlash;
import static de.rcm.ballistic.client.effect.ClientEffects.shockCrack;
import static de.rcm.ballistic.client.effect.ClientEffects.shockSphere;
import static de.rcm.ballistic.client.effect.ClientEffects.vanilla;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.explosion.Tsunami;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The three very large, very different detonations: buried nuke, Poseidon under the sea, rod from orbit. */
final class GiantEffects {
	private static final double SPEED_OF_SOUND = 17.15;
	/** Ground shock through rock travels far faster than sound through the air. */
	private static final double SEISMIC_SPEED = 120.0;

	private GiantEffects() {
	}

	private static int life(int base, int spread) {
		return base + (int) (rand() * spread);
	}

	// ------------------------------------------------------------------ B61-11: buried burst

	/**
	 * B61-11 going off underground: the earthquake arrives first, the ground over the warhead heaves up
	 * into a dome that bursts, a dark column of rock, dirt and dust towers up with only a dull glow at
	 * its foot, a base surge of dust rolls out along the ground, and rocks rain down for a long time.
	 */
	static final class Underground implements ClientEffects.Effect {
		private static final double DOME = 30.0;
		private final Vec3 pos;
		private final double distance;
		private final int soundDelay;
		private final int quakeDelay;
		private final BlockState ground;
		private int age;

		Underground(Vec3 pos) {
			this.pos = pos;
			Minecraft mc = Minecraft.getInstance();
			this.distance = distanceToCamera(mc, pos);
			this.soundDelay = (int) (this.distance / SPEED_OF_SOUND);
			this.quakeDelay = (int) (this.distance / SEISMIC_SPEED);
			BlockState g = groundBlock(mc, pos);
			this.ground = g != null ? g : net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
			BlastShader.blast(BlastShader.Kind.UNDERGROUND, pos, 1.3, 0xC08850);
			BlastShader.shockwave(pos, 9.0, 260.0, 0.8F);
		}

		@Override
		public boolean tick(Minecraft mc) {
			int t = this.age++;
			double cx = this.pos.x;
			double cy = this.pos.y;
			double cz = this.pos.z;
			// ---- the earthquake: a hard jolt, then a long rolling tremor
			if (t >= this.quakeDelay && t < this.quakeDelay + 260) {
				int q = t - this.quakeDelay;
				double near = Mth.clamp(1.0 - this.distance / 1400.0, 0.0, 1.0);
				double roll = Math.exp(-q / 80.0) * (0.65 + 0.35 * Math.sin(q * 0.45) * Math.sin(q * 0.13));
				addShake((float) (near * (q < 6 ? 5.5 : 3.2 * roll)));
				if (q == 0) {
					BlastShader.quake((float) near);
				}
			}
			// ---- sound: a deep, long thunder from the ground rather than a crack
			if (t == this.soundDelay) {
				float near = (float) Mth.clamp(1.25 - this.distance / 2600.0, 0.15, 1.0);
				shockCrack(mc, ModRegistry.SHOCK_BUNKER, this.pos, this.distance, 2600.0, 0.7F);
				playDistant(mc, ModRegistry.NUKE_SUB, this.pos, near, 0.7F);
				playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, near, 0.5F);
				playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, near, 0.62F);
				playDistant(mc, this.distance < 500 ? ModRegistry.NUKE_NEAR : ModRegistry.NUKE_FAR, this.pos, near * 0.8F, 0.72F);
				playDistant(mc, ModRegistry.EXPLOSION_DEBRIS, this.pos, near, 0.6F);
				if (this.distance < 300) {
					deafen(mc, (float) (0.95 - this.distance / 400.0), 120);
				}
			}
			// ---- the ground over the warhead heaves up into a dome
			if (t < 9) {
				double f = (t + 1) / 9.0;
				for (int i = 0; i < 70; i++) {
					double a = rand() * Mth.TWO_PI;
					double rr = DOME * Math.sqrt(rand());
					double h = Math.sqrt(Math.max(0.0, DOME * DOME - rr * rr)) * 0.45 * f;
					double x = cx + Math.cos(a) * rr;
					double z = cz + Math.sin(a) * rr;
					double y = groundY(mc, x, cy, z) + h * 0.5;
					CloudParticle p = cloud(false, x, y, z, Math.cos(a) * 0.12, 0.35 * f, Math.sin(a) * 0.12);
					if (p != null) {
						p.configure(life(60, 40), 3.0F, 7.0F, mix(0x7A6650, 0x5E5246, rand()), 0x4E463E, 0.95F).physics(0.9F, -0.01F).turbulence(0.03F);
					}
					if (i % 4 == 0) {
						vanilla(new BlockParticleOption(ParticleTypes.BLOCK, this.ground), x, y, z, gauss() * 0.3, 0.6 * f, gauss() * 0.3);
					}
				}
			}
			// ---- the dome bursts: rock flung out on every side, a dull glow at the vent
			if (t == 9) {
				debrisJets(mc, this.pos, 90, 3.4, 8.0F);
				fireball(new Vec3(cx, cy + 4, cz), 9.0, 50, 10.0F, 1.2, 26);
				embers(this.pos, 220, 2.6);
				shockSphere(new Vec3(cx, cy + 4, cz), 30.0, 80);
				setFlash((float) Mth.clamp(0.45 - this.distance / 3000.0, 0.05, 0.45), 0xFFB070);
			}
			// ---- the column of rock, earth and dust and its dirty, cauliflower head
			if (t >= 9 && t < 160) {
				double rise = Math.min(1.0, (t - 9) / 70.0);
				double top = 140.0 * (1.0 - Math.exp(-(t - 9) / 45.0));
				int n = t < 60 ? 30 : 12;
				for (int i = 0; i < n; i++) {
					double y = rand() * top;
					double r = (10.0 + 10.0 * (y / Math.max(1.0, top))) * Math.sqrt(rand());
					double a = rand() * Mth.TWO_PI;
					boolean hot = t < 40 && y < 20 && rand() < 0.3F;
					CloudParticle p = cloud(hot, cx + Math.cos(a) * r, cy + y, cz + Math.sin(a) * r, Math.cos(a) * 0.05, 0.5 + 0.8 * (1.0 - rise), Math.sin(a) * 0.05);
					if (p != null) {
						p.configure(life(160, 120), 6.0F, 15.0F, hot ? 0xFF8A3A : mix(0x6E5C48, 0x7A7066, rand()), 0x4E4842, 0.95F)
							.physics(0.96F, 0.002F).cooling(0.3F, 0x4A4440).litFrom(cx, cy + top, cz).turbulence(0.05F).wind(0.4F);
					}
				}
				for (int i = 0; i < (t < 90 ? 16 : 6); i++) {
					double a = rand() * Mth.TWO_PI;
					double r = 22.0 * Math.sqrt(rand()) * (0.6 + rise);
					CloudParticle p = cloud(false, cx + Math.cos(a) * r, cy + top + gauss() * 6, cz + Math.sin(a) * r, Math.cos(a) * 0.15, 0.1, Math.sin(a) * 0.15);
					if (p != null) {
						p.configure(life(260, 120), 10.0F, 22.0F, mix(0x806C58, 0x8A8278, rand()), 0x5E5852, 0.92F)
							.physics(0.985F, 0.0F).litFrom(cx, cy + top - 10, cz).turbulence(0.03F).wind(0.7F);
					}
				}
			}
			// ---- base surge: a wall of dust rolling out along the ground
			if (t >= 12 && t < 170 && t % 2 == 0) {
				double ring = 30.0 + (t - 12) * 1.5;
				groundRing(mc, this.pos, ring, 60, 15.0F, 0.9, 0x9A8A72);
			}
			// ---- rocks and clods raining back down
			if (t > 20 && t < 200) {
				for (int i = 0; i < 10; i++) {
					double a = rand() * Mth.TWO_PI;
					double r = 70.0 * Math.sqrt(rand());
					vanilla(new BlockParticleOption(ParticleTypes.BLOCK, this.ground), cx + Math.cos(a) * r, cy + 30 + rand() * 60, cz + Math.sin(a) * r, 0, -1.0, 0);
				}
			}
			return t > Math.max(this.soundDelay + 2, Math.max(this.quakeDelay + 262, 520));
		}
	}

	// ------------------------------------------------------------------ Poseidon: under the sea

	/**
	 * Poseidon going off under the sea: the water heaves into a white dome, then a hollow column of
	 * spray a kilometre high bursts out of it, its top opening into a cauliflower of radioactive mist;
	 * a base surge of mist runs over the sea, and the tsunami front (the same ring the server floods
	 * the land with) races outward as a wall of breaking white water.
	 */
	static final class Poseidon implements ClientEffects.Effect {
		private final Vec3 pos;
		private final double sea;
		private final double distance;
		private final int soundDelay;
		private boolean roared;
		private int age;

		Poseidon(Vec3 pos) {
			this.pos = pos;
			Minecraft mc = Minecraft.getInstance();
			BlockPos.MutableBlockPos m = BlockPos.containing(pos).mutable();
			int up = 0;
			while (up < 64 && mc.level != null && !mc.level.getFluidState(m).isEmpty()) {
				m.move(0, 1, 0);
				up++;
			}
			this.sea = m.getY();
			this.distance = distanceToCamera(mc, pos);
			this.soundDelay = (int) (this.distance / SPEED_OF_SOUND);
			BlastShader.blast(BlastShader.Kind.WATER, new Vec3(pos.x, this.sea, pos.z), 1.8, 0xD8F0FF);
			BlastShader.shockwave(new Vec3(pos.x, this.sea, pos.z), 12.0, 500.0, 1.0F);
			setFlash((float) Mth.clamp(0.75 - this.distance / 4000.0, 0.1, 0.75), 0xE8F4FF);
		}

		@Override
		public boolean tick(Minecraft mc) {
			int t = this.age++;
			double cx = this.pos.x;
			double cz = this.pos.z;
			double s = this.sea;
			if (t == (int) (this.distance / SEISMIC_SPEED)) {
				addShake((float) Mth.clamp(3.5 - this.distance / 300.0, 0.3, 3.5));
			}
			if (t == this.soundDelay) {
				float near = (float) Mth.clamp(1.3 - this.distance / 3000.0, 0.15, 1.0);
				shockCrack(mc, ModRegistry.SHOCK_NUKE, this.pos, this.distance, 4000.0, 0.75F);
				playDistant(mc, ModRegistry.NUKE_SUB, this.pos, near, 0.6F);
				playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, near, 0.45F);
				playDistant(mc, this.distance < 600 ? ModRegistry.NUKE_NEAR : ModRegistry.NUKE_FAR, this.pos, near * 0.7F, 0.75F);
				playDistant(mc, SoundEvents.GENERIC_SPLASH, this.pos, near, 0.3F);
				if (this.distance < 400) {
					deafen(mc, (float) (1.0 - this.distance / 500.0), 140);
				}
			}
			// ---- the sea heaves up into a white dome
			if (t < 10) {
				double r = 6.0 + t * 5.0;
				for (int i = 0; i < 80; i++) {
					double a = rand() * Mth.TWO_PI;
					double rr = r * Math.sqrt(rand());
					double h = Math.sqrt(Math.max(0.0, r * r - rr * rr)) * 0.55;
					CloudParticle p = cloud(false, cx + Math.cos(a) * rr, s + h, cz + Math.sin(a) * rr, Math.cos(a) * 0.3, 0.5, Math.sin(a) * 0.3);
					if (p != null) {
						p.configure(life(40, 30), 3.0F, 8.0F, 0xFFFFFF, 0xE4ECEF, 0.9F).physics(0.92F, -0.02F).wind(0.0F);
					}
				}
			}
			// ---- the hollow column of spray, and its head of mist
			if (t >= 6 && t < 140) {
				double top = 230.0 * (1.0 - Math.exp(-(t - 6) / 40.0));
				for (int i = 0; i < (t < 50 ? 45 : 18); i++) {
					double a = rand() * Mth.TWO_PI;
					double r = 22.0 + gauss() * 4.0; // a hollow chimney of water
					double vy = Math.max(0.3, 4.2 - (t - 6) * 0.05) * (0.7 + rand() * 0.5);
					boolean dirty = rand() < 0.15F;
					CloudParticle p = cloud(false, cx + Math.cos(a) * r, s + rand() * top * 0.4, cz + Math.sin(a) * r, Math.cos(a) * 0.1, vy, Math.sin(a) * 0.1);
					if (p != null) {
						p.configure(life(110, 80), 6.0F, 16.0F, dirty ? 0xB4AE9E : 0xFFFFFF, dirty ? 0x9A968C : 0xDDE6EA, 0.9F)
							.physics(0.97F, -0.035F).turbulence(0.03F).wind(0.5F);
					}
				}
				if (t > 30) {
					for (int i = 0; i < 14; i++) {
						double a = rand() * Mth.TWO_PI;
						double r = 45.0 * Math.sqrt(rand());
						CloudParticle p = cloud(false, cx + Math.cos(a) * r, s + top + gauss() * 10, cz + Math.sin(a) * r, Math.cos(a) * 0.2, 0.05, Math.sin(a) * 0.2);
						if (p != null) {
							p.configure(life(320, 160), 14.0F, 28.0F, 0xF4F6F6, 0xC8CCCC, 0.85F).physics(0.99F, 0.0F).litFrom(cx, s + top - 20, cz).wind(0.8F);
						}
					}
				}
			}
			// ---- the water of the column falls back, and the base surge of mist runs out over the sea
			if (t > 40 && t < 300) {
				for (int i = 0; i < 8; i++) {
					vanilla(ParticleTypes.FALLING_WATER, cx + gauss() * 30, s + 20 + rand() * 120, cz + gauss() * 30, 0, 0, 0);
				}
			}
			if (t > 50 && t < 260 && t % 2 == 0) {
				double ring = 40.0 + (t - 50) * 1.3;
				for (int i = 0; i < 50; i++) {
					double a = rand() * Mth.TWO_PI;
					double x = cx + Math.cos(a) * ring;
					double z = cz + Math.sin(a) * ring;
					CloudParticle p = cloud(false, x, s + 2 + rand() * 6, z, Math.cos(a) * 0.6, 0.02, Math.sin(a) * 0.6);
					if (p != null) {
						p.configure(life(160, 80), 8.0F, 18.0F, 0xF0F4F4, 0xC4CACA, 0.6F).physics(0.97F, 0.0F).turbulence(0.02F);
					}
				}
			}
			// ---- the tsunami front: breaking white water racing outward
			double front = Tsunami.START_RADIUS + Tsunami.SPEED * t;
			if (front < Tsunami.MAX_RADIUS) {
				double h = Tsunami.height(front);
				Vec3 cam = mc.gameRenderer.getMainCamera().position();
				for (int i = 0; i < 110; i++) {
					double a = rand() * Mth.TWO_PI;
					double x = cx + Math.cos(a) * front;
					double z = cz + Math.sin(a) * front;
					if (Math.abs(x - cam.x) > 260 || Math.abs(z - cam.z) > 260) {
						continue;
					}
					double ground = groundY(mc, x, s, z);
					if (ground > s + h + 1) {
						continue; // the land stops it here
					}
					double crest = Math.max(s, ground) + h * (0.6 + 0.4 * rand());
					CloudParticle p = cloud(false, x, crest, z, Math.cos(a) * 0.9, 0.15, Math.sin(a) * 0.9);
					if (p != null) {
						p.configure(life(30, 25), 2.5F, 6.0F, 0xFFFFFF, 0xD6E4E8, 0.85F).physics(0.9F, -0.05F).wind(0.0F);
					}
					vanilla(ParticleTypes.SPLASH, x, crest, z, Math.cos(a) * 0.6, 0.4, Math.sin(a) * 0.6);
					if (i % 3 == 0) {
						vanilla(ParticleTypes.FALLING_WATER, x - Math.cos(a) * 2, crest + 1, z - Math.sin(a) * 2, 0, 0, 0);
					}
				}
				double toCam = Math.hypot(cam.x - cx, cam.z - cz);
				if (!this.roared && toCam - front < 60 && toCam > front - 10) {
					this.roared = true;
					// the roar of the wall of water coming in
					playDistant(mc, ModRegistry.NUKE_WIND, new Vec3(cx, s, cz), 1.0F, 0.55F);
					playDistant(mc, SoundEvents.GENERIC_SPLASH, cam, 1.0F, 0.25F);
					addShake(1.5F);
				}
				if (Math.abs(toCam - front) < 6 && cam.y < s + h + 2) {
					BlastShader.drench(1.0F);
				}
			}
			return t > Math.max(this.soundDelay + 2, (int) ((Tsunami.MAX_RADIUS - Tsunami.START_RADIUS) / Tsunami.SPEED) + 10);
		}
	}

	// ------------------------------------------------------------------ rod from orbit

	/**
	 * A tungsten rod hitting the ground at many times the speed of sound: a blinding blue-white flash,
	 * a steep curtain of ejecta, glowing molten droplets, a pillar of dust and a hammer blow through
	 * the ground. No mushroom cloud, no fire to speak of.
	 */
	static final class KineticImpact implements ClientEffects.Effect {
		private final Vec3 pos;
		private final double distance;
		private final int soundDelay;
		private final int quakeDelay;
		private final BlockState ground;
		private int age;

		KineticImpact(Vec3 pos) {
			this.pos = pos;
			Minecraft mc = Minecraft.getInstance();
			this.distance = distanceToCamera(mc, pos);
			this.soundDelay = (int) (this.distance / SPEED_OF_SOUND);
			this.quakeDelay = (int) (this.distance / SEISMIC_SPEED);
			BlockState g = groundBlock(mc, pos);
			this.ground = g != null ? g : net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
			setFlash((float) Mth.clamp(1.1 - this.distance / 1800.0, 0.2, 1.0), 0xE6EEFF);
			BlastShader.blast(BlastShader.Kind.KINETIC, pos, 1.2, 0xCFE2FF);
			BlastShader.shockwave(pos, 14.0, 220.0, 0.9F);
		}

		@Override
		public boolean tick(Minecraft mc) {
			int t = this.age++;
			double cx = this.pos.x;
			double cy = this.pos.y;
			double cz = this.pos.z;
			if (t == this.quakeDelay) {
				addShake((float) Mth.clamp(5.0 - this.distance / 120.0, 0.4, 5.0));
				BlastShader.quake((float) Mth.clamp(1.0 - this.distance / 900.0, 0.0, 1.0));
			}
			if (t == this.soundDelay) {
				float near = (float) Mth.clamp(1.25 - this.distance / 2200.0, 0.15, 1.0);
				shockCrack(mc, ModRegistry.SHOCK_HEAVY, this.pos, this.distance, 2400.0, 0.85F);
				playDistant(mc, ModRegistry.SONIC_BOOM, this.pos, near, 0.8F);
				playDistant(mc, this.distance < 300 ? ModRegistry.EXPLOSION_NEAR : ModRegistry.EXPLOSION_FAR, this.pos, near, 0.6F);
				playDistant(mc, ModRegistry.EXPLOSION_SUB, this.pos, near, 0.5F);
				playDistant(mc, ModRegistry.EXPLOSION_DEBRIS, this.pos, near, 0.7F);
				if (this.distance < 250) {
					deafen(mc, (float) (1.0 - this.distance / 300.0), 120);
				}
			}
			if (t == 0) {
				// the instant of impact: a white-hot point and a ring of shock-heated air
				shockSphere(new Vec3(cx, cy + 3, cz), 18.0, 100);
				fireball(new Vec3(cx, cy + 2, cz), 6.0, 40, 7.0F, 1.6, 12);
				embers(this.pos, 400, 3.4);
				debrisJets(mc, this.pos, 110, 4.2, 6.5F);
			}
			// ---- the ejecta curtain: an inverted cone of rock and dust thrown out steeply
			if (t < 24) {
				double r = 4.0 + t * 2.2;
				for (int i = 0; i < 70; i++) {
					double a = rand() * Mth.TWO_PI;
					double h = rand() * r * 1.4;
					double rr = r * 0.5 + h * 0.55;
					CloudParticle p = cloud(false, cx + Math.cos(a) * rr, cy + h, cz + Math.sin(a) * rr, Math.cos(a) * 0.7, 1.0 + rand(), Math.sin(a) * 0.7);
					if (p != null) {
						p.configure(life(110, 70), 3.5F, 9.0F, mix(0x8A7A66, 0x6C645A, rand()), 0x58524C, 0.9F).physics(0.95F, -0.03F).turbulence(0.03F);
					}
					if (i % 3 == 0) {
						vanilla(new BlockParticleOption(ParticleTypes.BLOCK, this.ground), cx + Math.cos(a) * rr, cy + h, cz + Math.sin(a) * rr,
							Math.cos(a) * 0.8, 1.2 + rand(), Math.sin(a) * 0.8);
					}
				}
			}
			// ---- the pillar of dust over the crater
			if (t > 4 && t < 140) {
				double top = 110.0 * (1.0 - Math.exp(-(t - 4) / 35.0));
				for (int i = 0; i < (t < 50 ? 20 : 8); i++) {
					double a = rand() * Mth.TWO_PI;
					double r = 12.0 * Math.sqrt(rand());
					CloudParticle p = cloud(false, cx + Math.cos(a) * r, cy + rand() * top, cz + Math.sin(a) * r, 0, 0.6 + rand() * 0.4, 0);
					if (p != null) {
						p.configure(life(200, 100), 6.0F, 14.0F, mix(0x9A9286, 0x7E786E, rand()), 0x66625C, 0.9F)
							.physics(0.97F, 0.002F).litFrom(cx, cy + top, cz).turbulence(0.04F).wind(0.5F);
					}
				}
			}
			// ---- the ground shock skirt, and the molten floor glowing for a while
			if (t < 60) {
				groundRing(mc, this.pos, 8.0 + t * 3.0, 40, 10.0F, 1.2, 0xA8A090);
			}
			if (t < 400 && t % 3 == 0) {
				for (int i = 0; i < 4; i++) {
					vanilla(ParticleTypes.LAVA, cx + gauss() * 4, cy - 6 + rand() * 4, cz + gauss() * 4, 0, 0, 0);
				}
				CloudParticle p = cloud(true, cx + gauss() * 4, cy - 4, cz + gauss() * 4, 0, 0.12, 0);
				if (p != null) {
					p.configure(60, 2.0F, 4.0F, 0xFFB060, 0xA03010, 0.6F * (1.0F - t / 400.0F)).cooling(0.5F, 0x3A3632);
				}
			}
			return t > Math.max(this.soundDelay + 2, 400);
		}
	}
}
