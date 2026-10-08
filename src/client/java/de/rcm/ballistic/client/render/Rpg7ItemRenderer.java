package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BAKELITE;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.LENS;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.WOOD;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.item.RocketLauncherItem;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The RPG-7 in the hand, as a real round model: the launch tube with its muzzle ring and the flared
 * venturi at the back, the laminated-wood heat shield with its clamp bands, the trigger housing with
 * the raked pistol grip, trigger and guard, the folding iron sights, and the PGO-7 optical sight on
 * the left. When a round is loaded the PG-7V's olive warhead sticks out of the muzzle.
 * <p>
 * Item space: origin at the hand on the pistol grip, -Z forward (the way the player aims), +Y up,
 * +X to the right; one unit is a block. Display transforms are in {@code models/item/rocket_launcher_in_hand.json}.
 */
public final class Rpg7ItemRenderer implements SpecialModelRenderer<Boolean> {
	/** Height of the tube's axis above the hand. */
	private static final float AXIS_Y = 0.11F;
	private static final float MUZZLE_Z = -0.36F;
	private static final float TUBE_R = 0.04F;
	static final BoxMesh LAUNCHER = launcher();
	static final BoxMesh WARHEAD = warhead();

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("rpg7"), Unbaked.MAP_CODEC);
	}

	@Override
	public void submit(Boolean loaded, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
		boolean withRound = Boolean.TRUE.equals(loaded);
		poseStack.pushPose();
		// the item transform leaves the origin at the model's corner (it shifts by -0.5 for 0..1
		// block-space models); this mesh is built around the hand, so move back to the centre
		poseStack.translate(0.5F, 0.5F, 0.5F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> {
			LAUNCHER.emit(pose, consumer, light);
			if (withRound) {
				WARHEAD.emit(pose, consumer, light);
			}
		});
		poseStack.popPose();
	}

	/** The PG-7V on its own, positioned as if seated in the muzzle (for the round in the reloading hand). */
	public static void submitWarhead(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> WARHEAD.emit(pose, consumer, light));
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0.4F, 0.4F, -0.35F));
		output.accept(new Vector3f(0.58F, 0.7F, 1.16F));
	}

	@Override
	public Boolean extractArgument(ItemStack stack) {
		return RocketLauncherItem.isLoaded(stack);
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
			return new Rpg7ItemRenderer();
		}

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}
	}

	// ------------------------------------------------------------------ geometry

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** Round section along the tube: profile distances are measured back from the muzzle. */
	private static void tube(BoxMesh.Builder b, int patch, float[][] profile, int segments) {
		b.revolve(patch, v(0, AXIS_Y, MUZZLE_Z), v(0, 0, 1), profile, segments);
	}

	private static BoxMesh launcher() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float r = TUBE_R;
		// launch tube: muzzle ring, barrel, heat shield seat, rear barrel, venturi and its flared bell
		tube(b, GUNMETAL, new float[][] {{0.0F, r + 0.008F}, {0.022F, r + 0.008F}}, 14);
		tube(b, GUNMETAL, new float[][] {{0.022F, r}, {0.42F, r}}, 14);
		tube(b, GUNMETAL, new float[][] {{0.66F, r}, {0.88F, r}, {0.92F, r + 0.01F}, {0.96F, r + 0.024F}, {1.0F, r + 0.038F}}, 14);
		tube(b, BLACK, new float[][] {{0.995F, r + 0.036F}, {1.0F, r + 0.038F}, {1.0F, r - 0.004F}, {0.97F, r - 0.004F}}, 14); // soot inside the bell
		// laminated birch heat shield where it rests on the shoulder, held by two clamp bands
		tube(b, WOOD, new float[][] {{0.40F, r + 0.005F}, {0.42F, r + 0.024F}, {0.64F, r + 0.024F}, {0.66F, r + 0.005F}}, 12);
		tube(b, GUNMETAL, new float[][] {{0.43F, r + 0.028F}, {0.445F, r + 0.028F}}, 12);
		tube(b, GUNMETAL, new float[][] {{0.615F, r + 0.028F}, {0.63F, r + 0.028F}}, 12);
		float top = AXIS_Y + r;
		// trigger mechanism housing under the tube, pistol grip raked back, trigger and guard
		b.box(-0.022F, AXIS_Y - r - 0.045F, -0.07F, 0.022F, AXIS_Y - r + 0.01F, 0.06F, GUNMETAL);
		b.beam(v(0, 0.035F, -0.004F), v(0, -0.1F, 0.04F), 0.036F, 0.05F, BAKELITE);
		b.box(-0.02F, -0.112F, 0.022F, 0.02F, -0.098F, 0.064F, BAKELITE); // grip butt
		b.beam(v(0, 0.035F, -0.056F), v(0, -0.018F, -0.05F), 0.01F, 0.008F, GUNMETAL);
		b.beam(v(0, -0.018F, -0.054F), v(0, -0.022F, -0.004F), 0.01F, 0.008F, GUNMETAL);
		b.beam(v(0, 0.03F, -0.026F), v(0, 0.002F, -0.018F), 0.008F, 0.01F, STEEL); // trigger
		b.box(-0.008F, AXIS_Y - r - 0.02F, 0.06F, 0.008F, AXIS_Y - r + 0.005F, 0.085F, STEEL); // hammer
		// folding iron sights: front post near the muzzle, rear leaf above the grip
		b.box(-0.012F, top - 0.004F, MUZZLE_Z + 0.04F, 0.012F, top + 0.012F, MUZZLE_Z + 0.07F, GUNMETAL);
		b.box(-0.003F, top + 0.012F, MUZZLE_Z + 0.05F, 0.003F, top + 0.055F, MUZZLE_Z + 0.057F, GUNMETAL);
		// rear leaf with its notch
		b.box(-0.016F, top - 0.004F, -0.03F, 0.016F, top + 0.05F, -0.022F, GUNMETAL);
		b.box(-0.016F, top + 0.05F, -0.03F, -0.004F, top + 0.062F, -0.022F, GUNMETAL);
		b.box(0.004F, top + 0.05F, -0.03F, 0.016F, top + 0.062F, -0.022F, GUNMETAL);
		// PGO-7 optical sight on the left: bracket, body, objective lens, rubber eye-cup
		float sx = -0.065F;
		float sy = AXIS_Y + 0.05F;
		b.box(-0.05F, AXIS_Y - 0.01F, -0.05F, -0.035F, AXIS_Y + 0.045F, 0.03F, GUNMETAL);
		b.box(sx - 0.022F, sy - 0.03F, -0.08F, sx + 0.022F, sy + 0.03F, 0.05F, GUNMETAL);
		b.box(sx - 0.01F, sy + 0.03F, -0.04F, sx + 0.01F, sy + 0.042F, -0.01F, BLACK); // range drum
		b.box(sx - 0.017F, sy - 0.022F, -0.086F, sx + 0.017F, sy + 0.022F, -0.08F, LENS);
		b.revolve(BLACK, v(sx, sy, 0.05F), v(0, 0, 1), new float[][] {{0.0F, 0.02F}, {0.05F, 0.026F}}, 10);
		return b.build();
	}

	/** The PG-7V's front end out of the muzzle: sustainer stub, adapter, warhead, ogive and nose probe. */
	private static BoxMesh warhead() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float[][] profile = {
			{-0.02F, 0.024F}, {0.13F, 0.024F}, {0.18F, 0.06F}, {0.2F, 0.068F}
		};
		Vector3f origin = v(0, AXIS_Y, MUZZLE_Z);
		Vector3f fwd = v(0, 0, -1);
		b.revolve(OLIVE_DARK, origin, fwd, new float[][] {profile[0], profile[1]}, 12);
		b.revolve(OLIVE, origin, fwd, new float[][] {profile[1], profile[2], profile[3], {0.27F, 0.068F}}, 16);
		b.revolve(BLACK, origin, fwd, new float[][] {{0.27F, 0.07F}, {0.285F, 0.07F}}, 16);
		b.revolve(OLIVE, origin, fwd, new float[][] {{0.285F, 0.068F}, {0.33F, 0.068F}, {0.37F, 0.056F}, {0.41F, 0.034F}, {0.435F, 0.016F}}, 16);
		b.revolve(STEEL, origin, fwd, new float[][] {{0.435F, 0.014F}, {0.5F, 0.01F}, {0.515F, 0.0F}}, 8);
		return b.build();
	}
}
