package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.rcm.ballistic.BallisticMissiles;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * A jet engine's exhaust, as a real one looks.
 * <ul>
 *   <li>Idle and military power: no flame - a turbofan shows none - only the turbine and the inside of the
 *       nozzle glowing dull red, brighter as the throttle goes up, and a hint of heat just behind it.</li>
 *   <li>Afterburner: the whole nozzle filled with yellow-orange fire (the flame holder rings bright in it),
 *       and out of it a dense flame - yellow-white at the exit, orange further back, dying away red - with
 *       its string of shock diamonds: bright white-yellow knots on the axis, one behind the other, each a
 *       little weaker. Round it near the nozzle a pale blue-violet sheath. It flickers and pulses as it burns.</li>
 * </ul>
 * The flame is a volume: nested shells, each faint, so the middle (where the eye looks through all of them)
 * is brightest and the edge fades softly, from any side. Drawn emissive with a little cover ("premultiplied":
 * colour added, the alpha darkening what is behind), so it reads against a bright sky too, and with no faces
 * culled. In the pose's space the nozzle exit is at the origin and the exhaust goes down -Y.
 */
public final class JetExhaust {
	private static RenderType type;
	private static final int SEGMENTS = 18;
	private static final int STEPS = 22;
	/** The flame's shells, as fractions of its radius. */
	private static final float[] SHELLS = {1.0F, 0.78F, 0.56F, 0.36F, 0.18F};

	private JetExhaust() {
	}

	public static void init() {
		RenderPipelines.register(Pipeline.PIPELINE);
	}

	public static RenderType type() {
		if (type == null) {
			type = RenderType.create("ballisticmissiles_jet_exhaust", RenderSetup.builder(Pipeline.PIPELINE).createRenderSetup());
		}
		return type;
	}

	/** Additive light with a little cover, no faces culled, nothing written to the depth buffer. */
	private static final class Pipeline {
		static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
			.withLocation(BallisticMissiles.id("pipeline/jet_exhaust"))
			.withVertexShader(Identifier.withDefaultNamespace("core/rendertype_lightning"))
			.withFragmentShader(Identifier.withDefaultNamespace("core/rendertype_lightning"))
			.withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA))
			.withCull(false)
			.withDepthWrite(false)
			.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
			.build();
	}

	/**
	 * @param radius   the nozzle's radius (blocks)
	 * @param throttle 0..1
	 * @param burner   whether the afterburner is lit
	 * @param length   how far the afterburner flame reaches (blocks)
	 * @param time     ticks, for the flicker
	 */
	public static void draw(PoseStack.Pose pose, VertexConsumer c, float radius, float throttle, boolean burner, float length, float time) {
		float thr = Mth.clamp(throttle, 0.0F, 1.0F);
		if (!burner) {
			// the turbine and nozzle walls glowing dull red inside the exit
			float heat = (0.3F + 0.55F * thr) * (0.94F + 0.06F * Mth.sin(time * 2.9F));
			nozzle(pose, c, radius, new float[] {0.85F, 0.22F, 0.05F}, new float[] {0.55F, 0.1F, 0.02F}, heat, 0.75F);
			// hot gas just behind the exit, barely glowing
			float len = radius * (1.2F + 1.6F * thr);
			Profile hot = f -> radius * 0.9F * (1.0F + 0.25F * f);
			for (float s : new float[] {1.0F, 0.6F}) {
				shell(pose, c, len, f -> hot.at(f) * s, f -> new float[] {0.9F, 0.3F, 0.08F}, f -> 0.05F * thr * fade(f, 0.08F, 1.6F), 0.0F);
			}
			return;
		}
		float pulse = 1.0F + 0.06F * Mth.sin(time * 2.3F) * Mth.cos(time * 3.7F) + 0.03F * Mth.sin(time * 9.1F);
		float len = length * pulse;
		float core = len * 0.62F;
		// the nozzle full of fire, the flame holder rings brighter in it
		nozzle(pose, c, radius, new float[] {1.0F, 0.86F, 0.55F}, new float[] {1.0F, 0.5F, 0.15F}, 1.0F, 0.95F);
		float spacing = radius * 2.1F;
		// the flame: necking in and swelling out round each diamond, narrowing to its end
		Profile flame = f -> {
			float y = f * core;
			float wave = 1.0F + 0.07F * Mth.cos(Mth.TWO_PI * (y + radius * 0.4F) / spacing) * (1.0F - f);
			float flick = 1.0F + 0.04F * Mth.sin(time * 1.9F + f * 13.0F) + 0.03F * Mth.sin(time * 4.3F - f * 21.0F);
			return radius * 0.97F * wave * flick * (1.0F - 0.62F * (float) Math.pow(f, 1.4F));
		};
		for (float s : SHELLS) {
			shell(pose, c, core, f -> flame.at(f) * s, JetExhaust::flameColour, f -> 0.14F * fade(f, 0.02F, 1.3F), 0.85F);
		}
		// the long tail: orange, wider, thinning out
		Profile tail = f -> radius * (0.75F + 0.5F * f) * (1.0F + 0.06F * Mth.sin(time * 1.3F + f * 9.0F));
		for (float s : new float[] {1.0F, 0.55F}) {
			shell(pose, c, len, f -> tail.at(f) * s, f -> new float[] {1.0F, 0.42F - 0.2F * f, 0.1F - 0.06F * f},
				f -> 0.07F * fade(f, 0.15F, 2.2F), 0.45F);
		}
		// the pale blue-violet sheath round it near the nozzle
		Profile sheath = f -> radius * (1.05F + 0.15F * f) * (1.0F - 0.5F * f * f);
		shell(pose, c, radius * 3.2F, sheath, f -> new float[] {0.45F, 0.42F, 1.0F}, f -> 0.07F * fade(f, 0.02F, 1.8F), 0.0F);
		// the shock diamonds
		int count = Math.max(3, (int) (core / spacing));
		for (int k = 0; k < count; k++) {
			float y = radius * 0.75F + k * spacing + 0.04F * radius * Mth.sin(time * 6.0F + k * 1.7F);
			if (y > core * 0.92F) {
				break;
			}
			float f = y / core;
			float strength = (1.0F - f) * (1.0F - f) * (0.92F + 0.08F * Mth.sin(time * 8.0F + k));
			float w = flame.at(f) * 0.62F;
			diamond(pose, c, -y, w, spacing * 0.36F, strength);
		}
	}

	/** How bright the flame is a fraction {@code f} down it: quickly up at the start, dying away at the end. */
	private static float fade(float f, float rise, float power) {
		return Mth.clamp(f / rise, 0.0F, 1.0F) * (float) Math.pow(1.0F - f, power);
	}

	/** The afterburner flame's colour: yellow-white at the exit, orange, red at its end. */
	private static float[] flameColour(float f) {
		if (f < 0.35F) {
			float t = f / 0.35F;
			return new float[] {1.0F, Mth.lerp(t, 0.86F, 0.6F), Mth.lerp(t, 0.55F, 0.22F)};
		}
		float t = (f - 0.35F) / 0.65F;
		return new float[] {1.0F, Mth.lerp(t, 0.6F, 0.3F), Mth.lerp(t, 0.22F, 0.06F)};
	}

	@FunctionalInterface
	private interface Profile {
		float at(float f);
	}

	@FunctionalInterface
	private interface Colour {
		float[] at(float f);
	}

	/**
	 * A closed shell round the axis, {@code length} long: radius, colour and brightness by the fraction down it.
	 * {@code cover}: how much of what is behind it hides, against the brightness.
	 */
	private static void shell(PoseStack.Pose pose, VertexConsumer c, float length, Profile radius, Colour colour, Profile bright, float cover) {
		float[][] ring0 = null;
		for (int i = 0; i <= STEPS; i++) {
			float f = i / (float) STEPS;
			float r = radius.at(f);
			float b = bright.at(f);
			float[] col = colour.at(f);
			float[][] ring = new float[SEGMENTS + 1][];
			for (int s = 0; s <= SEGMENTS; s++) {
				float a = s / (float) SEGMENTS * Mth.TWO_PI;
				ring[s] = new float[] {Mth.cos(a) * r, -f * length, Mth.sin(a) * r, col[0] * b, col[1] * b, col[2] * b, b * cover};
			}
			if (ring0 != null) {
				for (int s = 0; s < SEGMENTS; s++) {
					quad(pose, c, ring0[s], ring0[s + 1], ring[s + 1], ring[s]);
				}
			}
			ring0 = ring;
		}
	}

	/** A shock diamond: a bright white-yellow knot on the axis, a spindle of nested shells, pink at its rim. */
	private static void diamond(PoseStack.Pose pose, VertexConsumer c, float y, float halfWidth, float halfLength, float strength) {
		float[][] layers = {{1.0F, 0.16F, 1.0F, 0.6F, 0.55F}, {0.7F, 0.24F, 1.0F, 0.85F, 0.7F}, {0.42F, 0.34F, 1.0F, 0.97F, 0.88F}};
		for (float[] layer : layers) {
			float w = halfWidth * layer[0];
			float h = halfLength * (0.55F + 0.45F * layer[0]);
			float b = layer[1] * strength;
			float[] col = {layer[2] * b, layer[3] * b, layer[4] * b, b * 0.5F};
			int lat = 8;
			float[][] ring0 = null;
			for (int i = 0; i <= lat; i++) {
				float t = i / (float) lat * Mth.PI;
				// a spindle: pointed at its ends, round in its middle
				float r = w * (float) Math.pow(Mth.sin(t), 1.4F);
				float yy = y + h * Mth.cos(t);
				float[][] ring = new float[SEGMENTS + 1][];
				for (int s = 0; s <= SEGMENTS; s++) {
					float a = s / (float) SEGMENTS * Mth.TWO_PI;
					ring[s] = new float[] {Mth.cos(a) * r, yy, Mth.sin(a) * r, col[0], col[1], col[2], col[3]};
				}
				if (ring0 != null) {
					for (int s = 0; s < SEGMENTS; s++) {
						quad(pose, c, ring0[s], ring0[s + 1], ring[s + 1], ring[s]);
					}
				}
				ring0 = ring;
			}
		}
	}

	/**
	 * The inside of the nozzle, seen through its exit: a disc set just inside, {@code hot} in the middle and
	 * round the flame holder ring, {@code rim} at the wall.
	 */
	private static void nozzle(PoseStack.Pose pose, VertexConsumer c, float radius, float[] hot, float[] rim, float heat, float cover) {
		float[] radii = {0.0F, 0.3F, 0.45F, 0.58F, 0.75F, 0.95F};
		float[] glow = {1.0F, 0.8F, 1.0F, 0.75F, 0.7F, 0.45F};
		float y = 0.06F;
		for (int i = 0; i < radii.length - 1; i++) {
			for (int s = 0; s < SEGMENTS; s++) {
				float a0 = s / (float) SEGMENTS * Mth.TWO_PI;
				float a1 = (s + 1) / (float) SEGMENTS * Mth.TWO_PI;
				float[] p00 = disc(radius * radii[i], a0, y, hot, rim, glow[i] * heat, radii[i], cover);
				float[] p01 = disc(radius * radii[i], a1, y, hot, rim, glow[i] * heat, radii[i], cover);
				float[] p11 = disc(radius * radii[i + 1], a1, y, hot, rim, glow[i + 1] * heat, radii[i + 1], cover);
				float[] p10 = disc(radius * radii[i + 1], a0, y, hot, rim, glow[i + 1] * heat, radii[i + 1], cover);
				quad(pose, c, p00, p01, p11, p10);
			}
		}
	}

	private static float[] disc(float r, float a, float y, float[] hot, float[] rim, float b, float t, float cover) {
		return new float[] {Mth.cos(a) * r, y, Mth.sin(a) * r, Mth.lerp(t, hot[0], rim[0]) * b, Mth.lerp(t, hot[1], rim[1]) * b,
			Mth.lerp(t, hot[2], rim[2]) * b, cover};
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer c, float[] a, float[] b, float[] d, float[] e) {
		vertex(pose, c, a);
		vertex(pose, c, b);
		vertex(pose, c, d);
		vertex(pose, c, a);
		vertex(pose, c, d);
		vertex(pose, c, e);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer c, float[] v) {
		c.addVertex(pose, v[0], v[1], v[2]).setColor(Mth.clamp(v[3], 0.0F, 1.0F), Mth.clamp(v[4], 0.0F, 1.0F), Mth.clamp(v[5], 0.0F, 1.0F),
			Mth.clamp(v[6], 0.0F, 1.0F));
	}
}
