package de.rcm.ballistic.sun;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.BlastPhysics;
import de.rcm.ballistic.explosion.CraterLog;
import de.rcm.ballistic.explosion.FlyingDebris;
import de.rcm.ballistic.explosion.Wasteland;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What the Sun Destroyer does to the world where it comes down (what it looks like is drawn on every client from
 * {@link SunTimeline}). The core collapses and draws everything in; the star it becomes grows into the ground and
 * turns all it swallows into plasma, shell by shell; the rock round it melts and runs to glass, the land for a
 * hundred metres round burns, and anyone near is cooked. Then it collapses and goes nova: the shock throws the
 * ground and everyone in reach outwards.
 */
public final class SunDetonation {
	/** How long the server has work after impact. */
	public static final int LENGTH = SunTimeline.NOVA + 60;
	private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
	private static final float PULL_REACH = SunTimeline.RADIUS * 3.2F;
	private static final float HEAT_REACH = SunTimeline.RADIUS * 2.6F;
	/** Most blocks turned over in one tick by the star's growth, so the server keeps up with a star this size. */
	private static final int BLOCKS_PER_TICK = 24000;

	private final ServerLevel level;
	private final Vec3 target;
	private final Vec3 centre;
	private final BlockPos origin;
	private final boolean griefing;
	/** How far the star has eaten in, in whole blocks from the centre. */
	private int carved = -1;
	/** Next slice (x offset) of the melt ring and of the burnt land to work on. */
	private int meltSlice = Integer.MIN_VALUE;
	private int scorchSlice = Integer.MIN_VALUE;

	public SunDetonation(ServerLevel level, Vec3 target) {
		this.level = level;
		this.target = target;
		this.centre = SunTimeline.sunCentre(target);
		this.origin = BlockPos.containing(this.centre);
		this.griefing = level.getGameRules().get(GameRules.TNT_EXPLODES);
	}

	/** {@code d}: ticks since impact. */
	public void tick(int d) {
		if (d > LENGTH) {
			return;
		}
		float radius = SunTimeline.sunRadius(d);
		if (d < SunTimeline.IMPLODE) {
			this.pull(1.0F - d / (float) SunTimeline.IMPLODE * 0.4F);
		} else if (d < SunTimeline.COLLAPSED) {
			if (this.griefing) {
				this.carve(Math.min(SunTimeline.RADIUS, radius * 0.97F));
			}
			this.burn(d, radius);
			if (d >= SunTimeline.BURN_END) {
				this.pull(0.6F * (d - SunTimeline.BURN_END) / (float) (SunTimeline.COLLAPSED - SunTimeline.BURN_END)); // the collapse draws in
			} else {
				this.pull(0.12F);
			}
		}
		if (this.griefing && d >= SunTimeline.GROWN) {
			this.melt();
		}
		if (this.griefing && d >= SunTimeline.GROWN + 30) {
			this.scorch();
		}
		if (d == SunTimeline.NOVA) {
			this.nova();
		}
		if (d > SunTimeline.NOVA && d < SunTimeline.NOVA + 150) {
			this.shock(SunTimeline.shockRadius(d - 1), SunTimeline.shockRadius(d));
		}
	}

	// ------------------------------------------------------------------ the star

	/** Everything loose round it is drawn in towards the core. */
	private void pull(float strength) {
		if (strength <= 0.0F) {
			return;
		}
		AABB box = new AABB(this.centre, this.centre).inflate(PULL_REACH);
		for (Entity e : this.level.getEntities((Entity) null, box, e -> !e.isSpectator())) {
			if (e instanceof Player p && p.isCreative() && p.getAbilities().flying) {
				continue;
			}
			Vec3 to = this.centre.subtract(e.position());
			double dist = to.length();
			if (dist < 1.0 || dist > PULL_REACH) {
				continue;
			}
			double f = strength * (1.0 - dist / PULL_REACH);
			Vec3 v = to.scale(1.0 / dist).scale(0.2 * f);
			e.setDeltaMovement(e.getDeltaMovement().add(v.x, v.y + 0.02 * f, v.z));
			e.hurtMarked = true;
			e.fallDistance = 0.0;
		}
	}

	/** Eats out the sphere it fills, shell by shell as it grows: what it swallows is gone. */
	private void carve(float radius) {
		int target = Mth.floor(radius);
		int done = 0;
		while (this.carved < target && done < BLOCKS_PER_TICK) {
			int r = this.carved + 1;
			done += this.shell(r - 0.5, r + 0.5, null);
			this.carved = r;
		}
	}

	/**
	 * Works through the shell between {@code inner} and {@code outer} blocks from the centre, column by column (only
	 * the blocks in the shell are looked at): with {@code rule} null they are vaporised, else the rule says what each
	 * becomes. Returns how many blocks were looked at.
	 */
	private int shell(double inner, double outer, java.util.function.@org.jspecify.annotations.Nullable BiFunction<BlockPos, Double, BlockState> rule) {
		int o = Mth.ceil(outer);
		int looked = 0;
		for (int dx = -o; dx <= o; dx++) {
			looked += this.shellSlice(dx, inner, outer, rule);
		}
		return looked;
	}

	private int shellSlice(int dx, double inner, double outer, java.util.function.@org.jspecify.annotations.Nullable BiFunction<BlockPos, Double, BlockState> rule) {
		int o = Mth.ceil(outer);
		int looked = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dz = -o; dz <= o; dz++) {
			double h2 = dx * dx + dz * dz;
			if (h2 > outer * outer) {
				continue;
			}
			int x = this.origin.getX() + dx;
			int z = this.origin.getZ() + dz;
			if (!this.level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}
			// the stretch of this column inside the shell: above and below the inner sphere
			int top = Mth.floor(Math.sqrt(outer * outer - h2));
			int gap = inner * inner > h2 ? Mth.ceil(Math.sqrt(inner * inner - h2)) : 0;
			for (int dy = -top; dy <= top; dy++) {
				if (gap > 0 && dy > -gap && dy < gap) {
					dy = gap - 1;
					continue;
				}
				int y = this.origin.getY() + dy;
				if (y < this.level.getMinY() || y > this.level.getMaxY()) {
					continue;
				}
				double d = Math.sqrt(h2 + dy * dy);
				if (d <= inner || d > outer) {
					continue;
				}
				looked++;
				pos.set(x, y, z);
				BlockState state = this.level.getBlockState(pos);
				if (state.isAir() || state.getDestroySpeed(this.level, pos) < 0.0F) {
					continue;
				}
				BlockState now = rule == null ? Blocks.AIR.defaultBlockState() : rule.apply(pos, d);
				if (now != null && now != state) {
					CraterLog.set(this.level, pos, now, QUIET);
				}
			}
		}
		return looked;
	}

	/** Inside it nothing lives; round it everything burns, the closer the worse. */
	private void burn(int d, float radius) {
		AABB box = new AABB(this.centre, this.centre).inflate(HEAT_REACH);
		for (LivingEntity e : this.level.getEntitiesOfClass(LivingEntity.class, box)) {
			if (e instanceof Player p && (p.isCreative() || p.isSpectator())) {
				continue;
			}
			double dist = e.position().distanceTo(this.centre);
			if (dist < radius + 1.5) {
				e.hurtServer(this.level, this.level.damageSources().inFire(), 100000.0F);
				continue;
			}
			if (radius < 2.0F || dist > HEAT_REACH) {
				continue;
			}
			double f = 1.0 - (dist - radius) / (HEAT_REACH - radius);
			if (f <= 0.0) {
				continue;
			}
			// out of its sight behind a hill or a wall, the heat is far less
			boolean seen = this.level.clip(new net.minecraft.world.level.ClipContext(this.centre, e.getEyePosition(),
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, e)).getType()
				== net.minecraft.world.phys.HitResult.Type.MISS;
			double heat = f * f * (seen ? 1.0 : 0.25);
			if (heat > 0.1) {
				e.igniteForSeconds((float) (2.0 + 8.0 * heat));
			}
			if (d % 10 == 0 && heat > 0.05) {
				e.hurtServer(this.level, this.level.damageSources().onFire(), (float) (1.0 + 9.0 * heat));
			}
		}
	}

	/** The rock round it melts: glowing melt against it, glass behind; water round it boils away. */
	private void melt() {
		float r = SunTimeline.RADIUS;
		double outer = r + 10.0;
		int reach = Mth.ceil(outer);
		if (this.meltSlice == Integer.MIN_VALUE) {
			this.meltSlice = -reach;
		}
		RandomSource random = this.level.getRandom();
		java.util.function.BiFunction<BlockPos, Double, BlockState> rule = (pos, dist) -> {
			BlockState state = this.level.getBlockState(pos);
			if (!state.getFluidState().isEmpty()) {
				return Blocks.AIR.defaultBlockState(); // boiled off
			}
			if (state.hasBlockEntity()) {
				return null;
			}
			// a ragged edge: the melt reaches further in some places than in others
			if (dist < r + 2.5 + random.nextDouble() * 2.0) {
				return ModRegistry.MOLTEN_ROCK.defaultBlockState();
			}
			if (dist < r + 5.0 + random.nextDouble() * 2.5) {
				return ModRegistry.CRATER_GLASS.defaultBlockState();
			}
			if (this.level.getBlockState(pos.above()).isAir() && random.nextFloat() < 0.8F) {
				return (random.nextBoolean() ? ModRegistry.SMOLDERING_EARTH : ModRegistry.SCORCHED_EARTH).defaultBlockState();
			}
			return null;
		};
		for (int n = 0; n < 6 && this.meltSlice <= reach; n++, this.meltSlice++) {
			this.shellSlice(this.meltSlice, r - 0.5, outer, rule);
		}
	}

	/** The land round it, burnt out to well beyond the crater: a slice of columns a tick. */
	private void scorch() {
		int reach = Mth.ceil(HEAT_REACH);
		if (this.scorchSlice == Integer.MIN_VALUE) {
			this.scorchSlice = -reach;
		}
		RandomSource random = this.level.getRandom();
		for (int n = 0; n < 8 && this.scorchSlice <= reach; n++, this.scorchSlice++) {
			int dx = this.scorchSlice;
			for (int dz = -reach; dz <= reach; dz++) {
				double dist = Math.sqrt(dx * dx + dz * dz);
				if (dist > reach || dist < SunTimeline.RADIUS * 0.8) {
					continue;
				}
				int x = Mth.floor(this.target.x) + dx;
				int z = Mth.floor(this.target.z) + dz;
				if (!this.level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				double intensity = 1.0 - (dist - SunTimeline.RADIUS) / (reach - SunTimeline.RADIUS);
				if (random.nextFloat() > 0.35 + 0.65 * intensity) {
					continue;
				}
				Wasteland.scorchColumn(this.level, x, z, Mth.clamp(intensity, 0.0, 1.0), 0.18F, false, random);
			}
		}
	}

	// ------------------------------------------------------------------ the nova

	private void nova() {
		de.rcm.ballistic.explosion.Killcam.trigger(this.level, this.centre, SunTimeline.NOVA_REACH * 0.4F);
		if (this.griefing) {
			FlyingDebris.launch(this.level, this.centre.subtract(0.0, SunTimeline.RADIUS * 0.2, 0.0), SunTimeline.RADIUS * 1.4, 700, 3.6);
			BlastPhysics.shatter(this.level, this.centre, SunTimeline.RADIUS, SunTimeline.NOVA_REACH * 1.6, 1400, 1.0F);
			this.ejecta();
		}
	}

	/** Melt flung out of the crater's rim, coming down glowing all round it. */
	private void ejecta() {
		RandomSource random = this.level.getRandom();
		for (int i = 0; i < 220; i++) {
			double a = random.nextDouble() * Mth.TWO_PI;
			Vec3 at = this.centre.add(Math.cos(a) * SunTimeline.RADIUS * 0.9, -SunTimeline.RADIUS * 0.3, Math.sin(a) * SunTimeline.RADIUS * 0.9);
			BlockPos p = BlockPos.containing(at);
			if (!this.level.isLoaded(p) || !this.level.getBlockState(p).isAir()) {
				continue;
			}
			FallingBlockEntity melt = FallingBlockEntity.fall(this.level, p, (random.nextInt(3) == 0 ? ModRegistry.CRATER_GLASS : ModRegistry.MOLTEN_ROCK).defaultBlockState());
			double s = 2.0 + random.nextDouble() * 3.5;
			melt.setDeltaMovement(Math.cos(a) * s, 1.6 + random.nextDouble() * 2.4, Math.sin(a) * s);
			melt.dropItem = false;
			melt.hurtMarked = true;
		}
	}

	/** The shock front between {@code from} and {@code to} blocks out: it hits and throws whoever it passes. */
	private void shock(float from, float to) {
		if (to <= from) {
			return;
		}
		AABB box = new AABB(this.centre, this.centre).inflate(to + 2.0);
		for (Entity e : this.level.getEntities((Entity) null, box, e -> !e.isSpectator())) {
			double dist = e.position().distanceTo(this.centre);
			if (dist <= from || dist > to) {
				continue;
			}
			if (e instanceof Player p && p.isCreative()) {
				continue;
			}
			double f = 1.0 - dist / SunTimeline.NOVA_REACH;
			Vec3 out = e.position().subtract(this.centre);
			out = new Vec3(out.x, 0.0, out.z);
			out = out.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : out.normalize();
			double push = 0.8 + 6.0 * f * f;
			e.setDeltaMovement(e.getDeltaMovement().add(out.x * push, 0.35 + 1.4 * f, out.z * push));
			e.hurtMarked = true;
			if (e instanceof LivingEntity living) {
				living.hurtServer(this.level, this.level.damageSources().explosion(null, null), (float) (3.0 + 70.0 * f * f * f));
				if (f > 0.3) {
					living.igniteForSeconds((float) (4.0 + 10.0 * f));
				}
			}
		}
	}
}
