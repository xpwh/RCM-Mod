package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.AK_BLUED;
import static de.rcm.ballistic.client.render.StructureKit.AK_WORN;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.BRASS;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.WOOD;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.gun.ShotgunClient;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The pump-action shotgun (Remington 870 pattern), at its real size: the steel receiver with its ejection
 * port and the loading port underneath, the 18.5 in. barrel on its ventilated rib with the brass bead,
 * the magazine tube under it with its cap and the barrel clamp, the grooved walnut forend on its twin
 * action bars - a separate part, so it slides back and forward when the gun is pumped - the trigger
 * group, and the walnut stock with its black recoil pad. And a 12-gauge shell for the loading hand.
 * <p>
 * Item space as the AK's: origin at the right hand, -Z forward, +Y up, one unit a block, so the same
 * display transform and arms fit.
 */
public final class ShotgunItemRenderer implements SpecialModelRenderer<Float> {
	/** Height of the bore above the hand, and where the receiver ends and the muzzle is. */
	public static final float BORE_Y = 0.135F;
	public static final float RECEIVER_FRONT = -0.17F;
	public static final float MUZZLE_Z = -0.70F;
	/** Top of the rib at the receiver and of the bead at the muzzle: the line of sight. */
	public static final float SIGHT_REAR_Y = 0.158F;
	public static final float BEAD_Y = 0.161F;
	/** How far the forend travels back when the gun is pumped. */
	public static final float PUMP_TRAVEL = 0.085F;
	static final BoxMesh GUN = gun();
	static final BoxMesh FOREND = forend();
	static final BoxMesh SHELL = shell();

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("shotgun"), Unbaked.MAP_CODEC);
	}

	@Override
	public void submit(Float pump, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil,
		int outline) {
		float back = pump == null ? 0.0F : pump;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> GUN.emit(pose, consumer, light));
		poseStack.translate(0.0F, 0.0F, back * PUMP_TRAVEL);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> FOREND.emit(pose, consumer, light));
		poseStack.popPose();
	}

	/** A shell on its own, its base at the origin, pointing along -Z (for the hand loading it). */
	public static void submitShell(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> SHELL.emit(pose, consumer, light));
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0.4F, 0.4F, -0.25F));
		output.accept(new Vector3f(0.6F, 0.7F, 1.0F));
	}

	@Override
	public Float extractArgument(ItemStack stack) {
		return ShotgunClient.pumpFor(stack);
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
			return new ShotgunItemRenderer();
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

	private static void along(BoxMesh.Builder b, int patch, float y, float z0, float[][] profile, int segments) {
		b.revolve(patch, v(0, y, z0), v(0, 0, -1), profile, segments);
	}

	private static BoxMesh gun() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float w = 0.021F;
		float rf = RECEIVER_FRONT;
		// receiver: a milled steel box, rounded a little at the top, flat sides
		b.box(-w, 0.085F, rf, w, 0.15F, 0.062F, AK_BLUED);
		b.hexa(AK_BLUED,
			v(-w, 0.15F, rf), v(w, 0.15F, rf), v(w * 0.7F, SIGHT_REAR_Y, rf), v(-w * 0.7F, SIGHT_REAR_Y, rf),
			v(-w, 0.15F, 0.062F), v(w, 0.15F, 0.062F), v(w * 0.7F, SIGHT_REAR_Y - 0.004F, 0.062F), v(-w * 0.7F, SIGHT_REAR_Y - 0.004F, 0.062F));
		// the groove along the top that the eye lines up with the bead
		b.box(-0.0035F, SIGHT_REAR_Y - 0.003F, rf + 0.01F, 0.0035F, SIGHT_REAR_Y + 0.0005F, 0.05F, BLACK);
		// ejection port on the right, the shell carrier showing; loading port underneath
		b.box(w - 0.0005F, 0.113F, -0.135F, w + 0.001F, 0.146F, -0.055F, BLACK);
		b.box(-0.016F, 0.0835F, -0.13F, 0.016F, 0.0855F, -0.02F, BLACK);
		b.box(-0.012F, 0.0838F, -0.12F, 0.012F, 0.0858F, -0.035F, STEEL); // the shell lifter, just inside
		// bolt release and the trigger-plate pins
		b.box(-w - 0.002F, 0.088F, -0.03F, -w, 0.096F, -0.018F, STEEL);
		for (float z : new float[] {-0.01F, 0.035F}) {
			b.box(-w - 0.0008F, 0.095F, z - 0.004F, w + 0.0008F, 0.103F, z + 0.004F, AK_WORN);
		}
		// trigger group: guard, trigger, safety button behind it
		b.box(-0.01F, 0.072F, -0.05F, 0.01F, 0.085F, 0.045F, AK_BLUED);
		b.beam(v(0, 0.075F, -0.04F), v(0, 0.035F, -0.03F), 0.011F, 0.006F, AK_BLUED);
		b.beam(v(0, 0.035F, -0.03F), v(0, 0.033F, 0.03F), 0.011F, 0.006F, AK_BLUED);
		b.beam(v(0, 0.033F, 0.03F), v(0, 0.072F, 0.04F), 0.011F, 0.006F, AK_BLUED);
		b.beam(v(0, 0.075F, -0.008F), v(0, 0.048F, 0.0F), 0.006F, 0.008F, STEEL);
		b.box(-0.012F, 0.078F, 0.03F, 0.012F, 0.086F, 0.04F, BLACK);
		// barrel: 18.5 in., a little proud of the receiver, the muzzle's dark bore
		along(b, AK_BLUED, BORE_Y, rf, new float[][] {{0.0F, 0.0145F}, {0.03F, 0.0145F}, {0.034F, 0.0122F}, {-MUZZLE_Z + rf, 0.0118F}}, 14);
		b.revolve(BLACK, v(0, BORE_Y, MUZZLE_Z + 0.001F), v(0, 0, 1), new float[][] {{0.0F, 0.0094F}, {0.012F, 0.0094F}, {0.012F, 0.0F}}, 12);
		// ventilated rib on top of the barrel: posts and a flat top, the brass bead at its end
		float ribY = BORE_Y + 0.0118F;
		b.box(-0.0042F, ribY + 0.0055F, rf + 0.02F, 0.0042F, ribY + 0.0085F, MUZZLE_Z + 0.01F, AK_BLUED);
		for (float z = rf; z > MUZZLE_Z + 0.02F; z -= 0.045F) {
			b.box(-0.0025F, ribY - 0.001F, z - 0.012F, 0.0025F, ribY + 0.0056F, z - 0.004F, AK_BLUED);
		}
		b.revolve(BRASS, v(0, ribY + 0.0085F, MUZZLE_Z + 0.016F), v(0, 1, 0), new float[][] {{0.0F, 0.0032F}, {0.0035F, 0.0035F}, {0.007F, 0.0F}}, 8);
		// magazine tube under the barrel, its cap, and the clamp that ties barrel and tube together
		float magY = 0.098F;
		along(b, AK_BLUED, magY, rf, new float[][] {{0.0F, 0.0118F}, {0.47F, 0.0118F}}, 12);
		along(b, AK_WORN, magY, rf - 0.47F, new float[][] {{0.0F, 0.0135F}, {0.022F, 0.0135F}, {0.026F, 0.011F}, {0.03F, 0.0F}}, 12);
		b.box(-0.0135F, magY - 0.004F, rf - 0.455F, 0.0135F, BORE_Y + 0.006F, rf - 0.44F, AK_BLUED);
		// stock: wrist down from the receiver, the comb, the long walnut butt and its black recoil pad
		b.hexa(WOOD,
			v(-0.02F, 0.088F, 0.062F), v(0.02F, 0.088F, 0.062F), v(0.019F, 0.155F, 0.062F), v(-0.019F, 0.155F, 0.062F),
			v(-0.019F, 0.03F, 0.15F), v(0.019F, 0.03F, 0.15F), v(0.018F, 0.128F, 0.15F), v(-0.018F, 0.128F, 0.15F));
		b.hexa(WOOD,
			v(-0.019F, 0.03F, 0.15F), v(0.019F, 0.03F, 0.15F), v(0.018F, 0.128F, 0.15F), v(-0.018F, 0.128F, 0.15F),
			v(-0.022F, -0.035F, 0.43F), v(0.022F, -0.035F, 0.43F), v(0.021F, 0.12F, 0.43F), v(-0.021F, 0.12F, 0.43F));
		b.hexa(BLACK,
			v(-0.022F, -0.035F, 0.43F), v(0.022F, -0.035F, 0.43F), v(0.021F, 0.12F, 0.43F), v(-0.021F, 0.12F, 0.43F),
			v(-0.023F, -0.04F, 0.452F), v(0.023F, -0.04F, 0.452F), v(0.022F, 0.124F, 0.452F), v(-0.022F, 0.124F, 0.452F));
		// sling swivel stud under the butt
		b.box(-0.003F, -0.006F, 0.33F, 0.003F, 0.01F, 0.338F, AK_WORN);
		return b.build();
	}

	/** The forend: grooved walnut round the magazine tube, on two action bars running back into the receiver. */
	private static BoxMesh forend() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float magY = 0.098F;
		float z0 = -0.325F;
		float z1 = -0.505F;
		b.hexa(WOOD,
			v(-0.022F, magY - 0.026F, z0), v(0.022F, magY - 0.026F, z0), v(0.026F, magY + 0.02F, z0), v(-0.026F, magY + 0.02F, z0),
			v(-0.022F, magY - 0.026F, z1), v(0.022F, magY - 0.026F, z1), v(0.026F, magY + 0.02F, z1), v(-0.026F, magY + 0.02F, z1));
		// the grip grooves round it
		for (float z = z0 - 0.02F; z > z1 + 0.015F; z -= 0.016F) {
			b.box(-0.0265F, magY - 0.022F, z - 0.004F, -0.0255F, magY + 0.016F, z, BLACK);
			b.box(0.0255F, magY - 0.022F, z - 0.004F, 0.0265F, magY + 0.016F, z, BLACK);
			b.box(-0.02F, magY - 0.0268F, z - 0.004F, 0.02F, magY - 0.0258F, z, BLACK);
		}
		// action bars, flat steel either side of the tube, into the receiver
		for (float sx : new float[] {-1.0F, 1.0F}) {
			b.box(sx < 0 ? -0.0185F : 0.0165F, magY - 0.004F, z0 + 0.002F, sx < 0 ? -0.0165F : 0.0185F, magY + 0.004F, RECEIVER_FRONT - 0.002F, STEEL);
		}
		return b.build();
	}

	/** A 12-gauge shell: red plastic hull with its crimped end, brass head and its rim. */
	private static BoxMesh shell() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(BRASS, v(0, 0, 0), v(0, 0, -1), new float[][] {{0.0F, 0.0112F}, {0.002F, 0.0112F}, {0.003F, 0.0102F}, {0.015F, 0.0102F}}, 10);
		b.revolve(RED, v(0, 0, -0.015F), v(0, 0, -1), new float[][] {{0.0F, 0.0102F}, {0.052F, 0.0102F}, {0.056F, 0.006F}, {0.057F, 0.0F}}, 10);
		return b.build();
	}
}
