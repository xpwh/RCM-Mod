package de.rcm.ballistic.client.effect;

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
import net.minecraft.world.level.Level;

/**
 * The End's sky in place of Minecraft's flat texture (shader: {@code shaders/core/end_sky.fsh}): the black of
 * space thick with stars, veils of nebula and dust, far galaxies and slow ribbons of violet light. Drawn over the
 * whole view at the far plane, so it only shows where nothing else is.
 */
public final class EndSky {
	private static final float FAR = 500.0F;
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/end_sky"))
		.withVertexShader(BallisticMissiles.id("core/end_sky"))
		.withFragmentShader(BallisticMissiles.id("core/end_sky"))
		.withCull(false)
		.withDepthWrite(false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();
	private static RenderType type;

	private EndSky() {
	}

	public static void init() {
		RenderPipelines.register(PIPELINE);
		WorldRenderEvents.BEFORE_ENTITIES.register(EndSky::render);
	}

	private static RenderType type() {
		if (type == null) {
			type = RenderType.create("ballisticmissiles_end_sky", RenderSetup.builder(PIPELINE).createRenderSetup());
		}
		return type;
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (!ModConfig.realSky || !ModConfig.endSky || mc.level == null || mc.level.dimension() != Level.END) {
			return;
		}
		Camera camera = mc.gameRenderer.getMainCamera();
		VertexConsumer consumer = context.consumers().getBuffer(type());
		PoseStack.Pose pose = context.matrices().last();
		org.joml.Vector3fc f = camera.forwardVector();
		org.joml.Vector3fc u = camera.upVector();
		org.joml.Vector3fc l = camera.leftVector();
		// alpha: the nature quality (0 low, 1/3 medium, 2/3 high)
		int color = Math.min(2, ModConfig.natureQuality.ordinal()) * 85 << 24 | 0xFFFFFF;
		float w = 6.0F;
		float[][] corners = {{w, -w}, {-w, -w}, {-w, w}, {w, w}};
		for (float[] c : corners) {
			float x = (f.x() + l.x() * c[0] + u.x() * c[1]) * FAR;
			float y = (f.y() + l.y() * c[0] + u.y() * c[1]) * FAR;
			float z = (f.z() + l.z() * c[0] + u.z() * c[1]) * FAR;
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(0.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0).setNormal(pose, 0.0F, 1.0F, 0.0F);
		}
	}
}
