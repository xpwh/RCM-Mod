package de.rcm.ballistic.explosion;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.block.DustLayerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * What explosions leave in the ground: bowl-shaped craters with a raised rim of thrown-out earth,
 * and around them burnt land - scorched and smoldering earth, charred trees, ash, fires that keep
 * spreading from the embers, and after nuclear blasts sand fused into green trinitite glass.
 */
public final class Wasteland {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
	private static final int QUIET = FLAGS | Block.UPDATE_SUPPRESS_DROPS;
	/** Blast-rated construction (and anything tougher) is never reshaped. */
	private static final float HARD = 1200.0F;

	private Wasteland() {
	}

	// ------------------------------------------------------------------ craters

	/**
	 * Shapes a crater after the blast itself has torn the ground open: a smooth bowl about
	 * {@code radius} wide and {@code depth} deep (real craters are roughly four times wider than
	 * deep), lined with loose, partly burning debris, ringed by a rim of ejected earth that thins out
	 * over half a radius beyond the edge. Water, bunkers and containers are left alone.
	 */
	public static void crater(ServerLevel level, Vec3 pos, double radius, double depth, RandomSource random) {
		BlockPos center = BlockPos.containing(pos);
		if (radius < 1.5 || !level.getFluidState(center).isEmpty() || !level.getFluidState(center.above()).isEmpty()) {
			return;
		}
		int surfaceY = surface(level, center.getX(), center.getZ(), center.getY());
		int cy = Math.min(center.getY(), surfaceY);
		double phase1 = random.nextDouble() * Mth.TWO_PI;
		double phase2 = random.nextDouble() * Mth.TWO_PI;
		int reach = Mth.ceil(radius * 1.6);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				double angle = Math.atan2(dz, dx);
				double r = radius * (1.0 + 0.08 * Math.sin(angle * 3 + phase1) + 0.05 * Math.sin(angle * 5 + phase2));
				double d = Math.sqrt(dx * dx + dz * dz) + random.nextDouble() * 0.4;
				if (d < r) {
					bowl(level, x, z, cy, d / r, depth, random, m);
				} else if (d < r * 1.6) {
					rim(level, x, z, (d - r) / (r * 0.6), depth, random, m);
				}
			}
		}
	}

	private static void bowl(ServerLevel level, int x, int z, int cy, double rel, double depth, RandomSource random, BlockPos.MutableBlockPos m) {
		int floor = cy - (int) Math.round(depth * (1.0 - rel * rel) + random.nextDouble() * 0.6);
		floor = Math.max(floor, level.getMinY() + 1);
		int top = Math.max(floor, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1);
		top = Math.min(top, cy + 6);
		for (int y = top; y > floor; y--) {
			m.set(x, y, z);
			BlockState state = level.getBlockState(m);
			if (state.isAir() || !state.getFluidState().isEmpty()) {
				if (!state.getFluidState().isEmpty()) {
					return;
				}
				continue;
			}
			if (!editable(level, m, state)) {
				return;
			}
			level.setBlock(m, Blocks.AIR.defaultBlockState(), QUIET);
		}
		// fill pockets the blast dug below the bowl, so the floor is one smooth slope
		for (int y = floor; y > floor - 6; y--) {
			m.set(x, y, z);
			BlockState state = level.getBlockState(m);
			if (!state.isAir() && !state.canBeReplaced()) {
				break;
			}
			if (!state.getFluidState().isEmpty()) {
				return;
			}
			level.setBlock(m, loose(random, rel), FLAGS);
		}
		// line the floor: broken, burnt rock and earth, still glowing in the middle
		m.set(x, floor, z);
		BlockState floorState = level.getBlockState(m);
		if (editable(level, m, floorState) && !floorState.isAir()) {
			BlockState lining = rel < 0.45 && random.nextFloat() < 0.3F ? ModRegistry.SMOLDERING_EARTH.defaultBlockState() : loose(random, rel);
			level.setBlock(m, lining, FLAGS);
			m.set(x, floor + 1, z);
			if (rel < 0.7 && random.nextFloat() < 0.04F && level.getBlockState(m).isAir()) {
				level.setBlock(m, BaseFireBlock.getState(level, m), Block.UPDATE_ALL);
			}
		}
	}

	/** Loose crater fill: burnt earth, gravel and shattered rock. */
	private static BlockState loose(RandomSource random, double rel) {
		float roll = random.nextFloat();
		if (roll < 0.35F) {
			return ModRegistry.SCORCHED_EARTH.defaultBlockState();
		}
		if (roll < 0.6F) {
			return Blocks.COARSE_DIRT.defaultBlockState();
		}
		if (roll < 0.8F) {
			return Blocks.GRAVEL.defaultBlockState();
		}
		return rel < 0.5 ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
	}

	/** Ejecta: earth thrown over the lip, highest right at the edge and thinning outwards. */
	private static void rim(ServerLevel level, int x, int z, double t, double depth, RandomSource random, BlockPos.MutableBlockPos m) {
		double height = depth * 0.45 * Math.pow(1.0 - Mth.clamp(t, 0.0, 1.0), 1.6) + random.nextDouble() * 0.7 - 0.35;
		int layers = (int) Math.round(height);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		m.set(x, y - 1, z);
		BlockState ground = level.getBlockState(m);
		if (!ground.getFluidState().isEmpty()) {
			return;
		}
		for (int i = 0; i < layers; i++) {
			m.set(x, y + i, z);
			BlockState state = level.getBlockState(m);
			if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) {
				break;
			}
			level.setBlock(m, ejecta(random, ground), FLAGS);
		}
		if (layers <= 0 && random.nextFloat() < 0.5F) {
			scorchGround(level, m.set(x, y - 1, z), ground, 0.6, false, random);
		}
	}

	/** Thrown-out material: mostly what the ground there is made of, mixed with burnt earth and rock. */
	private static BlockState ejecta(RandomSource random, BlockState ground) {
		float roll = random.nextFloat();
		if (roll < 0.3F) {
			return ModRegistry.SCORCHED_EARTH.defaultBlockState();
		}
		if (roll < 0.55F) {
			if (ground.is(BlockTags.SAND)) {
				return ground;
			}
			return ground.is(BlockTags.BASE_STONE_OVERWORLD) ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
		}
		if (roll < 0.8F) {
			return Blocks.GRAVEL.defaultBlockState();
		}
		return Blocks.COARSE_DIRT.defaultBlockState();
	}

	// ------------------------------------------------------------------ burnt land

	/**
	 * Burns one column of land. {@code intensity} is 1 right at the fireball and 0 at the edge of the
	 * burnt zone: plants are gone, leaves burn off, trees are charred or knocked down, glass shatters,
	 * the ground is scorched (smoldering close in), ash settles, fires start. With {@code nuclear}
	 * the heat flash also fuses sand into trinitite near ground zero.
	 */
	public static void scorchColumn(ServerLevel level, int x, int z, double intensity, float fireChance, boolean nuclear, RandomSource random) {
		int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = top; y > top - 28 && y > level.getMinY(); y--) {
			pos.set(x, y, z);
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			if (!state.getFluidState().isEmpty()) {
				return; // water: the heat skims over it
			}
			if (!editable(level, pos, state)) {
				return;
			}
			if (state.is(BlockTags.LEAVES)) {
				if (random.nextDouble() < 0.5 + 0.5 * intensity) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET);
				} else if (random.nextDouble() < 0.15) {
					level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL);
				}
				continue;
			}
			if (state.is(BlockTags.LOGS)) {
				if (nuclear && random.nextDouble() < intensity * 0.7) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET); // blown down
				} else if (random.nextDouble() < 0.35 + 0.65 * intensity) {
					BlockState charred = ModRegistry.CHARRED_LOG.defaultBlockState();
					if (state.hasProperty(RotatedPillarBlock.AXIS)) {
						charred = charred.setValue(RotatedPillarBlock.AXIS, state.getValue(RotatedPillarBlock.AXIS));
					}
					level.setBlock(pos, charred, FLAGS);
					if (random.nextDouble() < 0.2 * intensity) {
						igniteNextTo(level, pos, random);
					}
				}
				continue;
			}
			if (state.is(BlockTags.PLANKS) || state.is(BlockTags.WOOL) || state.is(BlockTags.WOODEN_FENCES)) {
				if (random.nextDouble() < 0.25 * intensity) {
					igniteNextTo(level, pos, random); // buildings catch fire
				}
				if (nuclear && random.nextDouble() < intensity * 0.5) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET);
				}
				continue;
			}
			if (state.canBeReplaced() || state.is(BlockTags.FLOWERS) || state.is(Blocks.SNOW_BLOCK) || DustLayerBlock.isDust(state)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET);
				continue;
			}
			if (state.is(BlockTags.IMPERMEABLE) || state.getBlock() instanceof IronBarsBlock) {
				if (random.nextDouble() < 0.4 + 0.6 * intensity) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET); // shattered glass
				}
				continue;
			}
			// the first real ground block
			scorchGround(level, pos, state, intensity, nuclear, random);
			pos.set(x, y + 1, z);
			if (level.getBlockState(pos).isAir()) {
				if (random.nextDouble() < fireChance * (0.3 + 0.7 * intensity)) {
					level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL);
				} else if (random.nextDouble() < 0.12 + 0.2 * intensity) {
					level.setBlock(pos, ModRegistry.ASH.defaultBlockState(), FLAGS);
				}
			}
			return;
		}
	}

	private static void scorchGround(ServerLevel level, BlockPos pos, BlockState state, double intensity, boolean nuclear, RandomSource random) {
		if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.MOSS_BLOCK)
			|| state.is(Blocks.ROOTED_DIRT) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.COARSE_DIRT)) {
			if (random.nextDouble() < 0.45 + 0.55 * intensity) {
				boolean embers = random.nextDouble() < 0.08 + 0.3 * intensity;
				level.setBlock(pos, (embers ? ModRegistry.SMOLDERING_EARTH : ModRegistry.SCORCHED_EARTH).defaultBlockState(), FLAGS);
			}
		} else if (state.is(BlockTags.SAND)) {
			if (nuclear && random.nextDouble() < (intensity - 0.35) * 1.3) {
				level.setBlock(pos, ModRegistry.TRINITITE.defaultBlockState(), FLAGS);
			}
		} else if (nuclear && (state.is(Blocks.STONE) || state.is(Blocks.ANDESITE) || state.is(Blocks.GRANITE) || state.is(Blocks.DIORITE))
			&& random.nextDouble() < (intensity - 0.5) * 1.2) {
			level.setBlock(pos, ModRegistry.CRATER_GLASS.defaultBlockState(), FLAGS); // glazed rock surface
		}
	}

	private static void igniteNextTo(ServerLevel level, BlockPos pos, RandomSource random) {
		BlockPos p = pos.relative(net.minecraft.core.Direction.Plane.HORIZONTAL.getRandomDirection(random));
		if (level.getBlockState(p).isAir()) {
			level.setBlock(p, BaseFireBlock.getState(level, p), Block.UPDATE_ALL);
		}
	}

	/** Burns a disc of land around a blast: strongest in the middle. */
	public static void scorch(ServerLevel level, BlockPos center, int radius, RandomSource random, float fireChance, boolean nuclear) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d > radius || random.nextFloat() > 1.0 - d / radius * 0.55) {
					continue;
				}
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				if (level.hasChunk(x >> 4, z >> 4)) {
					scorchColumn(level, x, z, 1.0 - d / radius, fireChance, nuclear, random);
				}
			}
		}
	}

	// ------------------------------------------------------------------ helpers

	private static int surface(ServerLevel level, int x, int z, int fallback) {
		return level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1 : fallback;
	}

	private static boolean editable(ServerLevel level, BlockPos pos, BlockState state) {
		return state.getDestroySpeed(level, pos) >= 0.0F && state.getBlock().getExplosionResistance() < HARD && !state.hasBlockEntity();
	}
}
