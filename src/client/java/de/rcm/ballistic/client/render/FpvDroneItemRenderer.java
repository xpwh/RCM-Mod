package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** The FPV drones as held items: the full 3D drone, nose away from you, props still. */
public final class FpvDroneItemRenderer implements SpecialModelRenderer<Boolean> {
	/** How big the drone is in the hand, relative to its model. */
	public static final float HAND_SCALE = 0.8F;

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("fpv_drone"), Unbaked.MAP_CODEC);
	}

	@Override
	public void submit(Boolean racer, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil,
		int outline) {
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F);
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
		poseStack.scale(HAND_SCALE, HAND_SCALE, HAND_SCALE);
		FpvDroneRenderer.submitDrone(poseStack, collector, light, racer != null && racer, 0.0F, 0.0F);
		poseStack.popPose();
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0.3F, 0.38F, 0.3F));
		output.accept(new Vector3f(0.7F, 0.58F, 0.7F));
	}

	@Override
	public Boolean extractArgument(ItemStack stack) {
		return stack.is(ModRegistry.FPV_RACER_ITEM);
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
			return new FpvDroneItemRenderer();
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
