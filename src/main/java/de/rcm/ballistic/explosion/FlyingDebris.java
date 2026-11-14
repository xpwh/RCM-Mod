package de.rcm.ballistic.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * Ejecta: real blocks torn out of the crater and thrown away on ballistic arcs. They are ordinary
 * falling blocks, so they tumble through the air, hurt whatever they land on and pile up as rubble
 * around the crater.
 */
public final class FlyingDebris {
	private FlyingDebris() {
	}

	/**
	 * @param radius region the blocks are taken from
	 * @param count  how many blocks fly at most
	 * @param speed  launch speed in blocks per tick
	 */
	public static void launch(ServerLevel level, Vec3 center, double radius, int count, double speed) {
		if (!level.getGameRules().get(GameRules.TNT_EXPLODES)) {
			return;
		}
		RandomSource random = level.getRandom();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int spawned = 0;
		for (int attempt = 0; attempt < count * 5 && spawned < count; attempt++) {
			Vec3 dir = new Vec3(random.nextGaussian(), random.nextGaussian() * 0.6, random.nextGaussian());
			if (dir.lengthSqr() < 1.0E-6) {
				continue;
			}
			dir = dir.normalize();
			double dist = radius * Math.cbrt(random.nextDouble());
			Vec3 p = center.add(dir.scale(dist));
			m.set(Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z));
			if (!level.isLoaded(m)) {
				continue;
			}
			BlockState state = level.getBlockState(m);
			if (!canFly(level, m, state)) {
				continue;
			}
			// mostly the exposed surface flies; deeper blocks only right at the centre
			if (dist > radius * 0.45 && !level.getBlockState(m.above()).isAir()) {
				continue;
			}
			FallingBlockEntity block = FallingBlockEntity.fall(level, m.immutable(), state);
			Vec3 out = Vec3.atCenterOf(m).subtract(center);
			out = out.lengthSqr() < 1.0E-4 ? new Vec3(0, 1, 0) : out.normalize();
			out = new Vec3(out.x, Math.abs(out.y) * 0.3 + 0.55 + random.nextDouble() * 0.7, out.z).normalize();
			double s = speed * (0.45 + random.nextDouble() * 0.75) * (1.25 - dist / radius * 0.6);
			block.setDeltaMovement(out.scale(s));
			block.dropItem = false;
			block.setHurtsEntities(1.5F, 30);
			block.hurtMarked = true;
			spawned++;
		}
	}

	private static boolean canFly(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity() || state.is(Blocks.FIRE) || state.is(Blocks.BEDROCK)) {
			return false;
		}
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0 || state.getBlock().getExplosionResistance() >= 600) {
			return false;
		}
		return state.isCollisionShapeFullBlock(level, pos);
	}
}
