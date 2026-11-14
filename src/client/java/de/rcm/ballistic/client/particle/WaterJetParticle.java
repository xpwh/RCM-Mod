package de.rcm.ballistic.client.particle;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.state.QuadParticleRenderState;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * The spout a bullet throws up out of water: a thin white column that shoots up in a few hundredths of a
 * second, hangs, and collapses back into the surface under its own weight, thinning and breaking up as
 * it goes. Drawn as a stack of frothy quads turned to face you round the upright.
 */
public class WaterJetParticle extends SingleQuadParticle {
	private static final float RISE = 3.0F;
	private final float height;
	private final float width;
	private final SpriteSet sprites;

	protected WaterJetParticle(ClientLevel level, double x, double y, double z, double height, double width, SpriteSet sprites) {
		super(level, x, y, z, sprites.first());
		this.sprites = sprites;
		this.xd = 0.0;
		this.yd = 0.0;
		this.zd = 0.0;
		this.gravity = 0.0F;
		this.hasPhysics = false;
		this.height = (float) Math.max(0.1, height);
		this.width = (float) Math.max(0.02, width);
		// up in RISE ticks, then the top falls back freely (0.04 blocks/tick^2)
		this.lifetime = (int) (RISE + Math.sqrt(2.0 * this.height / 0.04) + 2);
		this.setColor(0.93F, 0.96F, 1.0F);
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
	public SingleQuadParticle.FacingCameraMode getFacingCameraMode() {
		return SingleQuadParticle.FacingCameraMode.LOOKAT_Y;
	}

	@Override
	public void extract(QuadParticleRenderState state, Camera camera, float partial) {
		float t = this.age + partial;
		float top;
		if (t < RISE) {
			float k = t / RISE;
			top = this.height * (1.0F - (1.0F - k) * (1.0F - k));
		} else {
			float f = t - RISE;
			top = this.height - 0.02F * f * f;
		}
		if (top <= 0.01F) {
			return;
		}
		float life = t / this.lifetime;
		float fade = Mth.clamp((1.0F - life) * 2.2F, 0.0F, 1.0F);
		Quaternionf q = new Quaternionf();
		this.getFacingCameraMode().setRotation(q, camera, partial);
		Vec3 cam = camera.position();
		float x = (float) (this.x - cam.x);
		float y = (float) (this.y - cam.y);
		float z = (float) (this.z - cam.z);
		// the column spreads at its foot as it falls back, and is thinnest at the tip
		float foot = this.width * (1.0F + 0.8F * life);
		int n = Math.max(2, Mth.ceil(top / (this.width * 0.9F)));
		for (int i = 0; i < n; i++) {
			float h = top * i / (n - 1);
			float along = h / this.height;
			this.quadSize = Mth.lerp(along, foot, this.width * 0.45F);
			this.alpha = 0.85F * fade * (0.55F + 0.45F * (1.0F - along));
			this.setSprite(this.sprites.get((i * 7 + this.age / 2) % 4, 3));
			this.extractRotatedQuad(state, q, x, y + h, z, partial);
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

		/** dx: how high it goes; dy: how wide it is. */
		@Override
		public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double dx, double dy, double dz, RandomSource random) {
			return new WaterJetParticle(level, x, y, z, dx, dy, this.sprites);
		}
	}
}
