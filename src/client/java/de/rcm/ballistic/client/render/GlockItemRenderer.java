package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.AK_BRIGHT;
import static de.rcm.ballistic.client.render.StructureKit.AK_WORN;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.BRASS;
import static de.rcm.ballistic.client.render.StructureKit.GL_POLY;
import static de.rcm.ballistic.client.render.StructureKit.SG_BORE;
import static de.rcm.ballistic.client.render.StructureKit.SG_PARK;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.serialization.MapCodec;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.gun.GlockClient;
import de.rcm.ballistic.gun.GlockMagazineItem;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The Glock 19, at its real size (185 mm long, the slide 25.5 mm wide): the black nitrided slide with its
 * serrations front and rear, the ejection port on the right showing the barrel hood and - with the slide back
 * - the open chamber, the low U-notch rear sight and the front post with their white dots; the polymer frame
 * with the accessory rail under the dust cover, the square-fronted trigger guard and the safe-action trigger,
 * slide stop, takedown lever and magazine release; the grip at its 22 degrees, stippled, with the beavertail.
 * The magazine is drawn on its own: the flush 15-rounder or the long 33-round extended one, a round showing at
 * the lips.
 * <p>
 * Item space as the other guns': -Z forward, +Y up, one unit a metre; the origin is the middle of the grip,
 * where the shooting hand holds it.
 */
public final class GlockItemRenderer implements SpecialModelRenderer<GlockItemRenderer.Pose> {
	public static final float SLIDE_BOTTOM = 0.040F;
	public static final float SLIDE_TOP = 0.072F;
	public static final float SLIDE_REAR = 0.034F;
	public static final float SLIDE_FRONT = -0.152F;
	public static final float BORE_Y = 0.058F;
	/** How far the slide runs back when it cycles (and stays when locked open). */
	public static final float SLIDE_TRAVEL = 0.03F;
	/** The sights: the bottom of the rear notch, the top of the front post (the white dot just under it). */
	public static final float REAR_SIGHT_Z = 0.026F;
	public static final float FRONT_SIGHT_Z = -0.142F;
	public static final float NOTCH_Y = SLIDE_TOP + 0.0042F;
	public static final float POST_Y = SLIDE_TOP + 0.0058F;
	public static final float DOT_Y = SLIDE_TOP + 0.0036F;
	/** The grip's slope: 22 degrees back from the vertical. */
	public static final float GRIP_ANGLE = 22.0F;
	/** Top of the magazine seated in the grip, and the way it slides out (down the grip). */
	public static final Vector3f MAG_SEAT = new Vector3f(0.0F, 0.031F, gripAxisZ(0.031F) - 0.002F);
	public static final Vector3f MAG_DOWN = new Vector3f(0.0F, -(float) Math.cos(Math.toRadians(GRIP_ANGLE)),
		(float) Math.sin(Math.toRadians(GRIP_ANGLE)));
	/** The ejection port (on the right of the slide). */
	private static final float PORT_FRONT = -0.052F;
	private static final float PORT_REAR = -0.011F;
	private static final float PORT_LOW = 0.051F;
	private static final float W = 0.0128F;

	static final BoxMesh FRAME = frame();
	static final BoxMesh SLIDE = slide();
	static final BoxMesh BARREL = barrel();
	static final BoxMesh MAG = magazine(false);
	static final BoxMesh MAG_EXT = magazine(true);
	static final BoxMesh ROUND = round();
	static final BoxMesh CASE = spent();

	/**
	 * What moves: how far back the slide is (0..1), the magazine - seated, or (when not) where it is, as a
	 * transform from its seat (null: none) - and of which kind, the old magazine falling away (null: none),
	 * ticks since the empty case left the port (negative: none), whether a round sits in the chamber, and ticks
	 * since the shot for the muzzle flash (negative: none) with the shot's seed.
	 */
	public record Pose(float slide, boolean magIn, int kind, Matrix4f mag, int dropKind, Matrix4f drop, float eject, boolean chambered, float flash,
		long seed) {
		public static final Pose REST = new Pose(0.0F, true, GlockMagazineItem.STANDARD, null, 0, null, -1.0F, true, -1.0F, 0L);
	}

	public static void register() {
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("glock19"), Unbaked.MAP_CODEC);
	}

	/** Z of the grip's centre line at height {@code y} (it slopes back going down). */
	public static float gripAxisZ(float y) {
		return -0.0115F + (float) Math.tan(Math.toRadians(GRIP_ANGLE)) * (0.031F - y);
	}

	@Override
	public void submit(Pose pose, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil,
		int outline) {
		Pose p = pose == null ? Pose.REST : pose;
		boolean firstPerson = context.firstPerson();
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F);
		if (context == ItemDisplayContext.GROUND || context == ItemDisplayContext.FIXED) {
			poseStack.translate(0.0F, 0.0F, 0.06F);
		}
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pp, consumer) -> FRAME.emit(pp, consumer, light));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pp, consumer) -> BARREL.emit(pp, consumer, light));
		float back = p.slide() * SLIDE_TRAVEL;
		// a round in the chamber shows through the port with the slide pulled back
		if (p.chambered() && back > 0.008F) {
			poseStack.pushPose();
			poseStack.translate(0.0F, BORE_Y, PORT_REAR + 0.002F);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pp, consumer) -> ROUND.emit(pp, consumer, light));
			poseStack.popPose();
		}
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.0F, back);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pp, consumer) -> SLIDE.emit(pp, consumer, light));
		poseStack.popPose();
		// the magazine: seated, or where the hand has it (third person only once it is nearly in: there is no hand to carry it)
		if (p.magIn()) {
			submitMagazine(poseStack, collector, light, p.kind(), null);
		} else if (p.mag() != null && (firstPerson || p.mag().getTranslation(new Vector3f()).length() < 0.06F)) {
			submitMagazine(poseStack, collector, light, p.kind(), p.mag());
		}
		if (p.drop() != null) {
			submitMagazine(poseStack, collector, light, p.dropKind(), p.drop());
		}
		// the muzzle flash: smaller than a rifle's, a quick orange-white bloom off the short barrel
		if (p.flash() >= 0.0F && p.flash() < 1.2F) {
			AkItemRenderer.muzzleFlash(poseStack, collector, p.flash(), p.seed(), AkItemRenderer.ambient(light, net.minecraft.client.Minecraft.getInstance()),
				BORE_Y, SLIDE_FRONT - 0.002F, 0.55F);
		}
		// the empty case flicked out of the port, tumbling away to the right and up
		float t = p.eject();
		if (t >= 0.0F && t < 6.0F) {
			poseStack.pushPose();
			poseStack.translate(W + 0.09F * t, BORE_Y + 0.045F * t - 0.011F * t * t, -0.03F + 0.01F * t);
			poseStack.mulPose(Axis.YP.rotationDegrees(-70.0F * t));
			poseStack.mulPose(Axis.XP.rotationDegrees(85.0F * t));
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pp, consumer) -> CASE.emit(pp, consumer, light));
			poseStack.popPose();
		}
		poseStack.popPose();
	}

	/** A magazine at its seat in the grip, moved by {@code from} (null: seated). */
	public static void submitMagazine(PoseStack poseStack, SubmitNodeCollector collector, int light, int kind, Matrix4f from) {
		poseStack.pushPose();
		poseStack.translate(MAG_SEAT.x, MAG_SEAT.y, MAG_SEAT.z);
		if (from != null) {
			poseStack.mulPose(from);
		}
		poseStack.mulPose(Axis.XP.rotationDegrees(-GRIP_ANGLE));
		BoxMesh mesh = kind == GlockMagazineItem.EXTENDED ? MAG_EXT : MAG;
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pp, consumer) -> mesh.emit(pp, consumer, light));
		poseStack.popPose();
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0.45F, 0.3F, 0.3F));
		output.accept(new Vector3f(0.55F, 0.6F, 0.6F));
	}

	@Override
	public Pose extractArgument(ItemStack stack) {
		return GlockClient.poseFor(stack);
	}

	public record Unbaked() implements SpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

		@Override
		public SpecialModelRenderer<?> bake(SpecialModelRenderer.BakingContext context) {
			return new GlockItemRenderer();
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

	/** A box sheared along the grip: {@code front}/{@code back} offsets from the grip line, between heights {@code y0} (low) and {@code y1}. */
	private static void gripBox(BoxMesh.Builder b, int patch, float hw, float front, float back, float y0, float y1) {
		float z0 = gripAxisZ(y0);
		float z1 = gripAxisZ(y1);
		b.hexa(patch,
			v(-hw, y0, z0 + front), v(hw, y0, z0 + front), v(hw, y0, z0 + back), v(-hw, y0, z0 + back),
			v(-hw, y1, z1 + front), v(hw, y1, z1 + front), v(hw, y1, z1 + back), v(-hw, y1, z1 + back));
	}

	private static BoxMesh frame() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float fw = 0.0124F;
		// ===== the dust cover: the frame forward of the trigger guard, the rail cut into it underneath
		b.hexa(GL_POLY,
			v(-fw, 0.025F, -0.146F), v(fw, 0.025F, -0.146F), v(fw, 0.040F, -0.148F), v(-fw, 0.040F, -0.148F),
			v(-fw, 0.025F, 0.004F), v(fw, 0.025F, 0.004F), v(fw, 0.040F, 0.004F), v(-fw, 0.040F, 0.004F));
		b.box(-0.0096F, 0.0244F, -0.141F, 0.0096F, 0.0256F, -0.106F, SG_BORE); // the rail slot
		b.box(-0.0101F, 0.0236F, -0.124F, 0.0101F, 0.0251F, -0.119F, GL_POLY); // its cross groove
		// the seam where the slide rides on the frame
		b.box(-fw - 0.0002F, 0.0392F, -0.146F, fw + 0.0002F, 0.0402F, 0.004F, SG_BORE);
		// ===== the grip: stippled, sloping back at 22 degrees, the beavertail over the web of the hand
		gripBox(b, GL_POLY, 0.0145F, -0.023F, 0.024F, -0.068F, 0.031F);
		gripBox(b, GL_POLY, 0.0138F, -0.0205F, 0.0215F, -0.071F, -0.066F); // the magwell lip, a little flared
		b.hexa(GL_POLY,
			v(-0.0128F, 0.029F, gripAxisZ(0.029F) + 0.018F), v(0.0128F, 0.029F, gripAxisZ(0.029F) + 0.018F),
			v(0.0128F, 0.029F, 0.031F), v(-0.0128F, 0.029F, 0.031F),
			v(-0.0128F, 0.040F, gripAxisZ(0.040F) + 0.016F), v(0.0128F, 0.040F, gripAxisZ(0.040F) + 0.016F),
			v(0.0128F, 0.040F, 0.032F), v(-0.0128F, 0.040F, 0.032F));
		// the frame between grip and dust cover, round the trigger
		b.box(-fw, 0.022F, -0.024F, fw, 0.040F, 0.006F, GL_POLY);
		// ===== the trigger guard: a square front, a bar under the trigger
		b.box(-0.0045F, -0.006F, -0.071F, 0.0045F, 0.026F, -0.0645F, GL_POLY);
		b.box(-0.0045F, -0.0065F, -0.071F, 0.0045F, -0.0005F, gripAxisZ(-0.003F) - 0.021F, GL_POLY);
		// ===== the trigger: curved, with its safety blade in the face
		b.beam(v(0, 0.0235F, -0.0385F), v(0, 0.0135F, -0.0425F), 0.0055F, 0.0042F, BLACK);
		b.beam(v(0, 0.0135F, -0.0425F), v(0, 0.0045F, -0.0405F), 0.0055F, 0.0042F, BLACK);
		b.beam(v(0, 0.019F, -0.0418F), v(0, 0.010F, -0.0438F), 0.0018F, 0.002F, AK_WORN);
		// ===== controls: slide stop (left), takedown lever (both sides), magazine release (left, behind the guard)
		b.box(-fw - 0.0013F, 0.0335F, -0.040F, -fw + 0.0002F, 0.0372F, -0.021F, BLACK);
		for (float sx : new float[] {-1.0F, 1.0F}) {
			float x0 = sx < 0 ? -fw - 0.0009F : fw - 0.0002F;
			b.box(x0, 0.0305F, -0.064F, x0 + 0.0011F, 0.0362F, -0.0575F, BLACK);
		}
		float rz = gripAxisZ(0.018F) - 0.0235F;
		b.box(-0.0156F, 0.0145F, rz - 0.001F, -0.0138F, 0.0215F, rz + 0.007F, BLACK);
		// the trigger pin and the locking block pin, through the frame
		b.box(-fw - 0.0004F, 0.0285F, -0.030F, fw + 0.0004F, 0.0315F, -0.027F, AK_WORN);
		b.box(-fw - 0.0004F, 0.0300F, -0.057F, fw + 0.0004F, 0.0330F, -0.054F, AK_WORN);
		return b.build();
	}

	private static BoxMesh slide() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float lo = SLIDE_BOTTOM;
		float hi = SLIDE_TOP;
		float k = 0.0032F; // the chamfer along the top edges
		// rear of the slide, up to the ejection port
		b.box(-W, lo, PORT_REAR, W, hi - k, SLIDE_REAR, SG_PARK);
		b.hexa(SG_PARK, v(-W, hi - k, PORT_REAR), v(W, hi - k, PORT_REAR), v(W - k, hi, PORT_REAR), v(-W + k, hi, PORT_REAR),
			v(-W, hi - k, SLIDE_REAR), v(W, hi - k, SLIDE_REAR), v(W - k, hi, SLIDE_REAR), v(-W + k, hi, SLIDE_REAR));
		// along the port: the full slide under it, the left wall and top beside it
		b.box(-W, lo, PORT_FRONT, W, PORT_LOW, PORT_REAR, SG_PARK);
		b.box(-W, PORT_LOW, PORT_FRONT, 0.0012F, hi - k, PORT_REAR, SG_PARK);
		b.hexa(SG_PARK, v(-W, hi - k, PORT_FRONT), v(0.0012F, hi - k, PORT_FRONT), v(0.0012F, hi, PORT_FRONT), v(-W + k, hi, PORT_FRONT),
			v(-W, hi - k, PORT_REAR), v(0.0012F, hi - k, PORT_REAR), v(0.0012F, hi, PORT_REAR), v(-W + k, hi, PORT_REAR));
		// forward of the port to the muzzle; the nose bevelled
		b.box(-W, lo, SLIDE_FRONT + 0.004F, W, hi - k, PORT_FRONT, SG_PARK);
		b.hexa(SG_PARK, v(-W, hi - k, SLIDE_FRONT + 0.004F), v(W, hi - k, SLIDE_FRONT + 0.004F), v(W - k, hi, SLIDE_FRONT + 0.004F),
			v(-W + k, hi, SLIDE_FRONT + 0.004F), v(-W, hi - k, PORT_FRONT), v(W, hi - k, PORT_FRONT), v(W - k, hi, PORT_FRONT), v(-W + k, hi, PORT_FRONT));
		b.hexa(SG_PARK, v(-W + 0.002F, lo + 0.001F, SLIDE_FRONT), v(W - 0.002F, lo + 0.001F, SLIDE_FRONT), v(W - 0.003F, hi - 0.003F, SLIDE_FRONT),
			v(-W + 0.003F, hi - 0.003F, SLIDE_FRONT), v(-W, lo, SLIDE_FRONT + 0.004F), v(W, lo, SLIDE_FRONT + 0.004F), v(W, hi - k, SLIDE_FRONT + 0.004F),
			v(-W, hi - k, SLIDE_FRONT + 0.004F));
		// the muzzle opening the barrel sits in
		b.box(-0.0074F, BORE_Y - 0.0074F, SLIDE_FRONT - 0.0003F, 0.0074F, BORE_Y + 0.0074F, SLIDE_FRONT + 0.001F, SG_BORE);
		// serrations: rear, and the front ones of the newer slides
		for (int i = 0; i < 8; i++) {
			float z = SLIDE_REAR - 0.004F - i * 0.0032F;
			b.box(-W - 0.0004F, lo + 0.006F, z - 0.0011F, W + 0.0004F, hi - 0.006F, z, BLACK);
		}
		for (int i = 0; i < 6; i++) {
			float z = PORT_FRONT - 0.012F - i * 0.0032F;
			b.box(-W - 0.0004F, lo + 0.008F, z - 0.0011F, W + 0.0004F, hi - 0.007F, z, BLACK);
		}
		// the extractor, standing proud by the port, and the slide's rear plate
		b.box(W - 0.0004F, PORT_LOW + 0.004F, PORT_REAR, W + 0.0009F, PORT_LOW + 0.0085F, PORT_REAR + 0.017F, AK_WORN);
		b.box(-W + 0.0025F, lo + 0.004F, SLIDE_REAR - 0.0002F, W - 0.0025F, hi - 0.006F, SLIDE_REAR + 0.0006F, GL_POLY);
		// ===== sights: the U-notch rear with its white outline, the front post with its dot
		float sh = 0.0055F;
		b.box(-0.0068F, hi, REAR_SIGHT_Z, 0.0068F, NOTCH_Y, REAR_SIGHT_Z + 0.0075F, BLACK);
		for (float sx : new float[] {-1.0F, 1.0F}) {
			float x0 = sx < 0 ? -0.0068F : 0.0018F;
			b.box(x0, NOTCH_Y - 0.0002F, REAR_SIGHT_Z, x0 + 0.005F, hi + sh + 0.0015F, REAR_SIGHT_Z + 0.0075F, BLACK);
			// the white outline of the U, on the face towards the eye
			float wx = sx < 0 ? -0.0031F : 0.0018F;
			b.box(wx, NOTCH_Y, REAR_SIGHT_Z + 0.0075F, wx + 0.0013F, hi + sh + 0.0008F, REAR_SIGHT_Z + 0.0079F, WHITE);
		}
		b.box(-0.0031F, NOTCH_Y - 0.0012F, REAR_SIGHT_Z + 0.0075F, 0.0031F, NOTCH_Y, REAR_SIGHT_Z + 0.0079F, WHITE);
		b.box(-0.0016F, hi, FRONT_SIGHT_Z - 0.006F, 0.0016F, POST_Y, FRONT_SIGHT_Z, BLACK);
		b.box(-0.001F, DOT_Y - 0.001F, FRONT_SIGHT_Z, 0.001F, DOT_Y + 0.001F, FRONT_SIGHT_Z + 0.0004F, WHITE);
		return b.build();
	}

	/** The barrel: its hood squared off in the port (the chamber's mouth dark at its back), the crown at the muzzle. */
	private static BoxMesh barrel() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.0001F, PORT_LOW + 0.0005F, PORT_FRONT + 0.001F, W - 0.0012F, SLIDE_TOP - 0.0012F, PORT_REAR, AK_WORN);
		b.box(-0.0055F, BORE_Y - 0.0055F, PORT_REAR - 0.0004F, 0.0055F, BORE_Y + 0.0055F, PORT_REAR + 0.0002F, SG_BORE);
		b.revolve(AK_WORN, v(0, BORE_Y, SLIDE_FRONT + 0.03F), v(0, 0, -1), new float[][] {{0.0F, 0.0068F}, {0.0305F, 0.0068F}, {0.031F, 0.0062F}}, 12);
		b.revolve(SG_BORE, v(0, BORE_Y, SLIDE_FRONT - 0.0012F), v(0, 0, 1), new float[][] {{0.0F, 0.0046F}, {0.02F, 0.0046F}, {0.02F, 0.0F}}, 10);
		return b.build();
	}

	/**
	 * A magazine in its own frame: its top (the feed lips) at the origin, running down -Y. The flush one is as
	 * long as the grip, its baseplate filling the bottom of the magwell; the extended one hangs 85 mm further out.
	 */
	private static BoxMesh magazine(boolean extended) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float length = extended ? 0.2F : 0.115F;
		float hw = 0.0112F;
		float front = -0.0165F;
		float back = 0.0155F;
		// the body: steel-lined polymer, the front edge rounded off
		b.box(-hw, -length + 0.008F, front + 0.002F, hw, -0.004F, back, GL_POLY);
		b.box(-hw + 0.0015F, -length + 0.008F, front, hw - 0.0015F, -0.004F, front + 0.002F, GL_POLY);
		// the lips, and the top round in them (brass case, copper-jacketed bullet)
		b.box(-hw, -0.004F, front + 0.004F, -0.006F, 0.0F, back - 0.002F, AK_WORN);
		b.box(0.006F, -0.004F, front + 0.004F, hw, 0.0F, back - 0.002F, AK_WORN);
		b.revolve(BRASS, v(0, 0.0005F, back - 0.004F), v(0, 0, -1), new float[][] {{0.0F, 0.0049F}, {0.019F, 0.0048F}}, 10);
		b.revolve(AK_BRIGHT, v(0, 0.0005F, back - 0.023F), v(0, 0, -1), new float[][] {{0.0F, 0.0045F}, {0.006F, 0.0040F}, {0.0095F, 0.0022F},
			{0.0105F, 0.0F}}, 10);
		// the floorplate, its lip standing out front and back
		b.box(-hw - 0.0006F, -length, front - 0.0015F, hw + 0.0006F, -length + 0.008F, back + 0.0015F, GL_POLY);
		b.box(-hw + 0.002F, -length - 0.0006F, front + 0.002F, hw - 0.002F, -length, back - 0.002F, BLACK);
		// witness holes down the back, numbered
		int holes = extended ? 10 : 4;
		float step = (length - 0.03F) / holes;
		for (int i = 0; i < holes; i++) {
			float y = -0.02F - i * step;
			b.box(-0.0028F, y - 0.0028F, back - 0.0004F, 0.0028F, y, back + 0.0003F, SG_BORE);
			b.box(-0.0016F, y - 0.0045F, back - 0.0001F, 0.0016F, y - 0.0037F, back + 0.0004F, WHITE);
		}
		return b.build();
	}

	/** A 9 mm round lying in the chamber, its base at the origin, pointing along -Z. */
	private static BoxMesh round() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(BRASS, v(0, 0, 0), v(0, 0, -1), new float[][] {{0.0F, 0.0049F}, {0.019F, 0.0048F}}, 10);
		b.revolve(AK_BRIGHT, v(0, 0, -0.019F), v(0, 0, -1), new float[][] {{0.0F, 0.0045F}, {0.006F, 0.0040F}, {0.0095F, 0.0022F}, {0.0105F, 0.0F}}, 10);
		return b.build();
	}

	/** An empty 9 mm case, turning about its middle. */
	private static BoxMesh spent() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(BRASS, v(0, 0, 0.0095F), v(0, 0, -1), new float[][] {{0.0F, 0.0049F}, {0.019F, 0.0048F}}, 10);
		b.revolve(SG_BORE, v(0, 0, -0.0094F), v(0, 0, 1), new float[][] {{0.0F, 0.0041F}, {0.012F, 0.0041F}, {0.012F, 0.0F}}, 8);
		return b.build();
	}
}
