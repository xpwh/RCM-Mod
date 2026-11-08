package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.BlastPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A thrown RGD-5: it flies, clunks down, bounces and rolls until the fuse has burnt, then bursts. A
 * 110 g charge of TNT in a thin steel shell: a sharp, heavy bang, deadly a few metres around, its
 * fragments dangerous out to some twenty-five metres; it does not dig craters.
 */
public class GrenadeEntity extends Entity {
	private static final EntityDataAccessor<Byte> KIND = SynchedEntityData.defineId(GrenadeEntity.class, EntityDataSerializers.BYTE);
	/** A smoke grenade that has popped: pouring out smoke. */
	private static final EntityDataAccessor<Boolean> BURNING = SynchedEntityData.defineId(GrenadeEntity.class, EntityDataSerializers.BOOLEAN);
	/** How long a smoke grenade pours out smoke, ticks. */
	public static final int SMOKE_TICKS = 500;
	private int fuse = GrenadeItem.FUSE;
	private int burnt;
	@Nullable
	private Entity thrower;
	/** Client: tumble, for the renderer. */
	public float spin;
	public float prevSpin;

	public GrenadeEntity(EntityType<? extends GrenadeEntity> type, Level level) {
		super(type, level);
	}

	/** @param fuse ticks left on the fuse (less than the full four seconds if it was cooked off in the hand) */
	public static void throwFrom(ServerLevel level, Entity thrower, Vec3 from, Vec3 velocity, int fuse) {
		throwFrom(level, thrower, from, velocity, fuse, GrenadeItem.FRAG);
	}

	public static void throwFrom(ServerLevel level, Entity thrower, Vec3 from, Vec3 velocity, int fuse, int kind) {
		GrenadeEntity g = ModRegistry.GRENADE_ENTITY.create(level, EntitySpawnReason.TRIGGERED);
		if (g == null) {
			return;
		}
		g.thrower = thrower;
		g.entityData.set(KIND, (byte) kind);
		g.fuse = Math.max(1, fuse);
		g.setPos(from);
		g.setDeltaMovement(velocity);
		level.addFreshEntity(g);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(KIND, (byte) GrenadeItem.FRAG);
		builder.define(BURNING, false);
	}

	public int kind() {
		return this.entityData.get(KIND);
	}

	public boolean burning() {
		return this.entityData.get(BURNING);
	}

	/**
	 * What a grenade does when its fuse runs out ({@code grenade} is the thrown one, or null when it went off in
	 * the hand): the RGD-5 bursts, the stun grenade flashes and bangs, the smoke grenade starts smoking.
	 */
	public static void goOff(ServerLevel level, Vec3 at, @Nullable Entity thrower, int kind, @Nullable GrenadeEntity grenade) {
		switch (kind) {
			case GrenadeItem.FLASH -> {
				if (grenade != null) {
					grenade.discard();
				}
				Flashbang.detonate(level, at, thrower);
			}
			case GrenadeItem.SMOKE -> {
				level.playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 0.9F, 0.55F);
				if (grenade != null) {
					grenade.entityData.set(BURNING, true);
					SmokeScreens.start(level, grenade);
				} else if (thrower != null) {
					// popped in the hand: it is dropped, smoking
					GrenadeEntity g = ModRegistry.GRENADE_ENTITY.create(level, EntitySpawnReason.TRIGGERED);
					if (g != null) {
						g.thrower = thrower;
						g.entityData.set(KIND, (byte) GrenadeItem.SMOKE);
						g.entityData.set(BURNING, true);
						g.fuse = 0;
						g.setPos(at);
						level.addFreshEntity(g);
						SmokeScreens.start(level, g);
					}
				}
			}
			default -> {
				if (grenade != null) {
					grenade.discard();
				}
				de.rcm.ballistic.explosion.DetonationManager.detonateGrenade(level, at, thrower);
			}
		}
	}

	@Override
	public void tick() {
		super.tick();
		Vec3 v = this.getDeltaMovement().add(0, -0.04, 0);
		Vec3 before = this.position();
		this.move(MoverType.SELF, v);
		Vec3 moved = this.position().subtract(before);
		double vx = v.x;
		double vy = v.y;
		double vz = v.z;
		boolean clunk = false;
		double impact = 0.0;
		if (this.verticalCollision && v.y < 0.0) {
			clunk = v.y < -0.12;
			impact = -v.y;
			vy = Math.abs(v.y) > 0.12 ? -v.y * 0.3 : 0.0;
			vx *= 0.6;
			vz *= 0.6;
		}
		if (this.horizontalCollision) {
			if (Math.abs(moved.x) < Math.abs(v.x) * 0.9) {
				clunk |= Math.abs(v.x) > 0.08;
				impact = Math.max(impact, Math.abs(v.x));
				vx = -v.x * 0.35;
			}
			if (Math.abs(moved.z) < Math.abs(v.z) * 0.9) {
				clunk |= Math.abs(v.z) > 0.08;
				impact = Math.max(impact, Math.abs(v.z));
				vz = -v.z * 0.35;
			}
		}
		if (this.onGround()) {
			vx *= 0.82; // rolling to a stop
			vz *= 0.82;
		} else {
			vx *= 0.99;
			vy *= 0.99;
			vz *= 0.99;
		}
		this.setDeltaMovement(vx, vy, vz);
		if (this.level().isClientSide()) {
			this.prevSpin = this.spin;
			this.spin += (float) Math.sqrt(vx * vx + vz * vz) * 120.0F + (this.onGround() ? 0.0F : 6.0F);
			if (this.burning()) {
				this.burnt++;
				de.rcm.ballistic.ClientHooks.smokeGrenade.emit(this.position().add(0, 0.1, 0), this.burnt);
			}
			return;
		}
		ServerLevel level = (ServerLevel) this.level();
		if (clunk) {
			// a clunk that sounds of what it struck: ringing on stone, a knock on wood, a thud in the grass
			float loud = (float) Math.min(1.0, 0.3 + impact * 1.6);
			level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.GRENADE_BOUNCE, SoundSource.PLAYERS, 0.35F * loud,
				0.9F + this.random.nextFloat() * 0.2F);
			MagazineLanding.knock(level, this, loud * 0.75F);
		}
		if (this.burning()) {
			// pouring out smoke, hissing, until it is spent
			this.burnt++;
			SmokeScreens.track(this);
			if (this.burnt % 20 == 1 && this.burnt < SMOKE_TICKS - 40) {
				level.playSound(null, this.getX(), this.getY(), this.getZ(), net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS,
					0.35F, 0.5F + this.random.nextFloat() * 0.1F);
			}
			if (this.burnt >= SMOKE_TICKS) {
				this.discard();
			}
			return;
		}
		if (--this.fuse <= 0) {
			goOff(level, this.position().add(0, 0.15, 0), this.thrower, this.kind(), this);
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.fuse = input.getIntOr("Fuse", GrenadeItem.FUSE);
		this.entityData.set(KIND, (byte) input.getIntOr("Kind", GrenadeItem.FRAG));
		this.entityData.set(BURNING, input.getBooleanOr("Burning", false));
		this.burnt = input.getIntOr("Burnt", 0);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("Fuse", this.fuse);
		output.putInt("Kind", this.kind());
		output.putBoolean("Burning", this.burning());
		output.putInt("Burnt", this.burnt);
	}
}
