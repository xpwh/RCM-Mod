package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.InterceptorEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Renders MIRV re-entry vehicles (glowing) and air-defense interceptors (with exhaust flame). */
public class ProjectileRenderer<T extends Entity> extends EntityRenderer<T, ProjectileRenderer.State> {
	static final RenderType RV_TYPE = RenderTypes.entityCutoutNoCull(BallisticMissiles.id("textures/entity/reentry_vehicle.png"));
	private static final RenderType INTERCEPTOR_TYPE = RenderTypes.entityCutoutNoCull(BallisticMissiles.id("textures/entity/interceptor.png"));
	private static final RenderType FLAME_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/exhaust_flame.png"));

	private final boolean interceptor;

	public ProjectileRenderer(EntityRendererProvider.Context context, boolean interceptor) {
		super(context);
		this.interceptor = interceptor;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
		public float time;
		public float distance;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(T entity) {
		return entity.getBoundingBox().inflate(4.0);
	}

	@Override
	public void extractRenderState(T entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 dir = entity instanceof InterceptorEntity i ? i.getDir() : entity.getDeltaMovement();
		MissileRenderer.orient(state.rotation, dir.lengthSqr() < 1.0E-6 ? new Vec3(0, -1, 0) : dir.normalize());
		state.time = entity.tickCount + partialTick;
		state.distance = (float) Math.sqrt(state.distanceToCameraSq);
		state.lightCoords = LightTexture.FULL_BRIGHT;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		float t = state.time;
		poseStack.pushPose();
		poseStack.mulPose(state.rotation);
		if (this.interceptor) {
			collector.submitCustomGeometry(poseStack, INTERCEPTOR_TYPE, (pose, consumer) -> MissileMesh.INTERCEPTOR.emit(pose, consumer, light));
			collector.submitCustomGeometry(poseStack, FLAME_TYPE, (pose, consumer) -> MissileRenderer.flame(pose, consumer, 0.08F, 1.6F, t, 1.0F));
		} else {
			poseStack.translate(0.0F, -0.9F, 0.0F);
			collector.submitCustomGeometry(poseStack, RV_TYPE, (pose, consumer) -> MissileMesh.REENTRY_VEHICLE.emit(pose, consumer, light));
			poseStack.scale(1.12F, 1.05F, 1.12F);
			int a = (int) (220 + 35 * Math.sin(t * 2.1)) << 24 | 0xFFFFFF;
			collector.submitCustomGeometry(poseStack, MissileRenderer.PLASMA_TYPE, (pose, consumer) -> MissileMesh.REENTRY_VEHICLE.emit(pose, consumer, light, a));
		}
		poseStack.popPose();
		if (!this.interceptor && camera.orientation != null) {
			// re-entering warheads seen from afar: glowing points streaking down, day and night
			float r = Math.max(0.8F, state.distance * 0.006F) * (0.9F + 0.1F * (float) Math.sin(t * 2.7F));
			poseStack.pushPose();
			poseStack.mulPose(camera.orientation);
			collector.submitCustomGeometry(poseStack, MissileRenderer.GLOW_TYPE, (pose, consumer) -> {
				MissileRenderer.glowQuad(pose, consumer, r, 0xFFFFE6C8);
				MissileRenderer.glowQuad(pose, consumer, r * 2.4F, 0x66FF9040);
			});
			poseStack.popPose();
		}
		super.submit(state, poseStack, collector, camera);
	}
}
