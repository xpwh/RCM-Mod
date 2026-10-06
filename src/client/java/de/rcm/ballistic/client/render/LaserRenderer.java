package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BEAM;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.GLASS;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.LAMP;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.LaserDefenseBlockEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Iron Beam-style laser battery: a 20 ft container holding the laser and its cooling, radiators on
 * the side, and on the roof the beam director - a large telescope on a yaw/pitch gimbal with sensor
 * pods. While it burns a target the beam is drawn from the telescope to the target.
 */
public class LaserRenderer implements BlockEntityRenderer<LaserDefenseBlockEntity, LaserRenderer.State> {
	private static final float PIVOT = (float) LaserDefenseBlockEntity.EMITTER_Y;
	private static final float ROOF = 2.6F;

	private static final BoxMesh CONTAINER = buildContainer();
	private static final BoxMesh YOKE = buildYoke();
	private static final BoxMesh DIRECTOR = buildDirector();
	private static final BoxMesh READY_LAMP = new BoxMesh.Builder().box(2.6F, ROOF, 0.8F, 2.8F, ROOF + 0.2F, 1.0F, GREEN_LAMP).build();
	private static final BoxMesh FAULT_LAMP = new BoxMesh.Builder().box(2.6F, ROOF, 0.8F, 2.8F, ROOF + 0.2F, 1.0F, RED_LAMP).build();

	public LaserRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float yaw;
		public float pitch;
		public boolean powered;
		public float heat;
		public @Nullable Vector3f target;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(LaserDefenseBlockEntity laser, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(laser, state, partialTick, cameraPos, overlay);
		state.yaw = Mth.rotLerpRad(partialTick, laser.clientYawO, laser.clientYaw);
		state.pitch = Mth.lerp(partialTick, laser.clientPitchO, laser.clientPitch);
		state.powered = laser.isPowered();
		state.heat = laser.getHeat();
		state.time = laser.getLevel() == null ? 0.0F : laser.getLevel().getGameTime() + partialTick;
		Entity target = laser.getTarget();
		if (target != null && state.heat > 0.0F) {
			Vec3 p = target.getPosition(partialTick).add(0, target.getBbHeight() * 0.5, 0);
			Vec3 o = Vec3.atBottomCenterOf(laser.getBlockPos());
			state.target = new Vector3f((float) (p.x - o.x), (float) (p.y - o.y), (float) (p.z - o.z));
		} else {
			state.target = null;
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CONTAINER.emit(pose, consumer, light));
		BoxMesh lamp = state.powered ? READY_LAMP : FAULT_LAMP;
		collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> lamp.emit(pose, consumer, LightTexture.FULL_BRIGHT));

		Vector3f target = state.target;
		if (target != null) {
			// the beam leaves the telescope's exit aperture
			Vector3f dir = new Vector3f(Mth.cos(state.pitch) * Mth.sin(state.yaw), Mth.sin(state.pitch), Mth.cos(state.pitch) * Mth.cos(state.yaw));
			Vector3f start = new Vector3f(dir).mul(0.85F).add(0, PIVOT, 0);
			float flicker = 0.85F + 0.15F * Mth.sin(state.time * 7.0F);
			float w = (0.12F + 0.1F * state.heat) * flicker;
			BoxMesh beam = new BoxMesh.Builder()
				.beam(start, target, w, w, BEAM)
				.beam(start, target, w * 0.4F, w * 0.4F, LAMP)
				.box(target.x - w * 2.5F, target.y - w * 2.5F, target.z - w * 2.5F, target.x + w * 2.5F, target.y + w * 2.5F, target.z + w * 2.5F, LAMP)
				.build();
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> beam.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}

		poseStack.mulPose(new Quaternionf().rotationY(state.yaw));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> YOKE.emit(pose, consumer, light));
		poseStack.translate(0.0F, PIVOT, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationX(-state.pitch));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> DIRECTOR.emit(pose, consumer, light));
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

	private static BoxMesh buildContainer() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-3.0F, 0.0F, -1.2F, 3.0F, ROOF, 1.2F, OLIVE);
		// corrugated side walls, corner castings, roof edge
		for (float x = -2.8F; x < 2.9F; x += 0.3F) {
			b.box(x, 0.15F, -1.24F, x + 0.12F, ROOF - 0.15F, -1.2F, OLIVE_DARK);
			b.box(x, 0.15F, 1.2F, x + 0.12F, ROOF - 0.15F, 1.24F, OLIVE_DARK);
		}
		for (float[] c : new float[][] {{-3.0F, -1.2F}, {2.85F, -1.2F}, {-3.0F, 1.05F}, {2.85F, 1.05F}}) {
			b.box(c[0] - 0.02F, 0.0F, c[1] - 0.02F, c[0] + 0.17F, ROOF + 0.02F, c[1] + 0.17F, STEEL);
		}
		// cargo doors with locking bars on one end, the cooling plant on the other
		b.box(-3.03F, 0.1F, -1.1F, -3.0F, ROOF - 0.1F, 1.1F, DOOR);
		for (float z : new float[] {-0.7F, -0.25F, 0.25F, 0.7F}) {
			b.box(-3.06F, 0.15F, z - 0.03F, -3.03F, ROOF - 0.15F, z + 0.03F, STEEL);
		}
		b.box(3.0F, 0.3F, -1.0F, 3.45F, 2.1F, 1.0F, VENT); // chiller
		b.box(3.45F, 0.5F, -0.9F, 3.5F, 1.9F, 0.9F, BLACK);
		b.box(-1.8F, 0.4F, 1.24F, 0.8F, 1.6F, 1.5F, VENT); // side radiator
		b.box(-2.4F, 1.6F, 1.24F, -2.0F, 2.1F, 1.32F, PANEL); // control panel
		b.box(-3.5F, 0.0F, -0.2F, 2.2F, 0.12F, 0.0F, CABLE); // power feed
		b.box(-3.0F, 0.0F, -1.25F, 3.0F, 0.12F, -1.2F, HAZARD);
		// roof: walkway and the turret pedestal
		b.box(-2.8F, ROOF, -0.3F, -0.8F, ROOF + 0.05F, 0.3F, PANEL);
		b.revolve(OLIVE_DARK, v(0, ROOF, 0), v(0, 1, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 0.75F}, {0.3F, 0.7F}, {0.3F, 0.0F}}, 20);
		return b.build();
	}

	/** Gimbal yoke in yaw space. */
	private static BoxMesh buildYoke() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(OLIVE, v(0, ROOF + 0.3F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 0.6F}, {0.2F, 0.6F}, {0.2F, 0.0F}}, 20);
		for (float x : new float[] {-0.78F, 0.62F}) {
			b.box(x, ROOF + 0.5F, -0.3F, x + 0.16F, PIVOT + 0.2F, 0.3F, OLIVE);
		}
		return b.build();
	}

	/** Beam director in pitch space: telescope along +Z with its exit window and sensor pods. */
	private static BoxMesh buildDirector() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f fwd = v(0, 0, 1);
		b.revolve(WHITE, v(0, 0, -0.65F), fwd, new float[][] {
			{0.0F, 0.0F}, {0.0F, 0.32F}, {0.12F, 0.42F}, {1.25F, 0.46F}, {1.35F, 0.5F}, {1.45F, 0.5F}
		}, 24);
		b.revolve(GLASS, v(0, 0, 0.78F), fwd, new float[][] {{0.0F, 0.0F}, {0.0F, 0.42F}, {0.03F, 0.42F}, {0.03F, 0.0F}}, 24);
		b.revolve(STEEL, v(0, 0, -0.1F), v(1, 0, 0), new float[][] {{-0.62F, 0.0F}, {-0.62F, 0.12F}, {0.62F, 0.12F}, {0.62F, 0.0F}}, 12);
		// tracking cameras and the illuminator laser pod
		b.box(0.48F, 0.1F, -0.3F, 0.68F, 0.32F, 0.35F, OLIVE_DARK);
		b.box(0.52F, 0.14F, 0.35F, 0.64F, 0.28F, 0.37F, BLACK);
		b.box(-0.68F, 0.1F, -0.3F, -0.48F, 0.32F, 0.35F, OLIVE_DARK);
		b.box(-0.64F, 0.14F, 0.35F, -0.52F, 0.28F, 0.37F, BLACK);
		b.box(-0.12F, 0.48F, -0.5F, 0.12F, 0.62F, 0.4F, OLIVE_DARK);
		return b.build();
	}
}
