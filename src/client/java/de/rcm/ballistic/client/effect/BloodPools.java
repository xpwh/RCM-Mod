package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * Blood on the ground as a liquid, simulated. The top of every block it lies on is a grid of 12 x 12 little cells
 * holding a depth of blood. Each tick it runs from where it stands higher to where it stands lower - over the
 * ground's own small unevenness, so it creeps into hollows, puts out fingers and goes round the rises - until it
 * is down to the thin film that clings and stays (thicker here, thinner there, so the edge is never round). It runs
 * on over the flat into the next block's grid, pours down a step in a run down its face into the grid below, and at
 * a drop it gathers on the edge and falls, drop by drop. Earth and grass drink it faster than stone.
 * <p>
 * Drawn as one smooth surface: the depth at every cell corner sets how dark (deep, almost black-red), how see-through
 * (the thin film at the edge, lighter red) and how wet it is, the deepest pools standing a hair higher; where it has
 * dried a brown stain stays for minutes. A body bleeding out pours for a while from under it: the pool grows.
 */
public final class BloodPools {
	private static final int RES = 12;
	private static final int CELLS = RES * RES;
	private static final RenderType TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/effect/blood_film.png"));
	private static final RenderType GLOSS = RenderTypes.eyes(BallisticMissiles.id("textures/effect/blood_film_gloss.png"));
	/** How freely it runs (blood is thick). */
	private static final float FLOW = 0.13F;
	/** What gathers on an edge before a drop falls. */
	private static final float DROP = 3.2F;
	private static final int MAX = 900;
	private static final int LIFE = 18000;
	private static final RandomSource RANDOM = RandomSource.create();

	private static final int SAME = 0;
	private static final int STEP = 1;
	private static final int LEDGE = 2;
	private static final int WALL = 3;
	/** Sides: north (-z), east (+x), south (+z), west (-x). */
	private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private static final class Patch {
		final BlockPos pos;
		final double top;
		final boolean soaks;
		final long born;
		final float[] h = new float[CELLS];
		final float[] stain = new float[CELLS];
		/** The ground's small unevenness and how thick a film clings where it stops, per cell. */
		final float[] ground = new float[CELLS];
		final float[] film = new float[CELLS];
		final float[] runs = new float[4 * RES];
		final float[] drips = new float[4 * RES];
		final int[] side = {-1, -1, -1, -1};
		final BlockPos[] target = new BlockPos[4];
		final double[] targetTop = new double[4];
		boolean awake = true;
		int quiet;
		long lastDrip;
		/** When fresh blood last came: it darkens over minutes as it stands. */
		long fed;

		Patch(BlockPos pos, double top, boolean soaks, long now) {
			this.pos = pos;
			this.top = top;
			this.soaks = soaks;
			this.born = now;
			for (int iz = 0; iz < RES; iz++) {
				for (int ix = 0; ix < RES; ix++) {
					int wx = pos.getX() * RES + ix;
					int wz = pos.getZ() * RES + iz;
					this.ground[iz * RES + ix] = 0.55F * (noise(wx, wz, 5.0F, 11) - 0.5F) + 0.25F * (noise(wx, wz, 2.0F, 23) - 0.5F);
					this.film[iz * RES + ix] = 0.55F + 1.0F * noise(wx, wz, 4.0F, 37) + 0.35F * noise(wx, wz, 1.5F, 41);
				}
			}
		}
	}

	private record Source(Vec3 at, float rate, int[] left) {
	}

	private static final Map<Long, Patch> PATCHES = new LinkedHashMap<>();
	private static final List<Source> SOURCES = new ArrayList<>();

	private BloodPools() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(BloodPools::tick);
		WorldRenderEvents.BEFORE_ENTITIES.register(BloodPools::render);
	}

	// ---------------------------------------------------------------- noise (in world sub-cell coordinates)

	private static float hash(int x, int z, int seed) {
		int h = x * 374761393 + z * 668265263 + seed * 144665;
		h = (h ^ (h >>> 13)) * 1274126177;
		return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0F;
	}

	/** Smooth value noise, one wave every {@code scale} cells. */
	private static float noise(int x, int z, float scale, int seed) {
		float fx = x / scale;
		float fz = z / scale;
		int ix = Mth.floor(fx);
		int iz = Mth.floor(fz);
		float u = fx - ix;
		float v = fz - iz;
		u = u * u * (3 - 2 * u);
		v = v * v * (3 - 2 * v);
		float a = hash(ix, iz, seed);
		float b = hash(ix + 1, iz, seed);
		float c = hash(ix, iz + 1, seed);
		float d = hash(ix + 1, iz + 1, seed);
		return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
	}

	// ---------------------------------------------------------------- the ground

	/** The top of the block at pos, if blood can lie on it (NaN if not). */
	private static double topOf(ClientLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (!s.getFluidState().isEmpty()) {
			return Double.NaN;
		}
		VoxelShape shape = s.getCollisionShape(level, pos);
		if (shape.isEmpty()) {
			return Double.NaN;
		}
		return pos.getY() + shape.max(Direction.Axis.Y);
	}

	private static boolean passable(ClientLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
	}

	/** What lies past each side of the patch: more of the same ground, a step down, a drop, a wall. */
	private static void classify(ClientLevel level, Patch p) {
		for (int s = 0; s < 4; s++) {
			BlockPos n = p.pos.relative(SIDES[s]);
			double t = topOf(level, n);
			if (!Double.isNaN(t) && Math.abs(t - p.top) < 0.05 && passable(level, n.above())) {
				p.side[s] = SAME;
				p.target[s] = n;
				p.targetTop[s] = t;
			} else if (Double.isNaN(t) && passable(level, n) || !Double.isNaN(t) && t < p.top - 0.05 && passable(level, n.above())) {
				BlockPos low = Double.isNaN(t) ? n.below() : n;
				double tl = Double.isNaN(t) ? topOf(level, low) : t;
				if (!Double.isNaN(tl) && p.top - tl < 1.6) {
					p.side[s] = STEP;
					p.target[s] = low;
					p.targetTop[s] = tl;
				} else {
					p.side[s] = LEDGE;
				}
			} else {
				p.side[s] = WALL;
			}
		}
	}

	private static Patch patch(ClientLevel level, BlockPos pos, double top, boolean create) {
		Patch p = PATCHES.get(pos.asLong());
		if (p != null && Math.abs(p.top - top) > 0.05) {
			PATCHES.remove(pos.asLong());
			p = null;
		}
		if (p == null && create) {
			if (PATCHES.size() >= MAX) {
				Iterator<Long> it = PATCHES.keySet().iterator();
				it.next();
				it.remove();
			}
			BlockState s = level.getBlockState(pos);
			boolean soaks = s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.WOOL) || s.is(BlockTags.LEAVES);
			p = new Patch(pos.immutable(), top, soaks, level.getGameTime());
			p.fed = level.getGameTime();
			classify(level, p);
			PATCHES.put(pos.asLong(), p);
		}
		return p;
	}

	// ---------------------------------------------------------------- what lands

	/** {@code amount} of blood landing at {@code at}: into the cell of the ground below it. */
	public static void add(ClientLevel level, Vec3 at, float amount) {
		BlockPos pos = BlockPos.containing(at.x, at.y - 0.03, at.z);
		double top = topOf(level, pos);
		if (Double.isNaN(top)) {
			pos = pos.below();
			top = topOf(level, pos);
			if (Double.isNaN(top)) {
				return;
			}
		}
		if (!passable(level, pos.above())) {
			return;
		}
		Patch p = patch(level, pos, top, true);
		int ix = Mth.clamp((int) Math.floor((at.x - pos.getX()) * RES), 0, RES - 1);
		int iz = Mth.clamp((int) Math.floor((at.z - pos.getZ()) * RES), 0, RES - 1);
		p.h[iz * RES + ix] += amount;
		p.awake = true;
		p.quiet = 0;
		p.fed = level.getGameTime();
	}

	/** A drop of {@code size} landing at {@code speed}: its blood where it hit, flung out round it in droplets if fast. */
	public static void splash(ClientLevel level, Vec3 at, float size, float speed) {
		add(level, at, size * 75.0F);
		int n = speed > 0.25F ? 1 + RANDOM.nextInt(Math.min(6, 2 + (int) (speed * 6))) : 0;
		for (int i = 0; i < n; i++) {
			double a = RANDOM.nextDouble() * Math.PI * 2;
			double r = 0.05 + RANDOM.nextDouble() * speed * 0.35;
			add(level, at.add(Math.cos(a) * r, 0.0, Math.sin(a) * r), size * (6.0F + RANDOM.nextFloat() * 10.0F));
		}
	}

	/** Blood running out under a body: {@code total} poured at {@code at} over {@code ticks} - the pool grows. */
	public static void pour(Vec3 at, float total, int ticks) {
		SOURCES.add(new Source(at, total / Math.max(1, ticks), new int[] {ticks}));
	}

	// ---------------------------------------------------------------- the flow

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) {
			PATCHES.clear();
			SOURCES.clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		long now = level.getGameTime();
		for (Iterator<Source> it = SOURCES.iterator(); it.hasNext();) {
			Source s = it.next();
			// it wells up under the body, a little here, a little there
			add(level, s.at.add((RANDOM.nextDouble() - 0.5) * 0.3, 0.05, (RANDOM.nextDouble() - 0.5) * 0.3), s.rate);
			if (--s.left[0] <= 0) {
				it.remove();
			}
		}
		if (PATCHES.isEmpty()) {
			return;
		}
		List<Patch> all = new ArrayList<>(PATCHES.values());
		for (Patch p : all) {
			if ((now + p.pos.hashCode()) % 40 == 0) {
				if (Double.isNaN(topOf(level, p.pos)) || !passable(level, p.pos.above())) {
					PATCHES.remove(p.pos.asLong());
					continue;
				}
				classify(level, p);
			}
			if (p.awake) {
				flow(level, p, now);
			}
			// soaking in, drying out; the stain it leaves
			float keep = p.soaks ? 0.99985F : 0.99995F;
			float drink = p.soaks ? 0.0004F : 0.0001F;
			boolean any = false;
			for (int i = 0; i < CELLS; i++) {
				float h = p.h[i];
				if (h > 0.0F) {
					h = Math.max(0.0F, h * keep - drink);
					p.h[i] = h;
					p.stain[i] = Math.max(p.stain[i], Math.min(1.0F, h * 0.7F));
				}
				p.stain[i] *= 0.99993F;
				any |= h > 0.01F || p.stain[i] > 0.03F;
			}
			for (int k = 0; k < 4 * RES; k++) {
				p.runs[k] *= 0.9985F;
				any |= p.runs[k] > 0.03F;
			}
			if (!any || now - p.born > LIFE) {
				PATCHES.remove(p.pos.asLong());
			}
		}
	}

	private static void flow(ClientLevel level, Patch p, long now) {
		float moved = 0.0F;
		float[] out = new float[4];
		for (int iz = 0; iz < RES; iz++) {
			for (int ix = 0; ix < RES; ix++) {
				int i = iz * RES + ix;
				float h = p.h[i];
				float free = h - p.film[i];
				if (free <= 0.0F) {
					continue;
				}
				float level0 = h + p.ground[i];
				float total = 0.0F;
				for (int s = 0; s < 4; s++) {
					int nx = ix + SIDES[s].getStepX();
					int nz = iz + SIDES[s].getStepZ();
					out[s] = 0.0F;
					if (nx >= 0 && nx < RES && nz >= 0 && nz < RES) {
						int j = nz * RES + nx;
						float diff = level0 - (p.h[j] + p.ground[j]);
						if (diff > 0.0F) {
							out[s] = FLOW * diff;
						}
					} else {
						int type = p.side[s];
						if (type == SAME) {
							Patch o = PATCHES.get(p.target[s].asLong());
							int jx = Math.floorMod(nx, RES);
							int jz = Math.floorMod(nz, RES);
							float there = o == null ? 0.0F : o.h[jz * RES + jx];
							float g = o == null ? 0.0F : o.ground[jz * RES + jx];
							float diff = level0 - (there + g);
							if (diff > 0.0F) {
								out[s] = FLOW * diff;
							}
						} else if (type == STEP) {
							out[s] = FLOW * free * 0.9F;
						} else if (type == LEDGE) {
							out[s] = FLOW * free * 0.6F;
						}
					}
					total += out[s];
				}
				if (total <= 1.0E-5F) {
					continue;
				}
				// no more than half of what is free goes in one tick (it stays steady)
				float scale = Math.min(1.0F, free * 0.5F / total);
				for (int s = 0; s < 4; s++) {
					float m = out[s] * scale;
					if (m <= 0.0F) {
						continue;
					}
					p.h[i] -= m;
					moved += m;
					int nx = ix + SIDES[s].getStepX();
					int nz = iz + SIDES[s].getStepZ();
					if (nx >= 0 && nx < RES && nz >= 0 && nz < RES) {
						p.h[nz * RES + nx] += m;
						continue;
					}
					int e = s == 0 || s == 2 ? ix : iz;
					int jx = Math.floorMod(nx, RES);
					int jz = Math.floorMod(nz, RES);
					switch (p.side[s]) {
						case SAME -> {
							Patch o = patch(level, p.target[s], p.targetTop[s], true);
							o.h[jz * RES + jx] += m;
							o.awake = true;
							o.quiet = 0;
							o.fed = Math.max(o.fed, p.fed);
						}
						case STEP -> {
							// over the edge and down its face, into the ground below at the foot of it
							p.runs[s * RES + e] = Math.min(4.0F, p.runs[s * RES + e] + m);
							Patch o = patch(level, p.target[s], p.targetTop[s], true);
							o.h[jz * RES + jx] += m;
							o.awake = true;
							o.quiet = 0;
							o.fed = Math.max(o.fed, p.fed);
						}
						default -> {
							// gathering on the edge until a drop falls
							p.drips[s * RES + e] += m;
							if (p.drips[s * RES + e] >= DROP) {
								p.drips[s * RES + e] -= DROP;
								double ex = p.pos.getX() + (s == 1 ? 1.02 : s == 3 ? -0.02 : (ix + 0.5) / RES);
								double ez = p.pos.getZ() + (s == 2 ? 1.02 : s == 0 ? -0.02 : (iz + 0.5) / RES);
								BloodClient.drop(new Vec3(ex, p.top - 0.03, ez), new Vec3(SIDES[s].getStepX() * 0.004, -0.02, SIDES[s].getStepZ() * 0.004),
									0.042F);
								if (now - p.lastDrip > 10) {
									p.lastDrip = now;
									level.playLocalSound(ex, p.top - 1.5, ez, ModRegistry.GORE_DRIP, SoundSource.NEUTRAL, 0.2F, 0.85F + RANDOM.nextFloat() * 0.3F,
										false);
								}
							}
						}
					}
				}
			}
		}
		if (moved < 0.003F) {
			if (++p.quiet > 40) {
				p.awake = false;
			}
		} else {
			p.quiet = 0;
		}
	}

	// ---------------------------------------------------------------- drawing

	/** The depth (or stain) at cell (ix, iz) of p's grid, looking into the neighbouring ground past its edges. */
	private static float sample(Patch p, int ix, int iz, boolean stain) {
		if (ix >= 0 && ix < RES && iz >= 0 && iz < RES) {
			return stain ? p.stain[iz * RES + ix] : p.h[iz * RES + ix];
		}
		int bx = Math.floorDiv(ix, RES);
		int bz = Math.floorDiv(iz, RES);
		Patch o = PATCHES.get(BlockPos.asLong(p.pos.getX() + bx, p.pos.getY(), p.pos.getZ() + bz));
		if (o == null || Math.abs(o.top - p.top) > 0.05) {
			return 0.0F;
		}
		int j = Math.floorMod(iz, RES) * RES + Math.floorMod(ix, RES);
		return stain ? o.stain[j] : o.h[j];
	}

	private static float smooth(float e0, float e1, float x) {
		float t = Mth.clamp((x - e0) / (e1 - e0), 0.0F, 1.0F);
		return t * t * (3 - 2 * t);
	}

	/** Colour of blood this deep, with this much stain, {@code age} 0 fresh .. 1 standing for minutes: ARGB. */
	private static int colour(float h, float s, float fade, float age) {
		float wet = smooth(0.18F, 1.0F, h);
		float thick = smooth(0.45F, 2.4F, h);
		float dry = s * 0.62F;
		float a = Math.max(wet * 0.94F, dry) * fade;
		if (a < 0.004F) {
			return 0;
		}
		// wet: a thin film a clear red, deep almost black-red, darker the longer it stands; dried: dark brown
		float darken = 1.0F - 0.38F * age;
		float wr = Mth.lerp(thick, 118.0F, 40.0F) * darken;
		float wg = Mth.lerp(thick, 10.0F, 1.0F) * darken;
		float wb = Mth.lerp(thick, 12.0F, 3.0F) * darken;
		float k = wet * 0.94F >= dry ? 1.0F : wet * 0.94F / Math.max(dry, 1.0E-4F);
		int r = (int) Mth.lerp(k, 64.0F, wr);
		int g = (int) Mth.lerp(k, 20.0F, wg);
		int b = (int) Mth.lerp(k, 14.0F, wb);
		return (int) (a * 255.0F) << 24 | r << 16 | g << 8 | b;
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || PATCHES.isEmpty()) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		long now = mc.level.getGameTime();
		List<Patch> near = new ArrayList<>();
		for (Patch p : PATCHES.values()) {
			if (p.pos.distToCenterSqr(cam) < 72.0 * 72.0) {
				near.add(p);
			}
		}
		context.commandQueue().submitCustomGeometry(context.matrices(), TYPE, (pose, consumer) -> {
			for (Patch p : near) {
				surface(pose, consumer, p, cam, now, mc, false);
				runs(pose, consumer, p, cam, mc);
			}
		});
		context.commandQueue().order(1).submitCustomGeometry(context.matrices(), GLOSS, (pose, consumer) -> {
			for (Patch p : near) {
				if (p.pos.distToCenterSqr(cam) < 40.0 * 40.0) {
					surface(pose, consumer, p, cam, now, mc, true);
				}
			}
		});
	}

	/** The patch's surface: a quad per cell, each corner's depth the average of the four cells round it. */
	private static void surface(PoseStack.Pose pose, VertexConsumer consumer, Patch p, Vec3 cam, long now, Minecraft mc, boolean gloss) {
		int light = LevelRenderer.getLightColor(mc.level, p.pos.above());
		float fade = Mth.clamp((LIFE - (now - p.born)) / 900.0F, 0.0F, 1.0F);
		float age = Mth.clamp((now - p.fed) / 2400.0F, 0.0F, 1.0F);
		int n = RES + 1;
		float[] ch = new float[n * n];
		float[] cs = new float[n * n];
		boolean any = false;
		for (int cz = 0; cz < n; cz++) {
			for (int cx = 0; cx < n; cx++) {
				float h = 0.0F;
				float s = 0.0F;
				for (int dz = -1; dz <= 0; dz++) {
					for (int dx = -1; dx <= 0; dx++) {
						h += sample(p, cx + dx, cz + dz, false);
						s += sample(p, cx + dx, cz + dz, true);
					}
				}
				ch[cz * n + cx] = h * 0.25F;
				cs[cz * n + cx] = s * 0.25F;
				any |= h > 0.04F || s > 0.04F;
			}
		}
		if (!any) {
			return;
		}
		float bx = (float) (p.pos.getX() - cam.x);
		float bz = (float) (p.pos.getZ() - cam.z);
		float by = (float) (p.top + 0.003 + (p.pos.hashCode() & 3) * 0.0004 - cam.y);
		float u0 = (p.pos.getX() & 63) * 0.5F;
		float v0 = (p.pos.getZ() & 63) * 0.5F;
		int[] col = new int[n * n];
		Vector3f probe = new Vector3f();
		Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F);
		for (int k = 0; k < n * n; k++) {
			if (gloss) {
				float wet = smooth(0.3F, 1.6F, ch[k]) * fade;
				if (wet > 0.01F) {
					probe.set(bx + (k % n) / (float) RES, by, bz + (k / n) / (float) RES);
					wet *= de.rcm.ballistic.client.render.WetShine.at(probe, up, light);
				}
				col[k] = (int) (Mth.clamp(wet, 0.0F, 1.0F) * 210.0F) << 24 | 0xFFFFFF;
			} else {
				col[k] = colour(ch[k], cs[k], fade, age);
			}
		}
		for (int iz = 0; iz < RES; iz++) {
			for (int ix = 0; ix < RES; ix++) {
				int c00 = iz * n + ix;
				int c10 = c00 + 1;
				int c01 = c00 + n;
				int c11 = c01 + 1;
				if ((col[c00] | col[c10] | col[c01] | col[c11]) >>> 24 == 0) {
					continue;
				}
				corner(pose, consumer, bx, by, bz, ix, iz, ch[c00], col[c00], u0, v0, light);
				corner(pose, consumer, bx, by, bz, ix, iz + 1, ch[c01], col[c01], u0, v0, light);
				corner(pose, consumer, bx, by, bz, ix + 1, iz + 1, ch[c11], col[c11], u0, v0, light);
				corner(pose, consumer, bx, by, bz, ix + 1, iz, ch[c10], col[c10], u0, v0, light);
			}
		}
	}

	private static void corner(PoseStack.Pose pose, VertexConsumer consumer, float bx, float by, float bz, int cx, int cz, float h, int colour, float u0,
		float v0, int light) {
		float x = cx / (float) RES;
		float z = cz / (float) RES;
		// a deep pool stands a hair proud of the ground
		float y = by + Math.min(0.018F, h * 0.0035F);
		consumer.addVertex(pose, bx + x, y, bz + z).setColor(colour).setUv(u0 + x * 0.5F, v0 + z * 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light).setNormal(pose, 0.0F, 1.0F, 0.0F);
	}

	/** Where it poured down a step: runs down the face, as wide as the cells that fed them. */
	private static void runs(PoseStack.Pose pose, VertexConsumer consumer, Patch p, Vec3 cam, Minecraft mc) {
		int light = LevelRenderer.getLightColor(mc.level, p.pos.above());
		for (int s = 0; s < 4; s++) {
			if (p.side[s] != STEP) {
				continue;
			}
			Direction d = SIDES[s];
			float drop = (float) (p.top - p.targetTop[s]);
			for (int e = 0; e < RES; e++) {
				float r = p.runs[s * RES + e];
				if (r < 0.03F) {
					continue;
				}
				int colour = colour(0.4F + r * 2.0F, Math.min(1.0F, r), 1.0F, Mth.clamp((mc.level.getGameTime() - p.fed) / 2400.0F, 0.0F, 1.0F));
				if (colour >>> 24 == 0) {
					continue;
				}
				float a = (e + 0.1F) / RES;
				float b = (e + 0.9F) / RES;
				float fx = (float) (p.pos.getX() - cam.x);
				float fz = (float) (p.pos.getZ() - cam.z);
				float top = (float) (p.top - cam.y);
				float bottom = top - drop * Math.min(1.0F, 0.35F + r * 0.8F);
				float[][] pts;
				if (s == 0 || s == 2) {
					float z = fz + (s == 2 ? 1.003F : -0.003F);
					pts = new float[][] {{fx + a, top, z}, {fx + b, top, z}, {fx + b, bottom, z}, {fx + a, bottom, z}};
				} else {
					float x = fx + (s == 1 ? 1.003F : -0.003F);
					pts = new float[][] {{x, top, fz + a}, {x, top, fz + b}, {x, bottom, fz + b}, {x, bottom, fz + a}};
				}
				for (float[] q : pts) {
					consumer.addVertex(pose, q[0], q[1], q[2]).setColor(colour).setUv(q[0] * 0.5F + q[2] * 0.5F, q[1] * 0.5F)
						.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, d.getStepX(), 0.0F, d.getStepZ());
				}
			}
		}
	}
}
