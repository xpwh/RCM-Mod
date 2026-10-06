package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.DISH;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.GRATING;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.RadarBlock;
import de.rcm.ballistic.block.RadarBlockEntity;
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
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Long-range air-surveillance radar station: a concrete pad, an equipment shelter with its air
 * conditioner and cable runs, a tapering lattice mast with a work platform, and on top the rotating
 * antenna - a curved, doubly-curved reflector of mesh panels on a back truss, the feed horn on its
 * boom at the focus and the IFF array along the top edge. It turns at about 6 rpm and twice as fast
 * while a threat is tracked, when the beacons also flash.
 */
public class RadarRenderer implements BlockEntityRenderer<RadarBlockEntity, RadarRenderer.State> {
	private static final float MAST_TOP = 9.0F;
	private static final float TURNTABLE = MAST_TOP + 0.6F;
	private static final float REFLECTOR_RADIUS = 3.6F;
	private static final int PANEL_COLUMNS = 12;
	private static final float HALF_ARC = 0.8F;

	private static final BoxMesh STATION = buildStation();
	private static final BoxMesh ANTENNA = buildAntenna();
	private static final BoxMesh BEACON = new BoxMesh.Builder()
		.box(-0.1F, 3.55F, -0.1F, 0.1F, 3.75F, 0.1F, RED_LAMP)
		.build();
	private static final BoxMesh ALERT_BEACON = new BoxMesh.Builder()
		.box(1.9F, 2.25F, -0.15F, 2.2F, 2.5F, 0.15F, RED_LAMP)
		.build();

	public RadarRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float angle;
		public boolean alert;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(RadarBlockEntity radar, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(radar, state, partialTick, cameraPos, overlay);
		float time = radar.getLevel() == null ? 0.0F : (radar.getLevel().getGameTime() % 240000L) + partialTick;
		state.time = time;
		state.alert = state.blockState.hasProperty(RadarBlock.POWERED) && state.blockState.getValue(RadarBlock.POWERED);
		// each station starts at its own bearing so neighbouring radars don't turn in lockstep
		float phase = (radar.getBlockPos().hashCode() & 1023) / 1023.0F * Mth.TWO_PI;
		state.angle = phase + time * (state.alert ? 0.11F : 0.052F);
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> STATION.emit(pose, consumer, light));
		if (state.alert && Mth.sin(state.time * 1.3F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> ALERT_BEACON.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}

		poseStack.translate(0.0F, TURNTABLE, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationY(state.angle));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> ANTENNA.emit(pose, consumer, light));
		boolean blink = state.alert ? Mth.sin(state.time * 1.3F) > 0.0F : Mth.sin(state.time * 0.2F) > 0.3F;
		if (blink) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BEACON.emit(pose, consumer, LightTexture.FULL_BRIGHT));
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

	private static BoxMesh buildStation() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// pad around the plinth block
		b.box(-3.0F, 0.0F, -3.0F, 3.0F, 0.1F, -0.5F, CONCRETE);
		b.box(-3.0F, 0.0F, 0.5F, 3.0F, 0.1F, 3.0F, CONCRETE);
		b.box(-3.0F, 0.0F, -0.5F, -0.5F, 0.1F, 0.5F, CONCRETE);
		b.box(0.5F, 0.0F, -0.5F, 3.0F, 0.1F, 0.5F, CONCRETE);
		// lattice mast standing on the plinth
		StructureKit.lattice(b, 0.0F, 0.0F, 0.9F, 0.42F, 1.0F, MAST_TOP, 4, 0.16F, STEEL, STEEL, STEEL);
		for (float[] c : new float[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
			b.box(c[0] * 0.9F - 0.18F, 0.1F, c[1] * 0.9F - 0.18F, c[0] * 0.9F + 0.18F, 1.0F, c[1] * 0.9F + 0.18F, CONCRETE_DARK); // footings
		}
		// ladder with a safety cage up one leg
		b.box(0.95F, 1.0F, -0.22F, 1.0F, MAST_TOP, -0.18F, STEEL);
		b.box(0.95F, 1.0F, 0.18F, 1.0F, MAST_TOP, 0.22F, STEEL);
		for (float y = 1.3F; y < MAST_TOP; y += 0.35F) {
			b.box(0.95F, y, -0.2F, 0.99F, y + 0.03F, 0.2F, STEEL);
		}
		// work platform with railings, rotator housing
		b.box(-0.9F, MAST_TOP, -0.9F, 0.9F, MAST_TOP + 0.08F, 0.9F, GRATING);
		for (float[] r : new float[][] {{-0.9F, -0.92F, 0.9F, -0.88F}, {-0.9F, 0.88F, 0.9F, 0.92F}, {-0.92F, -0.9F, -0.88F, 0.9F}, {0.88F, -0.9F, 0.92F, 0.9F}}) {
			b.box(r[0], MAST_TOP + 0.55F, r[1], r[2], MAST_TOP + 0.6F, r[3], YELLOW);
		}
		b.box(-0.45F, MAST_TOP + 0.08F, -0.45F, 0.45F, TURNTABLE, 0.45F, OLIVE);
		// waveguide / cable run down the mast into the shelter
		b.box(-0.1F, 1.0F, 0.42F, 0.05F, MAST_TOP, 0.52F, CABLE);
		b.beam(v(0.0F, 1.2F, 0.5F), v(1.4F, 1.6F, 0.5F), 0.12F, 0.12F, CABLE);
		// equipment shelter with door, air conditioner and a generator
		b.box(1.4F, 0.1F, -1.4F, 3.0F, 2.2F, 1.4F, OLIVE);
		b.box(1.35F, 2.2F, -1.45F, 3.05F, 2.3F, 1.45F, PANEL);
		b.box(1.37F, 0.1F, -0.45F, 1.4F, 1.8F, 0.35F, DOOR);
		b.box(3.0F, 0.9F, -0.8F, 3.35F, 1.7F, 0.2F, VENT);
		b.box(-2.9F, 0.1F, 1.6F, -1.5F, 1.1F, 2.6F, OLIVE);
		b.box(-2.92F, 0.3F, 1.8F, -2.9F, 0.9F, 2.4F, VENT);
		b.box(-2.6F, 1.1F, 1.9F, -2.45F, 1.6F, 2.05F, BLACK); // exhaust
		b.box(1.95F, 2.3F, -0.1F, 2.15F, 2.6F, 0.1F, STEEL); // alert beacon housing
		return b.build();
	}

	/** Rotating antenna in turntable space: +Z is the beam direction, y = 0 at the turntable. */
	private static BoxMesh buildAntenna() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// pedestal and elevation yoke
		b.box(-0.35F, 0.0F, -0.35F, 0.35F, 0.3F, 0.35F, STEEL);
		b.box(-0.5F, 0.3F, -0.9F, 0.5F, 0.45F, 0.3F, OLIVE);
		// reflector: columns of mesh panels on a circular arc (curved in plan) and three rows that
		// bow forward at the top and bottom (curved in elevation) - a doubly curved surface
		float[] rowY = {0.45F, 1.3F, 2.25F, 3.15F};
		float[] rowZ = {0.32F, 0.0F, 0.0F, 0.32F};
		float thick = 0.05F;
		for (int c = 0; c < PANEL_COLUMNS; c++) {
			float a0 = -HALF_ARC + 2 * HALF_ARC * c / PANEL_COLUMNS;
			float a1 = -HALF_ARC + 2 * HALF_ARC * (c + 1) / PANEL_COLUMNS;
			for (int r = 0; r < 3; r++) {
				Vector3f p00 = arc(a0, rowY[r], rowZ[r]);
				Vector3f p10 = arc(a1, rowY[r], rowZ[r]);
				Vector3f p11 = arc(a1, rowY[r + 1], rowZ[r + 1]);
				Vector3f p01 = arc(a0, rowY[r + 1], rowZ[r + 1]);
				Vector3f n = new Vector3f(p10).sub(p00).cross(new Vector3f(p01).sub(p00)).normalize().mul(thick * 0.5F);
				b.hexa(DISH,
					new Vector3f(p00).sub(n), new Vector3f(p10).sub(n), new Vector3f(p11).sub(n), new Vector3f(p01).sub(n),
					new Vector3f(p00).add(n), new Vector3f(p10).add(n), new Vector3f(p11).add(n), new Vector3f(p01).add(n));
			}
		}
		// back truss: horizontal chords along the rows and vertical ribs every other column
		for (int r = 0; r < rowY.length; r++) {
			for (int c = 0; c < PANEL_COLUMNS; c++) {
				float a0 = -HALF_ARC + 2 * HALF_ARC * c / PANEL_COLUMNS;
				float a1 = -HALF_ARC + 2 * HALF_ARC * (c + 1) / PANEL_COLUMNS;
				b.beam(arc(a0, rowY[r], rowZ[r] - 0.12F), arc(a1, rowY[r], rowZ[r] - 0.12F), 0.06F, 0.06F, STEEL);
			}
		}
		for (int c = 0; c <= PANEL_COLUMNS; c += 2) {
			float a = -HALF_ARC + 2 * HALF_ARC * c / PANEL_COLUMNS;
			for (int r = 0; r + 1 < rowY.length; r++) {
				b.beam(arc(a, rowY[r], rowZ[r] - 0.12F), arc(a, rowY[r + 1], rowZ[r + 1] - 0.12F), 0.07F, 0.07F, STEEL);
			}
			// struts back to the yoke
			b.beam(arc(a, 1.75F, -0.15F), v(0.0F, 0.4F, -0.7F), 0.06F, 0.06F, STEEL);
		}
		// feed horn on its boom at the focus, held by two side struts
		Vector3f focus = v(0.0F, 1.3F, 1.9F);
		b.beam(v(0.0F, 0.45F, 0.2F), focus, 0.12F, 0.12F, STEEL);
		b.beam(arc(-HALF_ARC * 0.7F, 0.6F, 0.25F), focus, 0.05F, 0.05F, STEEL);
		b.beam(arc(HALF_ARC * 0.7F, 0.6F, 0.25F), focus, 0.05F, 0.05F, STEEL);
		b.hexa(WHITE,
			v(focus.x - 0.12F, focus.y - 0.2F, focus.z), v(focus.x + 0.12F, focus.y - 0.2F, focus.z),
			v(focus.x + 0.12F, focus.y + 0.2F, focus.z), v(focus.x - 0.12F, focus.y + 0.2F, focus.z),
			v(focus.x - 0.22F, focus.y - 0.35F, focus.z - 0.45F), v(focus.x + 0.22F, focus.y - 0.35F, focus.z - 0.45F),
			v(focus.x + 0.22F, focus.y + 0.35F, focus.z - 0.45F), v(focus.x - 0.22F, focus.y + 0.35F, focus.z - 0.45F));
		// IFF interrogator array along the top edge, with its dipole row
		b.box(-2.7F, 3.2F, 0.1F, 2.7F, 3.5F, 0.35F, OLIVE);
		for (float x = -2.5F; x <= 2.51F; x += 0.5F) {
			b.box(x - 0.03F, 3.25F, 0.35F, x + 0.03F, 3.45F, 0.5F, WHITE);
		}
		b.box(-0.05F, 3.5F, 0.18F, 0.05F, 3.55F, 0.28F, STEEL);
		return b.build();
	}

	/** Point on the reflector: angle across the arc, height, forward bow at that height. */
	private static Vector3f arc(float angle, float y, float bow) {
		float r = REFLECTOR_RADIUS;
		return v(Mth.sin(angle) * r, y, r * (Mth.cos(angle) - 1.0F) * 0.6F + bow + 0.4F);
	}
}
