package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
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
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A burnt-out missile stage after separation: it keeps the missile's speed for a moment, then falls
 * back on a ballistic arc, tumbling, trailing the last of its propellant as smoke, and crashes
 * (or splashes into the sea) far downrange of the launch site.
 */
public class SpentStageEntity extends Entity {
	private static final EntityDataAccessor<Integer> DATA_TYPE = SynchedEntityData.defineId(SpentStageEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_STAGE = SynchedEntityData.defineId(SpentStageEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Vector3fc> DATA_DIR = SynchedEntityData.defineId(SpentStageEntity.class, EntityDataSerializers.VECTOR3);
	private static final int LIFETIME = 2400;

	public SpentStageEntity(EntityType<? extends SpentStageEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/**
	 * Separates stage {@code stage} of a missile whose bottom is at {@code bottom}, flying along
	 * {@code dir} at {@code velocity}.
	 */
	public static void separate(ServerLevel level, MissileType type, int stage, Vec3 bottom, Vec3 dir, Vec3 velocity) {
		SpentStageEntity e = ModRegistry.SPENT_STAGE.create(level, EntitySpawnReason.TRIGGERED);
		if (e == null) {
			return;
		}
		float lo = MissileStages.bottom(type, stage);
		float hi = MissileStages.cuts(type)[stage];
		e.entityData.set(DATA_TYPE, type.ordinal());
		e.entityData.set(DATA_STAGE, stage);
		e.entityData.set(DATA_DIR, new Vector3f((float) dir.x, (float) dir.y, (float) dir.z));
		e.setPos(bottom.add(dir.scale((lo + hi) * 0.5 * type.scale)));
		// retro-rockets on the interstage push the empty stage back from the next one
		e.setDeltaMovement(velocity.scale(0.96).subtract(dir.scale(0.25)));
		level.addFreshEntity(e);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_TYPE, 0);
		builder.define(DATA_STAGE, 0);
		builder.define(DATA_DIR, new Vector3f(0, 1, 0));
	}

	public MissileType getMissileType() {
		MissileType[] all = MissileType.values();
		int i = this.entityData.get(DATA_TYPE);
		return i >= 0 && i < all.length ? all[i] : MissileType.NUCLEAR;
	}

	public int getStage() {
		return this.entityData.get(DATA_STAGE);
	}

	/** Direction the stage pointed when it separated. */
	public Vec3 getInitialDir() {
		Vector3fc v = this.entityData.get(DATA_DIR);
		return new Vec3(v.x(), v.y(), v.z());
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 pos = this.position();
		// thin air high up hardly slows it; lower down drag takes over and it falls steeply
		double drag = pos.y > 200 ? 0.998 : 0.985;
		Vec3 vel = this.getDeltaMovement().scale(drag).add(0, -0.045, 0);
		Vec3 next = pos.add(vel);
		Level level = this.level();
		if (level instanceof ServerLevel server) {
			server.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(next)), 1);
			if (next.y < server.getMaxY() + 64) {
				BlockHitResult hit = server.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
				if (hit.getType() != HitResult.Type.MISS) {
					this.crash(server, hit.getLocation());
					return;
				}
			}
			if (this.tickCount > LIFETIME || next.y < server.getMinY() - 32) {
				this.discard();
				return;
			}
		} else if (this.tickCount < 80) {
			// the last propellant venting from the open end
			level.addParticle(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, -vel.x * 0.05, -vel.y * 0.05, -vel.z * 0.05);
		}
		this.setDeltaMovement(vel);
		this.setPos(next);
	}

	private void crash(ServerLevel level, Vec3 at) {
		boolean water = !level.getFluidState(BlockPos.containing(at)).isEmpty();
		if (water) {
			level.sendParticles(ParticleTypes.SPLASH, at.x, at.y + 0.5, at.z, 120, 1.5, 0.5, 1.5, 0.6);
			level.sendParticles(ParticleTypes.CLOUD, at.x, at.y + 1, at.z, 30, 1.0, 1.5, 1.0, 0.1);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 8.0F, 0.5F);
		} else {
			// an empty steel casing hitting the ground at speed: a crash, a fireball of leftover fuel
			level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 1.0, 0.5, 1.0, 0.0);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1, at.z, 40, 1.2, 1.2, 1.2, 0.05);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 6.0F, 0.4F);
			level.explode(this, at.x, at.y, at.z, 2.0F, true, Level.ExplosionInteraction.NONE);
		}
		this.discard();
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
