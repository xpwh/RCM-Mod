package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.RpgRocketEntity;
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
 * PG-7V in flight: the fat olive shaped-charge warhead with its piezo nose probe, the slim sustainer
 * motor behind it and four thin stabiliser fins at the tail, spinning, with the motor flame.
 */
public class RpgGrenadeRenderer extends EntityRenderer<RpgRocketEntity, RpgGrenadeRenderer.State> {
	private static final RenderType FLAME_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/exhaust_flame.png"));
	/** Real size (0.95 m long, 85 mm warhead) made a little bigger so it can be followed in flight. */
	private static final float SCALE = 1.35F;
	static final BoxMesh BODY = body();
	static final BoxMesh FINS = fins();

	public RpgGrenadeRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
		public float time;
		public boolean burning;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(RpgRocketEntity entity) {
		return entity.getBoundingBox().inflate(1.5);
	}

	@Override
	public void extractRenderState(RpgRocketEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 v = entity.getDeltaMovement();
		MissileRenderer.orient(state.rotation, v.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : v.normalize());
		state.time = entity.tickCount + partialTick;
		state.burning = entity.sustainerBurning();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		float t = state.time;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.12F, 0.0F);
		poseStack.mulPose(state.rotation);
		poseStack.scale(SCALE, SCALE, SCALE);
		poseStack.translate(0.0F, -0.48F, 0.0F);
		// spin-stabilised: the canted fins turn it about 10 times a second
		poseStack.mulPose(Axis.YP.rotationDegrees(t * 180.0F));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> {
			BODY.emit(pose, consumer, light);
			FINS.emit(pose, consumer, light);
		});
		if (state.burning) {
			collector.submitCustomGeometry(poseStack, FLAME_TYPE, (pose, consumer) -> {
				MissileRenderer.flame(pose, consumer, 0.03F, 0.55F, t, 1.0F);
				MissileRenderer.flame(pose, consumer, 0.018F, 0.35F, t + 3.0F, 1.0F);
			});
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** Along +Y from the nozzle (y = 0) to the tip of the nose probe (y = 0.95). */
	private static BoxMesh body() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f o = v(0, 0, 0);
		Vector3f up = v(0, 1, 0);
		// sustainer nozzle and motor
		b.revolve(BLACK, o, up, new float[][] {{0.0F, 0.0F}, {0.0F, 0.026F}, {0.04F, 0.022F}}, 12);
		b.revolve(OLIVE_DARK, o, up, new float[][] {{0.04F, 0.022F}, {0.43F, 0.022F}}, 12);
		// motor-to-warhead adapter
		b.revolve(OLIVE, o, up, new float[][] {{0.43F, 0.022F}, {0.5F, 0.038F}, {0.53F, 0.0425F}}, 14);
		// warhead: cylindrical case, a black band, ogive and the piezo probe
		b.revolve(OLIVE, o, up, new float[][] {{0.53F, 0.0425F}, {0.62F, 0.0425F}}, 14);
		b.revolve(BLACK, o, up, new float[][] {{0.62F, 0.0435F}, {0.64F, 0.0435F}}, 14);
		b.revolve(OLIVE, o, up, new float[][] {{0.64F, 0.0425F}, {0.7F, 0.0425F}, {0.76F, 0.036F}, {0.81F, 0.022F}, {0.84F, 0.012F}}, 14);
		b.revolve(STEEL, o, up, new float[][] {{0.84F, 0.012F}, {0.93F, 0.008F}, {0.95F, 0.0F}}, 8);
		return b.build();
	}

	private static BoxMesh fins() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (int i = 0; i < 4; i++) {
			float a = i * (float) Math.PI * 0.5F;
			float cx = (float) Math.cos(a);
			float cz = (float) Math.sin(a);
			// thin fins folded out from the tail, slightly swept
			b.beam(v(cx * 0.02F, 0.05F, cz * 0.02F), v(cx * 0.085F, 0.03F, cz * 0.085F), 0.006F, 0.09F, STEEL);
		}
		return b.build();
	}
}
