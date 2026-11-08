package de.rcm.ballistic.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import de.rcm.ballistic.BallisticMissiles;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** The karambit as an item everywhere but your own first-person view: the real 3D knife, fist round the handle. */
public final class KarambitItemRenderer implements SpecialModelRenderer<Void> {
	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("karambit"), Unbaked.MAP_CODEC);
	}

	@Override
	public void submit(Void unused, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil,
		int outline) {
		poseStack.pushPose();
		// the grip at the middle of the item's block, so a hand holding it holds the handle
		poseStack.translate(0.5F - KarambitModel.GRIP.x, 0.5F - KarambitModel.GRIP.y, 0.5F - KarambitModel.GRIP.z);
		KarambitModel.submit(poseStack, collector, light);
		poseStack.popPose();
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0.42F, 0.38F, 0.45F));
		output.accept(new Vector3f(0.58F, 0.62F, 0.65F));
	}

	@Override
	public Void extractArgument(ItemStack stack) {
		return null;
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
			return new KarambitItemRenderer();
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}
}
