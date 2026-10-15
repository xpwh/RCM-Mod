package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.SOOT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.block.InclinedLauncherBlock;
import de.rcm.ballistic.block.InclinedLauncherBlockEntity;
import de.rcm.ballistic.entity.MissileEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The inclined canister launcher: a steel launch frame on jacks over its concrete footing, an olive
 * launch canister in a cradle - its rear end on a trunnion, its front held up by a hydraulic elevating
 * ram - with stiffening ribs, lifting lugs, an umbilical cable to a firing box, and the frangible front
 * cover: closed while a missile waits, swung open through the countdown, gone once it has flown (blown off
 * and replaced only when the next missile is loaded). The status lamp on the firing box shows green with a
 * missile ready and flashes red through the countdown.
 * <p>
 * Built facing -Z (north) and turned to the block's facing; the canister in its own rail frame: origin at
 * its rear end on the missile's axis, running along -Z, tilted up by {@link InclinedLauncherBlock#PITCH}.
 */
public class InclinedLauncherRenderer implements BlockEntityRenderer<InclinedLauncherBlockEntity, InclinedLauncherRenderer.State> {
	private static final float LEN = (float) InclinedLauncherBlock.CANISTER_LENGTH;
	private static final float HALF = 0.34F;
	private static final float WALL = 0.05F;
	private static final float REAR_Z = (float) InclinedLauncherBlock.REAR_BACK;
	private static final float REAR_Y = (float) InclinedLauncherBlock.REAR_HEIGHT;
	private static final float PITCH = InclinedLauncherBlock.PITCH;
	private static final BoxMesh FRAME = frame();
	private static final BoxMesh CANISTER = canister();
	private static final BoxMesh COVER = cover();
	private static final BoxMesh LAMP_GREEN = lamp(GREEN_LAMP);
	private static final BoxMesh LAMP_RED = lamp(RED_LAMP);

	public InclinedLauncherRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float time;
		public float yaw;
		/** 0 no missile (cover gone), 1 ready (cover shut), 2 counting down / firing (cover swinging open). */
		public int status;
		/** How far open the front cover is, 0..1. */
		public float cover;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(InclinedLauncherBlockEntity be, State state, float partialTick, Vec3 cameraPos,
		ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(be, state, partialTick, cameraPos, overlay);
		Direction facing = be.getBlockState().hasProperty(InclinedLauncherBlock.FACING) ? be.getBlockState().getValue(InclinedLauncherBlock.FACING) : Direction.NORTH;
		state.yaw = 180.0F - facing.toYRot();
		state.time = be.getLevel() == null ? 0.0F : be.getLevel().getGameTime() + partialTick;
		state.status = 0;
		state.cover = 1.0F;
		if (be.getLevel() == null) {
			return;
		}
		Vec3 tail = InclinedLauncherBlock.missileTail(be.getBlockPos(), facing);
		Vec3 mid = tail.add(InclinedLauncherBlock.railDirection(facing).scale(2.0));
		for (MissileEntity missile : be.getLevel().getEntitiesOfClass(MissileEntity.class, new AABB(mid, mid).inflate(2.5))) {
			int s = missile.getState();
			if (s == MissileEntity.IDLE) {
				state.status = Math.max(state.status, 1);
				state.cover = Math.min(state.cover, 0.0F);
			} else if (s == MissileEntity.COUNTDOWN) {
				float remaining = missile.getMissileType().countdownTicks - (missile.clientStateAge + partialTick);
				float t = Mth.clamp((60.0F - remaining) / 25.0F, 0.0F, 1.0F);
				state.status = 2;
				state.cover = Math.min(state.cover, t * t * (3.0F - 2.0F * t));
			} else if (s == MissileEntity.IGNITION) {
				state.status = 2;
			}
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.mulPose(Axis.YP.rotationDegrees(state.yaw));
		ModelPick.part(state, poseStack, camera, FRAME);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> FRAME.emit(pose, consumer, light));
		if (state.status == 1 || (state.status == 2 && Mth.sin(state.time * 0.6F) > 0.0F)) {
			BoxMesh lamp = state.status == 1 ? LAMP_GREEN : LAMP_RED;
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> lamp.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		// the canister, in its rail frame
		poseStack.translate(0.0F, REAR_Y, REAR_Z);
		poseStack.mulPose(Axis.XP.rotationDegrees(PITCH));
		ModelPick.part(state, poseStack, camera, CANISTER);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CANISTER.emit(pose, consumer, light));
		if (state.status > 0) {
			// the front cover, hinged along its top edge, swinging up and open
			poseStack.translate(0.0F, HALF, -LEN);
			poseStack.mulPose(Axis.XP.rotationDegrees(110.0F * state.cover));
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> COVER.emit(pose, consumer, light));
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

	// ------------------------------------------------------------------ geometry (facing -Z)

	private static BoxMesh frame() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float y0 = 5.0F / 16.0F;
		// the launch frame: two longitudinal beams and cross members over the footing
		for (float x : new float[] {-0.62F, 0.62F}) {
			b.box(x - 0.09F, y0, -2.6F, x + 0.09F, y0 + 0.22F, REAR_Z + 0.5F, OLIVE_DARK);
		}
		for (float z = -2.5F; z <= REAR_Z + 0.4F; z += 0.85F) {
			b.box(-0.62F, y0 + 0.04F, z - 0.06F, 0.62F, y0 + 0.18F, z + 0.06F, OLIVE_DARK);
		}
		b.box(-0.55F, y0 + 0.22F, -2.4F, 0.55F, y0 + 0.24F, REAR_Z + 0.3F, STEEL); // walkway plate
		// levelling jacks at the four corners with their foot pads
		for (float x : new float[] {-0.95F, 0.95F}) {
			for (float z : new float[] {-2.45F, REAR_Z + 0.35F}) {
				b.box(x - 0.05F, 0.0F, z - 0.05F, x + 0.05F, y0 + 0.2F, z + 0.05F, STEEL);
				b.box(x - 0.15F, 0.0F, z - 0.15F, x + 0.15F, 0.04F, z + 0.15F, BLACK);
				b.box(Math.min(x, x > 0 ? 0.62F : -0.62F), y0 + 0.08F, z - 0.04F, Math.max(x, x > 0 ? 0.62F : -0.62F), y0 + 0.16F, z + 0.04F, OLIVE_DARK);
			}
		}
		// the rear trunnion: an A-frame each side carrying the canister's pivot
		float pivotY = REAR_Y;
		for (float sx : new float[] {-1.0F, 1.0F}) {
			float x = sx * (HALF + 0.08F);
			b.beam(v(x, y0 + 0.22F, REAR_Z - 0.45F), v(x, pivotY, REAR_Z), 0.08F, 0.1F, OLIVE);
			b.beam(v(x, y0 + 0.22F, REAR_Z + 0.4F), v(x, pivotY, REAR_Z), 0.08F, 0.1F, OLIVE);
			b.box(x - 0.06F, pivotY - 0.08F, REAR_Z - 0.08F, x + 0.06F, pivotY + 0.08F, REAR_Z + 0.08F, STEEL);
		}
		// the elevating ram: a hydraulic cylinder from the frame up to the canister's belly, a chromed rod
		float p = (float) Math.toRadians(PITCH);
		float sz = REAR_Z - 2.6F * Mth.cos(p);
		float sy = REAR_Y + 2.6F * Mth.sin(p) - HALF;
		b.beam(v(0, y0 + 0.24F, -1.7F), v(0, (y0 + 0.24F + sy) * 0.5F, (-1.7F + sz) * 0.5F), 0.16F, 0.16F, OLIVE_DARK);
		b.beam(v(0, (y0 + 0.24F + sy) * 0.5F, (-1.7F + sz) * 0.5F), v(0, sy, sz), 0.08F, 0.08F, STEEL);
		b.box(-0.12F, y0 + 0.22F, -1.82F, 0.12F, y0 + 0.36F, -1.58F, STEEL);
		// the firing box at the rear with its status lamp housing, the umbilical up to the canister
		b.box(0.7F, y0 + 0.24F, REAR_Z + 0.05F, 1.05F, y0 + 0.8F, REAR_Z + 0.45F, PANEL);
		b.box(0.84F, y0 + 0.8F, REAR_Z + 0.2F, 0.92F, y0 + 0.86F, REAR_Z + 0.28F, BLACK);
		b.beam(v(0.75F, y0 + 0.6F, REAR_Z + 0.25F), v(HALF, REAR_Y + 0.25F, REAR_Z - 0.4F), 0.035F, 0.035F, CABLE);
		// hazard striping on the frame's ends
		b.box(-0.72F, y0 + 0.02F, -2.62F, 0.72F, y0 + 0.2F, -2.58F, HAZARD);
		b.box(-0.72F, y0 + 0.02F, REAR_Z + 0.5F, 0.72F, y0 + 0.2F, REAR_Z + 0.54F, HAZARD);
		// scorch on the footing behind the canister's mouth
		b.box(-0.5F, 0.001F, REAR_Z + 0.55F, 0.5F, 0.012F, REAR_Z + 1.6F, SOOT);
		b.box(-0.9F, -0.02F, REAR_Z + 0.5F, 0.9F, 0.0F, REAR_Z + 1.8F, CONCRETE);
		return b.build();
	}

	/** The canister: four walls round the missile (open at both ends: the cover is drawn on its own), ribs, lugs. */
	private static BoxMesh canister() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float h = HALF;
		b.box(-h, h - WALL, -LEN, h, h, 0.0F, OLIVE);
		b.box(-h, -h, -LEN, h, -h + WALL, 0.0F, OLIVE);
		b.box(-h, -h + WALL, -LEN, -h + WALL, h - WALL, 0.0F, OLIVE);
		b.box(h - WALL, -h + WALL, -LEN, h, h - WALL, 0.0F, OLIVE);
		// stiffening ribs round it
		for (float z = -0.25F; z > -LEN; z -= 1.05F) {
			b.box(-h - 0.03F, -h - 0.03F, z - 0.06F, h + 0.03F, h + 0.03F, z, OLIVE_DARK);
		}
		// end flanges, the rear one sooted from the launches
		b.box(-h - 0.05F, -h - 0.05F, -0.08F, h + 0.05F, h + 0.05F, 0.0F, OLIVE_DARK);
		b.box(-h - 0.05F, -h - 0.05F, -LEN, h + 0.05F, h + 0.05F, -LEN + 0.08F, OLIVE_DARK);
		b.box(-h + WALL, -h + WALL, -0.002F, h - WALL, h - WALL, 0.0F, SOOT);
		// lifting lugs on top, stencilled panel, the trunnion pin
		for (float z : new float[] {-1.2F, -LEN + 1.2F}) {
			b.box(-0.06F, h, z - 0.08F, 0.06F, h + 0.08F, z + 0.08F, STEEL);
		}
		b.box(-h - 0.004F, -0.12F, -2.9F, -h, 0.12F, -2.1F, WHITE);
		b.box(-h - 0.12F, -0.06F, -0.06F, h + 0.12F, 0.06F, 0.06F, STEEL);
		return b.build();
	}

	/** The front cover, hinged at its top edge (origin), hanging down over the mouth along -Y. */
	private static BoxMesh cover() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-HALF, -2.0F * HALF, -0.04F, HALF, 0.0F, 0.0F, OLIVE_DARK);
		b.box(-HALF + 0.08F, -2.0F * HALF + 0.08F, -0.045F, HALF - 0.08F, -0.08F, -0.04F, WHITE);
		b.box(-0.12F, -0.03F, -0.06F, 0.12F, 0.02F, 0.01F, STEEL);
		return b.build();
	}

	private static BoxMesh lamp(int patch) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float y0 = 5.0F / 16.0F;
		b.box(0.85F, y0 + 0.86F, REAR_Z + 0.21F, 0.91F, y0 + 0.92F, REAR_Z + 0.27F, patch);
		return b.build();
	}
}
