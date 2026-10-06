package de.rcm.ballistic.entity;

import de.rcm.ballistic.explosion.DetonationManager;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Low-drag free-fall bomb dropped by an airstrike jet. It keeps the jet's forward speed, noses over
 * as it falls (the renderer points it along its velocity) and explodes on contact.
 */
public class AerialBombEntity extends Entity {
	private static final EntityDataAccessor<Boolean> DATA_MOAB = SynchedEntityData.defineId(AerialBombEntity.class, EntityDataSerializers.BOOLEAN);
	private static final double GRAVITY = 0.05;
	private static final double DRAG = 0.992;

	public AerialBombEntity(EntityType<? extends AerialBombEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_MOAB, false);
	}

	/** GBU-43 "MOAB": the 10-tonne air blast bomb dropped by the B-2. */
	public boolean isMoab() {
		return this.entityData.get(DATA_MOAB);
	}

	public void setMoab(boolean moab) {
		this.entityData.set(DATA_MOAB, moab);
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement().scale(DRAG).add(0, -GRAVITY, 0);
		Vec3 next = pos.add(vel);

		if (this.level() instanceof ServerLevel level) {
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.blockPosition()), 1);
			BlockHitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
			Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
			boolean entityHit = this.tickCount > 4
				&& !level.getEntities(this, new AABB(pos, end).inflate(0.5), e -> e instanceof LivingEntity && e.isAlive()).isEmpty();
			if (hit.getType() != HitResult.Type.MISS || entityHit) {
				if (this.isMoab()) {
					DetonationManager.detonateMoab(level, end, this);
				} else {
					DetonationManager.detonateAerialBomb(level, end, this);
				}
				this.discard();
				return;
			}
			if (this.tickCount > 1200 || next.y < level.getMinY() - 32) {
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
