package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;

/**
 * A jet engine's exhaust, drawn as light rather than as a solid: everything here is added onto what is
 * behind it (additive blending, no depth written), so it glows and fades softly instead of showing hard
 * edges and rings.
 * <ul>
 *   <li>Idle and military power: no flame at all - a real turbofan shows none - just the hot turbine and
 *       nozzle glowing dull red-orange inside the exit, a little brighter as the throttle goes up.</li>
 *   <li>Afterburner: the nozzle glows yellow-white, and a long, translucent plume streams out of it - a hot
 *       core, then the string of bright shock diamonds (Mach disks) fading one after the other, then an
 *       orange tail dying away - flickering as it burns.</li>
 * </ul>
 * In the pose's space the nozzle exit is at the origin and the exhaust goes down -Y, as {@code
 * MissileRenderer.flame} draws it. Built from soft-edged crossed ribbons, so it looks full from any side, plus
 * a disc facing back down the plume for the view from behind.
 */
public final class JetExhaust {
	/** Additive light, not written to the depth buffer (triangles). */
	public static final RenderType TYPE = RenderTypes.dragonRays();
	private static final int RIBBONS = 4;
	private static final int STEPS = 14;
	private static final int DIAMONDS = 6;

	private JetExhaust() {
	}

	/**
	 * @param radius   the nozzle's radius (blocks)
	 * @param throttle 0..1
	 * @param burner   whether the afterburner is lit
	 * @param length   how far the afterburner plume reaches (blocks)
	 * @param time     ticks, for the flicker
	 */
	public static void draw(PoseStack.Pose pose, VertexConsumer c, float radius, float throttle, boolean burner, float length, float time) {
		// the glow inside the nozzle
		float heat = burner ? 1.0F : 0.22F + 0.45F * Mth.clamp(throttle, 0.0F, 1.0F);
		float[] inner = burner ? new float[] {1.0F, 0.86F, 0.6F} : new float[] {1.0F, 0.38F, 0.12F};
		disc(pose, c, 0.04F, radius * 0.92F, inner, heat * (0.9F + 0.1F * Mth.sin(time * 2.9F)));
		if (!burner) {
			// a faint hot shimmer just behind the exit, barely there
			plume(pose, c, radius * 0.8F, 0.6F + 1.4F * throttle, time, new float[] {1.0F, 0.45F, 0.15F}, 0.12F * throttle, false);
			return;
		}
		float flicker = 0.9F + 0.1F * Mth.sin(time * 2.3F) * Mth.cos(time * 3.7F);
		float len = length * flicker;
		// the plume: a hot core near the exit, cooling to orange and dying away
		plume(pose, c, radius * 1.35F, len, time, new float[] {1.0F, 0.6F, 0.3F}, 0.75F, true);
		plume(pose, c, radius * 0.7F, len * 0.6F, time + 3.0F, new float[] {1.0F, 0.85F, 0.6F}, 0.9F, true);
		// the shock diamonds, one behind the other, each a little fainter
		float spacing = radius * 1.65F;
		for (int k = 0; k < DIAMONDS; k++) {
			float fade = 1.0F - k / (float) DIAMONDS;
			float y = -(radius * 0.9F + k * spacing) * (0.97F + 0.03F * Mth.sin(time * 5.0F + k));
			float w = radius * (0.85F - 0.06F * k);
			diamond(pose, c, y, w, spacing * 0.5F, new float[] {1.0F, 0.92F, 0.8F}, fade * fade);
		}
		// seen from behind: the bright mouth of the burner
		disc(pose, c, -0.05F, radius * 1.15F, new float[] {1.0F, 0.75F, 0.45F}, 0.45F);
	}

	/**
	 * Soft-edged ribbons crossed about the axis, {@code length} long, bright down the middle and fading to
	 * nothing at their edges and their end.
	 */
	private static void plume(PoseStack.Pose pose, VertexConsumer c, float radius, float length, float time, float[] col, float strength,
		boolean taper) {
		for (int ribbon = 0; ribbon < RIBBONS; ribbon++) {
			float a = ribbon * Mth.PI / RIBBONS;
			float ax = Mth.cos(a);
			float az = Mth.sin(a);
			for (int i = 0; i < STEPS; i++) {
				float f0 = i / (float) STEPS;
				float f1 = (i + 1) / (float) STEPS;
				float w0 = width(radius, f0, time, taper);
				float w1 = width(radius, f1, time, taper);
				float b0 = brightness(f0) * strength;
				float b1 = brightness(f1) * strength;
				float y0 = -f0 * length;
				float y1 = -f1 * length;
				// centre line bright, the two edges dark: two triangles each side
				quadTo(pose, c, 0, y0, 0, b0, 0, y1, 0, b1, ax * w1, y1, az * w1, ax * w0, y0, az * w0, col, f0, f1);
				quadTo(pose, c, 0, y0, 0, b0, 0, y1, 0, b1, -ax * w1, y1, -az * w1, -ax * w0, y0, -az * w0, col, f0, f1);
			}
		}
	}

	/** Half-width of the plume a fraction {@code f} down it: swelling a little out of the nozzle, then narrowing. */
	private static float width(float radius, float f, float time, boolean taper) {
		float swell = 1.0F + 0.35F * Mth.sin(Mth.PI * Math.min(1.0F, f * 2.2F));
		float wobble = 1.0F + 0.05F * Mth.sin(time * 1.3F + f * 11.0F);
		return radius * swell * wobble * (taper ? 1.0F - 0.55F * f : 1.0F + 0.8F * f);
	}

	/** How bright the plume is down its length: strongest just out of the nozzle, gone at the end. */
	private static float brightness(float f) {
		return (1.0F - f) * (1.0F - f) * (0.6F + 0.4F * Mth.clamp(f * 8.0F, 0.0F, 1.0F));
	}

	/** Two triangles from the centre line (bright) out to an edge (black: adds nothing). */
	private static void quadTo(PoseStack.Pose pose, VertexConsumer c, float cx0, float cy0, float cz0, float b0, float cx1, float cy1, float cz1, float b1,
		float ex1, float ey1, float ez1, float ex0, float ey0, float ez0, float[] col, float f0, float f1) {
		// the colour cools down the plume: yellow-white at the nozzle, orange-red at the tail
		float[] c0 = cool(col, f0);
		float[] c1 = cool(col, f1);
		vertex(pose, c, cx0, cy0, cz0, c0, b0);
		vertex(pose, c, cx1, cy1, cz1, c1, b1);
		vertex(pose, c, ex1, ey1, ez1, c1, 0.0F);
		vertex(pose, c, cx0, cy0, cz0, c0, b0);
		vertex(pose, c, ex1, ey1, ez1, c1, 0.0F);
		vertex(pose, c, ex0, ey0, ez0, c0, 0.0F);
	}

	private static float[] cool(float[] col, float f) {
		return new float[] {col[0], col[1] * (1.0F - 0.45F * f), col[2] * (1.0F - 0.75F * f)};
	}

	/** A shock diamond: a small bright spindle on the axis, from crossed rhombi glowing out of their middle. */
	private static void diamond(PoseStack.Pose pose, VertexConsumer c, float y, float halfWidth, float halfLength, float[] col, float strength) {
		for (int ribbon = 0; ribbon < 2; ribbon++) {
			float a = ribbon * Mth.HALF_PI + Mth.PI / 4.0F;
			float ax = Mth.cos(a) * halfWidth;
			float az = Mth.sin(a) * halfWidth;
			float[][] corners = {{0, y + halfLength, 0}, {ax, y, az}, {0, y - halfLength, 0}, {-ax, y, -az}};
			for (int i = 0; i < 4; i++) {
				float[] p = corners[i];
				float[] q = corners[(i + 1) % 4];
				vertex(pose, c, 0, y, 0, col, strength);
				vertex(pose, c, p[0], p[1], p[2], col, 0.0F);
				vertex(pose, c, q[0], q[1], q[2], col, 0.0F);
			}
		}
	}

	/** A glowing disc across the axis at {@code y}: bright in the middle, dark at its rim. */
	private static void disc(PoseStack.Pose pose, VertexConsumer c, float y, float radius, float[] col, float strength) {
		int segments = 20;
		for (int s = 0; s < segments; s++) {
			float a0 = s / (float) segments * Mth.TWO_PI;
			float a1 = (s + 1) / (float) segments * Mth.TWO_PI;
			vertex(pose, c, 0, y, 0, col, strength);
			vertex(pose, c, Mth.cos(a0) * radius, y, Mth.sin(a0) * radius, col, strength * 0.15F);
			vertex(pose, c, Mth.cos(a1) * radius, y, Mth.sin(a1) * radius, col, strength * 0.15F);
		}
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer c, float x, float y, float z, float[] col, float b) {
		float k = Mth.clamp(b, 0.0F, 1.0F);
		c.addVertex(pose, x, y, z).setColor(col[0] * k, col[1] * k, col[2] * k, 1.0F);
	}
}
