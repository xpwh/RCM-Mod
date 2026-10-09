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
import de.rcm.ballistic.client.ModConfig;
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
 * wrapping map (1024 x 1024 blocks, 4 blocks a texel) the shader reads: R holes, G exhaust smoke,
 * B cloud piled up round the holes' rims, A engine glow.
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
	/** The light pass: cloud shadows on the ground (before the clouds), light shafts in the air (after them). */
	private static final RenderPipeline SHADOW_PIPELINE = lightPipeline("pipeline/cloud_shadows", false);
	private static final RenderPipeline SHAFT_PIPELINE = lightPipeline("pipeline/light_shafts", true);
	private static RenderType shadowType;
	private static RenderType shaftType;
	private static final Identifier DEPTH_ID = BallisticMissiles.id("textures/environment/world_depth");
	private static DepthCopy depth;

	private static RenderPipeline lightPipeline(String location, boolean shafts) {
		RenderPipeline.Builder b = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
			.withLocation(BallisticMissiles.id(location))
			.withVertexShader(BallisticMissiles.id("core/volumetric_clouds"))
			.withFragmentShader(BallisticMissiles.id("core/volumetric_light"))
			.withSampler("Sampler0")
			.withSampler("Sampler1")
			.withSampler("Sampler2")
			// shadows: the colour already there darkened by the alpha; shafts: light added on top
			.withBlend(shafts ? BlendFunction.ADDITIVE
				: new BlendFunction(com.mojang.blaze3d.platform.SourceFactor.ONE, com.mojang.blaze3d.platform.DestFactor.ONE_MINUS_SRC_ALPHA))
			.withDepthTestFunction(com.mojang.blaze3d.platform.DepthTestFunction.NO_DEPTH_TEST)
			.withCull(false)
			.withDepthWrite(false)
			.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS);
		if (shafts) {
			b = b.withShaderDefine("RAYS");
		}
		return b.build();
	}

	/**
	 * A copy of the world's depth, taken at the end of the main pass (before the hand is drawn, which
	 * clears it): the light pass reads how far away the world is at every pixel from it.
	 */
	private static final class DepthCopy extends net.minecraft.client.renderer.texture.AbstractTexture {
		void match(com.mojang.blaze3d.textures.GpuTexture source) {
			int w = source.getWidth(0);
			int h = source.getHeight(0);
			if (this.texture != null && this.texture.getWidth(0) == w && this.texture.getHeight(0) == h) {
				return;
			}
			if (this.textureView != null) {
				this.textureView.close();
			}
			if (this.texture != null) {
				this.texture.close();
			}
			var device = RenderSystem.getDevice();
			this.texture = device.createTexture(() -> "Ballistic Missiles world depth copy",
				com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,
				com.mojang.blaze3d.textures.TextureFormat.DEPTH32, w, h, 1, 1);
			this.textureView = device.createTextureView(this.texture);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		}

		void copy(com.mojang.blaze3d.textures.GpuTexture source) {
			this.match(source);
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, this.texture, 0, 0, 0, 0, 0, this.texture.getWidth(0),
				this.texture.getHeight(0));
		}
	}

	private static DynamicTexture map;
	/** Holes punched through the layer (0-1) and exhaust smoke spread along it (0-1), per texel. */
	private static final float[] HOLE = new float[MAP * MAP];
	private static final float[] SMOKE = new float[MAP * MAP];
	private static final float[] SCRATCH = new float[MAP * MAP];
	/** Cloud piled up round the rims of the holes (0-1), and rocket engines / fireballs lighting the cloud (0-1). */
	private static final float[] RING = new float[MAP * MAP];
	private static final float[] GLOW = new float[MAP * MAP];
	private static boolean glowing;
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
	/** A lightning flash lighting the storm clouds from inside (0-1), dying away in a few frames. */
	private static float flash;
	private static float prevFlash;

	private VolumetricClouds() {
	}

	public static void init() {
		RenderPipelines.register(PIPELINE);
		RenderPipelines.register(SHADOW_PIPELINE);
		RenderPipelines.register(SHAFT_PIPELINE);
		ClientTickEvents.END_CLIENT_TICK.register(VolumetricClouds::tick);
		WorldRenderEvents.END_MAIN.register(VolumetricClouds::render);
	}

	/** Whether the volumetric clouds are drawn (instead of Minecraft's own). */
	public static boolean enabled() {
		return ModConfig.clouds;
	}

	public static void setEnabled(boolean on) {
		ModConfig.clouds = on;
		if (!on) {
			base = Float.NaN;
		}
		ModConfig.save();
	}

	public static int amount() {
		return ModConfig.cloudAmount;
	}

	public static void setAmount(int percent) {
		ModConfig.cloudAmount = Mth.clamp(percent, 0, 100);
		ModConfig.save();
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

	private static RenderType lightType(boolean shafts) {
		if (shafts ? shaftType == null : shadowType == null) {
			RenderType t = RenderType.create(shafts ? "ballisticmissiles_light_shafts" : "ballisticmissiles_cloud_shadows", RenderSetup.builder(
					shafts ? SHAFT_PIPELINE : SHADOW_PIPELINE)
				.withTexture("Sampler0", MAP_ID, () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
				.withTexture("Sampler1", NOISE_ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
				.withTexture("Sampler2", DEPTH_ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.createRenderSetup());
			if (shafts) {
				shaftType = t;
			} else {
				shadowType = t;
			}
		}
		return shafts ? shaftType : shadowType;
	}

	// ------------------------------------------------------------------ disturbances

	/**
	 * A hole punched through the layer: it opens at the rocket's size and keeps widening for a while (the
	 * cloud around it is pushed aside and evaporates, like a hole-punch cloud), the displaced cloud piled
	 * up in a dense ring round its rim; then it fills in again.
	 */
	private static final class Puncture {
		double x;
		double z;
		final float r0;
		final float rMax;
		final float ring;
		final int life;
		final float seed;
		int age;

		Puncture(double x, double z, float r0, float rMax, float ring, int life) {
			this.x = x;
			this.z = z;
			this.r0 = r0;
			this.rMax = rMax;
			this.ring = ring;
			this.life = life;
			this.seed = ClientEffects.rand() * 100.0F;
		}

		float radius() {
			return this.r0 + (this.rMax - this.r0) * (1.0F - (float) Math.exp(-this.age / 160.0));
		}

		/** How clear the hole is: fully, until it starts to fill in over the second half of its life. */
		float strength() {
			return Mth.clamp(2.0F * (1.0F - this.age / (float) this.life), 0.0F, 1.0F);
		}

		float ringStrength() {
			return this.ring * (float) Math.exp(-this.age / 500.0) * Math.min(1.0F, this.age / 6.0F + 0.3F);
		}
	}

	private static final java.util.List<Puncture> PUNCTURES = new java.util.ArrayList<>();
	private static final int MAX_PUNCTURES = 160;

	/**
	 * A rocket moving from {@code from} to {@code to} this tick: where it is inside the layer it punches a
	 * hole along its path and leaves a column of exhaust smoke; while its engine burns ({@code glow}) it
	 * lights the cloud round it from inside, and from below as it comes up to the base.
	 */
	public static void rocket(Vec3 from, Vec3 to, float radius, float smoke, float glow) {
		if (Float.isNaN(base)) {
			return;
		}
		double lo = base - 4.0;
		double hi = base + THICKNESS + 4.0;
		if (glow > 0.0F && to.y > base - 70.0 && to.y < hi + 20.0) {
			float below = (float) Mth.clamp((to.y - (base - 70.0)) / 70.0, 0.0, 1.0);
			stamp(GLOW, to.x, to.z, 26.0F + radius * 2.0F, glow * below, false);
			glowing = true;
		}
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
			puncture(p.x, p.z, radius, radius * 3.4F, 1.0F, 2400);
			if (smoke > 0.0F) {
				stamp(SMOKE, p.x, p.z, radius * 1.6F, smoke, true);
			}
		}
	}

	/** A blast big enough to reach the clouds: the shock blows a wide ring clear, the fireball lights them, its smoke rides up into the layer. */
	public static void blast(Vec3 pos, float radius, float smoke) {
		if (Float.isNaN(base) || pos.y > base + THICKNESS + radius || pos.y < base - radius * 6.0) {
			return;
		}
		puncture(pos.x, pos.z, radius * 0.45F, radius, 1.4F, 3600);
		stamp(SMOKE, pos.x, pos.z, radius * 0.45F, smoke, true);
		stamp(GLOW, pos.x, pos.z, radius * 0.9F, 1.0F, false);
		glowing = true;
	}

	/** Smoke that has risen to the cloud base and spread into the layer there. */
	public static void smoke(double x, double z, float radius, float amount) {
		if (!Float.isNaN(base) && amount > 0.005F) {
			stamp(SMOKE, x, z, radius, amount, true);
		}
	}

	private static void puncture(double x, double z, float r0, float rMax, float ring, int life) {
		for (Puncture p : PUNCTURES) {
			double dx = p.x - x;
			double dz = p.z - z;
			if (p.age < 40 && dx * dx + dz * dz < r0 * r0 * 0.5) {
				return; // the same hole, a tick further along
			}
		}
		if (PUNCTURES.size() >= MAX_PUNCTURES) {
			PUNCTURES.remove(0);
		}
		PUNCTURES.add(new Puncture(x, z, r0, rMax, ring, life));
		disturbed = true;
		dirty = true;
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

	/** Draws the holes (and the rings of cloud round them) into their fields afresh. */
	private static void drawPunctures() {
		java.util.Arrays.fill(HOLE, 0.0F);
		java.util.Arrays.fill(RING, 0.0F);
		for (Puncture p : PUNCTURES) {
			float r = p.radius();
			float hole = p.strength();
			float ring = p.ringStrength();
			float width = Math.max(8.0F, r * 0.4F);
			float outer = r * 1.15F + width;
			int cx = Mth.floor(p.x / TEXEL);
			int cz = Mth.floor(p.z / TEXEL);
			int n = Math.min(MAP / 2 - 1, Mth.ceil(outer / TEXEL) + 1);
			for (int dz = -n; dz <= n; dz++) {
				for (int dx = -n; dx <= n; dx++) {
					double wx = (cx + dx + 0.5) * TEXEL - p.x;
					double wz = (cz + dz + 0.5) * TEXEL - p.z;
					float d = (float) Math.sqrt(wx * wx + wz * wz);
					if (d > outer) {
						continue;
					}
					// a ragged rim, not a perfect circle
					float a = (float) Mth.atan2(wz, wx);
					float edge = r * (1.0F + 0.13F * Mth.sin(a * 5.0F + p.seed) + 0.07F * Mth.sin(a * 11.0F + p.seed * 1.7F));
					int i = Math.floorMod(cz + dz, MAP) * MAP + Math.floorMod(cx + dx, MAP);
					float h = hole * (1.0F - smooth((d - edge * 0.7F) / (edge * 0.3F + 0.01F)));
					HOLE[i] = Math.max(HOLE[i], h);
					float k = (d - edge * 0.85F) / (width + edge * 0.3F);
					if (k > 0.0F && k < 1.0F) {
						RING[i] = Math.min(1.0F, RING[i] + ring * Mth.sin(k * Mth.PI));
					}
				}
			}
		}
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			if (disturbed) {
				java.util.Arrays.fill(HOLE, 0.0F);
				java.util.Arrays.fill(SMOKE, 0.0F);
				java.util.Arrays.fill(RING, 0.0F);
				java.util.Arrays.fill(GLOW, 0.0F);
				PUNCTURES.clear();
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
		if (thunder > 0.2F && ModConfig.clouds) {
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
		// the holes widen, their rings thin out, they fill in; all of it drifts with the clouds
		for (java.util.Iterator<Puncture> it = PUNCTURES.iterator(); it.hasNext(); ) {
			Puncture p = it.next();
			p.age++;
			p.x -= WIND_X;
			p.z -= WIND_Z;
			if (p.age >= p.life) {
				it.remove();
			}
		}
		drawPunctures();
		// the engine glow dies the moment the rocket has gone on
		boolean lit = false;
		if (glowing) {
			for (int i = 0; i < GLOW.length; i++) {
				float g = GLOW[i];
				if (g > 0.0F) {
					g = g * 0.72F - 0.01F;
					GLOW[i] = g > 0.0F ? g : 0.0F;
					lit |= g > 0.0F;
				}
			}
			glowing = lit;
		}
		// the smoke is carried along with the clouds by the wind (the noise is sampled at position + wind: it moves towards -x, -z)
		driftX += WIND_X;
		driftZ += WIND_Z;
		if (driftX >= TEXEL || driftZ >= TEXEL) {
			int sx = driftX >= TEXEL ? 1 : 0;
			int sz = driftZ >= TEXEL ? 1 : 0;
			driftX -= sx * TEXEL;
			driftZ -= sz * TEXEL;
			shift(SMOKE, sx, sz);
		}
		boolean any = !PUNCTURES.isEmpty() || glowing;
		if (age % 3 == 0) {
			// spreading: each texel shares with its neighbours, thinning slowly over two or three minutes
			for (int z = 0; z < MAP; z++) {
				int up = ((z + MAP - 1) % MAP) * MAP;
				int down = ((z + 1) % MAP) * MAP;
				int row = z * MAP;
				for (int x = 0; x < MAP; x++) {
					int l = (x + MAP - 1) % MAP;
					int r = (x + 1) % MAP;
					float c = SMOKE[row + x];
					float avg = (SMOKE[row + l] + SMOKE[row + r] + SMOKE[up + x] + SMOKE[down + x]) * 0.25F;
					float s = (c * 0.6F + avg * 0.4F) * 0.996F - 0.0004F;
					SCRATCH[row + x] = s > 0.0F ? s : 0.0F;
				}
			}
			System.arraycopy(SCRATCH, 0, SMOKE, 0, SMOKE.length);
		}
		if (!any) {
			for (float s : SMOKE) {
				if (s > 0.0F) {
					any = true;
					break;
				}
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
				int r = Math.round(Mth.clamp(RING[i], 0.0F, 1.0F) * 255.0F);
				int g = Math.round(Mth.clamp(GLOW[i], 0.0F, 1.0F) * 255.0F);
				img.setPixel(x, z, g << 24 | h << 16 | s << 8 | r);
			}
		}
		map.upload();
		dirty = false;
	}

	// ------------------------------------------------------------------ drawing

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !ModConfig.clouds || mc.options.getCloudsType() == CloudStatus.OFF) {
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
		boolean moonlit = sy < -0.05F;
		if (moonlit) {
			sx = -sx;
			sy = -sy;
		}
		float rain = mc.level.getRainLevel(partialTick);
		float thunder = mc.level.getThunderLevel(partialTick);
		// rain clouds the sky over; a thunderstorm closes it almost completely
		float coverage = Mth.clamp(ModConfig.cloudAmount / 100.0F + rain * 0.35F + thunder * 0.3F + WinterClient.amount() * 0.35F, 0.0F, 0.99F);
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
		PoseStack.Pose pose = context.matrices().last();
		// alpha: quality level (Minecraft's "fast" clouds: the lowest)
		ModConfig.Quality quality = mc.options.getCloudsType() == CloudStatus.FAST ? ModConfig.Quality.LOW : ModConfig.cloudQuality;
		int color = (quality.ordinal() * 64 + (moonlit ? 32 : 0)) << 24 | Math.round(coverage * 255.0F) << 16 | Math.round(day * 255.0F) << 8
			| Math.round(rain * 255.0F);
		// thickness, and which parts of the light pass are wanted
		boolean shadows = ModConfig.cloudShadows;
		boolean shafts = ModConfig.lightShafts && quality != ModConfig.Quality.LOW;
		int layer = THICKNESS | (shadows ? 256 : 0) | (shafts ? 512 : 0);
		boolean light = (shadows || shafts) && day > 0.05F && camY < bottom;
		if (light) {
			var target = mc.getMainRenderTarget();
			if (target.getDepthTexture() == null) {
				light = false;
			} else {
				if (depth == null) {
					depth = new DepthCopy();
					mc.getTextureManager().register(DEPTH_ID, depth);
				}
				depth.copy(target.getDepthTexture());
			}
		}
		if (light && shadows) {
			screen(context.consumers().getBuffer(lightType(false)), pose, camera, color, windX, windZ, bottom, layer, reach, storm, sx, sy);
		}
		VertexConsumer consumer = context.consumers().getBuffer(type());
		// below the layer the rays enter through its base, above it through its top; inside, both planes catch them
		if (camY < top) {
			plane(consumer, pose, (float) (bottom - camY), reach, storm, color, windX, windZ, bottom, sx, sy);
		}
		if (camY > bottom) {
			plane(consumer, pose, (float) (top - camY), reach, storm, color, windX, windZ, bottom, sx, sy);
		}
		if (light && shafts) {
			screen(context.consumers().getBuffer(lightType(true)), pose, camera, color, windX, windZ, bottom, layer, reach, storm, sx, sy);
		}
	}

	/** A quad right across the view, a block in front of the eye (the light pass ignores depth). */
	private static void screen(VertexConsumer consumer, PoseStack.Pose pose, Camera camera, int color, float windX, float windZ, float bottom, int layer,
		float reach, int storm, float sx, float sy) {
		org.joml.Vector3fc f = camera.forwardVector();
		org.joml.Vector3fc u = camera.upVector();
		org.joml.Vector3fc l = camera.leftVector();
		float w = 6.0F; // wide enough for any field of view
		float[][] corners = {{w, -w}, {-w, -w}, {-w, w}, {w, w}};
		for (float[] c : corners) {
			float x = f.x() + l.x() * c[0] + u.x() * c[1];
			float y = f.y() + l.y() * c[0] + u.y() * c[1];
			float z = f.z() + l.z() * c[0] + u.z() * c[1];
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(windX, windZ).setOverlay(OverlayTexture.pack((int) bottom, layer))
				.setLight((int) reach | storm << 16).setNormal(pose, sx, sy, 0.0F);
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
