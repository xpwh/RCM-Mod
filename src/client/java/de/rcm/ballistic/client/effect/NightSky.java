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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector3f;

/**
 * The night sky added over Minecraft's ({@code shaders/core/night_sky.fsh}): the Milky Way, finer stars,
 * shooting stars, and on aurora nights ({@link AuroraClient}) the northern lights. Light is only ever added,
 * so the vanilla stars and moon stay; it comes out as the sun goes down, and clouds of rain, the new moon's
 * overcast or being under water hide it.
 */
public final class NightSky {
	private static final float FAR = 500.0F;
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/night_sky"))
		.withVertexShader(BallisticMissiles.id("core/night_sky"))
		.withFragmentShader(BallisticMissiles.id("core/night_sky"))
		.withBlend(BlendFunction.ADDITIVE)
		.withCull(false)
		.withDepthWrite(false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();
	private static RenderType type;

	private NightSky() {
	}

	public static void init() {
		RenderPipelines.register(PIPELINE);
		WorldRenderEvents.BEFORE_ENTITIES.register(NightSky::render);
	}

	private static RenderType type() {
		if (type == null) {
			type = RenderType.create("ballisticmissiles_night_sky", RenderSetup.builder(PIPELINE).createRenderSetup());
		}
		return type;
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.level.dimension() != Level.OVERWORLD || mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
			return;
		}
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vector3f sun = Sky.sun(mc, partialTick);
		float dark = Mth.clamp((0.05F - sun.y) / 0.3F, 0.0F, 1.0F);
		dark = dark * dark * (3.0F - 2.0F * dark);
		float clear = (1.0F - mc.level.getRainLevel(partialTick)) * (1.0F - NewMoonClient.moonGone()) * (1.0F - WinterClient.amount());
		float night = ModConfig.nightSky ? dark * clear : 0.0F;
		float aurora = AuroraClient.amount() * dark * clear;
		if (night <= 0.002F && aurora <= 0.002F) {
			return;
		}
		int quality = ModConfig.cloudQuality.ordinal();
		int color = 0xFF000000 | Math.round(night * 255.0F) << 16 | Math.round(aurora * 255.0F) << 8 | Math.round(quality * 85.0F);
		float seconds = (float) ((mc.level.getGameTime() % 72000L + partialTick) / 20.0);
		Camera camera = mc.gameRenderer.getMainCamera();
		VertexConsumer consumer = context.consumers().getBuffer(type());
		PoseStack.Pose pose = context.matrices().last();
		org.joml.Vector3fc f = camera.forwardVector();
		org.joml.Vector3fc u = camera.upVector();
		org.joml.Vector3fc l = camera.leftVector();
		float w = 6.0F;
		float[][] corners = {{w, -w}, {-w, -w}, {-w, w}, {w, w}};
		for (float[] c : corners) {
			float x = (f.x() + l.x() * c[0] + u.x() * c[1]) * FAR;
			float y = (f.y() + l.y() * c[0] + u.y() * c[1]) * FAR;
			float z = (f.z() + l.z() * c[0] + u.z() * c[1]) * FAR;
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(seconds, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0)
				.setNormal(pose, sun.x, sun.y, sun.z);
		}
	}
}
