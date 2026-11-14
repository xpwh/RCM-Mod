package de.rcm.ballistic.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/** A ring lying on a water surface, spreading out from where something struck it and fading as it goes. */
public class WaterRippleParticle extends SingleQuadParticle {
	private static final SingleQuadParticle.FacingCameraMode FLAT = (q, camera, partial) -> q.rotationX(-Mth.HALF_PI);
	private final float radius;

	protected WaterRippleParticle(ClientLevel level, double x, double y, double z, double radius, SpriteSet sprites) {
		super(level, x, y, z, sprites.first());
		this.xd = 0.0;
		this.yd = 0.0;
		this.zd = 0.0;
		this.gravity = 0.0F;
		this.hasPhysics = false;
		this.radius = (float) Math.max(0.15, radius);
		this.lifetime = (int) (14 + this.radius * 22.0F);
		this.quadSize = 0.02F;
		this.setColor(0.9F, 0.95F, 1.0F);
		this.setAlpha(0.0F);
	}

	@Override
	public void tick() {
		this.xo = this.x;
		this.yo = this.y;
		this.zo = this.z;
		if (this.age++ >= this.lifetime) {
			this.remove();
		}
	}

	@Override
	public float getQuadSize(float partial) {
		float t = Mth.clamp((this.age + partial) / this.lifetime, 0.0F, 1.0F);
		// fast at first, slowing as it spreads
		return this.radius * (0.08F + 0.92F * (1.0F - (1.0F - t) * (1.0F - t)));
	}

	@Override
	public void extract(net.minecraft.client.renderer.state.QuadParticleRenderState state, net.minecraft.client.Camera camera, float partial) {
		float t = Mth.clamp((this.age + partial) / this.lifetime, 0.0F, 1.0F);
		this.alpha = 0.55F * (1.0F - t) * Mth.clamp(t * 8.0F, 0.0F, 1.0F);
		super.extract(state, camera, partial);
	}

	@Override
	public SingleQuadParticle.FacingCameraMode getFacingCameraMode() {
		return FLAT;
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
			return new WaterRippleParticle(level, x, y, z, dx, this.sprites);
		}
	}
}
