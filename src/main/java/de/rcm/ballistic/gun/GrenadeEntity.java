package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.BlastPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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
	private int fuse = GrenadeItem.FUSE;
	@Nullable
	private Entity thrower;
	/** Client: tumble, for the renderer. */
	public float spin;
	public float prevSpin;

	public GrenadeEntity(EntityType<? extends GrenadeEntity> type, Level level) {
		super(type, level);
	}

	public static void throwFrom(ServerLevel level, Entity thrower, Vec3 from, Vec3 velocity) {
		GrenadeEntity g = ModRegistry.GRENADE_ENTITY.create(level, EntitySpawnReason.TRIGGERED);
		if (g == null) {
			return;
		}
		g.thrower = thrower;
		g.setPos(from);
		g.setDeltaMovement(velocity);
		level.addFreshEntity(g);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
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
		if (this.verticalCollision && v.y < 0.0) {
			clunk = v.y < -0.12;
			vy = Math.abs(v.y) > 0.12 ? -v.y * 0.3 : 0.0;
			vx *= 0.6;
			vz *= 0.6;
		}
		if (this.horizontalCollision) {
			if (Math.abs(moved.x) < Math.abs(v.x) * 0.9) {
				clunk |= Math.abs(v.x) > 0.08;
				vx = -v.x * 0.35;
			}
			if (Math.abs(moved.z) < Math.abs(v.z) * 0.9) {
				clunk |= Math.abs(v.z) > 0.08;
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
			return;
		}
		ServerLevel level = (ServerLevel) this.level();
		if (clunk) {
			level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.GRENADE_BOUNCE, SoundSource.PLAYERS, 0.6F,
				0.9F + this.random.nextFloat() * 0.2F);
		}
		if (--this.fuse <= 0) {
			this.detonate(level);
		}
	}

	private void detonate(ServerLevel level) {
		Vec3 pos = this.position().add(0, 0.15, 0);
		this.discard();
		// the bang: blast damage close in, no cratering; our own recorded report
		level.explode(this, null, null, pos.x, pos.y, pos.z, 2.6F, false, Level.ExplosionInteraction.NONE, ParticleTypes.EXPLOSION,
			ParticleTypes.EXPLOSION, WeightedList.of(), Holder.direct(ModRegistry.GRENADE_EXPLODE));
		level.playSound(null, pos.x, pos.y, pos.z, ModRegistry.GRENADE_EXPLODE_FAR, SoundSource.BLOCKS, 6.0F, 0.95F + this.random.nextFloat() * 0.1F);
		// some 350 fragments: dangerous well beyond the blast
		BlastPhysics.fragments(level, pos, this.thrower, 40, 25.0, 5.0F);
		BlastPhysics.shatter(level, pos, 0.0, 6.0, 30, 0.8F);
		// a quick dark cloud of smoke and earth thrown up
		BlockState ground = level.getBlockState(BlockPos.containing(pos.x, pos.y - 0.5, pos.z));
		if (!ground.isAir()) {
			level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), pos.x, pos.y, pos.z, 60, 0.6, 0.3, 0.6, 0.35);
		}
		level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 0.5, pos.z, 24, 0.7, 0.6, 0.7, 0.04);
		level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y + 0.3, pos.z, 8, 0.5, 0.3, 0.5, 0.02);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.fuse = input.getIntOr("Fuse", GrenadeItem.FUSE);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("Fuse", this.fuse);
	}
}
