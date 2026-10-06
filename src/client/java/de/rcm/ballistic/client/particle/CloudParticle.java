package de.rcm.ballistic.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Large, soft, long-living billboard used for smoke trails, fireballs and mushroom clouds.
 * <p>
 * Realism features:
 * <ul>
 *   <li>sprites are lit 3D cloud renders in several shapes x {@value #STAGES} dissolve stages (dense -> wispy)</li>
 *   <li>{@link #cooling}: a fire puff cools down and turns into dark smoke (fireballs roll into black smoke)</li>
 *   <li>{@link #litFrom}: dynamic self-shadowing - the side facing the sun is bright, the far side dark</li>
 *   <li>{@link #turbulence}: smooth swirling motion, plus a slow global wind drift</li>
 * </ul>
 * Effects create it through the particle engine and then tune it with the fluent setters.
 */
public class CloudParticle extends SingleQuadParticle {
	public static final int STAGES = 4;
	public static final int SMOKE_SHAPES = 8;
	public static final int FIRE_SHAPES = 4;

	/** Smoke sprites, so fire particles can turn into smoke. Set when the smoke provider is created. */
	private static @Nullable SpriteSet smokeSprites;

	/** Slow wind every non-fire puff drifts with (blocks/tick). */
	private static final double WIND_X = 0.018;
	private static final double WIND_Z = 0.007;

	private SpriteSet sprites;
	private int shapes;
	private final int shape;
	private boolean emissive;
	private final float jitter;
	private float startSize;
	private float endSize;
	private float r0 = 1, g0 = 1, b0 = 1;
	private float r1 = 1, g1 = 1, b1 = 1;
	private float maxAlpha = 0.9F;
	private float buoyancy;
	private float spin;
	private float shade = 1.0F;

	// cooling (fire -> smoke)
	private int coolAt = -1;
	private float coolR = 0.2F, coolG = 0.19F, coolB = 0.18F;
	private boolean cooled;

	// dynamic lighting
	private double lcx, lcy, lcz;
	private boolean lit;

	// turbulence
	private float turb;
	private final float phaseA;
	private final float phaseB;
	private final float freq;

	private float wind = 1.0F;

	protected CloudParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites, int shapes, boolean emissive) {
		super(level, x, y, z, sprites.first());
		this.sprites = sprites;
		this.shapes = shapes;
		this.emissive = emissive;
		this.shape = this.random.nextInt(SMOKE_SHAPES * FIRE_SHAPES);
		this.jitter = 0.86F + this.random.nextFloat() * 0.28F;
		this.phaseA = this.random.nextFloat() * Mth.TWO_PI;
		this.phaseB = this.random.nextFloat() * Mth.TWO_PI;
		this.freq = 0.05F + this.random.nextFloat() * 0.07F;
		this.xd = dx;
		this.yd = dy;
		this.zd = dz;
		this.hasPhysics = false;
		this.gravity = 0.0F;
		this.friction = 0.96F;
		this.lifetime = 60;
		this.startSize = 1.0F;
		this.endSize = 3.0F;
		this.quadSize = this.startSize;
		if (emissive) {
			// Fire has no baked light direction, so it may tumble.
			this.roll = this.random.nextFloat() * Mth.TWO_PI;
			this.spin = (this.random.nextFloat() - 0.5F) * 0.06F;
			this.wind = 0.0F;
		} else {
			this.roll = (this.random.nextFloat() - 0.5F) * 0.3F;
			this.spin = (this.random.nextFloat() - 0.5F) * 0.004F;
		}
		this.oRoll = this.roll;
		this.updateSprite();
	}

	// ------------------------------------------------------------------ fluent setup

	public CloudParticle configure(int lifetime, float startSize, float endSize, int colorStart, int colorEnd, float alpha) {
		this.lifetime = Math.max(2, lifetime);
		this.startSize = startSize;
		this.endSize = endSize;
		this.quadSize = startSize;
		this.r0 = (colorStart >> 16 & 255) / 255.0F;
		this.g0 = (colorStart >> 8 & 255) / 255.0F;
		this.b0 = (colorStart & 255) / 255.0F;
		this.r1 = (colorEnd >> 16 & 255) / 255.0F;
		this.g1 = (colorEnd >> 8 & 255) / 255.0F;
		this.b1 = (colorEnd & 255) / 255.0F;
		this.maxAlpha = alpha;
		this.applyColor(0.0F);
		this.setAlpha(0.0F);
		this.setSize(endSize * 0.5F, endSize * 0.5F);
		return this;
	}

	public CloudParticle physics(float friction, float buoyancy) {
		this.friction = friction;
		this.buoyancy = buoyancy;
		return this;
	}

	/** Static self-shadowing factor (1 = lit top, ~0.55 = shadowed underside). */
	public CloudParticle shade(float shade) {
		this.shade = shade;
		this.applyColor(0.0F);
		return this;
	}

	/** Dynamic sun lighting relative to the center of the cloud this puff belongs to. */
	public CloudParticle litFrom(double cx, double cy, double cz) {
		this.lcx = cx;
		this.lcy = cy;
		this.lcz = cz;
		this.lit = true;
		return this;
	}

	/** Swirl amplitude in blocks/tick. */
	public CloudParticle turbulence(float amount) {
		this.turb = amount;
		return this;
	}

	public CloudParticle wind(float factor) {
		this.wind = factor;
		return this;
	}

	/**
	 * Fire puff that cools into smoke after {@code fraction} of its life, ending in {@code smokeColor}.
	 * Only meaningful on fire particles.
	 */
	public CloudParticle cooling(float fraction, int smokeColor) {
		this.coolAt = Math.max(1, (int) (this.lifetime * fraction));
		this.coolR = (smokeColor >> 16 & 255) / 255.0F;
		this.coolG = (smokeColor >> 8 & 255) / 255.0F;
		this.coolB = (smokeColor & 255) / 255.0F;
		return this;
	}

	// ------------------------------------------------------------------ simulation

	private void applyColor(float c) {
		float k = this.shade * (this.emissive ? 1.0F : this.jitter);
		this.setColor(
			Math.min(1.0F, Mth.lerp(c, this.r0, this.r1) * k),
			Math.min(1.0F, Mth.lerp(c, this.g0, this.g1) * k),
			Math.min(1.0F, Mth.lerp(c, this.b0, this.b1) * k)
		);
	}

	private void updateSprite() {
		float t = this.lifetime <= 0 ? 0.0F : (float) this.age / this.lifetime;
		int stage = Mth.clamp((int) (t * t * STAGES * 1.15F), 0, STAGES - 1);
		int s = this.shape % this.shapes;
		this.setSprite(this.sprites.get(s * STAGES + stage, this.shapes * STAGES - 1));
	}

	/** Direction the light comes from: the sun by day, the moon at night, always a bit from above. */
	private Vec3 lightDir() {
		double phase = (this.level.getDayTime() % 24000L) / 24000.0 * Math.PI * 2.0;
		double sx = Math.cos(phase);
		double sy = Math.sin(phase);
		if (sy < -0.05) {
			sx = -sx;
			sy = -sy;
		}
		return new Vec3(sx * 0.8, sy + 0.55, 0.25).normalize();
	}

	@Override
	public void tick() {
		this.xo = this.x;
		this.yo = this.y;
		this.zo = this.z;
		this.oRoll = this.roll;
		if (this.age++ >= this.lifetime) {
			this.remove();
			return;
		}
		float t = (float) this.age / this.lifetime;

		if (this.coolAt > 0 && !this.cooled && this.age >= this.coolAt && smokeSprites != null) {
			// The flame dies: from now on this is a dark, sun-lit smoke puff that keeps rolling upward.
			this.cooled = true;
			this.emissive = false;
			this.sprites = smokeSprites;
			this.shapes = SMOKE_SHAPES;
			this.r0 = this.rCol;
			this.g0 = this.gCol;
			this.b0 = this.bCol;
			this.r1 = this.coolR;
			this.g1 = this.coolG;
			this.b1 = this.coolB;
			this.spin *= 0.1F;
			this.wind = 1.0F;
		}

		this.xd *= this.friction;
		this.yd = this.yd * this.friction + this.buoyancy;
		this.zd *= this.friction;
		double sx = 0;
		double sz = 0;
		double sy = 0;
		if (this.turb > 0) {
			float a = this.age * this.freq;
			sx = Mth.sin(a + this.phaseA) * this.turb;
			sz = Mth.cos(a * 1.3F + this.phaseB) * this.turb;
			sy = Mth.sin(a * 0.7F + this.phaseB) * this.turb * 0.5;
		}
		double windRamp = this.wind * Math.min(1.0, this.age / 40.0);
		this.move(this.xd + sx + WIND_X * windRamp, this.yd + sy, this.zd + sz + WIND_Z * windRamp);
		this.roll += this.spin;
		this.updateSprite();

		// Billows out quickly first, then keeps expanding slowly (like real smoke).
		float grow = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
		this.quadSize = Mth.lerp(grow, this.startSize, this.endSize);

		if (this.cooled) {
			float c = Mth.clamp((this.age - this.coolAt) / 12.0F, 0.0F, 1.0F);
			this.applyColor(c);
		} else {
			this.applyColor(Math.min(1.0F, t * 1.6F));
		}
		if (this.lit && !this.emissive) {
			Vec3 n = new Vec3(this.x - this.lcx, this.y - this.lcy, this.z - this.lcz);
			double len = n.length();
			float light = 1.0F;
			if (len > 0.5) {
				double d = n.scale(1.0 / len).dot(this.lightDir());
				light = (float) (0.62 + 0.42 * d);
			}
			this.rCol = Math.min(1.0F, this.rCol * light);
			this.gCol = Math.min(1.0F, this.gCol * light);
			this.bCol = Math.min(1.0F, this.bCol * light);
		}

		float fadeIn = Math.min(1.0F, t / 0.05F);
		float fadeOut = t > 0.5F ? 1.0F - (t - 0.5F) / 0.5F : 1.0F;
		this.setAlpha(this.maxAlpha * fadeIn * fadeOut * fadeOut);
	}

	@Override
	protected int getLightColor(float partialTick) {
		if (this.emissive) {
			return 0xF000F0;
		}
		int light = super.getLightColor(partialTick);
		if (this.cooled) {
			// still glowing a little right after the flame went out
			int glow = (int) Mth.clamp(15 - (this.age - this.coolAt) * 0.6F, 0, 15);
			light = Math.max(light & 0xFFFF, glow << 4) | (light & 0xFFFF0000);
		}
		// Clouds are huge: never let them go completely black.
		int block = Math.max(light & 0xFFFF, 6 << 4);
		int sky = Math.max(light >> 16 & 0xFFFF, 8 << 4);
		return sky << 16 | block;
	}

	@Override
	protected SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.TRANSLUCENT;
	}

	public static class SmokeProvider implements ParticleProvider<SimpleParticleType> {
		private final SpriteSet sprites;

		public SmokeProvider(SpriteSet sprites) {
			this.sprites = sprites;
			smokeSprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz, RandomSource random) {
			return new CloudParticle(level, x, y, z, dx, dy, dz, this.sprites, SMOKE_SHAPES, false);
		}
	}

	public static class FireProvider implements ParticleProvider<SimpleParticleType> {
		private final SpriteSet sprites;

		public FireProvider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz, RandomSource random) {
			return new CloudParticle(level, x, y, z, dx, dy, dz, this.sprites, FIRE_SHAPES, true);
		}
	}
}
