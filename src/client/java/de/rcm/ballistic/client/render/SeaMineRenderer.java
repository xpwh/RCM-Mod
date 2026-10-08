package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.MINE;
import static de.rcm.ballistic.client.render.StructureKit.RUST;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.SeaMineBlock;
import de.rcm.ballistic.block.SeaMineBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Moored contact mine, as laid in both world wars: a rusty steel sphere with lead Hertz horns (break
 * one and the acid inside fires the mine), an equator seam and a lifting lug, held under the surface
 * by a mooring chain from a heavy sinker on the sea floor. It sways and bobs in the current. Laid on
 * land it rests on a wooden cradle instead.
 */
public class SeaMineRenderer implements BlockEntityRenderer<SeaMineBlockEntity, SeaMineRenderer.State> {
	private static final float RADIUS = 0.4F;
	private static final float CENTRE = 0.5F;
	private static final BoxMesh BODY = buildBody();
	private static final BoxMesh SINKER = buildSinker();
	private static final BoxMesh CRADLE = buildCradle();

	public SeaMineRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public boolean water;
		/** Blocks of water below the mine down to the sea floor. */
		public int depth;
		public float time;
		public float phase;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(SeaMineBlockEntity mine, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(mine, state, partialTick, cameraPos, overlay);
		Level level = mine.getLevel();
		BlockPos pos = mine.getBlockPos();
		state.water = state.blockState.hasProperty(SeaMineBlock.WATERLOGGED) && state.blockState.getValue(SeaMineBlock.WATERLOGGED);
		state.time = level == null ? 0.0F : level.getGameTime() + partialTick;
		state.phase = (pos.getX() * 7 + pos.getZ() * 13) * 0.37F;
		state.depth = 0;
		if (level != null && state.water) {
			BlockPos.MutableBlockPos m = pos.below().mutable();
			while (state.depth < 40 && !level.getFluidState(m).isEmpty() && level.getBlockState(m).getCollisionShape(level, m).isEmpty()) {
				state.depth++;
				m.move(0, -1, 0);
			}
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		if (!state.water) {
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CRADLE.emit(pose, consumer, light));
			poseStack.translate(0.0F, 0.12F, 0.0F);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> BODY.emit(pose, consumer, light));
			poseStack.popPose();
			return;
		}
		// the current sways the mine around its mooring; it bobs a little
		float t = state.time * 0.035F + state.phase;
		float swayX = 0.06F * Mth.sin(t) + 0.025F * Mth.sin(t * 2.3F);
		float swayZ = 0.05F * Mth.cos(t * 0.8F);
		float bob = 0.04F * Mth.sin(t * 1.7F);
		float floor = -state.depth;
		// mooring chain from the sinker up to the shackle under the sphere
		Vector3f bottom = v(swayX, CENTRE - RADIUS - 0.08F + bob, swayZ);
		Vector3f anchor = v(0.0F, floor + 0.35F, 0.0F);
		BoxMesh.Builder chain = new BoxMesh.Builder();
		float length = bottom.distance(anchor);
		int links = Math.max(2, (int) (length / 0.22F));
		for (int i = 0; i < links; i++) {
			float f0 = (float) i / links;
			float f1 = (float) (i + 1) / links;
			// a little slack: the chain bows out with the current
			float slack0 = 0.12F * Mth.sin(f0 * Mth.PI);
			float slack1 = 0.12F * Mth.sin(f1 * Mth.PI);
			Vector3f a = new Vector3f(anchor).lerp(bottom, f0).add(slack0 * Mth.sign(swayX), 0, 0);
			Vector3f b = new Vector3f(anchor).lerp(bottom, f1).add(slack1 * Mth.sign(swayX), 0, 0);
			// alternate link orientation, like a real chain
			float w = i % 2 == 0 ? 0.09F : 0.03F;
			float h = i % 2 == 0 ? 0.03F : 0.09F;
			chain.beam(a, b, w, h, i % 3 == 0 ? RUST : STEEL);
		}
		BoxMesh chainMesh = chain.build();
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> chainMesh.emit(pose, consumer, light));
		poseStack.pushPose();
		poseStack.translate(0.0F, floor, 0.0F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> SINKER.emit(pose, consumer, light));
		poseStack.popPose();
		poseStack.translate(swayX, bob, swayZ);
		poseStack.translate(0.0F, CENTRE, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationXYZ(swayZ * 0.8F, t * 0.05F, -swayX * 0.8F));
		poseStack.translate(0.0F, -CENTRE, 0.0F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> BODY.emit(pose, consumer, light));
		poseStack.popPose();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	/** The sphere around (0, CENTRE, 0) with its horns, seam, lug and shackle. */
	private static BoxMesh buildBody() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float[][] profile = new float[13][];
		for (int i = 0; i <= 12; i++) {
			float a = -Mth.HALF_PI + Mth.PI * i / 12.0F;
			profile[i] = new float[] {RADIUS + Mth.sin(a) * RADIUS, Mth.cos(a) * RADIUS};
		}
		b.revolve(MINE, v(0, CENTRE - RADIUS, 0), v(0, 1, 0), profile, 20);
		// equator seam with its bolt flange
		b.revolve(STEEL, v(0, CENTRE - 0.03F, 0), v(0, 1, 0), new float[][] {{0.0F, RADIUS + 0.03F}, {0.06F, RADIUS + 0.03F}}, 20);
		// Hertz horns: one on top, five round the upper half
		java.util.List<Vector3f> dirs = new java.util.ArrayList<>();
		dirs.add(v(0, 1, 0));
		for (int i = 0; i < 5; i++) {
			float a = i * Mth.TWO_PI / 5.0F;
			dirs.add(v(Mth.cos(a) * 0.72F, 0.69F, Mth.sin(a) * 0.72F));
		}
		for (int i = 0; i < 3; i++) {
			float a = i * Mth.TWO_PI / 3.0F + 0.5F;
			dirs.add(v(Mth.cos(a) * 0.97F, 0.1F, Mth.sin(a) * 0.97F));
		}
		Vector3f c = v(0, CENTRE, 0);
		for (Vector3f d : dirs) {
			d.normalize();
			if (d.y > 0.99F) {
				continue; // the top carries the lug instead
			}
			Vector3f base = new Vector3f(d).mul(RADIUS - 0.02F).add(c);
			Vector3f tip = new Vector3f(d).mul(RADIUS + 0.13F).add(c);
			b.beam(base, tip, 0.07F, 0.07F, STEEL);
			Vector3f cap = new Vector3f(d).mul(RADIUS + 0.16F).add(c);
			b.beam(tip, cap, 0.09F, 0.09F, BLACK); // the lead cap over the acid vial
		}
		// lifting lug on top and the mooring shackle underneath
		b.box(-0.03F, CENTRE + RADIUS - 0.02F, -0.1F, 0.03F, CENTRE + RADIUS + 0.12F, 0.1F, RUST);
		b.box(-0.06F, CENTRE - RADIUS - 0.1F, -0.06F, 0.06F, CENTRE - RADIUS + 0.02F, 0.06F, RUST);
		return b.build();
	}

	/** The sinker on the sea floor (origin at the floor's top surface). */
	private static BoxMesh buildSinker() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.35F, 0.0F, -0.3F, 0.35F, 0.3F, 0.3F, RUST);
		b.box(-0.38F, 0.0F, -0.33F, -0.3F, 0.08F, 0.33F, BLACK); // skids
		b.box(0.3F, 0.0F, -0.33F, 0.38F, 0.08F, 0.33F, BLACK);
		b.box(-0.1F, 0.3F, -0.05F, 0.1F, 0.4F, 0.05F, STEEL); // chain drum
		return b.build();
	}

	/** Wooden handling cradle the mine rests on when stored on land. */
	private static BoxMesh buildCradle() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float z : new float[] {-0.3F, 0.3F}) {
			b.box(-0.45F, 0.0F, z - 0.06F, 0.45F, 0.08F, z + 0.06F, RUST);
			b.box(-0.3F, 0.08F, z - 0.05F, -0.18F, 0.22F, z + 0.05F, RUST);
			b.box(0.18F, 0.08F, z - 0.05F, 0.3F, 0.22F, z + 0.05F, RUST);
		}
		return b.build();
	}
}
