package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.AerialBombEntity;
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

/** Free-fall bomb, nose along its velocity: level when released, nosing over as it falls. */
public class AerialBombRenderer extends EntityRenderer<AerialBombEntity, AerialBombRenderer.State> {
	private static final RenderType TYPE = RenderTypes.entityCutoutNoCull(BallisticMissiles.id("textures/entity/aerial_bomb.png"));
	private static final RenderType MOAB_TYPE = RenderTypes.entityCutoutNoCull(BallisticMissiles.id("textures/entity/moab.png"));

	public AerialBombRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.2F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
		public boolean moab;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(AerialBombEntity entity) {
		return entity.getBoundingBox().inflate(entity.isMoab() ? 5.0 : 2.0);
	}

	@Override
	public void extractRenderState(AerialBombEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.moab = entity.isMoab();
		Vec3 v = entity.getDeltaMovement();
		MissileRenderer.orient(state.rotation, v.lengthSqr() < 1.0E-6 ? new Vec3(0, -1, 0) : v.normalize());
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.2F, 0.0F);
		poseStack.mulPose(state.rotation);
		MissileMesh mesh = state.moab ? MissileMesh.MOAB : MissileMesh.AERIAL_BOMB;
		poseStack.translate(0.0F, -mesh.length() * 0.5F, 0.0F);
		collector.submitCustomGeometry(poseStack, state.moab ? MOAB_TYPE : TYPE, (pose, consumer) -> mesh.emit(pose, consumer, light));
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
