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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Dynamic light: a rifle's muzzle flash, an RPG's launch and motor, a missile's exhaust, a fireball, the
 * warning lamps of a silo counting down - each lights up the world round it for as long as it burns.
 * Minecraft has no moving lights, so this is a pass over the finished picture: from a copy of the depth
 * buffer every pixel knows where in the world it is, and each light brightens the surfaces round it - by
 * distance, by how squarely they face it, and in shadow behind whatever stands in the way (soft-edged
 * from a big fireball, crisp from a small flash). Faint by day, striking at night.
 * <p>
 * Fire flickers; a warhead's flash cools from white through orange to a dull red glow as it dies. In
 * haze, rain or smoke each light also glows in the air round it (the halo pass), and the smoke itself
 * is lit by them ({@link #lightAt}). How many lights, and how soft their shadows, follows the setting.
 */
public final class DynamicLights {
	private static final RenderPipeline PIPELINE = pipeline("dynamic_light", false);
	/** The glow in the air: added on top (it lights the air in front of whatever is there, even the sky). */
	private static final RenderPipeline HALO_PIPELINE = pipeline("dynamic_light_halo", true);
	private static final Identifier DEPTH_ID = BallisticMissiles.id("textures/environment/light_depth");
	private static RenderType type;
	private static RenderType haloType;
	private static DepthCopy depth;

	private static RenderPipeline pipeline(String name, boolean halo) {
		RenderPipeline.Builder b = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(BallisticMissiles.id("pipeline/" + name))
			.withVertexShader(BallisticMissiles.id("core/dynamic_light"))
			.withFragmentShader(BallisticMissiles.id("core/dynamic_light"))
			.withSampler("Sampler0")
			// the light brightens what is already there: dst * (1 + light); the halo adds its glow
			.withBlend(halo ? new BlendFunction(SourceFactor.ONE, DestFactor.ONE) : new BlendFunction(SourceFactor.DST_COLOR, DestFactor.ONE))
			.withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
			.withCull(false)
			.withDepthWrite(false)
			.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS);
		if (halo) {
			b = b.withShaderDefine("HALO");
		}
		return b.build();
	}

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
		/** Fire: its brightness wavers. */
		boolean flicker;
		/** A warhead's flash: it cools from white to red as it dies. */
		boolean cooling;
		float seed;
		float now;
		int shown;
	}

	private static final List<Light> LIGHTS = new ArrayList<>();
	/** Lamps updated in place every frame they are seen (warning beacons), by key. */
	private static final Map<Long, Light> LAMPS = new HashMap<>();
	/** The lights drawn this frame, for the smoke to be lit by. */
	private static final List<Light> DRAWN = new ArrayList<>();
	private static float haloDensity;

	private DynamicLights() {
	}

	public static void init() {
		RenderPipelines.register(PIPELINE);
		RenderPipelines.register(HALO_PIPELINE);
		de.rcm.ballistic.ClientHooks.lightFlash = DynamicLights::flash;
		WorldRenderEvents.END_MAIN.register(DynamicLights::render);
	}

	/** A flash: full {@code strength} at once, dying away over about {@code decayMs}. */
	public static void flash(Vec3 at, int rgb, float strength, float radius, float decayMs) {
		add(at, rgb, strength, radius, decayMs, false);
	}

	/** A flame that burns (and flickers) for now - call again every tick while it does. */
	public static void steady(Vec3 at, int rgb, float strength, float radius) {
		Light l = add(at, rgb, strength, radius, 75.0F, true);
		if (l != null) {
			l.flicker = true;
		}
	}

	/**
	 * A lamp that shines while this is called (every frame or tick it is lit; it goes out ~0.1 s after the
	 * last call). The same {@code key} moves and updates the one lamp instead of adding another.
	 */
	public static void lamp(long key, Vec3 at, int rgb, float strength, float radius) {
		if (!ModConfig.dynamicLights) {
			return;
		}
		Light l = LAMPS.computeIfAbsent(key, k -> new Light());
		l.x = at.x;
		l.y = at.y;
		l.z = at.z;
		l.rgb = rgb;
		l.strength = strength;
		l.radius = radius;
		l.born = System.nanoTime();
		l.decayMs = 110.0F;
		l.steady = true;
	}

	/**
	 * A lamp on a block's structure: {@code (x, y, z)} relative to the block's bottom centre; {@code index}
	 * tells its lamps apart.
	 */
	public static void blockLamp(BlockPos pos, int index, float x, float y, float z, int rgb, float strength, float radius) {
		lamp(pos.asLong() * 31L + index, new Vec3(pos.getX() + 0.5 + x, pos.getY() + y, pos.getZ() + 0.5 + z), rgb, strength, radius);
	}

	private static Light add(Vec3 at, int rgb, float strength, float radius, float decayMs, boolean steady) {
		if (!ModConfig.dynamicLights || LIGHTS.size() > 512) {
			return null;
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
		l.seed = (float) (Math.random() * 100.0);
		LIGHTS.add(l);
		return l;
	}

	/** The light of a warhead going off: a big hot flash, cooling from white to red over a second or more. */
	public static void explosion(Vec3 at, double size, int rgb) {
		float r = (float) Mth.clamp(14.0 + size * 30.0, 10.0, 160.0);
		Light l = add(at.add(0.0, 1.5, 0.0), rgb, 1.0F, r, (float) (250.0 + size * 900.0), false);
		if (l != null) {
			l.cooling = true;
		}
	}

	/**
	 * The light falling on a point from this frame's lights (no shadows), added to {@code out} as rgb 0-1+:
	 * what lights the smoke drifting round a fire or a muzzle flash.
	 */
	public static void lightAt(double x, double y, double z, float[] out) {
		for (Light l : DRAWN) {
			double dx = l.x - x;
			double dy = l.y - y;
			double dz = l.z - z;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 >= l.radius * l.radius) {
				continue;
			}
			float r = (float) (Math.sqrt(d2) / l.radius);
			float att = (1.0F - r * r);
			att = att * att / (1.0F + (float) d2 * 0.08F) * l.now;
			int c = l.shown;
			out[0] += (c >> 16 & 255) / 255.0F * att;
			out[1] += (c >> 8 & 255) / 255.0F * att;
			out[2] += (c & 255) / 255.0F * att;
		}
	}

	private static int blend(int a, int b, float t) {
		t = Mth.clamp(t, 0.0F, 1.0F);
		int r = Math.round(Mth.lerp(t, a >> 16 & 255, b >> 16 & 255));
		int g = Math.round(Mth.lerp(t, a >> 8 & 255, b >> 8 & 255));
		int bl = Math.round(Mth.lerp(t, a & 255, b & 255));
		return r << 16 | g << 8 | bl;
	}

	private static float smooth(float a, float b, float x) {
		float t = Mth.clamp((x - a) / (b - a), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		DRAWN.clear();
		if (mc.level == null || (LIGHTS.isEmpty() && LAMPS.isEmpty())) {
			return;
		}
		int quality = ModConfig.lightQuality;
		long now = System.nanoTime();
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cam = camera.position();
		// by day the sun drowns a muzzle flash out; at night it lights the whole street
		float dark = Mth.clamp(mc.level.getSkyDarken() / 11.0F, 0.0F, 1.0F);
		List<Light> drawn = new ArrayList<>();
		for (Iterator<Light> it = java.util.stream.Stream.concat(LIGHTS.stream(), LAMPS.values().stream()).iterator(); it.hasNext(); ) {
			Light l = it.next();
			float age = (now - l.born) / 1.0E6F;
			float s;
			if (l.steady) {
				s = age < l.decayMs ? 1.0F : 0.0F;
			} else {
				s = (float) Math.exp(-age / Math.max(1.0F, l.decayMs) * 2.3F);
			}
			if (s < 0.02F) {
				l.now = 0.0F;
				continue;
			}
			if (l.flicker) {
				// fire wavers: two slow swells and a quick shiver
				float t = (now / 1.0E6F) * 0.001F;
				s *= 0.78F + 0.12F * Mth.sin(t * 11.0F + l.seed) + 0.07F * Mth.sin(t * 27.0F + l.seed * 2.3F) + 0.03F * Mth.sin(t * 61.0F + l.seed * 4.1F);
			}
			l.shown = l.rgb;
			if (l.cooling) {
				// white-hot first, then orange, then the dull red of a dying fire
				float cool = 1.0F - s;
				l.shown = blend(blend(l.rgb, 0xFFA048, smooth(0.15F, 0.55F, cool)), 0xC0301A, smooth(0.55F, 0.95F, cool));
			}
			double dx = l.x - cam.x;
			double dy = l.y - cam.y;
			double dz = l.z - cam.z;
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (d > l.radius + mc.options.getEffectiveRenderDistance() * 16.0) {
				l.now = 0.0F;
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
		LIGHTS.removeIf(l -> l.now <= 0.0F && (l.steady ? (now - l.born) / 1.0E6F >= l.decayMs : true));
		LAMPS.values().removeIf(l -> (now - l.born) / 1.0E6F >= l.decayMs);
		if (drawn.isEmpty() || quality <= 0) {
			return;
		}
		// the ones that matter most on screen: strong, big and near
		drawn.sort(Comparator.comparingDouble((Light l) -> -l.now * l.radius / (1.0 + cam.distanceTo(new Vec3(l.x, l.y, l.z)))));
		int max = quality == 1 ? 6 : quality == 2 ? 14 : 28;
		if (drawn.size() > max) {
			drawn.subList(max, drawn.size()).clear();
		}
		DRAWN.addAll(drawn);
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
			haloType = RenderType.create("ballisticmissiles_dynamic_light_halo", RenderSetup.builder(HALO_PIPELINE)
				.withTexture("Sampler0", DEPTH_ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.createRenderSetup());
		}
		// shadows: rays per light (soft ones only from big lights) and steps per ray
		int softRays = quality == 1 ? 1 : quality == 2 ? 3 : 6;
		int steps = quality == 1 ? 6 : quality == 2 ? 8 : 12;
		// the glow in the air: thin clear night air, thicker in rain, mist and smoke
		float rain = mc.level.getRainLevel(1.0F);
		haloDensity = Mth.clamp(0.06F + rain * 0.35F + mc.level.getThunderLevel(1.0F) * 0.15F + SmokeField.fog() * 1.2F, 0.0F, 1.0F) * dark;
		PoseStack.Pose pose = context.matrices().last();
		VertexConsumer consumer = context.consumers().getBuffer(type);
		for (Light l : drawn) {
			int rays = l.radius > 18.0F ? softRays : 1;
			quad(consumer, pose, camera, cam, l, 0, rays | steps << 4);
		}
		if (quality >= 2 && haloDensity > 0.01F) {
			VertexConsumer halo = context.consumers().getBuffer(haloType);
			for (Light l : drawn) {
				quad(halo, pose, camera, cam, l, Math.round(haloDensity * 1000.0F), 0);
			}
		}
		if (context.consumers() instanceof net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers) {
			buffers.endBatch(type);
			buffers.endBatch(haloType);
		}
	}

	/** One light's quad right across the view, far out (so the walking bob cannot swing the lit view). */
	private static void quad(VertexConsumer consumer, PoseStack.Pose pose, Camera camera, Vec3 cam, Light l, int uv1x, int uv1y) {
		org.joml.Vector3fc f = camera.forwardVector();
		org.joml.Vector3fc u = camera.upVector();
		org.joml.Vector3fc left = camera.leftVector();
		float w = 6.0F;
		float rx = (float) (l.x - cam.x);
		float ry = (float) Mth.clamp(l.y - cam.y, -4000.0, 4000.0);
		float rz = (float) (l.z - cam.z);
		int color = Math.round(Mth.clamp(l.now, 0.0F, 1.0F) * 255.0F) << 24 | (l.shown & 0xFFFFFF);
		int light = (Math.round(ry * 8.0F) & 0xFFFF) | Math.round(Math.min(l.radius, 2000.0F) * 16.0F) << 16;
		for (float[] c : new float[][] {{w, -w}, {-w, -w}, {-w, w}, {w, w}}) {
			float x = (f.x() + left.x() * c[0] + u.x() * c[1]) * 500.0F;
			float y = (f.y() + left.y() * c[0] + u.y() * c[1]) * 500.0F;
			float z = (f.z() + left.z() * c[0] + u.z() * c[1]) * 500.0F;
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(rx, rz).setOverlay(uv1x | uv1y << 16).setLight(light)
				.setNormal(pose, 0.0F, -1.0F, 0.0F);
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
