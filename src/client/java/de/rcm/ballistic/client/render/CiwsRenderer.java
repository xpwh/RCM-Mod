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
 * Phalanx-style turret on top of the CIWS pedestal: the training mount turns towards the target,
 * the elevating mass (radome, gun, ammunition drum) pitches up, and the six-barrel cluster spins
 * while it fires. Tracers stream from the muzzle towards the target.
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
	private static final float BARREL_RING = 0.075F;

	private static final BoxMesh MOUNT = buildMount();
	private static final BoxMesh ELEVATING = buildElevating();
	private static final BoxMesh RADOME = new BoxMesh.Builder().cylinderX(0, 0, 0, 0.3F, 0.62F, 18, WHITE, WHITE).build();
	private static final BoxMesh RADOME_CAP = new BoxMesh.Builder().cylinderX(0, 0, 0, 0.2F, 0.12F, 18, WHITE, WHITE).build();
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

		// radome: vertical drum above the gun, with its cap
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.62F, -0.12F);
		poseStack.mulPose(new Quaternionf().rotationZ(Mth.HALF_PI));
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> RADOME.emit(pose, consumer, light));
		poseStack.translate(0.36F, 0.0F, 0.0F);
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> RADOME_CAP.emit(pose, consumer, light));
		poseStack.popPose();

		// spinning barrel cluster
		poseStack.pushPose();
		poseStack.mulPose(new Quaternionf().rotationZ(state.spin));
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> BARRELS.emit(pose, consumer, light));
		poseStack.popPose();

		if (state.firing) {
			int glow = LightTexture.FULL_BRIGHT;
			float t = state.time;
			float flash = 0.18F + 0.1F * Mth.sin(t * 9.0F);
			BoxMesh muzzle = new BoxMesh.Builder().box(-flash, -flash, 1.5F, flash, flash, 1.75F + flash * 2.0F, FLASH).build();
			// a few tracer streaks racing out along the line of fire
			BoxMesh.Builder tracers = new BoxMesh.Builder();
			float range = state.distance;
			float spacing = Math.max(6.0F, range / 4.0F);
			float travel = (t * 18.0F) % spacing;
			for (float z = 2.0F + travel; z < range; z += spacing) {
				float jitter = Mth.sin(z * 3.1F + t) * 0.06F * (z / 30.0F);
				tracers.box(-0.04F + jitter, -0.04F - jitter, z, 0.04F + jitter, 0.04F - jitter, Math.min(range, z + 3.0F), TRACER);
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

	/** Training mount in yaw space, standing on the pedestal (which ends at y = 1). */
	private static BoxMesh buildMount() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.44F, 1.0F, -0.44F, 0.44F, 1.12F, 0.44F, DARK); // training ring
		b.box(-0.38F, 1.12F, -0.38F, 0.38F, 1.22F, 0.38F, HAZE);
		// trunnion arms carrying the elevating mass
		b.box(-0.42F, 1.22F, -0.2F, -0.3F, PIVOT_Y + 0.12F, 0.2F, HAZE);
		b.box(0.3F, 1.22F, -0.2F, 0.42F, PIVOT_Y + 0.12F, 0.2F, HAZE);
		b.box(-0.44F, PIVOT_Y - 0.08F, -0.08F, -0.42F, PIVOT_Y + 0.08F, 0.08F, STEEL);
		b.box(0.42F, PIVOT_Y - 0.08F, -0.08F, 0.44F, PIVOT_Y + 0.08F, 0.08F, STEEL);
		b.box(-0.36F, 1.05F, 0.38F, 0.36F, 1.15F, 0.46F, HAZARD); // safety band at the front
		return b.build();
	}

	/** Elevating mass in pitch space: +Z along the barrels, pivot at the origin. */
	private static BoxMesh buildElevating() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.18F, -0.16F, -0.5F, 0.18F, 0.16F, 0.3F, HAZE); // gun housing / receiver
		b.box(-0.27F, -0.5F, -0.62F, 0.27F, -0.12F, 0.15F, HAZE); // ammunition drum
		b.box(-0.28F, -0.42F, -0.5F, 0.28F, -0.4F, 0.05F, DARK);
		b.box(-0.1F, 0.16F, -0.3F, 0.1F, 0.36F, 0.0F, DARK); // radome support
		b.box(-0.2F, -0.1F, 0.3F, 0.2F, 0.1F, 0.42F, DARK); // barrel bearing
		b.box(-0.14F, -0.14F, 1.3F, 0.14F, 0.14F, 1.38F, STEEL); // muzzle clamp
		b.box(-0.05F, 0.36F, 0.12F, 0.05F, 0.42F, 0.3F, BLACK); // FLIR / tracking camera
		b.box(0.18F, -0.08F, -0.35F, 0.26F, 0.06F, 0.1F, DARK); // ejection chute
		return b.build();
	}

	private static BoxMesh buildBarrels() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (int i = 0; i < 6; i++) {
			float a = i * Mth.TWO_PI / 6;
			float x = Mth.cos(a) * BARREL_RING;
			float y = Mth.sin(a) * BARREL_RING;
			b.beam(new Vector3f(x, y, 0.35F), new Vector3f(x, y, 1.55F), 0.045F, 0.045F, BLACK);
		}
		b.box(-0.11F, -0.11F, 0.8F, 0.11F, 0.11F, 0.86F, STEEL); // mid-barrel clamp
		return b.build();
	}
}
