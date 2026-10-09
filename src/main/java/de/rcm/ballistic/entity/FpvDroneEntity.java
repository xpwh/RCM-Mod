package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.DetonationManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * FPV kamikaze drone, flown through its own camera from the goggles. The pilot's keys and mouse
 * steer it (sent every tick). Two kinds:
 * <ul>
 * <li>the standard 7-inch drone with a PG-7 shaped-charge warhead: up to about 110 km/h (170 with
 * the throttle open), a minute and a half of battery;</li>
 * <li>the 5-inch racer: light and brutally fast, up to about 200 km/h (360 flat out), a minute of
 * battery and only a small fragmentation charge.</li>
 * </ul>
 * The video link reaches about two kilometres, weakened by terrain between pilot and drone; the
 * world around the drone is loaded and sent to the pilot while it flies, so the picture does not end
 * at the pilot's view distance. It goes off on any hard impact or on the trigger; with the link or
 * the battery gone it falls out of the sky and goes off where it lands. It can also be set down
 * (sneak + right-click on the ground), started from there (right-click it) or picked up again
 * (sneak + right-click it), and a drone flown gently down onto the ground lands when the link is cut.
 */
public class FpvDroneEntity extends Entity {
	private static final EntityDataAccessor<Integer> DATA_PILOT = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_SIGNAL = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> DATA_BATTERY = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_THROTTLE = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_ROLL = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> DATA_KIND = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> DATA_LANDED = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.BOOLEAN);
	/** How hard a jammer is drowning out the link, 0..1. */
	private static final EntityDataAccessor<Float> DATA_JAM = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.FLOAT);
	/** Fibre-optic drone: fibre paid out so far (blocks), and where its spool's free end is anchored. */
	private static final EntityDataAccessor<Float> DATA_CABLE = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<org.joml.Vector3fc> DATA_ANCHOR = SynchedEntityData.defineId(FpvDroneEntity.class,
		EntityDataSerializers.VECTOR3);

	public static final int KIND_STANDARD = 0;
	public static final int KIND_RACER = 1;
	/** Fibre-optic: steered down a glass fibre paid out from a spool - nothing to jam, but only as far as the fibre reaches. */
	public static final int KIND_FIBER = 2;
	/** Battery, ticks of flight, by kind. */
	private static final int[] BATTERY = {1800, 1200, 2400};
	private static final double[] THRUST = {0.085, 0.17, 0.075};
	private static final double[] BOOST_THRUST = {0.14, 0.3, 0.125};
	/** Fibre on the spool, blocks. */
	public static final float FIBER_LENGTH = 1200.0F;
	private static final double DRAG = 0.94;
	/** Faster than this into anything and the warhead's fuse fires. */
	private static final double FUSE_SPEED = 0.3;
	/** How far the video link reaches over open ground, blocks. */
	public static final double LINK_RANGE = 2000.0;

	public static final int ACTION_NONE = 0;
	public static final int ACTION_DETONATE = 1;
	public static final int ACTION_RELEASE = 2;

	/** Server: who is flying which drone (their view of the world follows it). */
	private static final Map<UUID, FpvDroneEntity> PILOTED = new HashMap<>();

	// the pilot's sticks, as last sent
	private float forward;
	private float strafe;
	private float lift;
	private boolean boost;
	private float yaw;
	private float pitch;
	private int lastInput;
	private Vec3 velocity = Vec3.ZERO;
	/** Link lost or battery flat: no more control, it falls. */
	private boolean dead;
	private int deadAge;
	private @Nullable UUID pilotUuid;
	private long lastChunk = Long.MIN_VALUE;
	private float blockedCache = -1.0F;
	public boolean clientSoundStarted;

	public FpvDroneEntity(EntityType<? extends FpvDroneEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	private static @Nullable FpvDroneEntity create(ServerLevel level, int kind) {
		FpvDroneEntity drone = ModRegistry.FPV_DRONE.create(level, EntitySpawnReason.TRIGGERED);
		if (drone != null) {
			drone.entityData.set(DATA_KIND, kind);
			drone.entityData.set(DATA_BATTERY, BATTERY[kind]);
		}
		return drone;
	}

	/** Launched from the hand: armed, buzzing up from in front of the pilot. */
	public static @Nullable FpvDroneEntity launch(ServerLevel level, ServerPlayer pilot, int kind) {
		FpvDroneEntity drone = create(level, kind);
		if (drone == null) {
			return null;
		}
		Vec3 look = Vec3.directionFromRotation(0.0F, pilot.getYRot());
		Vec3 at = pilot.getEyePosition().add(look.scale(1.0)).add(0.0, -0.2, 0.0);
		drone.setPos(at);
		drone.setYRot(pilot.getYRot());
		level.addFreshEntity(drone);
		drone.takeOff(level, pilot, look.scale(0.2).add(0.0, 0.25, 0.0));
		return drone;
	}

	/** Set down on the ground (sneak + right-click), motors off, waiting to be flown. */
	public static @Nullable FpvDroneEntity place(ServerLevel level, Player owner, Vec3 at, int kind) {
		FpvDroneEntity drone = create(level, kind);
		if (drone == null) {
			return null;
		}
		drone.setPos(at);
		drone.setYRot(owner.getYRot());
		drone.yaw = owner.getYRot();
		drone.entityData.set(DATA_LANDED, true);
		level.addFreshEntity(drone);
		level.playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.PLAYERS, 0.8F, 1.3F);
		return drone;
	}

	private void takeOff(ServerLevel level, ServerPlayer pilot, Vec3 kick) {
		this.entityData.set(DATA_LANDED, false);
		this.entityData.set(DATA_PILOT, pilot.getId());
		this.pilotUuid = pilot.getUUID();
		this.dead = false;
		this.deadAge = 0;
		this.yaw = pilot.getYRot();
		this.pitch = pilot.getXRot();
		this.velocity = kick;
		this.lastInput = this.tickCount;
		if (this.isFiber()) {
			// the free end stays with the pilot: the fibre pays out from the drone's spool from here
			Vec3 a = pilot.position().add(0.0, 0.9, 0.0);
			this.entityData.set(DATA_ANCHOR, new org.joml.Vector3f((float) a.x, (float) a.y, (float) a.z));
			this.entityData.set(DATA_CABLE, (float) a.distanceTo(this.position()));
		}
		PILOTED.put(pilot.getUUID(), this);
		refreshView(pilot);
		level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.DRONE_ARM, SoundSource.PLAYERS, 1.0F, this.isRacer() ? 1.2F : 1.0F);
	}

	/** The drone {@code player} is flying, if any. */
	public static @Nullable FpvDroneEntity flownBy(ServerPlayer player) {
		FpvDroneEntity drone = PILOTED.get(player.getUUID());
		if (drone != null && (drone.isRemoved() || drone.level() != player.level() || drone.getPilotId() != player.getId())) {
			PILOTED.remove(player.getUUID());
			return null;
		}
		return drone;
	}

	/** Where the world is seen from for {@code player}: their drone while they fly one, else themselves. */
	public static ChunkPos viewChunk(ServerPlayer player) {
		FpvDroneEntity drone = flownBy(player);
		return drone != null ? drone.chunkPosition() : player.chunkPosition();
	}

	public static Vec3 viewPosition(ServerPlayer player) {
		FpvDroneEntity drone = flownBy(player);
		return drone != null ? drone.position() : player.position();
	}

	/** Re-centres what the server sends the player on wherever they are looking from now. */
	private static void refreshView(ServerPlayer player) {
		try {
			((de.rcm.ballistic.mixin.ChunkMapAccessor) player.level().getChunkSource().chunkMap).ballisticmissiles$updateChunkTracking(player);
		} catch (RuntimeException e) {
			de.rcm.ballistic.BallisticMissiles.LOGGER.warn("Could not move the drone pilot's view: {}", e.toString());
		}
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_PILOT, -1);
		builder.define(DATA_SIGNAL, 1.0F);
		builder.define(DATA_BATTERY, BATTERY[KIND_STANDARD]);
		builder.define(DATA_THROTTLE, 0.0F);
		builder.define(DATA_ROLL, 0.0F);
		builder.define(DATA_KIND, KIND_STANDARD);
		builder.define(DATA_LANDED, false);
		builder.define(DATA_JAM, 0.0F);
		builder.define(DATA_CABLE, 0.0F);
		builder.define(DATA_ANCHOR, new org.joml.Vector3f());
	}

	/** Entity id of the player flying it, or -1. */
	public int getPilotId() {
		return this.entityData.get(DATA_PILOT);
	}

	/** Video link quality, 1 = perfect, 0 = gone. */
	public float getSignal() {
		return this.entityData.get(DATA_SIGNAL);
	}

	public int getBattery() {
		return this.entityData.get(DATA_BATTERY);
	}

	public int getMaxBattery() {
		return BATTERY[this.getKind()];
	}

	/** How hard the motors are working, 0..1 (for the sound). */
	public float getThrottle() {
		return this.entityData.get(DATA_THROTTLE);
	}

	/** Bank angle, degrees (it rolls into turns and sideways moves). */
	public float getRoll() {
		return this.entityData.get(DATA_ROLL);
	}

	public int getKind() {
		return Mth.clamp(this.entityData.get(DATA_KIND), 0, BATTERY.length - 1);
	}

	public boolean isRacer() {
		return this.getKind() == KIND_RACER;
	}

	public boolean isFiber() {
		return this.getKind() == KIND_FIBER;
	}

	/** Jamming of the radio link, 0..1 (always 0 for the fibre drone). */
	public float getJam() {
		return this.entityData.get(DATA_JAM);
	}

	/** Fibre paid out, blocks. */
	public float getCableUsed() {
		return this.entityData.get(DATA_CABLE);
	}

	/** Where the fibre starts (the pilot's end). */
	public Vec3 getAnchor() {
		org.joml.Vector3fc a = this.entityData.get(DATA_ANCHOR);
		return new Vec3(a.x(), a.y(), a.z());
	}

	/** Set down on the ground, motors off. */
	public boolean isLanded() {
		return this.entityData.get(DATA_LANDED);
	}

	public ItemStack asItem() {
		return new ItemStack(this.isRacer() ? ModRegistry.FPV_RACER_ITEM : this.isFiber() ? ModRegistry.FPV_FIBER_ITEM : ModRegistry.FPV_DRONE_ITEM);
	}

	/** Server: the pilot's sticks this tick. */
	public void input(ServerPlayer player, float forward, float strafe, float lift, boolean boost, float yaw, float pitch, int action) {
		if (player.getId() != this.getPilotId() || this.dead || this.isLanded()) {
			return;
		}
		this.forward = Mth.clamp(forward, -1.0F, 1.0F);
		this.strafe = Mth.clamp(strafe, -1.0F, 1.0F);
		this.lift = Mth.clamp(lift, -1.0F, 1.0F);
		this.boost = boost;
		this.yaw = yaw;
		this.pitch = Mth.clamp(pitch, -90.0F, 90.0F);
		this.lastInput = this.tickCount;
		ServerLevel level = (ServerLevel) this.level();
		if (action == ACTION_DETONATE) {
			this.detonate(level, this.position());
		} else if (action == ACTION_RELEASE) {
			if (this.velocity.length() < 0.3 && this.groundBelow(level, 0.6)) {
				this.land(level, player);
			} else {
				this.loseLink(level, "message.ballisticmissiles.drone_released");
			}
		}
	}

	/** Flown gently down onto the ground and the link cut: it settles and switches its motors off. */
	private void land(ServerLevel level, ServerPlayer pilot) {
		this.endFlight();
		this.entityData.set(DATA_LANDED, true);
		this.velocity = Vec3.ZERO;
		this.entityData.set(DATA_THROTTLE, 0.0F);
		this.entityData.set(DATA_ROLL, 0.0F);
		this.setXRot(0.0F);
		pilot.displayClientMessage(Component.translatable("message.ballisticmissiles.drone_landed").withStyle(ChatFormatting.GREEN), true);
	}

	private boolean groundBelow(ServerLevel level, double depth) {
		Vec3 p = this.position();
		return level.clip(new ClipContext(p, p.add(0.0, -depth, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this)).getType() != HitResult.Type.MISS;
	}

	/** The pilot lets go: their view goes back to their own eyes. */
	private void endFlight() {
		this.entityData.set(DATA_PILOT, -1);
		if (this.pilotUuid != null) {
			if (PILOTED.get(this.pilotUuid) == this) {
				PILOTED.remove(this.pilotUuid);
			}
			if (this.level() instanceof ServerLevel level && level.getServer().getPlayerList().getPlayer(this.pilotUuid) instanceof ServerPlayer player
				&& player.level() == level) {
				refreshView(player);
			}
		}
		this.pilotUuid = null;
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		if (!this.isLanded() || hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}
		if (this.level() instanceof ServerLevel level && player instanceof ServerPlayer serverPlayer) {
			if (player.isSecondaryUseActive()) {
				// picked up again
				if (!player.getAbilities().instabuild || !player.getInventory().contains(this.asItem())) {
					player.getInventory().placeItemBackInInventory(this.asItem());
				}
				level.playSound(null, this.getX(), this.getY(), this.getZ(), net.minecraft.sounds.SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 1.2F);
				this.discard();
			} else if (flownBy(serverPlayer) == null) {
				this.takeOff(level, serverPlayer, new Vec3(0.0, 0.3, 0.0));
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void tick() {
		super.tick();
		if (!(this.level() instanceof ServerLevel level)) {
			de.rcm.ballistic.ClientHooks.droneClientTick.accept(this);
			return;
		}
		if (this.isLanded()) {
			this.landedTick(level);
			return;
		}
		Entity pilotEntity = level.getEntity(this.getPilotId());
		ServerPlayer pilot = pilotEntity instanceof ServerPlayer p && p.isAlive() ? p : null;
		// keep the world round the drone (and ahead of it) loaded; while it is flown, out to a good distance
		int radius = pilot != null ? Mth.clamp(level.getServer().getPlayerList().getViewDistance(), 3, this.isRacer() ? 8 : 10) : 2;
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, this.chunkPosition(), radius);
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(this.position().add(this.velocity.scale(30.0)))), 2);
		if (pilot != null && this.chunkPosition().toLong() != this.lastChunk) {
			this.lastChunk = this.chunkPosition().toLong();
			refreshView(pilot);
		}
		int battery = this.getBattery() - 1;
		this.entityData.set(DATA_BATTERY, Math.max(0, battery));
		if (!this.dead) {
			if (pilot == null || this.tickCount - this.lastInput > 40) {
				this.loseLink(level, "message.ballisticmissiles.drone_link_lost");
			} else if (battery <= 0) {
				this.loseLink(level, "message.ballisticmissiles.drone_battery_dead");
			} else {
				float signal = this.signal(level, pilot);
				this.entityData.set(DATA_SIGNAL, signal);
				if (this.isFiber() && this.getCableUsed() >= FIBER_LENGTH) {
					this.loseLink(level, "message.ballisticmissiles.drone_fiber_snapped");
				} else if (signal <= 0.0F) {
					this.loseLink(level, this.getJam() > 0.3F ? "message.ballisticmissiles.drone_jammed" : "message.ballisticmissiles.drone_link_lost");
				}
			}
		}

		int kind = this.getKind();
		Vec3 v = this.velocity;
		if (!this.dead) {
			Vec3 look = Vec3.directionFromRotation(this.pitch, this.yaw);
			Vec3 right = Vec3.directionFromRotation(0.0F, this.yaw + 90.0F);
			double thrust = this.boost ? BOOST_THRUST[kind] : THRUST[kind];
			Vec3 push = look.scale(this.forward).add(right.scale(this.strafe * 0.75)).add(0.0, this.lift * 0.8, 0.0);
			if (push.lengthSqr() > 1.0) {
				push = push.normalize();
			}
			v = v.add(push.scale(thrust)).scale(DRAG);
			this.entityData.set(DATA_THROTTLE, (float) Mth.clamp(push.length() * (this.boost ? 1.0 : 0.7) + 0.25, 0.0, 1.0));
			float wantRoll = this.strafe * 28.0F + Mth.wrapDegrees(this.yaw - this.getYRot()) * 1.2F;
			this.entityData.set(DATA_ROLL, Mth.lerp(0.3F, this.getRoll(), Mth.clamp(wantRoll, -45.0F, 45.0F)));
			this.setYRot(this.yaw);
			this.setXRot(this.pitch);
		} else {
			// motors stopped: it drops, tumbling
			this.deadAge++;
			v = v.scale(0.98).add(0.0, -0.06, 0.0);
			this.entityData.set(DATA_THROTTLE, 0.0F);
			this.entityData.set(DATA_ROLL, this.getRoll() + 9.0F);
			this.setXRot(Mth.clamp(this.getXRot() + 5.0F, -90.0F, 90.0F));
			if (this.deadAge > 400) {
				this.discard();
				return;
			}
		}
		this.velocity = v;

		// into something: blocks, or anyone (or anything flying) in the way
		Vec3 pos = this.position();
		Vec3 next = pos.add(v);
		var hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
		Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
		Entity struck = this.entityHit(level, pos, end, pilot);
		double speed = v.length();
		if (struck != null && (speed > FUSE_SPEED * 0.5 || this.dead)) {
			struck.hurtServer(level, level.damageSources().explosion(this, pilot), this.isRacer() ? 12.0F : 20.0F);
			this.detonate(level, struck.position().add(0.0, struck.getBbHeight() * 0.5, 0.0));
			return;
		}
		if (hit.getType() != HitResult.Type.MISS) {
			if (speed > FUSE_SPEED || this.dead) {
				this.detonate(level, end.subtract(v.normalize().scale(0.2)));
				return;
			}
			// a gentle bump: it stops against the obstacle
			this.velocity = Vec3.ZERO;
			next = end.subtract(v.normalize().scale(0.15));
		}
		if (next.y < level.getMinY() - 16) {
			this.discard();
			return;
		}
		if (this.isFiber() && !this.dead) {
			this.entityData.set(DATA_CABLE, this.getCableUsed() + (float) next.distanceTo(pos));
		}
		this.setPos(next);
		this.setDeltaMovement(this.velocity);
	}

	/** Sitting on the ground: if the ground goes, it drops onto whatever is below. */
	private void landedTick(ServerLevel level) {
		this.entityData.set(DATA_THROTTLE, 0.0F);
		if (!this.groundBelow(level, 0.05)) {
			Vec3 p = this.position();
			var hit = level.clip(new ClipContext(p, p.add(0.0, -0.4, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
			this.setPos(hit.getType() == HitResult.Type.MISS ? p.add(0.0, -0.4, 0.0) : hit.getLocation().add(0.0, 0.01, 0.0));
			if (this.getY() < level.getMinY() - 16) {
				this.discard();
			}
		}
	}

	/**
	 * Video link: strong out to most of its range, then fading, and weakened by the hills, walls and
	 * buildings between the goggles and the drone (only where the world is loaded).
	 */
	private float signal(ServerLevel level, ServerPlayer pilot) {
		Vec3 eye = pilot.getEyePosition();
		Vec3 here = this.position();
		if (this.isFiber()) {
			// a glass fibre: a perfect picture, nothing to jam, until the spool runs out
			return 1.0F;
		}
		float jam = de.rcm.ballistic.block.JammerBlockEntity.droneJamming(level, here, eye, pilot.getUUID());
		this.entityData.set(DATA_JAM, Mth.lerp(0.3F, this.getJam(), jam));
		double d = eye.distanceTo(here);
		float s = (float) (1.0 - Math.pow(d / LINK_RANGE, 4.0));
		if (this.tickCount % 10 == 0 || this.blockedCache < 0.0F) {
			int solid = 0;
			int samples = (int) Math.min(500, d / 2.0);
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			for (int i = 1; i < samples; i++) {
				Vec3 p = eye.lerp(here, (double) i / samples);
				m.set(p.x, p.y, p.z);
				if (!level.hasChunkAt(m)) {
					continue;
				}
				BlockState state = level.getBlockState(m);
				if (!state.isAir() && !(state.getBlock() instanceof LeavesBlock) && !state.getCollisionShape(level, m).isEmpty()) {
					solid++;
				}
			}
			this.blockedCache = Math.min(0.65F, solid * 0.025F);
		}
		return Mth.clamp(s - this.blockedCache - this.getJam() * 1.15F, 0.0F, 1.0F);
	}

	private @Nullable Entity entityHit(ServerLevel level, Vec3 from, Vec3 to, @Nullable ServerPlayer pilot) {
		List<Entity> list = level.getEntities(this, new AABB(from, to).inflate(0.5), e ->
			(e instanceof LivingEntity || e instanceof JetEntity || e instanceof MobileLauncherEntity || e instanceof DestroyerEntity)
				&& e.isAlive() && !e.isSpectator() && (e != pilot || this.tickCount > 40));
		Entity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity e : list) {
			AABB box = e.getBoundingBox().inflate(0.3);
			var clip = box.clip(from, to);
			if (clip.isPresent() || box.contains(from)) {
				double dist = clip.map(from::distanceToSqr).orElse(0.0);
				if (dist < bestDist) {
					bestDist = dist;
					best = e;
				}
			}
		}
		return best;
	}

	private void loseLink(ServerLevel level, String messageKey) {
		if (this.dead) {
			return;
		}
		this.dead = true;
		Entity pilot = level.getEntity(this.getPilotId());
		if (pilot instanceof ServerPlayer player) {
			player.displayClientMessage(Component.translatable(messageKey).withStyle(ChatFormatting.RED), true);
		}
		this.endFlight();
		this.entityData.set(DATA_SIGNAL, 0.0F);
	}

	/** The warhead: the PG-7's shaped charge, or the racer's small fragmentation charge. */
	private void detonate(ServerLevel level, Vec3 at) {
		Entity pilot = level.getEntity(this.getPilotId());
		Entity source = pilot != null ? pilot : this;
		this.endFlight();
		if (this.isRacer()) {
			DetonationManager.detonateGrenade(level, at, source);
		} else {
			DetonationManager.detonateRpg(level, at, source);
		}
		this.discard();
	}

	@Override
	public void remove(RemovalReason reason) {
		if (!this.level().isClientSide()) {
			this.endFlight();
		}
		super.remove(reason);
	}

	/** The pilot's camera looks where the pilot looks (no lag through the server). */
	@Override
	public float getViewYRot(float partialTick) {
		Float v = de.rcm.ballistic.ClientHooks.droneView.view(this, partialTick, false);
		return v != null ? v : super.getViewYRot(partialTick);
	}

	@Override
	public float getViewXRot(float partialTick) {
		Float v = de.rcm.ballistic.ClientHooks.droneView.view(this, partialTick, true);
		return v != null ? v : super.getViewXRot(partialTick);
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	/** Shot down: rifle fire, flak or a blast brings it down; it goes off where it falls. */
	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (this.isRemoved() || amount <= 0.0F) {
			return false;
		}
		boolean blast = source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION);
		if (this.isLanded() && !blast && source.getDirectEntity() instanceof Player) {
			return false; // a knock with the hand doesn't set it off
		}
		if (blast || this.random.nextFloat() < 0.6F) {
			this.detonate(level, this.position());
		} else {
			this.loseLink(level, "message.ballisticmissiles.drone_shot_down");
		}
		return true;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 512 * 512;
	}

	/** A drone set down on the ground stays in the world; one in the air does not survive a reload. */
	@Override
	public boolean shouldBeSaved() {
		return this.isLanded() && !this.isRemoved();
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("Kind", this.getKind());
		output.putBoolean("Landed", this.isLanded());
		output.putInt("Battery", this.getBattery());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.entityData.set(DATA_KIND, Mth.clamp(input.getIntOr("Kind", KIND_STANDARD), 0, BATTERY.length - 1));
		this.entityData.set(DATA_LANDED, input.getBooleanOr("Landed", false));
		this.entityData.set(DATA_BATTERY, BATTERY[this.getKind()]);
	}
}
