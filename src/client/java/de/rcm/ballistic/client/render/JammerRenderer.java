package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.ARRAY;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.DISH;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.GLASS;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.PURPLE_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.JammerBlockEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * Krasukha-4-style electronic-warfare vehicle: an 8x8 truck with its crew cab and a long equipment
 * module, and at the rear the big jamming antenna - a dish under its radome on a hydraulic lift.
 * Switched off, the antenna lies folded flat on the roof; switched on, the lift raises it upright
 * over a few seconds and it sweeps slowly across the threat sector while a violet beacon pulses.
 * A towed generator, its power cable and a pair of whip antennas complete the station.
 */
public class JammerRenderer implements BlockEntityRenderer<JammerBlockEntity, JammerRenderer.State> {
	private static final float ROOF = 3.05F;
	/** Hinge of the antenna lift at the rear of the roof. */
	private static final float HINGE_Z = -2.6F;
	private static final float LIFT = 1.1F;

	private static final BoxMesh TRUCK = buildTruck();
	private static final BoxMesh ANTENNA = buildAntenna();
	private static final BoxMesh BEACON = new BoxMesh.Builder().box(-0.12F, ROOF + 0.25F, 2.9F, 0.12F, ROOF + 0.5F, 3.14F, PURPLE_LAMP).build();
	private static final BoxMesh READY = new BoxMesh.Builder().box(1.26F, 2.2F, 0.6F, 1.28F, 2.35F, 0.75F, GREEN_LAMP).build();

	public JammerRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public boolean active;
		public float deploy;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(JammerBlockEntity jammer, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(jammer, state, partialTick, cameraPos, overlay);
		state.active = jammer.isActive();
		float d = Mth.lerp(partialTick, jammer.deployO, jammer.deploy);
		state.deploy = d * d * (3.0F - 2.0F * d);
		state.time = jammer.getLevel() == null ? 0.0F : (jammer.getLevel().getGameTime() % 24000L) + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> TRUCK.emit(pose, consumer, light));
		if (state.active && Mth.sin(state.time * 0.6F) > -0.2F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BEACON.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		if (state.deploy > 0.98F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> READY.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		// hydraulic lift: the column extends, the antenna swings from lying flat (facing up) to upright
		// (facing out the back), then sweeps the sector
		float d = state.deploy;
		float sweep = state.active ? d * 0.9F * Mth.sin(state.time * 0.015F) : 0.0F;
		float lift = LIFT * d;
		BoxMesh column = new BoxMesh.Builder()
			.box(-0.22F, ROOF, HINGE_Z - 0.22F, 0.22F, ROOF + 0.3F + lift, HINGE_Z + 0.22F, OLIVE_DARK)
			.box(-0.15F, ROOF + 0.3F, HINGE_Z - 0.15F, 0.15F, ROOF + 0.35F + lift, HINGE_Z + 0.15F, STEEL)
			.build();
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> column.emit(pose, consumer, light));
		poseStack.translate(0.0F, ROOF + 0.35F + lift, HINGE_Z);
		poseStack.mulPose(new Quaternionf().rotationY(Mth.PI + sweep));
		poseStack.mulPose(new Quaternionf().rotationX(-(1.0F - d) * Mth.HALF_PI));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> ANTENNA.emit(pose, consumer, light));
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

	/** The truck along +Z (cab at the front), the generator trailer behind it. */
	private static BoxMesh buildTruck() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// chassis rails, four axles of big tyres
		b.box(-0.9F, 0.75F, -3.9F, -0.6F, 1.1F, 3.9F, OLIVE_DARK);
		b.box(0.6F, 0.75F, -3.9F, 0.9F, 1.1F, 3.9F, OLIVE_DARK);
		for (float z : new float[] {-3.0F, -1.75F, 1.5F, 2.75F}) {
			for (float x : new float[] {-1.1F, 1.1F}) {
				b.cylinderX(x, 0.62F, z, 0.62F, 0.5F, 18, BLACK, STEEL);
			}
			b.box(-0.85F, 0.52F, z - 0.07F, 0.85F, 0.72F, z + 0.07F, STEEL);
		}
		// fenders along the sides
		b.box(-1.4F, 1.25F, -3.7F, -0.85F, 1.32F, -1.05F, OLIVE_DARK);
		b.box(0.85F, 1.25F, -3.7F, 1.4F, 1.32F, -1.05F, OLIVE_DARK);
		b.box(-1.4F, 1.25F, 0.8F, -0.85F, 1.32F, 3.45F, OLIVE_DARK);
		b.box(0.85F, 1.25F, 0.8F, 1.4F, 1.32F, 3.45F, OLIVE_DARK);
		// crew cab: angular front, windscreen, side doors, grille, headlights
		b.box(-1.25F, 1.1F, 2.3F, 1.25F, 2.9F, 3.7F, OLIVE);
		b.hexa(OLIVE,
			v(-1.25F, 1.1F, 3.7F), v(1.25F, 1.1F, 3.7F), v(1.25F, 2.9F, 3.7F), v(-1.25F, 2.9F, 3.7F),
			v(-1.2F, 1.1F, 4.15F), v(1.2F, 1.1F, 4.15F), v(1.2F, 2.3F, 3.95F), v(-1.2F, 2.3F, 3.95F));
		b.box(-1.05F, 2.32F, 3.86F, 1.05F, 2.82F, 3.9F, GLASS);
		b.box(-0.8F, 1.3F, 4.14F, 0.8F, 1.9F, 4.18F, VENT);
		b.box(-1.15F, 1.3F, 4.1F, -0.95F, 1.5F, 4.2F, GLASS);
		b.box(0.95F, 1.3F, 4.1F, 1.15F, 1.5F, 4.2F, GLASS);
		for (float x : new float[] {-1.27F, 1.25F}) {
			b.box(x, 1.4F, 2.5F, x + 0.02F, 2.7F, 3.3F, DOOR);
			b.box(x, 2.3F, 2.6F, x + 0.02F, 2.65F, 3.2F, GLASS);
		}
		b.box(-1.2F, 1.1F, 4.15F, 1.2F, 1.3F, 4.3F, STEEL); // bumper
		b.box(-1.25F, 2.9F, 2.3F, 1.25F, 2.95F, 3.7F, OLIVE_DARK);
		// equipment module: the jammer electronics, cooling and its access door and ladder
		b.box(-1.25F, 1.1F, -3.8F, 1.25F, ROOF, 2.2F, OLIVE);
		b.box(-1.27F, 1.3F, -0.6F, -1.25F, 2.8F, 0.4F, DOOR);
		b.box(-1.27F, 1.6F, 0.8F, -1.25F, 2.6F, 1.9F, VENT);
		b.box(1.25F, 1.6F, -3.4F, 1.27F, 2.8F, -1.2F, VENT);
		b.box(1.25F, 1.6F, -0.8F, 1.27F, 2.6F, 0.4F, PANEL);
		for (float y = 1.3F; y < ROOF; y += 0.3F) {
			b.box(-1.36F, y, 0.45F, -1.27F, y + 0.04F, 0.75F, STEEL); // ladder rungs
		}
		b.box(-1.25F, ROOF, -3.8F, 1.25F, ROOF + 0.06F, 2.2F, OLIVE_DARK);
		b.box(-1.25F, 1.1F, -3.85F, 1.25F, 1.25F, -3.8F, HAZARD);
		// cradle the antenna rests in when stowed, A/C units on the roof
		b.box(-1.0F, ROOF, -0.2F, -0.8F, ROOF + 0.45F, 0.0F, STEEL);
		b.box(0.8F, ROOF, -0.2F, 1.0F, ROOF + 0.45F, 0.0F, STEEL);
		b.box(0.2F, ROOF, 1.2F, 1.0F, ROOF + 0.4F, 2.0F, VENT);
		// whip antennas on the cab roof and the beacon post
		b.box(-1.05F, 2.95F, 3.4F, -1.01F, 5.2F, 3.44F, BLACK);
		b.box(1.01F, 2.95F, 3.4F, 1.05F, 5.2F, 3.44F, BLACK);
		b.box(-0.05F, 2.95F, 2.97F, 0.05F, ROOF + 0.25F, 3.07F, STEEL);
		// towed generator behind, with its cable to the truck
		b.box(-0.9F, 0.5F, -7.2F, 0.9F, 1.9F, -5.0F, OLIVE);
		b.box(-0.92F, 0.8F, -6.9F, -0.9F, 1.7F, -5.4F, VENT);
		b.box(0.5F, 1.9F, -6.6F, 0.65F, 2.4F, -6.45F, BLACK);
		b.cylinderX(-0.95F, 0.45F, -6.1F, 0.45F, 0.3F, 14, BLACK, STEEL);
		b.cylinderX(0.95F, 0.45F, -6.1F, 0.45F, 0.3F, 14, BLACK, STEEL);
		b.beam(v(0.0F, 0.7F, -5.0F), v(0.0F, 0.9F, -3.9F), 0.12F, 0.12F, STEEL); // drawbar
		b.beam(v(0.6F, 0.6F, -5.0F), v(1.0F, 0.05F, -4.4F), 0.08F, 0.08F, CABLE);
		b.beam(v(1.0F, 0.05F, -4.4F), v(1.27F, 1.4F, -3.6F), 0.08F, 0.08F, CABLE);
		// stabiliser jacks put down while the station works
		for (float[] j : new float[][] {{-1.4F, -3.5F}, {1.4F, -3.5F}, {-1.4F, 1.9F}, {1.4F, 1.9F}}) {
			b.box(j[0] - 0.08F, 0.08F, j[1] - 0.08F, j[0] + 0.08F, 1.1F, j[1] + 0.08F, STEEL);
			b.box(j[0] - 0.25F, 0.0F, j[1] - 0.25F, j[0] + 0.25F, 0.08F, j[1] + 0.25F, BLACK);
		}
		return b.build();
	}

	/**
	 * The antenna, hinge at the origin; deployed it faces along +Z (the renderer turns it to face out
	 * the back): a large dish under a radome with its feed, on a back frame with a sidelobe array.
	 */
	private static BoxMesh buildAntenna() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float cy = 1.9F;
		// back frame and trunnion
		b.box(-0.3F, 0.0F, -0.35F, 0.3F, 0.3F, 0.15F, OLIVE_DARK);
		b.beam(v(0.0F, 0.15F, -0.1F), v(0.0F, cy, -0.25F), 0.25F, 0.25F, OLIVE_DARK);
		b.beam(v(-1.3F, cy, -0.3F), v(1.3F, cy, -0.3F), 0.18F, 0.18F, OLIVE_DARK);
		b.beam(v(0.0F, cy - 1.3F, -0.3F), v(0.0F, cy + 1.3F, -0.3F), 0.18F, 0.18F, OLIVE_DARK);
		// the dish and its radome
		b.revolve(DISH, v(0, cy, -0.25F), v(0, 0, 1), new float[][] {{0.0F, 0.0F}, {0.08F, 0.5F}, {0.3F, 1.05F}, {0.6F, 1.55F}}, 26);
		b.revolve(OLIVE, v(0, cy, 0.35F), v(0, 0, 1), new float[][] {{0.0F, 1.6F}, {0.25F, 1.45F}, {0.45F, 1.0F}, {0.55F, 0.0F}}, 26);
		// feed horn on its struts (inside the radome) and the sidelobe-canceller array on the frame
		b.beam(v(0.0F, cy, -0.2F), v(0.0F, cy, 0.7F), 0.14F, 0.14F, STEEL);
		b.box(-1.7F, cy - 0.5F, -0.4F, -1.35F, cy + 0.5F, -0.25F, ARRAY);
		b.box(1.35F, cy - 0.5F, -0.4F, 1.7F, cy + 0.5F, -0.25F, ARRAY);
		return b.build();
	}
}
