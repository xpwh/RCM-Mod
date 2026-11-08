package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.explosion.Tsunami;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The tsunami waves themselves, as water you can see coming: a ring of raised sea running out from the
 * burst - a long low swell over deep water that grows taller and steeper where the sea shallows, then
 * rears up into a breaking wall with a white crest curling over its face as it runs up the land, until
 * the ground has used it up (the same rule the server floods the land by). The train of waves follows
 * one after the other, each smaller.
 */
public final class TsunamiWaves {
	private static final RenderType TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/effect/tsunami.png"));
	private static final int BINS = 480;
	/** Rings across the wave, in blocks behind the front (negative: ahead of it, the foot). */
	private static final float[] U = {-1.5F, -0.4F, 0.4F, 1.2F, 2.2F, 3.5F, 5.0F, 7.0F, 9.5F, 12.5F, 16.0F, 20.0F, 25.0F, 31.0F, 38.0F};
	private static final float VIEW = 340.0F;
	private static final List<Wave> WAVES = new ArrayList<>();

	private TsunamiWaves() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(TsunamiWaves::tick);
		WorldRenderEvents.BEFORE_ENTITIES.register(TsunamiWaves::render);
	}

	/** A Poseidon went off at {@code pos}, the sea's surface water block at {@code sea}: the whole train sets out. */
	public static void start(Vec3 pos, double sea) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		long now = mc.level.getGameTime();
		for (int i = 0; i < Tsunami.AMPLITUDE.length; i++) {
			WAVES.add(new Wave(pos.x, pos.z, sea, now + Tsunami.DELAY[i], Tsunami.AMPLITUDE[i]));
		}
	}

	/** How far out the front of a wave is at {@code t} ticks after it set out. */
	static double front(double t) {
		return Tsunami.START_RADIUS + Tsunami.SPEED * t;
	}

	private static final class Wave {
		final double cx;
		final double cz;
		/** Y of the sea surface. */
		final double surface;
		final int seaBlock;
		final long start;
		final float amplitude;
		/** Per direction, worked out as the front gets there: how far it has been scanned, the height the wave has there, the steepness (0 a smooth swell over deep water, 1 a breaking bore), and its foot. */
		final double[] scanned = new double[BINS];
		final float[] height = new float[BINS];
		final float[] steep = new float[BINS];
		final float[] base = new float[BINS];
		final float[] energy = new float[BINS];
		final boolean[] dead = new boolean[BINS];

		Wave(double cx, double cz, double sea, long start, float amplitude) {
			this.cx = cx;
			this.cz = cz;
			this.seaBlock = (int) Math.floor(sea) - 1;
			this.surface = this.seaBlock + 0.89;
			this.start = start;
			this.amplitude = amplitude;
			java.util.Arrays.fill(this.scanned, Tsunami.START_RADIUS);
			java.util.Arrays.fill(this.energy, Float.MAX_VALUE);
		}

		/** Runs each direction's scan out to the front: over the sea the wave shoals, over the land it runs up and dies. */
		void scan(ClientLevel level, double front) {
			double h0 = Tsunami.height(front) * this.amplitude;
			BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
			for (int b = 0; b < BINS; b++) {
				if (this.dead[b]) {
					continue;
				}
				double a = (b + 0.5) * Mth.TWO_PI / BINS;
				double cos = Math.cos(a);
				double sin = Math.sin(a);
				while (this.scanned[b] < front && !this.dead[b]) {
					double r = this.scanned[b] = Math.min(front, this.scanned[b] + 1.5);
					int x = Mth.floor(this.cx + cos * r);
					int z = Mth.floor(this.cz + sin * r);
					if (!level.hasChunk(x >> 4, z >> 4)) {
						this.height[b] = (float) h0;
						this.steep[b] = 0.0F;
						this.base[b] = (float) this.surface;
						continue;
					}
					int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z); // first free block above, water counted
					p.set(x, this.seaBlock, z);
					boolean sea = top <= this.seaBlock + 1 && !level.getFluidState(p).isEmpty();
					if (sea) {
						// how deep the sea is here: the shallower, the taller and steeper the wave
						int depth = 0;
						while (depth < 24 && !level.getFluidState(p.move(0, -1, 0)).isEmpty()) {
							depth++;
						}
						float shoal = (float) Mth.clamp(Math.pow(10.0 / Math.max(1, depth), 0.25), 1.0, Tsunami.SHOAL);
						this.height[b] = (float) h0 * shoal;
						this.steep[b] = Mth.clamp((10.0F - depth) / 9.0F, 0.0F, 1.0F) * 0.85F;
						this.base[b] = (float) this.surface;
					} else {
						// the land: the same run-up the server floods it by
						int ground = top;
						float left = (float) Math.min(h0 * Tsunami.SHOAL, this.energy[b]);
						if (ground > this.seaBlock + Math.round(left)) {
							this.dead[b] = true; // a hill or a cliff stops it here
							break;
						}
						this.energy[b] = left - 0.14F - Math.max(0, ground - this.seaBlock - 1) * 0.05F;
						if (this.energy[b] <= 0.0F) {
							this.dead[b] = true;
							break;
						}
						this.height[b] = Math.max(1.0F, Math.min(left, 2.0F + left * 0.45F + (this.seaBlock + left - ground) * 0.5F));
						this.steep[b] = 1.0F;
						this.base[b] = Math.max((float) this.surface, ground - 0.1F);
					}
				}
			}
		}
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			WAVES.clear();
			return;
		}
		long now = mc.level.getGameTime();
		WAVES.removeIf(w -> now - w.start > (Tsunami.MAX_RADIUS - Tsunami.START_RADIUS) / Tsunami.SPEED + 10);
		for (Wave w : WAVES) {
			if (now >= w.start) {
				w.scan(mc.level, front(now - w.start + 1));
			}
		}
	}

	/** The wave's height across its width, 0..1, at {@code u} blocks behind the front, for a steepness. */
	private static float profile(float u, float steep) {
		float face = Mth.lerp(steep, 10.0F, 1.4F);
		float back = Mth.lerp(steep, 21.0F, 12.0F);
		if (u <= 0.0F) {
			return 0.0F;
		}
		if (u < face) {
			float k = u / face;
			// over deep water a smooth rise; a bore stands up almost at once
			return k * k * (3.0F - 2.0F * k);
		}
		float k = (u - face) / back;
		return (float) Math.exp(-k * k * 2.2);
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (WAVES.isEmpty() || mc.level == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double now = mc.level.getGameTime() + partial;
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.matrices();
		context.commandQueue().submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> {
			for (Wave w : WAVES) {
				double t = now - w.start;
				if (t < 0.0) {
					continue;
				}
				draw(pose, consumer, w, front(t), cam, mc);
			}
		});
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer consumer, Wave w, double front, Vec3 cam, Minecraft mc) {
		double toCam = Math.hypot(cam.x - w.cx, cam.z - w.cz);
		if (Math.abs(toCam - front) > VIEW + 40) {
			return;
		}
		int n = U.length;
		float[] xs = new float[n * 2];
		float[] ys = new float[n * 2];
		float[] zs = new float[n * 2];
		float[] fo = new float[n * 2];
		float[] ny = new float[n * 2];
		for (int b = 0; b < BINS; b++) {
			int b2 = (b + 1) % BINS;
			if (w.dead[b] || w.dead[b2] || w.height[b] <= 0.0F || w.height[b2] <= 0.0F) {
				continue;
			}
			double a0 = (b + 0.5) * Mth.TWO_PI / BINS;
			double a1 = (b + 1.5) * Mth.TWO_PI / BINS;
			double mx = w.cx + Math.cos((a0 + a1) * 0.5) * front - cam.x;
			double mz = w.cz + Math.sin((a0 + a1) * 0.5) * front - cam.z;
			if (mx * mx + mz * mz > VIEW * VIEW) {
				continue;
			}
			int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(w.cx + Math.cos(a0) * front, w.base[b] + w.height[b] + 1, w.cz + Math.sin(a0) * front));
			for (int side = 0; side < 2; side++) {
				int bb = side == 0 ? b : b2;
				double a = side == 0 ? a0 : a1;
				float c = (float) Math.cos(a);
				float s = (float) Math.sin(a);
				float h = w.height[bb];
				float st = w.steep[bb];
				for (int i = 0; i < n; i++) {
					float u = U[i];
					float y = profile(u, st);
					// a breaking crest pitches forward over its own face
					float lip = st > 0.55F ? (st - 0.55F) * 2.2F * h * 0.22F * (float) Math.exp(-Math.pow((u - Mth.lerp(st, 10.0F, 1.4F)) / 1.6F, 2.0)) : 0.0F;
					double r = front - u + lip;
					int k = side * n + i;
					xs[k] = (float) (w.cx + c * r - cam.x);
					zs[k] = (float) (w.cz + s * r - cam.z);
					ys[k] = (float) (w.base[bb] - 0.15 + h * y - cam.y);
					// white water: the crest and the face of a breaking wave, streaks of foam down its back
					float crest = (float) Math.exp(-Math.pow((y - 1.0F) / 0.18F, 2.0)) * (0.35F + 0.65F * st);
					float face = u > 0.0F && u < Mth.lerp(st, 10.0F, 1.4F) ? st * 0.7F : 0.0F;
					fo[k] = Mth.clamp(crest + face + (u < 0.5F ? 0.4F * st : 0.0F), 0.0F, 1.0F);
					// a rough normal: up, tipped toward the open sea on the face, back on the back slope
					float slope = (profile(u + 0.5F, st) - profile(u - 0.5F, st)) * h;
					ny[k] = slope;
				}
			}
			for (int i = 0; i < n - 1; i++) {
				// the fading back of the wave and its foot melt into the sea
				float edge0 = alphaAt(i, n);
				float edge1 = alphaAt(i + 1, n);
				quad(pose, consumer, light, xs, ys, zs, fo, ny, i, i + 1, n, edge0, edge1, a0, a1);
			}
		}
	}

	private static float alphaAt(int i, int n) {
		if (i == 0) {
			return 0.0F;
		}
		if (i >= n - 1) {
			return 0.0F;
		}
		return i >= n - 3 ? 0.5F : 1.0F;
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer consumer, int light, float[] xs, float[] ys, float[] zs, float[] fo, float[] ny, int i,
		int j, int n, float alphaI, float alphaJ, double a0, double a1) {
		int[] order = {i, j, n + j, n + i};
		float[] alpha = {alphaI, alphaJ, alphaJ, alphaI};
		double[] ang = {a0, a0, a1, a1};
		// the foam texture tiles round the ring segment by segment, and once across the wave
		float v0 = (U[i] - U[0]) / (U[U.length - 1] - U[0]);
		float v1 = (U[j] - U[0]) / (U[U.length - 1] - U[0]);
		float[][] uv = {{0.0F, v0}, {0.0F, v1}, {1.0F, v1}, {1.0F, v0}};
		for (int q = 0; q < 4; q++) {
			int k = order[q];
			float f = fo[k];
			// deep blue-green sea, going over to white where it breaks
			int r = (int) Mth.lerp(f, 38, 238);
			int g = (int) Mth.lerp(f, 92, 246);
			int bl = (int) Mth.lerp(f, 112, 250);
			int a = (int) (alpha[q] * Mth.lerp(f, 205, 240));
			float c = (float) Math.cos(ang[q]);
			float s = (float) Math.sin(ang[q]);
			// the slope across the wave tips the normal toward or away from the open sea
			float nx = -c * ny[k] * 0.25F;
			float nz = -s * ny[k] * 0.25F;
			float l = Mth.sqrt(nx * nx + 1.0F + nz * nz);
			consumer.addVertex(pose, xs[k], ys[k], zs[k]).setColor(r, g, bl, a).setUv(uv[q][0], uv[q][1]).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light).setNormal(pose, nx / l, 1.0F / l, nz / l);
		}
	}
}
