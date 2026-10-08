package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.GRATING;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.SOOT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
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
import org.jspecify.annotations.Nullable;

/**
 * The launch complex around the launch table: a 9x9 concrete apron scorched around the table, a
 * walled flame trench with a deflector on the +X side, a red-and-white service tower with work
 * platforms and umbilical swing arms on the -X side, water deluge pipes, cable trays, a control
 * bunker hatch and four floodlight masts. The table itself is the block's own model.
 * <p>
 * The umbilical swing arms stay connected to a missile standing on the table and swing back to the
 * tower in the last seconds of the countdown; status lights on the tower show green while a missile
 * is ready and flash red through the countdown and launch.
 */
public class LaunchPadRenderer implements BlockEntityRenderer<LaunchPadBlockEntity, LaunchPadRenderer.State> {
	private static final float TOWER_X = -3.2F;
	private static final float TOWER_TOP = 15.0F;
	private static final float[] ARM_HEIGHTS = {4.6F, 8.6F, 12.6F};
	private static final float[][] MASTS = {{-4.2F, -4.2F}, {4.2F, -4.2F}, {-4.2F, 4.2F}, {4.2F, 4.2F}};

	private static final BoxMesh COMPLEX = buildComplex();
	private static final BoxMesh GLOW = buildGlow();
	private static final BoxMesh BEACON = new BoxMesh.Builder().box(TOWER_X - 0.12F, TOWER_TOP + 2.0F, -0.12F, TOWER_X + 0.12F, TOWER_TOP + 2.25F, 0.12F, RED_LAMP).build();
	/** Arms swing about a hinge on the tower face; built around that hinge, reaching along +X to the missile. */
	private static final float ARM_HINGE_X = TOWER_X + 0.65F;
	private static final float ARM_LENGTH = -1.05F - ARM_HINGE_X;
	private static final BoxMesh ARMS = buildArms();
	private static final BoxMesh READY_LIGHT = statusLight(GREEN_LAMP);
	private static final BoxMesh COUNT_LIGHT = statusLight(RED_LAMP);
	/** Swing of a fully retracted arm, radians. */
	private static final float ARM_SWING = 1.35F;

	public LaunchPadRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float time;
		/** 0 = arms on the missile, 1 = swung back against the tower. */
		public float retract = 1.0F;
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
		state.retract = 1.0F;
		state.status = 0;
		if (pad.getLevel() != null) {
			for (MissileEntity missile : pad.getLevel().getEntitiesOfClass(MissileEntity.class, new AABB(pad.getBlockPos()).inflate(0.5, 3.0, 0.5))) {
				int s = missile.getState();
				if (s == MissileEntity.IDLE) {
					state.retract = 0.0F;
					state.status = 1;
				} else if (s == MissileEntity.COUNTDOWN) {
					// the arms let go and swing back between 5 and 2 seconds before ignition
					float remaining = missile.getMissileType().countdownTicks - (missile.clientStateAge + partialTick);
					float t = Mth.clamp((100.0F - remaining) / 60.0F, 0.0F, 1.0F);
					state.retract = t * t * (3.0F - 2.0F * t);
					state.status = 2;
				} else {
					state.status = 2;
				}
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
		poseStack.pushPose();
		poseStack.translate(ARM_HINGE_X, 0.0F, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationY(state.retract * ARM_SWING));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> ARMS.emit(pose, consumer, light));
		poseStack.popPose();
		// status lights: steady green when ready, fast red flashing through countdown and launch
		if (state.status == 1) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> READY_LIGHT.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		} else if (state.status == 2 && Mth.sin(state.time * 0.8F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> COUNT_LIGHT.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		// aviation obstruction light on the tower: slow red blink
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
		return 256;
	}

	private static BoxMesh buildComplex() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// apron around the table (the table block itself fills the centre)
		b.box(-4.5F, 0.0F, -4.5F, 4.5F, 0.08F, -0.5F, CONCRETE);
		b.box(-4.5F, 0.0F, 0.5F, 4.5F, 0.08F, 4.5F, CONCRETE);
		b.box(-4.5F, 0.0F, -0.5F, -0.5F, 0.08F, 0.5F, CONCRETE);
		b.box(0.5F, 0.0F, -0.5F, 4.5F, 0.08F, 0.5F, CONCRETE);
		// expansion joints in the slab
		for (float c : new float[] {-2.5F, 2.5F}) {
			b.box(c - 0.03F, 0.08F, -4.5F, c + 0.03F, 0.085F, 4.5F, CONCRETE_DARK);
			b.box(-4.5F, 0.08F, c - 0.03F, 4.5F, 0.085F, c + 0.03F, CONCRETE_DARK);
		}
		// scorch marks around the table and down the trench
		b.box(-1.7F, 0.08F, -1.7F, 1.7F, 0.09F, 1.7F, SOOT);
		// yellow keep-out line
		b.box(-2.0F, 0.09F, -2.0F, 2.0F, 0.095F, -1.88F, YELLOW);
		b.box(-2.0F, 0.09F, 1.88F, 2.0F, 0.095F, 2.0F, YELLOW);
		b.box(-2.0F, 0.09F, -1.88F, -1.88F, 0.095F, 1.88F, YELLOW);

		// flame trench towards +X: walls, blackened floor, curved deflector at the end
		b.box(0.5F, 0.0F, -1.35F, 4.5F, 0.7F, -1.0F, CONCRETE_DARK);
		b.box(0.5F, 0.0F, 1.0F, 4.5F, 0.7F, 1.35F, CONCRETE_DARK);
		b.box(0.5F, 0.08F, -1.0F, 4.5F, 0.1F, 1.0F, SOOT);
		b.hexa(SOOT,
			v(3.0F, 0.1F, -1.0F), v(3.0F, 0.1F, 1.0F), v(3.0F, 0.14F, 1.0F), v(3.0F, 0.14F, -1.0F),
			v(4.5F, 0.0F, -1.0F), v(4.5F, 0.0F, 1.0F), v(4.5F, 1.4F, 1.0F), v(4.5F, 1.4F, -1.0F));
		b.box(0.6F, 0.7F, -1.4F, 4.4F, 0.78F, -0.95F, HAZARD);
		b.box(0.6F, 0.7F, 0.95F, 4.4F, 0.78F, 1.4F, HAZARD);

		// water deluge ring around the table with spray nozzles, fed from a riser
		b.beam(v(-0.85F, 0.25F, -0.85F), v(0.85F, 0.25F, -0.85F), 0.1F, 0.1F, STEEL);
		b.beam(v(-0.85F, 0.25F, 0.85F), v(0.85F, 0.25F, 0.85F), 0.1F, 0.1F, STEEL);
		b.beam(v(-0.85F, 0.25F, -0.85F), v(-0.85F, 0.25F, 0.85F), 0.1F, 0.1F, STEEL);
		b.beam(v(0.85F, 0.25F, -0.85F), v(0.85F, 0.25F, 0.85F), 0.1F, 0.1F, STEEL);
		for (float c : new float[] {-0.85F, 0.85F}) {
			b.box(c - 0.06F, 0.3F, -0.06F, c + 0.06F, 0.38F, 0.06F, BLACK);
			b.box(-0.06F, 0.3F, c - 0.06F, 0.06F, 0.38F, c + 0.06F, BLACK);
		}
		b.beam(v(-0.85F, 0.25F, -0.85F), v(-2.2F, 0.25F, -2.6F), 0.12F, 0.12F, STEEL);

		// service tower
		StructureKit.lattice(b, TOWER_X, 0.0F, 0.85F, 0.65F, 0.08F, TOWER_TOP, 8, 0.15F, RED, WHITE, STEEL);
		b.box(TOWER_X - 1.0F, 0.0F, -1.0F, TOWER_X + 1.0F, 0.3F, 1.0F, CONCRETE_DARK); // footing
		for (float y : new float[] {4.4F, 8.4F, 12.4F, TOWER_TOP}) {
			float h = 0.85F - 0.2F * (y / TOWER_TOP);
			b.box(TOWER_X - h - 0.15F, y, -h - 0.15F, TOWER_X + h + 0.15F, y + 0.08F, h + 0.15F, GRATING);
			// railings
			b.box(TOWER_X - h - 0.15F, y + 0.08F, -h - 0.17F, TOWER_X + h + 0.15F, y + 0.12F, -h - 0.13F, YELLOW);
			b.box(TOWER_X - h - 0.15F, y + 0.55F, -h - 0.17F, TOWER_X + h + 0.15F, y + 0.6F, -h - 0.13F, YELLOW);
			b.box(TOWER_X - h - 0.15F, y + 0.55F, h + 0.13F, TOWER_X + h + 0.15F, y + 0.6F, h + 0.17F, YELLOW);
		}
		// stairs zig-zagging up one face
		for (int i = 0; i < 7; i++) {
			float y0 = 0.3F + i * 2.0F;
			float z0 = i % 2 == 0 ? -0.6F : 0.6F;
			b.beam(v(TOWER_X - 1.05F, y0, z0), v(TOWER_X - 1.05F, y0 + 2.0F, -z0), 0.35F, 0.06F, GRATING);
		}
		// hinges of the umbilical swing arms (the arms themselves move, see buildArms)
		for (float y : ARM_HEIGHTS) {
			b.box(ARM_HINGE_X - 0.1F, y - 0.6F, -0.1F, ARM_HINGE_X + 0.1F, y + 0.2F, 0.1F, STEEL);
		}
		// lightning rod mast on top
		b.box(TOWER_X - 0.05F, TOWER_TOP, -0.05F, TOWER_X + 0.05F, TOWER_TOP + 2.0F, 0.05F, STEEL);
		// cable tray from the tower base to the table
		b.box(TOWER_X + 0.9F, 0.08F, -0.15F, -0.5F, 0.24F, 0.15F, CABLE);
		b.box(TOWER_X + 0.9F, 0.24F, -0.17F, -0.5F, 0.26F, 0.17F, STEEL);

		// control bunker entrance and an equipment cabinet
		b.box(2.6F, 0.0F, -4.2F, 4.2F, 0.6F, -2.6F, CONCRETE_DARK);
		b.box(3.0F, 0.6F, -3.8F, 3.8F, 0.66F, -3.0F, DOOR);
		b.box(3.6F, 0.6F, -2.95F, 3.75F, 1.4F, -2.8F, VENT);
		b.box(-4.3F, 0.08F, 2.4F, -3.5F, 1.6F, 3.2F, PANEL);
		b.box(-4.32F, 0.4F, 2.55F, -4.3F, 1.4F, 3.05F, DOOR);

		for (float[] m : MASTS) {
			StructureKit.floodlight(b, m[0], m[1], 6.0F, -m[0], -m[1]);
		}
		return b.build();
	}

	/** The three umbilical swing arms, hinge at the origin, reaching along +X to the missile. */
	private static BoxMesh buildArms() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float l = ARM_LENGTH;
		for (float y : ARM_HEIGHTS) {
			b.beam(v(0.0F, y, -0.25F), v(l, y, -0.25F), 0.12F, 0.12F, RED);
			b.beam(v(0.0F, y, 0.25F), v(l, y, 0.25F), 0.12F, 0.12F, RED);
			b.beam(v(0.0F, y - 0.5F, 0.0F), v(l, y, 0.0F), 0.08F, 0.08F, STEEL); // brace
			for (float x = 0.3F; x < l; x += 0.45F) {
				b.beam(v(x, y, -0.25F), v(x, y, 0.25F), 0.06F, 0.06F, STEEL); // cross members
			}
			b.box(l - 0.1F, y - 0.25F, -0.32F, l + 0.1F, y + 0.15F, 0.32F, PANEL); // umbilical plate
			// umbilical lines sagging between the plate and the tower
			b.beam(v(l, y - 0.2F, 0.18F), v(l * 0.55F, y - 1.1F, 0.38F), 0.07F, 0.07F, CABLE);
			b.beam(v(l * 0.55F, y - 1.1F, 0.38F), v(0.05F, y - 0.4F, 0.38F), 0.07F, 0.07F, CABLE);
		}
		return b.build();
	}

	/** Status lamps on the tower face looking at the table, one per platform. */
	private static BoxMesh statusLight(int patch) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float y : new float[] {2.2F, 6.4F, 10.4F}) {
			b.box(ARM_HINGE_X + 0.02F, y, 0.55F, ARM_HINGE_X + 0.2F, y + 0.22F, 0.75F, patch);
		}
		return b.build();
	}

	private static BoxMesh buildGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] m : MASTS) {
			StructureKit.floodlightGlow(b, m[0], m[1], 6.0F, -m[0], -m[1]);
		}
		return b.build();
	}
}
