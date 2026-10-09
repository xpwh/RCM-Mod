package de.rcm.ballistic.entity;

import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.block.JammerBlockEntity;
import de.rcm.ballistic.block.LaunchPadBlock;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.explosion.DetonationManager;
import de.rcm.ballistic.item.LauncherLink;
import de.rcm.ballistic.item.TargetData;
import de.rcm.ballistic.item.TargetDesignatorItem;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

public class MissileEntity extends Entity implements AirThreat, de.rcm.ballistic.launch.Ownership.Owned {
	/** Whose it is (see {@link de.rcm.ballistic.launch.Ownership}). */
	private java.util.@org.jspecify.annotations.Nullable UUID owner;

	@Override
	public java.util.@org.jspecify.annotations.Nullable UUID getOwner() {
		return this.owner;
	}

	@Override
	public void setOwner(java.util.@org.jspecify.annotations.Nullable UUID owner) {
		this.owner = owner;
	}

	public static final int IDLE = 0;
	public static final int COUNTDOWN = 1;
	public static final int IGNITION = 2;
	public static final int FLIGHT = 3;
	/** Cold launch out of a silo: a gas generator pushes the missile out before the engine lights. */
	public static final int EJECT = 4;

	private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_AGE = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<BlockPos> DATA_TARGET = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.BLOCK_POS);
	private static final EntityDataAccessor<Vector3fc> DATA_LAUNCH = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.VECTOR3);
	/** Nose direction of free-flying (cruise) missiles; ballistic ones derive it from the trajectory. */
	private static final EntityDataAccessor<Vector3fc> DATA_DIR = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.VECTOR3);
	/** MIRV bus: warheads still on board, or -1 before the bus has started releasing them. */
	private static final EntityDataAccessor<Integer> DATA_BUS = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.INT);
	/** A launch gone wrong: 0 all well, 1 out of control with the motor still burning, 2 tumbling with the motor dead. */
	private static final EntityDataAccessor<Integer> DATA_FAIL = SynchedEntityData.defineId(MissileEntity.class, EntityDataSerializers.INT);
	public static final int FAIL_NONE = 0;
	public static final int FAIL_BURNING = 1;
	public static final int FAIL_DEAD = 2;
	/** What is planned to go wrong with this launch (decided when it lifts off). */
	private static final int PLAN_NONE = 0;
	private static final int PLAN_PAD_EXPLOSION = 1;
	private static final int PLAN_TUMBLE = 2;
	private static final int PLAN_NO_IGNITION = 3;
	private int misfirePlan;
	/** Tick (of the state) the planned failure happens. */
	private int misfireAt;
	private Vec3 failVelocity = Vec3.ZERO;
	private Vec3 failAxis = new Vec3(1, 0, 0);
	private int failAge;
	private int failBurn;
	/** Range safety: the flight termination system blows it up at this failure tick (-1 = it comes down whole). */
	private int failTerminate = -1;

	private static final int CRUISE_BOOST_TICKS = 32;
	private static final double CRUISE_ALTITUDE = 22.0;
	/** Cruise missiles and drones fly this much faster once the booster is done. */
	private static final double CRUISE_SPEEDUP = 1.6;

	/** Minimum horizontal distance between pad and target. */
	public static final int MIN_RANGE = 48;

	private final MissileType missileType;
	private int stateAge;
	private boolean surfaceTarget;
	/** Already thrown off by a jammer (it only happens once). */
	private boolean gpsJammed;
	/** Ticks since the ejected missile left the water (or the silo). */
	private int airAge;
	/** Cold launch from under water (submarine): it is shot out of the sea and lights in mid-air. */
	private boolean seaLaunch;
	private @Nullable MissileTrajectory trajectory;

	// ---- client-side bookkeeping (used by the client tick hook) ----
	public int clientStateAge;
	public int lastSeenState = -1;
	public boolean engineSoundStarted;
	public boolean incomingPlayed;
	/** Client: stages seen separated (for the separation effect). */
	public int clientStagesSeen;
	/** Server: stages separated so far. */
	private int stagesDropped;
	/** Server: warheads the MIRV bus has let go of (-1 = not yet releasing). */
	private int mirvReleased = -1;
	/** Client: a submarine-launched missile has broken the surface (spray already shown). */
	public boolean clientBroached;
	public @Nullable Vec3 lastNozzlePos;
	public boolean jetSoundStarted;
	public boolean sonicBoomPlayed;
	private Vec3 clientPrevDir = new Vec3(0, 1, 0);
	private Vec3 clientDir = new Vec3(0, 1, 0);

	public MissileEntity(EntityType<? extends MissileEntity> entityType, Level level, MissileType missileType) {
		super(entityType, level);
		this.missileType = missileType;
		this.noPhysics = true;
		this.setNoGravity(true);
	}

	public MissileType getMissileType() {
		return this.missileType;
	}

	public Item getItemForm() {
		return ModRegistry.missileItem(this.missileType);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_STATE, IDLE);
		builder.define(DATA_AGE, 0);
		builder.define(DATA_TARGET, BlockPos.ZERO);
		builder.define(DATA_LAUNCH, new Vector3f());
		builder.define(DATA_DIR, new Vector3f(0, 1, 0));
		builder.define(DATA_BUS, -1);
		builder.define(DATA_FAIL, FAIL_NONE);
	}

	// ------------------------------------------------------------------ state accessors

	public int getState() {
		return this.entityData.get(DATA_STATE);
	}

	private void setState(int state) {
		this.entityData.set(DATA_STATE, state);
		this.stateAge = 0;
		this.entityData.set(DATA_AGE, 0);
	}

	public int getSyncedAge() {
		return this.entityData.get(DATA_AGE);
	}

	public BlockPos getTarget() {
		return this.entityData.get(DATA_TARGET);
	}

	public Vec3 getLaunchPos() {
		Vector3fc v = this.entityData.get(DATA_LAUNCH);
		return new Vec3(v.x(), v.y(), v.z());
	}

	public Vec3 getFlightDir() {
		Vector3fc v = this.entityData.get(DATA_DIR);
		return new Vec3(v.x(), v.y(), v.z());
	}

	private void setFlightDir(Vec3 dir) {
		this.entityData.set(DATA_DIR, new Vector3f((float) dir.x, (float) dir.y, (float) dir.z));
	}

	/** Silo hatch position during a cold launch (kept in the launch position until ignition). */
	public Vec3 getSiloTop() {
		return this.getLaunchPos();
	}

	/**
	 * Rocket motor running: during ignition, then for a ballistic missile until burnout (after that
	 * it coasts silently on its arc), for a cruise missile only during the booster climb-out.
	 */
	public boolean isBoosterBurning() {
		int state = this.getState();
		if (state == IGNITION) {
			return true;
		}
		if (state != FLIGHT) {
			return false;
		}
		if (this.getFailure() != FAIL_NONE) {
			return this.getFailure() == FAIL_BURNING;
		}
		return this.missileType.isCruise() ? this.clientOrServerAge() < CRUISE_BOOST_TICKS : this.getTrajectory().boosting(this.clientOrServerAge());
	}

	public boolean isJetRunning() {
		return this.missileType.isCruise() && this.getState() == FLIGHT && this.getFailure() == FAIL_NONE;
	}

	private int clientOrServerAge() {
		return this.level().isClientSide() ? this.clientStateAge : this.stateAge;
	}

	public boolean isEngineOn() {
		int state = this.getState();
		return state == IGNITION || state == FLIGHT;
	}

	public MissileTrajectory getTrajectory() {
		if (this.trajectory == null) {
			this.trajectory = new MissileTrajectory(this.getLaunchPos(), Vec3.atBottomCenterOf(this.getTarget()), this.missileType.apexScale, this.missileType.durationScale);
		}
		return this.trajectory;
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (DATA_TARGET.equals(accessor) || DATA_LAUNCH.equals(accessor)) {
			this.trajectory = null;
		}
		if (this.level().isClientSide()) {
			if (DATA_STATE.equals(accessor)) {
				this.clientStateAge = 0;
			} else if (DATA_AGE.equals(accessor) && Math.abs(this.getSyncedAge() - this.clientStateAge) > 2) {
				this.clientStateAge = this.getSyncedAge();
			}
		}
	}

	/** A launch gone wrong (see {@link #FAIL_BURNING}, {@link #FAIL_DEAD}). */
	public int getFailure() {
		return this.entityData.get(DATA_FAIL);
	}

	/** Nose direction, interpolated for rendering. */
	public Vec3 getNoseDirection(float partialTick) {
		if (this.getState() != FLIGHT) {
			return new Vec3(0, 1, 0);
		}
		if (this.missileType.isCruise() || this.getFailure() != FAIL_NONE) {
			Vec3 d = this.clientPrevDir.lerp(this.clientDir, partialTick);
			return d.lengthSqr() < 1.0E-6 ? new Vec3(0, 1, 0) : d.normalize();
		}
		return this.getTrajectory().direction(this.clientStateAge - 1 + partialTick);
	}

	// ------------------------------------------------------------------ ticking

	@Override
	public void tick() {
		super.tick();
		if (this.level() instanceof ServerLevel serverLevel) {
			this.serverTick(serverLevel);
		} else {
			this.clientStateAge++;
			if (this.getState() == FLIGHT) {
				if (this.missileType.isCruise() || this.getFailure() != FAIL_NONE) {
					this.clientPrevDir = this.clientDir;
					this.clientDir = this.getFlightDir();
				} else {
					this.setPos(this.getTrajectory().position(this.clientStateAge));
				}
			}
			ClientHooks.missileClientTick.accept(this);
		}
	}

	private void serverTick(ServerLevel level) {
		switch (this.getState()) {
			case COUNTDOWN -> this.countdownTick(level);
			case IGNITION -> this.ignitionTick(level);
			case FLIGHT -> {
				ThreatTracker.report(level, this);
				this.flightTick(level);
			}
			case EJECT -> this.ejectTick(level);
			default -> {
			}
		}
	}

	/** Keeps the missile's own chunk loaded while it is armed, so remote launches work from anywhere. */
	private void keepLoaded(ServerLevel level) {
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.blockPosition()), 2);
	}

	/** Resolves a surface target to the real ground height once its chunk is ticking. */
	public static TargetData resolveSurface(ServerLevel level, TargetData target) {
		if (!target.surface()) {
			return target;
		}
		BlockPos t = target.pos();
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(t), 2);
		if (level.isPositionEntityTicking(t)) {
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, t.getX(), t.getZ());
			return new TargetData(new BlockPos(t.getX(), y, t.getZ()), false);
		}
		return target;
	}

	/** Starts a cold launch from a silo; the missile is spawned in the shaft below the hatch. */
	public void startSiloLaunch(TargetData target, Vec3 siloTop) {
		this.entityData.set(DATA_TARGET, target.pos());
		this.surfaceTarget = target.surface();
		this.entityData.set(DATA_LAUNCH, new Vector3f((float) siloTop.x, (float) siloTop.y, (float) siloTop.z));
		this.trajectory = null;
		this.setState(EJECT);
	}

	private void ejectTick(ServerLevel level) {
		this.keepLoaded(level);
		if (this.surfaceTarget) {
			TargetData resolved = resolveSurface(level, new TargetData(this.getTarget(), true));
			if (!resolved.surface()) {
				this.entityData.set(DATA_TARGET, resolved.pos());
				this.surfaceTarget = false;
			}
		}
		Vec3 top = this.getSiloTop();
		if (this.stateAge == 0) {
			level.playSound(null, top.x, top.y, top.z, ModRegistry.AIR_LAUNCH, SoundSource.BLOCKS, 8.0F, 1.0F);
		}
		// gas generator: hard push, then the missile coasts and slows down above the hatch. Under
		// water (submarine launch) it rises in a bubble column and only lights its motor once it has
		// broken the surface.
		boolean submerged = !level.getFluidState(BlockPos.containing(this.position())).isEmpty();
		double vy;
		if (submerged) {
			vy = 0.9;
			this.airAge = 0;
			this.seaLaunch = true;
			level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, this.getX(), this.getY(), this.getZ(), 12, 0.5, 0.8, 0.5, 0.1);
		} else {
			if (this.airAge == 0 && this.stateAge > 2 && level.getFluidState(BlockPos.containing(this.position().subtract(0, 1.5, 0))).isSource()) {
				// broaching: a white plume of spray as the missile leaves the water
				level.sendParticles(ParticleTypes.SPLASH, this.getX(), this.getY(), this.getZ(), 200, 1.5, 0.5, 1.5, 0.6);
				level.sendParticles(ParticleTypes.CLOUD, this.getX(), this.getY(), this.getZ(), 40, 1.2, 1.0, 1.2, 0.1);
				level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.WATER_SPLASH_HUGE, SoundSource.BLOCKS, 8.0F, 1.0F);
			}
			if (this.seaLaunch) {
				// thrown clear of the water by the gas bubble: it keeps rising unpowered, slowing like a
				// thrown stone, until the first stage lights near the top of the arc
				vy = 1.9 - this.airAge * 0.11;
			} else {
				vy = Math.max(0.25, 1.5 - this.airAge * 0.045);
			}
			this.airAge++;
		}
		Vec3 next = this.position().add(0, vy, 0);
		this.stateAge++;
		this.setPos(next);
		boolean lightUp = this.seaLaunch
			? !submerged && this.airAge > 8 && vy < 0.3
			: !submerged && this.airAge > 3 && (next.y > top.y + 2.5 + this.missileType.length * 0.15 || this.airAge > 120);
		if (lightUp || this.stateAge > 600) {
			// the engine lights in mid-air
			this.entityData.set(DATA_LAUNCH, new Vector3f((float) next.x, (float) next.y, (float) next.z));
			this.applyJamming(level);
			this.trajectory = null;
			this.planMisfire(level, true);
			this.setState(FLIGHT);
			if (this.misfirePlan == PLAN_NO_IGNITION) {
				// ...or it doesn't: the motor never lights and the missile drops back down
				this.startFailure(level, new Vec3(0, vy * 0.5, 0), false);
				return;
			}
			// the ignition roar is played client-side, riding along with the missile
		}
	}

	private void countdownTick(ServerLevel level) {
		this.keepLoaded(level);
		BlockPos target = this.getTarget();
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(target), 2);
		if (this.surfaceTarget && level.isPositionEntityTicking(target)) {
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, target.getX(), target.getZ());
			this.entityData.set(DATA_TARGET, new BlockPos(target.getX(), y, target.getZ()));
			this.surfaceTarget = false;
		}

		int remaining = this.missileType.countdownTicks - this.stateAge;
		if (this.stateAge % 20 == 0) {
			level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.COUNTDOWN_BEEP, SoundSource.BLOCKS, 3.0F, remaining <= 60 ? 1.4F : 1.0F);
			int seconds = Mth.ceil(remaining / 20.0F);
			Component msg = Component.literal((this.missileType.isNuclear() ? "☢ " : "⚠ "))
				.append(Component.translatable("message.ballisticmissiles.countdown", seconds))
				.withStyle(seconds <= 3 ? ChatFormatting.RED : ChatFormatting.GOLD, ChatFormatting.BOLD);
			for (ServerPlayer player : level.players()) {
				if (player.distanceToSqr(this) < 96 * 96) {
					player.displayClientMessage(msg, true);
				}
			}
		}
		if (this.stateAge % 60 == 0) {
			level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.SIREN, SoundSource.BLOCKS, 8.0F, 1.0F);
		}

		if (++this.stateAge >= this.missileType.countdownTicks) {
			Vec3 pos = this.position();
			this.entityData.set(DATA_LAUNCH, new Vector3f((float) pos.x, (float) pos.y, (float) pos.z));
			this.applyJamming(level);
			this.trajectory = null;
			this.planMisfire(level, false);
			this.setState(IGNITION);
			// the ignition roar is played client-side, riding along with the missile
		}
	}

	private void ignitionTick(ServerLevel level) {
		this.keepLoaded(level);
		if (this.misfirePlan == PLAN_PAD_EXPLOSION && this.stateAge >= this.misfireAt) {
			this.padExplosion(level);
			return;
		}
		if (this.stateAge % 10 == 0) {
			// Scorch & push away anything standing in the exhaust.
			AABB blast = this.getBoundingBox().inflate(3.0, 0.0, 3.0).expandTowards(0, -2, 0);
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, blast)) {
				entity.igniteForSeconds(4.0F);
				entity.hurtServer(level, level.damageSources().inFire(), 4.0F);
			}
		}
		if (++this.stateAge >= this.missileType.ignitionTicks) {
			this.setState(FLIGHT);
		}
	}

	private void flightTick(ServerLevel level) {
		if (this.getFailure() != FAIL_NONE) {
			this.failureTick(level);
			return;
		}
		if (this.misfirePlan == PLAN_TUMBLE && this.stateAge >= this.misfireAt) {
			this.startFailure(level, this.currentVelocity(), true);
			return;
		}
		if (this.missileType.isCruise()) {
			this.cruiseTick(level);
			return;
		}
		MissileTrajectory path = this.getTrajectory();

		// Keep the chunks along the upcoming path loaded and entity-ticking.
		for (int k = 0; k <= 40; k += 2) {
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(path.position(this.stateAge + k))), 3);
		}
		if (path.duration() - this.stateAge < 60) {
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.getTarget()), 3);
		}

		Vec3 next = path.position(this.stateAge + 1);
		if (next.y > level.getMinY() && next.y < level.getMaxY() && !level.isPositionEntityTicking(BlockPos.containing(next))) {
			return; // wait for the chunk to finish loading
		}
		this.stateAge++;
		if (this.stateAge % 10 == 0) {
			this.entityData.set(DATA_AGE, this.stateAge);
		}

		Vec3 dirOld = path.direction(this.stateAge - 1);
		Vec3 dirNew = path.direction(this.stateAge);
		Vec3 noseOld = this.position().add(dirOld.scale(this.missileType.length));
		Vec3 noseNew = next.add(dirNew.scale(this.missileType.length));

		if (this.stateAge > 15) {
			Vec3 hit = this.findImpact(level, noseOld, noseNew);
			if (hit != null) {
				this.detonate(level, hit);
				return;
			}
		}

		this.setPos(next);
		// staging: a burnt-out stage separates and falls away, the next one lights
		int dropped = MissileStages.dropped(this.missileType, path, this.stateAge);
		while (this.stagesDropped < dropped) {
			SpentStageEntity.separate(level, this.missileType, this.stagesDropped, next, dirNew, path.velocity(this.stateAge));
			this.stagesDropped++;
		}
		if (this.mirvReleased >= 0) {
			// the bus manoeuvres between releases and lets its warheads go one after the other
			if (this.stateAge % 5 == 0) {
				Vec3 bus = next.add(dirNew.scale(9.6 * this.missileType.scale));
				DetonationManager.releaseMirvWarhead(level, bus, Vec3.atBottomCenterOf(this.getTarget()), this.mirvReleased, this);
				this.mirvReleased++;
				this.entityData.set(DATA_BUS, DetonationManager.MIRV_WARHEADS - this.mirvReleased);
				if (this.mirvReleased >= DetonationManager.MIRV_WARHEADS) {
					this.discard();
				}
			}
			return;
		}
		if (this.missileType.warhead == MissileType.Warhead.METEOR && this.stateAge > 30 && (next.y > level.getMaxY() + 40 || dirNew.y < 0.0)) {
			// gone into space: the payload comes back as a meteor shower
			DetonationManager.meteorShower(level, Vec3.atBottomCenterOf(this.getTarget()), this);
			this.discard();
			return;
		}
		if (this.missileType.warhead == MissileType.Warhead.CLUSTER && this.stateAge > path.duration() * 0.6 && dirNew.y < -0.3
			&& next.y - this.getTarget().getY() < 75) {
			DetonationManager.releaseCluster(level, next, path.velocity(this.stateAge), this);
			this.discard();
			return;
		}
		if (this.missileType.warhead == MissileType.Warhead.MIRV && this.stateAge > path.duration() * 0.45 && dirNew.y < 0.05) {
			// out in space just past the top of the arc
			// the shroud comes off and the post-boost vehicle starts dispensing its warheads
			this.mirvReleased = 0;
			this.entityData.set(DATA_BUS, DetonationManager.MIRV_WARHEADS);
			return;
		}
		if (this.missileType.warhead == MissileType.Warhead.INCENDIARY && this.stateAge > path.duration() * 0.6 && dirNew.y < -0.3
			&& next.y - this.getTarget().getY() < 48) {
			DetonationManager.releaseIncendiary(level, next, path.velocity(this.stateAge), this);
			this.discard();
			return;
		}
		if (this.missileType.warhead == MissileType.Warhead.EMP && this.stateAge > path.duration() * 0.5 && dirNew.y < -0.2
			&& next.y - this.getTarget().getY() < 110) {
			DetonationManager.emp(level, next, this); // high-altitude burst, nothing reaches the ground
			this.discard();
			return;
		}
		if (next.y < level.getMinY() - 64 || this.stateAge > path.duration() + 600) {
			this.discard();
		}
	}

	/**
	 * Cruise profile: solid booster climb-out, then a turbofan run that follows the terrain
	 * {@value #CRUISE_ALTITUDE} blocks up, then a pop-up manoeuvre and a steep terminal dive.
	 */
	private void cruiseTick(ServerLevel level) {
		// a target picked far outside the loaded world: find the real ground height once its chunk
		// is loaded (the ticket below loads it), so the missile flies into the ground, not thin air
		if (this.surfaceTarget && this.stateAge % 10 == 0) {
			TargetData resolved = resolveSurface(level, new TargetData(this.getTarget(), true));
			if (!resolved.surface()) {
				this.entityData.set(DATA_TARGET, resolved.pos());
				this.surfaceTarget = false;
			}
		}
		Vec3 pos = this.position();
		Vec3 target = Vec3.atBottomCenterOf(this.getTarget());
		Vec3 dir = this.getFlightDir();
		double dx = target.x - pos.x;
		double dz = target.z - pos.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		Vec3 heading = horizontal > 1.0E-3 ? new Vec3(dx / horizontal, 0, dz / horizontal) : new Vec3(1, 0, 0);

		// anti-radiation missile: lock onto the strongest emitter near the aim point
		if (this.missileType.warhead == MissileType.Warhead.ANTI_RADAR && horizontal < 320 && this.stateAge % 10 == 0) {
			BlockPos emitter = this.findEmitter(level);
			if (emitter != null && !emitter.equals(this.getTarget())) {
				this.entityData.set(DATA_TARGET, emitter);
				target = Vec3.atBottomCenterOf(emitter);
				dx = target.x - pos.x;
				dz = target.z - pos.z;
				horizontal = Math.sqrt(dx * dx + dz * dz);
				heading = horizontal > 1.0E-3 ? new Vec3(dx / horizontal, 0, dz / horizontal) : heading;
			}
		}

		// guidance jamming takes hold as the missile closes in on a jammed area
		if (horizontal < JammerBlockEntity.RADIUS + 120 && this.stateAge % 10 == 0 && this.missileType.warhead != MissileType.Warhead.ANTI_RADAR) {
			this.applyJamming(level);
			target = Vec3.atBottomCenterOf(this.getTarget());
			dx = target.x - pos.x;
			dz = target.z - pos.z;
			horizontal = Math.sqrt(dx * dx + dz * dz);
			heading = horizontal > 1.0E-3 ? new Vec3(dx / horizontal, 0, dz / horizontal) : heading;
		}

		// kamikaze drone: its camera seeker picks the nearest living target around the aim point
		// and the drone flies straight into it
		boolean drone = this.missileType.model == MissileType.Model.DRONE;
		if (drone && horizontal < 110 && this.stateAge % 4 == 0) {
			Vec3 aimAt = target;
			LivingEntity prey = null;
			double best = 24.0 * 24.0;
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(aimAt, aimAt).inflate(24.0, 12.0, 24.0), e -> e.isAlive() && !e.isSpectator())) {
				double d = e.position().distanceToSqr(aimAt);
				if (d < best) {
					best = d;
					prey = e;
				}
			}
			if (prey != null) {
				this.entityData.set(DATA_TARGET, prey.blockPosition());
				target = prey.position().add(0, prey.getBbHeight() * 0.5, 0);
				dx = target.x - pos.x;
				dz = target.z - pos.z;
				horizontal = Math.sqrt(dx * dx + dz * dz);
				heading = horizontal > 1.0E-3 ? new Vec3(dx / horizontal, 0, dz / horizontal) : heading;
			}
		}

		// keep the corridor ahead loaded
		for (int k = 0; k <= 160; k += 16) {
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(pos.add(heading.scale(k)))), 3);
		}

		int age = this.stateAge;
		Vec3 desired;
		double speed;
		double turn;
		if (age < CRUISE_BOOST_TICKS) {
			double f = (double) age / CRUISE_BOOST_TICKS;
			desired = new Vec3(0, 1, 0).lerp(heading.add(0, 0.55, 0).normalize(), Math.min(1.0, f * 1.4));
			speed = (0.3 + 2.6 * f) * Math.min(1.0, this.missileType.cruiseSpeed() / 3.2 + 0.25);
			turn = 0.18;
		} else if (horizontal > 75) {
			double ground = pos.y - CRUISE_ALTITUDE;
			for (int k = 0; k <= 64; k += 8) {
				Vec3 probe = pos.add(heading.scale(k));
				int px = Mth.floor(probe.x);
				int pz = Mth.floor(probe.z);
				if (level.hasChunk(px >> 4, pz >> 4)) {
					ground = Math.max(ground, level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz));
				}
			}
			double climb = Mth.clamp((ground + CRUISE_ALTITUDE - pos.y) * 0.06, -0.35, 0.5);
			desired = heading.add(0, climb, 0).normalize();
			speed = this.missileType.cruiseSpeed() * CRUISE_SPEEDUP;
			turn = 0.1;
		} else if (drone) {
			// no pop-up: the drone goes straight for it, accelerating into a steep dive
			desired = target.subtract(pos).normalize();
			speed = this.missileType.cruiseSpeed() * CRUISE_SPEEDUP * (horizontal > 40 ? 1.2 : 1.5);
			turn = 0.4;
		} else if (horizontal > 32) {
			desired = heading.add(0, 0.6, 0).normalize(); // pop-up
			speed = this.missileType.cruiseSpeed() * CRUISE_SPEEDUP;
			turn = 0.16;
		} else {
			desired = target.subtract(pos).normalize(); // terminal dive
			speed = this.missileType.cruiseSpeed() * CRUISE_SPEEDUP * 1.15;
			turn = 0.32;
		}

		Vec3 newDir = dir.add(desired.subtract(dir).scale(turn));
		newDir = newDir.lengthSqr() < 1.0E-6 ? desired : newDir.normalize();
		Vec3 next = pos.add(newDir.scale(speed));
		if (next.y > level.getMinY() && next.y < level.getMaxY() && !level.isPositionEntityTicking(BlockPos.containing(next))) {
			return; // wait for the chunk to finish loading
		}
		this.stateAge++;
		if (this.stateAge % 10 == 0) {
			this.entityData.set(DATA_AGE, this.stateAge);
		}

		if (this.stateAge > 12) {
			Vec3 hit = this.findImpact(level, pos.add(dir.scale(this.missileType.length)), next.add(newDir.scale(this.missileType.length)));
			if (hit != null) {
				this.setFlightDir(newDir);
				this.detonate(level, hit);
				return;
			}
		}
		this.setFlightDir(newDir);
		this.setPos(next);
		if (next.y < level.getMinY() - 64 || this.stateAge > 12000) {
			this.discard();
		}
	}

	private @Nullable BlockPos findEmitter(ServerLevel level) {
		Vec3 aim = Vec3.atBottomCenterOf(this.getTarget());
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (DefenseNetwork.Kind kind : new DefenseNetwork.Kind[] {DefenseNetwork.Kind.RADAR, DefenseNetwork.Kind.AIR_DEFENSE, DefenseNetwork.Kind.JAMMER}) {
			List<BlockPos> sites = DefenseNetwork.find(level, kind, aim, 128.0);
			if (!sites.isEmpty()) {
				// search radars shine brightest, fire-control radars of the batteries come second
				double score = sites.get(0).distToCenterSqr(aim) * (kind == DefenseNetwork.Kind.RADAR ? 0.5 : 1.0);
				if (score < bestScore) {
					bestScore = score;
					best = sites.get(0);
				}
			}
		}
		return best;
	}

	private @Nullable Vec3 findImpact(ServerLevel level, Vec3 from, Vec3 to) {
		BlockHitResult blockHit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
		Vec3 end = blockHit.getType() == HitResult.Type.MISS ? to : blockHit.getLocation();

		AABB sweep = new AABB(from, end).inflate(1.0);
		Vec3 best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity entity : level.getEntities(this, sweep, e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator())) {
			var clip = entity.getBoundingBox().inflate(0.5).clip(from, end);
			if (clip.isPresent()) {
				double d = clip.get().distanceToSqr(from);
				if (d < bestDist) {
					bestDist = d;
					best = clip.get();
				}
			}
		}
		if (best != null) {
			return best;
		}
		return blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getLocation();
	}

	// ------------------------------------------------------------------ launches gone wrong

	/**
	 * Decides when the missile lifts off whether this launch goes wrong (the chance is a world
	 * setting): from the pad it can blow up before it clears the stand, or lose control a few
	 * seconds up and tumble; a cold launch can fail to light at all.
	 */
	private void planMisfire(ServerLevel level, boolean coldLaunch) {
		this.misfirePlan = PLAN_NONE;
		int chance = de.rcm.ballistic.config.ServerConfig.misfireChance;
		var random = level.getRandom();
		if (chance <= 0 || random.nextInt(100) >= chance) {
			return;
		}
		float roll = random.nextFloat();
		if (coldLaunch) {
			this.misfirePlan = roll < 0.4F ? PLAN_NO_IGNITION : PLAN_TUMBLE;
		} else {
			this.misfirePlan = roll < 0.4F ? PLAN_PAD_EXPLOSION : PLAN_TUMBLE;
		}
		this.misfireAt = this.misfirePlan == PLAN_PAD_EXPLOSION
			? (int) (this.missileType.ignitionTicks * (0.25F + 0.6F * random.nextFloat()))
			: 12 + random.nextInt(60);
	}

	/** The players who see it happen (and the one who launched it, wherever they are). */
	private void announce(ServerLevel level, String key, ChatFormatting color) {
		Component msg = Component.literal("⚠ ").append(Component.translatable(key, Component.translatable(this.nameKey())))
			.withStyle(color, ChatFormatting.BOLD);
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(this) < 400 * 400 || player.distanceToSqr(this.getLaunchPos()) < 400 * 400) {
				player.displayClientMessage(msg, true);
			}
		}
	}

	/** Blown up on the stand: the propellant goes up, the warhead (with its safety still on) does not. */
	private void padExplosion(ServerLevel level) {
		Vec3 base = this.position().add(0, this.missileType.length * 0.25, 0);
		this.announce(level, "message.ballisticmissiles.misfire_pad", ChatFormatting.RED);
		DetonationManager.fuelExplosion(level, base, this, 3.0F + this.missileType.length * 0.35F);
		this.discard();
	}

	/** Control lost: from here on it flies (and falls) by its own physics, not the planned arc. */
	private void startFailure(ServerLevel level, Vec3 velocity, boolean burning) {
		var random = level.getRandom();
		this.failVelocity = velocity;
		this.failAge = 0;
		this.failBurn = burning ? 30 + random.nextInt(70) : 0;
		Vec3 dir = this.currentDirection();
		// it starts to cartwheel about an axis across its line of flight
		Vec3 across = dir.cross(new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()));
		this.failAxis = across.lengthSqr() < 1.0E-6 ? new Vec3(1, 0, 0) : across.normalize();
		// range safety blows most of them up in the air before they come down somewhere they shouldn't
		this.failTerminate = burning && random.nextFloat() < 0.5F ? 40 + random.nextInt(50) : -1;
		this.setFlightDir(dir);
		this.entityData.set(DATA_FAIL, burning ? FAIL_BURNING : FAIL_DEAD);
		this.entityData.set(DATA_AGE, this.stateAge);
		this.announce(level, burning ? "message.ballisticmissiles.misfire_tumble" : "message.ballisticmissiles.misfire_no_ignition", ChatFormatting.GOLD);
	}

	private void failureTick(ServerLevel level) {
		this.keepLoaded(level);
		this.stateAge++;
		this.failAge++;
		Vec3 dir = this.getFlightDir();
		boolean burning = this.getFailure() == FAIL_BURNING;
		// the cartwheel speeds up as the aerodynamic forces take hold
		double spin = burning ? Math.min(0.16, 0.012 + this.failAge * 0.0025) : Math.min(0.08, 0.01 + this.failAge * 0.001);
		dir = rotate(dir, this.failAxis, spin);
		Vec3 v = this.failVelocity;
		if (burning) {
			v = v.add(dir.scale(0.05 + 0.04 * this.missileType.scale));
			if (--this.failBurn <= 0) {
				this.entityData.set(DATA_FAIL, FAIL_DEAD);
			}
		}
		v = v.scale(0.985).add(0, -0.045, 0);
		this.failVelocity = v;
		this.setFlightDir(dir);
		if (this.failTerminate > 0 && this.failAge >= this.failTerminate) {
			// flight termination: the range safety officer pushes the button
			this.announce(level, "message.ballisticmissiles.misfire_terminated", ChatFormatting.YELLOW);
			DetonationManager.intercepted(level, this.position().add(dir.scale(this.missileType.length * 0.5)), this);
			level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.EXPLOSION_NEAR, SoundSource.BLOCKS, 12.0F, 1.0F);
			this.discard();
			return;
		}
		Vec3 pos = this.position();
		Vec3 next = pos.add(v);
		Vec3 nose = next.add(dir.scale(this.missileType.length));
		var hit = level.clip(new net.minecraft.world.level.ClipContext(pos, next, net.minecraft.world.level.ClipContext.Block.COLLIDER,
			net.minecraft.world.level.ClipContext.Fluid.ANY, this));
		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) {
			var noseHit = level.clip(new net.minecraft.world.level.ClipContext(pos.add(dir.scale(this.missileType.length)), nose,
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.ANY, this));
			if (noseHit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
				hit = noseHit;
			}
		}
		if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS || next.y < level.getMinY() || this.failAge > 1200) {
			Vec3 at = hit.getLocation();
			this.crashFailed(level, at);
			return;
		}
		if (!level.isPositionEntityTicking(BlockPos.containing(next))) {
			this.discard();
			return;
		}
		this.setPos(next);
	}

	/**
	 * The failed missile coming down: the remaining propellant burns and blows; a plain high-explosive
	 * warhead may go off too, anything nuclear or exotic stays safe.
	 */
	private void crashFailed(ServerLevel level, Vec3 at) {
		boolean water = !level.getFluidState(BlockPos.containing(at)).isEmpty();
		MissileType.Warhead w = this.missileType.warhead;
		boolean plainWarhead = w == MissileType.Warhead.HIGH_EXPLOSIVE || w == MissileType.Warhead.THERMOBARIC || w == MissileType.Warhead.BUNKER_BUSTER
			|| w == MissileType.Warhead.INCENDIARY || w == MissileType.Warhead.CRUISE || w == MissileType.Warhead.DRONE;
		if (plainWarhead && !water && level.getRandom().nextFloat() < 0.4F) {
			this.detonate(level, at);
			return;
		}
		if (water) {
			DetonationManager.aircraftCrash(level, at, this, true, true);
		} else {
			DetonationManager.fuelExplosion(level, at, this, 2.5F + this.missileType.length * 0.25F);
		}
		this.discard();
	}

	private static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
		double cos = Math.cos(angle);
		double sin = Math.sin(angle);
		return v.scale(cos).add(axis.cross(v).scale(sin)).add(axis.scale(axis.dot(v) * (1.0 - cos))).normalize();
	}

	private void detonate(ServerLevel level, Vec3 pos) {
		Vec3 dir = this.missileType.isCruise() || this.getFailure() != FAIL_NONE ? this.getFlightDir() : this.getTrajectory().direction(this.stateAge);
		DetonationManager.detonate(level, pos, dir, this.missileType.warhead, this);
		this.discard();
	}

	// ------------------------------------------------------------------ air defense / radar

	/** Interceptors currently flying at this missile, so batteries don't waste them. */
	private int engagements;

	/** MIRV bus: warheads still on board, or -1 while it is a whole missile. */
	public int getBusWarheads() {
		return this.entityData.get(DATA_BUS);
	}

	public boolean isInFlight() {
		return this.getState() == FLIGHT;
	}

	/** Server side: current nose direction. */
	public Vec3 currentDirection() {
		if (this.getState() != FLIGHT) {
			return new Vec3(0, 1, 0);
		}
		return this.missileType.isCruise() || this.getFailure() != FAIL_NONE ? this.getFlightDir() : this.getTrajectory().direction(this.stateAge);
	}

	/** Server side: current velocity in blocks per tick. */
	public Vec3 currentVelocity() {
		if (this.getFailure() != FAIL_NONE) {
			return this.failVelocity;
		}
		if (this.getState() == FLIGHT && !this.missileType.isCruise()) {
			return this.getTrajectory().velocity(this.stateAge);
		}
		return new Vec3(this.getX() - this.xo, this.getY() - this.yo, this.getZ() - this.zo);
	}

	/** Server side: estimated ticks until impact. */
	@Override
	public int etaTicks() {
		if (this.getState() != FLIGHT) {
			return -1;
		}
		if (this.missileType.isCruise()) {
			Vec3 t = Vec3.atBottomCenterOf(this.getTarget());
			return (int) (Math.hypot(t.x - this.getX(), t.z - this.getZ()) / (this.missileType.cruiseSpeed() * CRUISE_SPEEDUP));
		}
		return Math.max(0, this.getTrajectory().duration() - this.stateAge);
	}

	/** Shot down by an interceptor: breaks up in the air, the warhead does not go off. */
	public void intercept(ServerLevel level) {
		DetonationManager.intercepted(level, this.position().add(this.currentDirection().scale(this.missileType.length * 0.5)), this);
		this.discard();
	}

	@Override
	public Entity asEntity() {
		return this;
	}

	@Override
	public boolean isActiveThreat() {
		return this.getState() == FLIGHT && this.isAlive() && this.getFailure() == FAIL_NONE;
	}

	@Override
	public Vec3 aimPoint(int ticksAhead) {
		if (this.missileType.isCruise() || this.getState() != FLIGHT) {
			Vec3 dir = this.currentDirection();
			return this.position().add(this.currentVelocity().scale(ticksAhead)).add(dir.scale(this.missileType.length * 0.5));
		}
		MissileTrajectory path = this.getTrajectory();
		double t = this.stateAge + ticksAhead;
		return path.position(t).add(path.direction(t).scale(this.missileType.length * 0.5));
	}

	@Override
	public Vec3 threatVelocity() {
		return this.currentVelocity();
	}

	@Override
	public Vec3 predictedImpact() {
		return Vec3.atBottomCenterOf(this.getTarget());
	}

	@Override
	public ThreatClass threatClass() {
		if (this.missileType.warhead == MissileType.Warhead.HYPERSONIC) {
			return ThreatClass.HYPERSONIC;
		}
		return this.missileType.isCruise() ? ThreatClass.CRUISE : ThreatClass.BALLISTIC;
	}

	@Override
	public double radarCrossSection() {
		return this.missileType.radarCrossSection();
	}

	@Override
	public float killProbability() {
		return this.missileType.killProbability();
	}

	@Override
	public String nameKey() {
		return "entity.ballisticmissiles." + this.missileType.id;
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
		this.intercept(level);
	}

	/**
	 * GPS jamming: a missile aimed into a jammer's zone loses its satellite fix and drifts off by
	 * 25 to 50 blocks. Anti-radiation missiles don't care - they home on the jammer's emissions.
	 */
	private void applyJamming(ServerLevel level) {
		if (this.gpsJammed || this.missileType.warhead == MissileType.Warhead.ANTI_RADAR) {
			return;
		}
		JammerBlockEntity jammer = JammerBlockEntity.covering(level, this.getTarget());
		if (jammer == null) {
			return;
		}
		this.gpsJammed = true;
		BlockPos old = this.getTarget();
		BlockPos spoof = jammer.spoofDestination();
		if (spoof != null) {
			// GPS spoofing: false satellite signals - it believes it is somewhere else and steers for the
			// operator's point instead, a few blocks of scatter from its now-imperfect fix
			int x = spoof.getX() + level.getRandom().nextInt(9) - 4;
			int z = spoof.getZ() + level.getRandom().nextInt(9) - 4;
			int y = level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : spoof.getY();
			this.entityData.set(DATA_TARGET, new BlockPos(x, y, z));
			this.surfaceTarget = !level.hasChunk(x >> 4, z >> 4);
			Component msg = Component.literal("⚡ ").append(Component.translatable("message.ballisticmissiles.jammer_spoofed",
				Component.translatable(this.nameKey()), x, z)).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
			Vec3 o = Vec3.atCenterOf(old);
			for (ServerPlayer player : level.players()) {
				if (player.position().distanceToSqr(o) < 400 * 400 || player.position().distanceToSqr(Vec3.atCenterOf(jammer.getBlockPos())) < 400 * 400) {
					player.displayClientMessage(msg, true);
				}
			}
			return;
		}
		double angle = level.getRandom().nextDouble() * Mth.TWO_PI;
		double miss = 25.0 + level.getRandom().nextDouble() * 25.0;
		int x = old.getX() + (int) Math.round(Math.cos(angle) * miss);
		int z = old.getZ() + (int) Math.round(Math.sin(angle) * miss);
		int y = level.hasChunk(x >> 4, z >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : old.getY();
		this.entityData.set(DATA_TARGET, new BlockPos(x, y, z));
		Component msg = Component.literal("⚡ ").append(Component.translatable("message.ballisticmissiles.jammer_deflect", Component.translatable(this.nameKey())))
			.withStyle(ChatFormatting.LIGHT_PURPLE);
		Vec3 o = Vec3.atCenterOf(old);
		for (ServerPlayer player : level.players()) {
			if (player.position().distanceToSqr(o) < 250 * 250) {
				player.displayClientMessage(msg, true);
			}
		}
	}

	// ------------------------------------------------------------------ interaction

	public boolean arm(@Nullable ServerPlayer player, TargetData target) {
		if (this.getState() != IDLE) {
			return false;
		}
		BlockPos t = target.pos();
		double dx = t.getX() + 0.5 - this.getX();
		double dz = t.getZ() + 0.5 - this.getZ();
		if (dx * dx + dz * dz < MIN_RANGE * MIN_RANGE) {
			if (player != null) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.too_close", MIN_RANGE).withStyle(ChatFormatting.RED), true);
			}
			return false;
		}
		this.entityData.set(DATA_TARGET, t);
		this.surfaceTarget = target.surface();
		this.trajectory = null;
		this.setState(COUNTDOWN);
		int distance = (int) Math.sqrt(dx * dx + dz * dz);
		if (player != null) {
			player.displayClientMessage(
				Component.translatable("message.ballisticmissiles.armed", target.describe(), distance).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), false
			);
		}
		return true;
	}

	/**
	 * Launches at once from where the missile stands (ship- or ground-launched salvo): no countdown,
	 * straight to ignition.
	 */
	public void launchNow(TargetData target) {
		this.entityData.set(DATA_TARGET, target.pos());
		this.surfaceTarget = target.surface();
		Vec3 pos = this.position();
		this.entityData.set(DATA_LAUNCH, new Vector3f((float) pos.x, (float) pos.y, (float) pos.z));
		this.trajectory = null;
		this.setState(IGNITION);
	}

	/**
	 * A decoy pulled the seeker away: a cruise missile in flight re-targets onto the decoy. Returns
	 * false for anything that can't be fooled (ballistic warheads follow their trajectory).
	 */
	public boolean decoy(Vec3 decoy) {
		if (!this.missileType.isCruise() || this.getState() != FLIGHT || this.missileType.warhead == MissileType.Warhead.ANTI_RADAR) {
			return false;
		}
		this.entityData.set(DATA_TARGET, BlockPos.containing(decoy));
		return true;
	}

	/** Stops a running countdown. */
	public boolean abort() {
		if (this.getState() != COUNTDOWN) {
			return false;
		}
		this.setState(IDLE);
		return true;
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		int state = this.getState();
		if (player instanceof ServerPlayer owner && (stack.is(ModRegistry.TARGET_DESIGNATOR) || stack.isEmpty() && player.isShiftKeyDown())
			&& de.rcm.ballistic.launch.Ownership.refuse(owner, this)) {
			return InteractionResult.SUCCESS; // someone else's missile: no arming, aborting or taking it
		}
		if (stack.is(ModRegistry.TARGET_DESIGNATOR)) {
			if (!(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.SUCCESS;
			}
			if (player.isShiftKeyDown()) {
				BlockPos below = BlockPos.containing(this.getX(), this.getY() - 0.1, this.getZ());
				if (this.level().getBlockState(below).getBlock() instanceof LaunchPadBlock) {
					TargetDesignatorItem.toggleLink(serverPlayer, stack, LauncherLink.pad(below));
				}
				return InteractionResult.SUCCESS;
			}
			if (state == IDLE) {
				TargetData target = stack.get(ModRegistry.TARGET);
				if (target == null) {
					serverPlayer.displayClientMessage(Component.translatable("message.ballisticmissiles.no_target").withStyle(ChatFormatting.YELLOW), true);
				} else {
					this.arm(serverPlayer, target);
				}
			} else if (state == COUNTDOWN) {
				this.setState(IDLE);
				serverPlayer.displayClientMessage(Component.translatable("message.ballisticmissiles.aborted").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), true);
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.isEmpty() && player.isShiftKeyDown() && state == IDLE) {
			if (!this.level().isClientSide()) {
				player.setItemInHand(hand, new ItemStack(this.getItemForm()));
				this.discard();
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (this.getState() == IDLE && source.getEntity() instanceof Player player) {
			if (!player.getAbilities().instabuild) {
				this.spawnAtLocation(level, new ItemStack(this.getItemForm()));
			}
			this.discard();
			return true;
		}
		return false;
	}

	@Override
	public boolean isPickable() {
		int state = this.getState();
		return state == IDLE || state == COUNTDOWN;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean canBeCollidedWith(@Nullable Entity entity) {
		return this.getState() == IDLE || this.getState() == COUNTDOWN;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return true;
	}

	@Override
	public boolean isOnFire() {
		return false;
	}

	// ------------------------------------------------------------------ persistence

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		if (this.owner != null) {
			output.store("Owner", net.minecraft.core.UUIDUtil.CODEC, this.owner);
		}
		output.putInt("MissileState", this.getState());
		output.putInt("StateAge", this.stateAge);
		output.store("Target", BlockPos.CODEC, this.getTarget());
		output.putBoolean("SurfaceTarget", this.surfaceTarget);
		Vec3 launch = this.getLaunchPos();
		output.putDouble("LaunchX", launch.x);
		output.putDouble("LaunchY", launch.y);
		output.putDouble("LaunchZ", launch.z);
		Vec3 dir = this.getFlightDir();
		output.putDouble("DirX", dir.x);
		output.putDouble("DirY", dir.y);
		output.putDouble("DirZ", dir.z);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.owner = input.read("Owner", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
		this.entityData.set(DATA_STATE, input.getIntOr("MissileState", IDLE));
		this.stateAge = input.getIntOr("StateAge", 0);
		this.entityData.set(DATA_AGE, this.stateAge);
		this.entityData.set(DATA_TARGET, input.read("Target", BlockPos.CODEC).orElse(BlockPos.ZERO));
		this.surfaceTarget = input.getBooleanOr("SurfaceTarget", false);
		this.entityData.set(
			DATA_LAUNCH,
			new Vector3f((float) input.getDoubleOr("LaunchX", 0), (float) input.getDoubleOr("LaunchY", 0), (float) input.getDoubleOr("LaunchZ", 0))
		);
		this.setFlightDir(new Vec3(input.getDoubleOr("DirX", 0), input.getDoubleOr("DirY", 1), input.getDoubleOr("DirZ", 0)));
		this.trajectory = null;
	}
}
