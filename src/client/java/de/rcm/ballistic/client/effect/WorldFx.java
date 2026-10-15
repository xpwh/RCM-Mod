package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Screen-space light on the world, worked out from copies of the depth (and picture) taken mid-frame:
 * <ul>
 *   <li>before the translucent terrain: sunlight that casts shadows (each pixel traces towards the sun
 *       through the depth of the picture: trees, houses, entities, a missile on its pad all throw them,
 *       moving with the sun) and the soft darkening of corners and crevices (ambient occlusion)</li>
 *   <li>after it: water that mirrors what is round it - the land, trees and lights on screen, the sky
 *       elsewhere - through waves that roll with the time, more mirror at a glancing look and more window
 *       looking down into it (Fresnel), the sun glittering on it, the bottom seen through the water,
 *       refracted by the waves and fading into the water's colour with depth</li>
 * </ul>
 * Each can be switched off in the settings.
 */
public final class WorldFx {
	private static final RenderPipeline SHADE_PIPELINE = pipeline("world_fx_shade", "SHADE");
	private static final RenderPipeline WATER_PIPELINE = pipeline("world_fx_water", "WATER");
	private static final RenderPipeline UNDER_PIPELINE = pipeline("world_fx_under", "UNDER");
	private static final Identifier OPAQUE_DEPTH = BallisticMissiles.id("textures/environment/fx_opaque_depth");
	private static final Identifier OPAQUE_COLOR = BallisticMissiles.id("textures/environment/fx_opaque_color");
	/** The world's depth at the end of the main pass (also read by the blast post effect). */
	public static final Identifier FULL_DEPTH = BallisticMissiles.id("textures/effect/fx_full_depth.png");
	private static final Identifier FULL_COLOR = BallisticMissiles.id("textures/environment/fx_full_color");
	private static ScreenCopy opaqueDepth;
	private static ScreenCopy opaqueColor;
	private static ScreenCopy fullDepth;
	private static ScreenCopy fullColor;
	private static RenderType shadeType;
	private static RenderType waterType;
	private static RenderType underType;
	/** Whether the opaque copies were taken this frame (the water needs them). */
	private static boolean haveOpaque;

	private WorldFx() {
	}

	private static RenderPipeline pipeline(String name, String define) {
		RenderPipeline.Builder b = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(BallisticMissiles.id("pipeline/" + name))
			.withVertexShader(BallisticMissiles.id("core/world_fx"))
			.withFragmentShader(BallisticMissiles.id("core/world_fx"))
			.withShaderDefine(define)
			.withSampler("Sampler0")
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withCull(false)
			.withDepthWrite(false)
			.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS);
		if (define.equals("SHADE")) {
			// shade multiplies what is there; Sampler1: the heightmap the sun's shadows are traced over
			b = b.withSampler("Sampler1").withBlend(new BlendFunction(SourceFactor.DST_COLOR, DestFactor.ZERO, SourceFactor.ZERO, DestFactor.ONE));
		} else if (define.equals("UNDER")) {
			b = b.withSampler("Sampler1").withSampler("Sampler2").withColorWrite(true, false);
		} else {
			b = b.withSampler("Sampler1").withSampler("Sampler2").withSampler("Sampler3").withColorWrite(true, false);
		}
		return b.build();
	}

	public static void init() {
		RenderPipelines.register(SHADE_PIPELINE);
		RenderPipelines.register(WATER_PIPELINE);
		RenderPipelines.register(UNDER_PIPELINE);
		WorldRenderEvents.BEFORE_TRANSLUCENT.register(WorldFx::beforeTranslucent);
		WorldRenderEvents.END_MAIN.register(WorldFx::endMain);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if ((ModConfig.sunShadows || ModConfig.realWater) && overworldView(mc)) {
				SunHeightmap.update(mc);
			}
			underwaterMotes(mc);
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> SunHeightmap.clear());
	}

	private static boolean overworldView(Minecraft mc) {
		return mc.level != null && mc.level.dimension() == Level.OVERWORLD;
	}

	private static void ensure(Minecraft mc) {
		if (opaqueDepth == null) {
			opaqueDepth = new ScreenCopy("opaque depth");
			opaqueColor = new ScreenCopy("opaque picture");
			fullDepth = new ScreenCopy("depth");
			fullColor = new ScreenCopy("picture");
			mc.getTextureManager().register(OPAQUE_DEPTH, opaqueDepth);
			mc.getTextureManager().register(OPAQUE_COLOR, opaqueColor);
			mc.getTextureManager().register(FULL_DEPTH, fullDepth);
			mc.getTextureManager().register(FULL_COLOR, fullColor);
		}
		if (shadeType == null) {
			shadeType = RenderType.create("ballisticmissiles_world_fx_shade", RenderSetup.builder(SHADE_PIPELINE)
				.withTexture("Sampler0", OPAQUE_DEPTH, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.withTexture("Sampler1", SunHeightmap.ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.createRenderSetup());
			waterType = RenderType.create("ballisticmissiles_world_fx_water", RenderSetup.builder(WATER_PIPELINE)
				.withTexture("Sampler0", OPAQUE_DEPTH, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.withTexture("Sampler1", FULL_DEPTH, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.withTexture("Sampler2", OPAQUE_COLOR, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
				.withTexture("Sampler3", FULL_COLOR, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.createRenderSetup());
			underType = RenderType.create("ballisticmissiles_world_fx_under", RenderSetup.builder(UNDER_PIPELINE)
				.withTexture("Sampler0", FULL_DEPTH, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.withTexture("Sampler1", SunHeightmap.ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.withTexture("Sampler2", FULL_COLOR, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
				.createRenderSetup());
		}
	}

	/** How much the sun shines on the land now (0 at night, under heavy rain), and where it is. */
	private static float sunlight(Minecraft mc, Vector3f sun, float partial) {
		float rain = mc.level.getRainLevel(partial);
		float thunder = mc.level.getThunderLevel(partial);
		float up = Mth.clamp((sun.y - 0.02F) / 0.18F, 0.0F, 1.0F);
		return up * (1.0F - 0.75F * rain) * (1.0F - 0.6F * thunder) * (1.0F - WinterClient.amount() * 0.8F);
	}

	private static void beforeTranslucent(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		haveOpaque = false;
		boolean shade = ModConfig.sunShadows || ModConfig.ambientOcclusion;
		if (!overworldView(mc) || !(shade || ModConfig.realWater)) {
			return;
		}
		var target = mc.getMainRenderTarget();
		if (target.getDepthTexture() == null || target.getColorTexture() == null
			|| !(context.consumers() instanceof MultiBufferSource.BufferSource buffers)) {
			return;
		}
		ensure(mc);
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Camera camera = mc.gameRenderer.getMainCamera();
		opaqueDepth.copy(target.getDepthTexture());
		Vector3f sun = Sky.sun(mc, partial);
		if (shade && camera.getFluidInCamera() == FogType.NONE) {
			// underground or deep indoors the sun's shadows fade out (what is lit there, torches light)
			float open = mc.player == null ? 1.0F : mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, net.minecraft.core.BlockPos.containing(mc.player.getEyePosition())) / 15.0F;
			float shadow = ModConfig.sunShadows ? 0.5F * sunlight(mc, sun, partial) * Mth.clamp((open - 0.3F) / 0.5F, 0.0F, 1.0F) : 0.0F;
			float ao = ModConfig.ambientOcclusion ? 0.55F : 0.0F;
			if (!SunHeightmap.ready()) {
				shadow = 0.0F;
			}
			if (shadow > 0.01F || ao > 0.01F) {
				int quality = Mth.clamp(ModConfig.lightQuality, 1, 3);
				Vec3 cam = camera.position();
				// the camera within the heightmap (x, z) and its height, for the shadows traced over the map
				float hx = (float) (cam.x - SunHeightmap.originX());
				float hz = (float) (cam.z - SunHeightmap.originZ());
				int camY = Mth.clamp((int) Math.round((cam.y + 256.0) * 16.0), 0, 32767);
				screen(buffers.getBuffer(shadeType), context.matrices().last(), camera, argb(1.0F, shadow, ao, 0.0F), sun, quality, SunHeightmap.maxTop() + 64, hx, hz, camY);
				buffers.endBatch(shadeType);
			}
		}
		opaqueColor.copy(target.getColorTexture());
		haveOpaque = true;
	}

	/** Makes sure the copies exist (registered), for the post effect that reads the world's depth. */
	static void ensureTextures(Minecraft mc) {
		ensure(mc);
	}

	private static void endMain(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		// the world's depth as it ends, for the screen post effect (by then the hand has its own depth)
		var main = mc.getMainRenderTarget();
		if (main.getDepthTexture() != null) {
			ensure(mc);
			fullDepth.copy(main.getDepthTexture());
		}
		Camera camera = mc.gameRenderer.getMainCamera();
		if (ModConfig.realWater && overworldView(mc) && camera.getFluidInCamera() == FogType.WATER) {
			underwater(context, mc, camera);
			return;
		}
		if (!haveOpaque || !ModConfig.realWater || !overworldView(mc)) {
			return;
		}
		if (camera.getFluidInCamera() != FogType.NONE) {
			return;
		}
		var target = mc.getMainRenderTarget();
		if (target.getDepthTexture() == null || target.getColorTexture() == null
			|| !(context.consumers() instanceof MultiBufferSource.BufferSource buffers)) {
			return;
		}
		fullColor.copy(target.getColorTexture());
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vector3f sun = Sky.sun(mc, partial);
		// the sky overhead, for what the water mirrors where nothing on screen is: the same model the sky is drawn with
		double[] zenith = Sky.toneMap(Sky.atmosphere(new double[] {0.0, 1.0, 0.0}, new double[] {sun.x, sun.y, sun.z}, 1.0, 8, 3));
		float night = Mth.clamp(mc.level.getSkyDarken() / 11.0F, 0.0F, 1.0F);
		float dim = (1.0F - 0.9F * night) * (1.0F - 0.5F * mc.level.getRainLevel(partial)) * (1.0F - NewMoonClient.amount());
		int zr = Math.round(Mth.clamp((float) zenith[0] * dim, 0.0F, 1.0F) * 255.0F);
		int zg = Math.round(Mth.clamp((float) zenith[1] * dim, 0.0F, 1.0F) * 255.0F);
		int zb = Math.round(Mth.clamp((float) zenith[2] * dim, 0.0F, 1.0F) * 255.0F);
		float glitter = sunlight(mc, sun, partial);
		Vec3 cam = camera.position();
		float cx = (float) (cam.x - Math.floor(cam.x / 2048.0) * 2048.0);
		float cz = (float) (cam.z - Math.floor(cam.z / 2048.0) * 2048.0);
		screen(buffers.getBuffer(waterType), context.matrices().last(), camera,
			argb(1.0F, glitter, mc.level.getRainLevel(partial), mc.level.getThunderLevel(partial)), sun,
			zr | zg << 8, zb, cx, cz, 0);
		buffers.endBatch(waterType);
	}

	/**
	 * Looking from under water: caustics on the bottom, shafts of light from the surface, the distance blurred
	 * into blue (traced over the heightmap, whose column tops are the water's surface).
	 */
	private static void underwater(WorldRenderContext context, Minecraft mc, Camera camera) {
		var target = mc.getMainRenderTarget();
		if (target.getColorTexture() == null || !SunHeightmap.ready()
			|| !(context.consumers() instanceof MultiBufferSource.BufferSource buffers)) {
			return;
		}
		ensure(mc);
		fullColor.copy(target.getColorTexture());
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vector3f sun = Sky.sun(mc, partial);
		Vec3 cam = camera.position();
		float hx = (float) (cam.x - SunHeightmap.originX());
		float hz = (float) (cam.z - SunHeightmap.originZ());
		int camY = Mth.clamp((int) Math.round((cam.y + 256.0) * 16.0), 0, 32767);
		screen(buffers.getBuffer(underType), context.matrices().last(), camera, argb(1.0F, sunlight(mc, sun, partial), 0.0F, 0.0F), sun,
			Mth.clamp(ModConfig.lightQuality, 1, 3), SunHeightmap.maxTop() + 64, hx, hz, camY);
		buffers.endBatch(underType);
	}

	/** Specks drifting in the water round the eye. */
	private static void underwaterMotes(Minecraft mc) {
		if (!ModConfig.realWater || mc.level == null || mc.isPaused()
			|| mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.WATER) {
			return;
		}
		Vec3 eye = mc.gameRenderer.getMainCamera().position();
		var random = mc.level.getRandom();
		for (int i = 0; i < 4; i++) {
			double x = eye.x + (random.nextDouble() - 0.5) * 14.0;
			double y = eye.y + (random.nextDouble() - 0.5) * 8.0;
			double z = eye.z + (random.nextDouble() - 0.5) * 14.0;
			if (mc.level.getFluidState(net.minecraft.core.BlockPos.containing(x, y, z)).is(net.minecraft.tags.FluidTags.WATER)) {
				mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.UNDERWATER, x, y, z, 0.0, 0.0, 0.0);
			}
		}
	}

	private static int argb(float a, float r, float g, float b) {
		return Math.round(Mth.clamp(a, 0.0F, 1.0F) * 255.0F) << 24 | Math.round(Mth.clamp(r, 0.0F, 1.0F) * 255.0F) << 16
			| Math.round(Mth.clamp(g, 0.0F, 1.0F) * 255.0F) << 8 | Math.round(Mth.clamp(b, 0.0F, 1.0F) * 255.0F);
	}

	/**
	 * One quad over the whole view, far out (so the walking bob does not swing it). Colour: the pass's
	 * parameters; UV0: the camera's x, z (water: wrapped, for the waves; shade: within the heightmap); UV1:
	 * the camera's height (shade); UV2: two 16-bit words of data; normal: the direction to the sun.
	 */
	private static void screen(VertexConsumer consumer, PoseStack.Pose pose, Camera camera, int color, Vector3f sun, int data0, int data1, float cx, float cz,
		int overlay) {
		org.joml.Vector3fc f = camera.forwardVector();
		org.joml.Vector3fc u = camera.upVector();
		org.joml.Vector3fc left = camera.leftVector();
		float w = 6.0F;
		int light = (data0 & 0xFFFF) | (data1 & 0xFFFF) << 16;
		for (float[] c : new float[][] {{w, -w}, {-w, -w}, {-w, w}, {w, w}}) {
			float x = (f.x() + left.x() * c[0] + u.x() * c[1]) * 500.0F;
			float y = (f.y() + left.y() * c[0] + u.y() * c[1]) * 500.0F;
			float z = (f.z() + left.z() * c[0] + u.z() * c[1]) * 500.0F;
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(cx, cz).setOverlay(overlay).setLight(light).setNormal(pose, sun.x, sun.y, sun.z);
		}
	}
}
