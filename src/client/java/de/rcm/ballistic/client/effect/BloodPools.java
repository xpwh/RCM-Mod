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
	/** The film a run leaves on the wall per block it runs down (at a half-width of 0.015). */
	private static final float CLING = 5.0F;
	/** The longest wall a run follows down. */
	private static final int WALL_BLOCKS = 6;
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
		/** How much has lately been pouring over each edge cell (the wet lip it leaves). */
		final float[] lip = new float[4 * RES];
		final int[] side = {-1, -1, -1, -1};
		final BlockPos[] target = new BlockPos[4];
		final double[] targetTop = new double[4];
		/** Down a step or a drop: how far the wall face below the edge goes, the runs on it, which run each edge cell feeds. */
		final float[] wall = new float[4];
		final Run[][] rivulets = new Run[4][];
		final int[][] catchment = new int[4][];
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

	/**
	 * One run of blood down a wall. What pours over the edge does not come down as a sheet: it gathers into a few
	 * rivulets (where the lip is a little lower), each a thin winding line with a bead of blood at its front. The front
	 * creeps down only as fast as blood is fed into it, and leaves a film clinging to the wall as it goes - a run fed
	 * too little stops halfway, its bead hanging there. Only once a run reaches the foot does the blood in it run into
	 * the ground below (or, over an overhang, gather in a hanging drop at its end until it falls).
	 */
	private static final class Run {
		/** Where along the edge (0..1) it comes down. */
		final float x;
		final int seed;
		/** Blood in it on the way down, not yet at the front. */
		float stream;
		/** Fed this tick; the smoothed rate of it; the most it has carried (sets its width). */
		float inflow;
		float rate;
		float peak;
		/** 1 while blood runs in it, falling as it drains to a film; the stain it leaves. */
		float wet;
		float stain;
		float front;
		float prevFront;
		/** The drop gathering at its end, over an overhang. */
		float hang;
		long fed;

		Run(float x, int seed) {
			this.x = x;
			this.seed = seed;
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

	/**
	 * What lies past each side of the patch: more of the same ground, a wall, or open air - and then how far the face
	 * below the edge goes down: to the ground at its foot (a step), or to where it ends over empty air (a drop).
	 */
	private static void classify(ClientLevel level, Patch p) {
		for (int s = 0; s < 4; s++) {
			int was = p.side[s];
			float wasWall = p.wall[s];
			BlockPos n = p.pos.relative(SIDES[s]);
			double t = topOf(level, n);
			p.target[s] = null;
			if (!Double.isNaN(t) && Math.abs(t - p.top) < 0.05 && passable(level, n.above())) {
				p.side[s] = SAME;
				p.target[s] = n;
				p.targetTop[s] = t;
			} else if (Double.isNaN(t) && passable(level, n) || !Double.isNaN(t) && t < p.top - 0.05 && passable(level, n.above())) {
				double foot = Double.NaN;
				double end = p.pos.getY();
				if (!Double.isNaN(t)) {
					// a lower block right beside it (a slab, a path)
					foot = t;
					p.target[s] = n;
				} else {
					for (int k = 1; k <= WALL_BLOCKS; k++) {
						BlockPos g = n.below(k);
						double tg = topOf(level, g);
						if (!Double.isNaN(tg)) {
							foot = tg;
							p.target[s] = g;
							break;
						}
						if (!passable(level, g)) {
							// it runs down into water: gone at the foot
							foot = g.getY() + 0.9;
							break;
						}
						if (Double.isNaN(topOf(level, p.pos.below(k)))) {
							// the wall stops over empty air: an overhang
							break;
						}
						end = p.pos.getY() - k;
					}
				}
				if (!Double.isNaN(foot)) {
					p.side[s] = STEP;
					p.targetTop[s] = foot;
					p.wall[s] = (float) (p.top - foot);
				} else {
					p.side[s] = LEDGE;
					p.wall[s] = (float) (p.top - end);
				}
			} else {
				p.side[s] = WALL;
			}
			if (p.side[s] != STEP && p.side[s] != LEDGE) {
				p.rivulets[s] = null;
			} else if (p.rivulets[s] == null || was != p.side[s] || Math.abs(wasWall - p.wall[s]) > 0.02F) {
				anchors(p, s);
			}
		}
	}

	/**
	 * Where along an edge its runs come down: three to five, unevenly spaced (the same for the same edge every time,
	 * matched along the world, not the block), and each edge cell pouring into the nearest.
	 */
	private static void anchors(Patch p, int s) {
		boolean alongX = s == 0 || s == 2;
		int line = alongX ? p.pos.getZ() + (s == 2 ? 1 : 0) : p.pos.getX() + (s == 1 ? 1 : 0);
		int origin = alongX ? p.pos.getX() : p.pos.getZ();
		int key = line * 31 + p.pos.getY() * 7 + (alongX ? 0 : 1);
		List<Run> runs = new ArrayList<>();
		float a = 0.06F + 0.12F * hash(key, origin, 101);
		for (int i = 0; a < 0.95F; i++) {
			runs.add(new Run(a, (int) (hash(key, origin * 16 + i, 107) * 1.0E6F)));
			a += 0.16F + 0.22F * hash(key, origin * 16 + i, 103);
		}
		Run[] rs = runs.toArray(new Run[0]);
		int[] c = new int[RES];
		for (int e = 0; e < RES; e++) {
			float at = (e + 0.5F) / RES;
			int best = 0;
			for (int i = 1; i < rs.length; i++) {
				if (Math.abs(rs[i].x - at) < Math.abs(rs[best].x - at)) {
					best = i;
				}
			}
			c[e] = best;
		}
		p.rivulets[s] = rs;
		p.catchment[s] = c;
	}

	/** Smooth 1D value noise, 0..1, one wave per unit of t. */
	private static float wave(int seed, float t) {
		int i = Mth.floor(t);
		float f = t - i;
		f = f * f * (3 - 2 * f);
		float a = hash(i, seed, 61);
		return a + (hash(i + 1, seed, 61) - a) * f;
	}

	/** How wide a run is (half of it): thin for a trickle, up to a finger's width for a stream. */
	private static float halfWidth(Run r) {
		return 0.006F + 0.018F * (float) Math.sqrt(Math.min(1.0F, r.peak / 0.4F));
	}

	/** How far it has wandered sideways (along the edge) at depth y below the edge: the wall is never quite even. */
	private static float meander(Run r, float y) {
		float m = 0.026F * (wave(r.seed, y * 4.0F) - 0.5F) * 2.0F + 0.009F * (wave(r.seed + 17, y * 15.0F) - 0.5F) * 2.0F;
		return m * smooth(0.0F, 0.15F, y);
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
				p.lip[k] *= 0.995F;
			}
			any |= trickle(level, p, now);
			if (!any || now - p.born > LIFE) {
				PATCHES.remove(p.pos.asLong());
			}
		}
	}

	/** The runs down the walls: their fronts creep on as far as their blood takes them. True while any is still to be seen. */
	private static boolean trickle(ClientLevel level, Patch p, long now) {
		boolean any = false;
		for (int s = 0; s < 4; s++) {
			Run[] rs = p.rivulets[s];
			if (rs == null) {
				continue;
			}
			float wall = p.wall[s];
			for (Run r : rs) {
				r.prevFront = r.front;
				r.rate = r.rate * 0.9F + r.inflow * 0.1F;
				r.inflow = 0.0F;
				r.peak = Math.max(r.peak * 0.9997F, r.rate);
				if (r.rate > 0.003F) {
					r.wet = Math.min(1.0F, Math.max(r.wet, r.rate * 5.0F));
					r.fed = now;
				} else {
					r.wet *= 0.995F;
				}
				r.stain *= 0.99997F;
				if (r.front < wall && r.stream > 0.0F) {
					// the front goes on as fast as it is fed, and only as far as there is blood to wet the wall with
					float cling = CLING * (0.5F + 0.5F * halfWidth(r) / 0.015F);
					float v = 0.004F + 0.03F * Math.min(1.0F, (r.rate + r.stream * 0.04F) / 0.4F);
					float need = v * cling;
					if (r.stream >= need) {
						r.front += v;
						r.stream -= need;
					} else {
						r.front += r.stream / cling;
						r.stream = 0.0F;
					}
					r.front = Math.min(r.front, wall);
					r.stain = 1.0F;
				}
				if (r.front >= wall && r.stream > 0.0F) {
					if (p.side[s] == STEP) {
						if (p.target[s] != null) {
							// down at the foot: into the ground there, beside the wall
							int e = Mth.clamp((int) ((r.x + meander(r, wall)) * RES), 0, RES - 1);
							int jx = s == 1 ? 0 : s == 3 ? RES - 1 : e;
							int jz = s == 2 ? 0 : s == 0 ? RES - 1 : e;
							Patch o = patch(level, p.target[s], p.targetTop[s], true);
							o.h[jz * RES + jx] += r.stream;
							o.awake = true;
							o.quiet = 0;
							o.fed = Math.max(o.fed, now);
						}
					} else {
						r.hang += r.stream;
					}
					r.stream = 0.0F;
				}
				if (r.hang >= DROP) {
					// heavy enough: the hanging drop lets go
					r.hang -= DROP;
					float a = Mth.clamp(r.x + meander(r, wall), 0.02F, 0.98F);
					Direction d = SIDES[s];
					double ex = p.pos.getX() + (s == 1 ? 1.02 : s == 3 ? -0.02 : a);
					double ez = p.pos.getZ() + (s == 2 ? 1.02 : s == 0 ? -0.02 : a);
					double ey = p.top - wall - 0.03;
					BloodClient.drop(new Vec3(ex, ey, ez), new Vec3(d.getStepX() * 0.002, -0.01, d.getStepZ() * 0.002), 0.042F);
					if (now - p.lastDrip > 10) {
						p.lastDrip = now;
						level.playLocalSound(ex, ey - 1.0, ez, ModRegistry.GORE_DRIP, SoundSource.NEUTRAL, 0.2F, 0.85F + RANDOM.nextFloat() * 0.3F, false);
					}
				}
				any |= r.stain > 0.03F || r.stream > 0.0F || r.hang > 0.0F;
			}
		}
		return any;
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
						default -> {
							// over the edge: wetting the lip, gathering into the run below it (trickle takes it down)
							p.lip[s * RES + e] = Math.min(3.0F, p.lip[s * RES + e] + m);
							if (p.rivulets[s] != null) {
								Run r = p.rivulets[s][p.catchment[s][e]];
								r.stream += m;
								r.inflow += m;
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
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		List<Patch> near = new ArrayList<>();
		for (Patch p : PATCHES.values()) {
			if (p.pos.distToCenterSqr(cam) < 72.0 * 72.0) {
				near.add(p);
			}
		}
		context.commandQueue().submitCustomGeometry(context.matrices(), TYPE, (pose, consumer) -> {
			for (Patch p : near) {
				surface(pose, consumer, p, cam, now, mc, false);
				walls(pose, consumer, p, cam, now, partial, mc, false);
			}
		});
		context.commandQueue().order(1).submitCustomGeometry(context.matrices(), GLOSS, (pose, consumer) -> {
			for (Patch p : near) {
				if (p.pos.distToCenterSqr(cam) < 40.0 * 40.0) {
					surface(pose, consumer, p, cam, now, mc, true);
					walls(pose, consumer, p, cam, now, partial, mc, true);
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

	// ---------------------------------------------------------------- down the walls

	/** A point on the wall face below side s's edge: {@code a} along the edge, {@code y} below it, {@code out} off the face. */
	private static void facePoint(float[] o, int s, float bx, float top, float bz, float a, float y, float out) {
		switch (s) {
			case 0 -> {
				o[0] = bx + a;
				o[2] = bz - out;
			}
			case 2 -> {
				o[0] = bx + a;
				o[2] = bz + 1.0F + out;
			}
			case 1 -> {
				o[0] = bx + 1.0F + out;
				o[2] = bz + a;
			}
			default -> {
				o[0] = bx - out;
				o[2] = bz + a;
			}
		}
		o[1] = top - y;
	}

	/** Same colour, less of it. */
	private static int thin(int argb, float k) {
		return (int) ((argb >>> 24) * k) << 24 | argb & 0xFFFFFF;
	}

	/** The faces below the edges it ran over: the wet lip, and the runs coming down. */
	private static void walls(PoseStack.Pose pose, VertexConsumer consumer, Patch p, Vec3 cam, long now, float partial, Minecraft mc, boolean gloss) {
		float fade = Mth.clamp((LIFE - (now - p.born)) / 900.0F, 0.0F, 1.0F);
		float bx = (float) (p.pos.getX() - cam.x);
		float bz = (float) (p.pos.getZ() - cam.z);
		float top = (float) (p.top - cam.y);
		float[] q = new float[3];
		Vector3f probe = new Vector3f();
		for (int s = 0; s < 4; s++) {
			Run[] rs = p.rivulets[s];
			if (rs == null) {
				continue;
			}
			Direction d = SIDES[s];
			Vector3f normal = new Vector3f(d.getStepX(), 0.0F, d.getStepZ());
			int light = LevelRenderer.getLightColor(mc.level, p.pos.relative(d));
			// the lip: a film of blood over the edge where it pours over, running out in a soft line just below it
			for (int e = 0; e < RES; e++) {
				float v = p.lip[s * RES + e];
				if (v < 0.02F) {
					continue;
				}
				float a0 = Math.max(0.0F, (e - 0.15F) / RES);
				float a1 = Math.min(1.0F, (e + 1.15F) / RES);
				float depth = 0.012F + 0.035F * Math.min(1.0F, v);
				int c;
				if (gloss) {
					facePoint(q, s, bx, top, bz, (a0 + a1) * 0.5F, 0.0F, 0.003F);
					c = (int) (Mth.clamp(smooth(0.05F, 0.6F, v) * fade * de.rcm.ballistic.client.render.WetShine.at(probe.set(q[0], q[1], q[2]), normal, light), 0.0F, 1.0F) * 190.0F) << 24
						| 0xFFFFFF;
				} else {
					c = colour(0.6F + Math.min(1.0F, v), Math.min(1.0F, v), fade, Mth.clamp((now - p.fed) / 2400.0F, 0.0F, 1.0F));
				}
				if (c >>> 24 == 0) {
					continue;
				}
				wallVertex(pose, consumer, q, s, bx, top, bz, a0, 0.0F, 0.003F, c, light, normal);
				wallVertex(pose, consumer, q, s, bx, top, bz, a1, 0.0F, 0.003F, c, light, normal);
				wallVertex(pose, consumer, q, s, bx, top, bz, a1, depth, 0.003F, c & 0xFFFFFF, light, normal);
				wallVertex(pose, consumer, q, s, bx, top, bz, a0, depth, 0.003F, c & 0xFFFFFF, light, normal);
			}
			for (int i = 0; i < rs.length; i++) {
				ribbon(pose, consumer, p, s, i, rs[i], bx, top, bz, now, partial, fade, light, normal, q, probe, gloss);
			}
		}
	}

	private static void wallVertex(PoseStack.Pose pose, VertexConsumer consumer, float[] q, int s, float bx, float top, float bz, float a, float y, float out,
		int colour, int light, Vector3f n) {
		facePoint(q, s, bx, top, bz, a, y, out);
		consumer.addVertex(pose, q[0], q[1], q[2]).setColor(colour).setUv(a * 0.6F + s * 0.25F, y * 0.22F).setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(light).setNormal(pose, n.x, n.y, n.z);
	}

	/**
	 * One run down the wall, as a soft ribbon: at the top a funnel as wide as the stretch of edge pouring into it
	 * (a sheet for a heavy pour, breaking up lower down), then a thin winding line of uneven width, darker and
	 * thicker down its middle, thin and clear red at its edges, and at its front a round bead of blood (or, at the
	 * end over an overhang, a drop hanging, growing until it falls).
	 */
	private static void ribbon(PoseStack.Pose pose, VertexConsumer consumer, Patch p, int s, int index, Run r, float bx, float top, float bz, long now,
		float partial, float fade, int light, Vector3f normal, float[] q, Vector3f probe, boolean gloss) {
		float front = Mth.lerp(partial, r.prevFront, r.front);
		if (front < 0.003F || r.stain < 0.03F && r.stream <= 0.0F) {
			return;
		}
		float wall = p.wall[s];
		boolean arrived = r.front >= wall - 1.0E-4F;
		float hw = halfWidth(r);
		// the bead at its front; at the end over an overhang, the drop gathering there
		boolean bead = !arrived || p.side[s] == LEDGE;
		float rb = hw * 1.6F + 0.003F;
		float headAt = front;
		if (arrived && p.side[s] == LEDGE) {
			rb = Math.max(rb * 0.8F, 0.008F + 0.03F * (float) Math.sqrt(Math.min(1.0F, r.hang / DROP)));
			headAt = wall + rb * 0.55F;
		}
		float end = bead ? headAt + rb : front;
		// the funnel at the top, from the cells pouring into it
		float lo = 2.0F;
		float hi = -1.0F;
		for (int e = 0; e < RES; e++) {
			if (p.catchment[s][e] == index && p.lip[s * RES + e] > 0.02F) {
				lo = Math.min(lo, e / (float) RES);
				hi = Math.max(hi, (e + 1) / (float) RES);
			}
		}
		float topC = hi > lo ? (lo + hi) * 0.5F : r.x;
		float topW = hi > lo ? Math.max(hw, (hi - lo) * 0.42F) : hw * 1.4F;
		float funnel = 0.05F + 0.28F * Math.min(1.0F, r.rate / 0.5F) + 0.05F * Math.min(1.0F, r.peak);
		// how it looks: wet and bright while it runs, draining to a dark film, drying brown over minutes
		float dried = Mth.clamp((now - r.fed) / 4800.0F, 0.0F, 1.0F);
		float age = Mth.clamp((now - r.fed) / 2400.0F, 0.0F, 1.0F);
		float base = 0.3F + 0.3F * (1.0F - dried) + r.wet * (0.5F + 0.6F * Math.min(1.0F, r.rate * 2.0F));
		float stain = r.stain;
		// the rows down it
		int n = 0;
		float[] ys = new float[160];
		float y = 0.0F;
		while (n < ys.length) {
			ys[n++] = y;
			if (y >= end) {
				break;
			}
			float step = bead && y > headAt - 2.5F * rb ? rb * 0.3F : y < funnel ? 0.022F : 0.045F;
			y = Math.min(end, y + Math.max(0.004F, step));
		}
		float pc = 0.0F;
		float pw = 0.0F;
		int pe = 0;
		int pm = 0;
		float pyy = 0.0F;
		for (int k = 0; k < n; k++) {
			float yy = ys[k];
			float fz = 1.0F - smooth(0.0F, funnel, yy);
			float body = hw * (0.75F + 0.5F * wave(r.seed + 5, yy * 7.0F));
			float h = base;
			if (bead) {
				// thinner just above the bead, the bead round below
				body *= 1.0F - 0.3F * smooth(headAt - 4.0F * rb, headAt - 1.2F * rb, yy);
				float u = yy - headAt;
				if (u > -1.4F * rb) {
					float t = u < 0.0F ? u / (1.4F * rb) : u / rb;
					float b = rb * (float) Math.sqrt(Math.max(0.0F, 1.0F - t * t));
					body = u >= 0.0F ? b : Math.max(body, b);
					h += 1.1F * (b / rb);
				}
			}
			if (!bead) {
				// spreading a little at the foot, where it runs into the blood on the ground
				body *= 1.0F + 0.9F * smooth(wall - 0.06F, wall, yy);
			}
			float w = Mth.lerp(fz, body, topW);
			float c = Mth.lerp(fz, r.x + meander(r, Math.min(yy, wall)), topC);
			c = Mth.clamp(c, Math.min(0.5F, w + 0.005F), Math.max(0.5F, 0.995F - w));
			int mid;
			int edge;
			if (gloss) {
				facePoint(q, s, bx, top, bz, c, yy, 0.004F);
				float shine = smooth(0.4F, 1.8F, h) * fade * de.rcm.ballistic.client.render.WetShine.at(probe.set(q[0], q[1], q[2]), normal, light);
				mid = (int) (Mth.clamp(shine, 0.0F, 1.0F) * 200.0F) << 24 | 0xFFFFFF;
				edge = 0x00FFFFFF;
			} else {
				mid = colour(h, stain, fade, age);
				edge = thin(colour(Math.max(0.3F, h * 0.45F), stain * 0.5F, fade, age), 0.55F);
			}
			if (k > 0) {
				float[] as = {pc - pw, pc - pw * 0.45F, pc + pw * 0.45F, pc + pw};
				float[] bs = {c - w, c - w * 0.45F, c + w * 0.45F, c + w};
				int[] ac = {pe, pm, pm, pe};
				int[] bc = {edge, mid, mid, edge};
				for (int lane = 0; lane < 3; lane++) {
					wallVertex(pose, consumer, q, s, bx, top, bz, as[lane], pyy, 0.004F, ac[lane], light, normal);
					wallVertex(pose, consumer, q, s, bx, top, bz, as[lane + 1], pyy, 0.004F, ac[lane + 1], light, normal);
					wallVertex(pose, consumer, q, s, bx, top, bz, bs[lane + 1], yy, 0.004F, bc[lane + 1], light, normal);
					wallVertex(pose, consumer, q, s, bx, top, bz, bs[lane], yy, 0.004F, bc[lane], light, normal);
				}
			}
			pc = c;
			pw = w;
			pe = edge;
			pm = mid;
			pyy = yy;
		}
	}
}
