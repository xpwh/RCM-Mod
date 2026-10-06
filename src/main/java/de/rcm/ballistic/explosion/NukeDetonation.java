package de.rcm.ballistic.explosion;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A nuclear detonation carved into the world over several seconds so the server doesn't freeze.
 * <ul>
 *   <li>tick 0: blast wave hits entities (lethal core, heavy damage + fire + knockback further out)</li>
 *   <li>then: crater carved slice by slice from the center outwards, with a scorched glassy floor,
 *       an ejecta rim and a burnt blast zone (leaves stripped, glass shattered, fires)</li>
 *   <li>afterwards: radioactive fallout around ground zero for one minute</li>
 * </ul>
 */
public class NukeDetonation {
	public enum Yield {
		/** Small MIRV warhead. */
		TACTICAL(20, 800),
		/** Classic fission warhead: big, but not world-ending. */
		FISSION(36, 1200),
		/** Hydrogen bomb: much larger crater, blast zone and fallout. */
		THERMONUCLEAR(58, 2400),
		/** Tsar Bomba: the biggest of them all. */
		TSAR(80, 3600);

		final int craterRadius;
		final int falloutTicks;

		Yield(int craterRadius, int falloutTicks) {
			this.craterRadius = craterRadius;
			this.falloutTicks = falloutTicks;
		}
	}

	private static final int SLICES_PER_TICK = 2;
	private static final int MAX_WAIT_FOR_CHUNKS = 60;

	private final int craterRadius;
	private final double blastRadius;
	private final double damageRadius;
	private final int falloutTicks;
	private final ServerLevel level;
	private final BlockPos center;
	private final Vec3 exact;
	private final @Nullable Entity source;
	private final RandomSource random;
	private final int outer;
	private final double phaseA;
	private final double phaseB;
	private final double phaseC;

	private int age;
	/** How far the shock front has travelled. */
	private double shockRadius;
	private final Set<Integer> shocked = new HashSet<>();
	private int nextSlice;
	private int falloutAge = -1;

	public NukeDetonation(ServerLevel level, Vec3 pos, @Nullable Entity source, Yield yield) {
		this.craterRadius = yield.craterRadius;
		this.blastRadius = yield.craterRadius * 2.6;
		this.damageRadius = yield.craterRadius * 3.0;
		this.falloutTicks = yield.falloutTicks;
		// contamination lingers far longer than the acute fallout phase
		RadiationManager.addZone(level, pos, yield.craterRadius * 3.2, yield.craterRadius * 400.0, yield.falloutTicks * 12L);
		this.level = level;
		this.exact = pos;
		this.center = BlockPos.containing(pos);
		this.source = source;
		this.random = level.getRandom();
		this.outer = Mth.ceil(this.blastRadius);
		this.phaseA = this.random.nextDouble() * Math.PI * 2;
		this.phaseB = this.random.nextDouble() * Math.PI * 2;
		this.phaseC = this.random.nextDouble() * Math.PI * 2;
	}

	public ServerLevel level() {
		return this.level;
	}

	/** @return true when finished */
	public boolean tick() {
		if (this.age % 20 == 0) {
			this.level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(this.center), Mth.ceil(this.blastRadius / 16.0) + 1);
		}
		if (this.shockRadius <= this.damageRadius) {
			this.blastWave();
		}
		this.age++;

		if (this.falloutAge < 0) {
			if (!this.areaLoaded() && this.age < MAX_WAIT_FOR_CHUNKS) {
				return false;
			}
			for (int i = 0; i < SLICES_PER_TICK && this.falloutAge < 0; i++) {
				this.carveNextSlice();
			}
			return false;
		}

		if (this.falloutAge % 20 == 0) {
			this.fallout();
		}
		return ++this.falloutAge > this.falloutTicks;
	}

	private boolean areaLoaded() {
		int r = Mth.ceil(this.blastRadius / 16.0);
		ChunkPos c = new ChunkPos(this.center);
		for (int dx = -r; dx <= r; dx += r) {
			for (int dz = -r; dz <= r; dz += r) {
				if (!this.level.hasChunk(c.x + dx, c.z + dz)) {
					return false;
				}
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ blast wave

	/**
	 * The shock front expands from the fireball: supersonic close in, slowing towards the speed of
	 * sound (17 blocks per tick) further out. Each entity is hit once, when the front reaches it.
	 */
	private void blastWave() {
		double inner = this.shockRadius;
		double speed = 17.15 + 60.0 * Math.exp(-this.age / 6.0);
		this.shockRadius = this.age == 0 ? Math.max(this.craterRadius * 0.85, speed) : this.shockRadius + speed;
		double outer = Math.min(this.shockRadius, this.damageRadius);
		AABB box = new AABB(this.center).inflate(outer);
		for (Entity entity : this.level.getEntities((Entity) null, box, e -> true)) {
			double d = entity.position().distanceTo(this.exact);
			if (d > outer || d < inner && this.age > 0 || !this.shocked.add(entity.getId())) {
				continue;
			}
			if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
				if (d < this.blastRadius) {
					entity.discard();
				}
				continue;
			}
			if (!(entity instanceof LivingEntity living)) {
				continue;
			}
			// earth and concrete between the fireball and the target soak up the blast
			int cover = this.shielding(living);
			double core = this.craterRadius * 0.85;
			if (cover >= 12 && d > this.craterRadius * 0.5) {
				living.hurtServer(this.level, this.level.damageSources().explosion(this.source, null), 2.0F);
				continue; // safe in a bunker: the ground shakes, nothing more
			}
			if (d < core && cover < 6) {
				living.hurtServer(this.level, this.level.damageSources().explosion(this.source, null), 10000.0F);
				continue;
			}
			double factor = Math.min(1.0, 1.0 - (d - core) / (this.damageRadius - core));
			if (cover >= 3) {
				factor *= 0.25;
			}
			float damage = (float) (6.0 + 90.0 * Math.pow(factor, 1.4));
			living.hurtServer(this.level, this.level.damageSources().explosion(this.source, null), damage);
			living.igniteForSeconds((float) (4.0 + 12.0 * factor));

			Vec3 push = entity.position().subtract(this.exact);
			push = new Vec3(push.x, 0, push.z).normalize().scale(3.5 * factor).add(0, 0.6 + 1.0 * factor, 0);
			living.push(push.x, push.y, push.z);
			living.hurtMarked = true;
			living.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, (int) (60 + 100 * factor), 0));
			living.addEffect(new MobEffectInstance(MobEffects.NAUSEA, (int) (120 + 200 * factor), 0));
		}
	}

	// ------------------------------------------------------------------ crater

	/** Slices are processed in order 0, +1, -1, +2, -2 ... so the crater grows from the middle. */
	private void carveNextSlice() {
		int n = this.nextSlice++;
		int dx = (n % 2 == 0) ? n / 2 : -(n + 1) / 2;
		if (Math.abs(dx) > this.outer) {
			this.falloutAge = 0;
			return;
		}
		int span = (int) Math.sqrt(Math.max(0, (double) this.outer * this.outer - (double) dx * dx));
		for (int dz = -span; dz <= span; dz++) {
			this.processColumn(dx, dz);
		}
	}

	private double craterRadiusAt(int dx, int dz) {
		double angle = Math.atan2(dz, dx);
		double wobble = 0.07 * Math.sin(angle * 3 + this.phaseA) + 0.05 * Math.sin(angle * 7 + this.phaseB) + 0.03 * Math.sin(angle * 13 + this.phaseC);
		return this.craterRadius * (1.0 + wobble);
	}

	private void processColumn(int dx, int dz) {
		int x = this.center.getX() + dx;
		int z = this.center.getZ() + dz;
		if (!this.level.hasChunk(x >> 4, z >> 4)) {
			return;
		}
		double d = Math.sqrt(dx * dx + dz * dz);
		double r = this.craterRadiusAt(dx, dz);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		if (d < r) {
			this.carveCraterColumn(x, z, d, r, pos);
		} else if (d < r * 1.4) {
			this.scorchSurface(x, z, d, r, pos);
			this.placeRim(x, z, d, r, pos);
		} else if (d < this.blastRadius) {
			this.scorchSurface(x, z, d, r, pos);
		}
	}

	private void carveCraterColumn(int x, int z, double d, double r, BlockPos.MutableBlockPos pos) {
		double f = Math.sqrt(1.0 - (d / r) * (d / r));
		int floorY = this.center.getY() - (int) Math.round(this.craterRadius * 0.5 * f + this.random.nextFloat());
		int topY = Math.min(this.level.getMaxY(), this.center.getY() + this.craterRadius + 12);
		floorY = Math.max(floorY, this.level.getMinY() + 1);
		int surface = this.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		topY = Math.min(topY, Math.max(surface, floorY));

		boolean core = d < r * 0.35;
		for (int y = floorY; y <= topY; y++) {
			pos.set(x, y, z);
			BlockState state = this.level.getBlockState(pos);
			if (state.isAir() || !breakable(state, pos) || !core && isBunker(state)) {
				continue;
			}
			this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
		}

		// Molten, glassy crater floor.
		for (int i = 1; i <= 2; i++) {
			pos.set(x, floorY - i, z);
			BlockState state = this.level.getBlockState(pos);
			if (!state.isAir() && breakable(state, pos)) {
				this.level.setBlock(pos, this.floorBlock(d / r), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}
		pos.set(x, floorY, z);
		if (this.random.nextFloat() < 0.07F && this.level.getBlockState(pos).isAir()) {
			this.level.setBlock(pos, BaseFireBlock.getState(this.level, pos), Block.UPDATE_ALL);
		}
	}

	private BlockState floorBlock(double relative) {
		float roll = this.random.nextFloat();
		if (relative < 0.45 && roll < 0.18F) {
			return Blocks.MAGMA_BLOCK.defaultBlockState();
		}
		if (roll < 0.35F) {
			return Blocks.BLACKSTONE.defaultBlockState();
		}
		if (roll < 0.55F) {
			return Blocks.BASALT.defaultBlockState();
		}
		if (roll < 0.68F) {
			return Blocks.TUFF.defaultBlockState();
		}
		if (roll < 0.76F) {
			return Blocks.OBSIDIAN.defaultBlockState();
		}
		if (roll < 0.86F) {
			return Blocks.COAL_BLOCK.defaultBlockState();
		}
		return Blocks.NETHERRACK.defaultBlockState();
	}

	private void placeRim(int x, int z, double d, double r, BlockPos.MutableBlockPos pos) {
		double t = 1.0 - (d - r) / (r * 0.4);
		int lip = (int) Math.round(5.0 * t * (0.6 + 0.8 * this.random.nextFloat()));
		int y = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		for (int i = 0; i < lip; i++) {
			pos.set(x, y + i, z);
			if (!this.level.getBlockState(pos).canBeReplaced()) {
				break;
			}
			float roll = this.random.nextFloat();
			BlockState debris = roll < 0.35F
				? Blocks.COARSE_DIRT.defaultBlockState()
				: roll < 0.6F ? Blocks.GRAVEL.defaultBlockState() : roll < 0.8F ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
			this.level.setBlock(pos, debris, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}

	private void scorchSurface(int x, int z, double d, double r, BlockPos.MutableBlockPos pos) {
		double intensity = 1.0 - (d - r) / (this.blastRadius - r); // 1 at the rim, 0 at the edge
		int top = this.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

		for (int y = top; y > top - 24 && y > this.level.getMinY(); y--) {
			pos.set(x, y, z);
			BlockState state = this.level.getBlockState(pos);
			if (state.isAir()) {
				continue;
			}
			if (!state.getFluidState().isEmpty()) {
				return; // water surface: the blast skims over it
			}
			if (state.is(BlockTags.LEAVES)) {
				if (this.random.nextDouble() < 0.55 + 0.45 * intensity) {
					this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
				}
				continue;
			}
			if (state.is(BlockTags.LOGS)) {
				if (this.random.nextDouble() < intensity * 0.8) {
					this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
				} else if (this.random.nextDouble() < 0.3) {
					this.igniteAbove(pos);
				}
				continue;
			}
			if (state.canBeReplaced() || state.is(BlockTags.FLOWERS) || state.is(Blocks.SNOW_BLOCK)) {
				this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
				continue;
			}
			if (state.is(BlockTags.IMPERMEABLE) || state.getBlock() instanceof IronBarsBlock) {
				this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags); // shattered glass
				continue;
			}

			// First real ground block: scorch it and maybe start a fire.
			if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM) || state.is(Blocks.MOSS_BLOCK)) {
				if (this.random.nextDouble() < 0.4 + 0.6 * intensity) {
					BlockState burnt = this.random.nextDouble() < intensity * 0.5 ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState();
					this.level.setBlock(pos, burnt, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			} else if (state.is(BlockTags.SAND) && this.random.nextDouble() < intensity * 0.45) {
				this.level.setBlock(pos, Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			} else if (state.is(Blocks.STONE) && this.random.nextDouble() < intensity * 0.5) {
				this.level.setBlock(pos, Blocks.BASALT.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}

			if (this.random.nextDouble() < 0.03 + 0.17 * intensity || (state.ignitedByLava() && this.random.nextDouble() < 0.5)) {
				this.igniteAbove(pos);
			}
			return;
		}
	}

	private void igniteAbove(BlockPos pos) {
		BlockPos above = pos.above();
		if (this.level.getBlockState(above).isAir()) {
			this.level.setBlock(above, BaseFireBlock.getState(this.level, above), Block.UPDATE_ALL);
		}
	}

	/** Blast-rated construction (reinforced concrete, blast doors) survives everywhere but the core. */
	private static boolean isBunker(BlockState state) {
		return state.getBlock().getExplosionResistance() >= 3000.0F;
	}

	/**
	 * Cover between ground zero and the entity: counts solid blocks along the line of sight, with
	 * blast-rated blocks worth four ordinary ones.
	 */
	private int shielding(LivingEntity living) {
		Vec3 eye = living.getEyePosition();
		Vec3 from = this.exact.add(0, 1.0, 0);
		double len = eye.distanceTo(from);
		int steps = Math.min(400, (int) (len * 2));
		int cover = 0;
		BlockPos last = null;
		for (int i = 1; i < steps; i++) {
			BlockPos p = BlockPos.containing(from.lerp(eye, (double) i / steps));
			if (p.equals(last)) {
				continue;
			}
			last = p;
			BlockState state = this.level.getBlockState(p);
			float resistance = state.getBlock().getExplosionResistance();
			if (resistance >= 3000.0F) {
				cover += 4;
			} else if (resistance >= 6.0F) {
				cover++;
			}
			if (cover >= 12) {
				break;
			}
		}
		return cover;
	}

	private boolean breakable(BlockState state, BlockPos pos) {
		return state.getDestroySpeed(this.level, pos) >= 0.0F && state.getBlock().getExplosionResistance() < 3_000_000.0F;
	}

	// ------------------------------------------------------------------ fallout

	private void fallout() {
		double radius = this.craterRadius * 1.8;
		AABB box = new AABB(this.center).inflate(radius, 64, radius);
		for (LivingEntity living : this.level.getEntitiesOfClass(LivingEntity.class, box)) {
			if (living instanceof Player player && (player.isCreative() || player.isSpectator())) {
				continue;
			}
			double d = living.position().distanceTo(this.exact);
			if (d > radius) {
				continue;
			}
			if (RadiationManager.protection(living) >= 0.9F) {
				continue; // a full radiation suit keeps the fallout out
			}
			int amp = d < this.craterRadius ? 1 : 0;
			living.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, amp));
			living.addEffect(new MobEffectInstance(MobEffects.HUNGER, 120, 1));
			if (d < this.craterRadius) {
				living.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 120, 0));
			}
		}
	}
}
