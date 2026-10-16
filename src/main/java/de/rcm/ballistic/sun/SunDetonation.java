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
	private static final float PULL_REACH = 110.0F;
	private static final float HEAT_REACH = SunTimeline.RADIUS * 3.2F;

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
		if (d > SunTimeline.NOVA && d < SunTimeline.NOVA + 50) {
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
			Vec3 v = to.scale(1.0 / dist).scale(0.14 * f);
			e.setDeltaMovement(e.getDeltaMovement().add(v.x, v.y + 0.02 * f, v.z));
			e.hurtMarked = true;
			e.fallDistance = 0.0;
		}
	}

	/** Eats out the sphere it fills, shell by shell as it grows: what it swallows is gone. */
	private void carve(float radius) {
		int to = Mth.floor(radius);
		if (to <= this.carved) {
			return;
		}
		int from = this.carved + 1;
		// at most a few shells a tick, so the server keeps up when it grows fast
		to = Math.min(to, from + 2);
		double inner = Math.max(0, from - 1) + 0.5;
		double outer = to + 0.5;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -to; dx <= to; dx++) {
			for (int dy = -to; dy <= to; dy++) {
				for (int dz = -to; dz <= to; dz++) {
					int d2 = dx * dx + dy * dy + dz * dz;
					if (d2 <= inner * inner || d2 > outer * outer) {
						continue;
					}
					pos.set(this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz);
					if (pos.getY() < this.level.getMinY() || pos.getY() > this.level.getMaxY() || !this.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
						continue;
					}
					BlockState state = this.level.getBlockState(pos);
					if (state.isAir() || state.getDestroySpeed(this.level, pos) < 0.0F) {
						continue;
					}
					CraterLog.set(this.level, pos, Blocks.AIR.defaultBlockState(), QUIET);
				}
			}
		}
		this.carved = to;
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
		int reach = Mth.ceil(SunTimeline.RADIUS + 8.0F);
		if (this.meltSlice == Integer.MIN_VALUE) {
			this.meltSlice = -reach;
		}
		if (this.meltSlice > reach) {
			return;
		}
		RandomSource random = this.level.getRandom();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		float r = SunTimeline.RADIUS;
		for (int n = 0; n < 4 && this.meltSlice <= reach; n++, this.meltSlice++) {
			int dx = this.meltSlice;
			for (int dy = -reach; dy <= reach; dy++) {
				for (int dz = -reach; dz <= reach; dz++) {
					double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (dist <= r - 0.5 || dist > r + 8.0) {
						continue;
					}
					pos.set(this.origin.getX() + dx, this.origin.getY() + dy, this.origin.getZ() + dz);
					if (pos.getY() < this.level.getMinY() || pos.getY() > this.level.getMaxY() || !this.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
						continue;
					}
					BlockState state = this.level.getBlockState(pos);
					if (state.isAir() || state.getDestroySpeed(this.level, pos) < 0.0F) {
						continue;
					}
					if (!state.getFluidState().isEmpty()) {
						CraterLog.set(this.level, pos, Blocks.AIR.defaultBlockState(), QUIET); // boiled off
						continue;
					}
					if (state.hasBlockEntity()) {
						continue;
					}
					// a ragged edge: the melt reaches further in some places than in others
					double edge = r + 1.6 + random.nextDouble() * 1.2;
					double glass = r + 3.2 + random.nextDouble() * 1.8;
					if (dist < edge) {
						CraterLog.set(this.level, pos, ModRegistry.MOLTEN_ROCK.defaultBlockState(), QUIET);
					} else if (dist < glass) {
						CraterLog.set(this.level, pos, ModRegistry.CRATER_GLASS.defaultBlockState(), QUIET);
					} else if (this.level.getBlockState(pos.above()).isAir() && random.nextFloat() < 0.8F) {
						CraterLog.set(this.level, pos, (random.nextBoolean() ? ModRegistry.SMOLDERING_EARTH : ModRegistry.SCORCHED_EARTH).defaultBlockState(), QUIET);
					}
				}
			}
		}
	}

	/** The land round it, burnt out to well beyond the crater: a slice of columns a tick. */
	private void scorch() {
		int reach = Mth.ceil(HEAT_REACH);
		if (this.scorchSlice == Integer.MIN_VALUE) {
			this.scorchSlice = -reach;
		}
		RandomSource random = this.level.getRandom();
		for (int n = 0; n < 6 && this.scorchSlice <= reach; n++, this.scorchSlice++) {
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
		if (this.griefing) {
			FlyingDebris.launch(this.level, this.centre.subtract(0.0, SunTimeline.RADIUS * 0.2, 0.0), SunTimeline.RADIUS * 1.6, 260, 2.6);
			BlastPhysics.shatter(this.level, this.centre, SunTimeline.RADIUS, SunTimeline.NOVA_REACH * 1.6, 1400, 1.0F);
			this.ejecta();
		}
	}

	/** Melt flung out of the crater's rim, coming down glowing all round it. */
	private void ejecta() {
		RandomSource random = this.level.getRandom();
		for (int i = 0; i < 70; i++) {
			double a = random.nextDouble() * Mth.TWO_PI;
			Vec3 at = this.centre.add(Math.cos(a) * SunTimeline.RADIUS * 0.9, -SunTimeline.RADIUS * 0.3, Math.sin(a) * SunTimeline.RADIUS * 0.9);
			BlockPos p = BlockPos.containing(at);
			if (!this.level.isLoaded(p) || !this.level.getBlockState(p).isAir()) {
				continue;
			}
			FallingBlockEntity melt = FallingBlockEntity.fall(this.level, p, (random.nextInt(3) == 0 ? ModRegistry.CRATER_GLASS : ModRegistry.MOLTEN_ROCK).defaultBlockState());
			double s = 1.4 + random.nextDouble() * 2.2;
			melt.setDeltaMovement(Math.cos(a) * s, 1.2 + random.nextDouble() * 1.6, Math.sin(a) * s);
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
			double push = 0.6 + 4.2 * f * f;
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
