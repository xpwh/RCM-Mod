package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.block.CiwsBlockEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Phalanx Block 1B-style turret at full size (about 4.5 blocks tall) on top of the CIWS pedestal.
 * The training mount turns towards the target; the elevating mass pitches up with everything on it:
 * the white "R2-D2" radome (search antenna in the dome, track antenna in the drum below), the
 * receiver, the big ammunition drum underneath with its feed and return chutes, and the six-barrel
 * cluster with its shroud and muzzle restrainer, which spins while it fires. A FLIR sits on the side
 * of the mount. Tracers stream from the muzzle towards the target.
 */
public class CiwsRenderer implements BlockEntityRenderer<CiwsBlockEntity, CiwsRenderer.State> {
	private static final RenderType TYPE = RenderTypes.entityCutout(BallisticMissiles.id("textures/entity/ciws.png"));
	private static final RenderType GLOW_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/ciws.png"));

	private static final int HAZE = 0;
	private static final int WHITE = 1;
	private static final int DARK = 2;
	private static final int STEEL = 3;
	private static final int BLACK = 4;
	private static final int TRACER = 5;
	private static final int FLASH = 6;
	private static final int HAZARD = 7;

	/** Pitch pivot height above the block origin. */
	private static final float PIVOT_Y = (float) CiwsBlockEntity.MUZZLE_Y;
	private static final float MUZZLE = (float) CiwsBlockEntity.BARREL_LENGTH;
	private static final float BARREL_RING = 0.09F;

	private static final BoxMesh MOUNT = buildMount();
	private static final BoxMesh ELEVATING = buildElevating();
	private static final BoxMesh BARRELS = buildBarrels();

	public CiwsRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float yaw;
		public float pitch;
		public float spin;
		public boolean firing;
		public float distance;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(CiwsBlockEntity gun, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(gun, state, partialTick, cameraPos, overlay);
		state.yaw = Mth.rotLerpRad(partialTick, gun.clientYawO, gun.clientYaw);
		state.pitch = Mth.lerp(partialTick, gun.clientPitchO, gun.clientPitch);
		state.spin = Mth.lerp(partialTick, gun.barrelSpinO, gun.barrelSpin);
		state.firing = gun.isFiring();
		state.distance = Math.max(4.0F, gun.getTargetDistance());
		state.time = gun.getLevel() == null ? 0.0F : gun.getLevel().getGameTime() + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.mulPose(new Quaternionf().rotationY(state.yaw));
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> MOUNT.emit(pose, consumer, light));

		poseStack.translate(0.0F, PIVOT_Y, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationX(-state.pitch));
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> ELEVATING.emit(pose, consumer, light));

		poseStack.pushPose();
		poseStack.mulPose(new Quaternionf().rotationZ(state.spin));
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> BARRELS.emit(pose, consumer, light));
		poseStack.popPose();

		if (state.firing) {
			int glow = LightTexture.FULL_BRIGHT;
			float t = state.time;
			float flash = 0.22F + 0.12F * Mth.sin(t * 9.0F);
			BoxMesh muzzle = new BoxMesh.Builder().box(-flash, -flash, MUZZLE, flash, flash, MUZZLE + 0.3F + flash * 2.0F, FLASH).build();
			// tracer streaks racing out along the line of fire
			BoxMesh.Builder tracers = new BoxMesh.Builder();
			float range = state.distance;
			float spacing = Math.max(6.0F, range / 4.0F);
			float travel = (t * 18.0F) % spacing;
			for (float z = MUZZLE + 0.5F + travel; z < range; z += spacing) {
				float jitter = Mth.sin(z * 3.1F + t) * 0.06F * (z / 30.0F);
				tracers.box(-0.045F + jitter, -0.045F - jitter, z, 0.045F + jitter, 0.045F - jitter, Math.min(range, z + 3.5F), TRACER);
			}
			BoxMesh streaks = tracers.build();
			collector.submitCustomGeometry(poseStack, GLOW_TYPE, (pose, consumer) -> {
				muzzle.emit(pose, consumer, glow);
				streaks.emit(pose, consumer, glow);
			});
		}
		poseStack.popPose();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 256;
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** Training mount in yaw space, standing on the pedestal (which ends at y = 1). */
	private static BoxMesh buildMount() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 1, 0);
		// base ring and the training drive housing
		b.revolve(HAZE, v(0, 1.0F, 0), up, new float[][] {{0.0F, 0.0F}, {0.0F, 0.82F}, {0.22F, 0.82F}, {0.3F, 0.7F}, {0.3F, 0.0F}}, 24);
		b.revolve(DARK, v(0, 1.0F, 0), up, new float[][] {{0.08F, 0.84F}, {0.14F, 0.84F}}, 24); // bolt ring
		b.box(-0.55F, 1.3F, -0.8F, 0.55F, 1.95F, 0.5F, HAZE); // training mount body
		b.box(-0.5F, 1.95F, -0.75F, 0.5F, 2.0F, 0.2F, DARK);
		b.box(-0.57F, 1.4F, -0.6F, -0.55F, 1.85F, 0.1F, DARK); // access panels
		b.box(0.55F, 1.4F, -0.6F, 0.57F, 1.85F, 0.1F, DARK);
		// trunnion arms (yoke) up to the elevation axis
		for (float x : new float[] {-0.72F, 0.56F}) {
			b.box(x, 1.3F, -0.38F, x + 0.16F, PIVOT_Y + 0.22F, 0.38F, HAZE);
			b.revolve(STEEL, v(x - 0.02F, PIVOT_Y, 0), v(1, 0, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 0.2F}, {0.2F, 0.2F}, {0.2F, 0.0F}}, 16);
		}
		// Block 1B electro-optical sensor (FLIR) on its own bracket on the left of the mount
		b.box(-1.02F, 2.05F, -0.12F, -0.72F, 2.15F, 0.12F, HAZE);
		b.box(-1.1F, 2.15F, -0.3F, -0.78F, 2.62F, 0.28F, HAZE);
		b.box(-1.06F, 2.25F, 0.28F, -0.82F, 2.52F, 0.3F, BLACK); // lens window
		b.box(-1.12F, 2.62F, -0.25F, -0.76F, 2.66F, 0.23F, DARK);
		// warning stripe at the front of the base
		b.box(-0.55F, 1.3F, 0.5F, 0.55F, 1.4F, 0.52F, HAZARD);
		return b.build();
	}

	/** Elevating mass in pitch space: +Z along the barrels, the elevation axis at the origin. */
	private static BoxMesh buildElevating() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f up = v(0, 1, 0);
		Vector3f fwd = v(0, 0, 1);
		// radome: track-antenna drum, search-antenna section and the dome on top, on its adapter plate
		b.box(-0.5F, 0.18F, -0.95F, 0.5F, 0.34F, 0.08F, HAZE);
		b.revolve(WHITE, v(0, 0.34F, -0.42F), up, new float[][] {
			{0.0F, 0.0F}, {0.0F, 0.5F}, {0.62F, 0.53F}, {0.66F, 0.5F}, {1.2F, 0.48F},
			{1.36F, 0.45F}, {1.5F, 0.38F}, {1.6F, 0.27F}, {1.66F, 0.14F}, {1.68F, 0.0F}
		}, 24);
		b.revolve(DARK, v(0, 0.34F, -0.42F), up, new float[][] {{0.6F, 0.535F}, {0.68F, 0.535F}}, 24); // seam band
		// receiver / gun housing and the barrel shroud (the barrels themselves spin)
		b.box(-0.24F, -0.22F, -0.75F, 0.24F, 0.2F, 0.3F, DARK);
		b.revolve(STEEL, v(0, 0, 0.3F), fwd, new float[][] {{0.0F, 0.0F}, {0.0F, 0.22F}, {0.5F, 0.21F}, {0.55F, 0.17F}}, 18);
		// ammunition drum under the gun, with end caps, feed and return chutes
		b.revolve(HAZE, v(0, -0.62F, -1.45F), fwd, new float[][] {{0.0F, 0.0F}, {0.0F, 0.4F}, {0.08F, 0.42F}, {1.42F, 0.42F}, {1.5F, 0.4F}, {1.5F, 0.0F}}, 20);
		b.revolve(DARK, v(0, -0.62F, -1.45F), fwd, new float[][] {{0.7F, 0.43F}, {0.76F, 0.43F}}, 20);
		b.beam(v(0.15F, -0.3F, -0.1F), v(0.32F, -0.35F, -0.8F), 0.16F, 0.12F, DARK); // feed chute
		b.beam(v(-0.15F, -0.3F, -0.1F), v(-0.32F, -0.35F, -0.8F), 0.16F, 0.12F, DARK); // return chute
		b.box(-0.06F, -1.06F, -1.2F, 0.06F, -1.0F, -0.2F, HAZARD); // stencil stripe along the drum
		// recoil adapters and the elevation drive arc on the sides
		b.box(-0.3F, -0.12F, 0.0F, -0.24F, 0.12F, 0.5F, STEEL);
		b.box(0.24F, -0.12F, 0.0F, 0.3F, 0.12F, 0.5F, STEEL);
		return b.build();
	}

	/** Spinning barrel cluster with its two clamps and the muzzle restrainer. */
	private static BoxMesh buildBarrels() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f fwd = v(0, 0, 1);
		for (int i = 0; i < 6; i++) {
			float a = i * Mth.TWO_PI / 6;
			float x = Mth.cos(a) * BARREL_RING;
			float y = Mth.sin(a) * BARREL_RING;
			b.beam(v(x, y, 0.75F), v(x, y, MUZZLE), 0.06F, 0.06F, BLACK);
		}
		b.revolve(STEEL, v(0, 0, 1.35F), fwd, new float[][] {{0.0F, 0.0F}, {0.0F, 0.15F}, {0.08F, 0.15F}, {0.08F, 0.0F}}, 12);
		b.revolve(STEEL, v(0, 0, 1.95F), fwd, new float[][] {{0.0F, 0.0F}, {0.0F, 0.15F}, {0.08F, 0.15F}, {0.08F, 0.0F}}, 12);
		b.revolve(DARK, v(0, 0, MUZZLE - 0.18F), fwd, new float[][] {{0.0F, 0.0F}, {0.0F, 0.16F}, {0.16F, 0.16F}, {0.16F, 0.0F}}, 12);
		return b.build();
	}
}
