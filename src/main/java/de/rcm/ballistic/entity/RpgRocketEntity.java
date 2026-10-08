package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.DetonationManager;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
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
 * PG-7V rocket-propelled grenade from the RPG-7. The small powder booster throws it out of the tube
 * (that charge is burnt before it leaves, so the shooter is not burnt); a few metres out the
 * sustainer motor ignites and accelerates it, spinning on its fins, trailing flame and smoke. The
 * shaped-charge warhead goes off on the piezo nose fuse at the first hard contact, or self-destructs
 * at the end of its flight.
 */
public class RpgRocketEntity extends Entity {
	/** Ticks of coasting on the booster before the sustainer lights. */
	public static final int SUSTAINER_IGNITION = 3;
	private static final int SUSTAINER_BURNOUT = 45;
	private static final int SELF_DESTRUCT = 110;
	private static final double MUZZLE_SPEED = 1.7;
	private static final double TOP_SPEED = 4.6;

	private @Nullable Entity shooter;

	public RpgRocketEntity(EntityType<? extends RpgRocketEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	public static void fire(ServerLevel level, Entity shooter, Vec3 from, Vec3 dir) {
		RpgRocketEntity rocket = ModRegistry.RPG_GRENADE.create(level, EntitySpawnReason.TRIGGERED);
		if (rocket == null) {
			return;
		}
		rocket.shooter = shooter;
		rocket.setPos(from);
		rocket.setDeltaMovement(dir.normalize().scale(MUZZLE_SPEED).add(shooter.getDeltaMovement().scale(0.5)));
		level.addFreshEntity(rocket);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	public boolean sustainerBurning() {
		return this.tickCount >= SUSTAINER_IGNITION && this.tickCount < SUSTAINER_BURNOUT;
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement();
		if (this.sustainerBurning()) {
			// the sustainer pushes it up to about 300 m/s; the fins keep it pointing into the wind
			double speed = Math.min(TOP_SPEED, vel.length() + 0.32);
			vel = vel.normalize().scale(speed).add(0, -0.006, 0);
		} else {
			vel = vel.scale(0.995).add(0, this.tickCount < SUSTAINER_IGNITION ? -0.01 : -0.03, 0);
		}
		Vec3 next = pos.add(vel);
		Level level = this.level();
		if (level instanceof ServerLevel server) {
			BlockHitResult hit = server.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
			Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
			Entity victim = this.firstEntityHit(server, pos, end);
			if (victim != null) {
				// the shaped charge's jet goes straight through whatever it struck
				victim.hurtServer(server, server.damageSources().explosion(this, this.shooter), 24.0F);
				DetonationManager.detonateRpg(server, victim.position().add(0, victim.getBbHeight() * 0.5, 0), this);
				this.discard();
				return;
			}
			if (hit.getType() != HitResult.Type.MISS) {
				DetonationManager.detonateRpg(server, end.subtract(vel.normalize().scale(0.2)), this);
				this.discard();
				return;
			}
			if (server.getFluidState(net.minecraft.core.BlockPos.containing(next)).isEmpty() == false) {
				// into the water: it dives, slows and the fuse never meets anything hard enough
				server.sendParticles(ParticleTypes.SPLASH, next.x, next.y + 0.3, next.z, 40, 0.5, 0.2, 0.5, 0.4);
				server.playSound(null, next.x, next.y, next.z, SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 1.5F, 1.2F);
				this.discard();
				return;
			}
			if (this.tickCount >= SELF_DESTRUCT) {
				DetonationManager.detonateRpg(server, pos, this);
				this.discard();
				return;
			}
			if (next.y < server.getMinY() - 16) {
				this.discard();
				return;
			}
		} else {
			this.clientEffects(pos, vel);
		}
		this.setDeltaMovement(vel);
		this.setPos(next);
	}

	private @Nullable Entity firstEntityHit(ServerLevel level, Vec3 from, Vec3 to) {
		List<Entity> list = level.getEntities(this, new AABB(from, to).inflate(0.6), e ->
			(e instanceof LivingEntity || e instanceof JetEntity || e instanceof MobileLauncherEntity || e instanceof DestroyerEntity)
				&& e.isAlive() && !e.isSpectator() && (e != this.shooter || this.tickCount > 12));
		Entity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity e : list) {
			AABB box = e.getBoundingBox().inflate(0.25);
			var clip = box.clip(from, to);
			if (clip.isPresent() || box.contains(from)) {
				double d = clip.map(from::distanceToSqr).orElse(0.0);
				if (d < bestDist) {
					bestDist = d;
					best = e;
				}
			}
		}
		return best;
	}

	private void clientEffects(Vec3 pos, Vec3 vel) {
		Level level = this.level();
		Vec3 dir = vel.lengthSqr() > 1.0E-6 ? vel.normalize() : new Vec3(0, 0, 1);
		Vec3 tail = pos.subtract(dir.scale(0.7));
		if (this.tickCount == 1) {
			// the launch: a cloud from the muzzle, and the back-blast roaring out behind the shooter
			Vec3 launcher = pos.subtract(dir.scale(1.1));
			de.rcm.ballistic.ClientHooks.smokeCloud.emit(launcher, dir.scale(0.15), 4, 0.9F);
			de.rcm.ballistic.ClientHooks.smokeCloud.emit(launcher.subtract(dir.scale(2.4)), dir.scale(-0.6), 10, 1.4F);
		}
		if (this.tickCount < SUSTAINER_IGNITION) {
			// only a thin wisp from the booster that burnt out in the tube
			level.addParticle(ParticleTypes.SMOKE, tail.x, tail.y, tail.z, 0, 0.01, 0);
			return;
		}
		de.rcm.ballistic.ClientHooks.smokeTrail.emit(this.getId(), tail, 0.32F, this.sustainerBurning() ? 1.0F : 0.0F);
		if (this.sustainerBurning()) {
			int steps = 4;
			for (int i = 0; i < steps; i++) {
				Vec3 p = tail.subtract(vel.scale((double) i / steps));
				if (i < 2) {
					level.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, -dir.x * 0.08, -dir.y * 0.08, -dir.z * 0.08);
				}
			}
			if (this.tickCount == SUSTAINER_IGNITION) {
				// the sustainer lighting off a few metres out: a bright puff and a bang
				level.addParticle(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFC870), tail.x, tail.y, tail.z, 0, 0, 0);
				for (int i = 0; i < 8; i++) {
					level.addParticle(ParticleTypes.LARGE_SMOKE, tail.x, tail.y, tail.z,
						(this.random.nextDouble() - 0.5) * 0.15, (this.random.nextDouble() - 0.5) * 0.15, (this.random.nextDouble() - 0.5) * 0.15);
				}
				level.playLocalSound(tail.x, tail.y, tail.z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.NEUTRAL, 2.0F, 0.6F, false);
			}
		} else if (this.tickCount % 2 == 0) {
			level.addParticle(ParticleTypes.SMOKE, tail.x, tail.y, tail.z, 0, 0.02, 0);
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 256 * 256;
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
