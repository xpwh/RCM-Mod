package de.rcm.ballistic.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.render.BoxMesh;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * The smoker's things as small meshes (sizes in blocks, a little larger than life so they read on screen):
 * the pack of twenty with its hinged lid, the foil and the filters showing; a cigarette, filter, paper, ash
 * and ember, at any length as it burns down; the Zippo - case, hinged lid, chimney and wheel - and its flame.
 * Coloured from their own little atlas (textures/entity/smoking.png, 8 x 8 patches).
 */
public final class SmokeKit {
	static final RenderType SOLID = RenderTypes.entityCutout(BallisticMissiles.id("textures/entity/smoking.png"));
	static final RenderType GLOW = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/smoking.png"));
	static final int FULL_BRIGHT = 0xF000F0;

	static final int PAPER = 0;
	static final int FILTER = 1;
	static final int ASH = 2;
	static final int EMBER = 3;
	static final int PACK_RED = 4;
	static final int PACK_WHITE = 5;
	static final int FOIL = 6;
	static final int CHROME = 7;
	static final int DARK = 8;
	static final int FLAME = 9;
	static final int FLAME_CORE = 10;
	static final int CARD = 11;
	static final int GOLD = 12;

	/** The pack: 77 x 123 x 31 mm-ish (scaled), its origin at the middle of its bottom, front towards +z. */
	static final float PACK_W = 0.077F;
	static final float PACK_H = 0.123F;
	static final float PACK_D = 0.031F;
	/** How tall the lid is, from the top down. */
	static final float LID_H = 0.034F;
	static final float CIG_R = 0.0058F;
	static final float CIG_LEN = 0.118F;
	static final float FILTER_LEN = 0.032F;
	/** The Zippo, origin at the middle of its bottom, front towards +z. */
	static final float ZIP_W = 0.052F;
	static final float ZIP_H = 0.056F;
	static final float ZIP_D = 0.018F;
	static final float ZIP_LID = 0.024F;

	static final BoxMesh PACK_BODY = packBody();
	static final BoxMesh PACK_LID = packLid();
	static final BoxMesh ZIPPO_CASE = zippoCase();
	static final BoxMesh ZIPPO_LID = zippoLid();
	static final BoxMesh ZIPPO_CHIMNEY = zippoChimney();
	static final BoxMesh FLAME_MESH = flame(FLAME, 1.0F);
	static final BoxMesh FLAME_INNER = flame(FLAME_CORE, 0.45F);

	private SmokeKit() {
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	private static BoxMesh packBody() {
		float w = PACK_W / 2;
		float d = PACK_D / 2;
		float top = PACK_H - LID_H;
		BoxMesh.Builder b = new BoxMesh.Builder();
		// the red lower part, the white band round the top of it, the cardboard edge and the foil above
		b.box(-w, 0, -d, w, top * 0.62F, d, PACK_RED);
		b.box(-w, top * 0.62F, -d, w, top, d, PACK_WHITE);
		b.box(-w * 0.6F, top * 0.30F, d, w * 0.6F, top * 0.36F, d + 0.0006F, GOLD);
		b.box(-w + 0.002F, top, -d + 0.002F, w - 0.002F, top + 0.006F, d - 0.002F, FOIL);
		// the inner collar standing up into the lid
		b.box(-w + 0.0015F, top, d - 0.003F, w - 0.0015F, top + 0.016F, d - 0.0015F, CARD);
		// the filters of the cigarettes standing in two rows
		for (int row = 0; row < 2; row++) {
			for (int i = 0; i < 5; i++) {
				float x = -w + 0.009F + i * (PACK_W - 0.018F) / 4.0F;
				float z = -d + 0.008F + row * (PACK_D - 0.016F);
				b.revolve(FILTER, v(x, top + 0.004F, z), v(0, 1, 0), new float[][] {{0.0F, CIG_R}, {0.012F, CIG_R}, {0.0125F, 0.0F}}, 8);
			}
		}
		return b.build();
	}

	/** The lid, built closed, hinged along its back top edge: origin on the hinge. */
	private static BoxMesh packLid() {
		float w = PACK_W / 2;
		float d = PACK_D / 2;
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-w, -LID_H, 0.0F, w, 0.0F, PACK_D, PACK_WHITE);
		b.box(-w, -LID_H, PACK_D - 0.0004F, w, -LID_H + 0.008F, PACK_D + 0.0004F, PACK_RED);
		b.box(-w + 0.002F, -LID_H + 0.002F, 0.002F, w - 0.002F, -0.001F, PACK_D - 0.002F, CARD);
		return b.build();
	}

	private static BoxMesh zippoCase() {
		float w = ZIP_W / 2;
		float d = ZIP_D / 2;
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-w, 0, -d, w, ZIP_H, d, CHROME);
		b.box(-w + 0.001F, ZIP_H - 0.0015F, -d + 0.001F, w - 0.001F, ZIP_H, d - 0.001F, DARK);
		// the hinge barrel on the right
		b.box(w - 0.001F, ZIP_H - 0.006F, -0.003F, w + 0.0025F, ZIP_H + 0.002F, 0.003F, CHROME);
		return b.build();
	}

	/** The lid, built closed, hinged on the right top corner: origin on the hinge. */
	private static BoxMesh zippoLid() {
		float d = ZIP_D / 2;
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-ZIP_W, 0.0F, -d, 0.0F, ZIP_LID, d, CHROME);
		b.box(-ZIP_W + 0.0012F, 0.0F, -d + 0.0012F, -0.0012F, ZIP_LID - 0.0012F, d - 0.0012F, DARK);
		return b.build();
	}

	/** The chimney and the wheel on top of the case: origin at the top of the case. */
	private static BoxMesh zippoChimney() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(-0.016F, 0.0F, -0.0065F, 0.012F, 0.021F, 0.0065F, CHROME);
		// the vent holes, three rows a side
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 3; j++) {
				float x = -0.013F + i * 0.0068F;
				float y = 0.004F + j * 0.0058F;
				b.box(x, y, 0.0063F, x + 0.0035F, y + 0.0033F, 0.0068F, DARK);
				b.box(x, y, -0.0068F, x + 0.0035F, y + 0.0033F, -0.0063F, DARK);
			}
		}
		b.cylinderX(0.017F, 0.017F, 0.0F, 0.0045F, 0.006F, 10, DARK, CHROME);
		b.box(0.014F, 0.0F, -0.002F, 0.02F, 0.013F, 0.002F, CHROME);
		return b.build();
	}

	/** A flame: a tall teardrop, about 3 cm, wavering (scaled when drawn). */
	private static BoxMesh flame(int patch, float k) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(patch, v(0, 0, 0), v(0, 1, 0), new float[][] {
			{0.0F, 0.0F}, {0.003F * k, 0.0045F * k}, {0.009F * k, 0.0062F * k}, {0.016F * k, 0.0055F * k}, {0.026F * k, 0.0032F * k}, {0.038F * k, 0.0F}}, 10);
		return b.build();
	}

	/**
	 * A cigarette lying along +z from its filter end at the origin: {@code burnt} 0 new .. 1 down to the
	 * filter. Returns the solid part; the ember is drawn by {@link #ember}.
	 */
	static BoxMesh cigarette(float burnt) {
		float paper = (CIG_LEN - FILTER_LEN) * (1.0F - 0.85F * Mth.clamp(burnt, 0.0F, 1.0F));
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(FILTER, v(0, 0, 0), v(0, 0, 1), new float[][] {{0.0F, 0.0F}, {0.0004F, CIG_R}, {FILTER_LEN, CIG_R}}, 10);
		b.revolve(PAPER, v(0, 0, FILTER_LEN), v(0, 0, 1), new float[][] {{0.0F, CIG_R}, {paper, CIG_R}}, 10);
		// a few millimetres of grey ash hanging on the end once it has burnt a while
		if (burnt > 0.04F) {
			float ash = Math.min(0.012F, burnt * 0.05F);
			b.revolve(ASH, v(0, 0, FILTER_LEN + paper), v(0, 0, 1), new float[][] {{0.0F, CIG_R * 0.98F}, {ash, CIG_R * 0.9F}, {ash + 0.0006F, 0.0F}}, 10);
		}
		return b.build();
	}

	/** Where the burning end of a cigarette is (along +z from the filter end). */
	static float tipAt(float burnt) {
		return FILTER_LEN + (CIG_LEN - FILTER_LEN) * (1.0F - 0.85F * Mth.clamp(burnt, 0.0F, 1.0F));
	}

	/** The glowing coal at the tip: a short ring of ember, brighter while drawn on. */
	static void ember(PoseStack poseStack, SubmitNodeCollector collector, float burnt, float glow) {
		float z = tipAt(burnt) - 0.0035F;
		int a = Math.round(Mth.clamp(0.35F + 0.65F * glow, 0.0F, 1.0F) * 255.0F);
		BoxMesh coal = new BoxMesh.Builder().revolve(EMBER, v(0, 0, z), v(0, 0, 1), new float[][] {{0.0F, CIG_R * 1.02F}, {0.0045F, CIG_R * 1.02F}, {0.0052F, 0.0F}}, 10).build();
		collector.submitCustomGeometry(poseStack, GLOW, (pose, consumer) -> coal.emit(pose, consumer, FULL_BRIGHT, a << 24 | 0xFFFFFF));
	}

	static void submit(PoseStack poseStack, SubmitNodeCollector collector, BoxMesh mesh, int light) {
		collector.submitCustomGeometry(poseStack, SOLID, (pose, consumer) -> mesh.emit(pose, consumer, light));
	}

	/** The pack with its lid at {@code open} (0 shut .. 1 thrown right back). */
	static void pack(PoseStack poseStack, SubmitNodeCollector collector, int light, float open) {
		submit(poseStack, collector, PACK_BODY, light);
		poseStack.pushPose();
		poseStack.translate(0.0F, PACK_H, -PACK_D / 2);
		poseStack.mulPose(Axis.XP.rotationDegrees(-125.0F * open));
		submit(poseStack, collector, PACK_LID, light);
		poseStack.popPose();
	}

	/** The Zippo with its lid at {@code open} (0 shut .. 1 flicked open), and its flame while {@code flame} > 0. */
	static void zippo(PoseStack poseStack, SubmitNodeCollector collector, int light, float open, float flame, float time) {
		submit(poseStack, collector, ZIPPO_CASE, light);
		poseStack.pushPose();
		poseStack.translate(0.0F, ZIP_H, 0.0F);
		submit(poseStack, collector, ZIPPO_CHIMNEY, light);
		poseStack.popPose();
		poseStack.pushPose();
		poseStack.translate(ZIP_W / 2 + 0.001F, ZIP_H, 0.0F);
		poseStack.mulPose(Axis.ZP.rotationDegrees(-150.0F * open));
		submit(poseStack, collector, ZIPPO_LID, light);
		poseStack.popPose();
		if (flame > 0.0F) {
			poseStack.pushPose();
			poseStack.translate(-0.002F, ZIP_H + 0.02F, 0.0F);
			float wave = 1.0F + 0.12F * Mth.sin(time * 2.7F) + 0.07F * Mth.sin(time * 6.1F + 1.0F);
			poseStack.mulPose(Axis.ZP.rotationDegrees(4.0F * Mth.sin(time * 1.9F)));
			poseStack.scale(flame * (1.0F + 0.05F * Mth.sin(time * 4.3F)), flame * wave, flame);
			int a = Math.round(200.0F * Mth.clamp(flame, 0.0F, 1.0F));
			collector.submitCustomGeometry(poseStack, GLOW, (pose, consumer) -> FLAME_MESH.emit(pose, consumer, FULL_BRIGHT, a << 24 | 0xFFFFFF));
			collector.submitCustomGeometry(poseStack, GLOW, (pose, consumer) -> FLAME_INNER.emit(pose, consumer, FULL_BRIGHT, a << 24 | 0xFFFFFF));
			poseStack.popPose();
		}
	}
}
