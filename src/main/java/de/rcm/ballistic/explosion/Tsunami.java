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
 * The radioactive tsunami of a Poseidon burst. Not one wave but a train of them: a ring-shaped wave runs
 * out from ground zero over the sea (the clients draw it as a moving wall of water), steepening as the
 * sea shallows; where it reaches land it rears up and floods inland as a bore, tearing out weak
 * buildings, trees and anything standing in the open, until the slope and friction of the ground have
 * used up its height. Hills and cliffs stop it. Behind it the water level falls again and the backwash
 * drags what is in it out to sea - and then the next, smaller wave comes in.
 * <p>
 * Heights are tracked per compass direction, so a headland shadows the coast behind it.
 */
public final class Tsunami {
	/** How fast the wave front runs, blocks per tick. */
	public static final double SPEED = 1.3;
	/** Where the wave forms (the crater and the water column are inside this). */
	public static final double START_RADIUS = 30.0;
	public static final double MAX_RADIUS = 400.0;
	/** The train of waves: each one's height against the first, and how many ticks after the burst it sets out. */
	public static final float[] AMPLITUDE = {1.0F, 0.68F, 0.42F};
	public static final int[] DELAY = {0, 160, 330};
	/** How much a wave rears up where the sea runs shallow against the coast. */
	public static final float SHOAL = 1.45F;
	private static final int BINS = 1440;
	private static final int MAX_PLACEMENTS_PER_TICK = 9000;
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
	private final LongOpenHashSet wet = new LongOpenHashSet();
	private double radius = START_RADIUS;
	private int age;
	private final float amplitude;
	private final int delay;
	private int waited;

	/** @param wave which of the train it is (0 the first, biggest) */
	public Tsunami(ServerLevel level, Vec3 pos, int sea, int wave) {
		this.amplitude = AMPLITUDE[wave];
		this.delay = DELAY[wave];
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
		return Math.max(4.0, 24.0 * Math.sqrt(START_RADIUS / Math.max(START_RADIUS, r)));
	}

	/** @return true when the wave has run its course and the water has drained */
	public boolean tick() {
		if (this.waited < this.delay) {
			this.waited++; // the later waves of the train are still out at sea
			return false;
		}
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
		if (this.age % 5 == 0) {
			this.backwash();
		}
		return this.radius >= MAX_RADIUS && this.drained >= this.flooded.size();
	}

	private void advance(double from, double to) {
		double h = height(to) * this.amplitude;
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
		// running up the land: reared up off the shallows, its height then drains away with every block of
		// slope and of distance
		float left = (float) Math.min(waveHeight * SHOAL, this.energy[b]);
		if (ground > this.sea + Math.round(left)) {
			this.energy[b] = 0.0F; // a hill or a cliff stops it here
			return 0;
		}
		// the bore over the land is not the full height of the wave deep: a few blocks of racing water
		int top = Math.min(this.sea + Math.round(left), ground + Math.round(2.0F + left * 0.45F));
		this.energy[b] = left - 0.14F - Math.max(0, ground - this.sea - 1) * 0.05F;
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
				count += this.flood(pos, state, top - y);
			}
		}
		for (int y = ground; y <= top; y++) {
			pos.set(x, y, z);
			BlockState state = this.level.getBlockState(pos);
			if (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()) {
				count += this.flood(pos, state, top - y);
			} else if (weak(state) && this.random.nextFloat() < force) {
				count += this.flood(pos, state, top - y);
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

	/** @param below how far under the surface of the flood this block is: the water level falls from the top down */
	private int flood(BlockPos.MutableBlockPos pos, BlockState old, int below) {
		if (old.getDestroySpeed(this.level, pos) < 0.0F) {
			return 0;
		}
		if (!old.isAir() && !old.canBeReplaced()) {
			this.level.levelEvent(2001, pos.immutable(), Block.getId(old)); // splinters
		}
		CraterLog.set(this.level, pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
		this.flooded.add(pos.asLong());
		this.wet.add(pos.asLong());
		// it stands a while, then the level drops, a block every few ticks
		this.drainAt.add(this.age + 50L + below * 4L + this.random.nextInt(8));
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
			this.wet.remove(pos.asLong());
			BlockState state = this.level.getBlockState(pos);
			if (state.is(Blocks.WATER)) {
				CraterLog.set(this.level, pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	/** Once the wave has passed, what is caught in the flood is dragged back out to sea with it. */
	private void backwash() {
		if (this.wet.isEmpty() || this.drained == 0) {
			return;
		}
		double r = this.radius;
		AABB box = new AABB(this.cx - r, this.sea - 2, this.cz - r, this.cx + r, this.sea + 40, this.cz + r);
		for (Entity e : this.level.getEntities((Entity) null, box, e -> e instanceof LivingEntity || e instanceof AbstractBoat
			|| e instanceof net.minecraft.world.entity.item.ItemEntity)) {
			if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
				continue;
			}
			if (!this.wet.contains(e.blockPosition().asLong())) {
				continue;
			}
			double dx = this.cx - e.getX();
			double dz = this.cz - e.getZ();
			double d = Math.sqrt(dx * dx + dz * dz);
			if (d < 1.0) {
				continue;
			}
			e.push(dx / d * 0.35, 0.0, dz / d * 0.35);
			e.hurtMarked = true;
		}
	}

	/** People, animals and boats caught by the front are swept inland and battered. */
	private void sweepEntities(double from, double to) {
		double h = height(to) * this.amplitude;
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
			double reach = ground <= this.sea ? h : Math.min(h * SHOAL, this.energy[b]);
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
