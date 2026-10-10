package de.rcm.ballistic.explosion;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The radioactive tsunami of a Poseidon burst. A ring-shaped wave runs out from ground zero over the
 * sea; where it reaches land it rears up and floods inland as a wall of water, tearing out weak
 * buildings, trees and anything standing in the open, until the slope and friction of the ground
 * have used up its height. Hills and cliffs stop it. A little later the water drains back to the sea.
 * <p>
 * Heights are tracked per compass direction, so a headland shadows the coast behind it.
 */
public final class Tsunami {
	/** How fast the wave front runs, blocks per tick. */
	public static final double SPEED = 1.15;
	/** Where the wave forms (the crater and the water column are inside this). */
	public static final double START_RADIUS = 30.0;
	public static final double MAX_RADIUS = 230.0;
	private static final int BINS = 1440;
	private static final int MAX_PLACEMENTS_PER_TICK = 5000;
	private static final int MAX_REMOVALS_PER_TICK = 6000;

	private final ServerLevel level;
	private final double cx;
	private final double cz;
	/** Y of the top water block of the undisturbed sea. */
	private final int sea;
	private final RandomSource random;
	/** Height the wave still has, per direction, once it is running up the land. */
	private final float[] energy = new float[BINS];
	private final LongOpenHashSet visited = new LongOpenHashSet();
	/** Flooded blocks and the tick they drain. */
	private final LongArrayList flooded = new LongArrayList();
	private final LongArrayList drainAt = new LongArrayList();
	private int drained;
	private double radius = START_RADIUS;
	private int age;

	public Tsunami(ServerLevel level, Vec3 pos, int sea) {
		this.level = level;
		this.cx = pos.x;
		this.cz = pos.z;
		this.sea = sea;
		this.random = level.getRandom();
		java.util.Arrays.fill(this.energy, Float.MAX_VALUE);
	}

	public ServerLevel level() {
		return this.level;
	}

	/** Height of the wave above the sea at distance {@code r}: it spreads its energy on an ever longer front. */
	public static double height(double r) {
		return Math.max(3.0, 17.0 * Math.sqrt(START_RADIUS / Math.max(START_RADIUS, r)));
	}

	/** @return true when the wave has run its course and the water has drained */
	public boolean tick() {
		this.age++;
		if (this.radius < MAX_RADIUS) {
			double from = this.radius;
			this.radius = Math.min(MAX_RADIUS, this.radius + SPEED);
			if (this.age % 10 == 1) {
				this.level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(BlockPos.containing(this.cx, this.sea, this.cz)),
					Mth.ceil(this.radius / 16.0) + 1);
			}
			this.advance(from, this.radius);
			this.sweepEntities(from, this.radius);
		}
		this.drain();
		return this.radius >= MAX_RADIUS && this.drained >= this.flooded.size();
	}

	private void advance(double from, double to) {
		double h = height(to);
		int placed = 0;
		for (int b = 0; b < BINS; b++) {
			if (this.energy[b] <= 0.0F) {
				continue;
			}
			double a = (b + 0.5) * Mth.TWO_PI / BINS;
			double cos = Math.cos(a);
			double sin = Math.sin(a);
			for (double r = from; r < to; r += 0.6) {
				int x = Mth.floor(this.cx + cos * r);
				int z = Mth.floor(this.cz + sin * r);
				if (!this.visited.add(BlockPos.asLong(x, 0, z))) {
					continue;
				}
				placed += this.column(x, z, b, h, placed >= MAX_PLACEMENTS_PER_TICK);
				if (this.energy[b] <= 0.0F) {
					break;
				}
			}
		}
	}

	/** The wave reaching column (x, z) from direction bin {@code b}; returns the number of blocks flooded. */
	private int column(int x, int z, int b, double waveHeight, boolean saturated) {
		if (!this.level.hasChunk(x >> 4, z >> 4)) {
			return 0;
		}
		int ground = this.level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z); // first free block above the solid ground
		if (ground <= this.sea) {
			return 0; // open water: the wave just passes through
		}
		// running up the land: its height drains away with every block of slope and of distance
		float left = (float) Math.min(waveHeight, this.energy[b]);
		int top = this.sea + Math.round(left);
		if (ground > top) {
			this.energy[b] = 0.0F; // a hill or a cliff stops it here
			return 0;
		}
		this.energy[b] = left - 0.18F - Math.max(0, ground - this.sea - 1) * 0.06F;
		if (saturated) {
			return 0;
		}
		float force = Mth.clamp(left / 8.0F, 0.2F, 1.0F);
		int count = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// buildings and trees in its path: the walls are battered in at the water line
		for (int y = ground - 1; y > ground - 8 && y > this.sea; y--) {
			pos.set(x, y, z);
			BlockState state = this.level.getBlockState(pos);
			if (weak(state) && this.random.nextFloat() < force * 0.55F) {
				count += this.flood(pos, state);
			}
		}
		for (int y = ground; y <= top; y++) {
			pos.set(x, y, z);
			BlockState state = this.level.getBlockState(pos);
			if (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()) {
				count += this.flood(pos, state);
			} else if (weak(state) && this.random.nextFloat() < force) {
				count += this.flood(pos, state);
			}
		}
		return count;
	}

	/** What the water tears away: wood, glass, leaves, wool, plants - not earth and rock. */
	private static boolean weak(BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity()) {
			return false;
		}
		if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY)) {
			return false;
		}
		return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_STAIRS)
			|| state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.WOODEN_DOORS)
			|| state.is(BlockTags.WOOL) || state.is(BlockTags.IMPERMEABLE) || state.is(Blocks.GLASS_PANE) || state.is(BlockTags.FLOWERS)
			|| state.is(BlockTags.CROPS) || state.is(BlockTags.SAPLINGS) || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.SNOW_BLOCK)
			|| state.getBlock().getExplosionResistance() < 1.0F;
	}

	private int flood(BlockPos.MutableBlockPos pos, BlockState old) {
		if (old.getDestroySpeed(this.level, pos) < 0.0F) {
			return 0;
		}
		if (!old.isAir() && !old.canBeReplaced()) {
			this.level.levelEvent(2001, pos.immutable(), Block.getId(old)); // splinters
		}
		this.level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
		this.flooded.add(pos.asLong());
		this.drainAt.add(this.age + 30L + this.random.nextInt(25));
		return 1;
	}

	/** Behind the crest the water runs back off the land. */
	private void drain() {
		int n = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (this.drained < this.flooded.size() && n < MAX_REMOVALS_PER_TICK) {
			if (this.drainAt.getLong(this.drained) > this.age) {
				break;
			}
			pos.set(this.flooded.getLong(this.drained));
			this.drained++;
			n++;
			BlockState state = this.level.getBlockState(pos);
			if (state.is(Blocks.WATER)) {
				this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	/** People, animals and boats caught by the front are swept inland and battered. */
	private void sweepEntities(double from, double to) {
		double h = height(to);
		AABB box = new AABB(this.cx - to - 2, this.sea - 4, this.cz - to - 2, this.cx + to + 2, this.sea + h + 3, this.cz + to + 2);
		for (Entity e : this.level.getEntities((Entity) null, box, e -> e instanceof LivingEntity || e instanceof AbstractBoat
			|| e instanceof net.minecraft.world.entity.item.ItemEntity)) {
			double dx = e.getX() - this.cx;
			double dz = e.getZ() - this.cz;
			double d = Math.sqrt(dx * dx + dz * dz);
			if (d < from - 4.0 || d > to + 1.0 || d < 1.0e-3) {
				continue;
			}
			if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
				continue;
			}
			int b = Mth.floor(Math.atan2(dz, dx) / Mth.TWO_PI * BINS);
			b = Math.floorMod(b, BINS);
			int ground = this.level.getHeight(Heightmap.Types.OCEAN_FLOOR, Mth.floor(e.getX()), Mth.floor(e.getZ()));
			double reach = ground <= this.sea ? h : Math.min(h, this.energy[b]);
			if (e.getY() > this.sea + reach + 1.0 || reach <= 0.5) {
				continue;
			}
			double f = Mth.clamp(reach / 10.0, 0.25, 1.0);
			e.push(dx / d * 1.3 * f, 0.25 * f, dz / d * 1.3 * f);
			e.hurtMarked = true;
			if (e instanceof LivingEntity living && this.age % 4 == 0) {
				living.hurtServer(this.level, this.level.damageSources().drown(), (float) (3.0 + 7.0 * f));
			}
		}
	}
}
