package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.DetonationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * B61 Mod 11 nuclear earth penetrator, dropped by the B-2. It falls free from high altitude, spun up
 * by the small rocket motors in its tail right after release so it flies true, and hits the ground
 * nose first at near the speed of sound. Its hardened steel case drives several blocks into earth and
 * rock (a reinforced bunker stops it at once) before the warhead goes off underground.
 */
public class EarthPenetratorEntity extends Entity {
	private static final EntityDataAccessor<Boolean> DATA_BURIED = SynchedEntityData.defineId(EarthPenetratorEntity.class, EntityDataSerializers.BOOLEAN);
	/** Same fall as the jet's other bombs, so the B-2's release point puts it on the target. */
	private static final double GRAVITY = 0.05;
	private static final double DRAG = 0.992;
	private static final double MAX_DEPTH = 9.0;
	/** Ticks after release during which the spin rockets burn. */
	public static final int SPIN_ROCKETS = 14;

	private Vec3 surface = Vec3.ZERO;
	private Vec3 drill = Vec3.ZERO;
	private double depth;
	private int fuse = -1;

	public EarthPenetratorEntity(EntityType<? extends EarthPenetratorEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_BURIED, false);
	}

	public boolean isBuried() {
		return this.entityData.get(DATA_BURIED);
	}

	@Override
	public void tick() {
		super.tick();
		Level level = this.level();
		if (this.isBuried()) {
			if (level instanceof ServerLevel server) {
				this.penetrate(server);
			}
			return;
		}
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement().scale(DRAG).add(0, -GRAVITY, 0);
		Vec3 next = pos.add(vel);
		if (level instanceof ServerLevel server) {
			server.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.blockPosition()), 1);
			BlockHitResult hit = server.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
			if (hit.getType() != HitResult.Type.MISS) {
				this.impact(server, hit, vel);
				return;
			}
			if (this.tickCount > 1600 || next.y < server.getMinY() - 32) {
				this.discard();
				return;
			}
		} else {
			this.clientTrail(pos, vel);
		}
		this.setDeltaMovement(vel);
		this.setPos(next);
	}

	private void clientTrail(Vec3 pos, Vec3 vel) {
		Level level = this.level();
		Vec3 dir = vel.lengthSqr() > 1.0E-6 ? vel.normalize() : new Vec3(0, -1, 0);
		Vec3 tail = pos.subtract(dir.scale(1.3));
		if (this.tickCount > 2 && this.tickCount < SPIN_ROCKETS) {
			// the spin rockets: four little jets fired sideways from the tail cone
			Vec3 side = dir.cross(new Vec3(0, 1, 0));
			side = side.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : side.normalize();
			Vec3 up = side.cross(dir).normalize();
			double spin = this.tickCount * 1.3;
			for (int i = 0; i < 4; i++) {
				double a = spin + i * Math.PI * 0.5;
				Vec3 out = side.scale(Math.cos(a)).add(up.scale(Math.sin(a)));
				Vec3 p = tail.add(out.scale(0.2));
				level.addParticle(ParticleTypes.SMOKE, p.x, p.y, p.z, out.x * 0.15, out.y * 0.15, out.z * 0.15);
			}
			level.addParticle(ParticleTypes.SMALL_FLAME, tail.x, tail.y, tail.z, 0, 0, 0);
		}
		if (vel.length() > 2.4 && this.random.nextInt(2) == 0) {
			level.addParticle(ParticleTypes.CLOUD, tail.x, tail.y, tail.z, -vel.x * 0.05, -vel.y * 0.05, -vel.z * 0.05);
		}
	}

	/** Nose first into the ground: the casing survives, the ground does not. */
	private void impact(ServerLevel level, BlockHitResult hit, Vec3 vel) {
		Vec3 at = hit.getLocation();
		BlockState state = level.getBlockState(hit.getBlockPos());
		this.surface = at;
		Vec3 dir = vel.normalize();
		if (dir.y > -0.6) {
			dir = new Vec3(dir.x, -0.6, dir.z).normalize(); // it noses into the ground
		}
		this.drill = dir;
		this.setPos(at);
		this.setDeltaMovement(Vec3.ZERO);
		this.entityData.set(DATA_BURIED, true);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y + 0.5, at.z, 120, 0.8, 0.6, 0.8, 0.4);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 1.0, at.z, 40, 1.0, 1.2, 1.0, 0.06);
		level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 2, 0.3, 0.3, 0.3, 0.0);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 8.0F, 0.3F);
		level.playSound(null, at.x, at.y, at.z, ModRegistry.EXPLOSION_BUNKER, SoundSource.BLOCKS, 6.0F, 1.6F);
		if (state.getBlock().getExplosionResistance() >= 1200.0F) {
			this.fuse = 2; // hardened target: no way through, the fuze fires on contact
		}
	}

	/** Drives on through earth and rock until it stops, then the delayed fuze fires. */
	private void penetrate(ServerLevel level) {
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.blockPosition()), 2);
		if (this.fuse < 0) {
			Vec3 next = this.position().add(this.drill.scale(1.1));
			BlockPos p = BlockPos.containing(next);
			BlockState state = level.getBlockState(p);
			boolean blocked = state.getDestroySpeed(level, p) < 0.0F || state.getBlock().getExplosionResistance() >= 1200.0F || p.getY() <= level.getMinY() + 2;
			if (blocked || this.depth >= MAX_DEPTH) {
				this.fuse = 3;
			} else {
				if (!state.isAir() && state.getFluidState().isEmpty()) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
				}
				this.setPos(next);
				this.depth += 1.1;
				if (this.tickCount % 2 == 0) {
					level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state.isAir() ? Blocks.DIRT.defaultBlockState() : state),
						this.surface.x, this.surface.y + 0.3, this.surface.z, 25, 0.4, 0.4, 0.4, 0.25);
				}
			}
			return;
		}
		if (--this.fuse <= 0) {
			DetonationManager.detonateEarthPenetrator(level, this.surface, this.position(), this);
			this.discard();
		}
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
