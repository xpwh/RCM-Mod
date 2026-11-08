package de.rcm.ballistic.entity;

import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.block.AirDefenseBlockEntity;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.explosion.DetonationManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Hit-to-kill surface-to-air interceptor (PAC-3 style). It flies towards the predicted intercept
 * point, which is recomputed every tick from the target's known flight path, and detonates its
 * fragmentation warhead at the point of closest approach. Whether the kill succeeds is decided by the
 * single-shot kill probability against that kind of target.
 */
public class InterceptorEntity extends Entity {
	private static final EntityDataAccessor<Vector3fc> DATA_DIR = SynchedEntityData.defineId(InterceptorEntity.class, EntityDataSerializers.VECTOR3);
	/** What it was fired from, for the launch effect: 0 other, 1 Patriot canister, 2 Iron Dome cell. */
	private static final EntityDataAccessor<Integer> DATA_LAUNCHER = SynchedEntityData.defineId(InterceptorEntity.class, EntityDataSerializers.INT);
	public static final int LAUNCHER_OTHER = 0;
	public static final int LAUNCHER_CANISTER = 1;
	public static final int LAUNCHER_CELL = 2;
	/** Client: the launch (cover bursting, blast, smoke) has been shown. */
	public boolean clientLaunchShown;
	public static final double START_SPEED = 1.2;
	public static final double ACCEL = 0.9;
	public static final double MAX_SPEED = 11.0;
	private static final double FUSE_RANGE = 4.5;
	private static final int LIFETIME = 200;
	/** How far ahead the guidance computer looks for an intercept. */
	public static final int MAX_LOOKAHEAD = 160;

	private int targetId = -1;
	private double speed = START_SPEED;
	private float killProbability = 0.8F;
	private @Nullable BlockPos battery;

	public InterceptorEntity(EntityType<? extends InterceptorEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	public static @Nullable InterceptorEntity launch(ServerLevel level, BlockPos battery, Vec3 from, AirThreat target, float killProbability) {
		return launch(level, battery, from, target, killProbability, null);
	}

	/** As above, leaving the launcher along {@code launcherDir} (the canister axis) if given. */
	public static @Nullable InterceptorEntity launch(
		ServerLevel level, BlockPos battery, Vec3 from, AirThreat target, float killProbability, @Nullable Vec3 launcherDir
	) {
		InterceptorEntity e = ModRegistry.INTERCEPTOR.create(level, EntitySpawnReason.TRIGGERED);
		if (e == null) {
			return null;
		}
		e.setPos(from);
		e.targetId = target.asEntity().getId();
		e.killProbability = killProbability;
		e.battery = battery.immutable();
		Vec3 aim = solveIntercept(from, START_SPEED, target);
		// the launcher is elevated towards the target, the rest is done by thrust vectoring
		Vec3 d = aim.subtract(from);
		Vec3 initial = launcherDir != null ? launcherDir.normalize() : new Vec3(d.x, Math.max(Math.abs(d.y), Math.hypot(d.x, d.z)) * 1.2, d.z).normalize();
		e.setDir(initial);
		var launcher = level.getBlockState(battery);
		e.entityData.set(DATA_LAUNCHER, launcher.is(ModRegistry.AIR_DEFENSE) ? LAUNCHER_CANISTER : launcher.is(ModRegistry.IRON_DOME) ? LAUNCHER_CELL : LAUNCHER_OTHER);
		level.addFreshEntity(e);
		return e;
	}

	/** Distance an interceptor flying at {@code speed} covers in {@code ticks} ticks while accelerating. */
	public static double reach(double speed, int ticks) {
		double sum = 0.0;
		double s = speed;
		for (int i = 0; i < ticks; i++) {
			s = Math.min(MAX_SPEED, s + ACCEL);
			sum += s;
		}
		return sum;
	}

	/**
	 * First point on the target's predicted path that the interceptor can reach in time. Falls back
	 * to the point that comes closest if there is none.
	 */
	public static Vec3 solveIntercept(Vec3 from, double speed, AirThreat target) {
		Vec3 best = target.aimPoint(0);
		double bestGap = Double.MAX_VALUE;
		double reach = 0.0;
		double s = speed;
		for (int k = 1; k <= MAX_LOOKAHEAD; k++) {
			s = Math.min(MAX_SPEED, s + ACCEL);
			reach += s;
			Vec3 p = target.aimPoint(k);
			double gap = p.distanceTo(from) - reach;
			if (gap <= 0) {
				return p;
			}
			if (gap < bestGap) {
				bestGap = gap;
				best = p;
			}
		}
		return best;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_DIR, new Vector3f(0, 1, 0));
		builder.define(DATA_LAUNCHER, LAUNCHER_OTHER);
	}

	public int getLauncher() {
		return this.entityData.get(DATA_LAUNCHER);
	}

	public Vec3 getDir() {
		Vector3fc v = this.entityData.get(DATA_DIR);
		return new Vec3(v.x(), v.y(), v.z());
	}

	private void setDir(Vec3 d) {
		this.entityData.set(DATA_DIR, new Vector3f((float) d.x, (float) d.y, (float) d.z));
	}

	@Override
	public void tick() {
		super.tick();
		if (!(this.level() instanceof ServerLevel level)) {
			ClientHooks.projectileClientTick.accept(this);
			return;
		}
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.blockPosition()), 2);
		Vec3 pos = this.position();
		Vec3 dir = this.getDir();
		this.speed = Math.min(MAX_SPEED, this.speed + ACCEL);

		Entity e = level.getEntity(this.targetId);
		if (!(e instanceof AirThreat threat) || !e.isAlive() || !threat.isActiveThreat()) {
			if (this.tickCount > 6) {
				DetonationManager.interceptorBurst(level, pos, this); // target gone: self-destruct
				this.discard();
				return;
			}
		} else {
			Vec3 aim = solveIntercept(pos, this.speed, threat);
			Vec3 desired = aim.subtract(pos).normalize();
			// boost phase: the missile pitches over slowly, then it can pull hard
			double turn = this.tickCount < 4 ? 0.12 : 0.5;
			dir = dir.add(desired.subtract(dir).scale(turn)).normalize();

			Vec3 next = pos.add(dir.scale(this.speed));
			// proximity fuse: closest approach between our path this tick and the target's path around now
			// (both can be 10+ blocks per tick, and entities tick in no fixed order)
			double[] approach = closestApproach(pos, next, threat.aimPoint(-1), threat.aimPoint(1));
			if (approach[0] < FUSE_RANGE) {
				Vec3 burst = pos.add(next.subtract(pos).scale(approach[1]));
				this.setPos(burst);
				boolean kill = level.getRandom().nextFloat() < this.killProbability;
				Component name = Component.translatable(threat.nameKey());
				if (kill) {
					threat.destroyByInterceptor(level);
				}
				DetonationManager.interceptorBurst(level, burst, this);
				this.report(level, kill, name);
				this.discard();
				return;
			}
			this.setDir(dir);
			this.setPos(next);
			if (this.tickCount > LIFETIME) {
				DetonationManager.interceptorBurst(level, next, this);
				this.discard();
			}
			return;
		}
		this.setDir(dir);
		this.setPos(pos.add(dir.scale(this.speed)));
	}

	/** Shortest distance between segments p0-p1 and q0-q1, and where on p0-p1 it occurs (0..1). */
	private static double[] closestApproach(Vec3 p0, Vec3 p1, Vec3 q0, Vec3 q1) {
		Vec3 d1 = p1.subtract(p0);
		Vec3 d2 = q1.subtract(q0);
		Vec3 r = p0.subtract(q0);
		double a = d1.dot(d1);
		double e = d2.dot(d2);
		double f = d2.dot(r);
		double s;
		double t;
		if (a < 1.0E-9 && e < 1.0E-9) {
			return new double[] {r.length(), 0.0};
		}
		if (a < 1.0E-9) {
			s = 0.0;
			t = Math.max(0.0, Math.min(1.0, f / e));
		} else {
			double c = d1.dot(r);
			if (e < 1.0E-9) {
				t = 0.0;
				s = Math.max(0.0, Math.min(1.0, -c / a));
			} else {
				double b = d1.dot(d2);
				double denom = a * e - b * b;
				s = denom > 1.0E-9 ? Math.max(0.0, Math.min(1.0, (b * f - c * e) / denom)) : 0.0;
				t = (b * s + f) / e;
				if (t < 0.0) {
					t = 0.0;
					s = Math.max(0.0, Math.min(1.0, -c / a));
				} else if (t > 1.0) {
					t = 1.0;
					s = Math.max(0.0, Math.min(1.0, (b - c) / a));
				}
			}
		}
		Vec3 cp = p0.add(d1.scale(s));
		Vec3 cq = q0.add(d2.scale(t));
		return new double[] {cp.distanceTo(cq), s};
	}

	private void report(ServerLevel level, boolean kill, Component name) {
		if (this.battery == null) {
			return;
		}
		if (level.getBlockEntity(this.battery) instanceof AirDefenseBlockEntity be) {
			be.onEngagementResult(kill);
		}
		Component msg = kill
			? Component.literal("✔ ").append(Component.translatable("message.ballisticmissiles.intercept_hit", name)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
			: Component.literal("✘ ").append(Component.translatable("message.ballisticmissiles.intercept_miss", name)).withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
		Vec3 b = Vec3.atCenterOf(this.battery);
		for (ServerPlayer player : level.players()) {
			if (player.position().distanceToSqr(b) < 200 * 200) {
				player.displayClientMessage(msg, true);
			}
		}
	}

	@Override
	public void remove(Entity.RemovalReason reason) {
		if (this.level() instanceof ServerLevel level && level.getEntity(this.targetId) instanceof AirThreat threat) {
			threat.setEngagements(Math.max(0, threat.getEngagements() - 1));
		}
		super.remove(reason);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return true;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}
}
