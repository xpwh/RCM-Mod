package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.GRATING;
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
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The launch complex around the launch table: a 9x9 concrete apron scorched around the table, a
 * walled flame trench with a deflector on the +X side, a red-and-white service tower with work
 * platforms and umbilical swing arms on the -X side, water deluge pipes, cable trays, a control
 * bunker hatch and four floodlight masts. The table itself is the block's own model.
 */
public class LaunchPadRenderer implements BlockEntityRenderer<LaunchPadBlockEntity, LaunchPadRenderer.State> {
	private static final float TOWER_X = -3.2F;
	private static final float TOWER_TOP = 15.0F;
	private static final float[] ARM_HEIGHTS = {4.6F, 8.6F, 12.6F};
	private static final float[][] MASTS = {{-4.2F, -4.2F}, {4.2F, -4.2F}, {-4.2F, 4.2F}, {4.2F, 4.2F}};

	private static final BoxMesh COMPLEX = buildComplex();
	private static final BoxMesh GLOW = buildGlow();
	private static final BoxMesh BEACON = new BoxMesh.Builder().box(TOWER_X - 0.12F, TOWER_TOP + 2.0F, -0.12F, TOWER_X + 0.12F, TOWER_TOP + 2.25F, 0.12F, RED_LAMP).build();

	public LaunchPadRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(LaunchPadBlockEntity pad, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(pad, state, partialTick, cameraPos, overlay);
		state.time = pad.getLevel() == null ? 0.0F : pad.getLevel().getGameTime() + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> COMPLEX.emit(pose, consumer, light));
		collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT));
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
		// umbilical swing arms reaching to the missile, with hanging umbilicals
		for (float y : ARM_HEIGHTS) {
			b.beam(v(TOWER_X + 0.65F, y, -0.25F), v(-1.05F, y, -0.25F), 0.12F, 0.12F, RED);
			b.beam(v(TOWER_X + 0.65F, y, 0.25F), v(-1.05F, y, 0.25F), 0.12F, 0.12F, RED);
			b.beam(v(TOWER_X + 0.65F, y - 0.5F, 0.0F), v(-1.05F, y, 0.0F), 0.08F, 0.08F, STEEL);
			b.box(-1.15F, y - 0.25F, -0.32F, -0.95F, y + 0.15F, 0.32F, PANEL); // umbilical plate
			b.beam(v(-1.05F, y - 0.2F, 0.18F), v(-1.6F, y - 1.5F, 0.4F), 0.07F, 0.07F, CABLE);
			b.beam(v(-1.6F, y - 1.5F, 0.4F), v(TOWER_X + 0.7F, y - 0.4F, 0.4F), 0.07F, 0.07F, CABLE);
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

	private static BoxMesh buildGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] m : MASTS) {
			StructureKit.floodlightGlow(b, m[0], m[1], 6.0F, -m[0], -m[1]);
		}
		return b.build();
	}
}
