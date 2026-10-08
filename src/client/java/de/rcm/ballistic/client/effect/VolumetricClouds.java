package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import de.rcm.ballistic.BallisticMissiles;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;

/**
 * Volumetric clouds in place of Minecraft's flat blocky ones: a layer some hundred blocks thick at the
 * dimension's cloud height, raymarched per pixel in {@code shaders/core/volumetric_clouds.fsh} (shape
 * from tiling 3D noise, light marched towards the sun). More of the sky clouds over when it rains.
 * <p>
 * Rockets disturb the layer: a missile climbing (or falling) through it punches a hole that slowly
 * fills in again, and its exhaust smoke spreads out sideways along the layer, drifting and thinning
 * over a minute or two. Rain and thunderstorms darken and thicken the clouds, and lightning lights
 * them up from inside. {@code /volcloud} switches them on or off and sets how cloudy it is. Big blasts that reach up to it blow a ring clear. All of this lives in a small
 * wrapping map (1024 x 1024 blocks, 4 blocks a texel) the shader reads.
 */
public final class VolumetricClouds {
	/** How thick the cloud layer is (blocks). */
	public static final int THICKNESS = 96;
	private static final int MAP = 256;
	private static final int TEXEL = 4;
	private static final Identifier MAP_ID = BallisticMissiles.id("textures/environment/cloud_disturbance");
	private static final Identifier NOISE_ID = BallisticMissiles.id("textures/environment/cloud_noise.png");

	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/volumetric_clouds"))
		.withVertexShader(BallisticMissiles.id("core/volumetric_clouds"))
		.withFragmentShader(BallisticMissiles.id("core/volumetric_clouds"))
		.withSampler("Sampler0")
		.withSampler("Sampler1")
		.withBlend(BlendFunction.TRANSLUCENT)
		.withCull(false)
		.withDepthWrite(false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();
	private static RenderType type;

	private static DynamicTexture map;
	/** Holes punched through the layer (0-1) and exhaust smoke spread along it (0-1), per texel. */
	private static final float[] HOLE = new float[MAP * MAP];
	private static final float[] SMOKE = new float[MAP * MAP];
	private static final float[] SCRATCH = new float[MAP * MAP];
	private static boolean disturbed;
	private static boolean dirty = true;
	private static int age;
	/** Wind drift not yet applied to the map (blocks); the map is shifted a texel at a time. */
	private static double driftX;
	private static double driftZ;
	/** Wind speed (blocks per tick): the noise moves this way, and so must the holes and smoke in it. */
	private static final double WIND_X = 0.05;
	private static final double WIND_Z = 0.025;
	/** Cloud base of the current dimension (blocks), or NaN where there are no clouds. */
	private static float base = Float.NaN;
	/** Switched on or off with /volcloud; off, Minecraft's own clouds come back. */
	private static boolean enabled = true;
	/** How much of the fair-weather sky is clouded (percent), set with /volcloud; rain and storms add to it. */
	private static int amount = 40;
	/** A lightning flash lighting the storm clouds from inside (0-1), dying away in a few frames. */
	private static float flash;
	private static float prevFlash;
	private static final java.nio.file.Path CONFIG = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
		.resolve("ballisticmissiles-clouds.properties");

	private VolumetricClouds() {
	}

	public static void init() {
		load();
		RenderPipelines.register(PIPELINE);
		ClientTickEvents.END_CLIENT_TICK.register(VolumetricClouds::tick);
		WorldRenderEvents.END_MAIN.register(VolumetricClouds::render);
	}

	/** Whether the volumetric clouds are drawn (instead of Minecraft's own). */
	public static boolean enabled() {
		return enabled;
	}

	public static void setEnabled(boolean on) {
		enabled = on;
		if (!on) {
			base = Float.NaN;
		}
		save();
	}

	public static int amount() {
		return amount;
	}

	public static void setAmount(int percent) {
		amount = Mth.clamp(percent, 0, 100);
		save();
	}

	private static void load() {
		try {
			if (java.nio.file.Files.exists(CONFIG)) {
				java.util.Properties p = new java.util.Properties();
				try (var in = java.nio.file.Files.newInputStream(CONFIG)) {
					p.load(in);
				}
				enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
				amount = Mth.clamp(Integer.parseInt(p.getProperty("amount", "40").trim()), 0, 100);
			}
		} catch (Exception e) {
			BallisticMissiles.LOGGER.warn("Could not read {}: {}", CONFIG, e.toString());
		}
	}

	private static void save() {
		try {
			java.util.Properties p = new java.util.Properties();
			p.setProperty("enabled", Boolean.toString(enabled));
			p.setProperty("amount", Integer.toString(amount));
			java.nio.file.Files.createDirectories(CONFIG.getParent());
			try (var out = java.nio.file.Files.newOutputStream(CONFIG)) {
				p.store(out, "Ballistic Missiles - volumetric clouds (/volcloud)");
			}
		} catch (Exception e) {
			BallisticMissiles.LOGGER.warn("Could not write {}: {}", CONFIG, e.toString());
		}
	}

	/** Bottom of the cloud layer here, NaN if this dimension has none. */
	public static float base() {
		return base;
	}

	/** Whether {@code y} lies in (or just at the edges of) the cloud layer. */
	public static boolean inLayer(double y) {
		return !Float.isNaN(base) && y > base - 6.0 && y < base + THICKNESS + 6.0;
	}

	private static RenderType type() {
		if (type == null) {
			type = RenderType.create("ballisticmissiles_volumetric_clouds", RenderSetup.builder(PIPELINE)
				.withTexture("Sampler0", MAP_ID, () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
				.withTexture("Sampler1", NOISE_ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
				.createRenderSetup());
		}
		return type;
	}

	// ------------------------------------------------------------------ disturbances

	/** A rocket moving through the layer from {@code from} to {@code to} this tick: a hole along its path, and its smoke. */
	public static void rocket(Vec3 from, Vec3 to, float radius, float smoke) {
		if (Float.isNaN(base)) {
			return;
		}
		double lo = base - 4.0;
		double hi = base + THICKNESS + 4.0;
		if (Math.max(from.y, to.y) < lo || Math.min(from.y, to.y) > hi) {
			return;
		}
		double length = from.distanceTo(to);
		int n = Math.max(1, (int) Math.ceil(length / TEXEL));
		for (int i = 0; i <= n; i++) {
			Vec3 p = from.lerp(to, i / (double) n);
			if (p.y < lo || p.y > hi) {
				continue;
			}
			stamp(HOLE, p.x, p.z, radius, 1.0F, false);
			if (smoke > 0.0F) {
				stamp(SMOKE, p.x, p.z, radius * 1.4F, smoke, true);
			}
		}
	}

	/** A blast big enough to reach the clouds: the shock blows a wide ring clear, and its smoke rides up into the layer. */
	public static void blast(Vec3 pos, float radius, float smoke) {
		if (Float.isNaN(base) || pos.y > base + THICKNESS + radius || pos.y < base - radius * 6.0) {
			return;
		}
		stamp(HOLE, pos.x, pos.z, radius, 1.0F, false);
		stamp(SMOKE, pos.x, pos.z, radius * 0.45F, smoke, true);
	}

	/** Smoke that has risen to the cloud base and spread into the layer there. */
	public static void smoke(double x, double z, float radius, float amount) {
		if (!Float.isNaN(base) && amount > 0.005F) {
			stamp(SMOKE, x, z, radius, amount, true);
		}
	}

	private static void stamp(float[] field, double x, double z, float radius, float value, boolean add) {
		int cx = Mth.floor(x / TEXEL);
		int cz = Mth.floor(z / TEXEL);
		int r = Math.max(1, Mth.ceil(radius / TEXEL));
		r = Math.min(r, MAP / 2 - 1);
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				float d = Mth.sqrt(dx * dx + dz * dz) * TEXEL / radius;
				if (d >= 1.0F) {
					continue;
				}
				float f = value * (1.0F - d * d);
				int i = Math.floorMod(cz + dz, MAP) * MAP + Math.floorMod(cx + dx, MAP);
				field[i] = add ? Math.min(1.0F, field[i] + f * 0.35F) : Math.max(field[i], f);
			}
		}
		disturbed = true;
		dirty = true;
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			if (disturbed) {
				java.util.Arrays.fill(HOLE, 0.0F);
				java.util.Arrays.fill(SMOKE, 0.0F);
				disturbed = false;
				dirty = true;
			}
			base = Float.NaN;
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		// lightning: the storm clouds lit up from inside - at every real bolt, and now and then within the clouds
		prevFlash = flash;
		flash *= 0.55F;
		float thunder = mc.level.getThunderLevel(1.0F);
		if (thunder > 0.2F && enabled) {
			boolean bolt = false;
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof net.minecraft.world.entity.LightningBolt && e.tickCount < 2) {
					bolt = true;
					break;
				}
			}
			if (bolt) {
				flash = 1.0F;
			} else if (ClientEffects.rand() < 0.006F * thunder) {
				flash = Math.max(flash, 0.45F + ClientEffects.rand() * 0.45F);
			} else if (flash > 0.2F && ClientEffects.rand() < 0.25F) {
				flash = Math.min(1.0F, flash + 0.35F); // it flickers
			}
		} else {
			flash = 0.0F;
		}
		if (!disturbed) {
			return;
		}
		age++;
		// holes fill in over about a minute, smoke thins over two or three and spreads out as it does
		boolean any = false;
		for (int i = 0; i < HOLE.length; i++) {
			float h = HOLE[i];
			if (h > 0.0F) {
				h = h * 0.996F - 0.0004F;
				HOLE[i] = h > 0.0F ? h : 0.0F;
				any = true;
			}
		}
		// carried along with the clouds by the wind (the noise is sampled at position + wind: it moves towards -x, -z)
		driftX += WIND_X;
		driftZ += WIND_Z;
		if (driftX >= TEXEL || driftZ >= TEXEL) {
			int sx = driftX >= TEXEL ? 1 : 0;
			int sz = driftZ >= TEXEL ? 1 : 0;
			driftX -= sx * TEXEL;
			driftZ -= sz * TEXEL;
			shift(HOLE, sx, sz);
			shift(SMOKE, sx, sz);
		}
		if (age % 3 == 0) {
			// spreading: each texel shares with its neighbours
			for (int z = 0; z < MAP; z++) {
				int up = ((z + MAP - 1) % MAP) * MAP;
				int down = ((z + 1) % MAP) * MAP;
				int row = z * MAP;
				for (int x = 0; x < MAP; x++) {
					int l = (x + MAP - 1) % MAP;
					int r = (x + 1) % MAP;
					float c = SMOKE[row + x];
					float avg = (SMOKE[row + l] + SMOKE[row + r] + SMOKE[up + x] + SMOKE[down + x]) * 0.25F;
					float s = (c * 0.55F + avg * 0.45F) * 0.995F - 0.0005F;
					SCRATCH[row + x] = s > 0.0F ? s : 0.0F;
				}
			}
			System.arraycopy(SCRATCH, 0, SMOKE, 0, SMOKE.length);
		}
		for (float s : SMOKE) {
			if (s > 0.0F) {
				any = true;
				break;
			}
		}
		disturbed = any;
		dirty = true;
	}

	/** Moves a field {@code sx} texels towards -x and {@code sz} towards -z (wrapping). */
	private static void shift(float[] field, int sx, int sz) {
		for (int z = 0; z < MAP; z++) {
			int from = Math.floorMod(z + sz, MAP) * MAP;
			int row = z * MAP;
			for (int x = 0; x < MAP; x++) {
				SCRATCH[row + x] = field[from + Math.floorMod(x + sx, MAP)];
			}
		}
		System.arraycopy(SCRATCH, 0, field, 0, field.length);
	}

	private static void upload(Minecraft mc) {
		if (map == null) {
			map = new DynamicTexture(() -> "Cloud disturbance", MAP, MAP, false);
			mc.getTextureManager().register(MAP_ID, map);
			dirty = true;
		}
		if (!dirty) {
			return;
		}
		NativeImage img = map.getPixels();
		if (img == null) {
			return;
		}
		for (int z = 0; z < MAP; z++) {
			for (int x = 0; x < MAP; x++) {
				int i = z * MAP + x;
				int h = Math.round(Mth.clamp(HOLE[i], 0.0F, 1.0F) * 255.0F);
				int s = Math.round(Mth.clamp(SMOKE[i], 0.0F, 1.0F) * 255.0F);
				img.setPixel(x, z, 0xFF000000 | h << 16 | s << 8);
			}
		}
		map.upload();
		dirty = false;
	}

	// ------------------------------------------------------------------ drawing

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !enabled || mc.options.getCloudsType() == CloudStatus.OFF) {
			base = Float.NaN;
			return;
		}
		Camera camera = mc.gameRenderer.getMainCamera();
		float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		var probe = camera.attributeProbe();
		float height = probe.getValue(EnvironmentAttributes.CLOUD_HEIGHT, partialTick);
		if (!Float.isFinite(height)) {
			base = Float.NaN;
			return;
		}
		base = Mth.floor(height);
		upload(mc);

		Vec3 cam = camera.position();
		float angle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) * Mth.DEG_TO_RAD;
		float sx = -Mth.sin(angle);
		float sy = Mth.cos(angle);
		// by night the moon lights the clouds, from the other side of the sky
		float day = Mth.clamp((sy + 0.12F) / 0.3F, 0.0F, 1.0F);
		if (sy < -0.05F) {
			sx = -sx;
			sy = -sy;
		}
		float rain = mc.level.getRainLevel(partialTick);
		float thunder = mc.level.getThunderLevel(partialTick);
		// rain clouds the sky over; a thunderstorm closes it almost completely
		float coverage = Mth.clamp(amount / 100.0F + rain * 0.35F + thunder * 0.3F + WinterClient.amount() * 0.35F, 0.0F, 0.99F);
		float lightning = Mth.lerp(partialTick, prevFlash, flash);
		// UV2: x reach, y storm (low 7 bits) and lightning (next 7)
		int storm = Math.round(thunder * 127.0F) | Math.round(lightning * 127.0F) << 7;
		long time = mc.level.getGameTime();
		// drifting with the wind; wraps after 4096 blocks, a whole number of noise periods
		double ticks = (time % 81920L) + partialTick;
		float windX = (float) (ticks * 0.05 % 4096.0);
		float windZ = (float) (ticks * 0.025 % 4096.0);

		float bottom = base;
		float top = base + THICKNESS;
		double camY = cam.y;
		float reach = Math.max(256.0F, mc.options.getEffectiveRenderDistance() * 16.0F * 4.0F);
		VertexConsumer consumer = context.consumers().getBuffer(type());
		PoseStack.Pose pose = context.matrices().last();
		// alpha: quality (clouds set to "fast": half the steps, no fine detail)
		int quality = mc.options.getCloudsType() == CloudStatus.FAST ? 0x80 : 0xFF;
		int color = quality << 24 | Math.round(coverage * 255.0F) << 16 | Math.round(day * 255.0F) << 8 | Math.round(rain * 255.0F);
		// below the layer the rays enter through its base, above it through its top; inside, both planes catch them
		if (camY < top) {
			plane(consumer, pose, (float) (bottom - camY), reach, storm, color, windX, windZ, bottom, sx, sy);
		}
		if (camY > bottom) {
			plane(consumer, pose, (float) (top - camY), reach, storm, color, windX, windZ, bottom, sx, sy);
		}
	}

	private static void plane(VertexConsumer consumer, PoseStack.Pose pose, float y, float reach, int storm, int color, float windX, float windZ, float bottom,
		float sx, float sy) {
		float[][] corners = {{-reach, -reach}, {reach, -reach}, {reach, reach}, {-reach, reach}};
		for (float[] c : corners) {
			consumer.addVertex(pose, c[0], y, c[1]).setColor(color).setUv(windX, windZ).setOverlay(OverlayTexture.pack((int) bottom, THICKNESS))
				.setLight((int) reach | storm << 16).setNormal(pose, sx, sy, 0.0F);
		}
	}
}
