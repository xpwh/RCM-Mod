package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.SUB_HULL;
import static de.rcm.ballistic.client.render.StructureKit.SUB_TILES;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.entity.PoseidonEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Poseidon: a torpedo the size of a mini-submarine. A rounded sonar nose, a long body under
 * rubber anechoic tiles with its section joints, a tapering tail cone carrying four control fins in
 * an X, and the pump-jet propulsor: a ring-shaped duct around the rotor hub with its stator vanes.
 */
public class PoseidonRenderer extends EntityRenderer<PoseidonEntity, PoseidonRenderer.State> {
	/** Real: about 20 m long and 1.6-2 m across; drawn 16 long, 1.5 across. */
	static final float LENGTH = 16.0F;
	static final float RADIUS = 0.75F;
	static final BoxMesh MESH = mesh();

	public PoseidonRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(PoseidonEntity entity) {
		return entity.getBoundingBox().inflate(9.0);
	}

	@Override
	public void extractRenderState(PoseidonEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 v = entity.getDeltaMovement();
		MissileRenderer.orient(state.rotation, v.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : v.normalize());
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.75F, 0.0F);
		poseStack.mulPose(state.rotation);
		poseStack.translate(0.0F, -LENGTH * 0.5F, 0.0F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> MESH.emit(pose, consumer, light));
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** Along +Y: pump-jet at y = 0, nose at y = LENGTH. */
	static BoxMesh mesh() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float r = RADIUS;
		Vector3f o = v(0, 0, 0);
		Vector3f up = v(0, 1, 0);
		// pump-jet: the duct (a thick ring) and the rotor hub inside it
		b.revolve(GUNMETAL, o, up, new float[][] {{0.15F, r * 1.02F}, {0.5F, r * 1.12F}, {1.4F, r * 1.1F}, {1.9F, r * 0.98F}}, 24);
		b.revolve(BLACK, o, up, new float[][] {{0.0F, 0.0F}, {0.05F, r * 0.18F}, {0.6F, r * 0.42F}, {1.6F, r * 0.55F}}, 16);
		for (int i = 0; i < 9; i++) {
			float a = i * (float) Math.PI * 2.0F / 9.0F;
			float cx = (float) Math.cos(a);
			float cz = (float) Math.sin(a);
			// stator vanes between the hub and the duct
			b.beam(v(cx * r * 0.45F, 1.15F, cz * r * 0.45F), v(cx * r * 0.98F, 1.25F, cz * r * 0.98F), 0.03F, 0.32F, GUNMETAL);
		}
		// tail cone under the tiles, then the long body with its section joints
		b.revolve(SUB_TILES, o, up, new float[][] {{1.6F, r * 0.55F}, {2.6F, r * 0.75F}, {3.8F, r * 0.92F}, {4.8F, r}}, 24);
		float[] joints = {4.8F, 7.6F, 10.4F, 13.0F};
		for (int i = 0; i < joints.length - 1; i++) {
			b.revolve(SUB_TILES, o, up, new float[][] {{joints[i] + 0.05F, r}, {joints[i + 1], r}}, 24);
			b.revolve(BLACK, o, up, new float[][] {{joints[i + 1], r * 1.008F}, {joints[i + 1] + 0.05F, r * 1.008F}}, 24);
		}
		b.revolve(SUB_TILES, o, up, new float[][] {{13.05F, r}, {13.8F, r}}, 24);
		// smooth sonar nose
		b.revolve(SUB_HULL, o, up, new float[][] {
			{13.8F, r}, {14.6F, r * 0.95F}, {15.2F, r * 0.78F}, {15.65F, r * 0.52F}, {15.9F, r * 0.25F}, {LENGTH, 0.0F}
		}, 24);
		// four control surfaces in an X on the tail cone, their tips braced to the duct
		float th = 0.05F;
		for (int i = 0; i < 4; i++) {
			float a = (i + 0.5F) * (float) Math.PI * 0.5F;
			float cx = (float) Math.cos(a);
			float cz = (float) Math.sin(a);
			float tx = -cz * th;
			float tz = cx * th;
			float in = r * 0.62F;
			float out = r * 1.75F;
			b.hexa(SUB_HULL,
				v(cx * in - tx, 1.7F, cz * in - tz), v(cx * out - tx, 1.9F, cz * out - tz),
				v(cx * out - tx, 2.9F, cz * out - tz), v(cx * in - tx, 3.9F, cz * in - tz),
				v(cx * in + tx, 1.7F, cz * in + tz), v(cx * out + tx, 1.9F, cz * out + tz),
				v(cx * out + tx, 2.9F, cz * out + tz), v(cx * in + tx, 3.9F, cz * in + tz));
			b.beam(v(cx * out, 1.95F, cz * out), v(cx * r * 1.1F, 1.2F, cz * r * 1.1F), 0.06F, 0.06F, GUNMETAL);
		}
		// flank sonar windows and a small towing/handling eye on top
		for (float side : new float[] {-1.0F, 1.0F}) {
			b.box(side * r - 0.03F, 10.8F, -0.18F, side * r + 0.03F, 12.6F, 0.18F, BLACK);
		}
		b.box(-0.08F, 12.2F, r - 0.02F, 0.08F, 12.6F, r + 0.1F, GUNMETAL);
		return b.build();
	}
}
