package de.rcm.ballistic.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * A spark: a fleck of hot metal knocked off by a bullet, or a crumb of burning magnesium from a
 * flare. It flies off white-hot, falls, skips along the ground and cools through yellow and orange
 * until it goes out.
 */
public class SparkParticle extends SingleQuadParticle {
	private final float size0;

	protected SparkParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
		super(level, x, y, z, sprites.first());
		this.xd = dx + (this.random.nextDouble() - 0.5) * 0.04;
		this.yd = dy + (this.random.nextDouble() - 0.5) * 0.04;
		this.zd = dz + (this.random.nextDouble() - 0.5) * 0.04;
		this.lifetime = 8 + this.random.nextInt(16);
		this.gravity = 0.75F;
		this.friction = 0.95F;
		this.hasPhysics = true;
		this.size0 = 0.03F + this.random.nextFloat() * 0.035F;
		this.quadSize = this.size0;
		this.setColor(1.0F, 0.97F, 0.85F);
	}

	@Override
	public void tick() {
		boolean wasOnGround = this.onGround;
		super.tick();
		if (this.onGround && !wasOnGround) {
			// skips off the ground, losing most of its speed
			this.yd = Math.abs(this.yd) * 0.3 + 0.02;
			this.xd *= 0.5;
			this.zd *= 0.5;
		}
		float t = (float) this.age / this.lifetime;
		// white-hot -> yellow -> deep orange
		this.setColor(1.0F, Mth.lerp(t, 0.97F, 0.38F), Mth.lerp(Math.min(1.0F, t * 2.0F), 0.85F, 0.06F));
		this.quadSize = this.size0 * (1.0F - t * 0.6F);
		this.setAlpha(t < 0.7F ? 1.0F : 1.0F - (t - 0.7F) / 0.3F);
	}

	@Override
	protected int getLightColor(float partialTick) {
		return 0xF000F0;
	}

	@Override
	protected SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.TRANSLUCENT;
	}

	public static class Provider implements ParticleProvider<SimpleParticleType> {
		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz, RandomSource random) {
			return new SparkParticle(level, x, y, z, dx, dy, dz, this.sprites);
		}
	}
}
