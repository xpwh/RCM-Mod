package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.item.LauncherLink;
import de.rcm.ballistic.item.MissileItem;
import de.rcm.ballistic.item.TargetData;
import de.rcm.ballistic.item.TargetDesignatorItem;
import de.rcm.ballistic.launch.RemoteLaunch;
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
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Transporter-erector-launcher truck (Iskander/MZKT-style). Drive it like a vehicle (W/S throttle,
 * A/D steering), load a tactical or cruise missile onto the erector, then arm it with the target
 * designator: the crew dismounts, the jacks go down, the erector raises the missile to vertical and
 * it launches from the back of the truck. Afterwards the erector folds down and you can drive off.
 */
public class MobileLauncherEntity extends Entity {
	private static final EntityDataAccessor<Integer> DATA_MISSILE = SynchedEntityData.defineId(MobileLauncherEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_ERECT = SynchedEntityData.defineId(MobileLauncherEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> DATA_PHASE = SynchedEntityData.defineId(MobileLauncherEntity.class, EntityDataSerializers.INT);

	public static final int DRIVE = 0;
	public static final int ERECTING = 1;
	public static final int LAUNCHING = 2;
	public static final int LOWERING = 3;

	/** Erector hinge in truck space (x right, y up, z forward). */
	public static final float PIVOT_Y = 3.35F;
	public static final float PIVOT_Z = -4.3F;
	private static final int ERECT_TICKS = 90;
	private static final int LOWER_TICKS = 60;
	private static final double MAX_SPEED = 0.62;
	private static final float MAX_HEALTH = 80.0F;
	private static final float REPAIR_PER_INGOT = 16.0F;

	private final InterpolationHandler interpolation = new InterpolationHandler(this, 3);
	private double speed;
	private float deltaYaw;
	private float health = MAX_HEALTH;
	private int phaseAge;
	private @Nullable TargetData pendingTarget;
	private int missileEntityId = -1;

	// ---- client-side animation
	public float wheelAngle;
	public float wheelAngleO;
	public float erectO;

	public MobileLauncherEntity(EntityType<? extends MobileLauncherEntity> type, Level level) {
		super(type, level);
		this.blocksBuilding = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_MISSILE, -1);
		builder.define(DATA_ERECT, 0.0F);
		builder.define(DATA_PHASE, DRIVE);
	}

	// ------------------------------------------------------------------ accessors

	public @Nullable MissileType getMissile() {
		int i = this.entityData.get(DATA_MISSILE);
		MissileType[] all = MissileType.values();
		return i >= 0 && i < all.length ? all[i] : null;
	}

	private void setMissile(@Nullable MissileType type) {
		this.entityData.set(DATA_MISSILE, type == null ? -1 : type.ordinal());
	}

	public float getErect() {
		return this.entityData.get(DATA_ERECT);
	}

	public float getErect(float partialTick) {
		return Mth.lerp(partialTick, this.erectO, this.getErect());
	}

	public int getPhase() {
		return this.entityData.get(DATA_PHASE);
	}

	private void setPhase(int phase) {
		this.entityData.set(DATA_PHASE, phase);
		this.phaseAge = 0;
	}

	/** Truck-space point to world space. */
	public Vec3 toWorld(double x, double y, double z) {
		return this.position().add(new Vec3(x, y, z).yRot(-this.getYRot() * Mth.DEG_TO_RAD));
	}

	/** Where the missile stands once the erector is vertical. */
	public Vec3 launchBase() {
		return this.toWorld(0, PIVOT_Y, PIVOT_Z);
	}

	// ------------------------------------------------------------------ ticking

	@Override
	public InterpolationHandler getInterpolation() {
		return this.interpolation;
	}

	@Override
	public float maxUpStep() {
		return 1.1F;
	}

	@Override
	public void tick() {
		this.erectO = this.getErect();
		this.wheelAngleO = this.wheelAngle;
		Vec3 before = this.position();
		super.tick();
		this.interpolation.interpolate();
		if (this.isLocalInstanceAuthoritative()) {
			this.drive();
		} else {
			this.setDeltaMovement(Vec3.ZERO);
		}
		if (this.level() instanceof ServerLevel level) {
			this.serverTick(level);
		} else {
			Vec3 moved = this.position().subtract(before);
			Vec3 forward = Vec3.directionFromRotation(0, this.getYRot());
			this.wheelAngle += (float) (moved.dot(forward) / 0.62);
			if (this.getControllingPassenger() != null && this.random.nextInt(3) == 0) {
				Vec3 stack = this.toWorld(1.22, 3.5, 3.15);
				this.level().addParticle(ParticleTypes.SMOKE, stack.x, stack.y, stack.z, 0, 0.06, 0);
			}
		}
	}

	private void drive() {
		float throttle = 0.0F;
		float steer = 0.0F;
		LivingEntity driver = this.getControllingPassenger();
		if (driver instanceof Player player && this.getPhase() == DRIVE) {
			throttle = player.zza;
			steer = player.xxa;
		}
		if (throttle > 0.01F) {
			this.speed += this.speed < 0 ? 0.05 : 0.022;
		} else if (throttle < -0.01F) {
			this.speed -= this.speed > 0.01 ? 0.05 : 0.012;
		} else {
			this.speed *= 0.93;
		}
		if (this.isInWater()) {
			this.speed *= 0.8;
		}
		this.speed = Mth.clamp(this.speed, -0.16, MAX_SPEED);
		if (Math.abs(this.speed) < 0.002) {
			this.speed = 0.0;
		}
		this.deltaYaw = -steer * 3.0F * (float) Mth.clamp(this.speed / 0.25, -1.0, 1.0);
		this.setYRot(this.getYRot() + this.deltaYaw);

		Vec3 forward = Vec3.directionFromRotation(0, this.getYRot());
		double vy = this.onGround() ? -0.04 : (this.getDeltaMovement().y - 0.08) * 0.98;
		this.setDeltaMovement(forward.x * this.speed, vy, forward.z * this.speed);
		this.move(MoverType.SELF, this.getDeltaMovement());
		if (this.horizontalCollision) {
			this.speed *= 0.5;
		}
	}

	private void serverTick(ServerLevel level) {
		if (this.tickCount % 20 == 0) {
			RemoteLaunch.truckMoved(this.getUUID(), this.blockPosition());
		}
		int phase = this.getPhase();
		if (phase != DRIVE) {
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.blockPosition()), 2);
		}
		this.phaseAge++;
		switch (phase) {
			case ERECTING -> {
				float erect = Math.min(1.0F, this.getErect() + 1.0F / ERECT_TICKS);
				this.entityData.set(DATA_ERECT, erect);
				if (this.phaseAge % 12 == 1) {
					level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.PISTON_EXTEND, SoundSource.NEUTRAL, 1.2F, 0.45F);
				}
				if (erect >= 1.0F) {
					this.handOver(level);
				}
			}
			case LAUNCHING -> {
				Entity e = level.getEntity(this.missileEntityId);
				if (e instanceof MissileEntity missile && missile.isAlive()) {
					if (missile.getState() == MissileEntity.IDLE && this.phaseAge > 20) {
						// countdown aborted: take the missile back on the erector
						this.setMissile(missile.getMissileType());
						missile.discard();
						this.setPhase(LOWERING);
					} else if (missile.getState() == MissileEntity.FLIGHT && this.phaseAge > 40) {
						this.setPhase(LOWERING);
					}
				} else if (this.phaseAge > 40) {
					this.setPhase(LOWERING);
				}
			}
			case LOWERING -> {
				float erect = Math.max(0.0F, this.getErect() - 1.0F / LOWER_TICKS);
				this.entityData.set(DATA_ERECT, erect);
				if (this.phaseAge % 12 == 1) {
					level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.PISTON_CONTRACT, SoundSource.NEUTRAL, 1.2F, 0.45F);
				}
				if (erect <= 0.0F) {
					this.setPhase(DRIVE);
				}
			}
			default -> {
			}
		}
	}

	/** The erector is vertical: the missile now stands on its own and runs its countdown. */
	private void handOver(ServerLevel level) {
		MissileType type = this.getMissile();
		TargetData target = this.pendingTarget;
		this.pendingTarget = null;
		if (type == null || target == null) {
			this.setPhase(LOWERING);
			return;
		}
		MissileEntity missile = ModRegistry.missileEntity(type).create(level, EntitySpawnReason.TRIGGERED);
		if (missile == null) {
			this.setPhase(LOWERING);
			return;
		}
		missile.setPos(this.launchBase());
		level.addFreshEntity(missile);
		if (!missile.arm(null, target)) {
			missile.discard();
			this.setPhase(LOWERING);
			return;
		}
		this.setMissile(null);
		this.missileEntityId = missile.getId();
		this.setPhase(LAUNCHING);
	}

	// ------------------------------------------------------------------ control

	/** Arms the launcher; returns a message describing the outcome. */
	public Component arm(@Nullable ServerPlayer player, TargetData target) {
		MissileType type = this.getMissile();
		if (type == null) {
			return Component.translatable("message.ballisticmissiles.truck_empty").withStyle(ChatFormatting.YELLOW);
		}
		if (this.getPhase() != DRIVE) {
			return Component.translatable("message.ballisticmissiles.remote_busy").withStyle(ChatFormatting.YELLOW);
		}
		if (EmpManager.isJammed(this.level(), this.blockPosition())) {
			return Component.translatable("message.ballisticmissiles.jammed", EmpManager.jammedTicks(this.level(), this.blockPosition()) / 20)
				.withStyle(ChatFormatting.DARK_PURPLE);
		}
		Vec3 base = this.launchBase();
		double dx = target.pos().getX() + 0.5 - base.x;
		double dz = target.pos().getZ() + 0.5 - base.z;
		if (dx * dx + dz * dz < MissileEntity.MIN_RANGE * MissileEntity.MIN_RANGE) {
			return Component.translatable("message.ballisticmissiles.too_close", MissileEntity.MIN_RANGE).withStyle(ChatFormatting.RED);
		}
		this.ejectPassengers();
		this.speed = 0;
		this.pendingTarget = target;
		this.setPhase(ERECTING);
		this.level().playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.SIREN, SoundSource.NEUTRAL, 6.0F, 1.1F);
		return Component.translatable("message.ballisticmissiles.truck_erecting", target.describe(), (int) Math.sqrt(dx * dx + dz * dz))
			.withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
	}

	public boolean abort() {
		int phase = this.getPhase();
		if (phase == ERECTING) {
			this.pendingTarget = null;
			this.setPhase(LOWERING);
			return true;
		}
		if (phase == LAUNCHING && this.level() instanceof ServerLevel level && level.getEntity(this.missileEntityId) instanceof MissileEntity missile) {
			return missile.abort();
		}
		return false;
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (stack.is(ModRegistry.TARGET_DESIGNATOR)) {
			if (player instanceof ServerPlayer serverPlayer) {
				if (player.isShiftKeyDown()) {
					TargetDesignatorItem.toggleLink(serverPlayer, stack, LauncherLink.truck(this.getUUID(), this.blockPosition()));
				} else if (this.getPhase() == ERECTING || this.getPhase() == LAUNCHING) {
					if (this.abort()) {
						serverPlayer.displayClientMessage(Component.translatable("message.ballisticmissiles.aborted").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), true);
					}
				} else {
					TargetData target = stack.get(ModRegistry.TARGET);
					if (target == null) {
						serverPlayer.displayClientMessage(Component.translatable("message.ballisticmissiles.no_target").withStyle(ChatFormatting.YELLOW), true);
					} else {
						serverPlayer.displayClientMessage(this.arm(serverPlayer, target), false);
					}
				}
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.getItem() instanceof MissileItem item) {
			if (!this.level().isClientSide()) {
				MissileType type = item.getMissileType();
				if (!type.fitsOnTruck()) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.truck_too_big").withStyle(ChatFormatting.YELLOW), true);
				} else if (this.getMissile() != null || this.getPhase() != DRIVE) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.truck_full").withStyle(ChatFormatting.YELLOW), true);
				} else {
					this.setMissile(type);
					if (!player.getAbilities().instabuild) {
						stack.shrink(1);
					}
					this.level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.NETHERITE_BLOCK_PLACE, SoundSource.NEUTRAL, 1.5F, 0.6F);
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.truck_loaded").withStyle(ChatFormatting.GRAY), true);
				}
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.is(Items.IRON_INGOT) && this.health < MAX_HEALTH) {
			if (!this.level().isClientSide()) {
				this.health = Math.min(MAX_HEALTH, this.health + REPAIR_PER_INGOT);
				if (!player.getAbilities().instabuild) {
					stack.shrink(1);
				}
				this.level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ANVIL_USE, SoundSource.NEUTRAL, 0.7F, 1.2F);
				player.displayClientMessage(this.healthMessage(), true);
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.isEmpty() && player.isShiftKeyDown() && this.getMissile() == null) {
			if (!this.level().isClientSide()) {
				player.displayClientMessage(this.healthMessage(), true);
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.isEmpty() && player.isShiftKeyDown()) {
			if (!this.level().isClientSide() && this.getPhase() == DRIVE && this.getMissile() != null) {
				player.setItemInHand(hand, new ItemStack(ModRegistry.missileItem(this.getMissile())));
				this.setMissile(null);
			}
			return InteractionResult.SUCCESS;
		}
		if (this.getPhase() == DRIVE && !this.isVehicle()) {
			if (!this.level().isClientSide()) {
				player.startRiding(this);
			}
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	private Component healthMessage() {
		int percent = Math.round(this.health / MAX_HEALTH * 100.0F);
		ChatFormatting color = percent > 60 ? ChatFormatting.GREEN : percent > 25 ? ChatFormatting.GOLD : ChatFormatting.RED;
		return Component.translatable("message.ballisticmissiles.truck_health", percent).withStyle(color);
	}

	// ------------------------------------------------------------------ riding

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		return this.getFirstPassenger() instanceof Player player ? player : super.getControllingPassenger();
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengers().isEmpty() && this.getPhase() == DRIVE;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		return new Vec3(0.55, 1.15, 3.95).yRot(-this.getYRot() * Mth.DEG_TO_RAD);
	}

	@Override
	protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
		super.positionRider(passenger, moveFunction);
		passenger.setYRot(passenger.getYRot() + this.deltaYaw);
		passenger.setYHeadRot(passenger.getYHeadRot() + this.deltaYaw);
	}

	// ------------------------------------------------------------------ damage

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (this.isInvulnerableToBase(source) || this.isRemoved()) {
			return false;
		}
		boolean byPlayer = source.getEntity() instanceof Player;
		if (source.getEntity() instanceof Player player && player.getAbilities().instabuild) {
			this.destroy(level, true, false);
			return true;
		}
		this.health -= byPlayer ? amount * 4.0F : amount;
		this.markHurt();
		if (this.health <= 0) {
			this.destroy(level, byPlayer, !byPlayer);
		}
		return true;
	}

	private void destroy(ServerLevel level, boolean drop, boolean wreck) {
		boolean creative = this.getFirstPassenger() instanceof Player p && p.getAbilities().instabuild;
		if (drop && !creative) {
			this.spawnAtLocation(level, new ItemStack(ModRegistry.MOBILE_LAUNCHER_ITEM));
			if (this.getMissile() != null) {
				this.spawnAtLocation(level, new ItemStack(ModRegistry.missileItem(this.getMissile())));
			}
		}
		if (wreck) {
			level.explode(this, this.getX(), this.getY() + 1.5, this.getZ(), 3.0F, true, Level.ExplosionInteraction.NONE);
		}
		this.ejectPassengers();
		this.discard();
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean canBeCollidedWith(@Nullable Entity entity) {
		return true;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 256.0 * 256.0;
	}

	@Override
	public ItemStack getPickResult() {
		return new ItemStack(ModRegistry.MOBILE_LAUNCHER_ITEM);
	}

	// ------------------------------------------------------------------ persistence

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		MissileType type = this.getMissile();
		if (type != null) {
			output.putString("Missile", type.id);
		}
		output.putInt("Phase", this.getPhase());
		output.putFloat("Erect", this.getErect());
		output.putFloat("Health", this.health);
		if (this.pendingTarget != null) {
			output.store("Target", TargetData.CODEC, this.pendingTarget);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.setMissile(input.getString("Missile").map(MissileType::byId).orElse(null));
		this.pendingTarget = input.read("Target", TargetData.CODEC).orElse(null);
		int phase = input.getIntOr("Phase", DRIVE);
		// a launch interrupted by unloading folds the erector back down
		this.entityData.set(DATA_PHASE, phase == ERECTING && this.pendingTarget != null ? ERECTING : phase == DRIVE ? DRIVE : LOWERING);
		this.entityData.set(DATA_ERECT, input.getFloatOr("Erect", 0.0F));
		this.health = input.getFloatOr("Health", MAX_HEALTH);
	}
}
