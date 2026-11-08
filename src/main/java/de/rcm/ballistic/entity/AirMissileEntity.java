package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.explosion.DetonationManager;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * An AIM-120-style missile from a fighter's weapons bay. It drops clear for a moment, the motor lights
 * and it accelerates hard to about Mach 3, steering with proportional navigation - flying not at the
 * target but at where the target will be. A proximity fuse sets the warhead off within a few metres.
 * Flares behind its target can pull it off. Fired without a lock it flies to a point on the ground.
 */
public class AirMissileEntity extends Entity {
	public static final double LOCK_RANGE = 900.0;
	private static final double TOP_SPEED = 52.0;
	private static final double FUSE = 6.0;
	private static final int BURN = 90;
	private static final int LIFE = 500;

	private static final EntityDataAccessor<Boolean> DATA_BURNING = SynchedEntityData.defineId(AirMissileEntity.class, EntityDataSerializers.BOOLEAN);

	private @Nullable Entity launcher;
	private @Nullable Player shooter;
	private @Nullable Entity target;
	private @Nullable Vec3 point;
	private Vec3 lastTargetPos = Vec3.ZERO;
	private double speed;
	private boolean decoyChecked;

	public AirMissileEntity(EntityType<? extends AirMissileEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	public static void launch(ServerLevel level, Entity launcher, @Nullable Player shooter, Vec3 from, Vec3 velocity, @Nullable Entity target,
		@Nullable Vec3 point) {
		AirMissileEntity m = ModRegistry.AIR_MISSILE.create(level, EntitySpawnReason.TRIGGERED);
		if (m == null) {
			return;
		}
		m.launcher = launcher;
		m.shooter = shooter;
		m.target = target;
		m.point = point;
		m.speed = velocity.length();
		m.setPos(from);
		m.setDeltaMovement(velocity);
		m.lastTargetPos = target != null ? target.position() : Vec3.ZERO;
		level.addFreshEntity(m);
		level.playSound(null, from.x, from.y, from.z, ModRegistry.HYDRAULIC_EXTEND, SoundSource.NEUTRAL, 1.5F, 1.8F);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_BURNING, false);
	}

	public boolean isBurning() {
		return this.entityData.get(DATA_BURNING);
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement();
		if (this.level().isClientSide()) {
			this.setPos(pos.add(vel));
			return;
		}
		ServerLevel level = (ServerLevel) this.level();
		int age = this.tickCount;
		// a moment's free fall clear of the jet, then the motor
		if (age == 6) {
			this.entityData.set(DATA_BURNING, true);
			level.playSound(null, pos.x, pos.y, pos.z, ModRegistry.RPG_MOTOR, SoundSource.NEUTRAL, 6.0F, 0.75F);
			level.playSound(null, pos.x, pos.y, pos.z, ModRegistry.SAM_LAUNCH, SoundSource.NEUTRAL, 6.0F, 1.5F);
		}
		if (age == BURN) {
			this.entityData.set(DATA_BURNING, false);
		}
		Vec3 dir = vel.lengthSqr() > 1.0E-6 ? vel.normalize() : new Vec3(0, -1, 0);
		if (age < 6) {
			vel = vel.add(0, -0.08, 0);
			this.speed = vel.length();
		} else {
			this.speed = age < BURN ? Math.min(TOP_SPEED, this.speed + 1.4) : Math.max(4.0, this.speed * 0.985);
			// flares behind the target: one look, and it may go for them instead
			if (this.target instanceof FighterEntity f && f.flaresOut() && !this.decoyChecked) {
				this.decoyChecked = true;
				if (this.random.nextFloat() < 0.55F) {
					this.point = f.position().subtract(f.forward().scale(30.0)).add(0, -10, 0);
					this.target = null;
				}
			} else if (this.target instanceof JetEntity j && j.getEngagements() > 0 && !this.decoyChecked && this.random.nextFloat() < 0.02F) {
				this.decoyChecked = true;
			}
			Vec3 aim = this.aim(dir);
			if (aim != null) {
				double turn = Math.toRadians(age < 14 ? 6.0 : 4.0);
				dir = turn(dir, aim.subtract(pos).normalize(), turn);
			}
			vel = dir.scale(this.speed);
		}
		Vec3 next = pos.add(vel);
		// what does this stretch of flight run into?
		HitResult block = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
		Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
		if (age > 4) {
			// proximity fuse: the target's closest pass along this stretch
			if (this.target != null && this.target.isAlive()) {
				Vec3 c = this.target.getBoundingBox().getCenter();
				Vec3 closest = closest(pos, end, c);
				if (closest.distanceTo(c) < FUSE) {
					this.explode(level, closest, this.target);
					return;
				}
			}
			for (Entity e : level.getEntities(this, new AABB(pos, end).inflate(1.5),
				e -> e != this.launcher && e != this.shooter && !(e instanceof AirMissileEntity) && (e instanceof LivingEntity || e instanceof AirThreat) && e.isAlive())) {
				if (e.getBoundingBox().inflate(1.0).clip(pos, end).isPresent()) {
					this.explode(level, e.getBoundingBox().getCenter(), e);
					return;
				}
			}
		}
		if (block.getType() != HitResult.Type.MISS) {
			this.explode(level, end, null);
			return;
		}
		if (this.point != null && this.target == null && next.distanceTo(this.point) < Math.max(3.0, this.speed * 0.6)) {
			this.explode(level, this.point, null);
			return;
		}
		this.setDeltaMovement(vel);
		this.setPos(next);
		this.setYRot((float) (Mth.atan2(-dir.x, dir.z) * Mth.RAD_TO_DEG));
		this.setXRot((float) (-Math.asin(Mth.clamp(dir.y, -1.0, 1.0)) * Mth.RAD_TO_DEG));
		if (age > LIFE || this.getY() < level.getMinY() - 32) {
			DetonationManager.intercepted(level, this.position(), this);
			this.discard();
		}
	}

	/** Where to steer: the predicted intercept point (proportional-navigation style lead) or the ground point. */
	private @Nullable Vec3 aim(Vec3 dir) {
		if (this.target != null && this.target.isAlive() && !this.target.isRemoved()) {
			Vec3 t = this.target.getBoundingBox().getCenter();
			Vec3 tv = t.subtract(this.lastTargetPos);
			this.lastTargetPos = t;
			if (tv.lengthSqr() > 60.0 * 60.0) {
				tv = Vec3.ZERO; // the first sample
			}
			double tgo = t.distanceTo(this.position()) / Math.max(10.0, this.speed);
			return t.add(tv.scale(Math.min(tgo, 60.0)));
		}
		return this.point;
	}

	private void explode(ServerLevel level, Vec3 at, @Nullable Entity hit) {
		Entity source = this.shooter != null ? this.shooter : this;
		if (hit instanceof AirThreat threat && !(hit instanceof LivingEntity)) {
			// an aircraft or a missile: the warhead's rod cloud shreds it
			if (this.random.nextFloat() < threat.killProbability() + 0.2F || hit instanceof JetEntity) {
				threat.destroyByInterceptor(level);
			} else if (hit instanceof FighterEntity f) {
				f.hurtServer(level, level.damageSources().explosion(this, source), 25.0F);
			}
			DetonationManager.intercepted(level, at, this);
		} else {
			DetonationManager.detonateHellfire(level, at, source);
		}
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(6.0))) {
			double d = e.position().distanceTo(at);
			e.hurtServer(level, level.damageSources().explosion(this, source), (float) (30.0 * (1.0 - d / 7.0)));
		}
		this.discard();
	}

	private static Vec3 closest(Vec3 a, Vec3 b, Vec3 p) {
		Vec3 ab = b.subtract(a);
		double len2 = ab.lengthSqr();
		if (len2 < 1.0E-8) {
			return a;
		}
		double t = Mth.clamp(p.subtract(a).dot(ab) / len2, 0.0, 1.0);
		return a.add(ab.scale(t));
	}

	private static Vec3 turn(Vec3 from, Vec3 to, double maxAngle) {
		double angle = Math.acos(Mth.clamp(from.dot(to), -1.0, 1.0));
		if (angle <= maxAngle) {
			return to;
		}
		double f = maxAngle / angle;
		return from.scale(1.0 - f).add(to.scale(f)).normalize();
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 768.0 * 768.0;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}

	/** Missiles in flight are not saved: a reload ends them. */
	@Override
	public boolean shouldBeSaved() {
		return false;
	}
}
