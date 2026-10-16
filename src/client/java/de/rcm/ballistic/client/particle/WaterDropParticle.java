package de.rcm.ballistic.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;

/**
 * A drop of water thrown up off a surface: it flies the way it was thrown, falls, and is gone the moment
 * it drops back into the water (or lands).
 */
public class WaterDropParticle extends SingleQuadParticle {
	protected WaterDropParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
		super(level, x, y, z, sprites.get(level.getRandom()));
		this.xd = dx;
		this.yd = dy;
		this.zd = dz;
		this.lifetime = 30;
		this.gravity = 1.0F;
		this.friction = 0.97F;
		this.hasPhysics = true;
		this.quadSize = 0.022F + this.random.nextFloat() * 0.03F;
		float b = 0.9F + this.random.nextFloat() * 0.1F;
		this.setColor(0.82F * b, 0.9F * b, 1.0F * b);
		this.setAlpha(0.8F);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.removed) {
			return;
		}
		// back into the water, or onto the ground
		if (this.onGround || this.yd < 0.0 && !this.level.getFluidState(BlockPos.containing(this.x, this.y, this.z)).isEmpty()) {
			this.remove();
		}
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
			return new WaterDropParticle(level, x, y, z, dx, dy, dz, this.sprites);
		}
	}
}
