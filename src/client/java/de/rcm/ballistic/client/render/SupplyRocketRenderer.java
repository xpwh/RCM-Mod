package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.SupplyRocketEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Two-stage supply launcher: white body with a black roll pattern, the interstage, the payload
 * fairing, four fins, its motor flame, and - once it is high up - a point of fire with a glowing tail
 * that can be followed far into the sky.
 */
public class SupplyRocketRenderer extends EntityRenderer<SupplyRocketEntity, SupplyRocketRenderer.State> {
	private static final RenderType FLAME_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/exhaust_flame.png"));
	static final float R = 0.32F;
	static final BoxMesh MESH = mesh();

	public SupplyRocketRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
		public float time;
		public float distance;
		public Vec3 back = new Vec3(0, -1, 0);
		public Vec3 toCamera = new Vec3(0, -1, 0);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public boolean shouldRender(SupplyRocketEntity entity, Frustum frustum, double x, double y, double z) {
		return true;
	}

	@Override
	public void extractRenderState(SupplyRocketEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 v = entity.getDeltaMovement();
		Vec3 dir = v.lengthSqr() < 1.0E-6 ? new Vec3(0, 1, 0) : v.normalize();
		MissileRenderer.orient(state.rotation, dir);
		state.time = entity.tickCount + partialTick;
		state.back = dir.scale(-1.0);
		Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
		state.toCamera = cam.subtract(state.x, state.y, state.z);
		state.distance = (float) state.toCamera.length();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		float t = state.time;
		// the motor seen from afar: a flickering point of fire climbing away with a short glowing tail
		float far = Mth.clamp((state.distance - 30.0F) / 120.0F, 0.0F, 1.0F);
		float r = SkyGlow.size(state.distance, 0.7F, 0.005F) * (0.9F + 0.1F * Mth.sin(t * 2.7F));
		SkyGlow.point(poseStack, collector, camera, r, 0xFFF2D0, 0xFF9A40, 0.35F + 0.65F * far);
		SkyGlow.streak(poseStack, collector, state.toCamera, state.back, 14.0F + 30.0F * far, r * 0.5F, 0xFFB060, 0.3F + 0.5F * far);
		poseStack.pushPose();
		poseStack.mulPose(state.rotation);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> MESH.emit(pose, consumer, light));
		collector.submitCustomGeometry(poseStack, FLAME_TYPE, (pose, consumer) -> {
			MissileRenderer.flame(pose, consumer, R * 0.8F, 5.5F, t, 1.0F);
			MissileRenderer.flame(pose, consumer, R * 0.45F, 3.0F, t + 3.0F, 1.0F);
		});
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** Along +Y, nozzle at 0. */
	static BoxMesh mesh() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f o = v(0, 0, 0);
		Vector3f up = v(0, 1, 0);
		b.revolve(BLACK, o, up, new float[][] {{-0.05F, R * 0.7F}, {0.25F, R * 0.45F}}, 12); // nozzle bell
		b.revolve(WHITE, o, up, new float[][] {{0.25F, R}, {1.4F, R}}, 16);
		b.revolve(BLACK, o, up, new float[][] {{1.4F, R * 1.005F}, {1.9F, R * 1.005F}}, 16); // roll pattern band
		b.revolve(WHITE, o, up, new float[][] {{1.9F, R}, {3.2F, R}}, 16);
		b.revolve(GUNMETAL, o, up, new float[][] {{3.2F, R}, {3.5F, R * 0.9F}}, 16); // interstage
		b.revolve(WHITE, o, up, new float[][] {{3.5F, R * 0.9F}, {4.6F, R * 0.9F}}, 16);
		// payload fairing with the rod inside
		b.revolve(WHITE, o, up, new float[][] {{4.6F, R * 1.05F}, {5.6F, R * 1.05F}, {6.1F, R * 0.75F}, {6.45F, R * 0.3F}, {6.55F, 0.0F}}, 16);
		for (int i = 0; i < 4; i++) {
			float a = i * (float) Math.PI * 0.5F;
			float cx = (float) Math.cos(a);
			float cz = (float) Math.sin(a);
			b.beam(v(cx * R, 0.7F, cz * R), v(cx * (R + 0.35F), 0.35F, cz * (R + 0.35F)), 0.04F, 0.6F, STEEL);
		}
		return b.build();
	}
}
