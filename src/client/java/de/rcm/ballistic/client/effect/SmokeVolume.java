package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.NativeImage;
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
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Volumetric smoke: the puffs of the {@link SmokeField} near the camera are not drawn as sprites but raymarched
 * as one body of smoke ({@code shaders/core/smoke_volume.fsh}). Each frame the nearest of them (how many with
 * the quality setting) are packed into a small data texture - where, how big, how thick, their colour, how much
 * sunlight reaches each through the others (worked out here), the light they carry - and one pass over the
 * screen traces every pixel's view ray through all of them against the depth of the scene. Smoke further off
 * stays as sprites.
 */
public final class SmokeVolume {
	/** Puffs further than this from the camera stay sprites. */
	public static final double NEAR = 110.0;
	private static final int COLUMNS = 256;
	private static final int ROWS = 5;
	private static final Identifier DATA_ID = BallisticMissiles.id("textures/environment/smoke_puffs");
	private static final Identifier DEPTH_ID = BallisticMissiles.id("textures/environment/smoke_depth");
	private static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
		.withLocation(BallisticMissiles.id("pipeline/smoke_volume"))
		.withVertexShader(BallisticMissiles.id("core/smoke_volume"))
		.withFragmentShader(BallisticMissiles.id("core/smoke_volume"))
		.withSampler("Sampler0")
		.withSampler("Sampler1")
		// premultiplied: what the smoke shows over what it hides
		.withBlend(new BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA))
		.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
		.withCull(false)
		.withDepthWrite(false)
		.withColorWrite(true, false)
		.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
		.build();

	private static DynamicTexture data;
	private static ScreenCopy depth;
	private static RenderType type;
	// the puffs gathered this frame (relative to the camera), nearest first
	private static final float[] PX = new float[COLUMNS];
	private static final float[] PY = new float[COLUMNS];
	private static final float[] PZ = new float[COLUMNS];
	private static final float[] PR = new float[COLUMNS];
	private static final float[] PA = new float[COLUMNS];
	private static final int[] PC = new int[COLUMNS];
	private static final float[] PSEED = new float[COLUMNS];
	private static final float[] PGLOW = new float[COLUMNS];
	private static final float[] PAGE = new float[COLUMNS];
	private static final int[] PEMIT = new int[COLUMNS];
	private static int count;

	private SmokeVolume() {
	}

	public static void init() {
		RenderPipelines.register(PIPELINE);
	}

	public static boolean enabled() {
		return ModConfig.volumetricSmoke;
	}

	/** How many puffs are raymarched (the rest are sprites), by the quality setting. */
	public static int budget() {
		return switch (ModConfig.cloudQuality) {
			case LOW -> 48;
			case MEDIUM -> 96;
			case HIGH -> 160;
			default -> 255;
		};
	}

	private static int samples() {
		return switch (ModConfig.cloudQuality) {
			case LOW -> 2;
			case MEDIUM -> 3;
			case HIGH -> 4;
			default -> 6;
		};
	}

	public static void begin() {
		count = 0;
	}

	public static boolean full() {
		return count >= Math.min(budget(), COLUMNS - 1);
	}

	/**
	 * One puff, nearest first: centre relative to the camera, radius, thickness (0-1), its colour (unlit), the
	 * light it carries from round it (rgb), how hot it still is (0-1), its seed and age (0-1).
	 */
	public static void add(float x, float y, float z, float radius, float alpha, int color, int emit, float glow, float seed, float age) {
		if (full()) {
			return;
		}
		int i = count++;
		PX[i] = x;
		PY[i] = y;
		PZ[i] = z;
		PR[i] = radius;
		PA[i] = alpha;
		PC[i] = color;
		PEMIT[i] = emit;
		PGLOW[i] = glow;
		PSEED[i] = seed;
		PAGE[i] = age;
	}

	private static void ensure(Minecraft mc) {
		if (data == null) {
			data = new DynamicTexture(() -> "Ballistic Missiles smoke puffs", COLUMNS, ROWS, false);
			mc.getTextureManager().register(DATA_ID, data);
			depth = new ScreenCopy("smoke depth");
			mc.getTextureManager().register(DEPTH_ID, depth);
		}
		if (type == null) {
			type = RenderType.create("ballisticmissiles_smoke_volume", RenderSetup.builder(PIPELINE)
				.withTexture("Sampler0", DEPTH_ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.withTexture("Sampler1", DATA_ID, () -> RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST))
				.createRenderSetup());
		}
	}

	/**
	 * Draws what was gathered. {@code sun}: direction of the sun (or the moon); {@code sunStrength}: how much it
	 * lights (0 at night under clouds); {@code sky}: the light from the sky all round (rgb 0-1).
	 */
	public static void draw(WorldRenderContext context, Vector3f sun, float sunStrength, float[] sky) {
		if (count == 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		var target = mc.getMainRenderTarget();
		if (target.getDepthTexture() == null || !(context.consumers() instanceof MultiBufferSource.BufferSource buffers)) {
			return;
		}
		ensure(mc);
		NativeImage img = data.getPixels();
		if (img == null) {
			return;
		}
		// how much sunlight gets to each puff past the others: the smoke lying between it and the sun
		for (int i = 0; i < count; i++) {
			float optical = 0.0F;
			for (int j = 0; j < count; j++) {
				if (j == i) {
					continue;
				}
				float vx = PX[j] - PX[i];
				float vy = PY[j] - PY[i];
				float vz = PZ[j] - PZ[i];
				float along = vx * sun.x + vy * sun.y + vz * sun.z;
				if (along <= 0.0F) {
					continue;
				}
				float perp2 = vx * vx + vy * vy + vz * vz - along * along;
				float r2 = PR[j] * PR[j];
				if (perp2 >= r2) {
					continue;
				}
				// the chord the sun's ray cuts through that puff, in its own radii, times its thickness
				optical += PA[j] * Mth.sqrt(r2 - perp2) / PR[j] * 1.3F;
			}
			float sunlit = (float) Math.exp(-optical * 0.7);
			int column = i;
			int[] w;
			w = word((PX[i] + 128.0F) * 256.0F);
			int[] wy = word((PY[i] + 128.0F) * 256.0F);
			img.setPixel(column, 0, pack(w[0], w[1], wy[0], wy[1]));
			int[] wz = word((PZ[i] + 128.0F) * 256.0F);
			int[] wr = word(PR[i] * 256.0F);
			img.setPixel(column, 1, pack(wz[0], wz[1], wr[0], wr[1]));
			int c = PC[i];
			img.setPixel(column, 2, pack(c >> 16 & 255, c >> 8 & 255, c & 255, byteOf(PA[i])));
			img.setPixel(column, 3, pack(byteOf(sunlit), byteOf(PSEED[i]), byteOf(PGLOW[i]), byteOf(PAGE[i])));
			int e = PEMIT[i];
			img.setPixel(column, 4, pack(e >> 16 & 255, e >> 8 & 255, e & 255, 255));
		}
		// where the camera is in the world (wrapped every 512 blocks), so the swirl does not slide with it
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		int[] cx = word((float) (Math.floorMod((long) Math.floor(cam.x * 128.0), 512L * 128L)));
		int[] cy = word((float) (Math.floorMod((long) Math.floor(cam.y * 128.0), 512L * 128L)));
		int[] cz = word((float) (Math.floorMod((long) Math.floor(cam.z * 128.0), 512L * 128L)));
		img.setPixel(COLUMNS - 1, 0, pack(cx[0], cx[1], cy[0], cy[1]));
		img.setPixel(COLUMNS - 1, 1, pack(cz[0], cz[1], 0, 0));
		data.upload();
		depth.copy(target.getDepthTexture());

		float seconds = (float) ((mc.level == null ? 0L : mc.level.getGameTime() % 72000L) / 20.0
			+ mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) / 20.0);
		int color = byteOf(sunStrength) << 24 | byteOf(sky[0]) << 16 | byteOf(sky[1]) << 8 | byteOf(sky[2]);
		screen(buffers.getBuffer(type), context.matrices().last(), mc.gameRenderer.getMainCamera(), color, sun, seconds, count, samples());
		buffers.endBatch(type);
	}

	private static void screen(VertexConsumer consumer, PoseStack.Pose pose, Camera camera, int color, Vector3f sun, float seconds, int n, int samples) {
		org.joml.Vector3fc f = camera.forwardVector();
		org.joml.Vector3fc u = camera.upVector();
		org.joml.Vector3fc left = camera.leftVector();
		float w = 6.0F;
		for (float[] c : new float[][] {{w, -w}, {-w, -w}, {-w, w}, {w, w}}) {
			float x = (f.x() + left.x() * c[0] + u.x() * c[1]) * 500.0F;
			float y = (f.y() + left.y() * c[0] + u.y() * c[1]) * 500.0F;
			float z = (f.z() + left.z() * c[0] + u.z() * c[1]) * 500.0F;
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(seconds, n).setOverlay(0).setLight(samples & 0xFFFF).setNormal(pose, sun.x, sun.y, sun.z);
		}
	}

	private static int[] word(float v) {
		int x = Mth.clamp(Math.round(v), 0, 65535);
		return new int[] {x >> 8, x & 255};
	}

	private static int byteOf(float v) {
		return Mth.clamp(Math.round(v * 255.0F), 0, 255);
	}

	/** A texel: r, g, b, a bytes (NativeImage takes ARGB). */
	private static int pack(int r, int g, int b, int a) {
		return (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | b & 255;
	}
}
