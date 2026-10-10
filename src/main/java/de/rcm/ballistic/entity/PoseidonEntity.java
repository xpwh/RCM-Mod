package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.DetonationManager;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Poseidon (Status-6): a nuclear-powered, nuclear-armed autonomous torpedo the size of a small
 * submarine. It swims out of the carrier submarine's bow tube, dives to cruising depth and heads for
 * its target at about 100 knots, keeping well below the surface and clear of the bottom. It cannot
 * leave the water: where the sea ends - at the coast in front of the target - or once it is close
 * enough, the warhead goes off.
 */
public class PoseidonEntity extends Entity {
	private static final double CRUISE_SPEED = 2.2;
	/** Degrees per tick it can turn. */
	private static final double TURN = 2.5;
	private static final int MAX_TICKS = 4000;

	private Vec3 target = Vec3.ZERO;
	private @Nullable UUID owner;
	private boolean reportedDive;

	public PoseidonEntity(EntityType<? extends PoseidonEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/** Puts a Poseidon in the water at {@code from}, heading {@code dir}, bound for {@code target}. */
	public static @Nullable PoseidonEntity launch(ServerLevel level, Vec3 from, Vec3 dir, Vec3 target, @Nullable ServerPlayer owner) {
		PoseidonEntity p = ModRegistry.POSEIDON.create(level, EntitySpawnReason.TRIGGERED);
		if (p == null) {
			return null;
		}
		Vec3 flat = new Vec3(dir.x, 0, dir.z);
		flat = flat.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : flat.normalize();
		p.setPos(from);
		p.setDeltaMovement(flat.scale(0.35));
		p.target = target;
		p.owner = owner != null ? owner.getUUID() : null;
		level.addFreshEntity(p);
		return p;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	private boolean water(Level level, BlockPos p) {
		return !level.getFluidState(p).isEmpty();
	}

	@Override
	public void tick() {
		super.tick();
		Level level = this.level();
		Vec3 pos = this.position();
		Vec3 vel = this.getDeltaMovement();
		if (!(level instanceof ServerLevel server)) {
			this.clientEffects(pos, vel);
			this.setPos(pos.add(vel));
			return;
		}
		server.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.blockPosition()), 2);
		BlockPos here = BlockPos.containing(pos);
		if (!this.water(server, here) && !this.water(server, here.above())) {
			if (this.tickCount < 3) {
				this.tell(server, Component.translatable("message.ballisticmissiles.poseidon_no_water").withStyle(ChatFormatting.YELLOW));
				this.discard();
				return;
			}
		}
		// ---- steering: towards the target, slowly, once it is clear of the carrier
		Vec3 flat = new Vec3(vel.x, 0, vel.z);
		flat = flat.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : flat.normalize();
		Vec3 toTarget = new Vec3(this.target.x - pos.x, 0, this.target.z - pos.z);
		double distance = toTarget.length();
		if (this.tickCount > 30 && distance > 1.0) {
			double want = Math.atan2(toTarget.z, toTarget.x);
			double have = Math.atan2(flat.z, flat.x);
			double turn = Mth.clamp(Mth.wrapDegrees(Math.toDegrees(want - have)), -TURN, TURN);
			double a = have + Math.toRadians(turn);
			flat = new Vec3(Math.cos(a), 0, Math.sin(a));
		}
		double speed = Math.min(CRUISE_SPEED, Math.sqrt(vel.x * vel.x + vel.z * vel.z) + (this.tickCount < 30 ? 0.01 : 0.05));
		// ---- depth keeping: seven blocks under the surface, at least two and a half above the bottom
		int surface = here.getY();
		for (int i = 0; i < 48 && this.water(server, BlockPos.containing(pos.x, surface + 1, pos.z)); i++) {
			surface++;
		}
		int bottom = here.getY();
		for (int i = 0; i < 48 && this.water(server, BlockPos.containing(pos.x, bottom - 1, pos.z)); i++) {
			bottom--;
		}
		double wantY = Math.max(bottom + 2.5, Math.min(surface - 6.0, surface - 1.5));
		if (surface - bottom < 4) {
			wantY = (surface + bottom) * 0.5 + 0.3;
		}
		double vy = Mth.clamp((wantY - pos.y) * 0.08, -0.35, 0.35);
		vel = flat.scale(speed).add(0, vy, 0);
		Vec3 next = pos.add(vel);
		// ---- the sea ends ahead: the coast. Or it is there.
		Vec3 ahead = next.add(flat.scale(2.5));
		boolean blocked = !this.water(server, BlockPos.containing(ahead)) && !this.water(server, BlockPos.containing(ahead.x, ahead.y + 1.5, ahead.z));
		if (this.tickCount > 20 && (blocked || distance < 10.0)) {
			this.tell(server, Component.translatable("message.ballisticmissiles.poseidon_detonated", (int) pos.x, (int) pos.z)
				.withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
			DetonationManager.detonatePoseidon(server, pos, this);
			this.discard();
			return;
		}
		if (!this.reportedDive && this.tickCount == 60) {
			this.reportedDive = true;
			this.tell(server, Component.translatable("message.ballisticmissiles.poseidon_cruise", (int) distance, (int) (distance / CRUISE_SPEED / 20.0))
				.withStyle(ChatFormatting.DARK_AQUA));
		}
		if (this.tickCount > MAX_TICKS) {
			this.discard();
			return;
		}
		this.setDeltaMovement(vel);
		this.setPos(next);
	}

	private void tell(ServerLevel level, Component message) {
		if (this.owner != null && level.getServer().getPlayerList().getPlayer(this.owner) instanceof ServerPlayer player) {
			player.displayClientMessage(message, false);
		}
	}

	private void clientEffects(Vec3 pos, Vec3 vel) {
		Level level = this.level();
		Vec3 dir = vel.lengthSqr() > 1.0E-6 ? vel.normalize() : new Vec3(1, 0, 0);
		Vec3 tail = pos.subtract(dir.scale(8.0));
		// cavitation from the pump-jet and the boundary layer streaming off the hull
		for (int i = 0; i < 6; i++) {
			level.addParticle(ParticleTypes.BUBBLE, tail.x + this.random.nextGaussian() * 0.4, tail.y + this.random.nextGaussian() * 0.4,
				tail.z + this.random.nextGaussian() * 0.4, -dir.x * 0.3, 0.05, -dir.z * 0.3);
		}
		if (this.random.nextInt(3) == 0) {
			Vec3 p = pos.subtract(dir.scale(this.random.nextDouble() * 14.0));
			level.addParticle(ParticleTypes.BUBBLE_COLUMN_UP, p.x, p.y + 0.6, p.z, 0, 0.2, 0);
		}
		// close under the surface it leaves a faint wake
		BlockPos above = BlockPos.containing(pos.x, pos.y + 3, pos.z);
		if (level.getFluidState(above).isEmpty() && this.random.nextInt(2) == 0) {
			level.addParticle(ParticleTypes.SPLASH, tail.x, Math.floor(pos.y) + 3.0, tail.z, 0, 0.1, 0);
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isPushedByFluid() {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 256 * 256;
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
