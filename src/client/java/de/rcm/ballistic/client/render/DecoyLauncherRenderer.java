package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.DecoyLauncherBlockEntity;
import de.rcm.ballistic.block.DefenseSiteBlock;
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
 * Decoy launcher (SRBOC-style chaff/flare/decoy mortars): two banks of three fixed mortar tubes
 * splayed out to both sides at 45, 55 and 65 degrees on a steel pedestal, a fire-control box with
 * its antenna and the cabling. Each tube shows a yellow cap while it still holds a round.
 */
public class DecoyLauncherRenderer implements BlockEntityRenderer<DecoyLauncherBlockEntity, DecoyLauncherRenderer.State> {
	private static final float TUBE_LENGTH = DecoyLauncherBlockEntity.TUBE_LENGTH;
	private static final float TUBE_RADIUS = DecoyLauncherBlockEntity.TUBE_RADIUS;
	private static final float[][] TUBES = DecoyLauncherBlockEntity.TUBES;

	private static final BoxMesh BASE = buildBase();
	private static final BoxMesh[] CAPS = buildCaps();
	private static final BoxMesh FLYING_CAP = new BoxMesh.Builder().revolve(YELLOW, v(0, 0, -0.03F), v(0, 0, 1),
		new float[][] {{0.0F, 0.0F}, {0.0F, TUBE_RADIUS + 0.01F}, {0.06F, TUBE_RADIUS + 0.01F}, {0.07F, 0.0F}}, 12).build();

	public DecoyLauncherRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float facingYaw;
		public int salvos;
		public final float[] pop = new float[6];
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(DecoyLauncherBlockEntity launcher, State state, float partialTick, Vec3 cameraPos,
		ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(launcher, state, partialTick, cameraPos, overlay);
		Direction facing = state.blockState.hasProperty(DefenseSiteBlock.FACING) ? state.blockState.getValue(DefenseSiteBlock.FACING) : Direction.NORTH;
		state.facingYaw = (float) Mth.atan2(facing.getStepX(), facing.getStepZ());
		state.salvos = launcher.getAmmo();
		for (int i = 0; i < 6; i++) {
			state.pop[i] = launcher.popAge[i] < 0 ? -1.0F : launcher.popAge[i] + partialTick;
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.mulPose(new Quaternionf().rotationY(state.facingYaw));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> BASE.emit(pose, consumer, light));
		for (int i = 0; i < TUBES.length; i++) {
			BoxMesh cap = i < TUBES.length - state.salvos ? CAPS[i + TUBES.length] : CAPS[i];
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> cap.emit(pose, consumer, light));
			float t = state.pop[i];
			if (t >= 0.0F && t < 35.0F) {
				// the cap blown off the muzzle, flying out along the tube and dropping
				Vector3f a = axis(TUBES[i]);
				Vector3f muzzle = new Vector3f(a).mul(TUBE_LENGTH).add(breech(i));
				float out = 0.5F * (1.0F - (float) Math.pow(0.88, t)) / 0.12F;
				Vector3f p = new Vector3f(a).mul(out).add(muzzle).add(0, -0.5F * 0.04F * t * t, 0);
				poseStack.pushPose();
				poseStack.translate(p.x, p.y, p.z);
				poseStack.mulPose(new Quaternionf().rotationXYZ(t * 0.6F, t * 0.3F, 0.0F));
				collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> FLYING_CAP.emit(pose, consumer, light));
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
		return 160;
	}

	/** Unit direction of tube {yaw, elevation}: elevation up from horizontal, yaw to the sides of +Z. */
	private static Vector3f axis(float[] tube) {
		float yaw = tube[0] * Mth.DEG_TO_RAD;
		float el = tube[1] * Mth.DEG_TO_RAD;
		return new Vector3f(Mth.sin(yaw) * Mth.cos(el), Mth.sin(el), Mth.cos(yaw) * Mth.cos(el));
	}

	private static Vector3f breech(int i) {
		float[] t = TUBES[i];
		return v(Math.signum(t[0]) * 0.32F, 0.95F + (t[1] - 45.0F) * 0.006F, -0.15F + (t[1] - 45.0F) * 0.012F);
	}

	private static BoxMesh buildBase() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.8F, 0.0F, -0.8F, 0.8F, 0.12F, 0.8F, CONCRETE);
		b.box(-0.35F, 0.12F, -0.35F, 0.35F, 0.65F, 0.35F, OLIVE_DARK); // pedestal
		b.box(-0.55F, 0.65F, -0.45F, 0.55F, 0.8F, 0.45F, OLIVE); // mounting plate
		b.box(-0.56F, 0.66F, 0.42F, 0.56F, 0.74F, 0.46F, HAZARD);
		for (int i = 0; i < TUBES.length; i++) {
			Vector3f a = axis(TUBES[i]);
			Vector3f start = breech(i);
			b.revolve(OLIVE, start, a, new float[][] {{0.0F, 0.0F}, {0.0F, TUBE_RADIUS + 0.03F}, {0.25F, TUBE_RADIUS + 0.03F}, {0.25F, TUBE_RADIUS},
				{TUBE_LENGTH, TUBE_RADIUS}, {TUBE_LENGTH, TUBE_RADIUS - 0.03F}, {TUBE_LENGTH - 0.05F, 0.0F}}, 12);
			// support strut from the plate to each tube
			b.beam(v(start.x * 0.6F, 0.8F, start.z), new Vector3f(a).mul(0.5F).add(start), 0.06F, 0.06F, STEEL);
		}
		// fire-control box, its whip antenna, firing cable
		b.box(0.55F, 0.12F, -0.75F, 0.78F, 0.7F, -0.35F, OLIVE);
		b.box(0.78F, 0.3F, -0.68F, 0.79F, 0.6F, -0.42F, PANEL);
		b.box(0.64F, 0.7F, -0.6F, 0.68F, 1.9F, -0.56F, BLACK);
		b.beam(v(0.6F, 0.2F, -0.4F), v(0.3F, 0.4F, 0.0F), 0.04F, 0.04F, CABLE);
		return b.build();
	}

	/** Muzzle caps: 0-5 loaded (yellow), 6-11 fired (open, dark). */
	private static BoxMesh[] buildCaps() {
		BoxMesh[] caps = new BoxMesh[TUBES.length * 2];
		for (int i = 0; i < TUBES.length; i++) {
			Vector3f a = axis(TUBES[i]);
			Vector3f muzzle = new Vector3f(a).mul(TUBE_LENGTH - 0.02F).add(breech(i));
			caps[i] = new BoxMesh.Builder().revolve(YELLOW, muzzle, a, new float[][] {{0.0F, 0.0F}, {0.0F, TUBE_RADIUS + 0.01F}, {0.06F, TUBE_RADIUS + 0.01F}, {0.07F, 0.0F}}, 12).build();
			caps[i + TUBES.length] = new BoxMesh.Builder().revolve(BLACK, new Vector3f(a).mul(-0.04F).add(muzzle), a,
				new float[][] {{0.0F, 0.0F}, {0.0F, TUBE_RADIUS - 0.02F}, {0.03F, TUBE_RADIUS - 0.02F}, {0.03F, 0.0F}}, 12).build();
		}
		return caps;
	}
}
