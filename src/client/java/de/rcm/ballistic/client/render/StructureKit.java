package de.rcm.ballistic.client.render;

import de.rcm.ballistic.BallisticMissiles;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Vector3f;

/**
 * Shared texture atlas and building blocks (lattice towers, floodlight masts) for the large
 * launch-site structures drawn around the launch pad, silo and radar blocks.
 */
final class StructureKit {
	static final RenderType TYPE = RenderTypes.entityCutout(BallisticMissiles.id("textures/entity/structures.png"));
	static final RenderType GLOW_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/structures.png"));

	// patches of textures/entity/structures.png
	static final int CONCRETE = 0;
	static final int CONCRETE_DARK = 1;
	static final int STEEL = 2;
	static final int RED = 3;
	static final int WHITE = 4;
	static final int HAZARD = 5;
	static final int BLACK = 6;
	static final int GRATING = 7;
	static final int YELLOW = 8;
	static final int OLIVE = 9;
	static final int GLASS = 10;
	static final int LAMP = 11;
	static final int RED_LAMP = 12;
	static final int RUST = 13;
	static final int SOOT = 14;
	static final int CABLE = 15;
	static final int DISH = 16;
	static final int SHAFT = 17;
	static final int PANEL = 18;
	static final int GREEN_LAMP = 19;
	static final int DOOR = 20;
	static final int VENT = 21;
	static final int OLIVE_DARK = 22;
	static final int ARRAY = 23;
	static final int HAZE = 24;
	static final int HAZE_DARK = 25;
	static final int BEAM = 26;
	static final int PURPLE_LAMP = 27;
	static final int SUB_HULL = 28;
	static final int MOON_ROCK = 29;
	static final int SUB_TILES = 30;
	static final int SUB_DECK = 31;
	static final int BEAM_HAZE = 32;
	static final int HOT = 33;
	static final int FLASH = 34;
	static final int MINE = 35;
	static final int WOOD = 36;
	static final int GUNMETAL = 37;
	static final int BAKELITE = 38;
	static final int LENS = 39;
	static final int BOMB_GRAY = 40;
	static final int TUNGSTEN = 41;
	static final int ABLATIVE = 42;
	static final int SHELTER = 43;
	static final int AK_MAG = 44;
	static final int AK_BLUED = 45;
	static final int AK_LAMINATE = 46;
	static final int AK_GRIP = 47;
	static final int BRASS = 48;
	static final int AK_BRIGHT = 49;
	static final int AK_WORN = 50;
	static final int SG_WALNUT = 51;
	static final int SG_PARK = 52;
	static final int SG_PAD = 53;
	static final int SG_HULL = 54;
	static final int SG_CHECKER = 55;
	static final int SG_BORE = 56;
	static final int GORE_FLESH = 57;
	static final int GORE_BONE = 58;
	static final int GORE_CLOTH = 59;
	static final int GORE_BLOOD = 60;
	static final int GORE_BRAIN = 61;
	static final int GORE_SKIN = 62;

	private StructureKit() {
	}

	static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/**
	 * Square lattice tower centred on (cx, cz), tapering from {@code halfBottom} to {@code halfTop},
	 * split into {@code segments} bays with horizontal girts and X-bracing on every face. Leg colours
	 * alternate between the two patches per bay (aviation red/white striping).
	 */
	static void lattice(BoxMesh.Builder b, float cx, float cz, float halfBottom, float halfTop, float y0, float y1, int segments, float leg,
		int patchA, int patchB, int brace) {
		float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
		for (int s = 0; s < segments; s++) {
			float f0 = (float) s / segments;
			float f1 = (float) (s + 1) / segments;
			float ya = y0 + (y1 - y0) * f0;
			float yb = y0 + (y1 - y0) * f1;
			float ha = halfBottom + (halfTop - halfBottom) * f0;
			float hb = halfBottom + (halfTop - halfBottom) * f1;
			int legPatch = s % 2 == 0 ? patchA : patchB;
			for (int c = 0; c < 4; c++) {
				float[] p = corners[c];
				float[] q = corners[(c + 1) % 4];
				b.beam(v(cx + p[0] * ha, ya, cz + p[1] * ha), v(cx + p[0] * hb, yb, cz + p[1] * hb), leg, leg, legPatch);
				// girt at the top of the bay and one diagonal brace per face, alternating direction
				b.beam(v(cx + p[0] * hb, yb, cz + p[1] * hb), v(cx + q[0] * hb, yb, cz + q[1] * hb), leg * 0.6F, leg * 0.6F, brace);
				boolean flip = (s + c) % 2 == 0;
				Vector3f lo = flip ? v(cx + p[0] * ha, ya, cz + p[1] * ha) : v(cx + q[0] * ha, ya, cz + q[1] * ha);
				Vector3f hi = flip ? v(cx + q[0] * hb, yb, cz + q[1] * hb) : v(cx + p[0] * hb, yb, cz + p[1] * hb);
				b.beam(lo, hi, leg * 0.45F, leg * 0.45F, brace);
			}
		}
	}

	/** Floodlight mast: pole, crossbar with four lamps facing {@code towardX/Z}. */
	static void floodlight(BoxMesh.Builder b, float x, float z, float height, float towardX, float towardZ) {
		b.box(x - 0.09F, 0.0F, z - 0.09F, x + 0.09F, height, z + 0.09F, STEEL);
		b.box(x - 0.25F, 0.0F, z - 0.25F, x + 0.25F, 0.15F, z + 0.25F, CONCRETE_DARK);
		float len = (float) Math.sqrt(towardX * towardX + towardZ * towardZ);
		float dx = towardX / len;
		float dz = towardZ / len;
		// crossbar perpendicular to the beam direction
		float px = -dz * 0.5F;
		float pz = dx * 0.5F;
		b.beam(v(x - px, height, z - pz), v(x + px, height, z + pz), 0.08F, 0.08F, STEEL);
		for (float t : new float[] {-0.35F, -0.12F, 0.12F, 0.35F}) {
			float lx = x + px * t * 2.0F + dx * 0.12F;
			float lz = z + pz * t * 2.0F + dz * 0.12F;
			b.box(lx - 0.09F, height - 0.12F, lz - 0.09F, lx + 0.09F, height + 0.08F, lz + 0.09F, BLACK);
		}
	}

	/** The glowing lamp faces of a {@link #floodlight}, for the emissive pass. */
	static void floodlightGlow(BoxMesh.Builder b, float x, float z, float height, float towardX, float towardZ) {
		float len = (float) Math.sqrt(towardX * towardX + towardZ * towardZ);
		float dx = towardX / len;
		float dz = towardZ / len;
		float px = -dz * 0.5F;
		float pz = dx * 0.5F;
		for (float t : new float[] {-0.35F, -0.12F, 0.12F, 0.35F}) {
			float lx = x + px * t * 2.0F + dx * 0.22F;
			float lz = z + pz * t * 2.0F + dz * 0.22F;
			b.box(lx - 0.07F, height - 0.1F, lz - 0.07F, lx + 0.07F, height + 0.06F, lz + 0.07F, LAMP);
		}
	}
}
