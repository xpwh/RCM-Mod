package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.JetRenderer.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.AirMissileEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** An AIM-120 in flight: the white missile with its fins, and the motor's flame while it burns. */
public class AirMissileRenderer extends EntityRenderer<AirMissileEntity, AirMissileRenderer.State> {
	private static final RenderType TYPE = RenderTypes.entityCutout(BallisticMissiles.id("textures/entity/fighter_jets.png"));
	private static final BoxMesh BODY = build();

	public AirMissileRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
		public boolean burning;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(AirMissileEntity m, State state, float partialTick) {
		super.extractRenderState(m, state, partialTick);
		Vec3 v = m.getDeltaMovement();
		MissileRenderer.orient(state.rotation, v.lengthSqr() > 1.0E-6 ? v : new Vec3(0, -1, 0));
		state.burning = m.isBurning();
		state.time = m.tickCount + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.mulPose(state.rotation);
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> BODY.emit(pose, consumer, light));
		if (state.burning) {
			poseStack.translate(0.0F, -1.85F, 0.0F);
			float t = state.time;
			collector.submitCustomGeometry(poseStack, JetRenderer.FLAME_TYPE, (pose, consumer) -> {
				MissileRenderer.flame(pose, consumer, 0.12F, 2.6F, t, 1.0F);
				MissileRenderer.flame(pose, consumer, 0.06F, 1.6F, t + 3.0F, 1.0F);
			});
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	private static BoxMesh build() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		int white = FighterRenderer.MISSILE;
		b.revolve(white, v(0, 1.8F, 0), v(0, -1, 0), new float[][] {{0.0F, 0.0F}, {0.25F, 0.07F}, {0.45F, 0.09F}, {3.45F, 0.09F}, {3.65F, 0.07F}}, 10);
		for (int i = 0; i < 4; i++) {
			double a = Math.PI / 4.0 + i * Math.PI / 2.0;
			float dx = (float) Math.cos(a);
			float dz = (float) Math.sin(a);
			b.beam(v(dx * 0.09F, 0.4F, dz * 0.09F), v(dx * 0.24F, 0.2F, dz * 0.24F), 0.02F, 0.2F, white);
			b.beam(v(dx * 0.09F, -1.5F, dz * 0.09F), v(dx * 0.26F, -1.65F, dz * 0.26F), 0.02F, 0.18F, white);
		}
		return b.build();
	}
}
