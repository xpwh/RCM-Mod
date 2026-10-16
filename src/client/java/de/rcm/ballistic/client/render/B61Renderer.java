package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.BOMB_GRAY;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.entity.EarthPenetratorEntity;
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
 * B61 Mod 11: a long, slim bomb in a one-piece hardened steel case with a sharp ogive nose, the
 * seams of the nose and centre sections, two suspension lugs on top, a tapering tail cone with the
 * spin-rocket ports, and four small swept fins. Nose along the flight path, rolling slowly from its
 * spin rockets.
 */
public class B61Renderer extends EntityRenderer<EarthPenetratorEntity, B61Renderer.State> {
	/** About 3.6 m long and 34 cm thick in reality; drawn at 0.85 length with a little more girth. */
	static final float LENGTH = 3.05F;
	static final float RADIUS = 0.19F;
	static final BoxMesh MESH = mesh();

	public B61Renderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.2F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf rotation = new Quaternionf();
		public float spin;
		public boolean buried;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(EarthPenetratorEntity entity) {
		return entity.getBoundingBox().inflate(2.5);
	}

	@Override
	public void extractRenderState(EarthPenetratorEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 v = entity.getDeltaMovement();
		MissileRenderer.orient(state.rotation, v.lengthSqr() < 1.0E-6 ? new Vec3(0, -1, 0) : v.normalize());
		float t = entity.tickCount + partialTick;
		// spun up by the rockets, then a steady roll of about one turn a second
		state.spin = t < EarthPenetratorEntity.SPIN_ROCKETS ? t * t * 0.9F : (EarthPenetratorEntity.SPIN_ROCKETS * EarthPenetratorEntity.SPIN_ROCKETS * 0.9F)
			+ (t - EarthPenetratorEntity.SPIN_ROCKETS) * 18.0F;
		state.buried = entity.isBuried();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.buried) {
			return;
		}
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.25F, 0.0F);
		poseStack.mulPose(state.rotation);
		poseStack.mulPose(Axis.YP.rotationDegrees(state.spin));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> MESH.emit(pose, consumer, light));
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** Along +Y, centred: tail at -LENGTH/2, nose tip at +LENGTH/2. */
	static BoxMesh mesh() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float r = RADIUS;
		float tail = -LENGTH * 0.5F;
		Vector3f o = v(0, tail, 0);
		Vector3f up = v(0, 1, 0);
		// tail cone with the closed end cap
		b.revolve(GUNMETAL, o, up, new float[][] {{0.0F, 0.0F}, {0.0F, r * 0.55F}, {0.06F, r * 0.62F}, {0.42F, r * 0.92F}, {0.52F, r}}, 18);
		// tail-section seam, then the long centre section of the case
		b.revolve(BLACK, o, up, new float[][] {{0.52F, r + 0.004F}, {0.55F, r + 0.004F}}, 18);
		b.revolve(BOMB_GRAY, o, up, new float[][] {{0.55F, r}, {1.55F, r}}, 18);
		b.revolve(BLACK, o, up, new float[][] {{1.55F, r + 0.004F}, {1.57F, r + 0.004F}}, 18);
		b.revolve(BOMB_GRAY, o, up, new float[][] {{1.57F, r}, {2.05F, r}}, 18);
		b.revolve(BLACK, o, up, new float[][] {{2.05F, r + 0.004F}, {2.075F, r + 0.004F}}, 18);
		// hardened nose: a long, sharp ogive of solid steel
		b.revolve(STEEL, o, up, new float[][] {
			{2.075F, r}, {2.3F, r * 0.97F}, {2.5F, r * 0.86F}, {2.7F, r * 0.64F}, {2.88F, r * 0.36F}, {3.0F, r * 0.1F}, {LENGTH, 0.0F}
		}, 18);
		// suspension lugs and the arming-wire well on top
		for (float y : new float[] {tail + 1.05F, tail + 1.8F}) {
			b.box(-0.025F, y - 0.03F, r - 0.01F, 0.025F, y + 0.03F, r + 0.05F, GUNMETAL);
		}
		b.box(-0.04F, tail + 1.36F, r - 0.005F, 0.04F, tail + 1.48F, r + 0.012F, BLACK);
		// spin-rocket ports in the tail cone, between the fins
		for (int i = 0; i < 4; i++) {
			float a = (i + 0.5F) * (float) Math.PI * 0.5F;
			float cx = (float) Math.cos(a);
			float cz = (float) Math.sin(a);
			float pr = r * 0.8F;
			b.beam(v(cx * pr, tail + 0.24F, cz * pr), v(cx * (pr + 0.025F), tail + 0.24F, cz * (pr + 0.025F)), 0.06F, 0.05F, BLACK);
		}
		// four small swept fins
		float th = 0.012F;
		for (int i = 0; i < 4; i++) {
			float a = i * (float) Math.PI * 0.5F;
			float cx = (float) Math.cos(a);
			float cz = (float) Math.sin(a);
			float tx = -cz * th;
			float tz = cx * th;
			float rootIn = r * 0.62F;
			float tipOut = r + 0.2F;
			float rootLo = tail + 0.04F;
			float rootHi = tail + 0.5F;
			float tipLo = tail + 0.02F;
			float tipHi = tail + 0.2F;
			b.hexa(STEEL,
				v(cx * rootIn - tx, rootLo, cz * rootIn - tz), v(cx * tipOut - tx, tipLo, cz * tipOut - tz),
				v(cx * tipOut - tx, tipHi, cz * tipOut - tz), v(cx * rootIn - tx, rootHi, cz * rootIn - tz),
				v(cx * rootIn + tx, rootLo, cz * rootIn + tz), v(cx * tipOut + tx, tipLo, cz * tipOut + tz),
				v(cx * tipOut + tx, tipHi, cz * tipOut + tz), v(cx * rootIn + tx, rootHi, cz * rootIn + tz));
		}
		return b.build();
	}
}
