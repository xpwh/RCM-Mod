package de.rcm.ballistic.explosion;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * What a blast does beyond the crater, the way real explosions do it:
 * <ul>
 *   <li>the overpressure wave carries far past the crater: windows burst, leaves are stripped, people in
 *       the open are knocked down - unless something solid stands between them and the blast;</li>
 *   <li>a high-explosive casing breaks into fragments that fly much further than the blast itself;</li>
 *   <li>for a nuclear burst the windows break ring by ring as the shock front reaches them.</li>
 * </ul>
 */
public final class BlastPhysics {
	private BlastPhysics() {
	}

	/**
	 * Everything outside the crater of a high-explosive blast of TNT-equivalent {@code power}
	 * (the vanilla explosion radius): fragments, overpressure on people, broken windows.
	 */
	public static void highExplosive(ServerLevel level, Vec3 pos, @Nullable Entity source, float power) {
		shatter(level, pos, 0.0, power * 3.5, (int) (power * power * 6) + 40, 1.0F);
		fragments(level, pos, source, (int) (power * 8) + 10, power * 5.5, 4.0F + power * 0.4F);
		overpressure(level, pos, source, power);
	}

	// ------------------------------------------------------------------ windows and leaves

	/** Glass that the blast wave breaks. */
	private static boolean fragile(BlockState state) {
		return state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(BlockTags.IMPERMEABLE) && !state.is(Blocks.TINTED_GLASS)
			|| state.getBlock() instanceof net.minecraft.world.level.block.StainedGlassPaneBlock;
	}

	/**
	 * The shock front passing between {@code inner} and {@code outer} blocks from {@code pos}: samples
	 * columns in that ring and breaks the glass in them (nearer windows always, further ones less
	 * often), and shakes leaves off the trees close in.
	 */
	public static void shatter(ServerLevel level, Vec3 pos, double inner, double outer, int samples, float strength) {
		if (outer <= inner || !level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.TNT_EXPLODES)) {
			return;
		}
		RandomSource random = level.getRandom();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int broken = 0;
		for (int i = 0; i < samples && broken < 400; i++) {
			double a = random.nextDouble() * Mth.TWO_PI;
			double r = Math.sqrt(inner * inner + random.nextDouble() * (outer * outer - inner * inner));
			int x = Mth.floor(pos.x + Math.cos(a) * r);
			int z = Mth.floor(pos.z + Math.sin(a) * r);
			if (!level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}
			double chance = strength * (1.0 - 0.75 * r / outer);
			int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			int bottom = Math.max(level.getMinY(), Math.min(top, Mth.floor(pos.y)) - 12);
			for (int y = top; y >= bottom; y--) {
				m.set(x, y, z);
				BlockState state = level.getBlockState(m);
				if (fragile(state) && random.nextDouble() < chance) {
					level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					level.levelEvent(2001, m.immutable(), Block.getId(state));
					if (random.nextInt(4) == 0) {
						level.playSound(null, m, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.0F, 0.8F + random.nextFloat() * 0.4F);
					}
					broken++;
				} else if (state.is(BlockTags.LEAVES) && r < outer * 0.45 && random.nextDouble() < chance * 0.35) {
					level.destroyBlock(m, false);
				}
			}
		}
	}

	// ------------------------------------------------------------------ fragments

	/**
	 * Casing fragments flying out on random, mostly flat paths. Each one stops at the first block or
	 * person it hits; glass in its way is holed.
	 */
	public static void fragments(ServerLevel level, Vec3 pos, @Nullable Entity source, int count, double range, float damage) {
		RandomSource random = level.getRandom();
		List<LivingEntity> targets = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(range),
			e -> e.isAlive() && !(e instanceof Player p && (p.isCreative() || p.isSpectator()))));
		Vec3 from = pos.add(0, 0.6, 0);
		for (int i = 0; i < count; i++) {
			double a = random.nextDouble() * Mth.TWO_PI;
			double elevation = -0.15 + random.nextDouble() * 0.6;
			Vec3 dir = new Vec3(Math.cos(a) * Math.cos(elevation), Math.sin(elevation), Math.sin(a) * Math.cos(elevation));
			Vec3 to = from.add(dir.scale(range * (0.5 + 0.5 * random.nextDouble())));
			BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
			Vec3 end = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation();
			LivingEntity struck = null;
			double best = Double.MAX_VALUE;
			for (LivingEntity e : targets) {
				var clip = e.getBoundingBox().clip(from, end);
				if (clip.isPresent()) {
					double d = clip.get().distanceToSqr(from);
					if (d < best) {
						best = d;
						struck = e;
					}
				}
			}
			if (struck != null) {
				double d = Math.sqrt(best);
				float hurt = (float) (damage * Math.max(0.3, 1.0 - d / range));
				struck.hurtServer(level, level.damageSources().explosion(source, null), hurt);
				continue;
			}
			if (hit.getType() == HitResult.Type.BLOCK) {
				BlockState state = level.getBlockState(hit.getBlockPos());
				if (fragile(state)) {
					level.destroyBlock(hit.getBlockPos(), false);
				} else if (i % 3 == 0) {
					Vec3 p = hit.getLocation();
					level.sendParticles(de.rcm.ballistic.ModRegistry.SPARK, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.1);
				}
			}
		}
	}

	// ------------------------------------------------------------------ overpressure

	/**
	 * The air blast beyond the vanilla explosion's reach: people standing in the open are thrown back,
	 * winded and stunned; a wall between them and the blast takes it instead.
	 */
	public static void overpressure(ServerLevel level, Vec3 pos, @Nullable Entity source, float power) {
		double inner = power * 1.6;
		double outer = power * 4.0;
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(outer))) {
			double d = e.position().distanceTo(pos);
			if (d < inner || d > outer || e instanceof Player p && (p.isCreative() || p.isSpectator())) {
				continue;
			}
			Vec3 eye = e.getEyePosition();
			if (level.clip(new ClipContext(pos.add(0, 0.5, 0), eye, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty())).getType()
				!= HitResult.Type.MISS) {
				continue; // sheltered behind something solid
			}
			double f = 1.0 - (d - inner) / (outer - inner);
			e.hurtServer(level, level.damageSources().explosion(source, null), (float) (1.0 + 5.0 * f * f));
			Vec3 push = e.position().subtract(pos);
			push = new Vec3(push.x, 0, push.z).normalize().scale(0.9 * f).add(0, 0.25 * f, 0);
			e.push(push.x, push.y, push.z);
			e.hurtMarked = true;
			if (f > 0.4) {
				e.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOWNESS, (int) (40 * f), 1));
			}
		}
	}
}
