package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.rcm.ballistic.BallisticMissiles;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.Mth;

/**
 * Rocket and jet exhaust plumes, drawn as light by their own shader ({@code exhaust_plume}): a white-hot
 * core out of the nozzle cooling through yellow and orange to a red, ragged, flickering tip, boiling with
 * turbulence that streams away down the plume, soft-edged like a volume of glowing gas. Three shells: the
 * narrow core, the plume, and a faint wide envelope round it. At altitude, where the air is thin, the
 * plume balloons out ({@code expand}).
 */
public final class ExhaustPlume {
	/** A solid (or liquid) rocket motor. */
	public static final int ROCKET = 0;
	/** A jet engine or afterburner: blue-white core and shock diamonds. */
	public static final int JET = 1;
	/** Re-entry plasma, a meteor's burning trail. */
	public static final int PLASMA = 2;

	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/exhaust_plume"))
		.withVertexShader(BallisticMissiles.id("core/exhaust_plume"))
		.withFragmentShader(BallisticMissiles.id("core/exhaust_plume"))
		// light: added to what is behind
		.withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE))
		.withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
		.withDepthWrite(false)
		.withCull(false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();
	public static final RenderType TYPE = RenderType.create("ballisticmissiles_exhaust_plume", RenderSetup.builder(PIPELINE).createRenderSetup());

	private ExhaustPlume() {
	}

	public static void init() {
		RenderPipelines.register(PIPELINE);
	}

	/** A rocket plume down -Y from the origin. */
	public static void plume(PoseStack.Pose pose, VertexConsumer consumer, float radius, float length, float time, float alpha) {
		plume(pose, consumer, radius, length, time, alpha, ROCKET, 1.0F);
	}

	/**
	 * A plume down -Y from the origin: nozzle {@code radius}, {@code length}, {@code alpha}, {@code style},
	 * and how far it balloons in thin air ({@code expand}, 1 at sea level).
	 */
	public static void plume(PoseStack.Pose pose, VertexConsumer consumer, float radius, float length, float time, float alpha, int style, float expand) {
		if (alpha <= 0.01F || length <= 0.01F) {
			return;
		}
		int phase = Math.floorMod(Math.round(radius * 977.0F + length * 131.0F), 256);
		// the hot core, the plume itself, a faint wide envelope of cooler gas
		shell(pose, consumer, radius * 0.5F, length * 0.7F, 1.0F, alpha * 0.75F, style, phase, expand * 0.8F, time);
		shell(pose, consumer, radius, length, 0.72F, alpha * 0.9F, style, (phase + 85) & 255, expand, time);
		shell(pose, consumer, radius * 1.6F, length * 0.85F, 0.35F, alpha * 0.22F, style, (phase + 170) & 255, expand * 1.1F, time);
	}

	private static void shell(PoseStack.Pose pose, VertexConsumer consumer, float radius, float length, float heat, float alpha, int style, int phase,
		float expand, float time) {
		final int segments = 20;
		final int rings = 12;
		int color = Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24 | Math.round(heat * 255.0F) << 16 | (style * 80) << 8 | phase;
		int overlay = Math.round(Math.min(length, 2000.0F) * 16.0F) | Math.min(32767, Math.round(radius * 256.0F)) << 16;
		// the length breathes a little with the motor's roar
		float breathe = 1.0F + 0.06F * Mth.sin(time * 1.7F + phase) + 0.04F * Mth.sin(time * 4.3F + phase * 0.3F);
		float len = length * breathe;
		for (int ring = 0; ring < rings; ring++) {
			float f0 = (float) ring / rings;
			float f1 = (float) (ring + 1) / rings;
			float y0 = -f0 * len;
			float y1 = -f1 * len;
			float r0 = radiusAt(radius, f0, expand);
			float r1 = radiusAt(radius, f1, expand);
			// the slope of the shell, for its normals
			float slope = (r1 - r0) / Math.max(1.0E-4F, y0 - y1);
			for (int s = 0; s < segments; s++) {
				float a0 = Mth.TWO_PI * s / segments;
				float a1 = Mth.TWO_PI * (s + 1) / segments;
				float u0 = (float) s / segments;
				float u1 = (float) (s + 1) / segments;
				vertex(consumer, pose, a0, r0, y0, u0, f0, color, overlay, slope);
				vertex(consumer, pose, a1, r0, y0, u1, f0, color, overlay, slope);
				vertex(consumer, pose, a1, r1, y1, u1, f1, color, overlay, slope);
				vertex(consumer, pose, a0, r1, y1, u0, f1, color, overlay, slope);
			}
		}
	}

	/** Out of the nozzle it swells (under-expanded exhaust, more so in thin air), then narrows to the tip. */
	private static float radiusAt(float nozzle, float f, float expand) {
		float swell = Mth.sin(Mth.PI * Math.min(1.0F, f * 1.5F)) * (0.55F + 0.5F * (expand - 1.0F));
		return nozzle * (1.0F + swell) * (1.0F - f * (0.75F - 0.25F * Math.min(1.0F, expand - 1.0F)));
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float angle, float r, float y, float u, float v, int color, int overlay,
		float slope) {
		float c = Mth.cos(angle);
		float s = Mth.sin(angle);
		float inv = Mth.invSqrt(1.0F + slope * slope);
		consumer.addVertex(pose, c * r, y, s * r).setColor(color).setUv(u, v).setOverlay(overlay).setLight(LightTexture.FULL_BRIGHT)
			.setNormal(pose, c * inv, slope * inv, s * inv);
	}
}
