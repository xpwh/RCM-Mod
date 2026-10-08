package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.GLASS;
import static de.rcm.ballistic.client.render.StructureKit.GRATING;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.LAMP;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RUST;
import static de.rcm.ballistic.client.render.StructureKit.SOOT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.LaunchPadBlock;
import de.rcm.ballistic.block.LaunchPadBlockEntity;
import de.rcm.ballistic.entity.MissileEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The launch complex around the launch table, modelled on a real orbital/ICBM test pad:
 * <ul>
 *   <li>a concrete apron, scorched around the table, with a walled flame trench running out to +X
 *       and a curved flame deflector and spillway at its end</li>
 *   <li>a red-and-white fixed service tower on the -X side with work platforms, stairs, an elevator
 *       that rides up and down, a lightning rod, and four umbilical swing arms - only the arms the
 *       missile is tall enough to reach are connected, and they swing back to the tower in the last
 *       seconds of the countdown</li>
 *   <li>hold-down clamps on the table that keep the missile down while the engines come up to
 *       thrust and fling open at release</li>
 *   <li>the sound-suppression water system: an elevated water tower, a deluge ring around the table
 *       and two "rainbird" water cannons on the deck</li>
 *   <li>propellant farm: a white liquid-oxygen sphere with a boil-off vent and a fuel sphere,
 *       piped to the tower</li>
 *   <li>two lightning masts with catenary wires strung over the pad, floodlight masts, cable trays,
 *       camera stands, the control bunker hatch</li>
 * </ul>
 * Status lights on the tower show green while a missile is ready and flash red through the
 * countdown and launch.
 */
public class LaunchPadRenderer implements BlockEntityRenderer<LaunchPadBlockEntity, LaunchPadRenderer.State> {
	private static final float TOWER_X = -3.2F;
	private static final float TOWER_TOP = 22.0F;
	private static final float TABLE = (float) LaunchPadBlock.TOP_HEIGHT;
	private static final float[] ARM_HEIGHTS = {4.6F, 8.6F, 12.6F, 16.6F};
	private static final float[] PLATFORMS = {4.4F, 8.4F, 12.4F, 16.4F, 20.4F, TOWER_TOP};
	private static final float[][] FLOODLIGHTS = {{-4.2F, -4.2F}, {4.2F, -4.2F}, {-4.2F, 4.2F}, {4.2F, 4.2F}};
	/** Lightning masts north and south of the pad. */
	private static final float MAST_HEIGHT = 30.0F;
	private static final float[][] LIGHTNING_MASTS = {{0.5F, -10.0F}, {0.5F, 10.0F}};
	private static final float[] WATER_TOWER = {-9.5F, 8.5F};
	private static final float[] LOX_SPHERE = {-9.5F, -8.5F};
	private static final float[] FUEL_SPHERE = {9.0F, -9.0F};

	/** Arms swing about a hinge on the tower face; built around that hinge, reaching along +X to the missile. */
	private static final float ARM_HINGE_X = TOWER_X + 0.65F;
	private static final float ARM_LENGTH = -1.05F - ARM_HINGE_X;
	/** Swing of a fully retracted arm, radians. */
	private static final float ARM_SWING = 1.35F;
	/** Hold-down clamp pivots on the table corners. */
	private static final float CLAMP_PIVOT = 0.42F;

	private static final BoxMesh COMPLEX = buildComplex();
	private static final BoxMesh GLOW = buildGlow();
	private static final BoxMesh BEACON = new BoxMesh.Builder()
		.box(TOWER_X - 0.12F, TOWER_TOP + 3.0F, -0.12F, TOWER_X + 0.12F, TOWER_TOP + 3.25F, 0.12F, RED_LAMP)
		.box(LIGHTNING_MASTS[0][0] - 0.15F, MAST_HEIGHT, LIGHTNING_MASTS[0][1] - 0.15F, LIGHTNING_MASTS[0][0] + 0.15F, MAST_HEIGHT + 0.3F, LIGHTNING_MASTS[0][1] + 0.15F, RED_LAMP)
		.box(LIGHTNING_MASTS[1][0] - 0.15F, MAST_HEIGHT, LIGHTNING_MASTS[1][1] - 0.15F, LIGHTNING_MASTS[1][0] + 0.15F, MAST_HEIGHT + 0.3F, LIGHTNING_MASTS[1][1] + 0.15F, RED_LAMP)
		.build();
	private static final BoxMesh[] ARMS = buildArms();
	private static final BoxMesh CLAMP = buildClamp();
	private static final BoxMesh ELEVATOR = buildElevator();
	private static final BoxMesh READY_LIGHT = statusLight(GREEN_LAMP);
	private static final BoxMesh COUNT_LIGHT = statusLight(RED_LAMP);

	public LaunchPadRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float time;
		/** Per arm: 0 = on the missile, 1 = swung back against the tower. */
		public final float[] retract = {1.0F, 1.0F, 1.0F, 1.0F};
		/** 0 = clamps closed on the missile, 1 = flung open. */
		public float clamps = 0.85F;
		/** 0 no missile, 1 missile ready, 2 counting down or launching. */
		public int status;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(LaunchPadBlockEntity pad, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(pad, state, partialTick, cameraPos, overlay);
		state.time = pad.getLevel() == null ? 0.0F : pad.getLevel().getGameTime() + partialTick;
		java.util.Arrays.fill(state.retract, 1.0F);
		state.clamps = 0.85F;
		state.status = 0;
		if (pad.getLevel() == null) {
			return;
		}
		for (MissileEntity missile : pad.getLevel().getEntitiesOfClass(MissileEntity.class, new AABB(pad.getBlockPos()).inflate(0.5, 3.0, 0.5))) {
			int s = missile.getState();
			float age = missile.clientStateAge + partialTick;
			float swing;
			if (s == MissileEntity.IDLE) {
				swing = 0.0F;
				state.clamps = 0.0F;
				state.status = 1;
			} else if (s == MissileEntity.COUNTDOWN) {
				// the arms let go and swing back between 5 and 2 seconds before ignition
				float remaining = missile.getMissileType().countdownTicks - age;
				float t = Mth.clamp((100.0F - remaining) / 60.0F, 0.0F, 1.0F);
				swing = t * t * (3.0F - 2.0F * t);
				state.clamps = 0.0F;
				state.status = 2;
			} else if (s == MissileEntity.IGNITION) {
				// engines run up to full thrust against the hold-downs, then they let go at once
				swing = 1.0F;
				state.clamps = Mth.clamp((age - (missile.getMissileType().ignitionTicks - 5)) / 3.0F, 0.0F, 1.0F);
				state.status = 2;
			} else {
				swing = 1.0F;
				state.clamps = 1.0F;
				state.status = 2;
			}
			float top = TABLE + missile.getMissileType().length;
			for (int i = 0; i < ARM_HEIGHTS.length; i++) {
				// an arm above the missile's nose has nothing to connect to and stays parked
				state.retract[i] = ARM_HEIGHTS[i] < top - 0.6F ? swing : 1.0F;
			}
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> COMPLEX.emit(pose, consumer, light));
		collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT));

		// umbilical swing arms
		for (int i = 0; i < ARMS.length; i++) {
			BoxMesh arm = ARMS[i];
			poseStack.pushPose();
			poseStack.translate(ARM_HINGE_X, 0.0F, 0.0F);
			poseStack.mulPose(new Quaternionf().rotationY(state.retract[i] * ARM_SWING));
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> arm.emit(pose, consumer, light));
			poseStack.popPose();
		}

		// hold-down clamps on the four table corners, flipping up and outwards at release
		for (int c = 0; c < 4; c++) {
			poseStack.pushPose();
			poseStack.mulPose(new Quaternionf().rotationY(c * Mth.HALF_PI));
			poseStack.translate(CLAMP_PIVOT, TABLE + 0.32F, CLAMP_PIVOT);
			poseStack.mulPose(new Quaternionf().rotationAxis(-state.clamps * 1.9F, new Vector3f(-1.0F, 0.0F, 1.0F).normalize()));
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CLAMP.emit(pose, consumer, light));
			poseStack.popPose();
		}

		// the tower elevator, riding up and down
		float lift = 0.3F + (TOWER_TOP - 2.0F) * (0.5F - 0.5F * Mth.cos(state.time * 0.006F));
		poseStack.pushPose();
		poseStack.translate(TOWER_X, lift, 0.0F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> ELEVATOR.emit(pose, consumer, light));
		poseStack.popPose();

		// status lights: steady green when ready, fast red flashing through countdown and launch
		if (state.status == 1) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> READY_LIGHT.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		} else if (state.status == 2 && Mth.sin(state.time * 0.8F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> COUNT_LIGHT.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		// aviation obstruction lights on the tower and the lightning masts: slow red blink
		if (Mth.sin(state.time * 0.2F) > 0.0F) {
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
		return 320;
	}

	// ------------------------------------------------------------------ static geometry

	private static BoxMesh buildComplex() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		apron(b);
		trench(b);
		tower(b);
		waterSystem(b);
		propellantFarm(b);
		lightningMasts(b);
		// control bunker entrance, equipment cabinet, camera stands
		b.box(2.6F, 0.0F, -4.2F, 4.2F, 0.6F, -2.6F, CONCRETE_DARK);
		b.box(3.0F, 0.6F, -3.8F, 3.8F, 0.66F, -3.0F, DOOR);
		b.box(3.6F, 0.6F, -2.95F, 3.75F, 1.4F, -2.8F, VENT);
		b.box(-4.3F, 0.08F, 2.4F, -3.5F, 1.6F, 3.2F, PANEL);
		b.box(-4.32F, 0.4F, 2.55F, -4.3F, 1.4F, 3.05F, DOOR);
		for (float[] cam : new float[][] {{6.0F, 3.5F}, {-6.0F, -3.0F}, {5.5F, -5.5F}}) {
			b.box(cam[0] - 0.05F, 0.0F, cam[1] - 0.05F, cam[0] + 0.05F, 1.6F, cam[1] + 0.05F, STEEL);
			b.box(cam[0] - 0.2F, 1.6F, cam[1] - 0.15F, cam[0] + 0.2F, 1.9F, cam[1] + 0.15F, BLACK);
			b.box(cam[0] - 0.08F, 1.65F, cam[1] - 0.16F, cam[0] + 0.08F, 1.85F, cam[1] - 0.15F, GLASS);
		}
		for (float[] m : FLOODLIGHTS) {
			StructureKit.floodlight(b, m[0], m[1], 6.0F, -m[0], -m[1]);
		}
		return b.build();
	}

	private static void apron(BoxMesh.Builder b) {
		// apron around the table (the table block itself fills the centre)
		b.box(-5.5F, 0.0F, -5.5F, 5.5F, 0.08F, -0.5F, CONCRETE);
		b.box(-5.5F, 0.0F, 0.5F, 5.5F, 0.08F, 5.5F, CONCRETE);
		b.box(-5.5F, 0.0F, -0.5F, -0.5F, 0.08F, 0.5F, CONCRETE);
		b.box(0.5F, 0.0F, -0.5F, 1.0F, 0.08F, 0.5F, CONCRETE);
		// expansion joints in the slab
		for (float c : new float[] {-3.0F, 3.0F}) {
			b.box(c - 0.03F, 0.08F, -5.5F, c + 0.03F, 0.085F, 5.5F, CONCRETE_DARK);
			b.box(-5.5F, 0.08F, c - 0.03F, 5.5F, 0.085F, c + 0.03F, CONCRETE_DARK);
		}
		// scorch marks around the table and down the trench
		b.box(-1.9F, 0.08F, -1.9F, 1.9F, 0.09F, 1.9F, SOOT);
		// yellow keep-out line
		b.box(-2.2F, 0.09F, -2.2F, 2.2F, 0.095F, -2.08F, YELLOW);
		b.box(-2.2F, 0.09F, 2.08F, 2.2F, 0.095F, 2.2F, YELLOW);
		b.box(-2.2F, 0.09F, -2.08F, -2.08F, 0.095F, 2.08F, YELLOW);
		// the table's base and the deluge ring around it with spray nozzles, fed from a riser
		b.box(-0.75F, 0.0F, -0.75F, 0.75F, 0.18F, 0.75F, CONCRETE_DARK);
		b.beam(v(-0.85F, 0.25F, -0.85F), v(0.85F, 0.25F, -0.85F), 0.1F, 0.1F, STEEL);
		b.beam(v(-0.85F, 0.25F, 0.85F), v(0.85F, 0.25F, 0.85F), 0.1F, 0.1F, STEEL);
		b.beam(v(-0.85F, 0.25F, -0.85F), v(-0.85F, 0.25F, 0.85F), 0.1F, 0.1F, STEEL);
		b.beam(v(0.85F, 0.25F, -0.85F), v(0.85F, 0.25F, 0.85F), 0.1F, 0.1F, STEEL);
		for (float c : new float[] {-0.85F, 0.85F}) {
			b.box(c - 0.06F, 0.3F, -0.06F, c + 0.06F, 0.38F, 0.06F, BLACK);
			b.box(-0.06F, 0.3F, c - 0.06F, 0.06F, 0.38F, c + 0.06F, BLACK);
		}
		// hold-down clamp housings on the table corners
		for (float[] c : new float[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
			float x = c[0] * CLAMP_PIVOT;
			float z = c[1] * CLAMP_PIVOT;
			b.box(x - 0.12F, TABLE, z - 0.12F, x + 0.12F, TABLE + 0.3F, z + 0.12F, STEEL);
		}
		// cable tray from the tower base to the table
		b.box(TOWER_X + 0.9F, 0.08F, -0.15F, -0.5F, 0.24F, 0.15F, CABLE);
		b.box(TOWER_X + 0.9F, 0.24F, -0.17F, -0.5F, 0.26F, 0.17F, STEEL);
	}

	/** Flame trench towards +X: walls, blackened floor, curved deflector and spillway. */
	private static void trench(BoxMesh.Builder b) {
		b.box(0.5F, 0.0F, -1.45F, 5.5F, 0.8F, -1.0F, CONCRETE_DARK);
		b.box(0.5F, 0.0F, 1.0F, 5.5F, 0.8F, 1.45F, CONCRETE_DARK);
		b.box(1.0F, 0.0F, -1.0F, 5.5F, 0.06F, 1.0F, SOOT);
		// curved deflector: three facets bending the exhaust up and out
		float[][] curve = {{3.0F, 0.06F}, {4.0F, 0.25F}, {4.8F, 0.75F}, {5.5F, 1.6F}};
		for (int i = 0; i + 1 < curve.length; i++) {
			float x0 = curve[i][0];
			float y0 = curve[i][1];
			float x1 = curve[i + 1][0];
			float y1 = curve[i + 1][1];
			b.hexa(SOOT,
				v(x0, y0, -1.0F), v(x0, y0, 1.0F), v(x0, y0 + 0.08F, 1.0F), v(x0, y0 + 0.08F, -1.0F),
				v(x1, y1, -1.0F), v(x1, y1, 1.0F), v(x1, y1 + 0.08F, 1.0F), v(x1, y1 + 0.08F, -1.0F));
		}
		b.box(5.5F, 0.0F, -1.45F, 5.8F, 1.7F, 1.45F, CONCRETE_DARK);
		// spillway apron beyond, scorched
		b.box(5.8F, 0.0F, -2.5F, 9.0F, 0.06F, 2.5F, SOOT);
		b.box(0.6F, 0.8F, -1.5F, 5.4F, 0.88F, -0.95F, HAZARD);
		b.box(0.6F, 0.8F, 0.95F, 5.4F, 0.88F, 1.5F, HAZARD);
	}

	private static void tower(BoxMesh.Builder b) {
		StructureKit.lattice(b, TOWER_X, 0.0F, 0.9F, 0.65F, 0.08F, TOWER_TOP, 11, 0.15F, RED, WHITE, STEEL);
		b.box(TOWER_X - 1.1F, 0.0F, -1.1F, TOWER_X + 1.1F, 0.3F, 1.1F, CONCRETE_DARK); // footing
		for (float y : PLATFORMS) {
			float h = 0.9F - 0.25F * (y / TOWER_TOP);
			b.box(TOWER_X - h - 0.15F, y, -h - 0.15F, TOWER_X + h + 0.15F, y + 0.08F, h + 0.15F, GRATING);
			// railings
			b.box(TOWER_X - h - 0.15F, y + 0.08F, -h - 0.17F, TOWER_X + h + 0.15F, y + 0.12F, -h - 0.13F, YELLOW);
			b.box(TOWER_X - h - 0.15F, y + 0.55F, -h - 0.17F, TOWER_X + h + 0.15F, y + 0.6F, -h - 0.13F, YELLOW);
			b.box(TOWER_X - h - 0.15F, y + 0.55F, h + 0.13F, TOWER_X + h + 0.15F, y + 0.6F, h + 0.17F, YELLOW);
		}
		// stairs zig-zagging up one face
		for (int i = 0; i < 10; i++) {
			float y0 = 0.3F + i * 2.15F;
			float z0 = i % 2 == 0 ? -0.6F : 0.6F;
			b.beam(v(TOWER_X - 1.1F, y0, z0), v(TOWER_X - 1.1F, y0 + 2.15F, -z0), 0.35F, 0.06F, GRATING);
		}
		// elevator guide rails
		b.box(TOWER_X - 0.06F, 0.3F, -0.5F, TOWER_X + 0.0F, TOWER_TOP, -0.44F, STEEL);
		b.box(TOWER_X - 0.06F, 0.3F, 0.44F, TOWER_X + 0.0F, TOWER_TOP, 0.5F, STEEL);
		// hinges of the umbilical swing arms (the arms themselves move, see buildArms)
		for (float y : ARM_HEIGHTS) {
			b.box(ARM_HINGE_X - 0.1F, y - 0.6F, -0.1F, ARM_HINGE_X + 0.1F, y + 0.2F, 0.1F, STEEL);
		}
		// hammerhead crane on top and the lightning rod
		b.beam(v(TOWER_X - 1.2F, TOWER_TOP + 0.6F, 0.0F), v(TOWER_X + 2.8F, TOWER_TOP + 0.6F, 0.0F), 0.3F, 0.3F, RED);
		b.box(TOWER_X - 0.3F, TOWER_TOP, -0.3F, TOWER_X + 0.3F, TOWER_TOP + 0.45F, 0.3F, PANEL);
		b.box(TOWER_X - 1.4F, TOWER_TOP + 0.3F, -0.3F, TOWER_X - 1.0F, TOWER_TOP + 0.9F, 0.3F, CONCRETE_DARK); // counterweight
		b.box(TOWER_X + 2.6F, TOWER_TOP - 1.5F, -0.02F, TOWER_X + 2.64F, TOWER_TOP + 0.45F, 0.02F, CABLE); // hook line
		b.box(TOWER_X + 2.5F, TOWER_TOP - 1.7F, -0.1F, TOWER_X + 2.74F, TOWER_TOP - 1.5F, 0.1F, YELLOW);
		b.box(TOWER_X - 0.05F, TOWER_TOP, -0.05F, TOWER_X + 0.05F, TOWER_TOP + 3.0F, 0.05F, STEEL);
		// propellant and water lines climbing the tower
		b.box(TOWER_X + 0.7F, 0.3F, 0.55F, TOWER_X + 0.85F, ARM_HEIGHTS[3], 0.7F, WHITE);
		b.box(TOWER_X + 0.7F, 0.3F, -0.7F, TOWER_X + 0.85F, ARM_HEIGHTS[3], -0.55F, STEEL);
	}

	/** Water tower, piping and the two rainbird water cannons on the deck. */
	private static void waterSystem(BoxMesh.Builder b) {
		float x = WATER_TOWER[0];
		float z = WATER_TOWER[1];
		float legTop = 14.0F;
		for (float[] c : new float[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
			b.beam(v(x + c[0] * 2.0F, 0.0F, z + c[1] * 2.0F), v(x + c[0] * 1.3F, legTop, z + c[1] * 1.3F), 0.25F, 0.25F, STEEL);
			b.box(x + c[0] * 2.0F - 0.3F, 0.0F, z + c[1] * 2.0F - 0.3F, x + c[0] * 2.0F + 0.3F, 0.3F, z + c[1] * 2.0F + 0.3F, CONCRETE_DARK);
		}
		for (float y : new float[] {4.0F, 9.0F}) {
			float h = 2.0F - 0.7F * (y / legTop);
			b.beam(v(x - h, y, z - h), v(x + h, y, z - h), 0.12F, 0.12F, STEEL);
			b.beam(v(x - h, y, z + h), v(x + h, y, z + h), 0.12F, 0.12F, STEEL);
			b.beam(v(x - h, y, z - h), v(x - h, y, z + h), 0.12F, 0.12F, STEEL);
			b.beam(v(x + h, y, z - h), v(x + h, y, z + h), 0.12F, 0.12F, STEEL);
		}
		// the tank with its conical roof, a catwalk round it and the ladder
		b.revolve(WHITE, v(x, legTop, z), v(0, 1, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 2.3F}, {3.6F, 2.3F}, {4.6F, 0.4F}, {4.8F, 0.0F}}, 20);
		b.revolve(GRATING, v(x, legTop - 0.05F, z), v(0, 1, 0), new float[][] {{0.0F, 2.8F}, {0.08F, 2.8F}}, 20);
		b.box(x + 2.3F, 0.0F, z - 0.2F, x + 2.36F, legTop, z - 0.15F, STEEL);
		b.box(x + 2.3F, 0.0F, z + 0.15F, x + 2.36F, legTop, z + 0.2F, STEEL);
		// riser down and the main to the pad
		b.box(x - 0.2F, 0.0F, z - 0.2F, x + 0.2F, legTop, z + 0.2F, STEEL);
		b.beam(v(x, 0.3F, z), v(-0.85F, 0.3F, 0.85F), 0.3F, 0.3F, STEEL);
		// rainbirds: water cannons on the deck aimed at the table
		for (float side : new float[] {-1.0F, 1.0F}) {
			float rx = 2.4F;
			float rz = side * 3.3F;
			b.box(rx - 0.25F, 0.0F, rz - 0.25F, rx + 0.25F, 0.7F, rz + 0.25F, STEEL);
			b.beam(v(rx, 0.75F, rz), v(rx - 0.7F, 1.1F, rz - side * 0.9F), 0.18F, 0.18F, YELLOW);
			b.beam(v(rx, 0.3F, rz), v(rx, 0.3F, side * 1.45F), 0.16F, 0.16F, STEEL);
		}
	}

	/** Liquid oxygen and fuel spheres on their legs, piped to the tower base. */
	private static void propellantFarm(BoxMesh.Builder b) {
		sphereTank(b, LOX_SPHERE[0], LOX_SPHERE[1], 2.4F, WHITE);
		sphereTank(b, FUEL_SPHERE[0], FUEL_SPHERE[1], 2.0F, STEEL);
		// boil-off vent stack on the oxygen sphere
		b.box(LOX_SPHERE[0] - 0.08F, 6.0F, LOX_SPHERE[1] - 0.08F, LOX_SPHERE[0] + 0.08F, 7.4F, LOX_SPHERE[1] + 0.08F, STEEL);
		// transfer lines (insulated oxygen line in white)
		b.beam(v(LOX_SPHERE[0], 0.35F, LOX_SPHERE[1]), v(TOWER_X + 0.78F, 0.35F, -0.62F), 0.22F, 0.22F, WHITE);
		b.beam(v(FUEL_SPHERE[0], 0.35F, FUEL_SPHERE[1]), v(TOWER_X + 0.78F, 0.35F, -5.5F), 0.2F, 0.2F, STEEL);
		b.beam(v(TOWER_X + 0.78F, 0.35F, -5.5F), v(TOWER_X + 0.78F, 0.35F, -0.62F), 0.2F, 0.2F, STEEL);
		// pipe supports
		for (float t = 0.15F; t < 1.0F; t += 0.2F) {
			float px = Mth.lerp(t, LOX_SPHERE[0], TOWER_X + 0.78F);
			float pz = Mth.lerp(t, LOX_SPHERE[1], -0.62F);
			b.box(px - 0.15F, 0.0F, pz - 0.15F, px + 0.15F, 0.24F, pz + 0.15F, CONCRETE_DARK);
		}
	}

	private static void sphereTank(BoxMesh.Builder b, float x, float z, float r, int patch) {
		float cy = r + 1.2F;
		float[][] profile = new float[11][];
		for (int i = 0; i <= 10; i++) {
			float t = -r + 2.0F * r * i / 10.0F;
			profile[i] = new float[] {t + r, (float) Math.sqrt(Math.max(0.0F, r * r - t * t))};
		}
		b.revolve(patch, v(x, cy - r, z), v(0, 1, 0), profile, 18);
		// legs and an equator ring girder
		for (int i = 0; i < 6; i++) {
			float a = i * Mth.TWO_PI / 6;
			float lx = x + Mth.cos(a) * r * 0.95F;
			float lz = z + Mth.sin(a) * r * 0.95F;
			b.box(lx - 0.1F, 0.0F, lz - 0.1F, lx + 0.1F, cy, lz + 0.1F, STEEL);
			b.box(lx - 0.22F, 0.0F, lz - 0.22F, lx + 0.22F, 0.2F, lz + 0.22F, CONCRETE_DARK);
		}
		b.revolve(STEEL, v(x, cy - 0.06F, z), v(0, 1, 0), new float[][] {{0.0F, r + 0.06F}, {0.12F, r + 0.06F}}, 18);
		// ladder up the side to the top platform
		b.box(x + r * 0.7F, 0.0F, z - 0.2F, x + r * 0.7F + 0.05F, cy + r * 0.7F, z - 0.15F, STEEL);
		b.box(x - 0.5F, cy + r - 0.05F, z - 0.5F, x + 0.5F, cy + r + 0.02F, z + 0.5F, GRATING);
	}

	/** Lightning masts with catenary wires strung over the pad and down to ground anchors. */
	private static void lightningMasts(BoxMesh.Builder b) {
		for (float[] m : LIGHTNING_MASTS) {
			StructureKit.lattice(b, m[0], m[1], 0.5F, 0.25F, 0.0F, MAST_HEIGHT, 10, 0.1F, RED, WHITE, STEEL);
			b.box(m[0] - 0.8F, 0.0F, m[1] - 0.8F, m[0] + 0.8F, 0.3F, m[1] + 0.8F, CONCRETE_DARK);
			b.box(m[0] - 0.04F, MAST_HEIGHT, m[1] - 0.04F, m[0] + 0.04F, MAST_HEIGHT + 1.5F, m[1] + 0.04F, STEEL);
			// guy wires to ground anchors
			for (float dx : new float[] {-9.0F, 9.0F}) {
				float az = m[1] + Math.signum(m[1]) * 5.0F;
				catenary(b, v(m[0], MAST_HEIGHT - 0.2F, m[1]), v(m[0] + dx, 0.2F, az), 0.6F);
				b.box(m[0] + dx - 0.3F, 0.0F, az - 0.3F, m[0] + dx + 0.3F, 0.35F, az + 0.3F, CONCRETE_DARK);
			}
		}
		// the catenary over the pad, well clear of the tower
		catenary(b, v(LIGHTNING_MASTS[0][0], MAST_HEIGHT - 0.2F, LIGHTNING_MASTS[0][1]), v(LIGHTNING_MASTS[1][0], MAST_HEIGHT - 0.2F, LIGHTNING_MASTS[1][1]), 3.0F);
	}

	/** A sagging wire from a to b. */
	private static void catenary(BoxMesh.Builder b, Vector3f a, Vector3f end, float sag) {
		int n = 14;
		Vector3f prev = a;
		for (int i = 1; i <= n; i++) {
			float t = (float) i / n;
			Vector3f p = new Vector3f(a).lerp(end, t).sub(0, sag * 4.0F * t * (1.0F - t), 0);
			b.beam(prev, p, 0.05F, 0.05F, BLACK);
			prev = p;
		}
	}

	/** One umbilical swing arm per height, hinge at the origin, reaching along +X to the missile. */
	private static BoxMesh[] buildArms() {
		BoxMesh[] arms = new BoxMesh[ARM_HEIGHTS.length];
		for (int i = 0; i < arms.length; i++) {
			BoxMesh.Builder b = new BoxMesh.Builder();
			float l = ARM_LENGTH;
			float y = ARM_HEIGHTS[i];
			b.beam(v(0.0F, y, -0.25F), v(l, y, -0.25F), 0.12F, 0.12F, RED);
			b.beam(v(0.0F, y, 0.25F), v(l, y, 0.25F), 0.12F, 0.12F, RED);
			b.beam(v(0.0F, y - 0.5F, 0.0F), v(l, y, 0.0F), 0.08F, 0.08F, STEEL); // brace
			for (float x = 0.3F; x < l; x += 0.45F) {
				b.beam(v(x, y, -0.25F), v(x, y, 0.25F), 0.06F, 0.06F, STEEL); // cross members
			}
			b.box(0.0F, y + 0.06F, -0.3F, l, y + 0.1F, 0.3F, GRATING); // walkway
			b.box(l - 0.1F, y - 0.25F, -0.32F, l + 0.1F, y + 0.15F, 0.32F, PANEL); // umbilical plate
			// umbilical lines sagging between the plate and the tower
			b.beam(v(l, y - 0.2F, 0.18F), v(l * 0.55F, y - 1.1F, 0.38F), 0.07F, 0.07F, CABLE);
			b.beam(v(l * 0.55F, y - 1.1F, 0.38F), v(0.05F, y - 0.4F, 0.38F), 0.07F, 0.07F, CABLE);
			if (i == 0) {
				// the lowest arm carries the insulated oxygen fill line
				b.beam(v(l, y - 0.1F, -0.15F), v(l * 0.5F, y - 0.9F, -0.38F), 0.12F, 0.12F, WHITE);
				b.beam(v(l * 0.5F, y - 0.9F, -0.38F), v(0.05F, y - 0.3F, -0.38F), 0.12F, 0.12F, WHITE);
			}
			arms[i] = b.build();
		}
		return arms;
	}

	/** A hold-down clamp, pivot at the origin, its jaw reaching in towards the missile's base. */
	private static BoxMesh buildClamp() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.beam(v(0.0F, 0.0F, 0.0F), v(-0.24F, 0.12F, -0.24F), 0.1F, 0.1F, YELLOW);
		b.box(-0.3F, 0.06F, -0.3F, -0.18F, 0.2F, -0.18F, BLACK); // jaw
		b.box(-0.05F, -0.05F, -0.05F, 0.05F, 0.05F, 0.05F, RUST); // pin
		return b.build();
	}

	/** Elevator cab, built around the tower axis, floor at y = 0. */
	private static BoxMesh buildElevator() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.38F, 0.0F, -0.4F, 0.38F, 0.06F, 0.4F, STEEL);
		b.box(-0.38F, 1.1F, -0.4F, 0.38F, 1.16F, 0.4F, STEEL);
		b.box(-0.38F, 0.06F, -0.4F, -0.34F, 1.1F, 0.4F, PANEL);
		b.box(0.34F, 0.06F, -0.4F, 0.38F, 1.1F, 0.4F, PANEL);
		b.box(-0.34F, 0.5F, -0.4F, 0.34F, 1.0F, -0.36F, GLASS);
		b.box(-0.03F, 1.16F, -0.03F, 0.03F, 1.8F, 0.03F, CABLE);
		return b.build();
	}

	/** Status lamps on the tower face looking at the table, one per lower platform. */
	private static BoxMesh statusLight(int patch) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float y : new float[] {2.2F, 6.4F, 10.4F, 14.4F}) {
			b.box(ARM_HINGE_X + 0.02F, y, 0.55F, ARM_HINGE_X + 0.2F, y + 0.22F, 0.75F, patch);
		}
		return b.build();
	}

	private static BoxMesh buildGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] m : FLOODLIGHTS) {
			StructureKit.floodlightGlow(b, m[0], m[1], 6.0F, -m[0], -m[1]);
		}
		// work lights under every tower platform
		for (float y : PLATFORMS) {
			b.box(TOWER_X + 0.5F, y - 0.1F, -0.1F, TOWER_X + 0.6F, y - 0.02F, 0.1F, LAMP);
		}
		return b.build();
	}
}
