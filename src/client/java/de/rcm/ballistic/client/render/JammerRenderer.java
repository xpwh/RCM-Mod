package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.DISH;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
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
 * Electronic-warfare jamming station (Krasukha-style): an equipment shelter, three masts carrying
 * log-periodic arrays pointed outwards, whip antennas, and a slowly turning dish on the roof. A
 * violet beacon pulses while it jams.
 */
public class JammerRenderer implements BlockEntityRenderer<JammerBlockEntity, JammerRenderer.State> {
	/** Mast positions {x, z} and the direction their arrays face (radians). Declared before the meshes. */
	private static final float[][] MASTS = {{-2.4F, -1.6F, -2.4F}, {2.4F, -1.6F, 2.4F}, {0.0F, 2.6F, 0.0F}};
	private static final float MAST_HEIGHT = 5.5F;

	private static final BoxMesh STATION = buildStation();
	private static final BoxMesh DISH_MESH = buildDish();
	private static final BoxMesh BEACON = new BoxMesh.Builder().box(-0.12F, 2.45F, -0.12F, 0.12F, 2.7F, 0.12F, PURPLE_LAMP).build();

	public JammerRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public boolean active;
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
		state.time = jammer.getLevel() == null ? 0.0F : (jammer.getLevel().getGameTime() % 24000L) + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> STATION.emit(pose, consumer, light));
		if (state.active && Mth.sin(state.time * 0.6F) > -0.2F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BEACON.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		poseStack.translate(0.8F, 2.1F, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationY(state.active ? state.time * 0.05F : 0.0F));
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

	private static BoxMesh buildStation() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// equipment shelter around the block, door, air conditioner, generator box
		b.box(-1.3F, 0.0F, -1.0F, 1.3F, 2.1F, 1.0F, OLIVE);
		b.box(-1.35F, 2.1F, -1.05F, 1.35F, 2.2F, 1.05F, OLIVE_DARK);
		b.box(-1.32F, 0.1F, -0.4F, -1.3F, 1.8F, 0.4F, DOOR);
		b.box(1.3F, 0.8F, -0.6F, 1.6F, 1.6F, 0.4F, VENT);
		b.box(0.4F, 0.0F, 1.0F, 1.2F, 0.9F, 1.6F, OLIVE_DARK);
		b.box(-1.0F, 1.0F, 1.0F, -0.2F, 1.8F, 1.04F, PANEL);
		// whip antennas and the beacon post on the roof
		for (float[] w : new float[][] {{-1.1F, -0.8F}, {-1.1F, 0.8F}, {-0.6F, 0.85F}}) {
			b.box(w[0] - 0.02F, 2.2F, w[1] - 0.02F, w[0] + 0.02F, 4.2F, w[1] + 0.02F, BLACK);
		}
		b.box(-0.06F, 2.2F, -0.06F, 0.06F, 2.45F, 0.06F, STEEL);
		// masts with log-periodic arrays facing outwards, guyed, cabled into the shelter
		for (float[] m : MASTS) {
			float x = m[0];
			float z = m[1];
			b.box(x - 0.3F, 0.0F, z - 0.3F, x + 0.3F, 0.12F, z + 0.3F, CONCRETE_DARK);
			b.box(x - 0.07F, 0.12F, z - 0.07F, x + 0.07F, MAST_HEIGHT, z + 0.07F, STEEL);
			float dx = Mth.sin(m[2]);
			float dz = Mth.cos(m[2]);
			float px = dz;
			float pz = -dx;
			// boom pointing outwards with elements shrinking towards the tip
			b.beam(v(x, MAST_HEIGHT - 0.4F, z), v(x + dx * 1.8F, MAST_HEIGHT - 0.4F, z + dz * 1.8F), 0.06F, 0.06F, STEEL);
			for (int i = 0; i < 8; i++) {
				float f = i / 7.0F;
				float half = 1.1F - 0.85F * f;
				float bx = x + dx * (0.15F + 1.6F * f);
				float bz = z + dz * (0.15F + 1.6F * f);
				b.beam(v(bx - px * half, MAST_HEIGHT - 0.4F, bz - pz * half), v(bx + px * half, MAST_HEIGHT - 0.4F, bz + pz * half), 0.03F, 0.03F, STEEL);
			}
			b.beam(v(x, MAST_HEIGHT - 1.0F, z), v(x + 1.8F, 0.0F, z + 1.2F), 0.025F, 0.025F, CABLE);
			b.beam(v(x, MAST_HEIGHT - 1.0F, z), v(x - 1.8F, 0.0F, z + 1.2F), 0.025F, 0.025F, CABLE);
			b.beam(v(x, 0.1F, z), v(x * 0.4F, 0.1F, z * 0.4F), 0.08F, 0.08F, CABLE);
		}
		return b.build();
	}

	/** Roof dish on its rotator, local to the rotator. */
	private static BoxMesh buildDish() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.12F, 0.0F, -0.12F, 0.12F, 0.5F, 0.12F, STEEL);
		b.revolve(DISH, v(0, 0.9F, -0.1F), v(0, 0.3F, 1.0F), new float[][] {{0.0F, 0.0F}, {0.04F, 0.15F}, {0.14F, 0.35F}, {0.3F, 0.55F}}, 18);
		b.beam(v(0, 0.9F, -0.1F), v(0, 1.05F, 0.45F), 0.04F, 0.04F, STEEL);
		return b.build();
	}
}
