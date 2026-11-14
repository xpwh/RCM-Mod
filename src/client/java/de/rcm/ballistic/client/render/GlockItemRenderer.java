package de.rcm.ballistic.client.render;

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
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The Glock 19 Gen 5, modelled in Blender after a photograph (tools/ak47_blender/glock.py) at its real size:
 * the nitrided slide with its serrations front and rear and the markings on the left, the port showing the
 * barrel's hood, the U-notch rear sight and the front post with their white; the polymer frame with its
 * stippled, rounded grip, the rail under the dust cover, the trigger with its safety blade, slide stop,
 * takedown lever and magazine release. The magazine is drawn on its own: the flush 15-rounder or the long
 * 33-round extended one. Shaded per pixel from the baked maps ({@link GunShading}).
 * <p>
 * Item space as the other guns': -Z forward, +Y up, one unit a metre; the origin is the middle of the grip,
 * where the shooting hand holds it.
 */
public final class GlockItemRenderer implements SpecialModelRenderer<GunAttachmentModels.Armed<GlockItemRenderer.Pose>> {
	public static final float SLIDE_BOTTOM = 0.0475F;
	public static final float SLIDE_TOP = 0.0689F;
	public static final float SLIDE_REAR = 0.019F;
	public static final float SLIDE_FRONT = -0.1562F;
	public static final float BORE_Y = 0.058F;
	/** How far the slide runs back when it cycles (and stays when locked open). */
	public static final float SLIDE_TRAVEL = 0.03F;
	/** The sights: the bottom of the rear notch and its face towards the eye, the front dot and the post's face. */
	public static final float NOTCH_Y = 0.0709F;
	public static final float NOTCH_Z = 0.0097F;
	public static final float DOT_Y = 0.0717F;
	public static final float FRONT_SIGHT_Z = -0.1443F;
	/** The magazine well's rake: 20 degrees back from the vertical. */
	public static final float GRIP_ANGLE = 20.0F;
	/** Top of the magazine seated in the grip, and the way it slides out (down the well). */
	public static final Vector3f MAG_SEAT = new Vector3f(0.0F, 0.034F, -0.02415F);
	public static final Vector3f MAG_DOWN = new Vector3f(0.0F, -(float) Math.cos(Math.toRadians(GRIP_ANGLE)),
		(float) Math.sin(Math.toRadians(GRIP_ANGLE)));
	/** The rear face of the barrel (the chamber's mouth), and the ejection port's middle on the right. */
	private static final float CHAMBER_Z = -0.024F;
	private static final float PORT_Z = -0.046F;
	private static final float W = 0.01275F;
	/** The barrel tilts about this point near the muzzle as its rear cams down (Browning's locking). */
	private static final Vector3f BARREL_PIVOT = new Vector3f(0.0F, BORE_Y, -0.146F);

	public static final net.minecraft.client.renderer.rendertype.RenderType TYPE = GunShading.type("glock19",
		BallisticMissiles.id("textures/entity/glock19.png"), BallisticMissiles.id("textures/entity/glock19_mat.png"));
	private static final java.util.Map<String, BoxMesh> PARTS = AkItemRenderer.load("/assets/ballisticmissiles/meshes/glock19.mesh.gz");
	static final BoxMesh FRAME = PARTS.get("frame");
	static final BoxMesh SLIDE = PARTS.get("slide");
	static final BoxMesh BARREL = PARTS.get("barrel");
	/** The magazines in their own frame: the top (the lips) at the origin, running down -Y. */
	static final BoxMesh MAG = PARTS.get("mag");
	static final BoxMesh MAG_EXT = PARTS.get("mag_ext");
	/** A cartridge, its base at the origin, pointing along -Z; an empty case about its middle. */
	static final BoxMesh ROUND = PARTS.get("round");
	static final BoxMesh CASE = PARTS.get("case");

	/**
	 * What moves: how far back the slide is (0..1, a little past while a hand holds it at the stop), the
	 * magazine - seated, or (when not) where it is, as a transform from its seat (null: none) - and of which
	 * kind, the old magazine coming out (null: none), ticks since the empty case left the port (negative:
	 * none), whether a round sits in the chamber, and ticks since the shot for the muzzle flash (negative:
	 * none) with the shot's seed.
	 */
	public record Pose(float slide, boolean magIn, int kind, Matrix4f mag, int dropKind, Matrix4f drop, float eject, boolean chambered, float flash,
		long seed, boolean stovepipe) {
		public static final Pose REST = new Pose(0.0F, true, GlockMagazineItem.STANDARD, null, 0, null, -1.0F, true, -1.0F, 0L, false);
	}

	public static void register() {
		GunShading.init();
		SpecialModelRenderers.ID_MAPPER.put(BallisticMissiles.id("glock19"), Unbaked.MAP_CODEC);
	}

	@Override
	public void submit(GunAttachmentModels.Armed<Pose> armed, ItemDisplayContext context, PoseStack poseStack, SubmitNodeCollector collector, int light,
		int overlay, boolean foil, int outline) {
		Pose p = armed == null || armed.pose() == null ? Pose.REST : armed.pose();
		boolean firstPerson = context.firstPerson();
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F);
		if (context == ItemDisplayContext.GROUND || context == ItemDisplayContext.FIXED) {
			poseStack.translate(0.0F, 0.0F, 0.06F);
		}
		collector.submitCustomGeometry(poseStack, TYPE, (pp, consumer) -> FRAME.emit(pp, consumer, light));
		if (armed != null) {
			// light and laser under the dust cover, on the frame's rail
			GunAttachmentModels.submit(armed.att(), GunAttachmentModels.GLOCK, true, poseStack, collector, light, firstPerson);
			if (firstPerson) {
				// a light or laser on its way from the pouch onto the rail (key P)
				de.rcm.ballistic.client.gun.AttachAnim.submitCarried(poseStack, collector, light);
			}
		}
		float back = p.slide() * SLIDE_TRAVEL;
		// the barrel: back with the slide for 3 mm, then its rear drops out of the slide's locking recess
		float barrelBack = Math.min(back, 0.003F);
		float tilt = 1.5F * Mth.clamp((back - 0.002F) / 0.004F, 0.0F, 1.0F);
		poseStack.pushPose();
		poseStack.translate(BARREL_PIVOT.x, BARREL_PIVOT.y, BARREL_PIVOT.z + barrelBack);
		poseStack.mulPose(Axis.XP.rotationDegrees(tilt));
		poseStack.translate(-BARREL_PIVOT.x, -BARREL_PIVOT.y, -BARREL_PIVOT.z);
		collector.submitCustomGeometry(poseStack, TYPE, (pp, consumer) -> BARREL.emit(pp, consumer, light));
		// a round in the chamber: its brass shows at the barrel's mouth with the slide pulled back
		if (p.chambered() && back > 0.008F) {
			poseStack.pushPose();
			poseStack.translate(0.0F, BORE_Y, CHAMBER_Z + 0.0012F);
			collector.submitCustomGeometry(poseStack, TYPE, (pp, consumer) -> ROUND.emit(pp, consumer, light));
			poseStack.popPose();
		}
		poseStack.popPose();
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.0F, back);
		collector.submitCustomGeometry(poseStack, TYPE, (pp, consumer) -> SLIDE.emit(pp, consumer, light));
		if (p.stovepipe()) {
			// a stovepipe: the spent case caught by the slide, standing up out of the port, mouth up, leaning out
			poseStack.pushPose();
			poseStack.translate(W * 0.4F, BORE_Y + 0.004F, PORT_Z + 0.004F);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-28.0F));
			poseStack.mulPose(Axis.XP.rotationDegrees(-78.0F));
			collector.submitCustomGeometry(poseStack, TYPE, (pp, consumer) -> CASE.emit(pp, consumer, light));
			poseStack.popPose();
		}
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
		if (p.flash() >= 0.0F && p.flash() < 1.2F && (armed == null || !armed.att().suppressor())) {
			AkItemRenderer.muzzleFlash(poseStack, collector, p.flash(), p.seed(), AkItemRenderer.ambient(light, net.minecraft.client.Minecraft.getInstance()),
				BORE_Y, SLIDE_FRONT - 0.002F, 0.55F);
		}
		// the empty case flicked out of the port, tumbling away to the right and up
		float t = p.eject();
		if (t >= 0.0F && t < 6.0F) {
			poseStack.pushPose();
			poseStack.translate(W + 0.09F * t, BORE_Y + 0.045F * t - 0.011F * t * t, PORT_Z + 0.01F * t);
			poseStack.mulPose(Axis.YP.rotationDegrees(-70.0F * t));
			poseStack.mulPose(Axis.XP.rotationDegrees(85.0F * t));
			collector.submitCustomGeometry(poseStack, TYPE, (pp, consumer) -> CASE.emit(pp, consumer, light));
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
		collector.submitCustomGeometry(poseStack, TYPE, (pp, consumer) -> mesh.emit(pp, consumer, light));
		poseStack.popPose();
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0.45F, 0.3F, 0.3F));
		output.accept(new Vector3f(0.55F, 0.6F, 0.6F));
	}

	@Override
	public GunAttachmentModels.Armed<Pose> extractArgument(ItemStack stack) {
		return new GunAttachmentModels.Armed<>(GlockClient.poseFor(stack), de.rcm.ballistic.gun.GunAttachments.of(stack));
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
}
