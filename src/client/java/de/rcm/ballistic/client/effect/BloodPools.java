package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.BallisticMissiles;
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
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

/**
 * Blood that behaves like a liquid. What lands on the ground - drops, the jets of a cut artery, what runs out of
 * a body - collects on the top of the block it falls on. A block holds a thin film; more than that runs on: out
 * over the flat to the next blocks, which fill in turn, down a step (leaving a run down its face) and over an
 * edge, where it drips - drop by drop, the drops falling and pooling again below. Earth, grass and sand soak it up
 * faster than stone. Fresh it is bright and wet; it dries dark over minutes and stays a while as a brown stain.
 */
public final class BloodPools {
	/** The pools: tiles that join up with their neighbours (tools/gen_blood_pool.py), and their wet shine. */
	private static final RenderType TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/effect/blood_pool.png"));
	private static final RenderType GLOSS = RenderTypes.eyes(BallisticMissiles.id("textures/effect/blood_pool_gloss.png"));
	/** The runs down a step, from the stains' atlas. */
	private static final RenderType RUNS = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/effect/blood_ground.png"));
	private static final int GRID = 12;
	/** For each of the 256 neighbour masks: which of the 47 tile patterns (as gen_blood_pool.py numbers them). */
	private static final int[] PATTERN = patterns();
	/** The film a block's top holds before it runs on (in "pool" units: a body empties about 1.5). */
	private static final float HOLD = 0.3F;
	/** What one drop falling off an edge carries. */
	private static final float DROP = 0.035F;
	private static final int LIFE = 7200;
	private static final int FADE = 900;
	private static final int MAX = 600;
	private static final RandomSource RANDOM = RandomSource.create();

	private static final class Cell {
		final BlockPos pos;
		final double top;
		final float ox;
		final float oz;
		final float angle;
		final int variant;
		final long born;
		final boolean soaks;
		float vol;
		float shown;
		long fed;
		float drip;
		long lastDripSound;
		/** Faces (bit per horizontal direction) the blood has run down over a step. */
		int runs;
		/** Sides where there is no same-level ground: the pool stays on its own block there. */
		int open = -1;

		Cell(BlockPos pos, double top, long now, boolean soaks) {
			this.pos = pos;
			this.top = top;
			this.ox = RANDOM.nextFloat() * 2.0F - 1.0F;
			this.oz = RANDOM.nextFloat() * 2.0F - 1.0F;
			this.angle = RANDOM.nextFloat() * Mth.TWO_PI;
			this.variant = RANDOM.nextInt(4);
			this.born = now;
			this.fed = now;
			this.soaks = soaks;
		}
	}

	private static final Map<Long, Cell> CELLS = new LinkedHashMap<>();
	private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

	private BloodPools() {
	}

	/** A corner counts only when both its sides are wet; the patterns numbered in the order the masks first meet them. */
	private static int canonical(int mask) {
		int m = mask & 15;
		int[][] corners = {{16, 1, 2}, {32, 4, 2}, {64, 4, 8}, {128, 1, 8}};
		for (int[] c : corners) {
			if ((mask & c[0]) != 0 && (m & c[1]) != 0 && (m & c[2]) != 0) {
				m |= c[0];
			}
		}
		return m;
	}

	private static int[] patterns() {
		int[] out = new int[256];
		List<Integer> seen = new ArrayList<>();
		for (int m = 0; m < 256; m++) {
			int c = canonical(m);
			int i = seen.indexOf(c);
			if (i < 0) {
				seen.add(c);
				i = seen.size() - 1;
			}
			out[m] = i;
		}
		return out;
	}

	/** Whether the block top at pos (as high as {@code top}) has blood on it to join up with. */
	private static boolean wet(BlockPos pos, double top) {
		Cell c = CELLS.get(pos.asLong());
		return c != null && c.shown > 0.12F && Math.abs(c.top - top) < 0.05;
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(BloodPools::tick);
		WorldRenderEvents.BEFORE_ENTITIES.register(BloodPools::render);
	}

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
		return pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
	}

	private static boolean passable(ClientLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return s.getCollisionShape(level, pos).isEmpty() && s.getFluidState().isEmpty();
	}

	/** {@code vol} of blood landing at {@code at}: onto the block below, if blood can lie there. */
	public static void add(ClientLevel level, Vec3 at, float vol) {
		BlockPos pos = BlockPos.containing(at.x, at.y - 0.03, at.z);
		double top = topOf(level, pos);
		if (Double.isNaN(top)) {
			pos = pos.below();
			top = topOf(level, pos);
			if (Double.isNaN(top)) {
				return;
			}
		}
		feed(level, pos, top, vol);
	}

	private static Cell feed(ClientLevel level, BlockPos pos, double top, float vol) {
		long now = level.getGameTime();
		Cell c = CELLS.get(pos.asLong());
		if (c == null) {
			if (CELLS.size() >= MAX) {
				Iterator<Long> it = CELLS.keySet().iterator();
				it.next();
				it.remove();
			}
			BlockState s = level.getBlockState(pos);
			boolean soaks = s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.WOOL) || s.is(BlockTags.LEAVES);
			c = new Cell(pos.immutable(), top, now, soaks);
			CELLS.put(pos.asLong(), c);
		}
		c.vol += vol;
		c.fed = now;
		return c;
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) {
			CELLS.clear();
			return;
		}
		if (mc.isPaused() || CELLS.isEmpty()) {
			return;
		}
		long now = level.getGameTime();
		boolean flow = (now & 1) == 0;
		List<Cell> cells = new ArrayList<>(CELLS.values());
		for (Cell c : cells) {
			// soaking in and drying out
			c.vol *= c.soaks ? 0.9988F : 0.9996F;
			if (c.open < 0 || (now + c.pos.hashCode()) % 40 == 0) {
				if (Double.isNaN(topOf(level, c.pos)) || !passable(level, c.pos.above())) {
					c.vol = 0.0F;
					c.shown = 0.0F;
					continue;
				}
				c.open = 0;
				for (int i = 0; i < 4; i++) {
					double t = topOf(level, c.pos.relative(SIDES[i]));
					if (Double.isNaN(t) || Math.abs(t - c.top) > 0.05 || !passable(level, c.pos.relative(SIDES[i]).above())) {
						c.open |= 1 << i;
					}
				}
			}
			if (flow && c.vol > HOLD) {
				float excess = c.vol - HOLD;
				int start = RANDOM.nextInt(4);
				for (int k = 0; k < 4 && c.vol > HOLD; k++) {
					Direction d = SIDES[(start + k) & 3];
					BlockPos n = c.pos.relative(d);
					double t = topOf(level, n);
					if (!Double.isNaN(t) && Math.abs(t - c.top) < 0.05 && passable(level, n.above())) {
						// out over the flat: into a neighbour holding less
						Cell o = CELLS.get(n.asLong());
						float there = o == null ? 0.0F : o.vol;
						if (there < c.vol) {
							float move = Math.min(excess * 0.22F, (c.vol - there) * 0.3F);
							c.vol -= move;
							feed(level, n, t, move);
						}
					} else if (Double.isNaN(t) && passable(level, n) && passable(level, n.above())) {
						BlockPos below = n.below();
						double tb = topOf(level, below);
						if (!Double.isNaN(tb) && c.top - tb > 0.5 && c.top - tb < 1.6) {
							// down a step, leaving a run down its face
							float move = excess * 0.3F;
							c.vol -= move;
							c.runs |= 1 << ((start + k) & 3);
							feed(level, below, tb, move);
						} else if (Double.isNaN(tb) || c.top - tb >= 1.6) {
							// over an edge: it drips
							c.drip += excess * 0.25F;
							while (c.drip > DROP) {
								c.drip -= DROP;
								c.vol -= DROP;
								double ex = c.pos.getX() + 0.5 + d.getStepX() * 0.52 + (RANDOM.nextDouble() - 0.5) * 0.5 * Math.abs(d.getStepZ());
								double ez = c.pos.getZ() + 0.5 + d.getStepZ() * 0.52 + (RANDOM.nextDouble() - 0.5) * 0.5 * Math.abs(d.getStepX());
								BloodClient.drop(new Vec3(ex, c.top - 0.04, ez), new Vec3(d.getStepX() * 0.01, -0.02, d.getStepZ() * 0.01), 0.04F + RANDOM.nextFloat() * 0.02F);
								if (now - c.lastDripSound > 12) {
									// heard close by: drop after drop hitting the ground below
									c.lastDripSound = now;
									level.playLocalSound(ex, c.top - 1.0, ez, de.rcm.ballistic.ModRegistry.GORE_DRIP, net.minecraft.sounds.SoundSource.NEUTRAL, 0.22F,
										0.85F + RANDOM.nextFloat() * 0.3F, false);
								}
							}
						}
					}
				}
			}
			float want = c.vol <= 0.004F ? 0.0F : Mth.clamp(Mth.sqrt(c.vol / HOLD) * 0.5F, 0.1F, 0.74F);
			c.shown += (want - c.shown) * (want > c.shown ? 0.08F : 0.004F);
		}
		CELLS.values().removeIf(c -> now - c.born > LIFE || c.vol <= 0.004F && c.shown < 0.04F && now - c.fed > 200);
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || CELLS.isEmpty()) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		long now = mc.level.getGameTime();
		context.commandQueue().submitCustomGeometry(context.matrices(), TYPE, (pose, consumer) -> {
			for (Cell c : CELLS.values()) {
				if (c.shown < 0.02F || c.pos.distToCenterSqr(cam) > 64.0 * 64.0) {
					continue;
				}
				pool(pose, consumer, c, cam, colour(c, now), LevelRenderer.getLightColor(mc.level, c.pos.above()));
			}
		});
		context.commandQueue().submitCustomGeometry(context.matrices(), RUNS, (pose, consumer) -> {
			for (Cell c : CELLS.values()) {
				if (c.runs != 0 && c.shown >= 0.02F && c.pos.distToCenterSqr(cam) < 48.0 * 48.0) {
					runs(pose, consumer, c, cam, colour(c, now), LevelRenderer.getLightColor(mc.level, c.pos.above()));
				}
			}
		});
		context.commandQueue().order(1).submitCustomGeometry(context.matrices(), GLOSS, (pose, consumer) -> {
			for (Cell c : CELLS.values()) {
				if (c.shown < 0.02F || c.pos.distToCenterSqr(cam) > 40.0 * 40.0) {
					continue;
				}
				float wet = 1.0F - Mth.clamp((now - c.fed) / 2600.0F, 0.0F, 1.0F);
				if (wet < 0.02F) {
					continue;
				}
				int light = LevelRenderer.getLightColor(mc.level, c.pos.above());
				Vector3f p = new Vector3f((float) (c.pos.getX() + 0.5 - cam.x), (float) (c.top - cam.y), (float) (c.pos.getZ() + 0.5 - cam.z));
				int a = (int) (200.0F * wet * de.rcm.ballistic.client.render.WetShine.at(p, new Vector3f(0, 1, 0), light));
				pool(pose, consumer, c, cam, a << 24 | 0xFFFFFF, light);
			}
		});
	}

	/** The pool's colour: dark and fresh while it is fed, drying darker and browner over minutes, fading at the end. */
	private static int colour(Cell c, long now) {
		float alpha = Mth.clamp((LIFE - (now - c.born)) / (float) FADE, 0.0F, 1.0F) * Mth.clamp(c.shown / 0.25F, 0.0F, 1.0F);
		float age = Mth.clamp((now - c.fed) / 3000.0F, 0.0F, 1.0F);
		age = age * age * (3.0F - 2.0F * age);
		int r = (int) (255 * (1.0F - 0.5F * age));
		int g = (int) (255 * (1.0F - 0.35F * age));
		int b = (int) (255 * (1.0F - 0.45F * age));
		return (int) (alpha * 255.0F) << 24 | r << 16 | g << 8 | b;
	}

	/**
	 * The blood on a block's top: the whole top, with the tile that matches which neighbours are wet too (so a big
	 * pool is one sheet) and how much stands here.
	 */
	private static void pool(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, Cell c, Vec3 cam, int colour,
		int light) {
		BlockPos p = c.pos;
		int mask = 0;
		BlockPos[] around = {p.north(), p.east(), p.south(), p.west(), p.north().east(), p.south().east(), p.south().west(), p.north().west()};
		for (int i = 0; i < 8; i++) {
			if (wet(around[i], c.top)) {
				mask |= 1 << i;
			}
		}
		int level = c.vol < HOLD * 0.6F ? 0 : c.vol < HOLD * 1.5F ? 1 : 2;
		if (c.shown < 0.3F) {
			level = 0;
		}
		int tile = level * 47 + PATTERN[mask];
		float u0 = (tile % GRID) / (float) GRID;
		float v0 = (tile / GRID) / (float) GRID;
		float d = 1.0F / GRID;
		float x0 = (float) (p.getX() - cam.x);
		float z0 = (float) (p.getZ() - cam.z);
		float y = (float) (c.top + 0.004 - cam.y);
		float[][] q = {{0, 0, u0, v0}, {0, 1, u0, v0 + d}, {1, 1, u0 + d, v0 + d}, {1, 0, u0 + d, v0}};
		for (float[] k : q) {
			consumer.addVertex(pose, x0 + k[0], y, z0 + k[1]).setColor(colour).setUv(k[2], k[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, 0.0F, 1.0F, 0.0F);
		}
	}

	/** Where it ran down a step: a run down the face below the edge. */
	private static void runs(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer consumer, Cell c, Vec3 cam, int colour,
		int light) {
		if (c.runs == 0) {
			return;
		}
		for (int i = 0; i < 4; i++) {
			if ((c.runs & 1 << i) == 0) {
				continue;
			}
			Direction d = SIDES[i];
			// down our own block's side, where the ground steps down to the next block
			float fx = (float) (c.pos.getX() + 0.5 + d.getStepX() * 0.503 - cam.x);
			float fz = (float) (c.pos.getZ() + 0.5 + d.getStepZ() * 0.503 - cam.z);
			float top = (float) (c.top - cam.y);
			float w = 0.22F;
			float sx = Math.abs(d.getStepZ()) * w;
			float sz = Math.abs(d.getStepX()) * w;
			float tu = ((c.variant + i) & 3) * 0.25F;
			float[][] q = {{-1, 0.0F, tu, 0.75F}, {1, 0.0F, tu + 0.25F, 0.75F}, {1, -1.0F, tu + 0.25F, 1.0F}, {-1, -1.0F, tu, 1.0F}};
			for (float[] p : q) {
				consumer.addVertex(pose, fx + sx * p[0], top + p[1], fz + sz * p[0]).setColor(colour).setUv(p[2], p[3]).setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(light).setNormal(pose, d.getStepX(), 0.0F, d.getStepZ());
			}
		}
	}
}
