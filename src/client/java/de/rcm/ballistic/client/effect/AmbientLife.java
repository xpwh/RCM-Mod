package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The life in a peaceful landscape, round the player: butterflies over the flowers by day, flocks of birds high
 * up, fireflies in warm summer nights, a fish leaping now and then, deer grazing in the clearings. None of it is
 * a real entity - it is drawn and moved here, on the client, and only where it can be seen.
 * <p>
 * And it is shy. A shot, an explosion ({@link #disturb}) sends it away: the butterflies flutter off, the
 * fireflies go out, birds burst out of the trees and the flocks scatter, the deer bound off - and for a few
 * minutes nothing comes back there. The quiet after the gunfire is a silence.
 */
public final class AmbientLife {
	private static final Identifier TEX = BallisticMissiles.id("textures/effect/wildlife.png");
	private static final RenderType SOLID = RenderTypes.entityCutoutNoCull(TEX);
	private static final RenderType GLOW = RenderTypes.entityTranslucentEmissive(TEX);
	private static final float TILE = 1.0F / 8.0F;
	private static final RandomSource R = RandomSource.create();

	private record Quiet(Vec3 at, double radius, long until) {
	}

	private static final List<Quiet> QUIET = new ArrayList<>();
	private static final List<Butterfly> BUTTERFLIES = new ArrayList<>();
	private static final List<Firefly> FIREFLIES = new ArrayList<>();
	private static final List<Flock> FLOCKS = new ArrayList<>();
	private static final List<Leap> LEAPS = new ArrayList<>();
	private static final List<Deer> DEER = new ArrayList<>();
	private static final List<Seed> SEEDS = new ArrayList<>();
	private static long tick;

	private AmbientLife() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(AmbientLife::tick);
		WorldRenderEvents.AFTER_ENTITIES.register(AmbientLife::render);
	}

	// ------------------------------------------------------------------ disturbance

	/** A shot fired (suppressed or not) at {@code at}. */
	public static void shot(Vec3 at, boolean suppressed) {
		disturb(at, suppressed ? 22.0 : 70.0, suppressed ? 900 : 2400);
	}

	/**
	 * Something loud at {@code at}: everything within {@code radius} flees or goes out, and for {@code quietTicks}
	 * nothing new comes there; birds sitting in the trees nearby burst out of them.
	 */
	public static void disturb(Vec3 at, double radius, int quietTicks) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		boolean already = quiet(at);
		NightSounds.hush(at, radius, quietTicks);
		QUIET.add(new Quiet(at, radius, level.getGameTime() + quietTicks));
		double r2 = radius * radius;
		for (Butterfly b : BUTTERFLIES) {
			if (b.distSqr(at) < r2) {
				b.flee(at);
			}
		}
		for (Firefly f : FIREFLIES) {
			if (f.distSqr(at) < r2) {
				f.out = true;
			}
		}
		for (Flock f : FLOCKS) {
			if (f.distSqr(at) < r2 * 2.5) {
				f.scatter(at);
			}
		}
		for (Deer d : DEER) {
			if (d.distSqr(at) < r2 * 1.4) {
				d.flee(at);
			}
		}
		// the birds in the trees nearby take off all at once (once: a place already quiet has none left)
		if (!already) {
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int i = 0; i < 40; i++) {
				m.set(at.x + R.nextGaussian() * 10.0, at.y + 3.0 + R.nextFloat() * 10.0, at.z + R.nextGaussian() * 10.0);
				if (level.getBlockState(m).is(BlockTags.LEAVES) && level.getBlockState(m.above()).isAir()) {
					Flock f = new Flock(m.getX() + 0.5, m.getY() + 1.0, m.getZ() + 0.5, 6 + R.nextInt(10), 0.0F);
					f.scatter(at);
					FLOCKS.add(f);
					level.playLocalSound(m.getX(), m.getY(), m.getZ(), SoundEvents.PARROT_FLY, SoundSource.AMBIENT, 1.2F, 0.7F + R.nextFloat() * 0.2F, false);
					level.playLocalSound(m.getX(), m.getY(), m.getZ(), SoundEvents.PARROT_FLY, SoundSource.AMBIENT, 1.0F, 0.9F, false);
					break;
				}
			}
		}
	}

	private static boolean quiet(Vec3 p) {
		for (Quiet q : QUIET) {
			if (q.at().distanceToSqr(p) < q.radius() * q.radius()) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ the creatures

	private abstract static class Creature {
		double x;
		double y;
		double z;
		double px;
		double py;
		double pz;
		int age;
		boolean gone;

		void last() {
			this.px = this.x;
			this.py = this.y;
			this.pz = this.z;
			this.age++;
		}

		double distSqr(Vec3 p) {
			return (this.x - p.x) * (this.x - p.x) + (this.y - p.y) * (this.y - p.y) + (this.z - p.z) * (this.z - p.z);
		}
	}

	/** Pollen, a dandelion seed, a bit of fluff: carried along on the wind, rising and sinking in the gusts. */
	private static final class Seed extends Creature {
		final float size;
		final float phase;
		final int life;

		Seed(double x, double y, double z) {
			this.x = this.px = x;
			this.y = this.py = y;
			this.z = this.pz = z;
			this.size = 0.025F + R.nextFloat() * 0.035F;
			this.phase = R.nextFloat() * 100.0F;
			this.life = 200 + R.nextInt(260);
		}

		void tick(ClientLevel level, float seconds, float dir, float strength) {
			this.last();
			float drift = 0.02F + 0.07F * strength;
			float t = this.age + this.phase;
			this.x += Mth.cos(dir) * drift + Mth.sin(t * 0.13F) * 0.008;
			this.z += Mth.sin(dir) * drift + Mth.cos(t * 0.11F) * 0.008;
			this.y += Mth.sin(t * 0.07F) * 0.012 - 0.002;
			if (!level.getBlockState(BlockPos.containing(this.x, this.y, this.z)).isAir()) {
				this.y += 0.05; // over the grass, not into it
			}
			this.gone = this.age > this.life;
		}

		float alpha(float partial) {
			float a = this.age + partial;
			return Mth.clamp(a / 30.0F, 0.0F, 1.0F) * Mth.clamp((this.life - a) / 30.0F, 0.0F, 1.0F);
		}
	}

	/** A butterfly: flutters about its flower patch, lands on a flower now and then, wings slowly opening and closing. */
	private static final class Butterfly extends Creature {
		final int species;
		final double homeX;
		final double homeZ;
		float yaw;
		float flap;
		int landed;
		boolean fleeing;
		double fleeX;
		double fleeZ;

		Butterfly(double x, double y, double z, int species) {
			this.x = this.px = x;
			this.y = this.py = y;
			this.z = this.pz = z;
			this.homeX = x;
			this.homeZ = z;
			this.species = species;
			this.yaw = R.nextFloat() * Mth.TWO_PI;
		}

		void flee(Vec3 from) {
			this.fleeing = true;
			this.landed = 0;
			double dx = this.x - from.x;
			double dz = this.z - from.z;
			double d = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
			this.fleeX = dx / d;
			this.fleeZ = dz / d;
		}

		void tick(ClientLevel level) {
			this.last();
			if (this.fleeing) {
				this.flap += 1.4F;
				this.x += this.fleeX * 0.22 + R.nextGaussian() * 0.03;
				this.z += this.fleeZ * 0.22 + R.nextGaussian() * 0.03;
				this.y += 0.08 + R.nextGaussian() * 0.02;
				this.yaw = (float) Mth.atan2(this.fleeZ, this.fleeX);
				this.gone = this.age > 600 || this.distSqrHome() > 40 * 40;
				return;
			}
			if (this.landed > 0) {
				// sitting on a flower, the wings opening and closing slowly
				this.landed--;
				this.flap += 0.12F;
				return;
			}
			this.flap += 1.05F;
			this.yaw += (R.nextFloat() - 0.5F) * 0.7F;
			// back towards the patch if it strays
			double hx = this.homeX - this.x;
			double hz = this.homeZ - this.z;
			if (hx * hx + hz * hz > 36.0) {
				this.yaw = (float) Mth.atan2(hz, hx) + (R.nextFloat() - 0.5F) * 0.6F;
			}
			int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(this.x), Mth.floor(this.z));
			double want = ground + 0.6 + 0.9 * (0.5 + 0.5 * Mth.sin(this.age * 0.05F + this.species));
			this.x += Mth.cos(this.yaw) * 0.065;
			this.z += Mth.sin(this.yaw) * 0.065;
			this.y += (want - this.y) * 0.08 + Mth.sin(this.flap * 0.5F) * 0.035;
			// a flower right underneath: land on it now and then
			BlockPos below = BlockPos.containing(this.x, ground, this.z);
			if (R.nextFloat() < 0.012F && level.getBlockState(below).is(BlockTags.FLOWERS)) {
				this.x = below.getX() + 0.5;
				this.z = below.getZ() + 0.5;
				this.y = below.getY() + 0.62;
				this.landed = 40 + R.nextInt(140);
			}
		}

		double distSqrHome() {
			return (this.x - this.homeX) * (this.x - this.homeX) + (this.z - this.homeZ) * (this.z - this.homeZ);
		}
	}

	/** A firefly: drifts low over the grass, its light swelling and fading in its own slow rhythm. */
	private static final class Firefly extends Creature {
		final float period;
		final float phase;
		double vx;
		double vy;
		double vz;
		boolean out;
		float fade = 1.0F;

		Firefly(double x, double y, double z) {
			this.x = this.px = x;
			this.y = this.py = y;
			this.z = this.pz = z;
			this.period = 40.0F + R.nextFloat() * 70.0F;
			this.phase = R.nextFloat() * 100.0F;
		}

		void tick() {
			this.last();
			this.vx = this.vx * 0.92 + R.nextGaussian() * 0.004;
			this.vy = this.vy * 0.9 + R.nextGaussian() * 0.003;
			this.vz = this.vz * 0.92 + R.nextGaussian() * 0.004;
			this.x += this.vx;
			this.y += this.vy;
			this.z += this.vz;
			if (this.out) {
				this.fade -= 0.08F;
				this.gone = this.fade <= 0.0F;
			}
			this.gone |= this.age > 2400;
		}

		float glow(float partial) {
			float t = (this.age + partial + this.phase) / this.period;
			float pulse = Mth.clamp(Mth.sin(t * Mth.TWO_PI) * 1.6F - 0.3F, 0.0F, 1.0F);
			return pulse * this.fade * Mth.clamp(this.age / 40.0F, 0.0F, 1.0F);
		}
	}

	/** A bird in a flock: its place in the flock, its own wingbeat. */
	private static final class Bird {
		double ox;
		double oy;
		double oz;
		float phase;
		float glide;
	}

	/** A flock of birds high up, wheeling slowly across the sky; scattered, it flees fast and climbs away. */
	private static final class Flock extends Creature {
		final List<Bird> birds = new ArrayList<>();
		float heading;
		float turn;
		float speed = 0.32F;
		boolean scattered;

		Flock(double x, double y, double z, int count, float heading) {
			this.x = this.px = x;
			this.y = this.py = y;
			this.z = this.pz = z;
			this.heading = heading;
			this.turn = (R.nextFloat() - 0.5F) * 0.01F;
			for (int i = 0; i < count; i++) {
				Bird b = new Bird();
				// a loose V or cloud
				int row = i / 2 + 1;
				b.ox = -row * 1.6 + R.nextGaussian() * 0.8;
				b.oz = (i % 2 == 0 ? 1 : -1) * row * 1.3 + R.nextGaussian() * 0.8;
				b.oy = R.nextGaussian() * 0.8;
				b.phase = R.nextFloat() * 10.0F;
				this.birds.add(b);
			}
		}

		void scatter(Vec3 from) {
			if (!this.scattered) {
				this.heading = (float) Mth.atan2(this.z - from.z, this.x - from.x) + (R.nextFloat() - 0.5F) * 0.8F;
				for (Bird b : this.birds) {
					b.ox *= 1.6;
					b.oz *= 1.6;
				}
			}
			this.scattered = true;
			this.speed = 0.75F;
		}

		void tick(Vec3 player) {
			this.last();
			this.heading += this.turn + (this.scattered ? 0.0F : Mth.sin(this.age * 0.01F) * 0.004F);
			this.x += Mth.cos(this.heading) * this.speed;
			this.z += Mth.sin(this.heading) * this.speed;
			if (this.scattered) {
				this.y += 0.16;
			}
			for (Bird b : this.birds) {
				b.phase += this.scattered ? 0.9F : 0.45F;
				b.glide = this.scattered ? 0.0F : Mth.clamp(Mth.sin(b.phase * 0.05F) * 3.0F, 0.0F, 1.0F);
				b.oy += R.nextGaussian() * 0.03;
				b.oy *= 0.98;
			}
			double dx = this.x - player.x;
			double dz = this.z - player.z;
			this.gone = dx * dx + dz * dz > 190 * 190 || this.age > 6000;
		}
	}

	/** A fish leaping out of the water: an arc, a splash going in and one coming down. */
	private static final class Leap extends Creature {
		final double sx;
		final double sz;
		final double surface;
		final float dir;
		final int length;
		float pitch;

		Leap(double x, double surface, double z) {
			this.sx = x;
			this.sz = z;
			this.surface = surface;
			this.dir = R.nextFloat() * Mth.TWO_PI;
			this.length = 12 + R.nextInt(8);
			this.x = this.px = x;
			this.y = this.py = surface;
			this.z = this.pz = z;
		}

		void tick(ClientLevel level) {
			this.last();
			float t = this.age / (float) this.length;
			double reach = 1.2 + this.length * 0.04;
			this.x = this.sx + Mth.cos(this.dir) * reach * t;
			this.z = this.sz + Mth.sin(this.dir) * reach * t;
			this.y = this.surface - 0.2 + Mth.sin(t * Mth.PI) * (0.6 + this.length * 0.03);
			this.pitch = Mth.cos(t * Mth.PI) * 1.1F;
			if (this.age == 1 || this.age == this.length) {
				for (int i = 0; i < 8; i++) {
					level.addParticle(ParticleTypes.SPLASH, this.x, this.surface + 0.05, this.z, R.nextGaussian() * 0.1, 0.15, R.nextGaussian() * 0.1);
				}
				level.playLocalSound(this.x, this.surface, this.z, SoundEvents.GENERIC_SPLASH, SoundSource.AMBIENT, 0.25F, 1.5F + R.nextFloat() * 0.3F, false);
			}
			this.gone = this.age > this.length;
		}
	}

	/** A deer: grazes in its clearing, looks up when someone comes near, bounds off when they come close or shoot. */
	private static final class Deer extends Creature {
		final boolean stag;
		final float size;
		float yaw;
		float pyaw;
		float stride;
		float head;
		float phead;
		int timer;
		boolean walking;
		boolean fleeing;
		double fleeX;
		double fleeZ;

		Deer(double x, double y, double z) {
			this.x = this.px = x;
			this.y = this.py = y;
			this.z = this.pz = z;
			this.stag = R.nextFloat() < 0.35F;
			this.size = (this.stag ? 1.1F : 0.95F) * (0.92F + R.nextFloat() * 0.16F);
			this.yaw = this.pyaw = R.nextFloat() * Mth.TWO_PI;
			this.head = this.phead = 1.0F;
		}

		void flee(Vec3 from) {
			if (!this.fleeing) {
				double dx = this.x - from.x;
				double dz = this.z - from.z;
				double d = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
				this.fleeX = dx / d;
				this.fleeZ = dz / d;
			}
			this.fleeing = true;
		}

		void tick(ClientLevel level, Vec3 player) {
			this.last();
			this.pyaw = this.yaw;
			this.phead = this.head;
			double dx = player.x - this.x;
			double dz = player.z - this.z;
			double d = Math.sqrt(dx * dx + dz * dz);
			if (!this.fleeing && d < 9.0) {
				this.flee(player);
			}
			double speed;
			float wantYaw = this.yaw;
			float wantHead;
			if (this.fleeing) {
				speed = 0.42;
				wantYaw = (float) Mth.atan2(this.fleeZ, this.fleeX);
				wantHead = 0.0F;
				this.gone = d > 90.0 || this.age > 3000;
			} else if (d < 22.0) {
				// alert: head up, turned towards whoever comes
				speed = 0.0;
				wantYaw = (float) Mth.atan2(dz, dx);
				wantHead = 0.0F;
			} else {
				if (--this.timer <= 0) {
					this.walking = R.nextFloat() < 0.35F;
					this.timer = 60 + R.nextInt(160);
					if (this.walking) {
						this.yaw += (R.nextFloat() - 0.5F) * 2.0F;
					}
				}
				speed = this.walking ? 0.045 : 0.0;
				wantYaw = this.yaw;
				wantHead = this.walking ? 0.2F : 1.0F; // grazing: head down
				this.gone = d > 110.0;
			}
			this.yaw += Mth.wrapDegrees((wantYaw - this.yaw) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD * (this.fleeing ? 0.3F : 0.1F);
			this.head += (wantHead - this.head) * 0.12F;
			if (speed > 0.0) {
				double nx = this.x + Mth.cos(this.yaw) * speed;
				double nz = this.z + Mth.sin(this.yaw) * speed;
				int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(nx), Mth.floor(nz));
				BlockState under = level.getBlockState(BlockPos.containing(nx, ground - 1, nz));
				boolean water = !under.getFluidState().isEmpty();
				if (ground - this.y > 1.6 || water) {
					// a wall or water ahead: turn aside
					this.yaw += (this.fleeing ? 0.9F : 2.4F) * (R.nextBoolean() ? 1 : -1);
					if (this.fleeing) {
						this.fleeX = Mth.cos(this.yaw);
						this.fleeZ = Mth.sin(this.yaw);
					}
				} else {
					this.x = nx;
					this.z = nz;
					this.y += (ground - this.y) * (ground > this.y ? 0.6 : 0.35);
				}
				this.stride += (float) speed * (this.fleeing ? 1.6F : 3.0F);
			}
		}
	}

	// ------------------------------------------------------------------ tick: spawning and moving

	/** How many of a kind at most, by the nature quality setting. */
	private static int cap(int high) {
		return switch (de.rcm.ballistic.client.ModConfig.natureQuality) {
			case LOW -> Math.max(1, high / 3);
			case MEDIUM -> Math.max(1, high * 2 / 3);
			default -> high;
		};
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null || mc.isPaused() || !de.rcm.ballistic.client.ModConfig.wildlife) {
			if (level == null || !de.rcm.ballistic.client.ModConfig.wildlife) {
				BUTTERFLIES.clear();
				FIREFLIES.clear();
				FLOCKS.clear();
				LEAPS.clear();
				DEER.clear();
				SEEDS.clear();
				QUIET.clear();
			}
			return;
		}
		tick++;
		long now = level.getGameTime();
		QUIET.removeIf(q -> q.until() < now);
		Vec3 p = mc.player.position();
		long t = level.getDayTime() % 24000L;
		boolean day = t > 600L && t < 11800L;
		boolean night = t > 13300L && t < 22700L;
		boolean rain = level.isRaining();
		boolean overworld = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();

		BUTTERFLIES.removeIf(b -> b.gone || b.distSqr(p) > 48 * 48);
		for (Butterfly b : BUTTERFLIES) {
			if (!b.fleeing && (!day || rain)) {
				b.flee(p.add(R.nextGaussian(), 0, R.nextGaussian())); // dusk or rain: off to shelter
			}
			b.tick(level);
		}
		FIREFLIES.removeIf(f -> f.gone || f.distSqr(p) > 40 * 40);
		for (Firefly f : FIREFLIES) {
			if (!night || rain) {
				f.out = true;
			}
			f.tick();
		}
		FLOCKS.removeIf(f -> f.gone);
		for (Flock f : FLOCKS) {
			f.tick(p);
		}
		LEAPS.removeIf(l -> l.gone);
		for (Leap l : LEAPS) {
			l.tick(level);
		}
		DEER.removeIf(d -> d.gone);
		for (Deer d : DEER) {
			d.tick(level, p);
		}
		float seconds = ((now % 72000L)) / 20.0F;
		float windDir = Wind.direction(seconds);
		float windStrength = Wind.strength(seconds, 1.0F);
		SEEDS.removeIf(sd -> sd.gone || sd.distSqr(p) > 36 * 36);
		for (Seed sd : SEEDS) {
			sd.tick(level, seconds, windDir, windStrength);
		}
		if (!overworld || mc.player.isSpectator()) {
			return;
		}
		BlockPos at = mc.player.blockPosition();
		Biome biome = level.getBiome(at).value();
		float warmth = biome.getBaseTemperature();
		boolean wild = !level.getBiome(at).is(BiomeTags.IS_OCEAN) && warmth > 0.15F && warmth < 1.5F;

		// butterflies: over flowers by day
		if (day && !rain && wild && BUTTERFLIES.size() < cap(22) && tick % 3 == 0) {
			int x = Mth.floor(p.x + (R.nextFloat() - 0.5F) * 40.0F);
			int z = Mth.floor(p.z + (R.nextFloat() - 0.5F) * 40.0F);
			int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			BlockPos flower = new BlockPos(x, h - 1, z);
			Vec3 spot = Vec3.atCenterOf(flower);
			if (level.getBlockState(flower).is(BlockTags.FLOWERS) && !quiet(spot)) {
				int species = R.nextInt(4);
				for (int i = 0, n = 1 + R.nextInt(2); i < n; i++) {
					BUTTERFLIES.add(new Butterfly(x + 0.5 + R.nextGaussian(), h + 0.4 + R.nextFloat(), z + 0.5 + R.nextGaussian(), species));
				}
			}
		}
		// fireflies: warm, dry nights, low over grass, plants and water
		if (night && !rain && warmth >= 0.5F && warmth < 1.5F && FIREFLIES.size() < cap(60) && tick % 2 == 0) {
			int x = Mth.floor(p.x + (R.nextFloat() - 0.5F) * 44.0F);
			int z = Mth.floor(p.z + (R.nextFloat() - 0.5F) * 44.0F);
			int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockState ground = level.getBlockState(new BlockPos(x, h - 1, z));
			Vec3 spot = new Vec3(x + R.nextFloat(), h + 0.3 + R.nextFloat() * 2.2, z + R.nextFloat());
			if ((ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.MOSS_BLOCK) || !ground.getFluidState().isEmpty()) && !quiet(spot)
				&& level.canSeeSky(BlockPos.containing(spot))) {
				FIREFLIES.add(new Firefly(spot.x, spot.y, spot.z));
			}
		}
		// pollen and seeds on the wind over meadows, warm days
		if (day && !rain && wild && warmth >= 0.5F && SEEDS.size() < cap(45) && tick % 2 == 0) {
			// upwind, so they come drifting past
			float a = windDir + Mth.PI + (R.nextFloat() - 0.5F) * 2.4F;
			double d = 4.0 + R.nextFloat() * 18.0;
			int x = Mth.floor(p.x + Mth.cos(a) * d);
			int z = Mth.floor(p.z + Mth.sin(a) * d);
			int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockState ground = level.getBlockState(new BlockPos(x, h - 1, z));
			BlockState plant = level.getBlockState(new BlockPos(x, h, z));
			Vec3 spot = new Vec3(x + R.nextFloat(), h + 0.4 + R.nextFloat() * 3.0, z + R.nextFloat());
			if ((ground.is(Blocks.GRASS_BLOCK) || plant.is(BlockTags.FLOWERS) || plant.is(Blocks.SHORT_GRASS) || plant.is(Blocks.TALL_GRASS))
				&& !quiet(spot)) {
				SEEDS.add(new Seed(spot.x, spot.y, spot.z));
			}
		}
		// a flock of birds crossing the sky
		if (day && wild && FLOCKS.size() < 3 && tick % 160 == 0 && R.nextFloat() < 0.55F) {
			float a = R.nextFloat() * Mth.TWO_PI;
			double fx = p.x + Mth.cos(a) * 110.0;
			double fz = p.z + Mth.sin(a) * 110.0;
			int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(p.x), Mth.floor(p.z));
			Vec3 spot = new Vec3(fx, h + 30 + R.nextFloat() * 25.0F, fz);
			if (!quiet(spot) && !quiet(p)) {
				// across the sky past the player, not straight at them
				float heading = a + Mth.PI + (R.nextFloat() - 0.5F) * 1.2F;
				FLOCKS.add(new Flock(spot.x, spot.y, spot.z, 7 + R.nextInt(18), heading));
			}
		}
		// a fish leaping
		if (!rain && LEAPS.size() < 3 && tick % 15 == 0 && R.nextFloat() < 0.3F) {
			int x = Mth.floor(p.x + (R.nextFloat() - 0.5F) * 40.0F);
			int z = Mth.floor(p.z + (R.nextFloat() - 0.5F) * 40.0F);
			int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
			BlockPos water = new BlockPos(x, top - 1, z);
			if (top - floor >= 2 && level.getBlockState(water).is(Blocks.WATER) && !quiet(Vec3.atCenterOf(water))) {
				LEAPS.add(new Leap(x + 0.5, top - 0.1, z + 0.5));
			}
		}
		// deer: in clearings of the woods and meadows, by day and at dusk
		boolean deerTime = t < 13000L || t > 22500L;
		boolean deerLand = level.getBiome(at).is(BiomeTags.IS_FOREST) || level.getBiome(at).is(BiomeTags.IS_TAIGA)
			|| warmth >= 0.5F && warmth <= 0.95F;
		if (deerTime && deerLand && wild && DEER.size() < cap(6) && tick % 80 == 0 && R.nextFloat() < 0.45F) {
			float a = R.nextFloat() * Mth.TWO_PI;
			double dist = 34.0 + R.nextFloat() * 26.0;
			int x = Mth.floor(p.x + Mth.cos(a) * dist);
			int z = Mth.floor(p.z + Mth.sin(a) * dist);
			int open = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			Vec3 spot = new Vec3(x + 0.5, h, z + 0.5);
			if (open == h && level.getBlockState(new BlockPos(x, h - 1, z)).is(Blocks.GRASS_BLOCK) && !quiet(spot)) {
				for (int i = 0, n = 1 + R.nextInt(3); i < n; i++) {
					double ox = x + 0.5 + R.nextGaussian() * 2.5;
					double oz = z + 0.5 + R.nextGaussian() * 2.5;
					int oh = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(ox), Mth.floor(oz));
					if (Math.abs(oh - h) <= 1) {
						DEER.add(new Deer(ox, oh, oz));
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ drawing

	/** Draws quads and boxes through a transform, with one light. */
	private static final class Emit {
		final VertexConsumer c;
		final Matrix4f m;
		final int light;

		Emit(VertexConsumer c, Matrix4f m, int light) {
			this.c = c;
			this.m = m;
			this.light = light;
		}

		void vertex(float x, float y, float z, float u, float v, int color, float ny) {
			this.c.addVertex(this.m, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(this.light).setNormal(0.0F, ny, 0.0F);
		}

		/** A flat quad in the local x-z plane: from x0 to x1 across, z0 to z1 along, at height y. */
		void flat(float x0, float x1, float z0, float z1, float y, int tile, boolean flipU, int color) {
			float u0 = tile * TILE;
			float u1 = u0 + TILE;
			if (flipU) {
				float s = u0;
				u0 = u1;
				u1 = s;
			}
			this.vertex(x0, y, z0, u0, 0.0F, color, 1.0F);
			this.vertex(x0, y, z1, u0, 1.0F, color, 1.0F);
			this.vertex(x1, y, z1, u1, 1.0F, color, 1.0F);
			this.vertex(x1, y, z0, u1, 0.0F, color, 1.0F);
		}

		/** A box in the white tile, shaded by its faces. */
		void box(float x0, float y0, float z0, float x1, float y1, float z1, int rgb) {
			float u0 = 6 * TILE + 0.01F;
			float u1 = 7 * TILE - 0.01F;
			int top = shade(rgb, 1.0F);
			int side = shade(rgb, 0.8F);
			int end = shade(rgb, 0.68F);
			int bottom = shade(rgb, 0.5F);
			this.face(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, u0, u1, top, 1.0F);
			this.face(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, u0, u1, bottom, -1.0F);
			this.face(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, u0, u1, side, 0.0F);
			this.face(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, u0, u1, side, 0.0F);
			this.face(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, u0, u1, end, 0.0F);
			this.face(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, u0, u1, end, 0.0F);
		}

		private void face(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz, float u0,
			float u1, int color, float ny) {
			this.vertex(ax, ay, az, u0, 0.0F, color, ny);
			this.vertex(bx, by, bz, u0, 1.0F, color, ny);
			this.vertex(cx, cy, cz, u1, 1.0F, color, ny);
			this.vertex(dx, dy, dz, u1, 0.0F, color, ny);
		}
	}

	private static int shade(int rgb, float f) {
		int r = (int) (((rgb >> 16) & 255) * f);
		int g = (int) (((rgb >> 8) & 255) * f);
		int b = (int) ((rgb & 255) * f);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || BUTTERFLIES.isEmpty() && FIREFLIES.isEmpty() && FLOCKS.isEmpty() && LEAPS.isEmpty() && DEER.isEmpty() && SEEDS.isEmpty()) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		var poseStack = context.matrices();
		context.commandQueue().submitCustomGeometry(poseStack, SOLID, (pose, consumer) -> {
			for (Butterfly b : BUTTERFLIES) {
				drawButterfly(level, consumer, pose.pose(), b, cam, partial);
			}
			for (Flock f : FLOCKS) {
				drawFlock(level, consumer, pose.pose(), f, cam, partial);
			}
			for (Leap l : LEAPS) {
				drawFish(level, consumer, pose.pose(), l, cam, partial);
			}
			for (Deer d : DEER) {
				drawDeer(level, consumer, pose.pose(), d, cam, partial);
			}
		});
		if (!FIREFLIES.isEmpty() || !SEEDS.isEmpty()) {
			var camera = mc.gameRenderer.getMainCamera();
			org.joml.Vector3f up = new org.joml.Vector3f(camera.upVector());
			org.joml.Vector3f right = new org.joml.Vector3f(camera.leftVector()).negate();
			context.commandQueue().submitCustomGeometry(poseStack, GLOW, (pose, consumer) -> {
				for (Seed sd : SEEDS) {
					float a = sd.alpha(partial) * 0.75F;
					if (a < 0.02F) {
						continue;
					}
					float x = (float) (Mth.lerp(partial, sd.px, sd.x) - cam.x);
					float y = (float) (Mth.lerp(partial, sd.py, sd.y) - cam.y);
					float z = (float) (Mth.lerp(partial, sd.pz, sd.z) - cam.z);
					billboard(consumer, pose.pose(), x, y, z, right, up, sd.size, (int) (a * 255.0F) << 24 | 0xFFF6DC);
				}
				for (Firefly f : FIREFLIES) {
					float g = f.glow(partial);
					if (g < 0.02F) {
						continue;
					}
					float x = (float) (Mth.lerp(partial, f.px, f.x) - cam.x);
					float y = (float) (Mth.lerp(partial, f.py, f.y) - cam.y);
					float z = (float) (Mth.lerp(partial, f.pz, f.z) - cam.z);
					int a = (int) (g * 255.0F);
					billboard(consumer, pose.pose(), x, y, z, right, up, 0.32F, a << 24 | 0xD8FF70);
					billboard(consumer, pose.pose(), x, y, z, right, up, 0.08F, a << 24 | 0xFFFFE0);
				}
			});
		}
	}

	private static void billboard(VertexConsumer c, Matrix4f m, float x, float y, float z, org.joml.Vector3f right, org.joml.Vector3f up, float size, int color) {
		float u0 = 5 * TILE;
		float u1 = u0 + TILE;
		float[][] q = {{-1, -1, u0, 1}, {1, -1, u1, 1}, {1, 1, u1, 0}, {-1, 1, u0, 0}};
		for (float[] p : q) {
			c.addVertex(m, x + (right.x * p[0] + up.x * p[1]) * size, y + (right.y * p[0] + up.y * p[1]) * size, z + (right.z * p[0] + up.z * p[1]) * size)
				.setColor(color).setUv(p[2], p[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(0.0F, 1.0F, 0.0F);
		}
	}

	private static int light(ClientLevel level, double x, double y, double z) {
		return LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
	}

	private static void drawButterfly(ClientLevel level, VertexConsumer c, Matrix4f base, Butterfly b, Vec3 cam, float partial) {
		double x = Mth.lerp(partial, b.px, b.x);
		double y = Mth.lerp(partial, b.py, b.y);
		double z = Mth.lerp(partial, b.pz, b.z);
		if ((x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z) > 40 * 40) {
			return;
		}
		int light = light(level, x, y, z);
		float flap = b.landed > 0 ? 0.25F + 0.55F * (0.5F + 0.5F * Mth.sin(b.flap)) : 0.15F + 1.15F * (0.5F + 0.5F * Mth.sin(b.flap));
		float span = 0.16F;
		for (int side = -1; side <= 1; side += 2) {
			Matrix4f m = new Matrix4f(base).translate((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z))
				.rotateY(-b.yaw + Mth.HALF_PI).rotateZ(side * flap);
			Emit e = new Emit(c, m, light);
			e.flat(0.0F, side * span, -span * 0.5F, span * 0.5F, 0.0F, b.species, side < 0, 0xFFFFFFFF);
		}
		// the body
		Matrix4f m = new Matrix4f(base).translate((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).rotateY(-b.yaw + Mth.HALF_PI);
		new Emit(c, m, light).box(-0.008F, -0.008F, -0.06F, 0.008F, 0.008F, 0.06F, 0x2A2420);
	}

	private static void drawFlock(ClientLevel level, VertexConsumer c, Matrix4f base, Flock f, Vec3 cam, float partial) {
		double cx = Mth.lerp(partial, f.px, f.x);
		double cy = Mth.lerp(partial, f.py, f.y);
		double cz = Mth.lerp(partial, f.pz, f.z);
		float cos = Mth.cos(f.heading);
		float sin = Mth.sin(f.heading);
		int light = light(level, cx, cy, cz);
		for (Bird b : f.birds) {
			// the place in the flock, turned with its heading
			double x = cx + b.ox * cos - b.oz * sin;
			double z = cz + b.ox * sin + b.oz * cos;
			double y = cy + b.oy;
			float flap = b.glide > 0.5F ? 0.08F : Mth.sin(b.phase) * 0.7F;
			for (int side = -1; side <= 1; side += 2) {
				Matrix4f m = new Matrix4f(base).translate((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z))
					.rotateY(-f.heading + Mth.HALF_PI).rotateZ(side * flap);
				new Emit(c, m, light).flat(0.0F, side * 0.55F, -0.2F, 0.2F, 0.0F, 4, side < 0, 0xFFFFFFFF);
			}
			Matrix4f m = new Matrix4f(base).translate((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z)).rotateY(-f.heading + Mth.HALF_PI);
			new Emit(c, m, light).box(-0.05F, -0.05F, -0.22F, 0.05F, 0.05F, 0.2F, 0x262428);
		}
	}

	private static void drawFish(ClientLevel level, VertexConsumer c, Matrix4f base, Leap l, Vec3 cam, float partial) {
		double x = Mth.lerp(partial, l.px, l.x);
		double y = Mth.lerp(partial, l.py, l.y);
		double z = Mth.lerp(partial, l.pz, l.z);
		int light = light(level, x, y + 0.5, z);
		Matrix4f m = new Matrix4f(base).translate((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z))
			.rotateY(-l.dir + Mth.HALF_PI).rotateX(-l.pitch);
		Emit e = new Emit(c, m, light);
		e.box(-0.06F, -0.08F, -0.2F, 0.06F, 0.08F, 0.18F, 0x9AA6B0);
		e.box(-0.05F, 0.04F, -0.16F, 0.05F, 0.1F, 0.12F, 0x56636E); // the darker back
		// the tail fin, upright behind
		Matrix4f t = new Matrix4f(m).translate(0.0F, 0.0F, -0.2F).rotateZ(Mth.HALF_PI);
		new Emit(c, t, light).flat(-0.12F, 0.12F, -0.18F, 0.0F, 0.0F, 7, false, 0xFFFFFFFF);
	}

	private static void drawDeer(ClientLevel level, VertexConsumer c, Matrix4f base, Deer d, Vec3 cam, float partial) {
		double x = Mth.lerp(partial, d.px, d.x);
		double y = Mth.lerp(partial, d.py, d.y);
		double z = Mth.lerp(partial, d.pz, d.z);
		if ((x - cam.x) * (x - cam.x) + (z - cam.z) * (z - cam.z) > 120 * 120) {
			return;
		}
		float yaw = d.pyaw + Mth.wrapDegrees((d.yaw - d.pyaw) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD * partial;
		float head = Mth.lerp(partial, d.phead, d.head);
		int light = light(level, x, y + 1.0, z);
		float s = d.size;
		// bounding when it runs
		float bound = d.fleeing ? Math.abs(Mth.sin(d.stride * 0.9F)) * 0.35F : 0.0F;
		Matrix4f m = new Matrix4f(base).translate((float) (x - cam.x), (float) (y - cam.y) + bound, (float) (z - cam.z)).rotateY(-yaw + Mth.HALF_PI).scale(s);
		Emit e = new Emit(c, m, light);
		int coat = 0x8A5A30;
		int belly = 0xC8A47A;
		int legs = 0x5E3E22;
		// the body (+z forward)
		e.box(-0.17F, 0.72F, -0.46F, 0.17F, 1.08F, 0.44F, coat);
		e.box(-0.14F, 0.7F, -0.36F, 0.14F, 0.76F, 0.34F, belly);
		// the white scut
		e.box(-0.06F, 0.88F, -0.52F, 0.06F, 1.04F, -0.45F, 0xEDE6D8);
		// the legs, swinging as it walks or runs
		float swing = d.fleeing ? Mth.sin(d.stride * 0.9F) * 0.9F : Mth.sin(d.stride) * 0.5F;
		float[][] hips = {{-0.11F, 0.36F, 1.0F}, {0.11F, 0.36F, -1.0F}, {-0.11F, -0.36F, -1.0F}, {0.11F, -0.36F, 1.0F}};
		for (float[] h : hips) {
			float a = d.fleeing ? swing * (h[1] > 0 ? 1.0F : -1.0F) : swing * h[2];
			Matrix4f lm = new Matrix4f(m).translate(h[0], 0.76F, h[1]).rotateX(a);
			Emit le = new Emit(c, lm, light);
			le.box(-0.045F, -0.4F, -0.045F, 0.045F, 0.0F, 0.045F, coat);
			le.box(-0.03F, -0.76F, -0.03F, 0.03F, -0.4F, 0.03F, legs);
		}
		// the neck and head: up and alert, or down in the grass
		float pitch = -0.35F + head * 1.55F;
		Matrix4f nm = new Matrix4f(m).translate(0.0F, 1.0F, 0.36F).rotateX(pitch);
		Emit ne = new Emit(c, nm, light);
		ne.box(-0.07F, -0.06F, -0.02F, 0.07F, 0.48F, 0.12F, coat);
		Matrix4f hm = new Matrix4f(nm).translate(0.0F, 0.48F, 0.05F).rotateX(1.2F - pitch * 0.6F);
		Emit he = new Emit(c, hm, light);
		he.box(-0.08F, -0.07F, -0.06F, 0.08F, 0.09F, 0.18F, coat);
		he.box(-0.05F, -0.06F, 0.18F, 0.05F, 0.05F, 0.3F, 0x6E4A2A); // the muzzle
		he.box(-0.03F, -0.03F, 0.3F, 0.03F, 0.02F, 0.32F, 0x1C1410); // the nose
		he.box(-0.16F, 0.06F, -0.04F, -0.07F, 0.1F, 0.04F, coat); // the ears
		he.box(0.07F, 0.06F, -0.04F, 0.16F, 0.1F, 0.04F, coat);
		if (d.stag) {
			// the antlers: a beam each side, tines off it
			for (int side = -1; side <= 1; side += 2) {
				Matrix4f am = new Matrix4f(hm).translate(side * 0.05F, 0.09F, 0.0F).rotateZ(side * -0.45F);
				Emit ae = new Emit(c, am, light);
				ae.box(-0.015F, 0.0F, -0.015F, 0.015F, 0.34F, 0.015F, 0xD8C8A8);
				ae.box(-0.012F, 0.12F, 0.0F, 0.012F, 0.15F, 0.12F, 0xD8C8A8);
				ae.box(-0.012F, 0.25F, 0.0F, 0.012F, 0.28F, 0.1F, 0xD8C8A8);
			}
		}
	}
}
