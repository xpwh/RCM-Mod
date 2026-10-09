package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.AK_BRIGHT;
import static de.rcm.ballistic.client.render.StructureKit.AK_WORN;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.BRASS;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.SG_BORE;
import static de.rcm.ballistic.client.render.StructureKit.SG_CHECKER;
import static de.rcm.ballistic.client.render.StructureKit.SG_HULL;
import static de.rcm.ballistic.client.render.StructureKit.SG_PAD;
import static de.rcm.ballistic.client.render.StructureKit.SG_PARK;
import static de.rcm.ballistic.client.render.StructureKit.SG_WALNUT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
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
 * The pump-action shotgun (Remington 870 pattern), at its real size: the parkerized steel receiver with
 * its ejection port - the bolt inside it riding back with the action, a fresh shell showing on the lifter
 * when it is open, the empty hull spinning out - and the loading port with its shell lifter underneath;
 * the trigger plate with guard, trigger and cross-bolt safety; the 18.5 in. barrel on a ventilated rib
 * with the brass bead; the magazine tube with its knurled cap and sling swivel; the ribbed walnut forend
 * on its twin action bars, sliding back and forward when the gun is pumped; and the walnut stock, checkered
 * at the wrist, with its spacer and ribbed rubber recoil pad. And a 12-gauge shell for the loading hand.
 * <p>
 * Item space as the AK's: origin at the right hand, -Z forward, +Y up, one unit a block (a metre), so the
 * same display transform and arms fit.
 */
public final class ShotgunItemRenderer implements SpecialModelRenderer<ShotgunItemRenderer.Pose> {
	/** Height of the bore above the hand, and where the receiver ends and the muzzle is. */
	public static final float BORE_Y = 0.135F;
	public static final float RECEIVER_FRONT = -0.17F;
	public static final float MUZZLE_Z = -0.70F;
	/** Top of the receiver's sighting groove and of the bead at the muzzle: the line of sight. */
	public static final float SIGHT_REAR_Y = 0.158F;
	public static final float BEAD_Y = 0.1625F;
	/** How far the forend (and the bolt with it) travels back when the gun is pumped. */
	public static final float PUMP_TRAVEL = 0.085F;
	private static final float W = 0.0205F;
	private static final float MAG_Y = 0.098F;
	/** The ejection port on the right of the receiver. */
	private static final float PORT_FRONT = -0.138F;
	private static final float PORT_REAR = -0.052F;
	private static final float SHELL_X = W - 0.008F;
	static final BoxMesh GUN = gun();
	static final BoxMesh FOREND = forend();
	static final BoxMesh BOLT = bolt();
	static final BoxMesh SHELL = shell(true);
	static final BoxMesh SPENT = shell(false);

	/**
	 * What moves: how far back the action is (0..1), whether a shell is waiting to be chambered, and how
	 * many ticks ago the empty hull left the port (negative when none is in the air).
	 */
	public record Pose(float pump, boolean loaded, float eject) {
		public static final Pose REST = new Pose(0.0F, false, -1.0F);
	}

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("shotgun"), Unbaked.MAP_CODEC);
	}

	@Override
	public void submit(Pose pose, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil,
		int outline) {
		Pose p = pose == null ? Pose.REST : pose;
		float back = p.pump();
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pp, consumer) -> GUN.emit(pp, consumer, light));
		// the bolt in the ejection port: its face rides back with the action, uncovering the chamber
		float face = PORT_FRONT + back * PUMP_TRAVEL;
		float length = PORT_REAR - face;
		if (length > 0.002F) {
			poseStack.pushPose();
			poseStack.translate(0.0F, 0.0F, face);
			poseStack.scale(1.0F, 1.0F, length);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pp, consumer) -> BOLT.emit(pp, consumer, light));
			poseStack.popPose();
		}
		// the action open: the next shell lies on the lifter, ready to be pushed into the chamber
		if (p.loaded() && back > 0.85F) {
			poseStack.pushPose();
			poseStack.translate(SHELL_X, BORE_Y - 0.004F, PORT_REAR - 0.004F);
			submitShell(poseStack, collector, light);
			poseStack.popPose();
		}
		// the empty hull flicked out of the port, tumbling away to the right
		float t = p.eject();
		if (t >= 0.0F && t < 7.0F) {
			poseStack.pushPose();
			poseStack.translate(SHELL_X + 0.11F * t, BORE_Y + 0.05F * t - 0.012F * t * t, -0.06F + 0.012F * t);
			poseStack.mulPose(Axis.YP.rotationDegrees(-75.0F * t));
			poseStack.mulPose(Axis.XP.rotationDegrees(25.0F * t));
			poseStack.translate(0.0F, 0.0F, 0.032F);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pp, consumer) -> SPENT.emit(pp, consumer, light));
			poseStack.popPose();
		}
		poseStack.translate(0.0F, 0.0F, back * PUMP_TRAVEL);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pp, consumer) -> FOREND.emit(pp, consumer, light));
		poseStack.popPose();
	}

	/** A loaded shell on its own, its base at the origin, pointing along -Z (for the hand loading it). */
	public static void submitShell(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> SHELL.emit(pose, consumer, light));
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0.4F, 0.4F, -0.25F));
		output.accept(new Vector3f(0.6F, 0.7F, 1.0F));
	}

	@Override
	public Pose extractArgument(ItemStack stack) {
		return ShotgunClient.poseFor(stack);
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

	/** Corners of a rounded-off section (an octagon) of the stock: bottom and top half-widths, chamfered corners. */
	private static Vector3f[] section(float z, float yb, float yt, float hwb, float hwt) {
		float kb = hwb * 0.55F;
		float kt = hwt * 0.55F;
		return new Vector3f[] {
			v(hwb - kb, yb, z), v(hwb, yb + kb, z), v(hwt, yt - kt, z), v(hwt - kt, yt, z),
			v(-(hwt - kt), yt, z), v(-hwt, yt - kt, z), v(-hwb, yb + kb, z), v(-(hwb - kb), yb, z)
		};
	}

	/** A solid lofted between two octagonal sections: a wedge from the centre line out to each edge. */
	private static void loft(BoxMesh.Builder b, int patch, Vector3f[] a, Vector3f[] c) {
		Vector3f ca = new Vector3f();
		Vector3f cc = new Vector3f();
		for (int i = 0; i < 8; i++) {
			ca.add(a[i]);
			cc.add(c[i]);
		}
		ca.div(8.0F);
		cc.div(8.0F);
		for (int i = 0; i < 8; i++) {
			int j = (i + 1) % 8;
			b.hexa(patch, a[i], a[j], new Vector3f(ca), new Vector3f(ca), c[i], c[j], new Vector3f(cc), new Vector3f(cc));
		}
	}

	private static BoxMesh gun() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float rf = RECEIVER_FRONT;
		// ===== receiver: a milled steel box with flat sides, the top edges rounded off, sloping down at the back
		b.box(-W, 0.086F, rf, W, 0.149F, 0.04F, SG_PARK);
		b.hexa(SG_PARK,
			v(-W, 0.149F, rf), v(W, 0.149F, rf), v(W * 0.7F, SIGHT_REAR_Y, rf), v(-W * 0.7F, SIGHT_REAR_Y, rf),
			v(-W, 0.149F, 0.04F), v(W, 0.149F, 0.04F), v(W * 0.7F, SIGHT_REAR_Y, 0.04F), v(-W * 0.7F, SIGHT_REAR_Y, 0.04F));
		b.hexa(SG_PARK,
			v(-W, 0.086F, 0.04F), v(W, 0.086F, 0.04F), v(W * 0.8F, SIGHT_REAR_Y, 0.04F), v(-W * 0.8F, SIGHT_REAR_Y, 0.04F),
			v(-W * 0.95F, 0.089F, 0.064F), v(W * 0.95F, 0.089F, 0.064F), v(W * 0.75F, 0.147F, 0.064F), v(-W * 0.75F, 0.147F, 0.064F));
		// the matte groove along the top that the eye lines up with the bead
		b.box(-0.0032F, SIGHT_REAR_Y - 0.0025F, rf + 0.008F, 0.0032F, SIGHT_REAR_Y + 0.0006F, 0.036F, BLACK);
		// ejection port on the right (the bolt and the shell are drawn on their own)
		b.box(W - 0.0004F, 0.111F, PORT_FRONT, W + 0.0006F, 0.146F, PORT_REAR, SG_BORE);
		b.box(W - 0.0002F, 0.1105F, PORT_FRONT - 0.002F, W + 0.0008F, 0.1115F, PORT_REAR + 0.002F, AK_WORN); // the port's worn lower edge
		// loading port underneath, the shell lifter just inside it
		b.box(-0.0155F, 0.0848F, -0.135F, 0.0155F, 0.0862F, -0.024F, SG_BORE);
		b.box(-0.011F, 0.0856F, -0.116F, 0.011F, 0.0868F, -0.04F, STEEL);
		// receiver pins, both sides
		for (float z : new float[] {-0.008F, 0.03F}) {
			b.box(-W - 0.0008F, 0.091F, z - 0.0035F, W + 0.0008F, 0.098F, z + 0.0035F, AK_WORN);
		}
		// barrel extension ring proud of the receiver front
		along(b, SG_PARK, BORE_Y, rf + 0.004F, new float[][] {{0.0F, 0.0158F}, {0.024F, 0.0158F}, {0.028F, 0.013F}}, 14);
		// ===== trigger plate, guard, trigger, cross-bolt safety, action lock lever
		b.box(-0.0115F, 0.071F, -0.056F, 0.0115F, 0.0862F, 0.05F, SG_PARK);
		b.beam(v(0, 0.073F, -0.036F), v(0, 0.041F, -0.026F), 0.0095F, 0.0055F, SG_PARK);
		b.beam(v(0, 0.041F, -0.026F), v(0, 0.037F, 0.008F), 0.0095F, 0.0055F, SG_PARK);
		b.beam(v(0, 0.037F, 0.008F), v(0, 0.044F, 0.03F), 0.0095F, 0.0055F, SG_PARK);
		b.beam(v(0, 0.044F, 0.03F), v(0, 0.072F, 0.042F), 0.0095F, 0.0055F, SG_PARK);
		b.beam(v(0, 0.072F, -0.008F), v(0, 0.058F, -0.006F), 0.0055F, 0.0045F, AK_BRIGHT);
		b.beam(v(0, 0.058F, -0.006F), v(0, 0.048F, 0.003F), 0.0055F, 0.0045F, AK_BRIGHT);
		b.cylinderX(0.0F, 0.0785F, 0.037F, 0.0036F, 0.027F, 8, BLACK, RED);
		b.box(-0.0135F, 0.0745F, -0.05F, -0.0115F, 0.0805F, -0.034F, STEEL);
		// ===== barrel: 18.5 in., a dark crown and bore at the muzzle
		along(b, SG_PARK, BORE_Y, rf - 0.024F, new float[][] {{0.0F, 0.0128F}, {0.04F, 0.0124F}, {rf - MUZZLE_Z - 0.026F, 0.0116F},
			{rf - MUZZLE_Z - 0.024F, 0.0108F}}, 14);
		b.revolve(SG_BORE, v(0, BORE_Y, MUZZLE_Z + 0.0005F), v(0, 0, 1), new float[][] {{0.0F, 0.0094F}, {0.02F, 0.0094F}, {0.02F, 0.0F}}, 12);
		// ventilated rib: posts and a flat matte top, the brass bead at its end
		float ribY = BORE_Y + 0.0116F;
		b.box(-0.0042F, ribY + 0.0055F, rf + 0.006F, 0.0042F, ribY + 0.0082F, MUZZLE_Z + 0.008F, SG_PARK);
		b.box(-0.0028F, ribY + 0.0081F, rf + 0.006F, 0.0028F, ribY + 0.0083F, MUZZLE_Z + 0.008F, BLACK);
		for (float z = rf - 0.02F; z > MUZZLE_Z + 0.02F; z -= 0.042F) {
			b.box(-0.0025F, ribY - 0.0015F, z - 0.011F, 0.0025F, ribY + 0.0056F, z - 0.004F, SG_PARK);
		}
		b.revolve(BRASS, v(0, ribY + 0.0082F, MUZZLE_Z + 0.014F), v(0, 1, 0), new float[][] {{0.0F, 0.0032F}, {0.0038F, 0.0036F}, {0.0072F, 0.0F}}, 8);
		// ===== magazine tube under the barrel, its knurled cap with the sling swivel, the barrel hanger on it
		along(b, SG_PARK, MAG_Y, rf, new float[][] {{0.0F, 0.0118F}, {0.47F, 0.0118F}}, 12);
		along(b, AK_WORN, MAG_Y, rf - 0.47F, new float[][] {{0.0F, 0.0136F}, {0.004F, 0.0142F}, {0.008F, 0.0136F}, {0.012F, 0.0142F}, {0.016F, 0.0136F},
			{0.02F, 0.0142F}, {0.024F, 0.0122F}, {0.028F, 0.0F}}, 12);
		b.beam(v(0, MAG_Y - 0.0136F, rf - 0.482F), v(0, MAG_Y - 0.026F, rf - 0.482F), 0.003F, 0.003F, STEEL);
		b.beam(v(0, MAG_Y - 0.026F, rf - 0.482F), v(0, MAG_Y - 0.026F, rf - 0.462F), 0.003F, 0.003F, STEEL);
		b.beam(v(0, MAG_Y - 0.026F, rf - 0.462F), v(0, MAG_Y - 0.0136F, rf - 0.462F), 0.003F, 0.003F, STEEL);
		b.hexa(SG_PARK,
			v(-0.0105F, MAG_Y + 0.006F, rf - 0.462F), v(0.0105F, MAG_Y + 0.006F, rf - 0.462F), v(0.0075F, BORE_Y - 0.008F, rf - 0.462F), v(-0.0075F, BORE_Y - 0.008F, rf - 0.462F),
			v(-0.0105F, MAG_Y + 0.006F, rf - 0.444F), v(0.0105F, MAG_Y + 0.006F, rf - 0.444F), v(0.0075F, BORE_Y - 0.008F, rf - 0.444F), v(-0.0075F, BORE_Y - 0.008F, rf - 0.444F));
		// ===== stock: tang into the receiver, the checkered wrist, the comb, the long butt; spacer and recoil pad
		float[][] stock = {
			{0.062F, 0.087F, 0.150F, 0.0190F, 0.0160F},
			{0.090F, 0.068F, 0.142F, 0.0165F, 0.0150F},
			{0.125F, 0.044F, 0.134F, 0.0160F, 0.0150F},
			{0.165F, 0.022F, 0.128F, 0.0180F, 0.0160F},
			{0.230F, 0.002F, 0.124F, 0.0200F, 0.0170F},
			{0.310F, -0.020F, 0.116F, 0.0215F, 0.0180F},
			{0.390F, -0.036F, 0.108F, 0.0220F, 0.0185F}};
		for (int i = 0; i + 1 < stock.length; i++) {
			float[] s0 = stock[i];
			float[] s1 = stock[i + 1];
			loft(b, i == 1 || i == 2 ? SG_CHECKER : SG_WALNUT, section(s0[0], s0[1], s0[2], s0[3], s0[4]), section(s1[0], s1[1], s1[2], s1[3], s1[4]));
		}
		float[] butt = stock[stock.length - 1];
		Vector3f[] end = section(butt[0], butt[1], butt[2], butt[3], butt[4]);
		Vector3f[] spacer = section(butt[0] + 0.003F, butt[1] - 0.0005F, butt[2] + 0.0005F, butt[3] + 0.0005F, butt[4] + 0.0005F);
		Vector3f[] padStart = section(butt[0] + 0.003F, butt[1] - 0.001F, butt[2] + 0.001F, butt[3] + 0.001F, butt[4] + 0.001F);
		Vector3f[] padEnd = section(butt[0] + 0.024F, butt[1] - 0.003F, butt[2] + 0.002F, butt[3] + 0.0015F, butt[4] + 0.0015F);
		loft(b, WHITE, end, spacer);
		loft(b, SG_PAD, padStart, padEnd);
		// sling swivel stud under the butt
		b.box(-0.0028F, -0.03F, 0.326F, 0.0028F, -0.018F, 0.334F, AK_WORN);
		return b.build();
	}

	/** The forend: ribbed walnut round the magazine tube on its steel sleeve, two action bars running back into the receiver. */
	private static BoxMesh forend() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float z0 = -0.322F;
		float length = 0.183F;
		// the classic ribbed forend: a ring of grooves along its length, tapered at both ends
		java.util.List<float[]> profile = new java.util.ArrayList<>();
		profile.add(new float[] {0.0F, 0.0185F});
		profile.add(new float[] {0.012F, 0.026F});
		boolean rib = true;
		for (float t = 0.024F; t < length - 0.016F; t += 0.0115F) {
			profile.add(new float[] {t - 0.0035F, rib ? 0.026F : 0.0238F});
			profile.add(new float[] {t, rib ? 0.0238F : 0.026F});
			rib = !rib;
		}
		profile.add(new float[] {length - 0.012F, 0.026F});
		profile.add(new float[] {length, 0.0195F});
		b.revolve(SG_WALNUT, v(0, MAG_Y, z0), v(0, 0, -1), profile.toArray(new float[0][]), 14);
		// steel sleeve showing at the back of the forend
		b.revolve(AK_WORN, v(0, MAG_Y, z0 + 0.008F), v(0, 0, -1), new float[][] {{0.0F, 0.0134F}, {0.01F, 0.0134F}}, 12);
		// action bars, flat steel either side of the tube, into the receiver
		for (float sx : new float[] {-1.0F, 1.0F}) {
			b.box(sx < 0 ? -0.0185F : 0.0165F, MAG_Y - 0.004F, z0 + 0.004F, sx < 0 ? -0.0165F : 0.0185F, MAG_Y + 0.004F, RECEIVER_FRONT + 0.012F, STEEL);
		}
		return b.build();
	}

	/** The bolt's visible side in the port: one unit long in Z (scaled to what shows), its face at z = 0. */
	private static BoxMesh bolt() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(W - 0.004F, 0.1125F, 0.0F, W + 0.0012F, 0.1445F, 1.0F, AK_BRIGHT);
		b.box(W + 0.0011F, 0.1265F, 0.0F, W + 0.0015F, 0.1285F, 1.0F, BLACK); // the groove along the bolt
		return b.build();
	}

	/** A 12-gauge shell: brass head with its rim, red ribbed hull, the star crimp closing it - or, fired, open. */
	private static BoxMesh shell(boolean loaded) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(BRASS, v(0, 0, 0), v(0, 0, -1), new float[][] {{0.0F, 0.0F}, {0.0003F, 0.0111F}, {0.002F, 0.0111F}, {0.0028F, 0.0101F},
			{0.016F, 0.0101F}}, 12);
		if (loaded) {
			b.revolve(SG_HULL, v(0, 0, -0.016F), v(0, 0, -1), new float[][] {{0.0F, 0.0100F}, {0.046F, 0.0100F}, {0.049F, 0.0082F}, {0.0505F, 0.0F}}, 12);
		} else {
			b.revolve(SG_HULL, v(0, 0, -0.016F), v(0, 0, -1), new float[][] {{0.0F, 0.0100F}, {0.054F, 0.0102F}}, 12);
			b.revolve(SG_BORE, v(0, 0, -0.069F), v(0, 0, 1), new float[][] {{0.0F, 0.0088F}, {0.045F, 0.0088F}, {0.045F, 0.0F}}, 10);
		}
		return b.build();
	}
}
