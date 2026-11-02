package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.gun.BulletEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Bullets as the eye sees them. A ball round is a brief grey smear along its path, gone in a blink.
 * A tracer is a burning pellet in the base of the bullet: it catches a few metres out of the muzzle,
 * then shows as a small, sputtering green spot trailing a short dash where the eye smears it - faint
 * by day, vivid at night - and burns out after about 800 m.
 */
public class BulletRenderer extends EntityRenderer<BulletEntity, BulletRenderer.State> {
	public BulletRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public boolean tracer;
		public float burn;
		public Vec3 back = new Vec3(0, 0, 1);
		public Vec3 toCamera = new Vec3(0, 0, 1);
		public float distance;
		public float speed;
		public float night;
		public float age;
		public int id;
		public boolean ownFirstPerson;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRender(BulletEntity entity, Frustum frustum, double x, double y, double z) {
		return true; // fast and tiny: let the streak decide what shows
	}

	@Override
	public void extractRenderState(BulletEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 v = entity.getDeltaMovement();
		state.speed = (float) v.length();
		state.back = state.speed < 1.0E-4 ? new Vec3(0, 0, 1) : v.scale(-1.0 / state.speed);
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		state.toCamera = cam.subtract(state.x, state.y, state.z);
		state.distance = (float) state.toCamera.length();
		state.tracer = entity.isTracer();
		state.age = entity.tickCount + partialTick;
		state.id = entity.getId();
		state.ownFirstPerson = mc.player != null && entity.shooterId() == mc.player.getId() && mc.options.getCameraType().isFirstPerson();
		state.burn = entity.isTracer() ? Mth.clamp(1.0F - (state.age - (BulletEntity.TRACER_BURN - 4)) / 4.0F, 0.0F, 1.0F) : 0.0F;
		state.night = night(entity.level().getDayTime()) * (1.0F - entity.level().getRainLevel(partialTick) * 0.3F);
	}

	private static float night(long dayTime) {
		float t = dayTime % 24000L;
		if (t < 12000.0F) {
			return t < 500.0F ? 0.5F : 0.0F;
		}
		if (t < 13500.0F) {
			return (t - 12000.0F) / 1500.0F;
		}
		if (t < 22500.0F) {
			return 1.0F;
		}
		return Math.max(0.0F, (24000.0F - t) / 1500.0F);
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		// the streak never reaches back past the muzzle
		float travelled = state.age * state.speed;
		if (state.tracer) {
			// drawn by TracerFx from the moment of the shot
		} else if (travelled > 2.0F && state.age < 6.0F && !state.ownFirstPerson) {
			// a ball round: a faint grey flick of disturbed air
			float trail = Math.min(travelled - 1.5F, 3.5F);
			SkyGlow.streak(poseStack, collector, state.toCamera, state.back, trail, 0.012F + state.distance * 0.0006F, 0xC8C8C0, 0.22F);
		}
		super.submit(state, poseStack, collector, camera);
	}
}
