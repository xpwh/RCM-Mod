package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.LENS;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.entity.FpvDroneEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * The FPV kamikaze drone: a 7-inch carbon X frame, four motors with spinning props, the camera
 * tilted up at the nose with its antenna, the battery strapped on top and a PG-7 shaped-charge
 * warhead zip-tied underneath, its probe pointing forward. Drawn a little bigger than life.
 */
public class FpvDroneRenderer extends EntityRenderer<FpvDroneEntity, FpvDroneRenderer.State> {
	private static final float SCALE = 1.6F;
	private static final float ARM = 0.16F;
	private static final BoxMesh BODY = body();
	private static final BoxMesh PROP = new BoxMesh.Builder()
		.box(-0.085F, 0.0F, -0.008F, 0.085F, 0.004F, 0.008F, BLACK)
		.build();

	public FpvDroneRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.2F;
	}

	public static class State extends EntityRenderState {
		public float yaw;
		public float pitch;
		public float roll;
		public float time;
		public float throttle;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(FpvDroneEntity drone, State state, float partialTick) {
		super.extractRenderState(drone, state, partialTick);
		state.yaw = drone.getViewYRot(partialTick);
		state.pitch = drone.getViewXRot(partialTick);
		state.roll = drone.getRoll();
		state.time = drone.tickCount + partialTick;
		state.throttle = drone.getThrottle();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.12F, 0.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.yaw));
		// it flies nose down to go forward: the camera is tilted up to make up for it
		poseStack.mulPose(Axis.XP.rotationDegrees(Mth.clamp(state.pitch, -60.0F, 60.0F) * 0.6F + 18.0F * state.throttle));
		poseStack.mulPose(Axis.ZP.rotationDegrees(state.roll));
		poseStack.scale(SCALE, SCALE, SCALE);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> BODY.emit(pose, consumer, light));
		float spin = state.time * (state.throttle > 0.0F ? 140.0F : 0.0F);
		for (int i = 0; i < 4; i++) {
			float sx = (i % 2 == 0 ? -1.0F : 1.0F) * ARM;
			float sz = (i < 2 ? -1.0F : 1.0F) * ARM;
			for (int blade = 0; blade < 2; blade++) {
				poseStack.pushPose();
				poseStack.translate(sx, 0.05F, sz);
				poseStack.mulPose(Axis.YP.rotationDegrees((i % 3 == 0 ? spin : -spin) + blade * 90.0F + i * 30.0F));
				collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> PROP.emit(pose, consumer, light));
				poseStack.popPose();
			}
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** In drone space: +Z forward (nose), +Y up, origin in the middle of the frame. */
	private static BoxMesh body() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// carbon X frame and the centre plates
		b.beam(v(-ARM, 0.0F, -ARM), v(ARM, 0.0F, ARM), 0.03F, 0.012F, BLACK);
		b.beam(v(ARM, 0.0F, -ARM), v(-ARM, 0.0F, ARM), 0.03F, 0.012F, BLACK);
		b.box(-0.04F, -0.006F, -0.07F, 0.04F, 0.006F, 0.07F, BLACK);
		b.box(-0.035F, 0.006F, -0.05F, 0.035F, 0.03F, 0.05F, GUNMETAL); // flight controller stack
		b.box(-0.04F, 0.03F, -0.055F, 0.04F, 0.036F, 0.055F, BLACK);
		// four motors
		for (int i = 0; i < 4; i++) {
			float sx = (i % 2 == 0 ? -1.0F : 1.0F) * ARM;
			float sz = (i < 2 ? -1.0F : 1.0F) * ARM;
			b.revolve(GUNMETAL, v(sx, 0.006F, sz), v(0, 1, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 0.02F}, {0.035F, 0.02F}, {0.04F, 0.0F}}, 10);
			b.box(sx - 0.004F, 0.04F, sz - 0.004F, sx + 0.004F, 0.05F, sz + 0.004F, STEEL);
		}
		// battery on top, strapped down, its lead to the stack
		b.box(-0.03F, 0.036F, -0.06F, 0.03F, 0.075F, 0.05F, YELLOW);
		b.box(-0.031F, 0.05F, -0.005F, 0.031F, 0.077F, 0.008F, BLACK);
		b.beam(v(0.02F, 0.06F, -0.06F), v(0.02F, 0.03F, -0.075F), 0.008F, 0.008F, RED);
		// camera in its cage at the nose, tilted up, and the video antenna sticking out the back
		b.box(-0.022F, 0.01F, 0.06F, 0.022F, 0.05F, 0.09F, BLACK);
		b.box(-0.012F, 0.022F, 0.09F, 0.012F, 0.044F, 0.096F, LENS);
		b.beam(v(0.0F, 0.035F, -0.07F), v(0.0F, 0.09F, -0.13F), 0.008F, 0.008F, CABLE);
		b.box(-0.01F, 0.085F, -0.14F, 0.01F, 0.1F, -0.12F, RED);
		// the PG-7 warhead under it, nose and probe forward, cable-tied to the frame
		Vector3f o = v(0.0F, -0.055F, -0.12F);
		Vector3f fwd = v(0, 0, 1);
		b.revolve(OLIVE, o, fwd, new float[][] {{0.0F, 0.0F}, {0.0F, 0.04F}, {0.13F, 0.04F}, {0.18F, 0.032F}, {0.22F, 0.018F}, {0.24F, 0.01F}}, 12);
		b.revolve(STEEL, o, fwd, new float[][] {{0.24F, 0.01F}, {0.3F, 0.006F}, {0.31F, 0.0F}}, 8);
		b.revolve(BLACK, o, fwd, new float[][] {{0.06F, 0.041F}, {0.075F, 0.041F}}, 12);
		b.box(-0.045F, -0.03F, -0.04F, -0.04F, -0.006F, -0.03F, BLACK);
		b.box(0.04F, -0.03F, -0.04F, 0.045F, -0.006F, -0.03F, BLACK);
		b.box(-0.045F, -0.03F, 0.05F, -0.04F, -0.006F, 0.06F, BLACK);
		b.box(0.04F, -0.03F, 0.05F, 0.045F, -0.006F, 0.06F, BLACK);
		return b.build();
	}
}
