package de.rcm.ballistic.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/** Fine spray hanging over a splash for a moment: a faint white puff that swells, drifts and is gone. */
public class WaterMistParticle extends SingleQuadParticle {
	private final float size0;

	protected WaterMistParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
		super(level, x, y, z, sprites.get(level.getRandom()));
		this.xd = dx;
		this.yd = dy;
		this.zd = dz;
		this.gravity = 0.06F;
		this.friction = 0.86F;
		this.hasPhysics = false;
		this.lifetime = 14 + this.random.nextInt(14);
		this.size0 = 0.06F + this.random.nextFloat() * 0.07F;
		this.quadSize = this.size0;
		this.roll = this.random.nextFloat() * 6.28F;
		this.oRoll = this.roll;
		this.setColor(0.94F, 0.97F, 1.0F);
		this.setAlpha(0.0F);
	}

	@Override
	public void tick() {
		super.tick();
		float t = (float) this.age / this.lifetime;
		this.quadSize = this.size0 * (1.0F + 1.6F * t);
		this.setAlpha(0.32F * (1.0F - t) * Math.min(1.0F, t * 6.0F + 0.2F));
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
			return new WaterMistParticle(level, x, y, z, dx, dy, dz, this.sprites);
		}
	}
}
