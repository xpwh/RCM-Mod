package de.rcm.ballistic.client.particle;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.state.QuadParticleRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * A drop of water thrown up off a surface: a clear bead with a glint, drawn out into a short streak while it
 * flies fast, falling under gravity. Where it drops back into the water it leaves a tiny ring; on the
 * ground it is simply gone.
 */
public class WaterDropParticle extends SingleQuadParticle {
	public WaterDropParticle(ClientLevel level, double x, double y, double z, double dx, double dy, double dz, SpriteSet sprites) {
		super(level, x, y, z, sprites.get(level.getRandom()));
		this.xd = dx;
		this.yd = dy;
		this.zd = dz;
		this.lifetime = 40;
		this.gravity = 1.0F;
		this.friction = 0.985F;
		this.hasPhysics = true;
		// most drops small, a few big ones
		float r = this.random.nextFloat();
		this.quadSize = 0.012F + r * r * r * 0.03F + this.random.nextFloat() * 0.006F;
		this.setColor(0.86F, 0.93F, 1.0F);
		this.setAlpha(0.9F);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.removed) {
			return;
		}
		if (this.onGround) {
			this.remove();
		} else if (this.yd < 0.0 && !this.level.getFluidState(BlockPos.containing(this.x, this.y, this.z)).isEmpty()) {
			// back into the water: the bigger drops leave a little ring of their own
			if (this.quadSize > 0.016F && this.random.nextFloat() < 0.45F) {
				double top = Math.floor(this.y) + this.level.getFluidState(BlockPos.containing(this.x, this.y, this.z)).getHeight(this.level,
					BlockPos.containing(this.x, this.y, this.z));
				this.level.addParticle(ModRegistry.WATER_RIPPLE, this.x, top + 0.02, this.z, 0.07 + this.quadSize * 3.0, 0.0, 0.0);
			}
			this.remove();
		}
	}

	@Override
	public void extract(QuadParticleRenderState state, Camera camera, float partial) {
		Quaternionf q = new Quaternionf();
		this.getFacingCameraMode().setRotation(q, camera, partial);
		Vec3 cam = camera.position();
		float x = (float) (Mth.lerp(partial, this.xo, this.x) - cam.x);
		float y = (float) (Mth.lerp(partial, this.yo, this.y) - cam.y);
		float z = (float) (Mth.lerp(partial, this.zo, this.z) - cam.z);
		this.extractRotatedQuad(state, q, x, y, z, partial);
		// moving fast it reads as a short streak: fainter, smaller beads strung out behind it
		float vx = (float) (this.x - this.xo);
		float vy = (float) (this.y - this.yo);
		float vz = (float) (this.z - this.zo);
		float speed = Mth.sqrt(vx * vx + vy * vy + vz * vz);
		if (speed > 0.06F) {
			float size = this.quadSize;
			float alpha = this.alpha;
			int n = speed > 0.2F ? 3 : 2;
			for (int k = 1; k <= n; k++) {
				float back = k * Math.min(this.quadSize * 1.2F, speed * 0.3F) / speed;
				this.quadSize = size * (1.0F - 0.18F * k);
				this.alpha = alpha * (1.0F - 0.27F * k);
				this.extractRotatedQuad(state, q, x - vx * back, y - vy * back, z - vz * back, partial);
			}
			this.quadSize = size;
			this.alpha = alpha;
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
