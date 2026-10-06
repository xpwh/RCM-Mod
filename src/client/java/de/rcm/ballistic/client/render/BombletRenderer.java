package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.BombletEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Tumbling cluster sub-munition, nose pointing along its fall. */
public class BombletRenderer extends EntityRenderer<BombletEntity, BombletRenderer.State> {
	private static final RenderType TYPE = RenderTypes.entityCutoutNoCull(BallisticMissiles.id("textures/entity/bomblet.png"));

	public BombletRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.15F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(BombletEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 v = entity.getDeltaMovement();
		Vec3 dir = v.lengthSqr() < 1.0E-6 ? new Vec3(0, -1, 0) : v.normalize();
		MissileRenderer.orient(state.rotation, dir);
		// spin around the long axis, like a real finned bomblet
		state.rotation.rotateY((entity.tickCount + partialTick) * 0.6F + entity.spinOffset);
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.mulPose(state.rotation);
		poseStack.translate(0.0F, -0.3F, 0.0F);
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> MissileMesh.BOMBLET.emit(pose, consumer, light));
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
