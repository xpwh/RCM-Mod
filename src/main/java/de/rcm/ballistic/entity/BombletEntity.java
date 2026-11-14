package de.rcm.ballistic.entity;

import de.rcm.ballistic.explosion.DetonationManager;
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

/** Cluster sub-munition: falls with drag, tumbles, explodes on contact. */
public class BombletEntity extends Entity {
	private static final double GRAVITY = 0.06;
	private static final double DRAG = 0.985;

	public float spinOffset;
	private boolean incendiary;

	public BombletEntity(EntityType<? extends BombletEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.spinOffset = level.getRandom().nextFloat() * 360.0F;
	}

	public void setIncendiary(boolean incendiary) {
		this.incendiary = incendiary;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement().scale(DRAG).add(0, -GRAVITY, 0);
		Vec3 next = pos.add(vel);

		if (this.level() instanceof ServerLevel level) {
			BlockHitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
			Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
			boolean entityHit = !level.getEntities(this, new AABB(pos, end).inflate(0.6), e -> e instanceof LivingEntity && e.isAlive()).isEmpty();
			if (hit.getType() != HitResult.Type.MISS || entityHit) {
				if (this.incendiary) {
					DetonationManager.detonateIncendiary(level, end, this);
				} else {
					DetonationManager.detonateBomblet(level, end, this);
				}
				this.discard();
				return;
			}
			if (this.incendiary && this.tickCount % 2 == 0) {
				level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, pos.x, pos.y, pos.z, 2, 0.1, 0.1, 0.1, 0.01);
				level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 1, 0.1, 0.1, 0.1, 0.01);
			}
			if (this.tickCount > 600 || next.y < level.getMinY() - 32) {
				this.discard();
				return;
			}
		}
		this.setDeltaMovement(vel);
		this.setPos(next);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 512 * 512;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}
}
