package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BEAM_HAZE;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.base.BaseBlocks;
import de.rcm.ballistic.base.BaseFlagBlockEntity;
import de.rcm.ballistic.client.effect.DynamicLights;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** The lights of military bases (floodlights, alarm beacons) and the base flag. */
public final class BaseRenderers {
	private BaseRenderers() {
	}

	/** How dark it is out: 0 by day, 1 at night (rain darkens it too) - beams of light only show in the dark. */
	static float darkness(@Nullable Level level, float partialTick) {
		if (level == null) {
			return 0.0F;
		}
		float t = (level.getDayTime() % 24000L + partialTick) / 24000.0F;
		float night = Mth.clamp(Mth.cos((t - 0.75F) * Mth.TWO_PI) / 0.5F, 0.0F, 1.0F);
		float rain = level.getRainLevel(partialTick) * 0.4F + level.getThunderLevel(partialTick) * 0.3F;
		return Mth.clamp(night + rain, 0.0F, 1.0F);
	}

	/** A cone of light haze along +Z from the origin. */
	private static BoxMesh cone(float near, float far, float length, int patch) {
		return new BoxMesh.Builder().hexa(patch,
			new Vector3f(-near, -near, 0), new Vector3f(near, -near, 0), new Vector3f(near, near, 0), new Vector3f(-near, near, 0),
			new Vector3f(-far, -far, length), new Vector3f(far, -far, length), new Vector3f(far, far, length), new Vector3f(-far, far, length)
		).build();
	}

	// ------------------------------------------------------------------ lamps

	public static class LampRenderer implements BlockEntityRenderer<BaseBlocks.LampBlockEntity, LampRenderer.State> {
		private static final float TILT = 18.0F;
		private static final BoxMesh FLOOD_BEAM = cone(0.2F, 2.4F, 11.0F, BEAM_HAZE);
		private static final BoxMesh FLOOD_CORE = cone(0.2F, 0.9F, 3.5F, BEAM_HAZE);
		private static final BoxMesh ALARM_BEAM = cone(0.08F, 1.3F, 4.5F, BEAM_HAZE);
		private static final BoxMesh REFLECTOR = new BoxMesh.Builder()
			.box(-0.12F, 0.22F, -0.03F, 0.12F, 0.42F, 0.03F, RED_LAMP)
			.box(-0.04F, 0.14F, -0.04F, 0.04F, 0.46F, 0.04F, YELLOW)
			.build();

		public LampRenderer(BlockEntityRendererProvider.Context context) {
		}

		public static class State extends BlockEntityRenderState {
			public boolean lit;
			public boolean alarm;
			public Direction facing = Direction.NORTH;
			public float time;
			public float dark;
		}

		@Override
		public State createRenderState() {
			return new State();
		}

		@Override
		public void extractRenderState(BaseBlocks.LampBlockEntity lamp, State state, float partialTick, Vec3 cameraPos,
			ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
			BlockEntityRenderer.super.extractRenderState(lamp, state, partialTick, cameraPos, overlay);
			BlockState s = lamp.getBlockState();
			state.lit = s.getBlock() instanceof BaseBlocks.Lamp && s.getValue(BaseBlocks.Lamp.LIT);
			state.alarm = s.getBlock() instanceof BaseBlocks.Lamp l && l.isAlarm();
			state.facing = s.hasProperty(BaseBlocks.Shaped.FACING) ? s.getValue(BaseBlocks.Shaped.FACING) : Direction.NORTH;
			Level level = lamp.getLevel();
			state.time = level == null ? 0.0F : (level.getGameTime() % 72000L) + partialTick;
			state.dark = darkness(level, partialTick);
		}

		@Override
		public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
			if (!state.lit) {
				return;
			}
			if (state.alarm) {
				this.alarm(state, poseStack, collector, camera);
			} else {
				this.flood(state, poseStack, collector, camera);
			}
		}

		/** A floodlight: a broad white spot tilted down at the ground, and its beam in the haze at night. */
		private void flood(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
			Direction f = state.facing;
			float tilt = TILT * Mth.DEG_TO_RAD;
			Vec3 lens = Vec3.atBottomCenterOf(state.blockPos).add(f.getStepX() * 0.3, 0.6, f.getStepZ() * 0.3);
			Vec3 dir = new Vec3(f.getStepX() * Mth.cos(tilt), -Mth.sin(tilt), f.getStepZ() * Mth.cos(tilt));
			DynamicLights.spot(state.blockPos.asLong() * 5L, lens.add(dir.scale(0.2)), dir, 34.0F, 0xFFF2DC, 1.0F, 44.0F);
			if (state.dark < 0.05F) {
				return;
			}
			poseStack.pushPose();
			poseStack.translate(0.5F + f.getStepX() * 0.3F, 0.6F, 0.5F + f.getStepZ() * 0.3F);
			poseStack.mulPose(new Quaternionf().rotationY(-f.toYRot() * Mth.DEG_TO_RAD));
			poseStack.mulPose(new Quaternionf().rotationX(tilt));
			int a = (int) (state.dark * 34.0F);
			int b = (int) (state.dark * 60.0F);
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE,
				(pose, consumer) -> FLOOD_BEAM.emit(pose, consumer, LightTexture.FULL_BRIGHT, a << 24 | 0xFFF0D8));
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE,
				(pose, consumer) -> FLOOD_CORE.emit(pose, consumer, LightTexture.FULL_BRIGHT, b << 24 | 0xFFF4E4));
			poseStack.popPose();
		}

		/** An alarm beacon: a mirror turning round the bulb, two red sweeping beams. */
		private void alarm(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
			float spin = state.time * 0.55F;
			Vec3 c = Vec3.atBottomCenterOf(state.blockPos).add(0.0, 0.32, 0.0);
			long key = state.blockPos.asLong() * 5L + 1L;
			for (int k = 0; k < 2; k++) {
				float a = spin + k * Mth.PI;
				Vec3 dir = new Vec3(Mth.sin(a), -0.18, Mth.cos(a));
				DynamicLights.spot(key + k, c.add(dir.scale(0.2)), dir, 24.0F, 0xFF2410, 1.1F, 26.0F);
			}
			DynamicLights.lamp(key + 2, c, 0xFF2010, 0.35F, 5.0F);
			poseStack.pushPose();
			poseStack.translate(0.5F, 0.0F, 0.5F);
			poseStack.mulPose(new Quaternionf().rotationY(spin));
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> REFLECTOR.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			int alpha = (int) (40.0F + state.dark * 80.0F);
			for (int k = 0; k < 2; k++) {
				poseStack.pushPose();
				poseStack.translate(0.0F, 0.32F, 0.0F);
				poseStack.mulPose(new Quaternionf().rotationY(k * Mth.PI));
				poseStack.mulPose(new Quaternionf().rotationX(0.18F));
				collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE,
					(pose, consumer) -> ALARM_BEAM.emit(pose, consumer, LightTexture.FULL_BRIGHT, alpha << 24 | 0xFF3018));
				poseStack.popPose();
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
	}

	// ------------------------------------------------------------------ the flag

	public static class FlagRenderer implements BlockEntityRenderer<BaseFlagBlockEntity, FlagRenderer.State> {
		private static final float POLE = 7.2F;
		private static final BoxMesh MAST = new BoxMesh.Builder()
			.box(-0.06F, 0.1F, -0.06F, 0.06F, POLE, 0.06F, STEEL)
			.box(-0.1F, POLE, -0.1F, 0.1F, POLE + 0.16F, 0.1F, YELLOW)
			.build();
		private static final int SEGMENTS = 12;
		private static final float LENGTH = 2.4F;
		private static final float HEIGHT = 1.5F;

		public FlagRenderer(BlockEntityRendererProvider.Context context) {
		}

		public static class State extends BlockEntityRenderState {
			public boolean captured;
			public float time;
		}

		@Override
		public State createRenderState() {
			return new State();
		}

		@Override
		public void extractRenderState(BaseFlagBlockEntity flag, State state, float partialTick, Vec3 cameraPos,
			ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
			BlockEntityRenderer.super.extractRenderState(flag, state, partialTick, cameraPos, overlay);
			BlockState s = flag.getBlockState();
			state.captured = s.hasProperty(BaseBlocks.Flag.CAPTURED) && s.getValue(BaseBlocks.Flag.CAPTURED);
			Level level = flag.getLevel();
			state.time = level == null ? 0.0F : (level.getGameTime() % 72000L) + partialTick;
		}

		@Override
		public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
			int light = state.lightCoords;
			poseStack.pushPose();
			poseStack.translate(0.5F, 0.0F, 0.5F);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> MAST.emit(pose, consumer, light));
			// the wind turns slowly; the cloth flies out from the pole and ripples
			float t = state.time;
			float wind = 0.6F + 0.4F * Mth.sin(t * 0.004F) + 0.15F * Mth.sin(t * 0.031F);
			poseStack.mulPose(new Quaternionf().rotationY(Mth.sin(t * 0.0013F) * 1.2F + 0.4F));
			BoxMesh.Builder b = new BoxMesh.Builder();
			int main = state.captured ? OLIVE : RED;
			int band = state.captured ? WHITE : BLACK;
			float top = POLE - 0.05F;
			float x0 = 0.06F;
			float z0 = 0.0F;
			float y0 = 0.0F;
			for (int i = 0; i < SEGMENTS; i++) {
				float u = (i + 1) / (float) SEGMENTS;
				float x1 = 0.06F + u * LENGTH;
				float z1 = Mth.sin(t * 0.32F * wind - u * 5.5F) * 0.22F * u * (0.6F + 0.4F * wind);
				float y1 = -u * u * 0.25F * (1.2F - wind);
				// three bands: main, band, main
				float hb = HEIGHT / 3.0F;
				b.hexa(main, corners(x0, z0, y0 + top - hb, x1, z1, y1 + top - hb, y0 + top, y1 + top));
				b.hexa(band, corners(x0, z0, y0 + top - 2 * hb, x1, z1, y1 + top - 2 * hb, y0 + top - hb, y1 + top - hb));
				b.hexa(main, corners(x0, z0, y0 + top - HEIGHT, x1, z1, y1 + top - HEIGHT, y0 + top - 2 * hb, y1 + top - 2 * hb));
				x0 = x1;
				z0 = z1;
				y0 = y1;
			}
			BoxMesh cloth = b.build();
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> cloth.emit(pose, consumer, light));
			poseStack.popPose();
		}

		/** A thin slab of cloth between two vertical edges (x, z, bottom .. top at each end). */
		private static Vector3f[] corners(float xa, float za, float ya0, float xb, float zb, float yb0, float ya1, float yb1) {
			float d = 0.012F;
			return new Vector3f[] {
				new Vector3f(xa, ya0, za - d), new Vector3f(xb, yb0, zb - d), new Vector3f(xb, yb1, zb - d), new Vector3f(xa, ya1, za - d),
				new Vector3f(xa, ya0, za + d), new Vector3f(xb, yb0, zb + d), new Vector3f(xb, yb1, zb + d), new Vector3f(xa, ya1, za + d)
			};
		}

		@Override
		public boolean shouldRenderOffScreen() {
			return true;
		}

		@Override
		public int getViewDistance() {
			return 256;
		}
	}
}
