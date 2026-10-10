package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.injury.Wounds;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * The head wounds, in the head's own space (pixels: x -4..4 with +X the player's left, y -8 crown .. 0 jaw,
 * z -4 face .. 4 back), each in three versions - and each of those mirrored or not - so no two are alike:
 * <ul>
 *   <li>a graze: a furrow torn to the skull (across the side above the ear, over the crown onto the forehead,
 *       or across the back of the head), blood running down from it - decals only;</li>
 *   <li>the skull blown open: the entry hole in the forehead, the crater where it came out (back and to one
 *       side, or the top), the brain bulging out of it in soft lumps, splinters of skull round the rim, flaps
 *       of scalp hanging - the decals painted by tools/gen_gore.py from the same crater fields used here.</li>
 * </ul>
 */
public final class GoreHead {
	/** Where each version's crater is (as in gen_gore.py). */
	private static final Vector3f[] CRATERS = {new Vector3f(-3.4F, -7.9F, 2.7F), new Vector3f(3.2F, -7.6F, 3.0F), new Vector3f(-0.3F, -8.0F, 3.6F)};
	private static final String[] FACES = {"front", "left", "right", "top", "back"};
	private static final GoreMesh[] GRAZED = new GoreMesh[3];
	private static final GoreMesh[] SHATTERED = new GoreMesh[3];

	static {
		for (int v = 0; v < 3; v++) {
			GRAZED[v] = grazed(v);
			SHATTERED[v] = shattered(v);
		}
	}

	private GoreHead() {
	}

	/** {@code seed} picks the version and whether it is mirrored; {@code cut}: what of the head is gone (null: none). */
	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, int head, int seed, GoreMesh.Cut cut) {
		int v = Math.floorMod(seed, 3);
		GoreMesh mesh = head == Wounds.SHATTERED ? SHATTERED[v] : GRAZED[v];
		boolean mirror = ((seed >> 2) & 1) != 0;
		poseStack.pushPose();
		if (mirror) {
			poseStack.scale(-1.0F, 1.0F, 1.0F);
		}
		mesh.submit(poseStack, collector, light, cut == null || !mirror ? cut : (x, y, z) -> cut.test(-x, y, z));
		poseStack.popPose();
	}

	/** A decal over one whole face of the head, {@code out} pixels proud of it, mapped as gen_gore.py paints it. */
	static void face(GoreMesh.Builder b, String face, int tile, float out) {
		float o = 4.0F + out;
		Vector3f origin;
		Vector3f a;
		Vector3f c;
		switch (face) {
			case "front" -> {
				origin = new Vector3f(-4, -8, -o);
				a = new Vector3f(8, 0, 0);
				c = new Vector3f(0, 8, 0);
			}
			case "back" -> {
				origin = new Vector3f(-4, -8, o);
				a = new Vector3f(8, 0, 0);
				c = new Vector3f(0, 8, 0);
			}
			case "left" -> {
				origin = new Vector3f(o, -8, -4);
				a = new Vector3f(0, 0, 8);
				c = new Vector3f(0, 8, 0);
			}
			case "right" -> {
				origin = new Vector3f(-o, -8, -4);
				a = new Vector3f(0, 0, 8);
				c = new Vector3f(0, 8, 0);
			}
			default -> {
				origin = new Vector3f(-4, -4.0F - o, -4);
				a = new Vector3f(8, 0, 0);
				c = new Vector3f(0, 0, 8);
			}
		}
		decal(b, origin, a, c, tile, outward(face));
	}

	static Vector3f outward(String face) {
		return switch (face) {
			case "front" -> new Vector3f(0, 0, -1);
			case "back" -> new Vector3f(0, 0, 1);
			case "left" -> new Vector3f(1, 0, 0);
			case "right" -> new Vector3f(-1, 0, 0);
			default -> new Vector3f(0, -1, 0);
		};
	}

	/** A decal over the rectangle origin + a, + c, the tile's u along a and v along c, lit as facing {@code n}. */
	static void decal(GoreMesh.Builder b, Vector3f o, Vector3f a, Vector3f c, int tile, Vector3f n) {
		// in half-pixel cells, so a wound cut right through the body (or the jaw torn off) can take them with it
		b.decalGrid(o, a, c, tile, n, 0.5F);
	}

	/** The graze: decals only - the hat layer sits half a pixel out, so they go just beyond it. */
	private static GoreMesh grazed(int v) {
		GoreMesh.Builder b = new GoreMesh.Builder();
		for (int f = 0; f < 5; f++) {
			face(b, FACES[f], v * 5 + f, 0.56F);
		}
		return b.build();
	}

	private static GoreMesh shattered(int v) {
		GoreMesh.Builder b = new GoreMesh.Builder();
		// the hat layer is hidden with this wound: the decals lie right on the skin
		for (int f = 0; f < 5; f++) {
			face(b, FACES[f], 15 + v * 5 + f, 0.03F);
		}
		Vector3f crater = CRATERS[v];
		RandomSource r = RandomSource.create(1911L + v * 77L);
		// the brain bulging out wherever the crater lies on a face: bigger the nearer the face runs to its middle
		for (int f = 0; f < 5; f++) {
			Vector3f n = outward(FACES[f]);
			// the point of this face nearest the crater, kept off its edges
			Vector3f q = new Vector3f(Mth.clamp(crater.x, -3.2F, 3.2F), Mth.clamp(crater.y, -7.2F, -0.8F), Mth.clamp(crater.z, -3.2F, 3.2F));
			if (n.x != 0) {
				q.x = 4 * n.x;
			} else if (n.y != 0) {
				q.y = -8;
			} else {
				q.z = 4 * n.z;
			}
			float dist = q.distance(crater);
			if (dist > 1.7F) {
				continue;
			}
			float size = 1.6F - dist * 0.55F;
			Vector3f across = Math.abs(n.y) > 0.5F ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
			b.dome(q, n, across, size, size * (0.85F + r.nextFloat() * 0.2F), 0.3F + size * 0.25F, 31L + v * 7 + f, GoreMesh.T_BRAIN);
		}
		// splinters of skull standing out round the rim, here and there
		int made = 0;
		for (int i = 0; i < 80 && made < 11; i++) {
			Vector3f d = new Vector3f(r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1, r.nextFloat() * 2 - 1);
			if (d.lengthSquared() < 0.05F) {
				continue;
			}
			d.normalize();
			Vector3f rim = surface(new Vector3f(crater).add(new Vector3f(d).mul(2.35F + r.nextFloat() * 0.4F)));
			if (rim == null) {
				continue;
			}
			float dist = rim.distance(crater);
			if (dist < 1.9F || dist > 2.9F) {
				continue;
			}
			Vector3f normal = surfaceNormal(rim);
			Vector3f away = new Vector3f(rim).sub(crater).normalize();
			Vector3f up = new Vector3f(normal).mul(0.45F + r.nextFloat() * 0.3F).add(new Vector3f(away).mul(0.9F)).normalize();
			b.shard(rim, up, away, 0.4F + r.nextFloat() * 0.6F, 0.4F + r.nextFloat() * 0.45F, r.nextBoolean() ? GoreMesh.T_BONE : GoreMesh.T_BONE_THIN, 0.1F);
			made++;
		}
		// flaps of scalp torn back, hanging down the sides below the crater
		made = 0;
		for (int i = 0; i < 60 && made < 4; i++) {
			float a = r.nextFloat() * Mth.TWO_PI;
			Vector3f d = new Vector3f(Mth.cos(a), 0.6F + r.nextFloat() * 0.5F, Mth.sin(a)).normalize();
			Vector3f rim = surface(new Vector3f(crater).add(new Vector3f(d).mul(2.3F)));
			if (rim == null || rim.y < -7.9F) {
				continue;
			}
			Vector3f normal = surfaceNormal(rim);
			Vector3f down = new Vector3f(normal).mul(0.35F).add(0, 1, 0).normalize();
			b.shard(rim, down, new Vector3f(0, 1, 0), 0.9F + r.nextFloat() * 0.7F, 0.8F + r.nextFloat() * 0.4F, GoreMesh.T_FLAP, 0.15F);
			made++;
		}
		return b.build();
	}

	/** Moves a point onto the head's surface (the 8 px cube), or null at the jaw. */
	private static Vector3f surface(Vector3f p) {
		Vector3f q = new Vector3f(Mth.clamp(p.x, -4, 4), Mth.clamp(p.y, -8, 0), Mth.clamp(p.z, -4, 4));
		float dx = 4 - Math.abs(q.x);
		float dy = Math.min(q.y + 8, -q.y);
		float dz = 4 - Math.abs(q.z);
		if (dx <= dy && dx <= dz) {
			q.x = Math.signum(q.x == 0 ? 1 : q.x) * 4;
		} else if (dy <= dz) {
			q.y = q.y + 8 < -q.y ? -8 : 0;
		} else {
			q.z = Math.signum(q.z == 0 ? 1 : q.z) * 4;
		}
		return q.y >= -0.01F ? null : q;
	}

	private static Vector3f surfaceNormal(Vector3f q) {
		if (Math.abs(Math.abs(q.x) - 4) < 0.01F) {
			return new Vector3f(Math.signum(q.x), 0, 0);
		}
		if (Math.abs(q.y + 8) < 0.01F) {
			return new Vector3f(0, -1, 0);
		}
		return new Vector3f(0, 0, Math.signum(q.z));
	}
}
