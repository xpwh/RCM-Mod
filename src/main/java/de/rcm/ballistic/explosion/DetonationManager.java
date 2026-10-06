package de.rcm.ballistic.explosion;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.entity.BombletEntity;
import de.rcm.ballistic.entity.ReentryVehicleEntity;
import de.rcm.ballistic.entity.MissileType.Warhead;
import de.rcm.ballistic.network.ModNetworking.DetonationPayload;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class DetonationManager {
	/** Players this far away still see the flash/cloud and hear the rumble. */
	private static final double EFFECT_RANGE = 3000.0;
	private static final double HYDROGEN_EFFECT_RANGE = 5000.0;

	private static final List<NukeDetonation> NUKES = new ArrayList<>();
	private static final List<Scheduled> SCHEDULED = new ArrayList<>();

	private record Scheduled(ServerLevel level, int[] delay, Runnable action) {
	}

	private DetonationManager() {
	}

	// ------------------------------------------------------------------ entry points

	public static void detonate(ServerLevel level, Vec3 pos, Vec3 dir, Warhead warhead, @Nullable Entity source) {
		broadcast(level, pos, warhead);
		// ejecta first, before the blast and the crater remove the blocks
		switch (warhead) {
			case NUCLEAR, MIRV -> FlyingDebris.launch(level, pos, 18.0, 200, 2.8);
			case HYDROGEN -> FlyingDebris.launch(level, pos, 26.0, 300, 3.4);
			case MIRV_WARHEAD, TACTICAL_NUKE -> FlyingDebris.launch(level, pos, 12.0, 120, 2.2);
			case HYPERSONIC -> FlyingDebris.launch(level, pos, 6.5, 50, 1.9);
			case THERMOBARIC -> FlyingDebris.launch(level, pos, 6.0, 40, 1.5);
			case CRUISE, ANTI_RADAR -> FlyingDebris.launch(level, pos, 4.5, 24, 1.3);
			case CLUSTER, INCENDIARY -> FlyingDebris.launch(level, pos, 3.0, 10, 1.0);
			case DRONE -> FlyingDebris.launch(level, pos, 3.5, 14, 1.1);
			case BUNKER_BUSTER -> {
			}
			default -> FlyingDebris.launch(level, pos, 5.0, 34, 1.5);
		}
		switch (warhead) {
			case NUCLEAR, MIRV -> NUKES.add(new NukeDetonation(level, pos, source, NukeDetonation.Yield.FISSION));
			case HYDROGEN -> NUKES.add(new NukeDetonation(level, pos, source, NukeDetonation.Yield.THERMONUCLEAR));
			case MIRV_WARHEAD, TACTICAL_NUKE -> NUKES.add(new NukeDetonation(level, pos, source, NukeDetonation.Yield.TACTICAL));
			case INCENDIARY -> {
				highExplosive(level, pos, source, 5.0F, 1, 0);
				scorch(level, BlockPos.containing(pos), 24, level.getRandom(), 0.6F);
			}
			case ANTI_RADAR -> highExplosive(level, pos, source, 8.0F, 2, 10);
			case HYPERSONIC -> highExplosive(level, pos, source, 13.0F, 5, 22); // warhead + enormous kinetic energy
			case BUNKER_BUSTER -> bunkerBuster(level, pos, dir, source);
			case THERMOBARIC -> thermobaric(level, pos, source);
			case CRUISE -> highExplosive(level, pos, source, 9.0F, 3, 15);
			case DRONE -> highExplosive(level, pos, source, 6.5F, 2, 8); // ~50 kg warhead
			case CLUSTER -> highExplosive(level, pos, source, 6.0F, 1, 8); // hit the ground before opening
			default -> highExplosive(level, pos, source, 11.0F, 4, 18);
		}
	}

	public static void releaseCluster(ServerLevel level, Vec3 pos, Vec3 velocity, Entity source) {
		broadcast(level, pos, Warhead.CLUSTER_RELEASE);
		level.playSound(null, pos.x, pos.y, pos.z, ModRegistry.CLUSTER_POP, SoundSource.BLOCKS, 12.0F, 1.0F);
		RandomSource random = level.getRandom();
		Vec3 carry = velocity.scale(0.35);
		for (int i = 0; i < 28; i++) {
			BombletEntity bomblet = ModRegistry.BOMBLET.create(level, EntitySpawnReason.TRIGGERED);
			if (bomblet == null) {
				continue;
			}
			double angle = random.nextDouble() * Mth.TWO_PI;
			double spread = 0.35 + random.nextDouble() * 0.75;
			bomblet.setPos(pos.x + random.nextGaussian(), pos.y + random.nextGaussian(), pos.z + random.nextGaussian());
			bomblet.setDeltaMovement(carry.add(Math.cos(angle) * spread, 0.2 + random.nextDouble() * 0.4, Math.sin(angle) * spread));
			level.addFreshEntity(bomblet);
		}
	}

	/** Incendiary airburst: burning sub-munitions rain down over a wide area and set it alight. */
	public static void releaseIncendiary(ServerLevel level, Vec3 pos, Vec3 velocity, Entity source) {
		broadcast(level, pos, Warhead.INCENDIARY_RELEASE);
		level.playSound(null, pos.x, pos.y, pos.z, ModRegistry.CLUSTER_POP, SoundSource.BLOCKS, 12.0F, 0.7F);
		RandomSource random = level.getRandom();
		Vec3 carry = velocity.scale(0.25);
		for (int i = 0; i < 40; i++) {
			BombletEntity bomblet = ModRegistry.BOMBLET.create(level, EntitySpawnReason.TRIGGERED);
			if (bomblet == null) {
				continue;
			}
			double angle = random.nextDouble() * Mth.TWO_PI;
			double spread = 0.4 + random.nextDouble() * 1.0;
			bomblet.setIncendiary(true);
			bomblet.setPos(pos.x + random.nextGaussian(), pos.y + random.nextGaussian(), pos.z + random.nextGaussian());
			bomblet.setDeltaMovement(carry.add(Math.cos(angle) * spread, 0.3 + random.nextDouble() * 0.5, Math.sin(angle) * spread));
			level.addFreshEntity(bomblet);
		}
	}

	/**
	 * High-altitude nuclear burst. Nothing reaches the ground but light and the electromagnetic pulse:
	 * radars, batteries and silos below go dark, missiles in flight lose their guidance and redstone
	 * electronics burn out.
	 */
	public static void emp(ServerLevel level, Vec3 pos, Entity source) {
		broadcast(level, pos, Warhead.EMP);
		final double radius = 220.0;
		EmpManager.addZone(level, pos, radius, 20 * 90);
		for (AirThreat threat : ThreatTracker.threats(level)) {
			Entity e = threat.asEntity();
			if (e != source && Math.hypot(e.getX() - pos.x, e.getZ() - pos.z) < radius) {
				threat.destroyByInterceptor(level);
			}
		}
		fryElectronics(level, pos, 96);
		Component msg = Component.literal("⚡ ").append(Component.translatable("message.ballisticmissiles.emp_hit")).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD);
		for (ServerPlayer player : level.players()) {
			if (Math.hypot(player.getX() - pos.x, player.getZ() - pos.z) < radius) {
				player.displayClientMessage(msg, true);
				player.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 120, 0));
			}
		}
	}

	private static boolean empSensitive(BlockState state) {
		return state.is(Blocks.REDSTONE_WIRE) || state.is(Blocks.REPEATER) || state.is(Blocks.COMPARATOR) || state.is(Blocks.REDSTONE_TORCH)
			|| state.is(Blocks.REDSTONE_WALL_TORCH) || state.is(Blocks.OBSERVER) || state.is(Blocks.DAYLIGHT_DETECTOR);
	}

	/** Burns out redstone components below the burst. */
	private static void fryElectronics(ServerLevel level, Vec3 pos, int radius) {
		RandomSource random = level.getRandom();
		List<BlockPos> fried = new ArrayList<>();
		int cx0 = SectionPos.blockToSectionCoord(pos.x - radius);
		int cx1 = SectionPos.blockToSectionCoord(pos.x + radius);
		int cz0 = SectionPos.blockToSectionCoord(pos.z - radius);
		int cz1 = SectionPos.blockToSectionCoord(pos.z + radius);
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				LevelChunk chunk = level.getChunk(cx, cz);
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section.hasOnlyAir() || !section.maybeHas(DetonationManager::empSensitive)) {
						continue;
					}
					int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								if (!empSensitive(section.getBlockState(x, y, z))) {
									continue;
								}
								int wx = (cx << 4) + x;
								int wz = (cz << 4) + z;
								if (Math.hypot(wx + 0.5 - pos.x, wz + 0.5 - pos.z) < radius && random.nextFloat() < 0.7F && fried.size() < 4000) {
									fried.add(new BlockPos(wx, baseY + y, wz));
								}
							}
						}
					}
				}
			}
		}
		for (BlockPos p : fried) {
			level.destroyBlock(p, true);
			if (random.nextInt(4) == 0) {
				level.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, p.getX() + 0.5, p.getY() + 0.3, p.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.3);
			}
		}
	}

	/** MIRV bus: five re-entry vehicles fan out onto a cross pattern around the aim point. */
	public static void releaseMirv(ServerLevel level, Vec3 pos, Vec3 aim, Entity source) {
		broadcast(level, pos, Warhead.MIRV_RELEASE);
		level.playSound(null, pos.x, pos.y, pos.z, ModRegistry.CLUSTER_POP, SoundSource.BLOCKS, 16.0F, 0.6F);
		double spacing = 70.0;
		Vec3[] aims = {
			aim, aim.add(spacing, 0, 0), aim.add(-spacing, 0, 0), aim.add(0, 0, spacing), aim.add(0, 0, -spacing)
		};
		for (int i = 0; i < aims.length; i++) {
			ReentryVehicleEntity rv = ModRegistry.REENTRY_VEHICLE.create(level, EntitySpawnReason.TRIGGERED);
			if (rv == null) {
				continue;
			}
			Vec3 target = aims[i];
			int x = Mth.floor(target.x);
			int z = Mth.floor(target.z);
			if (level.hasChunk(x >> 4, z >> 4)) {
				target = new Vec3(target.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), target.z);
			}
			Vec3 start = pos.add((i - 2) * 1.5, 0, 0);
			Vec3 dir = target.subtract(start).normalize();
			rv.setPos(start);
			rv.setAim(target);
			rv.setDeltaMovement(dir.scale(4.5 + i * 0.25)); // staggered impacts
			level.addFreshEntity(rv);
		}
	}

	/** A missile destroyed in mid-air: fireball and falling debris, no warhead detonation. */
	public static void intercepted(ServerLevel level, Vec3 pos, Entity source) {
		broadcast(level, pos, Warhead.INTERCEPT);
	}

	/** The interceptor's own fragmentation warhead going off. */
	public static void interceptorBurst(ServerLevel level, Vec3 pos, Entity source) {
		broadcast(level, pos, Warhead.BOMBLET);
		if (pos.y < level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(pos.x), Mth.floor(pos.z)) + 6) {
			level.explode(source, pos.x, pos.y, pos.z, 2.0F, false, Level.ExplosionInteraction.NONE);
		}
	}

	public static void detonateBomblet(ServerLevel level, Vec3 pos, Entity source) {
		broadcast(level, pos, Warhead.BOMBLET);
		FlyingDebris.launch(level, pos, 2.0, 2, 0.9);
		level.explode(source, pos.x, pos.y, pos.z, 3.2F, false, Level.ExplosionInteraction.TNT);
	}

	/** Free-fall bomb from an airstrike jet (Mk 82-class): a solid blast with a small crater. */
	public static void detonateAerialBomb(ServerLevel level, Vec3 pos, Entity source) {
		broadcast(level, pos, Warhead.AERIAL_BOMB);
		FlyingDebris.launch(level, pos, 3.5, 12, 1.2);
		highExplosive(level, pos, source, 5.5F, 1, 6);
	}

	/** Burning sub-munition: splashes fire around the point of impact. */
	public static void detonateIncendiary(ServerLevel level, Vec3 pos, Entity source) {
		broadcast(level, pos, Warhead.BOMBLET);
		level.explode(source, pos.x, pos.y, pos.z, 1.2F, false, Level.ExplosionInteraction.NONE);
		RandomSource random = level.getRandom();
		BlockPos center = BlockPos.containing(pos);
		for (int i = 0; i < 14; i++) {
			BlockPos p = center.offset(random.nextInt(7) - 3, random.nextInt(3) - 1, random.nextInt(7) - 3);
			if (level.isLoaded(p) && level.getBlockState(p).isAir() && !level.getBlockState(p.below()).isAir()) {
				level.setBlock(p, BaseFireBlock.getState(level, p), 3);
			}
		}
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(center).inflate(3.5))) {
			e.igniteForSeconds(8.0F);
			e.hurtServer(level, level.damageSources().inFire(), 4.0F);
		}
	}

	private static void broadcast(ServerLevel level, Vec3 pos, Warhead warhead) {
		double range = warhead == Warhead.HYDROGEN ? HYDROGEN_EFFECT_RANGE : warhead == Warhead.BOMBLET ? 600.0 : EFFECT_RANGE;
		if (warhead == Warhead.MIRV_WARHEAD) {
			range = 4000.0;
		}
		DetonationPayload payload = new DetonationPayload(warhead.ordinal(), pos.x, pos.y, pos.z);
		for (ServerPlayer player : level.players()) {
			if (player.position().distanceToSqr(pos) < range * range) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	private static void schedule(ServerLevel level, int delay, Runnable action) {
		SCHEDULED.add(new Scheduled(level, new int[] {delay}, action));
	}

	// ------------------------------------------------------------------ warheads

	private static void highExplosive(ServerLevel level, Vec3 pos, @Nullable Entity source, float power, int secondaries, int scorchRadius) {
		RandomSource random = level.getRandom();
		level.explode(source, pos.x, pos.y, pos.z, power, true, Level.ExplosionInteraction.TNT);
		// A few secondary blasts make the crater irregular and the boom feel heavier.
		for (int i = 0; i < secondaries; i++) {
			double ox = pos.x + random.nextGaussian() * power * 0.35;
			double oy = pos.y + random.nextGaussian() * 1.5;
			double oz = pos.z + random.nextGaussian() * power * 0.35;
			level.explode(source, ox, oy, oz, power * 0.55F, true, Level.ExplosionInteraction.TNT);
		}
		scorch(level, BlockPos.containing(pos), scorchRadius, random, 0.12F);
	}

	/** Drills along the flight direction, then detonates deep underground. */
	private static void bunkerBuster(ServerLevel level, Vec3 pos, Vec3 dir, @Nullable Entity source) {
		Vec3 d = dir.lengthSqr() < 1.0E-6 ? new Vec3(0, -1, 0) : dir.normalize();
		if (d.y > -0.4) {
			d = new Vec3(d.x, -0.4, d.z).normalize();
		}
		final int depth = 22;
		level.explode(source, pos.x, pos.y, pos.z, 2.5F, false, Level.ExplosionInteraction.TNT);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		Vec3 tip = pos;
		for (int i = 0; i < depth * 2; i++) {
			tip = pos.add(d.scale(i * 0.5));
			for (int ox = -1; ox <= 1; ox++) {
				for (int oy = -1; oy <= 1; oy++) {
					for (int oz = -1; oz <= 1; oz++) {
						if (ox * ox + oy * oy + oz * oz > 2) {
							continue;
						}
						m.set(Mth.floor(tip.x) + ox, Mth.floor(tip.y) + oy, Mth.floor(tip.z) + oz);
						BlockState state = level.getBlockState(m);
						if (!state.isAir() && state.getDestroySpeed(level, m) >= 0 && state.getBlock().getExplosionResistance() < 1200) {
							level.setBlock(m, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
						}
					}
				}
			}
		}
		Vec3 deep = tip;
		schedule(level, 12, () -> {
			level.playSound(null, deep.x, deep.y, deep.z, ModRegistry.EXPLOSION_BUNKER, SoundSource.BLOCKS, 20.0F, 1.0F);
			FlyingDebris.launch(level, deep, 6.0, 45, 1.9);
			level.explode(source, deep.x, deep.y, deep.z, 13.0F, false, Level.ExplosionInteraction.TNT);
			RandomSource random = level.getRandom();
			for (int i = 0; i < 5; i++) {
				level.explode(
					source, deep.x + random.nextGaussian() * 4, deep.y + random.nextGaussian() * 3, deep.z + random.nextGaussian() * 4, 7.0F, false,
					Level.ExplosionInteraction.TNT
				);
			}
		});
	}

	/** Fuel-air explosive: suction, then a huge pressure wave and a firestorm. */
	private static void thermobaric(ServerLevel level, Vec3 pos, @Nullable Entity source) {
		final double radius = 42.0;
		AABB box = new AABB(BlockPos.containing(pos)).inflate(radius);
		// 1) the fuel cloud ignites and sucks air (and everything else) in
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
			Vec3 in = pos.subtract(e.position());
			double d = in.length();
			if (d < radius && d > 0.5) {
				e.push(in.normalize().scale(0.9 * (1 - d / radius)));
				e.hurtMarked = true;
			}
		}
		schedule(level, 6, () -> {
			level.explode(source, pos.x, pos.y + 2, pos.z, 9.0F, true, Level.ExplosionInteraction.TNT);
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
				Vec3 out = e.position().subtract(pos);
				double d = out.length();
				if (d >= radius) {
					continue;
				}
				double f = 1.0 - d / radius;
				e.hurtServer(level, level.damageSources().explosion(source, null), (float) (8 + 50 * f * f));
				e.igniteForSeconds((float) (6 + 14 * f));
				e.push(out.normalize().scale(2.6 * f).add(0, 0.5 + 0.6 * f, 0));
				e.hurtMarked = true;
				e.addEffect(new MobEffectInstance(MobEffects.NAUSEA, (int) (100 + 160 * f), 0));
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, (int) (60 + 100 * f), 1));
			}
			scorch(level, BlockPos.containing(pos), 30, level.getRandom(), 0.45F);
		});
	}

	/** Blackens and ignites the surface around a blast. */
	static void scorch(ServerLevel level, BlockPos center, int radius, RandomSource random, float fireChance) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d > radius || random.nextFloat() > 1.0 - d / radius * 0.6) {
					continue;
				}
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				if (!level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				BlockPos ground = new BlockPos(x, y, z);
				BlockState state = level.getBlockState(ground);
				if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)) {
					level.setBlock(ground, random.nextFloat() < 0.3F ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState(), 3);
				} else if (state.is(Blocks.STONE) && random.nextFloat() < 0.4F) {
					level.setBlock(ground, Blocks.BASALT.defaultBlockState(), 3);
				}
				BlockPos above = ground.above();
				if (random.nextFloat() < fireChance * (1.0F - (float) (d / radius) * 0.5F) && level.getBlockState(above).isAir()) {
					level.setBlock(above, BaseFireBlock.getState(level, above), 3);
				}
			}
		}
	}

	public static void tick(ServerLevel level) {
		if (!SCHEDULED.isEmpty()) {
			List<Scheduled> due = new ArrayList<>();
			Iterator<Scheduled> it = SCHEDULED.iterator();
			while (it.hasNext()) {
				Scheduled s = it.next();
				if (s.level() == level && --s.delay()[0] <= 0) {
					it.remove();
					due.add(s);
				}
			}
			due.forEach(s -> s.action().run());
		}
		if (NUKES.isEmpty()) {
			return;
		}
		Iterator<NukeDetonation> it = NUKES.iterator();
		while (it.hasNext()) {
			NukeDetonation nuke = it.next();
			if (nuke.level() == level && nuke.tick()) {
				it.remove();
			}
		}
	}
}
