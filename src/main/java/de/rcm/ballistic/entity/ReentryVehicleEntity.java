package de.rcm.ballistic.entity;

import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.explosion.DetonationManager;
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

/** A MIRV warhead: a glowing cone falling at high speed onto its own aim point. */
public class ReentryVehicleEntity extends Entity implements AirThreat {
	private Vec3 aim = Vec3.ZERO;
	private int engagements;

	public ReentryVehicleEntity(EntityType<? extends ReentryVehicleEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	public void setAim(Vec3 aim) {
		this.aim = aim;
	}

	@Override
	public Entity asEntity() {
		return this;
	}

	@Override
	public boolean isActiveThreat() {
		return this.isAlive();
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
		return this.aim == Vec3.ZERO ? this.position() : this.aim;
	}

	@Override
	public int etaTicks() {
		double speed = Math.max(0.1, this.getDeltaMovement().length());
		return (int) (this.position().distanceTo(this.predictedImpact()) / speed);
	}

	@Override
	public ThreatClass threatClass() {
		return ThreatClass.REENTRY;
	}

	@Override
	public double radarCrossSection() {
		return 0.08; // small, blunt cone
	}

	@Override
	public float killProbability() {
		return 0.5F;
	}

	@Override
	public String nameKey() {
		return "entity.ballisticmissiles.reentry_vehicle";
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
		DetonationManager.intercepted(level, this.position(), this);
		this.discard();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement();
		Vec3 next = pos.add(vel);
		if (this.level().isClientSide()) {
			de.rcm.ballistic.ClientHooks.projectileClientTick.accept(this);
		}
		if (this.level() instanceof ServerLevel level) {
			ThreatTracker.report(level, this);
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(net.minecraft.core.BlockPos.containing(next)), 2);
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(net.minecraft.core.BlockPos.containing(next.add(vel.scale(10)))), 2);
			BlockHitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
			if (hit.getType() != HitResult.Type.MISS) {
				DetonationManager.detonate(level, hit.getLocation(), vel.normalize(), MissileType.Warhead.MIRV_WARHEAD, this);
				this.discard();
				return;
			}
			if (this.tickCount > 1200 || next.y < level.getMinY() - 32) {
				this.discard();
				return;
			}
		}
		this.setPos(next);
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
	protected void addAdditionalSaveData(ValueOutput output) {
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}
}
