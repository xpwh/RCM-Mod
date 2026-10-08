package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.DetonationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * "Rod from God": a six-metre tungsten rod released from an orbital weapons platform. After the
 * de-orbit burn it falls nose first, steering with small fins at the tail, glowing white in its own
 * plasma as it tears through the atmosphere at about ten times the speed of sound, and strikes the
 * target with the energy of tons of TNT - without carrying any explosive at all.
 */
public class KineticRodEntity extends Entity {
	/** Height above the target where it comes out of the sky. */
	public static final double DROP_HEIGHT = 900.0;
	private static final double SPEED = 12.0;

	private Vec3 target = Vec3.ZERO;
	private boolean warned;
	private boolean aimResolved;

	public KineticRodEntity(EntityType<? extends KineticRodEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/**
	 * Releases a rod onto {@code target}, coming in steeply (about 80 degrees) from {@code from}'s side
	 * of the sky.
	 */
	public static KineticRodEntity drop(ServerLevel level, Vec3 target, Vec3 approachFrom) {
		KineticRodEntity rod = ModRegistry.KINETIC_ROD.create(level, EntitySpawnReason.TRIGGERED);
		if (rod == null) {
			return null;
		}
		int x = Mth.floor(target.x);
		int z = Mth.floor(target.z);
		Vec3 aim = target;
		if (level.hasChunk(x >> 4, z >> 4)) {
			aim = new Vec3(target.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), target.z);
		}
		Vec3 side = new Vec3(approachFrom.x - aim.x, 0, approachFrom.z - aim.z);
		side = side.lengthSqr() < 1.0 ? new Vec3(1, 0, 0) : side.normalize();
		Vec3 start = aim.add(side.scale(DROP_HEIGHT * 0.18)).add(0, DROP_HEIGHT, 0);
		rod.target = aim;
		rod.aimResolved = level.hasChunk(x >> 4, z >> 4);
		rod.setPos(start);
		rod.setDeltaMovement(aim.subtract(start).normalize().scale(SPEED * 0.8));
		level.addFreshEntity(rod);
		return rod;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	/** 0 in the thin upper air, 1 low down where the plasma sheath glows white-hot. */
	public float heating() {
		double above = this.getY() - this.target.y;
		if (this.level().isClientSide()) {
			above = this.getY() - this.level().getSeaLevel();
		}
		return (float) Mth.clamp(1.0 - (above - 120.0) / 700.0, 0.0, 1.0);
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement();
		Level level = this.level();
		if (level instanceof ServerLevel server) {
			int tx = Mth.floor(this.target.x);
			int tz = Mth.floor(this.target.z);
			if (!this.aimResolved && server.hasChunk(tx >> 4, tz >> 4)) {
				// the target area has loaded: aim at the actual ground there
				this.aimResolved = true;
				this.target = new Vec3(this.target.x, server.getHeight(Heightmap.Types.MOTION_BLOCKING, tx, tz), this.target.z);
			}
			// the tail fins hold it on the aim point all the way down
			Vec3 want = this.target.subtract(pos);
			double speed = Math.min(SPEED, vel.length() + 0.15);
			Vec3 dir = vel.normalize().lerp(want.normalize(), 0.15).normalize();
			vel = dir.scale(speed);
			Vec3 next = pos.add(vel);
			server.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(next)), 2);
			server.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(this.target)), 3);
			if (!this.warned) {
				this.warned = true;
				// the tearing roar of the rod coming down, swelling until it hits
				server.playSound(null, this.target.x, this.target.y + 40, this.target.z, ModRegistry.ROD_REENTRY, SoundSource.HOSTILE, 3.0F, 1.0F);
			}
			if (next.y < server.getMaxY() + 32) {
				BlockHitResult hit = server.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
				if (hit.getType() != HitResult.Type.MISS) {
					DetonationManager.detonateKineticRod(server, hit.getLocation(), dir, this);
					this.discard();
					return;
				}
			}
			if (this.tickCount > 400 || next.y < server.getMinY() - 16) {
				this.discard();
				return;
			}
			this.setDeltaMovement(vel);
			this.setPos(next);
			return;
		}
		this.clientTrail(pos, vel);
		this.setPos(pos.add(vel));
	}

	private void clientTrail(Vec3 pos, Vec3 vel) {
		Level level = this.level();
		float heat = this.heating();
		int steps = 12;
		for (int i = 0; i < steps; i++) {
			Vec3 p = pos.subtract(vel.scale((double) i / steps));
			if (heat > 0.05F) {
				// ionised air glowing behind it, then a long white trail
				level.addParticle(ParticleTypes.END_ROD, p.x + this.random.nextGaussian() * 0.2, p.y, p.z + this.random.nextGaussian() * 0.2, 0, 0, 0);
				if (this.random.nextFloat() < heat) {
					level.addParticle(ParticleTypes.FLAME, p.x, p.y, p.z, 0, 0, 0);
				}
				if (i % 2 == 0) {
					level.addParticle(ParticleTypes.CLOUD, p.x, p.y, p.z, 0, 0.02, 0);
				}
				if (heat > 0.3F && i % 3 == 0) {
					// a lasting column of smoke where it tore through the air
					level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, p.x, p.y, p.z, 0, 0.01, 0);
					level.addParticle(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 0, 0.0, 0);
				}
			} else if (i % 4 == 0) {
				level.addParticle(ParticleTypes.FIREWORK, p.x, p.y, p.z, 0, 0, 0);
			}
		}
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
