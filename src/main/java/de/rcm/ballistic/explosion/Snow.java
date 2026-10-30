package de.rcm.ballistic.explosion;

import de.rcm.ballistic.ModRegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Snow as it is. It piles up while it snows - a layer at a time, deeper the longer it goes on, deepest in a
 * blizzard - instead of stopping at one thin layer. A blast melts a crater into it (ice turns to water). And on a
 * steep snowy slope the shock of a big one sets off an avalanche: the snow breaking away near the blast first, then
 * further and further out, sliding and tumbling down the mountainside in a cloud of powder, burying what it hits.
 */
public final class Snow {
	private static final RandomSource R = RandomSource.create();

	/** A block of snow about to break away (game time). */
	private record Release(ServerLevel level, BlockPos pos, double dirX, double dirZ, long at) {
	}

	private static final List<Release> PENDING = new ArrayList<>();

	private Snow() {
	}

	// ------------------------------------------------------------------ snowfall

	public static void tick(ServerLevel level) {
		long now = level.getGameTime();
		releases(level, now);
		if (!level.isRaining() || now % 10L != 3L || !level.dimensionType().hasSkyLight() || !de.rcm.ballistic.config.ServerConfig.nature(de.rcm.ballistic.config.ServerConfig.SNOW)) {
			return;
		}
		int max = level.isThundering() ? 8 : 5;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (ServerPlayer p : level.players()) {
			for (int i = 0; i < 12; i++) {
				int x = Mth.floor(p.getX()) + R.nextInt(97) - 48;
				int z = Mth.floor(p.getZ()) + R.nextInt(97) - 48;
				if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				m.set(x, y, z);
				if (level.getBiome(m).value().getPrecipitationAt(m, level.getSeaLevel()) != Biome.Precipitation.SNOW) {
					continue;
				}
				// the top of the snow already lying there, or the ground for the first layer
				BlockState here = level.getBlockState(m);
				BlockState below = level.getBlockState(m.below());
				if (below.is(Blocks.SNOW)) {
					m.move(0, -1, 0);
					here = below;
				}
				if (here.is(Blocks.SNOW)) {
					int layers = here.getValue(SnowLayerBlock.LAYERS);
					if (layers < max) {
						level.setBlock(m, here.setValue(SnowLayerBlock.LAYERS, layers + 1), Block.UPDATE_CLIENTS);
					}
				} else if (here.isAir() && Blocks.SNOW.defaultBlockState().canSurvive(level, m)) {
					level.setBlock(m, Blocks.SNOW.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
		}
	}

	// ------------------------------------------------------------------ blasts

	/** A blast of {@code radius} at {@code pos}: the snow melts round it, and steep snowy slopes may let go. */
	public static void blast(ServerLevel level, Vec3 pos, double radius) {
		if (radius <= 0.0) {
			return;
		}
		melt(level, pos, Math.min(radius * 0.8, 80.0));
		if (de.rcm.ballistic.config.ServerConfig.nature(de.rcm.ballistic.config.ServerConfig.AVALANCHES)) {
			avalanche(level, pos, Mth.clamp(radius * 2.2, 24.0, 140.0));
		}
	}

	/** Melts the snow (and ice) round {@code pos}: a crater in the snow, puddles where the ice was. */
	private static void melt(ServerLevel level, Vec3 pos, double radius) {
		int r = Mth.ceil(radius);
		BlockPos c = BlockPos.containing(pos);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				// the edge ragged: patches left near the rim
				if (d > radius || d > radius * 0.75 && R.nextFloat() < (d - radius * 0.75) / (radius * 0.25)) {
					continue;
				}
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
					continue;
				}
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				if (Math.abs(top - c.getY()) > radius) {
					continue;
				}
				for (int y = top; y >= top - 3; y--) {
					m.set(x, y, z);
					BlockState s = level.getBlockState(m);
					if (s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)) {
						level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					} else if (s.is(Blocks.ICE)) {
						level.setBlock(m, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
					} else if (!s.isAir()) {
						break;
					}
				}
			}
		}
	}

	/**
	 * The slopes round {@code pos}: where snow lies on ground steep enough (and is deep enough), it breaks away, the
	 * nearest first - each block sent sliding off downhill.
	 */
	private static void avalanche(ServerLevel level, Vec3 pos, double reach) {
		BlockPos c = BlockPos.containing(pos);
		long now = level.getGameTime();
		int found = 0;
		int r = Mth.ceil(reach);
		for (int dx = -r; dx <= r && found < 320; dx += 2) {
			for (int dz = -r; dz <= r && found < 320; dz += 2) {
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d > reach) {
					continue;
				}
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null || level.getChunkSource().getChunkNow((x + 3) >> 4, (z + 3) >> 4) == null
					|| level.getChunkSource().getChunkNow((x - 3) >> 4, (z - 3) >> 4) == null) {
					continue;
				}
				int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				BlockPos top = new BlockPos(x, h - 1, z);
				BlockState s = level.getBlockState(top);
				boolean deep = s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.SNOW) && s.getValue(SnowLayerBlock.LAYERS) >= 3;
				if (!deep) {
					continue;
				}
				// the slope: how the ground falls away over three blocks each way
				double gx = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + 3, z) - level.getHeight(Heightmap.Types.MOTION_BLOCKING, x - 3, z);
				double gz = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z + 3) - level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z - 3);
				double slope = Math.sqrt(gx * gx + gz * gz) / 6.0;
				// about 30 degrees and steeper; the further from the blast the steeper it must be to go
				if (slope < 0.6 + 0.5 * d / reach || R.nextFloat() > 0.85) {
					continue;
				}
				double len = Math.max(Math.sqrt(gx * gx + gz * gz), 1.0E-3);
				// the shock reaches it, then the slab above it lets go too
				long at = now + 2L + (long) (d / 9.0) + R.nextInt(10);
				PENDING.add(new Release(level, top, -gx / len, -gz / len, at));
				found++;
			}
		}
		if (found > 12) {
			level.playSound(null, pos.x, pos.y, pos.z, ModRegistry.THUNDER_FAR, SoundSource.BLOCKS, 6.0F, 0.45F);
		}
	}

	private static void releases(ServerLevel level, long now) {
		for (Iterator<Release> it = PENDING.iterator(); it.hasNext();) {
			Release r = it.next();
			if (r.level() != level) {
				continue;
			}
			if (now < r.at()) {
				continue;
			}
			it.remove();
			BlockState s = level.getBlockState(r.pos());
			if (!(s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.SNOW))) {
				continue;
			}
			FallingBlockEntity slab = FallingBlockEntity.fall(level, r.pos(), Blocks.SNOW_BLOCK.defaultBlockState());
			slab.dropItem = false;
			slab.setHurtsEntities(0.6F, 6);
			double speed = 0.5 + R.nextDouble() * 0.5;
			slab.setDeltaMovement(r.dirX() * speed, 0.12 + R.nextDouble() * 0.15, r.dirZ() * speed);
			// the powder cloud thrown up as it goes
			level.sendParticles(ParticleTypes.SNOWFLAKE, r.pos().getX() + 0.5, r.pos().getY() + 1.0, r.pos().getZ() + 0.5, 6, 1.2, 0.6, 1.2, 0.05);
			level.sendParticles(ParticleTypes.CLOUD, r.pos().getX() + 0.5, r.pos().getY() + 1.0, r.pos().getZ() + 0.5, 2, 1.0, 0.5, 1.0, 0.02);
			if (R.nextFloat() < 0.1F) {
				level.playSound(null, r.pos(), SoundEvents.SNOW_BREAK, SoundSource.BLOCKS, 2.0F, 0.6F);
			}
		}
	}

	public static void clear() {
		PENDING.clear();
	}
}
