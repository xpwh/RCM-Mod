package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.entity.MissileStages;
import de.rcm.ballistic.entity.MissileType;
import de.rcm.ballistic.entity.SpentStageEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A spent missile stage tumbling end over end as it falls back. */
public class SpentStageRenderer extends EntityRenderer<SpentStageEntity, SpentStageRenderer.State> {
	public SpentStageRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public MissileType type = MissileType.NUCLEAR;
		public int stage;
		public final Quaternionf rotation = new Quaternionf();
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(SpentStageEntity entity) {
		return entity.getBoundingBox().inflate(8.0);
	}

	@Override
	public void extractRenderState(SpentStageEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.type = entity.getMissileType();
		state.stage = entity.getStage();
		Vec3 dir = entity.getInitialDir();
		MissileRenderer.orient(state.rotation, dir);
		// slow end-over-end tumble about an axis across the stage, plus a little roll
		float t = entity.tickCount + partialTick;
		float spin = 0.02F + 0.01F * (entity.getId() % 5);
		state.rotation.mul(new Quaternionf().rotationAxis(t * spin, new Vector3f(1.0F, 0.0F, 0.35F).normalize()));
		state.rotation.mul(new Quaternionf().rotationY(t * 0.03F));
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		float[] cuts = MissileStages.cuts(state.type);
		if (state.stage < 0 || state.stage >= cuts.length) {
			return;
		}
		MissileMesh mesh = MissileMesh.spent(state.type, state.stage);
		float lo = MissileStages.bottom(state.type, state.stage);
		float mid = (lo + cuts[state.stage]) * 0.5F;
		float s = state.type.scale;
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.mulPose(state.rotation);
		poseStack.scale(s, s, s);
		poseStack.translate(0.0F, -mid, 0.0F);
		collector.submitCustomGeometry(poseStack, MissileRenderer.bodyType(state.type), (pose, consumer) -> mesh.emit(pose, consumer, light));
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
