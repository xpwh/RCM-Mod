package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.DISH;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.GRATING;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.SHELTER;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.OrbitalUplinkBlock;
import de.rcm.ballistic.block.OrbitalUplinkBlockEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Satellite ground station: a concrete pad with a white equipment shelter (door, air conditioner,
 * cable tray, warning light) and a 3.6 m parabolic dish on an elevation-over-azimuth pedestal, with
 * its feed horn held at the focus by a quadripod and the counterweight and back structure behind
 * it. The dish tracks the weapons platform: it sweeps the sky when idle and slews round to uplink a
 * strike, the warning light flashing.
 */
public class OrbitalUplinkRenderer implements BlockEntityRenderer<OrbitalUplinkBlockEntity, OrbitalUplinkRenderer.State> {
	/** Height of the elevation axis above the block's bottom. */
	private static final float PIVOT_Y = 2.75F;
	private static final float DISH_R = 1.8F;
	/** Focal length of the paraboloid. */
	private static final float FOCUS = 1.15F;

	static final BoxMesh SITE = buildSite();
	static final BoxMesh TURNTABLE = buildTurntable();
	static final BoxMesh DISH_MESH = buildDish();
	private static final BoxMesh BEACON = new BoxMesh.Builder().box(1.75F, 2.02F, -0.15F, 1.95F, 2.2F, 0.05F, RED_LAMP).build();
	private static final BoxMesh READY = new BoxMesh.Builder().box(2.47F, 1.4F, 0.45F, 2.49F, 1.5F, 0.55F, GREEN_LAMP).build();

	public OrbitalUplinkRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float facing;
		public float azimuth;
		public float elevation;
		public int phase;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(OrbitalUplinkBlockEntity uplink, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(uplink, state, partialTick, cameraPos, overlay);
		Direction facing = state.blockState.hasProperty(OrbitalUplinkBlock.FACING) ? state.blockState.getValue(OrbitalUplinkBlock.FACING) : Direction.NORTH;
		state.facing = (float) Mth.atan2(facing.getStepX(), facing.getStepZ());
		state.azimuth = Mth.lerp(partialTick, uplink.azimuthO, uplink.azimuth);
		state.elevation = Mth.lerp(partialTick, uplink.elevationO, uplink.elevation);
		state.phase = uplink.phase();
		state.time = uplink.clientAge + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.pushPose();
		poseStack.mulPose(new Quaternionf().rotationY(state.facing));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> SITE.emit(pose, consumer, light));
		if (state.phase != OrbitalUplinkBlockEntity.IDLE && Mth.sin(state.time * 0.8F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BEACON.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		} else if (state.phase == OrbitalUplinkBlockEntity.IDLE) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> READY.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		poseStack.popPose();
		// azimuth: the whole head turns on the pedestal (world azimuth, 0 = +Z)
		poseStack.mulPose(new Quaternionf().rotationY(state.azimuth));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> TURNTABLE.emit(pose, consumer, light));
		// elevation: the dish tips up about the yoke's axis
		poseStack.translate(0.0F, PIVOT_Y, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationX(-state.elevation));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> DISH_MESH.emit(pose, consumer, light));
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

	/** Pad, pedestal and shelter; the shelter stands to the +X side, its door facing +Z. */
	private static BoxMesh buildSite() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// concrete pad (the block is its centre) with a hazard-striped kerb around the pedestal
		b.box(-1.5F, 0.0F, -1.5F, 2.6F, 1.02F, 1.5F, CONCRETE);
		b.box(-0.75F, 1.02F, -0.75F, 0.75F, 1.06F, 0.75F, HAZARD);
		// pedestal column with its base flange and anchor bolts
		b.box(-0.55F, 1.06F, -0.55F, 0.55F, 1.22F, 0.55F, STEEL);
		b.revolve(STEEL, v(0, 1.22F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.42F}, {1.0F, 0.36F}}, 14);
		for (int i = 0; i < 8; i++) {
			float a = i * Mth.TWO_PI / 8;
			b.box(Mth.cos(a) * 0.48F - 0.03F, 1.22F, Mth.sin(a) * 0.48F - 0.03F, Mth.cos(a) * 0.48F + 0.03F, 1.28F, Mth.sin(a) * 0.48F + 0.03F, BLACK);
		}
		// equipment shelter: corrugated white box, door, AC unit, roof rail
		b.box(1.0F, 1.02F, -1.1F, 2.45F, 2.0F, 1.1F, SHELTER);
		b.box(1.02F, 2.0F, -1.12F, 2.47F, 2.04F, 1.12F, STEEL);
		b.box(2.45F, 1.04F, -0.3F, 2.47F, 1.86F, 0.4F, DOOR);
		b.box(2.45F, 1.15F, -0.95F, 2.75F, 1.55F, -0.5F, VENT);
		b.box(1.25F, 2.04F, 0.6F, 1.55F, 2.08F, 0.9F, GRATING);
		// a little whip antenna on the roof
		b.beam(v(1.3F, 2.04F, -0.8F), v(1.3F, 2.9F, -0.8F), 0.02F, 0.02F, BLACK);
		// cable tray from the shelter to the pedestal
		b.box(0.4F, 1.06F, -0.08F, 1.0F, 1.14F, 0.08F, CABLE);
		// light mast for the warning beacon
		b.box(1.82F, 2.04F, -0.1F, 1.88F, 2.02F, 0.0F, STEEL);
		return b.build();
	}

	/** Azimuth head: turntable, bearing housing and the two yoke arms carrying the elevation axis. */
	private static BoxMesh buildTurntable() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(STEEL, v(0, 2.22F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.5F}, {0.18F, 0.5F}}, 16);
		b.box(-0.4F, 2.4F, -0.35F, 0.4F, 2.6F, 0.35F, WHITE);
		for (float x : new float[] {-0.5F, 0.42F}) {
			b.box(x, 2.4F, -0.18F, x + 0.08F, PIVOT_Y + 0.18F, 0.18F, WHITE);
		}
		// elevation drive motor
		b.box(-0.62F, 2.55F, -0.12F, -0.5F, 2.8F, 0.12F, BLACK);
		return b.build();
	}

	/** The dish looking along +Z at elevation 0, hub on the elevation axis. */
	private static BoxMesh buildDish() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// elevation axle and hub
		b.beam(v(-0.52F, 0.0F, 0.0F), v(0.52F, 0.0F, 0.0F), 0.16F, 0.16F, STEEL);
		b.box(-0.3F, -0.3F, -0.25F, 0.3F, 0.3F, 0.25F, WHITE);
		// paraboloid reflector: depth = r^2 / (4 f)
		int rings = 9;
		float[][] profile = new float[rings + 1][2];
		for (int i = 0; i <= rings; i++) {
			float r = DISH_R * i / rings;
			profile[i][0] = r * r / (4.0F * FOCUS);
			profile[i][1] = Math.max(r, 0.001F);
		}
		Vector3f vertex = v(0, 0, 0.25F);
		b.revolve(DISH, vertex, v(0, 0, 1), profile, 28);
		float depth = DISH_R * DISH_R / (4.0F * FOCUS);
		// rim stiffener
		b.revolve(STEEL, vertex, v(0, 0, 1), new float[][] {{depth - 0.03F, DISH_R + 0.04F}, {depth + 0.02F, DISH_R + 0.04F}}, 28);
		// back structure: ribs behind the reflector and the counterweight arm
		for (int i = 0; i < 6; i++) {
			float a = i * Mth.TWO_PI / 6 + 0.26F;
			float x = Mth.cos(a);
			float y = Mth.sin(a);
			b.beam(v(x * 0.25F, y * 0.25F, 0.2F), v(x * DISH_R * 0.85F, y * DISH_R * 0.85F, 0.25F + 0.85F * 0.85F * DISH_R * DISH_R / (4.0F * FOCUS) - 0.06F),
				0.05F, 0.05F, STEEL);
		}
		b.box(-0.18F, -0.9F, -0.55F, 0.18F, -0.5F, -0.2F, STEEL); // counterweight
		b.beam(v(0, -0.25F, -0.2F), v(0, -0.7F, -0.4F), 0.08F, 0.08F, STEEL);
		// quadripod holding the feed at the focus
		Vector3f feed = v(0, 0, 0.25F + FOCUS);
		for (int i = 0; i < 4; i++) {
			float a = i * Mth.HALF_PI + Mth.PI / 4;
			Vector3f rim = v(Mth.cos(a) * DISH_R * 0.92F, Mth.sin(a) * DISH_R * 0.92F, 0.25F + 0.92F * 0.92F * depth);
			b.beam(rim, feed, 0.035F, 0.035F, WHITE);
		}
		// feed horn and LNB
		b.revolve(STEEL, feed, v(0, 0, -1), new float[][] {{-0.18F, 0.06F}, {0.0F, 0.08F}, {0.12F, 0.14F}}, 12);
		b.box(-0.07F, -0.07F, 0.25F + FOCUS + 0.18F, 0.07F, 0.07F, 0.25F + FOCUS + 0.34F, BLACK);
		return b.build();
	}
}
