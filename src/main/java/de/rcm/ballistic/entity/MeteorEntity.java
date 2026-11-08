package de.rcm.ballistic.entity;

import de.rcm.ballistic.explosion.DetonationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** A glowing rock from the moon rocket's meteor shower: streaks down on a steep line and explodes. */
public class MeteorEntity extends Entity {
	private static final EntityDataAccessor<Float> DATA_SIZE = SynchedEntityData.defineId(MeteorEntity.class, EntityDataSerializers.FLOAT);

	public float spin;

	public MeteorEntity(EntityType<? extends MeteorEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.spin = level.getRandom().nextFloat() * 360.0F;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_SIZE, 1.0F);
	}

	public float getSize() {
		return this.entityData.get(DATA_SIZE);
	}

	public void setSize(float size) {
		this.entityData.set(DATA_SIZE, size);
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement().add(0, -0.04, 0);
		Vec3 next = pos.add(vel);
		if (this.level() instanceof ServerLevel level) {
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(next)), 1);
			BlockHitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
			if (hit.getType() != HitResult.Type.MISS) {
				DetonationManager.meteorImpact(level, hit.getLocation(), this.getSize(), this);
				this.discard();
				return;
			}
			if (this.tickCount > 400 || next.y < level.getMinY() - 32) {
				this.discard();
				return;
			}
		} else {
			// burning trail: fire at the head, smoke streaming behind
			Vec3 back = vel.normalize().scale(-1.0);
			for (int i = 0; i < 4; i++) {
				Vec3 p = pos.add(back.scale(i * 0.8));
				this.level().addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, back.x * 0.1, back.y * 0.1, back.z * 0.1);
				this.level().addParticle(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 0, 0.02, 0);
			}
			this.level().addParticle(ParticleTypes.LAVA, pos.x, pos.y, pos.z, 0, 0, 0);
		}
		this.spin += 9.0F;
		this.setDeltaMovement(vel);
		this.setPos(next);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 1024 * 1024;
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
