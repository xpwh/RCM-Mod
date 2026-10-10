package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Dynamic light: a rifle's muzzle flash, an RPG's launch and motor, a missile's exhaust, a fireball -
 * each lights up the world round it for as long as it burns. Minecraft has no moving lights, so this is
 * a pass over the finished picture: from a copy of the depth buffer every pixel knows where in the world
 * it is, and each light brightens the surfaces round it - by distance, by how squarely they face it,
 * and not through walls. Faint by day, striking at night.
 */
public final class DynamicLights {
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/dynamic_light"))
		.withVertexShader(BallisticMissiles.id("core/dynamic_light"))
		.withFragmentShader(BallisticMissiles.id("core/dynamic_light"))
		.withSampler("Sampler0")
		// brightens what is already there: dst * (1 + light)
		.withBlend(new BlendFunction(SourceFactor.DST_COLOR, DestFactor.ONE))
		.withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
		.withCull(false)
		.withDepthWrite(false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();
	private static final Identifier DEPTH_ID = BallisticMissiles.id("textures/environment/light_depth");
	/** At most this many lights are drawn in a frame (the strongest, nearest ones). */
	private static final int MAX_DRAWN = 8;
	private static RenderType type;
	private static DepthCopy depth;

	private static final class Light {
		double x;
		double y;
		double z;
		int rgb;
		float strength;
		float radius;
		long born;
		/** How long it takes to die away, ms (a flash) - or how long it is held at full strength, for steady ones. */
		float decayMs;
		boolean steady;
		float now;
	}

	private static final List<Light> LIGHTS = new ArrayList<>();

	private DynamicLights() {
	}

	public static void init() {
		RenderPipelines.register(PIPELINE);
		de.rcm.ballistic.ClientHooks.lightFlash = DynamicLights::flash;
		WorldRenderEvents.END_MAIN.register(DynamicLights::render);
	}

	/** A flash: full {@code strength} at once, dying away over about {@code decayMs}. */
	public static void flash(Vec3 at, int rgb, float strength, float radius, float decayMs) {
		add(at, rgb, strength, radius, decayMs, false);
	}

	/** A light that burns steadily for now (call again every tick while it does). */
	public static void steady(Vec3 at, int rgb, float strength, float radius) {
		add(at, rgb, strength, radius, 75.0F, true);
	}

	private static void add(Vec3 at, int rgb, float strength, float radius, float decayMs, boolean steady) {
		if (!ModConfig.dynamicLights || LIGHTS.size() > 256) {
			return;
		}
		Light l = new Light();
		l.x = at.x;
		l.y = at.y;
		l.z = at.z;
		l.rgb = rgb;
		l.strength = strength;
		l.radius = radius;
		l.born = System.nanoTime();
		l.decayMs = decayMs;
		l.steady = steady;
		LIGHTS.add(l);
	}

	/** The light of a warhead going off: a big hot flash fading over a second or more. */
	public static void explosion(Vec3 at, double size, int rgb) {
		float r = (float) Mth.clamp(14.0 + size * 30.0, 10.0, 160.0);
		flash(at.add(0.0, 1.5, 0.0), rgb, 1.0F, r, (float) (250.0 + size * 900.0));
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (LIGHTS.isEmpty() || mc.level == null) {
			return;
		}
		long now = System.nanoTime();
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cam = camera.position();
		// by day the sun drowns a muzzle flash out; at night it lights the whole street
		float dark = Mth.clamp(mc.level.getSkyDarken() / 11.0F, 0.0F, 1.0F);
		List<Light> drawn = new ArrayList<>();
		for (Iterator<Light> it = LIGHTS.iterator(); it.hasNext(); ) {
			Light l = it.next();
			float age = (now - l.born) / 1.0E6F;
			float s;
			if (l.steady) {
				s = age < l.decayMs ? 1.0F : 0.0F;
			} else {
				s = (float) Math.exp(-age / Math.max(1.0F, l.decayMs) * 2.3F);
			}
			if (s < 0.02F) {
				it.remove();
				continue;
			}
			double dx = l.x - cam.x;
			double dy = l.y - cam.y;
			double dz = l.z - cam.z;
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d > l.radius + mc.options.getEffectiveRenderDistance() * 16.0) {
				continue;
			}
			// how lit the place is already: block light or sky light (by day)
			int packed = LevelRenderer.getLightColor(mc.level, BlockPos.containing(l.x, l.y, l.z));
			float block = (packed & 0xFFFF) / 16 / 15.0F;
			float sky = (packed >>> 16) / 16 / 15.0F * (1.0F - dark);
			float ambient = Math.max(block, sky);
			l.now = l.strength * s * (0.12F + 0.88F * (1.0F - ambient));
			if (l.now > 0.01F) {
				drawn.add(l);
			}
		}
		if (drawn.isEmpty()) {
			return;
		}
		// the ones that matter most on screen: strong, big and near
		drawn.sort(Comparator.comparingDouble((Light l) -> -l.now * l.radius / (1.0 + cam.distanceTo(new Vec3(l.x, l.y, l.z)))));
		var target = mc.getMainRenderTarget();
		if (target.getDepthTexture() == null) {
			return;
		}
		if (depth == null) {
			depth = new DepthCopy();
			mc.getTextureManager().register(DEPTH_ID, depth);
		}
		depth.copy(target.getDepthTexture());
		if (type == null) {
			type = RenderType.create("ballisticmissiles_dynamic_light", RenderSetup.builder(PIPELINE)
				.withTexture("Sampler0", DEPTH_ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.createRenderSetup());
		}
		VertexConsumer consumer = context.consumers().getBuffer(type);
		PoseStack.Pose pose = context.matrices().last();
		org.joml.Vector3fc f = camera.forwardVector();
		org.joml.Vector3fc u = camera.upVector();
		org.joml.Vector3fc left = camera.leftVector();
		float w = 6.0F;
		float[][] corners = {{w, -w}, {-w, -w}, {-w, w}, {w, w}};
		for (int i = 0; i < Math.min(MAX_DRAWN, drawn.size()); i++) {
			Light l = drawn.get(i);
			float rx = (float) (l.x - cam.x);
			float ry = (float) Mth.clamp(l.y - cam.y, -4000.0, 4000.0);
			float rz = (float) (l.z - cam.z);
			int color = Math.round(Mth.clamp(l.now, 0.0F, 1.0F) * 255.0F) << 24 | (l.rgb & 0xFFFFFF);
			int light = (Math.round(ry * 8.0F) & 0xFFFF) | Math.round(Math.min(l.radius, 2000.0F) * 16.0F) << 16;
			for (float[] c : corners) {
				float x = f.x() + left.x() * c[0] + u.x() * c[1];
				float y = f.y() + left.y() * c[0] + u.y() * c[1];
				float z = f.z() + left.z() * c[0] + u.z() * c[1];
				consumer.addVertex(pose, x, y, z).setColor(color).setUv(rx, rz).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
					.setNormal(pose, 0.0F, 1.0F, 0.0F);
			}
		}
	}

	/** A copy of the world's depth at the end of the main pass. */
	private static final class DepthCopy extends net.minecraft.client.renderer.texture.AbstractTexture {
		void copy(com.mojang.blaze3d.textures.GpuTexture source) {
			int w = source.getWidth(0);
			int h = source.getHeight(0);
			if (this.texture == null || this.texture.getWidth(0) != w || this.texture.getHeight(0) != h) {
				if (this.textureView != null) {
					this.textureView.close();
				}
				if (this.texture != null) {
					this.texture.close();
				}
				var device = RenderSystem.getDevice();
				this.texture = device.createTexture(() -> "Ballistic Missiles light depth copy",
					com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,
					com.mojang.blaze3d.textures.TextureFormat.DEPTH32, w, h, 1, 1);
				this.textureView = device.createTextureView(this.texture);
				this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			}
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, this.texture, 0, 0, 0, 0, 0, w, h);
		}
	}
}
