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
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the Sun Destroyer's star ({@code shaders/core/sun_star}): first its surface, raytraced against a sphere and
 * writing its true depth, so the world stands in front of and behind it as it should; then, over the whole
 * screen, its light scattered by the air up to whatever each view ray meets, its corona and prominences, and the
 * shock front of the nova; and the nova itself, a raymarched fireball and ring of plasma blended over the scene.
 * Called from the late pass before the smoke and clouds, which then lie over it.
 */
public final class SunStar {
	private static final Identifier DEPTH_ID = BallisticMissiles.id("textures/environment/sun_depth");
	private static final RenderPipeline SURFACE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/sun_surface"))
		.withVertexShader(BallisticMissiles.id("core/sun_star"))
		.withFragmentShader(BallisticMissiles.id("core/sun_star"))
		.withSampler("Sampler0")
		.withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
		.withCull(false)
		.withDepthWrite(true)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();
	private static final RenderPipeline GLOW = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/sun_glow"))
		.withVertexShader(BallisticMissiles.id("core/sun_star"))
		.withFragmentShader(BallisticMissiles.id("core/sun_star"))
		.withSampler("Sampler0")
		.withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_COLOR))
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withCull(false)
		.withDepthWrite(false)
		.withColorWrite(true, false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();

	/** The nova's fireball and ring, blended over the scene so its smoke can darken what is behind it. */
	private static final RenderPipeline VOLUME = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/sun_nova"))
		.withVertexShader(BallisticMissiles.id("core/sun_star"))
		.withFragmentShader(BallisticMissiles.id("core/sun_star"))
		.withSampler("Sampler0")
		.withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA))
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withCull(false)
		.withDepthWrite(false)
		.withColorWrite(true, false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();

	private static ScreenCopy depth;
	private static RenderType volumeType;
	private static RenderType surfaceType;
	private static RenderType glowType;

	/** One star to draw this frame. */
	public record Star(Vec3 centre, float radius, float glowRadius, float shock, float temperature, float dark, float brightness, float seed) {
	}

	/** A nova: the fireball's radius and the ring's, its age (0-1), about where the star was. */
	public record Nova(Vec3 centre, float radius, float ring, float age, float brightness, float seed) {
	}

	private SunStar() {
	}

	public static void init() {
		RenderPipelines.register(SURFACE);
		RenderPipelines.register(GLOW);
		RenderPipelines.register(VOLUME);
	}

	private static void ensure(Minecraft mc) {
		if (depth == null) {
			depth = new ScreenCopy("sun depth");
			mc.getTextureManager().register(DEPTH_ID, depth);
		}
		if (surfaceType == null) {
			surfaceType = RenderType.create("ballisticmissiles_sun_surface", RenderSetup.builder(SURFACE)
				.withTexture("Sampler0", DEPTH_ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.createRenderSetup());
			glowType = RenderType.create("ballisticmissiles_sun_glow", RenderSetup.builder(GLOW)
				.withTexture("Sampler0", DEPTH_ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.createRenderSetup());
			volumeType = RenderType.create("ballisticmissiles_sun_nova", RenderSetup.builder(VOLUME)
				.withTexture("Sampler0", DEPTH_ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.createRenderSetup());
		}
	}

	public static void draw(WorldRenderContext context, java.util.List<Star> stars, java.util.List<Nova> novas) {
		if (stars.isEmpty() && novas.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		var target = mc.getMainRenderTarget();
		if (target.getDepthTexture() == null || !(context.consumers() instanceof MultiBufferSource.BufferSource buffers)) {
			return;
		}
		ensure(mc);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack.Pose pose = context.matrices().last();
		float seconds = (float) ((mc.level == null ? 0L : mc.level.getGameTime() % 24000L) / 20.0
			+ mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) / 20.0);
		// the surfaces, with their depth
		depth.copy(target.getDepthTexture());
		boolean any = false;
		for (Star s : stars) {
			if (s.radius > 0.05F) {
				quad(buffers.getBuffer(surfaceType), pose, s, cam, s.radius * 1.02F, seconds, 0, s.radius);
				any = true;
			}
		}
		if (any) {
			buffers.endBatch(surfaceType);
		}
		// their light in the air, up to whatever stands in the way (now including the stars themselves)
		depth.copy(target.getDepthTexture());
		for (Star s : stars) {
			quad(buffers.getBuffer(glowType), pose, s, cam, 0.0F, seconds, s.radius > 0.05F ? 1 : 2, s.glowRadius);
		}
		buffers.endBatch(glowType);
		// the novas, over all of it
		for (Nova n : novas) {
			Star s = new Star(n.centre, 0.0F, 0.0F, n.ring, n.age, 0.0F, n.brightness, n.seed);
			quad(buffers.getBuffer(volumeType), pose, s, cam, 0.0F, seconds, 3, n.radius);
		}
		if (!novas.isEmpty()) {
			buffers.endBatch(volumeType);
		}
	}

	private static void quad(VertexConsumer consumer, PoseStack.Pose pose, Star s, Vec3 cam, float reach, float seconds, int mode, float radius) {
		float x = (float) (s.centre.x - cam.x);
		float y = (float) (s.centre.y - cam.y);
		float z = (float) (s.centre.z - cam.z);
		int color = byteOf(s.seed) << 24 | byteOf(s.temperature) << 16 | byteOf(s.dark) << 8 | byteOf(s.brightness * 0.5F);
		int overlay = Mth.clamp(Math.round(radius * 64.0F), 0, 32000) | Mth.clamp(Math.round(s.shock * 16.0F), 0, 32000) << 16;
		for (int corner : new int[] {0, 1, 3, 2}) {
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(reach, seconds).setOverlay(overlay).setLight(corner | mode << 16).setNormal(pose, 0.0F, 1.0F, 0.0F);
		}
	}

	private static int byteOf(float v) {
		return Mth.clamp(Math.round(v * 255.0F), 0, 255);
	}
}
