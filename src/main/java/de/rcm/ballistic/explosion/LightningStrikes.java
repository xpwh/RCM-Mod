package de.rcm.ballistic.explosion;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Lightning as it strikes: it goes for the highest point round where it comes down - the lone tree, the tower, the
 * peak (a lightning rod still draws it from far round, and keeps it off the house). Where it hits it leaves its
 * mark: a tree is blasted, the bark torn off, the trunk charred and split, the crown set alight; sand fuses into
 * a dark glassy fulgurite; the ground is scorched; a roof catches fire and glass shatters; in water the current
 * spreads and catches what swims there; and anyone standing right by it is thrown by the side flash.
 */
public final class LightningStrikes {
	private static final RandomSource R = RandomSource.create();

	private LightningStrikes() {
	}

	/**
	 * A better target than {@code chosen} for a bolt coming down round {@code around}: the highest column among a
	 * few round it, if it stands well above. Null: keep Minecraft's choice (a rod, a creature, or no better spot).
	 */
	public static BlockPos target(ServerLevel level, BlockPos around, BlockPos chosen) {
		BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, around);
		if (!chosen.equals(ground)) {
			return null; // a lightning rod or a creature: Minecraft's own reasons
		}
		BlockPos best = ground;
		for (int i = 0; i < 10; i++) {
			int x = ground.getX() + R.nextInt(49) - 24;
			int z = ground.getZ() + R.nextInt(49) - 24;
			if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
				continue;
			}
			BlockPos p = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z));
			if (p.getY() > best.getY()) {
				best = p;
			}
		}
		int rise = best.getY() - ground.getY();
		// the taller it stands above the rest, the surer the bolt goes for it
		return rise >= 3 && R.nextFloat() < Mth.clamp(rise / 10.0F, 0.35F, 0.9F) ? best : null;
	}

	/** A bolt has struck (a real one, not just its look): what it does where it hits. */
	public static void struck(ServerLevel level, LightningBolt bolt) {
		BlockPos at = bolt.blockPosition();
		BlockPos hit = at.below();
		BlockState s = level.getBlockState(hit);
		if (s.is(Blocks.LIGHTNING_ROD) || level.getBlockState(at).is(Blocks.LIGHTNING_ROD)) {
			return; // taken safely to the ground
		}
		boolean fire = level.getGameRules().get(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER) != 0;
		sideFlash(level, bolt);
		if (!level.getFluidState(hit).isEmpty() && level.getFluidState(hit).is(FluidTags.WATER) || !level.getFluidState(at).isEmpty()) {
			water(level, at);
			return;
		}
		BlockPos trunk = trunk(level, hit);
		if (trunk != null) {
			tree(level, trunk, fire);
		} else if (s.is(BlockTags.SAND)) {
			fulgurite(level, hit);
		} else if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.PODZOL) || s.is(Blocks.COARSE_DIRT)) {
			level.setBlock(hit, ModRegistry.SCORCHED_EARTH.defaultBlockState(), Block.UPDATE_ALL);
			for (int i = 0; i < 3; i++) {
				BlockPos q = hit.offset(R.nextInt(3) - 1, 0, R.nextInt(3) - 1);
				if (level.getBlockState(q).is(Blocks.GRASS_BLOCK) && R.nextFloat() < 0.5F) {
					level.setBlock(q, ModRegistry.SCORCHED_EARTH.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		} else if (built(s)) {
			building(level, hit, fire);
		}
	}

	/** The tree it hit: the log the bolt came down to (through the crown), or null if it was no tree. */
	private static BlockPos trunk(ServerLevel level, BlockPos hit) {
		BlockPos.MutableBlockPos m = hit.mutable();
		for (int i = 0; i < 7; i++) {
			BlockState s = level.getBlockState(m);
			if (s.is(BlockTags.LOGS) && s.is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
				return m.immutable();
			}
			if (!s.is(BlockTags.LEAVES) && !s.isAir()) {
				return null;
			}
			m.move(0, -1, 0);
		}
		return null;
	}

	/** A blasted tree: the bark torn away in a shower of splinters, the trunk charred down a way, split, the crown alight. */
	private static void tree(ServerLevel level, BlockPos top, boolean fire) {
		BlockPos.MutableBlockPos m = top.mutable();
		int down = 2 + R.nextInt(5);
		for (int i = 0; i < down; i++) {
			BlockState s = level.getBlockState(m);
			if (!s.is(BlockTags.LOGS)) {
				break;
			}
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), m.getX() + 0.5, m.getY() + 0.5, m.getZ() + 0.5, 30, 0.6, 0.6, 0.6, 0.2);
			// split: now and then a piece of the trunk blown right out
			if (i > 0 && R.nextFloat() < 0.25F) {
				level.destroyBlock(m, false);
				Block.popResource(level, m, new ItemStack(Items.STICK, 1 + R.nextInt(3)));
			} else {
				BlockState charred = ModRegistry.CHARRED_LOG.defaultBlockState();
				if (s.hasProperty(RotatedPillarBlock.AXIS)) {
					charred = charred.setValue(RotatedPillarBlock.AXIS, s.getValue(RotatedPillarBlock.AXIS));
				}
				level.setBlock(m, charred, Block.UPDATE_ALL);
			}
			m.move(0, -1, 0);
		}
		level.playSound(null, top, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, SoundSource.BLOCKS, 3.0F, 0.6F);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, top.getX() + 0.5, top.getY() + 1.0, top.getZ() + 0.5, 12, 0.8, 0.8, 0.8, 0.02);
		// the crown catches: a few flames in it (the rain may put them out again)
		if (fire) {
			for (int i = 0; i < 12; i++) {
				BlockPos q = top.offset(R.nextInt(7) - 3, R.nextInt(4) - 1, R.nextInt(7) - 3);
				if (level.getBlockState(q).isAir() && level.getBlockState(q.below()).is(BlockTags.LEAVES) && R.nextFloat() < 0.5F) {
					level.setBlock(q, BaseFireBlock.getState(level, q), Block.UPDATE_ALL);
				}
			}
		}
	}

	/** Sand fused by the heat: a dark, glassy tube down into it. */
	private static void fulgurite(ServerLevel level, BlockPos hit) {
		BlockPos.MutableBlockPos m = hit.mutable();
		int deep = 1 + R.nextInt(3);
		for (int i = 0; i < deep; i++) {
			if (!level.getBlockState(m).is(BlockTags.SAND)) {
				break;
			}
			level.setBlock(m, ModRegistry.CRATER_GLASS.defaultBlockState(), Block.UPDATE_ALL);
			m.move(R.nextInt(3) - 1, -1, R.nextInt(3) - 1);
		}
		level.sendParticles(ParticleTypes.LAVA, hit.getX() + 0.5, hit.getY() + 1.0, hit.getZ() + 0.5, 6, 0.3, 0.1, 0.3, 0.0);
	}

	/** Planks, wool, hay, a roof: it burns where it was hit, glass near by shatters, the top of it is knocked off. */
	private static boolean built(BlockState s) {
		return s.is(BlockTags.PLANKS) || s.is(BlockTags.WOOL) || s.is(Blocks.HAY_BLOCK) || s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS)
			|| s.is(Blocks.BRICKS) || s.is(Blocks.STONE_BRICKS) || s.is(BlockTags.IMPERMEABLE) || s.is(Blocks.BOOKSHELF);
	}

	private static void building(ServerLevel level, BlockPos hit, boolean fire) {
		BlockState s = level.getBlockState(hit);
		if (R.nextFloat() < 0.5F && !s.is(BlockTags.IMPERMEABLE)) {
			level.destroyBlock(hit, true);
		}
		// glass round it shatters
		for (BlockPos q : BlockPos.betweenClosed(hit.offset(-2, -3, -2), hit.offset(2, 1, 2))) {
			BlockState g = level.getBlockState(q);
			boolean pane = g.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock && !g.is(Blocks.IRON_BARS);
			if ((g.is(BlockTags.IMPERMEABLE) || pane) && R.nextFloat() < 0.5F) {
				level.destroyBlock(q, false);
			}
		}
		if (fire) {
			for (Direction d : Direction.values()) {
				BlockPos q = hit.relative(d);
				if (level.getBlockState(q).isAir() && R.nextFloat() < 0.6F) {
					level.setBlock(q, BaseFireBlock.getState(level, q), Block.UPDATE_ALL);
				}
			}
		}
	}

	/** In water the current spreads out: what swims near is shocked, the closer the worse. */
	private static void water(ServerLevel level, BlockPos at) {
		Vec3 c = Vec3.atCenterOf(at);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at).inflate(10.0))) {
			if (!e.isInWater()) {
				continue;
			}
			double d = e.position().distanceTo(c);
			if (d < 10.0) {
				e.hurtServer(level, level.damageSources().lightningBolt(), (float) (8.0 * (1.0 - d / 10.0)) + 1.0F);
			}
		}
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 40, 3.0, 0.2, 3.0, 0.3);
	}

	/** The side flash: whoever stands just by the strike (but was not hit) is thrown and burnt a little. */
	private static void sideFlash(ServerLevel level, LightningBolt bolt) {
		Vec3 c = bolt.position();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, bolt.getBoundingBox().inflate(4.0))) {
			double d = e.position().distanceTo(c);
			if (d < 1.2 || d > 4.0) {
				continue; // right under it, the bolt itself does the damage
			}
			e.hurtServer(level, level.damageSources().lightningBolt(), (float) (3.0 * (1.0 - d / 4.0)) + 0.5F);
			Vec3 away = e.position().subtract(c).normalize().scale(0.6 * (1.0 - d / 4.0) + 0.2);
			e.push(away.x, 0.3, away.z);
			e.hurtMarked = true;
		}
	}
}
