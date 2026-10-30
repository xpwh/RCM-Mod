package de.rcm.ballistic.explosion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Nature taking it all back. Where something big went off the ground stays scorched for a day or so (a few days
 * after a nuclear burst); then, slowly, over the following days, it comes back: the ash and the fallout wash
 * away, the burnt earth turns to soil and then to grass, short grass and ferns and poppies come up between the
 * craters, moss creeps over the concrete and the rubble, vines climb the walls of the ruins, and late on a
 * sapling or two takes root in what used to be a street.
 * <p>
 * Only in loaded chunks, a little at a time; the zones are saved with the world.
 */
public final class Regrowth {
	private static final String FILE = "ballisticmissiles_regrowth.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int DAY = 24000;
	/** How long the regrowth takes once it has begun, ticks. */
	private static final int SPAN = 5 * DAY;
	/** How long a zone is kept after it began: by then it is green. */
	private static final int KEEP = 14 * DAY;
	private static final int MAX_ZONES = 256;

	/** A place that was laid waste at {@code start}; the green starts coming back {@code delay} ticks later. */
	private record Zone(ResourceKey<Level> dimension, double x, double z, double radius, long start, int delay) {
		/** How far along the regrowth is, 0-1. */
		double progress(long now) {
			return Mth.clamp((now - this.start - this.delay) / (double) SPAN, 0.0, 1.0);
		}
	}

	private static final List<Zone> ZONES = new ArrayList<>();

	private static final Block[] FLOWERS = {
		Blocks.POPPY, Blocks.POPPY, Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.AZURE_BLUET,
		Blocks.ALLIUM, Blocks.RED_TULIP, Blocks.WHITE_TULIP
	};
	private static final Block[] SAPLINGS = {Blocks.OAK_SAPLING, Blocks.OAK_SAPLING, Blocks.BIRCH_SAPLING, Blocks.SPRUCE_SAPLING};

	private Regrowth() {
	}

	/**
	 * Remembers that {@code pos} was laid waste over {@code radius} blocks; a nuclear burst ({@code nuclear}) keeps the
	 * ground dead for longer.
	 */
	public static void mark(ServerLevel level, Vec3 pos, double radius, boolean nuclear) {
		// a second strike on the same ruins starts their clock again
		ZONES.removeIf(z -> z.dimension() == level.dimension() && Mth.square(z.x() - pos.x) + Mth.square(z.z() - pos.z) < Mth.square(radius)
			&& z.radius() <= radius);
		int delay = nuclear ? 3 * DAY : DAY;
		ZONES.add(new Zone(level.dimension(), pos.x, pos.z, radius, level.getGameTime(), delay + level.getRandom().nextInt(DAY / 4)));
		while (ZONES.size() > MAX_ZONES) {
			ZONES.removeFirst();
		}
	}

	public static void tick(ServerLevel level) {
		long now = level.getGameTime();
		if (ZONES.isEmpty() || now % 20 != 7) {
			return;
		}
		ZONES.removeIf(z -> now - z.start() > z.delay() + KEEP || now < z.start());
		RandomSource random = level.getRandom();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (Zone z : ZONES) {
			if (z.dimension() != level.dimension()) {
				continue;
			}
			double p = z.progress(now);
			if (p <= 0.0) {
				continue;
			}
			// about one try a second per 200 m², the bigger zones capped
			int tries = Math.min(160, Mth.ceil(Math.PI * z.radius() * z.radius() / 200.0 * (0.3 + 0.7 * p)));
			for (int i = 0; i < tries; i++) {
				double a = random.nextDouble() * Math.PI * 2.0;
				double r = z.radius() * Math.sqrt(random.nextDouble());
				int x = Mth.floor(z.x() + Math.cos(a) * r);
				int zz = Mth.floor(z.z() + Math.sin(a) * r);
				if (level.getChunkSource().getChunkNow(x >> 4, zz >> 4) == null) {
					continue;
				}
				// the edge of the zone comes back first, ground zero last
				double edge = r / z.radius();
				double local = Mth.clamp(p * 1.4 - (1.0 - edge) * 0.4, 0.0, 1.0);
				if (local <= 0.0) {
					continue;
				}
				grow(level, m.set(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, zz) - 1, zz), local, random);
			}
		}
	}

	/** One try at the column whose topmost block is at {@code top}. */
	private static void grow(ServerLevel level, BlockPos.MutableBlockPos top, double p, RandomSource random) {
		BlockState s = level.getBlockState(top);
		// a plant already there: look at what it grows on
		if (s.is(Blocks.SHORT_GRASS) && random.nextFloat() < 0.04F * p) {
			BlockPos below = top.below();
			if (level.getBlockState(top.above()).isAir()) {
				level.setBlock(below.above(), Blocks.TALL_GRASS.defaultBlockState(), Block.UPDATE_ALL);
				level.setBlock(below.above(2), Blocks.TALL_GRASS.defaultBlockState()
					.setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),
					Block.UPDATE_ALL);
			}
			return;
		}
		if (s.is(ModRegistry.ASH) || s.is(ModRegistry.FALLOUT)) {
			// rain washes the dust away (the fallout takes its time)
			if (random.nextFloat() < (s.is(ModRegistry.FALLOUT) ? 0.25F : 0.6F)) {
				level.setBlock(top, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
			return;
		}
		if (s.is(Blocks.MOSS_CARPET)) {
			BlockPos below = top.below();
			BlockState under = level.getBlockState(below);
			if (random.nextFloat() < 0.12F * p && softRubble(under)) {
				level.setBlock(top, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				level.setBlock(below, Blocks.MOSS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			}
			return;
		}
		if (!level.getBlockState(top.above()).isAir()) {
			return;
		}
		vines(level, top, p, random);
		if (s.is(ModRegistry.SCORCHED_EARTH) || s.is(ModRegistry.SMOLDERING_EARTH) || s.is(ModRegistry.CHARRED_LOG)) {
			if (random.nextFloat() < 0.5F) {
				level.setBlock(top, (random.nextFloat() < 0.3F ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState(), Block.UPDATE_ALL);
			}
		} else if (s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT) || s.is(Blocks.ROOTED_DIRT)) {
			if (random.nextFloat() < 0.35F + 0.4F * p) {
				level.setBlock(top, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			}
		} else if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.MOSS_BLOCK)) {
			plant(level, top.above(), p, random);
		} else if (hard(level, top, s)) {
			float f = random.nextFloat();
			if (f < 0.10F * p && softRubble(s)) {
				level.setBlock(top, Blocks.MOSS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			} else if (f < 0.35F * p) {
				level.setBlock(top.above(), Blocks.MOSS_CARPET.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	/** Something coming up from grass or moss at {@code pos}. */
	private static void plant(ServerLevel level, BlockPos pos, double p, RandomSource random) {
		float f = random.nextFloat();
		BlockState plant;
		if (p > 0.6 && f < 0.012F) {
			plant = SAPLINGS[random.nextInt(SAPLINGS.length)].defaultBlockState();
		} else if (f < 0.22F) {
			plant = FLOWERS[random.nextInt(FLOWERS.length)].defaultBlockState();
		} else if (f < 0.32F) {
			plant = Blocks.FERN.defaultBlockState();
		} else if (f < 0.4F + 0.5F * p) {
			plant = Blocks.SHORT_GRASS.defaultBlockState();
		} else {
			return;
		}
		if (plant.canSurvive(level, pos)) {
			level.setBlock(pos, plant, Block.UPDATE_ALL);
		}
	}

	/**
	 * Vines on the walls of the ruins: a wall beside the column, a vine set on it somewhere up its face (vanilla
	 * lets it spread from there).
	 */
	private static void vines(ServerLevel level, BlockPos top, double p, RandomSource random) {
		if (random.nextFloat() > 0.25F * p) {
			return;
		}
		Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
		BlockPos side = top.relative(dir);
		int wallTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, side.getX(), side.getZ());
		int rise = wallTop - top.getY() - 1;
		if (rise < 2) {
			return;
		}
		BlockPos at = top.above(1 + random.nextInt(Math.min(rise, 10)));
		BlockPos wall = at.relative(dir);
		if (!level.getBlockState(at).isAir() || !VineBlock.isAcceptableNeighbour(level, wall, dir)) {
			return;
		}
		level.setBlock(at, Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(dir), true), Block.UPDATE_ALL);
	}

	/** Hard ground moss can creep over: stone, rubble, concrete, asphalt, brick. */
	private static boolean hard(ServerLevel level, BlockPos pos, BlockState s) {
		if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(BlockTags.PLANKS) || s.is(Blocks.WATER) || s.is(Blocks.LAVA)
			|| s.is(Blocks.SAND) || s.is(Blocks.SNOW) || s.is(Blocks.ICE) || s.is(ModRegistry.CRATER_GLASS) || s.is(ModRegistry.MOLTEN_ROCK)
			|| s.is(ModRegistry.TRINITITE)) {
			return false;
		}
		return s.isCollisionShapeFullBlock(level, pos);
	}

	/** Loose, broken stuff that turns to moss itself (rather than just being covered). */
	private static boolean softRubble(BlockState s) {
		return s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.GRAVEL) || s.is(Blocks.DIRT)
			|| s.is(Blocks.COARSE_DIRT) || s.is(Blocks.STONE) || s.is(Blocks.ANDESITE) || s.is(Blocks.TUFF)
			|| s.is(ModRegistry.SCORCHED_EARTH);
	}

	// ------------------------------------------------------------------ persistence

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
	}

	public static void load(MinecraftServer server) {
		ZONES.clear();
		Path path = file(server);
		if (!Files.exists(path)) {
			return;
		}
		try {
			JsonObject root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
			for (var element : root.getAsJsonArray("zones")) {
				JsonObject o = element.getAsJsonObject();
				ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString()));
				ZONES.add(new Zone(dim, o.get("x").getAsDouble(), o.get("z").getAsDouble(), o.get("radius").getAsDouble(),
					o.get("start").getAsLong(), o.get("delay").getAsInt()));
			}
		} catch (IOException | RuntimeException e) {
			BallisticMissiles.LOGGER.warn("Could not read regrowth zones from {}", path, e);
		}
	}

	public static void save(MinecraftServer server) {
		JsonArray zones = new JsonArray();
		for (Zone z : ZONES) {
			JsonObject o = new JsonObject();
			o.addProperty("dimension", z.dimension().identifier().toString());
			o.addProperty("x", z.x());
			o.addProperty("z", z.z());
			o.addProperty("radius", z.radius());
			o.addProperty("start", z.start());
			o.addProperty("delay", z.delay());
			zones.add(o);
		}
		JsonObject root = new JsonObject();
		root.add("zones", zones);
		Path path = file(server);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BallisticMissiles.LOGGER.warn("Could not save regrowth zones to {}", path, e);
		}
	}

	public static void clear() {
		ZONES.clear();
	}
}
