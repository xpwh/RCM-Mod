package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * The small launcher that carries a tungsten rod up to the weapons platform: it lifts off from the
 * pad beside the ground station in a cloud of exhaust, climbs, pitches over into its gravity turn and
 * dwindles to a point of fire high in the sky before it is gone.
 */
public class SupplyRocketEntity extends Entity {
	private static final int LIFETIME = 520;

	public SupplyRocketEntity(EntityType<? extends SupplyRocketEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/** Lifts off a few blocks behind the station at {@code station} (facing {@code facing}). */
	public static void launch(ServerLevel level, BlockPos station, Direction facing) {
		SupplyRocketEntity rocket = ModRegistry.SUPPLY_ROCKET.create(level, EntitySpawnReason.TRIGGERED);
		if (rocket == null) {
			return;
		}
		BlockPos pad = station.relative(facing.getOpposite(), 6).relative(facing.getClockWise(), 2);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pad.getX(), pad.getZ());
		rocket.setPos(pad.getX() + 0.5, Math.max(y, station.getY() + 1), pad.getZ() + 0.5);
		rocket.setDeltaMovement(0, 0.02, 0);
		level.addFreshEntity(rocket);
		level.playSound(null, rocket.getX(), rocket.getY(), rocket.getZ(), ModRegistry.SUPPLY_LAUNCH, SoundSource.BLOCKS, 6.0F, 1.0F);
		level.playSound(null, rocket.getX(), rocket.getY(), rocket.getZ(), ModRegistry.IGNITION, SoundSource.BLOCKS, 4.0F, 1.2F);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	public void tick() {
		super.tick();
		int t = this.tickCount;
		Vec3 vel = this.getDeltaMovement();
		// slow off the pad, then ever faster; from about 3 s it pitches over eastwards
		double speed = Math.min(5.0, 0.04 + t * t * 0.0006 + t * 0.004);
		double pitch = t < 60 ? 0.0 : Math.min(0.9, (t - 60) * 0.006);
		Vec3 dir = new Vec3(Math.sin(pitch), Math.cos(pitch), 0.0);
		vel = dir.scale(speed);
		Vec3 pos = this.position();
		if (this.level().isClientSide()) {
			this.exhaust(pos, vel, t);
		} else if (t > LIFETIME || pos.y > this.level().getMaxY() + 900) {
			this.discard();
			return;
		}
		this.setDeltaMovement(vel);
		this.setPos(pos.add(vel));
	}

	private void exhaust(Vec3 pos, Vec3 vel, int t) {
		Level level = this.level();
		Vec3 dir = vel.lengthSqr() > 1.0E-6 ? vel.normalize() : new Vec3(0, 1, 0);
		if (t < 30) {
			// the exhaust hitting the pad and boiling out sideways
			for (int i = 0; i < 12; i++) {
				double a = this.random.nextDouble() * Math.PI * 2;
				double s = 0.3 + this.random.nextDouble() * 0.5;
				level.addParticle(ParticleTypes.CLOUD, pos.x, pos.y - Math.min(t * 0.1, 3.0), pos.z, Math.cos(a) * s, 0.02, Math.sin(a) * s);
			}
			level.addParticle(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 0, 0.05, 0);
		}
		int steps = Math.max(1, (int) Math.ceil(vel.length() / 0.8));
		for (int i = 0; i < steps; i++) {
			Vec3 p = pos.subtract(vel.scale((double) i / steps)).subtract(dir.scale(0.4));
			level.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, -dir.x * 0.2, -dir.y * 0.2, -dir.z * 0.2);
			if (i % 2 == 0) {
				level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.x, p.y, p.z, 0, 0.0, 0);
			}
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 2048 * 2048;
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
