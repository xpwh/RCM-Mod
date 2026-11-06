package de.rcm.ballistic.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.render.BoxMesh;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * The karambit, traced from a photograph of a real tactical one (about 20 cm overall): a broad hawkbill blade
 * in black-coated steel with a flat grind - the flat a shade lighter than the bevel below it, the edge a bright
 * line - and jimping on the spine at the handle; tan G10 scales with their slanted wave grooves, three finger
 * grooves underneath and two black Torx screws, over a steel tang; and at the end the black steel finger ring
 * with its pointed spur.
 * <p>
 * The outlines are in the photo's pixels (x along the knife from the tip on the left to the ring on the right,
 * y down). Local space: the origin at the middle of the ring, the handle running down -y, the edge side towards
 * +z (the photo's down), x across the thickness.
 */
public final class KarambitModel {
	static final RenderType TYPE = RenderTypes.entityCutoutNoCull(BallisticMissiles.id("textures/entity/karambit.png"));

	static final int BLADE = 0;
	static final int EDGE = 1;
	static final int G10 = 2;
	static final int LINER = 3;
	static final int PIN = 4;
	static final int JIMP = 5;
	static final int BEVEL = 6;
	static final int RING = 7;
	static final int RIDGE = 8;

	/** Blocks per photo pixel: 0.1 mm a pixel, the whole knife a little over life size so it reads in the hand. */
	private static final float S = 0.000114F;
	/** The middle of the finger ring in the photo. */
	private static final float RX = 1741.0F;
	private static final float RY = 605.0F;
	/** The middle of the handle, where the fist closes. */
	public static final Vector3f GRIP = local(0.0F, 1250.0F, 430.0F);

	// --- the outlines, measured off the photo (x, y)
	/** The back of the blade, from the handle to the point. */
	private static final float[][] SPINE = {{870, 345}, {825, 348}, {800, 345}, {750, 336}, {725, 332}, {700, 333}, {650, 350}, {600, 371}, {550, 394},
		{500, 419}, {450, 448}, {400, 479}, {350, 514}, {300, 553}, {250, 598}, {200, 650}, {150, 712}, {125, 749}, {100, 790}, {75, 839}, {50, 902},
		{35, 950}, {25, 999}, {19, 1060}, {17, 1110}};
	/** Where the flat meets the bevel: it sweeps down to the edge at the ricasso. */
	private static final float[][] GRIND = {{845, 668}, {800, 634}, {775, 598}, {750, 568}, {725, 543}, {700, 526}, {650, 514}, {600, 517}, {550, 527},
		{500, 541}, {450, 559}, {400, 581}, {350, 607}, {300, 640}, {250, 681}, {200, 729}, {150, 789}, {125, 828}, {100, 878}, {75, 937}, {50, 999},
		{35, 1045}, {25, 1080}, {19, 1100}, {17, 1110}};
	/** The edge, from the ricasso round the inside of the claw to the point. */
	private static final float[][] EDGE_LINE = {{860, 679}, {825, 676}, {800, 671}, {750, 663}, {725, 664}, {700, 666}, {650, 673}, {600, 684}, {550, 696},
		{500, 712}, {450, 732}, {400, 754}, {350, 780}, {300, 811}, {250, 846}, {200, 888}, {150, 938}, {125, 966}, {100, 997}, {75, 1030}, {50, 1066},
		{35, 1088}, {25, 1102}, {19, 1108}, {17, 1110}};
	/** The top of the handle scales, by x. */
	private static final float[][] G10_TOP = {{775, 345}, {800, 348}, {850, 350}, {900, 349}, {950, 339}, {1000, 323}, {1025, 306}, {1050, 296}, {1100, 295},
		{1150, 298}, {1200, 298}, {1250, 300}, {1300, 302}, {1350, 307}, {1400, 318}, {1450, 321}, {1500, 338}, {1550, 343}, {1600, 350}, {1650, 364},
		{1700, 377}, {1750, 393}, {1800, 409}};
	/** The underside of the scales with the three finger grooves, by x (the front end slants up to the top). */
	private static final float[][] G10_BOTTOM = {{885, 619}, {900, 563}, {925, 552}, {950, 534}, {975, 521}, {1000, 514}, {1025, 512}, {1050, 513},
		{1075, 520}, {1100, 530}, {1125, 551}, {1150, 556}, {1175, 543}, {1200, 535}, {1225, 532}, {1250, 533}, {1275, 536}, {1300, 545}, {1325, 559},
		{1350, 567}, {1375, 585}, {1400, 575}, {1425, 571}, {1450, 570}, {1475, 573}, {1500, 581}, {1525, 592}, {1550, 611}, {1575, 620}, {1600, 599},
		{1625, 529}, {1650, 498}, {1675, 480}, {1700, 469}, {1725, 463}, {1750, 460}, {1775, 445}, {1800, 437}};

	public static final BoxMesh MESH = build();

	private KarambitModel() {
	}

	/** A photo point (x, y) and a thickness offset (blocks) to local space. */
	private static Vector3f local(float thick, float x, float y) {
		return new Vector3f(thick, (x - RX) * S, (y - RY) * S);
	}

	/** Points spaced evenly along a polyline, {@code n + 1} of them. */
	private static float[][] resample(float[][] line, int n) {
		float[] len = new float[line.length];
		for (int i = 1; i < line.length; i++) {
			len[i] = len[i - 1] + (float) Math.hypot(line[i][0] - line[i - 1][0], line[i][1] - line[i - 1][1]);
		}
		float[][] out = new float[n + 1][];
		int k = 1;
		for (int j = 0; j <= n; j++) {
			float want = len[len.length - 1] * j / n;
			while (k < line.length - 1 && len[k] < want) {
				k++;
			}
			float f = (want - len[k - 1]) / Math.max(1.0E-6F, len[k] - len[k - 1]);
			out[j] = new float[] {Mth.lerp(f, line[k - 1][0], line[k][0]), Mth.lerp(f, line[k - 1][1], line[k][1])};
		}
		return out;
	}

	/** y of a profile given by x (the table runs by increasing x). */
	private static float at(float[][] table, float x) {
		if (x <= table[0][0]) {
			return table[0][1];
		}
		for (int i = 1; i < table.length; i++) {
			if (x <= table[i][0]) {
				return Mth.lerp((x - table[i - 1][0]) / (table[i][0] - table[i - 1][0]), table[i - 1][1], table[i][1]);
			}
		}
		return table[table.length - 1][1];
	}

	/** A six-sided slab, its corners put in the order the mesh builder wants whichever way round they came. */
	private static void solid(BoxMesh.Builder b, int patch, Vector3f... c) {
		Vector3f x = new Vector3f(c[1]).sub(c[0]);
		Vector3f y = new Vector3f(c[3]).sub(c[0]);
		Vector3f z = new Vector3f(c[4]).sub(c[0]);
		if (new Vector3f(x).cross(y).dot(z) < 0.0F) {
			c = new Vector3f[] {c[4], c[5], c[6], c[7], c[0], c[1], c[2], c[3]};
		}
		b.hexa(patch, c);
	}

	/** The band between two lines across the thickness: {@code ta} thick along line a, {@code tb} along line b. */
	private static void band(BoxMesh.Builder b, int patch, float[][] a, float[][] bb, float[] ta, float[] tb) {
		for (int i = 0; i + 1 < a.length; i++) {
			solid(b, patch,
				local(-ta[i], a[i][0], a[i][1]), local(ta[i], a[i][0], a[i][1]), local(tb[i], bb[i][0], bb[i][1]), local(-tb[i], bb[i][0], bb[i][1]),
				local(-ta[i + 1], a[i + 1][0], a[i + 1][1]), local(ta[i + 1], a[i + 1][0], a[i + 1][1]), local(tb[i + 1], bb[i + 1][0], bb[i + 1][1]),
				local(-tb[i + 1], bb[i + 1][0], bb[i + 1][1]));
		}
	}

	private static float[] fill(int n, java.util.function.IntFunction<Float> f) {
		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			out[i] = f.apply(i);
		}
		return out;
	}

	private static BoxMesh build() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		blade(b);
		handle(b);
		ring(b);
		return b.build();
	}

	private static void blade(BoxMesh.Builder b) {
		int n = 40;
		float[][] spine = resample(SPINE, n);
		float[][] grind = resample(GRIND, n);
		float[][] edge = resample(EDGE_LINE, n);
		// 4 mm at the spine, thinning a little to the point; the flat converges slightly, the bevel runs down to the edge
		float[] ts = fill(n + 1, i -> 0.0024F * (1.0F - 0.5F * i / (float) n));
		float[] tg = fill(n + 1, i -> 0.002F * (1.0F - 0.5F * i / (float) n));
		float[] te = fill(n + 1, i -> 0.00025F);
		band(b, BLADE, spine, grind, ts, tg);
		band(b, BEVEL, grind, edge, tg, te);
		// the sharpened edge: a fine bright line along it
		float[][] inner = new float[n + 1][];
		for (int i = 0; i <= n; i++) {
			inner[i] = new float[] {Mth.lerp(0.06F, edge[i][0], grind[i][0]), Mth.lerp(0.06F, edge[i][1], grind[i][1])};
		}
		band(b, EDGE, inner, edge, fill(n + 1, i -> 0.0006F), te);
		// the blade carries on under the scales as the tang
		float[][] tangTop = new float[24][];
		float[][] tangBottom = new float[24][];
		for (int i = 0; i < 24; i++) {
			float x = Mth.lerp(i / 23.0F, 860.0F, 1660.0F);
			tangTop[i] = new float[] {x, at(G10_TOP, x) + 4.0F};
			tangBottom[i] = new float[] {x, Math.max(at(G10_BOTTOM, Math.max(x, 900.0F)) + 5.0F, tangTop[i][1] + 10.0F)};
		}
		tangBottom[0] = new float[] {860.0F, 679.0F};
		band(b, LINER, tangTop, tangBottom, fill(24, i -> 0.0023F), fill(24, i -> 0.0023F));
		// jimping on the spine where it meets the handle
		for (int i = 0; i < 7; i++) {
			float x = 772.0F + i * 14.0F;
			float y = at(G10_TOP, x) - 3.0F;
			solid(b, JIMP, local(-0.0026F, x, y - 7.0F), local(0.0026F, x, y - 7.0F), local(0.0026F, x, y + 4.0F), local(-0.0026F, x, y + 4.0F),
				local(-0.0026F, x + 6.0F, y - 7.0F), local(0.0026F, x + 6.0F, y - 7.0F), local(0.0026F, x + 6.0F, y + 4.0F), local(-0.0026F, x + 6.0F, y + 4.0F));
		}
	}

	private static void handle(BoxMesh.Builder b) {
		// the scales, one slab per wave groove of their texture, following the finger grooves underneath
		int m = 26;
		float[][] top2 = new float[m + 1][];
		float[][] bottom2 = new float[m + 1][];
		for (int i = 0; i <= m; i++) {
			float s = i / (float) m;
			float xt = Mth.lerp(s, 775.0F, 1800.0F);
			float xb = Mth.lerp(s, 885.0F, 1800.0F);
			top2[i] = new float[] {xt, at(G10_TOP, xt)};
			bottom2[i] = new float[] {xb, at(G10_BOTTOM, xb)};
		}
		band(b, G10, top2, bottom2, fill(m + 1, i -> 0.0072F), fill(m + 1, i -> 0.0064F));
		// the slanted wave grooves milled across the scales
		for (int i = 0; i < 13; i++) {
			float x = 945.0F + i * 62.0F;
			float yt = at(G10_TOP, x - 18.0F) + 7.0F;
			float yb = at(G10_BOTTOM, x + 22.0F) - 8.0F;
			if (yb - yt < 30.0F) {
				continue;
			}
			for (float side : new float[] {-1.0F, 1.0F}) {
				float t0 = side * 0.0072F;
				float t1 = side * 0.0074F;
				float u0 = side * 0.0064F;
				float u1 = side * 0.0066F;
				solid(b, RIDGE, local(t0, x - 18.0F, yt), local(t1, x - 18.0F, yt), local(u1, x + 22.0F, yb), local(u0, x + 22.0F, yb),
					local(t0, x + 14.0F, yt), local(t1, x + 14.0F, yt), local(u1, x + 54.0F, yb), local(u0, x + 54.0F, yb));
			}
		}
		// two Torx screws through the scales
		for (float[] p : new float[][] {{850.0F, 445.0F}, {1566.0F, 441.0F}}) {
			Vector3f c = local(0.0F, p[0], p[1]);
			b.cylinderX(c.x, c.y, c.z, 34.0F * S, 0.0158F, 12, PIN, PIN);
			b.cylinderX(c.x, c.y, c.z, 14.0F * S, 0.0162F, 6, JIMP, JIMP);
		}
	}

	private static void ring(BoxMesh.Builder b) {
		int n = 40;
		float rin = 118.0F;
		float rout = 152.0F;
		for (int i = 0; i < n; i++) {
			float a0 = Mth.TWO_PI * i / n;
			float a1 = Mth.TWO_PI * (i + 1) / n;
			float t = 0.0023F;
			solid(b, RING,
				local(-t, RX + rout * Mth.cos(a0), RY + rout * Mth.sin(a0)), local(t, RX + rout * Mth.cos(a0), RY + rout * Mth.sin(a0)),
				local(t, RX + rin * Mth.cos(a0), RY + rin * Mth.sin(a0)), local(-t, RX + rin * Mth.cos(a0), RY + rin * Mth.sin(a0)),
				local(-t, RX + rout * Mth.cos(a1), RY + rout * Mth.sin(a1)), local(t, RX + rout * Mth.cos(a1), RY + rout * Mth.sin(a1)),
				local(t, RX + rin * Mth.cos(a1), RY + rin * Mth.sin(a1)), local(-t, RX + rin * Mth.cos(a1), RY + rin * Mth.sin(a1)));
		}
		// the steel neck from the scales into the ring
		float[][] neckTop = {{1640, 372}, {1700, 385}, {1760, 402}, {1800, 415}};
		float[][] neckBottom = {{1640, 610}, {1700, 600}, {1760, 520}, {1800, 470}};
		band(b, RING, neckTop, neckBottom, fill(4, i -> 0.0023F), fill(4, i -> 0.0023F));
		// the spur off the back of the ring, ground to a point
		float t = 0.0023F;
		solid(b, RING, local(-t, 1872, 548), local(t, 1872, 548), local(t, 1878, 670), local(-t, 1878, 670),
			local(-0.0004F, 1983, 610), local(0.0004F, 1983, 610), local(0.0004F, 1983, 618), local(-0.0004F, 1983, 618));
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, int light) {
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> MESH.emit(pose, consumer, light));
	}
}
