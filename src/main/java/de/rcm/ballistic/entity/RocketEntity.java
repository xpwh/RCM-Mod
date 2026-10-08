package de.rcm.ballistic.entity;

import de.rcm.ballistic.explosion.DetonationManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * Air-to-ground rocket fired by the Apache and the Reaper. A Hellfire is laser guided: it climbs a
 * little after launch, then dives onto its target (and follows it if it moves); a Hydra 70 is an
 * unguided folding-fin rocket fired in salvos, fast and inaccurate.
 */
public class RocketEntity extends Entity implements de.rcm.ballistic.defense.AirThreat {
	public enum Kind {
		HELLFIRE,
		HYDRA
	}

	private static final EntityDataAccessor<Integer> DATA_KIND = SynchedEntityData.defineId(RocketEntity.class, EntityDataSerializers.INT);

	private Vec3 target = Vec3.ZERO;
	private @Nullable Entity targetEntity;
	private @Nullable Entity shooter;
	private int engagements;

	public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/** Launches a rocket from {@code from} with initial velocity {@code velocity}. */
	public static void fire(ServerLevel level, Kind kind, Entity shooter, Vec3 from, Vec3 velocity, Vec3 target, @Nullable Entity targetEntity) {
		RocketEntity rocket = de.rcm.ballistic.ModRegistry.ROCKET.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
		if (rocket == null) {
			return;
		}
		rocket.entityData.set(DATA_KIND, kind.ordinal());
		rocket.shooter = shooter;
		rocket.target = target;
		rocket.targetEntity = targetEntity;
		rocket.setPos(from);
		rocket.setDeltaMovement(velocity);
		level.addFreshEntity(rocket);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_KIND, Kind.HELLFIRE.ordinal());
	}

	public Kind getKind() {
		Kind[] all = Kind.values();
		int i = this.entityData.get(DATA_KIND);
		return i >= 0 && i < all.length ? all[i] : Kind.HELLFIRE;
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement();
		Kind kind = this.getKind();
		if (kind == Kind.HELLFIRE) {
			if (this.targetEntity != null && this.targetEntity.isAlive()) {
				this.target = this.targetEntity.position().add(0, this.targetEntity.getBbHeight() * 0.5, 0);
			}
			// motor burn: accelerate to about Mach 1.3, steer with proportional navigation-ish lead
			double speed = Math.min(5.5, vel.length() + 0.35);
			Vec3 want = this.target.subtract(pos);
			if (this.tickCount < 6) {
				want = want.add(0, want.length() * 0.25, 0); // loft a little after launch
			}
			Vec3 dir = vel.normalize().add(want.normalize().subtract(vel.normalize()).scale(0.22)).normalize();
			vel = dir.scale(speed);
		} else {
			vel = vel.scale(1.004).add(0, -0.012, 0); // unguided: a little drag and gravity
		}
		Vec3 next = pos.add(vel);

		if (this.level() instanceof ServerLevel level) {
			de.rcm.ballistic.defense.ThreatTracker.report(level, this);
			BlockHitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
			Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
			boolean entityHit = this.tickCount > 3 && !level.getEntities(this, new AABB(pos, end).inflate(0.8),
				e -> e instanceof LivingEntity && e.isAlive() && e != this.shooter).isEmpty();
			boolean arrived = kind == Kind.HELLFIRE && end.distanceTo(this.target) < 1.5;
			if (hit.getType() != HitResult.Type.MISS || entityHit || arrived) {
				if (kind == Kind.HELLFIRE) {
					DetonationManager.detonateHellfire(level, end, this);
				} else {
					DetonationManager.detonateSmallRound(level, end, this, 2.0F);
				}
				this.discard();
				return;
			}
			if (this.tickCount > 300 || next.y < level.getMinY() - 16) {
				this.discard();
				return;
			}
		} else {
			// smoke trail and motor flame
			Vec3 back = vel.lengthSqr() > 1.0E-6 ? vel.normalize().scale(-0.8) : Vec3.ZERO;
			de.rcm.ballistic.ClientHooks.smokeTrail.emit(this.getId(), pos.add(back), this.getKind() == Kind.HYDRA ? 0.35F : 0.45F, 1.0F);
			this.level().addParticle(ParticleTypes.FLAME, pos.x + back.x, pos.y + back.y, pos.z + back.z, 0, 0, 0);
		}
		this.setDeltaMovement(vel);
		this.setPos(next);
	}

	// ------------------------------------------------------------------ as a target for air defense

	/** The player whose aircraft fired this rocket. */
	public java.util.@Nullable UUID getOwnerUuid() {
		return this.shooter instanceof JetEntity jet ? jet.getCaller() : null;
	}

	/** Seeker pulled off by a decoy: the Hellfire flies at the decoy instead. */
	public boolean decoy(Vec3 decoy) {
		if (this.getKind() != Kind.HELLFIRE) {
			return false;
		}
		this.target = decoy;
		this.targetEntity = null;
		return true;
	}

	@Override
	public Entity asEntity() {
		return this;
	}

	@Override
	public boolean isActiveThreat() {
		return this.isAlive() && this.tickCount > 2;
	}

	@Override
	public Vec3 aimPoint(int ticksAhead) {
		return this.position().add(this.getDeltaMovement().scale(ticksAhead));
	}

	@Override
	public Vec3 threatVelocity() {
		return this.getDeltaMovement();
	}

	@Override
	public Vec3 predictedImpact() {
		if (this.getKind() == Kind.HELLFIRE) {
			return this.target;
		}
		Vec3 v = this.getDeltaMovement();
		double t = v.y < -1.0E-3 ? Math.min(200.0, (this.getY() - this.target.y) / -v.y) : 60.0;
		return this.position().add(v.scale(t));
	}

	@Override
	public int etaTicks() {
		double speed = Math.max(0.5, this.getDeltaMovement().length());
		return (int) (this.position().distanceTo(this.predictedImpact()) / speed);
	}

	@Override
	public ThreatClass threatClass() {
		return ThreatClass.CRUISE;
	}

	@Override
	public double radarCrossSection() {
		return 0.02;
	}

	@Override
	public float killProbability() {
		return 0.7F;
	}

	@Override
	public String nameKey() {
		return this.getKind() == Kind.HELLFIRE ? "entity.ballisticmissiles.hellfire" : "entity.ballisticmissiles.hydra";
	}

	@Override
	public int getEngagements() {
		return this.engagements;
	}

	@Override
	public void setEngagements(int engagements) {
		this.engagements = engagements;
	}

	@Override
	public void destroyByInterceptor(ServerLevel level) {
		Vec3 p = this.position();
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION, p.x, p.y, p.z, 2, 0.3, 0.3, 0.3, 0.0);
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 10, 0.5, 0.5, 0.5, 0.05);
		this.discard();
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 384 * 384;
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
