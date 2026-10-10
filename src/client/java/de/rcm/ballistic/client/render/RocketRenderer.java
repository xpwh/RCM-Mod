package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.RocketEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Hellfire / Hydra rocket: a scaled-down winged missile body pointing along its flight, motor flame. */
public class RocketRenderer extends EntityRenderer<RocketEntity, RocketRenderer.State> {
	private static final RenderType TYPE = RenderTypes.entityCutoutNoCull(BallisticMissiles.id("textures/entity/anti_radar_missile.png"));
	private static final RenderType FLAME_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/exhaust_flame.png"));

	public RocketRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
		public boolean hydra;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(RocketEntity entity) {
		return entity.getBoundingBox().inflate(2.0);
	}

	@Override
	public void extractRenderState(RocketEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 v = entity.getDeltaMovement();
		MissileRenderer.orient(state.rotation, v.lengthSqr() < 1.0E-6 ? new Vec3(0, -1, 0) : v.normalize());
		state.hydra = entity.getKind() == RocketEntity.Kind.HYDRA;
		state.time = entity.tickCount + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		float scale = state.hydra ? 0.2F : 0.28F;
		float t = state.time;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.2F, 0.0F);
		poseStack.mulPose(state.rotation);
		poseStack.scale(scale, scale, scale);
		poseStack.translate(0.0F, -3.0F, 0.0F);
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> MissileMesh.ANTI_RADAR.emit(pose, consumer, light));
		collector.submitCustomGeometry(poseStack, FLAME_TYPE, (pose, consumer) -> {
			MissileRenderer.flame(pose, consumer, 0.22F, 2.4F, t, 1.0F);
			MissileRenderer.flame(pose, consumer, 0.12F, 1.6F, t + 3.0F, 1.0F);
		});
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
