package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.ARRAY;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.DISH;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.SOOT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.AirDefenseBlock;
import de.rcm.ballistic.block.AirDefenseBlockEntity;
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
 * Patriot-style fire unit around the battery block: an M860-type semitrailer with outriggers and its
 * electric power unit, the trainable launcher with four PAC canisters of two cells each that turns
 * towards the threat and elevates to 38 degrees to fire. Every cell is closed by a frangible cover;
 * when its round fires the cover is blown off and tumbles away, leaving the open, scorched mouth. Plus the
 * phased-array engagement radar on its own trailer and an antenna mast group. The trailer is laid out
 * along the block's facing.
 */
public class AirDefenseRenderer implements BlockEntityRenderer<AirDefenseBlockEntity, AirDefenseRenderer.State> {
	private static final float PIVOT = (float) AirDefenseBlockEntity.PIVOT_HEIGHT;
	private static final float LENGTH = (float) AirDefenseBlockEntity.CANISTER_LENGTH;
	/** Canister cross-sections in the launcher frame: {x0, x1, y0, y1}. Declared before the meshes. */
	private static final float[][] CANISTERS = {
		{-1.0F, -0.04F, 0.05F, 0.68F}, {0.04F, 1.0F, 0.05F, 0.68F},
		{-1.0F, -0.04F, 0.74F, 1.37F}, {0.04F, 1.0F, 0.74F, 1.37F}
	};

	private static final BoxMesh SITE = buildSite();
	private static final BoxMesh TURNTABLE = buildTurntable();
	private static final BoxMesh PACK = buildPack();
	/** Per cell: [0] the intact cover, [1] the open, scorched mouth, [2] the cover centred on the origin (flying). */
	private static final BoxMesh[][] CELLS = buildCells();

	public AirDefenseRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float facingYaw;
		public float yaw;
		public float elevation;
		public int ammo;
		/** Per cell: ticks the blown-off cover has been flying, or -1. */
		public final float[] pop = new float[8];
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(AirDefenseBlockEntity battery, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(battery, state, partialTick, cameraPos, overlay);
		Direction facing = state.blockState.hasProperty(AirDefenseBlock.FACING) ? state.blockState.getValue(AirDefenseBlock.FACING) : Direction.NORTH;
		state.facingYaw = (float) Mth.atan2(facing.getStepX(), facing.getStepZ());
		state.yaw = Mth.rotLerpRad(partialTick, battery.clientYawO, battery.clientYaw);
		state.elevation = Mth.lerp(partialTick, battery.clientElevationO, battery.clientElevation);
		state.ammo = battery.getAmmo();
		for (int i = 0; i < 8; i++) {
			state.pop[i] = battery.popAge[i] < 0 ? -1.0F : battery.popAge[i] + partialTick;
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);

		poseStack.pushPose();
		poseStack.mulPose(new Quaternionf().rotationY(state.facingYaw));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> SITE.emit(pose, consumer, light));
		poseStack.popPose();

		// launcher: turntable trains in azimuth, the canister pack elevates about its rear pivot
		poseStack.mulPose(new Quaternionf().rotationY(state.yaw));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> TURNTABLE.emit(pose, consumer, light));
		Quaternionf tilt = new Quaternionf().rotationX(-state.elevation);
		Vector3f ramTop = new Vector3f(0.0F, -0.05F, 2.1F).rotate(tilt).add(0.0F, PIVOT, 0.0F);
		BoxMesh rams = new BoxMesh.Builder()
			.beam(v(-0.55F, 1.55F, 1.0F), new Vector3f(ramTop).add(-0.55F, 0, 0), 0.2F, 0.2F, STEEL)
			.beam(v(0.55F, 1.55F, 1.0F), new Vector3f(ramTop).add(0.55F, 0, 0), 0.2F, 0.2F, STEEL)
			.beam(v(-0.55F, 1.55F, 1.0F), v(-0.55F, 1.55F, 1.0F).lerp(new Vector3f(ramTop).add(-0.55F, 0, 0), 0.5F), 0.28F, 0.28F, OLIVE_DARK)
			.beam(v(0.55F, 1.55F, 1.0F), v(0.55F, 1.55F, 1.0F).lerp(new Vector3f(ramTop).add(0.55F, 0, 0), 0.5F), 0.28F, 0.28F, OLIVE_DARK)
			.build();
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> rams.emit(pose, consumer, light));

		poseStack.translate(0.0F, PIVOT, 0.0F);
		poseStack.mulPose(tilt);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> PACK.emit(pose, consumer, light));
		for (int cell = 0; cell < 8; cell++) {
			BoxMesh face = fired(cell, state.ammo) ? CELLS[cell][1] : CELLS[cell][0];
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> face.emit(pose, consumer, light));
			float t = state.pop[cell];
			if (t >= 0.0F && t < 45.0F) {
				// the blown-off cover: thrown forward by the motor blast, tumbling, falling (gravity in launcher space)
				float[] c = AirDefenseBlockEntity.cellCentre(cell);
				float ahead = 0.75F * (1.0F - (float) Math.pow(0.9, t)) / 0.1F;
				float fall = 0.5F * 0.045F * t * t;
				float sideways = (cell % 2 == 0 ? -1.0F : 1.0F) * 0.04F * t;
				BoxMesh flying = CELLS[cell][2];
				poseStack.pushPose();
				poseStack.translate(c[0] + sideways, c[1] - fall * Mth.cos(state.elevation), LENGTH + 0.03F + ahead - fall * Mth.sin(state.elevation));
				poseStack.mulPose(new Quaternionf().rotationXYZ(t * 0.45F * (cell % 2 == 0 ? 1.0F : -1.0F), t * 0.17F, t * 0.08F));
				collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> flying.emit(pose, consumer, light));
				poseStack.popPose();
			}
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

	/** Static part in trailer space: +Z is the trailer's front, origin at the block's bottom centre. */
	private static BoxMesh buildSite() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// ---- launcher semitrailer
		b.box(-1.0F, 0.6F, -4.2F, -0.7F, 1.0F, 4.0F, OLIVE_DARK); // main frame rails
		b.box(0.7F, 0.6F, -4.2F, 1.0F, 1.0F, 4.0F, OLIVE_DARK);
		for (float z = -3.8F; z < 4.0F; z += 1.2F) {
			b.box(-1.25F, 0.75F, z, 1.25F, 0.95F, z + 0.15F, OLIVE_DARK); // cross members
		}
		b.box(-1.25F, 1.0F, -4.2F, 1.25F, 1.12F, -0.5F, OLIVE); // deck plates around the pedestal
		b.box(-1.25F, 1.0F, 0.5F, 1.25F, 1.12F, 4.0F, OLIVE);
		b.box(-1.25F, 1.0F, -0.5F, -0.5F, 1.12F, 0.5F, OLIVE);
		b.box(0.5F, 1.0F, -0.5F, 1.25F, 1.12F, 0.5F, OLIVE);
		b.box(-1.27F, 1.12F, -4.2F, 1.27F, 1.2F, -4.1F, HAZARD); // rear bumper markings
		// tandem rear axles with fenders
		for (float z : new float[] {-3.4F, -2.3F}) {
			for (float x : new float[] {-1.05F, 1.05F}) {
				b.cylinderX(x, 0.55F, z, 0.55F, 0.42F, 16, BLACK, STEEL);
			}
			b.box(-0.8F, 0.45F, z - 0.06F, 0.8F, 0.65F, z + 0.06F, STEEL); // axle
		}
		b.box(-1.32F, 1.12F, -3.95F, -0.82F, 1.18F, -1.75F, OLIVE_DARK);
		b.box(0.82F, 1.12F, -3.95F, 1.32F, 1.18F, -1.75F, OLIVE_DARK);
		// front landing gear and kingpin plate
		for (float x : new float[] {-0.8F, 0.8F}) {
			b.box(x - 0.08F, 0.1F, 3.0F, x + 0.08F, 0.75F, 3.16F, STEEL);
			b.box(x - 0.2F, 0.0F, 2.9F, x + 0.2F, 0.1F, 3.26F, BLACK);
		}
		b.box(-0.5F, 0.55F, 3.6F, 0.5F, 0.6F, 4.0F, STEEL);
		// four outriggers with jacks and ground pads
		for (float[] o : new float[][] {{-1.0F, -3.9F, -2.1F}, {1.0F, -3.9F, 2.1F}, {-1.0F, 3.4F, -2.1F}, {1.0F, 3.4F, 2.1F}}) {
			b.beam(v(o[0], 0.85F, o[1]), v(o[2], 0.85F, o[1]), 0.2F, 0.2F, OLIVE_DARK);
			b.box(o[2] - 0.08F, 0.08F, o[1] - 0.08F, o[2] + 0.08F, 0.85F, o[1] + 0.08F, STEEL);
			b.box(o[2] - 0.3F, 0.0F, o[1] - 0.3F, o[2] + 0.3F, 0.08F, o[1] + 0.3F, BLACK);
		}
		// electric power unit at the rear: generator housing, louvres, exhaust, cable to the launcher
		b.box(-1.2F, 1.12F, -4.15F, 1.2F, 2.2F, -3.0F, OLIVE);
		b.box(-1.22F, 1.3F, -4.0F, -1.2F, 2.0F, -3.2F, VENT);
		b.box(1.2F, 1.3F, -4.0F, 1.22F, 2.0F, -3.2F, VENT);
		b.box(0.7F, 2.2F, -3.9F, 0.85F, 2.6F, -3.75F, BLACK);
		b.box(-0.4F, 1.3F, -4.17F, 0.4F, 1.95F, -4.15F, PANEL);
		b.box(-0.1F, 1.12F, -3.0F, 0.1F, 1.2F, -0.6F, CABLE);
		// launcher data link antenna on a short mast
		b.box(1.05F, 1.12F, -2.9F, 1.15F, 3.4F, -2.8F, STEEL);
		b.box(0.9F, 3.4F, -3.0F, 1.3F, 3.45F, -2.7F, STEEL);

		// ---- engagement radar on its own trailer beside the launcher, facing the threat sector
		float rx = -5.0F;
		b.box(rx - 1.2F, 0.6F, -3.2F, rx + 1.2F, 1.0F, 2.0F, OLIVE_DARK);
		for (float z : new float[] {-2.6F, -1.6F}) {
			for (float x : new float[] {rx - 1.05F, rx + 1.05F}) {
				b.cylinderX(x, 0.5F, z, 0.5F, 0.38F, 16, BLACK, STEEL);
			}
		}
		b.box(rx - 1.25F, 1.0F, -3.3F, rx + 1.25F, 3.0F, 0.6F, OLIVE); // shelter
		b.box(rx - 1.27F, 1.2F, -2.6F, rx - 1.25F, 2.6F, -1.8F, DOOR);
		b.box(rx + 1.25F, 1.6F, -2.8F, rx + 1.27F, 2.6F, -0.8F, VENT);
		b.box(rx - 1.3F, 3.0F, -3.35F, rx + 1.3F, 3.1F, 0.65F, PANEL);
		// the main phased-array face, tilted back, with its frame, the IFF array above it and the
		// small track-via-missile array beside it
		Vector3f n = v(0.0F, 0.42F, 0.91F); // array normal (tilted 25 degrees up)
		Vector3f up = v(0.0F, 0.91F, -0.42F);
		Vector3f c = v(rx, 2.45F, 1.25F);
		face(b, ARRAY, c, n, up, 1.25F, 1.15F, 0.12F);
		face(b, OLIVE_DARK, new Vector3f(c).sub(new Vector3f(n).mul(0.14F)), n, up, 1.4F, 1.3F, 0.18F);
		face(b, ARRAY, new Vector3f(c).add(new Vector3f(up).mul(1.45F)), n, up, 1.1F, 0.14F, 0.1F);
		face(b, ARRAY, new Vector3f(c).add(v(1.6F, -0.7F, -0.2F)), n, up, 0.25F, 0.25F, 0.08F);
		face(b, ARRAY, new Vector3f(c).add(v(-1.6F, -0.7F, -0.2F)), n, up, 0.25F, 0.25F, 0.08F);
		b.beam(v(rx - 1.0F, 1.0F, 0.6F), new Vector3f(c).add(v(-1.0F, -1.0F, -0.2F)), 0.14F, 0.14F, STEEL); // array struts
		b.beam(v(rx + 1.0F, 1.0F, 0.6F), new Vector3f(c).add(v(1.0F, -1.0F, -0.2F)), 0.14F, 0.14F, STEEL);
		b.box(rx - 0.05F, 3.1F, -3.0F, rx + 0.05F, 4.0F, -2.9F, STEEL);
		b.box(rx - 0.12F, 4.0F, -3.07F, rx + 0.12F, 4.18F, -2.83F, RED_LAMP);
		b.box(rx + 1.2F, 0.08F, -0.2F, -1.25F, 0.16F, 0.0F, CABLE); // data cable to the launcher

		// ---- antenna mast group: two microwave relay dishes on a tall mast
		float mx = 3.6F;
		float mz = -4.6F;
		b.box(mx - 0.08F, 0.0F, mz - 0.08F, mx + 0.08F, 7.0F, mz + 0.08F, STEEL);
		b.box(mx - 0.6F, 0.0F, mz - 0.6F, mx + 0.6F, 0.12F, mz + 0.6F, CONCRETE_DARK);
		for (float[] guy : new float[][] {{-2.5F, 0.0F}, {2.5F, 0.0F}, {0.0F, 2.5F}}) {
			b.beam(v(mx, 5.0F, mz), v(mx + guy[0], 0.0F, mz + guy[1]), 0.03F, 0.03F, CABLE);
		}
		for (float[] d : new float[][] {{6.4F, 0.6F}, {5.6F, -0.6F}}) {
			Vector3f dc = v(mx + d[1] * 0.5F, d[0], mz + 0.4F);
			face(b, DISH, dc, v(d[1] * 0.4F, 0.0F, 0.92F).normalize(), v(0, 1, 0), 0.42F, 0.42F, 0.1F);
			b.beam(v(mx, d[0], mz), dc, 0.06F, 0.06F, STEEL);
		}
		b.box(mx - 0.1F, 7.0F, mz - 0.1F, mx + 0.1F, 7.15F, mz + 0.1F, RED_LAMP);
		return b.build();
	}

	/** Flat rectangular plate centred on {@code c} with normal {@code n}, half sizes and thickness. */
	private static void face(BoxMesh.Builder b, int patch, Vector3f c, Vector3f n, Vector3f up, float halfW, float halfH, float thick) {
		Vector3f right = new Vector3f(up).cross(n).normalize().mul(halfW);
		Vector3f u = new Vector3f(up).normalize().mul(halfH);
		Vector3f t = new Vector3f(n).normalize().mul(thick * 0.5F);
		Vector3f[] q = {
			new Vector3f(c).sub(right).sub(u), new Vector3f(c).add(right).sub(u), new Vector3f(c).add(right).add(u), new Vector3f(c).sub(right).add(u)
		};
		b.hexa(patch,
			new Vector3f(q[0]).sub(t), new Vector3f(q[1]).sub(t), new Vector3f(q[2]).sub(t), new Vector3f(q[3]).sub(t),
			new Vector3f(q[0]).add(t), new Vector3f(q[1]).add(t), new Vector3f(q[2]).add(t), new Vector3f(q[3]).add(t));
	}

	/** Turntable and trunnion frame in launcher-yaw space. */
	private static BoxMesh buildTurntable() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.95F, 1.12F, -0.95F, 0.95F, 1.4F, 0.95F, STEEL); // slew ring
		b.box(-1.1F, 1.4F, -0.7F, 1.1F, 1.7F, 1.2F, OLIVE_DARK); // launcher base
		for (float x : new float[] {-1.1F, 0.95F}) {
			b.box(x, 1.4F, -0.45F, x + 0.15F, PIVOT + 0.2F, 0.35F, OLIVE); // trunnion plates
		}
		b.box(-1.15F, PIVOT - 0.12F, -0.12F, 1.15F, PIVOT + 0.12F, 0.12F, STEEL); // pivot shaft
		b.box(-0.6F, 1.7F, -0.7F, 0.6F, 1.95F, -0.3F, PANEL); // launcher control electronics
		return b.build();
	}

	/** Four-canister pack in launcher space: +Z along the canisters from the rear pivot, +Y up. */
	private static BoxMesh buildPack() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] cs : CANISTERS) {
			b.box(cs[0], cs[2], 0.0F, cs[1], cs[3], LENGTH, OLIVE);
		}
		// steel bands and lifting points around the pack
		for (float z : new float[] {0.25F, 2.6F, 4.95F}) {
			b.box(-1.05F, 0.0F, z, 1.05F, 1.42F, z + 0.18F, OLIVE_DARK);
		}
		b.box(-1.05F, 1.42F, 0.4F, -0.85F, 1.52F, 0.7F, STEEL);
		b.box(0.85F, 1.42F, 0.4F, 1.05F, 1.52F, 0.7F, STEEL);
		b.box(-1.05F, 1.42F, 4.7F, -0.85F, 1.52F, 5.0F, STEEL);
		b.box(0.85F, 1.42F, 4.7F, 1.05F, 1.52F, 5.0F, STEEL);
		// stencils and the "do not stand behind" stripe at the rear
		b.box(-0.9F, 1.37F, 1.4F, -0.2F, 1.38F, 2.2F, YELLOW);
		b.box(0.2F, 1.37F, 1.4F, 0.9F, 1.38F, 2.2F, WHITE);
		b.box(-1.0F, 0.0F, -0.05F, 1.0F, 1.42F, 0.0F, HAZARD);
		// rear covers (blow-out plugs) and the web between each canister's two cells
		for (float[] cs : CANISTERS) {
			b.box(cs[0] + 0.05F, cs[2] + 0.05F, -0.08F, cs[1] - 0.05F, cs[3] - 0.05F, -0.05F, BLACK);
			float xm = (cs[0] + cs[1]) * 0.5F;
			b.box(xm - 0.03F, cs[2], LENGTH - 0.02F, xm + 0.03F, cs[3], LENGTH + 0.04F, OLIVE_DARK);
		}
		return b.build();
	}

	/** Whether the round of a cell has been fired, with {@code ammo} rounds left. */
	private static boolean fired(int cell, int ammo) {
		for (int a = AirDefenseBlockEntity.MAGAZINE; a > ammo; a--) {
			if (AirDefenseBlockEntity.coverIndex(a) == cell) {
				return true;
			}
		}
		return false;
	}

	/** The eight cells' covers, open mouths and flying covers (see {@link #CELLS}). */
	private static BoxMesh[][] buildCells() {
		BoxMesh[][] cells = new BoxMesh[8][];
		float hw = 0.2F;
		float hh = 0.28F;
		for (int cell = 0; cell < 8; cell++) {
			float[] c = AirDefenseBlockEntity.cellCentre(cell);
			BoxMesh cover = new BoxMesh.Builder()
				.box(c[0] - hw, c[1] - hh, LENGTH, c[0] + hw, c[1] + hh, LENGTH + 0.05F, DISH)
				.box(c[0] - hw * 0.7F, c[1] - 0.02F, LENGTH + 0.05F, c[0] + hw * 0.7F, c[1] + 0.02F, LENGTH + 0.06F, BLACK) // scored burst lines
				.box(c[0] - 0.02F, c[1] - hh * 0.7F, LENGTH + 0.05F, c[0] + 0.02F, c[1] + hh * 0.7F, LENGTH + 0.06F, BLACK)
				.build();
			BoxMesh mouth = new BoxMesh.Builder()
				.box(c[0] - hw, c[1] - hh, LENGTH - 0.35F, c[0] + hw, c[1] + hh, LENGTH - 0.3F, BLACK)
				.box(c[0] - hw, c[1] - hh, LENGTH - 0.3F, c[0] - hw + 0.03F, c[1] + hh, LENGTH + 0.02F, SOOT)
				.box(c[0] + hw - 0.03F, c[1] - hh, LENGTH - 0.3F, c[0] + hw, c[1] + hh, LENGTH + 0.02F, SOOT)
				.box(c[0] - hw, c[1] - hh, LENGTH - 0.3F, c[0] + hw, c[1] - hh + 0.03F, LENGTH + 0.02F, SOOT)
				.box(c[0] - hw, c[1] + hh - 0.03F, LENGTH - 0.3F, c[0] + hw, c[1] + hh, LENGTH + 0.02F, SOOT)
				.build();
			BoxMesh flying = new BoxMesh.Builder()
				.box(-hw, -hh, -0.025F, hw, hh, 0.025F, DISH)
				.box(-hw * 0.7F, -0.02F, 0.025F, hw * 0.7F, 0.02F, 0.035F, BLACK)
				.build();
			cells[cell] = new BoxMesh[] {cover, mouth, flying};
		}
		return cells;
	}
}
