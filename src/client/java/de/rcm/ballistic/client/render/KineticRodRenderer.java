package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.ABLATIVE;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.HOT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.TUNGSTEN;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.KineticRodEntity;
import net.minecraft.client.renderer.LightTexture;
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
import org.joml.Vector3f;

/**
 * The tungsten rod: six metres of dense metal with a pointed, charred carbon nose, a slightly wider
 * guidance section at the tail with four small steering fins and attitude thrusters. Low down it is
 * wrapped in a white-hot plasma sheath and drags a glowing wake.
 */
public class KineticRodRenderer extends EntityRenderer<KineticRodEntity, KineticRodRenderer.State> {
	private static final RenderType FLAME_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/exhaust_flame.png"));
	static final float LENGTH = 6.2F;
	static final float RADIUS = 0.17F;
	static final BoxMesh MESH = mesh();
	static final BoxMesh SHEATH = sheath();

	public KineticRodRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
		public float heat;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(KineticRodEntity entity) {
		return entity.getBoundingBox().inflate(16.0);
	}

	@Override
	public void extractRenderState(KineticRodEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 v = entity.getDeltaMovement();
		MissileRenderer.orient(state.rotation, v.lengthSqr() < 1.0E-6 ? new Vec3(0, -1, 0) : v.normalize());
		state.heat = entity.heating();
		state.time = entity.tickCount + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		float heat = state.heat;
		float t = state.time;
		poseStack.pushPose();
		poseStack.mulPose(state.rotation);
		poseStack.translate(0.0F, -LENGTH * 0.5F, 0.0F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> MESH.emit(pose, consumer, heat > 0.3F ? LightTexture.FULL_BRIGHT : light));
		if (heat > 0.02F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> SHEATH.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			// the glowing wake streaming off the tail
			collector.submitCustomGeometry(poseStack, FLAME_TYPE, (pose, consumer) -> {
				MissileRenderer.flame(pose, consumer, 0.35F * heat + 0.1F, 14.0F * heat + 2.0F, t, Math.min(1.0F, heat * 1.4F));
				MissileRenderer.flame(pose, consumer, 0.2F, 6.0F * heat + 1.0F, t + 3.0F, heat);
			});
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** Along +Y, tail at 0, nose at LENGTH. */
	static BoxMesh mesh() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float r = RADIUS;
		Vector3f o = v(0, 0, 0);
		Vector3f up = v(0, 1, 0);
		// guidance and control section at the tail
		b.revolve(BLACK, o, up, new float[][] {{0.0F, 0.0F}, {0.0F, r * 1.1F}}, 14);
		b.revolve(GUNMETAL, o, up, new float[][] {{0.0F, r * 1.15F}, {0.9F, r * 1.15F}, {1.05F, r}}, 14);
		// the rod itself, with the joint between its two forged halves
		b.revolve(TUNGSTEN, o, up, new float[][] {{1.05F, r}, {3.1F, r}}, 14);
		b.revolve(BLACK, o, up, new float[][] {{3.1F, r * 1.01F}, {3.14F, r * 1.01F}}, 14);
		b.revolve(TUNGSTEN, o, up, new float[][] {{3.14F, r}, {5.0F, r}}, 14);
		// charred carbon-composite nose
		b.revolve(ABLATIVE, o, up, new float[][] {{5.0F, r}, {5.4F, r * 0.85F}, {5.8F, r * 0.5F}, {6.1F, r * 0.16F}, {LENGTH, 0.0F}}, 14);
		// four small steering fins and the attitude thrusters between them
		for (int i = 0; i < 4; i++) {
			float a = i * (float) Math.PI * 0.5F;
			float cx = (float) Math.cos(a);
			float cz = (float) Math.sin(a);
			b.beam(v(cx * r, 0.12F, cz * r), v(cx * (r + 0.28F), 0.06F, cz * (r + 0.28F)), 0.025F, 0.42F, STEEL);
			float b2 = a + (float) Math.PI * 0.25F;
			b.box((float) Math.cos(b2) * r * 1.15F - 0.03F, 0.7F, (float) Math.sin(b2) * r * 1.15F - 0.03F,
				(float) Math.cos(b2) * r * 1.15F + 0.03F, 0.78F, (float) Math.sin(b2) * r * 1.15F + 0.03F, BLACK);
		}
		return b.build();
	}

	/** White-hot shock layer hugging the nose and front of the rod. */
	static BoxMesh sheath() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float r = RADIUS;
		b.revolve(HOT, v(0, 0, 0), v(0, 1, 0), new float[][] {
			{3.6F, r * 1.6F}, {4.6F, r * 1.9F}, {5.4F, r * 1.75F}, {5.9F, r * 1.3F}, {6.25F, r * 0.6F}, {6.4F, 0.0F}
		}, 14);
		return b.build();
	}
}
