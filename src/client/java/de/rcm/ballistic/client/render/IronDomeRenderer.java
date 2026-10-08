package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.ARRAY;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.SOOT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.DefenseSiteBlock;
import de.rcm.ballistic.block.IronDomeBlockEntity;
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
 * Iron Dome battery: the Tamir launcher - a 20-round box launcher raised to a fixed 55 degrees on a
 * towed trailer with outriggers and its generator - next to the EL/M-2084 multi-mission radar, a
 * flat array on a mast that turns slowly. Tube covers show which cells are still loaded. Trailer
 * space: +Z is the front, origin at the block's bottom centre.
 */
public class IronDomeRenderer implements BlockEntityRenderer<IronDomeBlockEntity, IronDomeRenderer.State> {
	/** Pack geometry, shared by the meshes: pivot and elevation. Declared before the meshes. */
	private static final float PIVOT_Y = IronDomeBlockEntity.PIVOT_Y;
	private static final float PIVOT_Z = IronDomeBlockEntity.PIVOT_Z;
	private static final float ELEVATION = IronDomeBlockEntity.ELEVATION;
	private static final float PACK_LENGTH = IronDomeBlockEntity.PACK_LENGTH;
	private static final float RADAR_X = 3.6F;

	private static final BoxMesh TRAILER = buildTrailer();
	private static final BoxMesh PACK = buildPack();
	private static final BoxMesh[] CAPS = buildCaps();
	private static final BoxMesh RADAR_ARRAY = buildRadarArray();
	private static final BoxMesh LAMP = new BoxMesh.Builder().box(-0.06F, 0.0F, -0.06F, 0.06F, 0.12F, 0.06F, RED_LAMP).build();

	public IronDomeRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float facingYaw;
		public int ammo;
		public final float[] pop = new float[20];
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(IronDomeBlockEntity dome, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(dome, state, partialTick, cameraPos, overlay);
		Direction facing = state.blockState.hasProperty(DefenseSiteBlock.FACING) ? state.blockState.getValue(DefenseSiteBlock.FACING) : Direction.NORTH;
		state.facingYaw = (float) Mth.atan2(facing.getStepX(), facing.getStepZ());
		state.ammo = dome.getAmmo();
		for (int i = 0; i < 20; i++) {
			state.pop[i] = dome.popAge[i] < 0 ? -1.0F : dome.popAge[i] + partialTick;
		}
		state.time = dome.getLevel() == null ? 0.0F : (dome.getLevel().getGameTime() % 24000L) + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.mulPose(new Quaternionf().rotationY(state.facingYaw));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> TRAILER.emit(pose, consumer, light));

		// the radar array turns slowly on its mast; its beacon blinks
		poseStack.pushPose();
		poseStack.translate(RADAR_X, 3.4F, 0.4F);
		poseStack.mulPose(new Quaternionf().rotationY(state.time * 0.04F));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> RADAR_ARRAY.emit(pose, consumer, light));
		poseStack.popPose();
		if (Mth.sin(state.time * 0.3F) > 0.6F) {
			poseStack.pushPose();
			poseStack.translate(RADAR_X, 4.75F, 0.4F);
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> LAMP.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			poseStack.popPose();
		}

		// launcher pack, raised to its fixed launch angle
		poseStack.translate(0.0F, PIVOT_Y, PIVOT_Z);
		poseStack.mulPose(new Quaternionf().rotationX(-ELEVATION));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> PACK.emit(pose, consumer, light));
		// cells are fired from the top row down; a fired cell shows its open black tube
		for (int i = 0; i < 20; i++) {
			BoxMesh cap = i < 20 - state.ammo ? CAPS[i + 20] : CAPS[i];
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> cap.emit(pose, consumer, light));
			float t = state.pop[i];
			if (t >= 0.0F && t < 45.0F) {
				// the blown-off cover, thrown out by the motor and falling away (gravity in pack space)
				float[] c = IronDomeBlockEntity.cellCentre(i);
				float ahead = 0.6F * (1.0F - (float) Math.pow(0.9, t)) / 0.1F;
				float fall = 0.5F * 0.045F * t * t;
				BoxMesh flying = CAPS[40];
				poseStack.pushPose();
				poseStack.translate(c[0] + (i % 2 == 0 ? -0.03F : 0.03F) * t, c[1] - fall * Mth.cos(ELEVATION), PACK_LENGTH + 0.07F + ahead - fall * Mth.sin(ELEVATION));
				poseStack.mulPose(new Quaternionf().rotationXYZ(t * 0.5F, t * 0.21F, t * 0.13F));
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

	private static BoxMesh buildTrailer() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// ---- launcher trailer: frame, deck, wheels, drawbar, outriggers with pads
		b.box(-1.0F, 0.55F, -3.2F, -0.75F, 0.85F, 2.6F, OLIVE_DARK);
		b.box(0.75F, 0.55F, -3.2F, 1.0F, 0.85F, 2.6F, OLIVE_DARK);
		b.box(-1.2F, 0.85F, -3.2F, 1.2F, 0.98F, 2.6F, OLIVE);
		for (float z : new float[] {-2.7F, -1.5F}) {
			b.cylinderX(-1.15F, 0.48F, z, 0.48F, 0.36F, 16, BLACK, STEEL);
			b.cylinderX(1.15F, 0.48F, z, 0.48F, 0.36F, 16, BLACK, STEEL);
		}
		b.box(-1.35F, 0.95F, -3.2F, -1.2F, 1.05F, -1.0F, OLIVE_DARK); // mudguards
		b.box(1.2F, 0.95F, -3.2F, 1.35F, 1.05F, -1.0F, OLIVE_DARK);
		b.beam(v(-0.6F, 0.7F, 2.6F), v(0.0F, 0.6F, 4.0F), 0.14F, 0.14F, OLIVE_DARK); // A-frame drawbar
		b.beam(v(0.6F, 0.7F, 2.6F), v(0.0F, 0.6F, 4.0F), 0.14F, 0.14F, OLIVE_DARK);
		b.box(-0.1F, 0.4F, 3.9F, 0.1F, 0.7F, 4.15F, STEEL); // lunette
		for (float[] o : new float[][] {{-1.0F, 2.3F, -1.0F}, {1.0F, 2.3F, 1.0F}, {-1.0F, -3.0F, -1.0F}, {1.0F, -3.0F, 1.0F}}) {
			float x = o[0];
			float z = o[1];
			float out = o[2];
			b.beam(v(x, 0.75F, z), v(x + out * 1.2F, 0.75F, z), 0.16F, 0.16F, OLIVE_DARK);
			b.beam(v(x + out * 1.2F, 0.8F, z), v(x + out * 1.2F, 0.08F, z), 0.12F, 0.12F, STEEL);
			b.box(x + out * 1.2F - 0.28F, 0.0F, z - 0.28F, x + out * 1.2F + 0.28F, 0.08F, z + 0.28F, CONCRETE_DARK);
		}
		// generator / fire-control unit at the front of the deck, cable to the radar
		b.box(-0.9F, 0.98F, 1.3F, 0.9F, 2.05F, 2.5F, OLIVE);
		b.box(-0.92F, 1.2F, 1.5F, -0.9F, 1.8F, 2.3F, VENT);
		b.box(0.9F, 1.3F, 1.6F, 0.92F, 1.9F, 2.2F, PANEL);
		b.box(-0.95F, 2.05F, 1.25F, 0.95F, 2.12F, 2.55F, OLIVE_DARK);
		// pack cradle and the elevating rams
		b.box(-1.1F, 0.98F, -2.0F, -0.9F, 1.4F, -1.2F, OLIVE_DARK);
		b.box(0.9F, 0.98F, -2.0F, 1.1F, 1.4F, -1.2F, OLIVE_DARK);
		Vector3f ramTop = new Vector3f(0.0F, -0.1F, 1.6F).rotate(new Quaternionf().rotationX(-ELEVATION)).add(0.0F, PIVOT_Y, PIVOT_Z);
		for (float x : new float[] {-0.7F, 0.7F}) {
			b.beam(v(x, 1.0F, 0.6F), new Vector3f(ramTop).add(x, 0, 0), 0.14F, 0.14F, STEEL);
			b.beam(v(x, 1.0F, 0.6F), v(x, 1.0F, 0.6F).lerp(new Vector3f(ramTop).add(x, 0, 0), 0.5F), 0.22F, 0.22F, OLIVE_DARK);
		}
		b.beam(v(0.9F, 1.0F, 1.9F), v(RADAR_X - 0.5F, 0.05F, 1.2F), 0.05F, 0.05F, CABLE);

		// ---- EL/M-2084 radar on its own small trailer
		float rx = RADAR_X;
		b.box(rx - 0.8F, 0.5F, -1.2F, rx + 0.8F, 0.8F, 1.8F, OLIVE_DARK);
		b.cylinderX(rx - 0.85F, 0.42F, -0.3F, 0.42F, 0.3F, 14, BLACK, STEEL);
		b.cylinderX(rx + 0.85F, 0.42F, -0.3F, 0.42F, 0.3F, 14, BLACK, STEEL);
		b.box(rx - 0.7F, 0.8F, 0.9F, rx + 0.7F, 1.7F, 1.7F, OLIVE);
		b.box(rx - 0.72F, 1.0F, 1.1F, rx - 0.7F, 1.5F, 1.5F, VENT);
		b.box(rx - 0.18F, 0.8F, 0.22F, rx + 0.18F, 3.4F, 0.58F, OLIVE_DARK); // mast
		b.box(rx - 0.03F, 3.4F, 0.37F, rx + 0.03F, 4.75F, 0.43F, STEEL); // lightning rod / beacon post
		for (float[] o : new float[][] {{-1.0F, -1.0F}, {1.0F, -1.0F}, {-1.0F, 1.6F}, {1.0F, 1.6F}}) {
			b.beam(v(rx + o[0] * 0.75F, 0.6F, o[1]), v(rx + o[0] * 1.5F, 0.06F, o[1]), 0.1F, 0.1F, STEEL);
		}
		return b.build();
	}

	/** The 20-cell box launcher in pack space: tubes along +Z, the pivot at the origin. */
	private static BoxMesh buildPack() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-1.05F, 0.0F, 0.0F, 1.05F, 1.55F, PACK_LENGTH, OLIVE);
		// stiffening ribs, rear hatches, lifting eyes, markings
		for (float z = 0.4F; z < PACK_LENGTH; z += 0.75F) {
			b.box(-1.1F, -0.05F, z, 1.1F, 1.6F, z + 0.1F, OLIVE_DARK);
		}
		b.box(-0.9F, 0.15F, -0.04F, 0.9F, 1.4F, 0.0F, OLIVE_DARK);
		b.box(-0.6F, 0.3F, -0.06F, -0.1F, 0.7F, -0.04F, PANEL);
		b.box(0.1F, 0.3F, -0.06F, 0.6F, 0.7F, -0.04F, PANEL);
		b.box(-1.08F, 0.55F, 1.4F, -1.05F, 1.0F, 2.6F, HAZARD);
		b.box(1.05F, 0.55F, 1.4F, 1.08F, 1.0F, 2.6F, HAZARD);
		for (float x : new float[] {-0.8F, 0.8F}) {
			b.box(x - 0.08F, 1.55F, PACK_LENGTH - 0.4F, x + 0.08F, 1.7F, PACK_LENGTH - 0.25F, STEEL);
		}
		// muzzle face frame
		b.box(-1.08F, -0.03F, PACK_LENGTH, 1.08F, 1.58F, PACK_LENGTH + 0.05F, OLIVE_DARK);
		return b.build();
	}

	/** Cell covers: 0-19 closed (loaded, red cover), 20-39 open (fired, dark tube mouth). Row 0 at the top. */
	private static BoxMesh[] buildCaps() {
		BoxMesh[] caps = new BoxMesh[41];
		float r = 0.13F;
		for (int i = 0; i < 20; i++) {
			float[] c = IronDomeBlockEntity.cellCentre(i);
			float x = c[0];
			float y = c[1];
			caps[i] = new BoxMesh.Builder().box(x - r, y - r, PACK_LENGTH + 0.05F, x + r, y + r, PACK_LENGTH + 0.09F, RED).build();
			// fired: the dark tube mouth with a sooty rim
			caps[i + 20] = new BoxMesh.Builder()
				.box(x - r, y - r, PACK_LENGTH - 0.2F, x + r, y + r, PACK_LENGTH - 0.15F, BLACK)
				.box(x - r - 0.02F, y - r - 0.02F, PACK_LENGTH + 0.05F, x + r + 0.02F, y - r, PACK_LENGTH + 0.07F, SOOT)
				.box(x - r - 0.02F, y + r, PACK_LENGTH + 0.05F, x + r + 0.02F, y + r + 0.02F, PACK_LENGTH + 0.07F, SOOT)
				.build();
		}
		caps[40] = new BoxMesh.Builder().box(-r, -r, -0.02F, r, r, 0.02F, RED).build(); // a flying cover, centred
		return caps;
	}

	/** Flat AESA array on its rotator, local to the top of the mast. */
	private static BoxMesh buildRadarArray() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.25F, 0.0F, -0.25F, 0.25F, 0.3F, 0.25F, STEEL);
		b.box(-1.1F, 0.3F, -0.12F, 1.1F, 1.35F, 0.06F, OLIVE_DARK);
		b.box(-1.0F, 0.38F, 0.06F, 1.0F, 1.27F, 0.1F, ARRAY);
		b.box(-1.0F, 0.38F, -0.16F, 1.0F, 1.27F, -0.12F, OLIVE);
		b.beam(v(0, 0.3F, -0.1F), v(0, 0.8F, -0.6F), 0.08F, 0.08F, STEEL); // tilt strut
		return b.build();
	}
}
