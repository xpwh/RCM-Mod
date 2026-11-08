package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.ModConfig;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * A physically based sky over the Overworld in place of Minecraft's flat gradient (shader:
 * {@code shaders/core/sky.fsh}, model: {@code include/atmosphere.glsl}): blue by day, a deep orange
 * horizon and a pale glow round the sun at sunset, the Earth's shadow rising in the east at dusk with
 * the pink Belt of Venus above it. It fades out at night, leaving Minecraft's stars and moon. The fog
 * the land fades into takes the colour of this sky at the horizon (computed here, on the CPU, with the
 * same model), so the land meets the sky without a seam; the volumetric clouds are lit by the same sun.
 */
public final class Sky {
	/** How far out the quad over the whole view is drawn (blocks). */
	private static final float FAR = 500.0F;
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/sky"))
		.withUniform("BmWind", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER)
		.withVertexShader(BallisticMissiles.id("core/sky"))
		.withFragmentShader(BallisticMissiles.id("core/sky"))
		.withBlend(BlendFunction.TRANSLUCENT)
		.withCull(false)
		.withDepthWrite(false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();
	private static RenderType type;

	private Sky() {
	}

	public static void init() {
		RenderPipelines.register(PIPELINE);
		// before the entities: the solid world is drawn (and hides the sky where it stands), particles,
		// smoke and translucent things still come after
		WorldRenderEvents.BEFORE_ENTITIES.register(Sky::render);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(Sky::tickRainbow);
	}

	// ------------------------------------------------------------------ the rainbow

	/** How long a rainbow stays after the rain (ticks), give or take. */
	private static final int BOW_LENGTH = 3600;
	private static boolean wet;
	private static boolean stormy;
	private static long bowStart = -1L;
	private static long bowEnd = -1L;
	private static final net.minecraft.util.RandomSource BOW_RANDOM = net.minecraft.util.RandomSource.create();

	/**
	 * Watches the weather: when a shower or a thunderstorm passes over and the rain eases off, there may be a
	 * rainbow (after a thunderstorm there always is), opposite the sun, for a few minutes.
	 */
	private static void tickRainbow(Minecraft mc) {
		if (mc.level == null) {
			wet = stormy = false;
			bowStart = bowEnd = -1L;
			return;
		}
		long now = mc.level.getGameTime();
		float rain = mc.level.getRainLevel(1.0F);
		if (rain > 0.6F) {
			wet = true;
			stormy |= mc.level.getThunderLevel(1.0F) > 0.3F;
			if (rain > 0.8F) {
				bowStart = bowEnd = -1L; // raining hard again: gone
			}
		} else if (wet && rain < 0.45F) {
			wet = false;
			if (stormy || BOW_RANDOM.nextFloat() < 0.6F) {
				bowStart = now;
				bowEnd = now + BOW_LENGTH / 2 + BOW_RANDOM.nextInt(BOW_LENGTH);
			}
			stormy = false;
		}
	}

	/** How strong the rainbow is now, 0-1: there is one, the sun is low enough (under 42 degrees) and up. */
	private static float rainbow(Minecraft mc, Vector3f sun, float partialTick) {
		if (bowStart < 0L || !ModConfig.rainbow) {
			return 0.0F;
		}
		float now = mc.level.getGameTime() + partialTick;
		float in = smooth((now - bowStart) / 400.0F);
		float out = smooth((bowEnd - now) / 900.0F);
		if (out <= 0.0F) {
			bowStart = bowEnd = -1L;
			return 0.0F;
		}
		// the sun high: the bow's top sinks below the horizon; the sun down: no light for it
		float low = smooth((0.66F - sun.y) / 0.12F) * smooth(sun.y / 0.05F);
		// only where it was raining: not over a desert or snow
		net.minecraft.core.BlockPos at = mc.gameRenderer.getMainCamera().blockPosition();
		if (mc.level.getBiome(at).value().getPrecipitationAt(at, mc.level.getSeaLevel()) != net.minecraft.world.level.biome.Biome.Precipitation.RAIN) {
			return 0.0F;
		}
		float rain = mc.level.getRainLevel(partialTick);
		return in * out * low * (1.0F - 0.7F * smooth((rain - 0.35F) / 0.3F));
	}

	private static RenderType type() {
		if (type == null) {
			type = RenderType.create("ballisticmissiles_sky", RenderSetup.builder(PIPELINE).createRenderSetup());
		}
		return type;
	}

	/** Whether the sky is ours here: switched on, in the Overworld, the camera in open air. */
	private static boolean active(Minecraft mc) {
		return ModConfig.realSky && mc.level != null && mc.level.dimension() == Level.OVERWORLD
			&& mc.gameRenderer.getMainCamera().getFluidInCamera() == FogType.NONE;
	}

	/** Direction to the sun (it moves through the x-y plane, rising in the east). */
	static Vector3f sun(Minecraft mc, float partialTick) {
		float angle = mc.gameRenderer.getMainCamera().attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) * Mth.DEG_TO_RAD;
		return new Vector3f(-Mth.sin(angle), Mth.cos(angle), 0.0F);
	}

	/** How much of the sky is ours: all of it by day and in twilight, none deep in the night. */
	private static float cover(float sunY) {
		return smooth((sunY + 0.3F) / 0.18F);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (!active(mc)) {
			return;
		}
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vector3f sun = sun(mc, partialTick);
		float cover = cover(sun.y);
		// at night only for a storm on the horizon (its flashes and the stars it hides)
		if (cover <= 0.0F && !StormFront.visible()) {
			return;
		}
		float rain = mc.level.getRainLevel(partialTick);
		float thunder = mc.level.getThunderLevel(partialTick);
		ModConfig.Quality quality = ModConfig.cloudQuality;
		// blue: the quality in the top two bits, the rainbow's strength in the low six
		int bow = Math.round(rainbow(mc, sun, partialTick) * 63.0F);
		int color = Math.round(cover * 255.0F) << 24 | Math.round(rain * 255.0F) << 16 | Math.round(thunder * 255.0F) << 8
			| quality.ordinal() << 6 | bow;
		Camera camera = mc.gameRenderer.getMainCamera();
		VertexConsumer consumer = context.consumers().getBuffer(type());
		PoseStack.Pose pose = context.matrices().last();
		org.joml.Vector3fc f = camera.forwardVector();
		org.joml.Vector3fc u = camera.upVector();
		org.joml.Vector3fc l = camera.leftVector();
		float w = 6.0F;
		float[][] corners = {{w, -w}, {-w, -w}, {-w, w}, {w, w}};
		for (float[] c : corners) {
			// far out (FAR blocks), not a block in front of the eye: the walking bob shifts the eye a little, and
			// that would swing the whole sky - the sun, the shafts - with every step; this far it does not show
			float x = (f.x() + l.x() * c[0] + u.x() * c[1]) * FAR;
			float y = (f.y() + l.y() * c[0] + u.y() * c[1]) * FAR;
			float z = (f.z() + l.z() * c[0] + u.z() * c[1]) * FAR;
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(0.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0)
				.setNormal(pose, sun.x, sun.y, sun.z);
		}
	}

	// ------------------------------------------------------------------ the fog colour

	private static final Vector3f FOG = new Vector3f();

	/**
	 * The fog colour Minecraft is about to use, turned towards the colour of this sky at the horizon in
	 * the direction the camera looks (by day and in twilight; at night Minecraft's own).
	 */
	public static void fogColor(Vector4f vanilla, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (!active(mc)) {
			return;
		}
		Vector3f sun = sun(mc, partialTick);
		float cover = cover(sun.y);
		if (cover <= 0.0F) {
			return;
		}
		Camera camera = mc.gameRenderer.getMainCamera();
		float rain = mc.level.getRainLevel(partialTick);
		float thunder = mc.level.getThunderLevel(partialTick);
		org.joml.Vector3fc f = camera.forwardVector();
		double hx = f.x();
		double hz = f.z();
		double hl = Math.sqrt(hx * hx + hz * hz);
		if (hl < 1.0E-3) {
			hx = 1.0;
			hz = 0.0;
			hl = 1.0;
		}
		double[] rd = {hx / hl * 0.9985, 0.055, hz / hl * 0.9985};
		double[] col = toneMap(atmosphere(rd, new double[] {sun.x, sun.y, sun.z}, 1.0 + 5.0 * rain, 12, 4));
		double lum = 0.2126 * col[0] + 0.7152 * col[1] + 0.0722 * col[2];
		float wet = Mth.clamp(rain * 0.85F, 0.0F, 0.85F);
		float dim = 1.0F - 0.45F * thunder;
		for (int i = 0; i < 3; i++) {
			col[i] = Mth.lerp(wet, col[i], lum * 0.85 * (i == 0 ? 0.95 : i == 1 ? 0.97 : 1.0)) * dim;
		}
		FOG.set((float) col[0], (float) col[1], (float) col[2]);
		vanilla.set(Mth.lerp(cover, vanilla.x, FOG.x), Mth.lerp(cover, vanilla.y, FOG.y), Mth.lerp(cover, vanilla.z, FOG.z), vanilla.w);
	}

	// ------------------------------------------------------------------ the model, as in atmosphere.glsl

	private static final double RE = 6360.0;
	private static final double RA = 6460.0;
	private static final double EYE = RE + 0.2;
	private static final double[] BR = {5.8e-3, 13.5e-3, 33.1e-3};
	private static final double BM = 4.0e-3;
	private static final double[] BO = {0.65e-3, 1.881e-3, 0.085e-3};
	private static final double HR = 8.0;
	private static final double HM = 1.2;

	private static double ozone(double h) {
		return Math.max(0.0, 1.0 - Math.abs(h - 25.0) / 15.0);
	}

	private static double exit(double[] o, double[] d, double r) {
		double b = o[0] * d[0] + o[1] * d[1] + o[2] * d[2];
		double c = o[0] * o[0] + o[1] * o[1] + o[2] * o[2] - r * r;
		return -b + Math.sqrt(Math.max(b * b - c, 0.0));
	}

	private static double ground(double[] o, double[] d) {
		double b = o[0] * d[0] + o[1] * d[1] + o[2] * d[2];
		double c = o[0] * o[0] + o[1] * o[1] + o[2] * o[2] - RE * RE;
		double disc = b * b - c;
		if (disc < 0.0) {
			return -1.0;
		}
		double t = -b - Math.sqrt(disc);
		return t > 0.0 ? t : -1.0;
	}

	private static double height(double x, double y, double z) {
		return Math.sqrt(x * x + y * y + z * z) - RE;
	}

	static double[] atmosphere(double[] rd, double[] sun, double haze, int viewSteps, int lightSteps) {
		double[] o = {0.0, EYE, 0.0};
		double tMax = exit(o, rd, RA);
		double tg = ground(o, rd);
		if (tg > 0.0) {
			tMax = tg;
		}
		double seg = tMax / viewSteps;
		double odR = 0.0;
		double odM = 0.0;
		double odO = 0.0;
		double[] sumR = new double[3];
		double[] sumM = new double[3];
		for (int i = 0; i < viewSteps; i++) {
			double t = seg * (i + 0.5);
			double[] p = {o[0] + rd[0] * t, o[1] + rd[1] * t, o[2] + rd[2] * t};
			double h = height(p[0], p[1], p[2]);
			double dr = Math.exp(-h / HR) * seg;
			double dm = Math.exp(-h / HM) * seg;
			odR += dr;
			odM += dm;
			odO += ozone(h) * seg;
			if (ground(p, sun) > 0.0) {
				continue;
			}
			double len = exit(p, sun, RA);
			double ls = len / lightSteps;
			double lR = 0.0;
			double lM = 0.0;
			double lO = 0.0;
			for (int j = 0; j < lightSteps; j++) {
				double s = ls * (j + 0.5);
				double hq = height(p[0] + sun[0] * s, p[1] + sun[1] * s, p[2] + sun[2] * s);
				lR += Math.exp(-hq / HR) * ls;
				lM += Math.exp(-hq / HM) * ls;
				lO += ozone(hq) * ls;
			}
			for (int k = 0; k < 3; k++) {
				double tau = BR[k] * (odR + lR) + BM * haze * 1.1 * (odM + lM) + BO[k] * (odO + lO);
				double att = Math.exp(-tau);
				sumR[k] += att * dr;
				sumM[k] += att * dm;
			}
		}
		double mu = rd[0] * sun[0] + rd[1] * sun[1] + rd[2] * sun[2];
		double pR = 3.0 / (16.0 * Math.PI) * (1.0 + mu * mu);
		double g = 0.76;
		double pM = 3.0 / (8.0 * Math.PI) * ((1.0 - g * g) * (1.0 + mu * mu)) / ((2.0 + g * g) * Math.pow(1.0 + g * g - 2.0 * g * mu, 1.5));
		double[] out = new double[3];
		for (int k = 0; k < 3; k++) {
			out[k] = 20.0 * (sumR[k] * BR[k] * pR + sumM[k] * BM * haze * pM);
		}
		return out;
	}

	static double[] toneMap(double[] l) {
		double[] c = new double[3];
		for (int k = 0; k < 3; k++) {
			c[k] = Math.pow(1.0 - Math.exp(-l[k] * 2.5), 1.0 / 2.2);
		}
		double lum = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
		for (int k = 0; k < 3; k++) {
			c[k] = Mth.clamp(lum + (c[k] - lum) * 1.1, 0.0, 1.0);
		}
		return c;
	}
}
