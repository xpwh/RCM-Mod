package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.AK_BLUED;
import static de.rcm.ballistic.client.render.StructureKit.AK_WORN;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.gun.GrenadeEntity;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The RGD-5: a smooth olive egg of thin steel with the UZRGM fuse screwed into its top - the fuse
 * body, the long lever (spoon) lying down the side, the safety pin through it with its ring. In the
 * hand it shows the ring until the pin is pulled; thrown, the lever has gone too.
 * <p>
 * Item space: origin at the middle of the grenade, +Y up.
 */
public final class GrenadeRenderers {
	/** Where the ring hangs (item space), for the hand that pulls it. */
	public static final Vector3f RING_AT = new Vector3f(0.05F, 0.07F, 0.0F);
	public static final BoxMesh BODY = body();
	public static final BoxMesh LEVER = lever();
	public static final BoxMesh RING = ring();

	private GrenadeRenderers() {
	}

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("grenade"), ItemRenderer.Unbaked.MAP_CODEC);
	}

	/** The pin and ring on their own (in the hand that pulled them). */
	public static void submitRing(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> RING.emit(pose, consumer, light));
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	private static BoxMesh body() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// the egg: 58 mm across, drawn a little larger than life so it reads in the hand
		b.revolve(OLIVE, v(0, -0.055F, 0), v(0, 1, 0), new float[][] {
			{0.0F, 0.0F}, {0.006F, 0.022F}, {0.02F, 0.037F}, {0.045F, 0.046F}, {0.07F, 0.044F}, {0.088F, 0.034F}, {0.1F, 0.016F}, {0.102F, 0.0F}}, 14);
		// a seam round the middle where the two halves were pressed together
		b.revolve(OLIVE_DARK, v(0, -0.012F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.0466F}, {0.004F, 0.0466F}}, 14);
		// fuse: threaded neck, fuse body, striker housing
		b.revolve(AK_BLUED, v(0, 0.04F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.011F}, {0.016F, 0.011F}}, 10);
		b.revolve(AK_BLUED, v(0, 0.056F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.015F}, {0.022F, 0.014F}, {0.03F, 0.009F}, {0.032F, 0.0F}}, 10);
		return b.build();
	}

	/** The spoon: from the striker over the top and down the side of the body. */
	private static BoxMesh lever() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f[] spine = {v(0, 0.086F, -0.004F), v(0, 0.082F, 0.012F), v(0, 0.066F, 0.022F), v(0, 0.04F, 0.044F), v(0, 0.01F, 0.05F), v(0, -0.022F, 0.047F)};
		float[] depth = new float[spine.length];
		float[] hw = new float[spine.length];
		java.util.Arrays.fill(depth, 0.004F);
		for (int i = 0; i < hw.length; i++) {
			hw[i] = 0.011F - i * 0.0008F;
		}
		AkItemRenderer.sweep(b, AK_WORN, spine, depth, hw, 0.001F, 0.0F);
		return b.build();
	}

	/** The safety pin through the fuse and its pull ring. */
	private static BoxMesh ring() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.beam(v(-0.018F, 0.07F, 0.0F), v(0.034F, 0.07F, 0.0F), 0.003F, 0.003F, STEEL);
		int n = 10;
		float r = 0.015F;
		for (int i = 0; i < n; i++) {
			float a0 = Mth.TWO_PI * i / n;
			float a1 = Mth.TWO_PI * (i + 1) / n;
			b.beam(v(RING_AT.x + Mth.sin(a0) * r * 0.3F, RING_AT.y - Mth.cos(a0) * r, RING_AT.z + Mth.sin(a0) * r),
				v(RING_AT.x + Mth.sin(a1) * r * 0.3F, RING_AT.y - Mth.cos(a1) * r, RING_AT.z + Mth.sin(a1) * r), 0.0028F, 0.0028F, STEEL);
		}
		return b.build();
	}

	/** In the hand: 0 = pin in, 1 = pin pulled (the local player winding up to throw this very stack). */
	public static final class ItemRenderer implements SpecialModelRenderer<Integer> {
		@Override
		public void submit(Integer state, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil,
			int outline) {
			boolean pinned = state == null || state == 0;
			poseStack.pushPose();
			poseStack.translate(0.5F, 0.5F, 0.5F);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> {
				BODY.emit(pose, consumer, light);
				LEVER.emit(pose, consumer, light);
				if (pinned) {
					RING.emit(pose, consumer, light);
				}
			});
			poseStack.popPose();
		}

		@Override
		public void getExtents(Consumer<Vector3fc> output) {
			output.accept(new Vector3f(0.44F, 0.44F, 0.44F));
			output.accept(new Vector3f(0.56F, 0.6F, 0.56F));
		}

		@Override
		public Integer extractArgument(ItemStack stack) {
			var player = Minecraft.getInstance().player;
			if (player != null && player.isUsingItem() && player.getUseItem() == stack && player.getTicksUsingItem() >= 5) {
				return 1;
			}
			return 0;
		}

		public record Unbaked() implements SpecialModelRenderer.Unbaked {
			public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

			@Override
			public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
				return new ItemRenderer();
			}

			@Override
			public MapCodec<Unbaked> type() {
				return MAP_CODEC;
			}
		}
	}

	/** Thrown: tumbling end over end through the air, rolling on the ground. */
	public static final class EntityRendererImpl extends EntityRenderer<GrenadeEntity, EntityRendererImpl.State> {
		public EntityRendererImpl(EntityRendererProvider.Context context) {
			super(context);
			this.shadowRadius = 0.08F;
		}

		public static class State extends EntityRenderState {
			public float spin;
			public float yaw;
		}

		@Override
		public State createRenderState() {
			return new State();
		}

		@Override
		public void extractRenderState(GrenadeEntity entity, State state, float partialTick) {
			super.extractRenderState(entity, state, partialTick);
			state.spin = Mth.lerp(partialTick, entity.prevSpin, entity.spin);
			state.yaw = (entity.getId() * 37) % 360;
		}

		@Override
		public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
			poseStack.pushPose();
			poseStack.translate(0.0F, 0.05F, 0.0F);
			poseStack.mulPose(Axis.YP.rotationDegrees(state.yaw));
			poseStack.mulPose(Axis.XP.rotationDegrees(state.spin));
			int light = state.lightCoords;
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> BODY.emit(pose, consumer, light));
			poseStack.popPose();
			super.submit(state, poseStack, collector, camera);
		}
	}
}
