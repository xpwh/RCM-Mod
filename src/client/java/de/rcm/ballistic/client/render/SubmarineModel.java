package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.GLASS;
import static de.rcm.ballistic.client.render.StructureKit.HAZE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.SUB_DECK;
import static de.rcm.ballistic.client.render.StructureKit.SUB_HULL;
import static de.rcm.ballistic.client.render.StructureKit.SUB_TILES;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.v;

import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * Ohio-class-style ballistic missile submarine, 34 blocks long, lying on the sea floor with the
 * silo block in its keel. Sub space: +Z is the bow, the keel at y = 0.1, origin at the block's
 * bottom centre. The missile tube rises through the hull to the hatch at the origin's x/z.
 */
final class SubmarineModel {
	static final float HULL_RADIUS = 2.1F;
	static final float AXIS_Y = 2.2F;
	static final float DECK_Y = AXIS_Y + HULL_RADIUS + 0.35F;
	/** Hatch positions along the missile deck; the one at z = 0 belongs to the live tube. */
	private static final float[] HATCHES = {-8.4F, -7.0F, -5.6F, -4.2F, -2.8F, -1.4F, 0.0F, 1.4F, 2.8F};

	static final BoxMesh HULL = buildHull();
	/** The live tube's hatch, hinged at its aft edge (z = -0.45), drawn closed. */
	static final BoxMesh HATCH = new BoxMesh.Builder().box(-0.45F, 0.0F, 0.0F, 0.45F, 0.12F, 0.9F, SUB_HULL).box(-0.3F, 0.12F, 0.2F, 0.3F, 0.14F, 0.7F, STEEL).build();
	static final BoxMesh MAST_LIGHT = new BoxMesh.Builder().box(-0.08F, DECK_Y + 4.55F, 8.12F, 0.08F, DECK_Y + 4.7F, 8.28F, RED_LAMP).build();

	private SubmarineModel() {
	}

	private static BoxMesh buildHull() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		Vector3f fwd = v(0, 0, 1);
		// pressure hull under its anechoic tiles: rounded bow, long parallel midbody, tapering stern
		b.revolve(SUB_TILES, v(0, AXIS_Y, -17.0F), fwd, new float[][] {
			{0.0F, 0.0F}, {0.4F, 0.55F}, {2.0F, 1.25F}, {4.5F, 1.85F}, {7.0F, HULL_RADIUS}, {28.0F, HULL_RADIUS},
			{30.6F, 1.95F}, {32.4F, 1.5F}, {33.5F, 0.85F}, {34.0F, 0.0F}
		}, 28);
		// the long missile deck "turtleback" aft of the sail, with its row of tube hatches
		b.hexa(SUB_DECK,
			v(-1.3F, AXIS_Y + 1.5F, -9.9F), v(1.3F, AXIS_Y + 1.5F, -9.9F), v(0.9F, DECK_Y, -9.3F), v(-0.9F, DECK_Y, -9.3F),
			v(-1.3F, AXIS_Y + 1.5F, 4.4F), v(1.3F, AXIS_Y + 1.5F, 4.4F), v(0.9F, DECK_Y, 3.8F), v(-0.9F, DECK_Y, 3.8F));
		for (float z : HATCHES) {
			if (z != 0.0F) {
				b.box(-0.45F, DECK_Y, z - 0.45F, 0.45F, DECK_Y + 0.12F, z + 0.45F, HAZE_DARK);
				b.box(-0.4F, DECK_Y + 0.12F, z - 0.47F, 0.4F, DECK_Y + 0.16F, z - 0.39F, STEEL); // hinge
			}
		}
		// limber holes: rows of flood openings along both sides of the casing
		for (float z = -9.8F; z < 10.8F; z += 0.7F) {
			if (z > 5.2F && z < 10.8F) {
				continue; // under the sail
			}
			for (float side : new float[] {-1.0F, 1.0F}) {
				float x = side * 1.36F;
				b.box(x - 0.06F, AXIS_Y + 1.52F, z, x + 0.06F, AXIS_Y + 1.62F, z + 0.38F, BLACK);
			}
		}
		// flank sonar arrays near the bow and the towed-array fairing along the port side
		for (float side : new float[] {-1.0F, 1.0F}) {
			b.box(side * 2.08F - 0.04F, AXIS_Y - 0.7F, 9.0F, side * 2.08F + 0.04F, AXIS_Y + 0.7F, 12.5F, HAZE_DARK);
		}
		b.beam(v(1.92F, AXIS_Y + 0.85F, -9.8F), v(1.92F, AXIS_Y + 0.85F, 9.0F), 0.16F, 0.16F, SUB_HULL);
		// rescue hatches fore and aft, where a rescue vehicle can mate
		for (float z : new float[] {-10.6F, 11.0F}) {
			b.revolve(HAZE_DARK, v(0, AXIS_Y + HULL_RADIUS - 0.05F, z), v(0, 1, 0), new float[][] {{0.0F, 0.55F}, {0.12F, 0.55F}, {0.12F, 0.0F}}, 14);
		}
		// the open tube mouth under the live hatch
		b.box(-0.42F, DECK_Y - 0.02F, -0.42F, 0.42F, DECK_Y + 0.01F, 0.42F, BLACK);
		// sail with its rounded leading edge, windows, masts and fairwater planes
		b.hexa(SUB_HULL,
			v(-0.6F, DECK_Y - 0.4F, 5.6F), v(0.6F, DECK_Y - 0.4F, 5.6F), v(0.55F, DECK_Y + 3.4F, 6.0F), v(-0.55F, DECK_Y + 3.4F, 6.0F),
			v(-0.6F, DECK_Y - 0.4F, 10.4F), v(0.6F, DECK_Y - 0.4F, 10.4F), v(0.55F, DECK_Y + 3.4F, 9.6F), v(-0.55F, DECK_Y + 3.4F, 9.6F));
		b.revolve(SUB_HULL, v(0, DECK_Y - 0.4F, 10.3F), v(0, 1, 0), new float[][] {{0.0F, 0.6F}, {3.8F, 0.55F}, {3.8F, 0.0F}}, 14);
		b.box(-0.56F, DECK_Y + 3.1F, 9.6F, 0.56F, DECK_Y + 3.3F, 10.4F, GLASS);
		b.box(-0.06F, DECK_Y + 3.4F, 7.0F, 0.06F, DECK_Y + 4.8F, 7.2F, STEEL); // periscopes and masts
		b.box(-0.08F, DECK_Y + 3.4F, 7.6F, 0.08F, DECK_Y + 4.3F, 7.8F, STEEL);
		b.box(-0.1F, DECK_Y + 3.4F, 8.1F, 0.1F, DECK_Y + 4.55F, 8.3F, BLACK);
		b.hexa(SUB_HULL,
			v(0.55F, DECK_Y + 2.0F, 7.6F), v(0.55F, DECK_Y + 2.0F, 8.9F), v(0.55F, DECK_Y + 2.14F, 8.9F), v(0.55F, DECK_Y + 2.14F, 7.6F),
			v(2.5F, DECK_Y + 2.0F, 7.9F), v(2.5F, DECK_Y + 2.0F, 8.7F), v(2.5F, DECK_Y + 2.1F, 8.7F), v(2.5F, DECK_Y + 2.1F, 7.9F));
		b.hexa(SUB_HULL,
			v(-0.55F, DECK_Y + 2.0F, 7.6F), v(-0.55F, DECK_Y + 2.0F, 8.9F), v(-0.55F, DECK_Y + 2.14F, 8.9F), v(-0.55F, DECK_Y + 2.14F, 7.6F),
			v(-2.5F, DECK_Y + 2.0F, 7.9F), v(-2.5F, DECK_Y + 2.0F, 8.7F), v(-2.5F, DECK_Y + 2.1F, 8.7F), v(-2.5F, DECK_Y + 2.1F, 7.9F));
		// cruciform stern: upper and lower rudders, stern planes with end plates
		for (int i = 0; i < 4; i++) {
			float a = i * Mth.HALF_PI;
			float cx = Mth.cos(a);
			float cy = Mth.sin(a);
			float rootR = 1.0F;
			float tipR = 3.4F;
			Vector3f n = v(-cy, cx, 0); // plate normal (perpendicular to the fin's span)
			Vector3f t = new Vector3f(n).mul(0.08F);
			Vector3f r0 = v(cx * rootR, AXIS_Y + cy * rootR, -15.6F);
			Vector3f r1 = v(cx * rootR, AXIS_Y + cy * rootR, -12.6F);
			Vector3f t0 = v(cx * tipR, AXIS_Y + cy * tipR, -15.4F);
			Vector3f t1 = v(cx * tipR, AXIS_Y + cy * tipR, -13.6F);
			b.hexa(SUB_HULL,
				new Vector3f(r0).sub(t), new Vector3f(r1).sub(t), new Vector3f(r1).add(t), new Vector3f(r0).add(t),
				new Vector3f(t0).sub(t), new Vector3f(t1).sub(t), new Vector3f(t1).add(t), new Vector3f(t0).add(t));
			if (i % 2 == 0) {
				// end plates on the stern planes
				float x = cx * tipR;
				b.box(x - 0.06F, AXIS_Y - 0.5F, -15.5F, x + 0.06F, AXIS_Y + 0.5F, -13.6F, SUB_HULL);
			}
		}
		// shrouded propulsor
		b.revolve(SUB_HULL, v(0, AXIS_Y, -17.4F), fwd, new float[][] {{0.0F, 1.15F}, {1.2F, 1.05F}}, 22);
		b.revolve(STEEL, v(0, AXIS_Y, -17.2F), fwd, new float[][] {{0.0F, 0.0F}, {0.0F, 0.3F}, {0.5F, 0.3F}, {0.5F, 0.0F}}, 12);
		for (int i = 0; i < 7; i++) {
			float a = i * Mth.TWO_PI / 7;
			b.beam(v(0, AXIS_Y, -16.95F), v(Mth.cos(a) * 1.0F, AXIS_Y + Mth.sin(a) * 1.0F, -16.85F), 0.25F, 0.04F, BLACK);
		}
		// draft marks on the bow
		for (int k = 0; k < 4; k++) {
			float y = AXIS_Y - 1.6F + k * 0.5F;
			b.box(-1.62F, y, 13.9F, -1.55F, y + 0.08F, 14.3F, WHITE);
			b.box(1.55F, y, 13.9F, 1.62F, y + 0.08F, 14.3F, WHITE);
		}
		return b.build();
	}
}
