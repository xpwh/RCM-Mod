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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import de.rcm.ballistic.ModRegistry;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A nuclear detonation carved into the world over several seconds so the server doesn't freeze.
 * <ul>
 *   <li>tick 0: blast wave hits entities (lethal core, heavy damage + fire + knockback further out)</li>
 *   <li>then: crater carved slice by slice from the center outwards, with a scorched glassy floor,
 *       an ejecta rim and a burnt blast zone (leaves stripped, glass shattered, fires)</li>
 *   <li>afterwards: radioactive fallout around ground zero, and a fallout plume carried off by the
 *       high-altitude wind that rains radioactive dust onto the land downwind</li>
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
	private final RadiationManager.Plume plume;

	public NukeDetonation(ServerLevel level, Vec3 pos, @Nullable Entity source, Yield yield) {
		this.craterRadius = yield.craterRadius;
		this.blastRadius = yield.craterRadius * 2.6;
		this.damageRadius = yield.craterRadius * 3.0;
		this.falloutTicks = yield.falloutTicks;
		// contamination lingers far longer than the acute fallout phase
		RadiationManager.addZone(level, pos, yield.craterRadius * 3.2, yield.craterRadius * 400.0, yield.falloutTicks * 12L);
		// soot from the firestorm rises into the stratosphere: several bursts bring on a nuclear winter
		NuclearWinter.add(level, switch (yield) {
			case TACTICAL -> 0.05;
			case FISSION -> 0.1;
			case THERMONUCLEAR -> 0.2;
			case TSAR -> 0.35;
		});
		// the mushroom cloud drifts off with the wind at its own height and rains out on the way
		double cloudY = pos.y + yield.craterRadius * 3.5;
		this.plume = RadiationManager.addPlume(level, pos, Wind.direction(level.getGameTime(), cloudY),
			yield.craterRadius * 14.0, yield.craterRadius * 2.2, yield.craterRadius * 110.0, yield.falloutTicks * 12L);
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
		if (this.falloutAge % 5 == 0) {
			this.depositFallout();
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

		// Molten, glassy crater floor; sand fused into trinitite.
		for (int i = 1; i <= 2; i++) {
			pos.set(x, floorY - i, z);
			BlockState state = this.level.getBlockState(pos);
			if (!state.isAir() && breakable(state, pos) && !state.hasBlockEntity()) {
				BlockState floor = state.is(BlockTags.SAND) || state.is(Blocks.SANDSTONE) ? ModRegistry.TRINITITE.defaultBlockState() : this.floorBlock(d / r, i);
				this.level.setBlock(pos, floor, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
			}
		}
		pos.set(x, floorY, z);
		if (this.random.nextFloat() < 0.07F && this.level.getBlockState(pos).isAir()) {
			this.level.setBlock(pos, BaseFireBlock.getState(this.level, pos), Block.UPDATE_ALL);
		}
	}

	/**
	 * Crater floor: rock molten by the fireball, still glowing in the middle and set into black glass
	 * further out, over shattered, baked rock ({@code layer} 1 is the surface).
	 */
	private BlockState floorBlock(double relative, int layer) {
		float roll = this.random.nextFloat();
		if (layer == 1) {
			if (relative < 0.5 && roll < 0.35F) {
				return ModRegistry.MOLTEN_ROCK.defaultBlockState();
			}
			if (roll < 0.7F - relative * 0.3) {
				return ModRegistry.CRATER_GLASS.defaultBlockState();
			}
			return roll < 0.85F ? ModRegistry.SCORCHED_EARTH.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
		}
		if (roll < 0.35F) {
			return ModRegistry.CRATER_GLASS.defaultBlockState();
		}
		if (roll < 0.6F) {
			return Blocks.BASALT.defaultBlockState();
		}
		return roll < 0.8F ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.TUFF.defaultBlockState();
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
			// the overturned rim: excavated earth and rock, burnt on top, with chunks of fused glass
			BlockState debris = roll < 0.3F
				? ModRegistry.SCORCHED_EARTH.defaultBlockState()
				: roll < 0.5F ? Blocks.COARSE_DIRT.defaultBlockState()
				: roll < 0.7F ? Blocks.GRAVEL.defaultBlockState()
				: roll < 0.85F ? Blocks.COBBLESTONE.defaultBlockState()
				: roll < 0.93F ? ModRegistry.CRATER_GLASS.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
			if (i == lip - 1 && this.random.nextFloat() < 0.15F) {
				debris = ModRegistry.SMOLDERING_EARTH.defaultBlockState();
			}
			this.level.setBlock(pos, debris, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
	}

	private void scorchSurface(int x, int z, double d, double r, BlockPos.MutableBlockPos pos) {
		double intensity = 1.0 - (d - r) / (this.blastRadius - r); // 1 at the rim, 0 at the edge
		Wasteland.scorchColumn(this.level, x, z, intensity, 0.22F, true, this.random);
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

	/**
	 * Radioactive dust raining out of the drifting cloud: settles as a fallout layer on the ground
	 * the front has already passed, thickest close to ground zero and along the plume's centre line.
	 */
	private void depositFallout() {
		long now = this.level.getGameTime();
		double front = this.plume.front(now);
		int tries = 6 + this.craterRadius / 3;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < tries; i++) {
			double along = -this.craterRadius + this.random.nextDouble() * (front + this.craterRadius);
			double halfWidth = this.plume.width() * (0.35 + 0.65 * Math.max(0.0, along) / this.plume.length());
			double across = this.random.nextGaussian() * halfWidth * 0.5;
			double x = this.exact.x + this.plume.dirX() * along - this.plume.dirZ() * across;
			double z = this.exact.z + this.plume.dirZ() * along + this.plume.dirX() * across;
			double strength = this.plume.strength(x, z, now);
			if (this.random.nextDouble() > strength * 1.5 + 0.1) {
				continue;
			}
			int bx = Mth.floor(x);
			int bz = Mth.floor(z);
			if (!this.level.hasChunk(bx >> 4, bz >> 4)) {
				continue;
			}
			pos.set(bx, this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz), bz);
			BlockState at = this.level.getBlockState(pos);
			if (!(at.isAir() || at.is(ModRegistry.ASH)) || !at.getFluidState().isEmpty()) {
				continue;
			}
			BlockState fallout = ModRegistry.FALLOUT.defaultBlockState();
			if (fallout.canSurvive(this.level, pos)) {
				this.level.setBlock(pos, fallout, Block.UPDATE_CLIENTS);
			}
		}
	}

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
