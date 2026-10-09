package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.explosion.DetonationManager;
import java.util.List;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * FPV kamikaze drone: a 7-inch racing quadcopter with a PG-7 shaped-charge warhead strapped under it,
 * flown through its own camera from the goggles. The pilot's keys and mouse steer it (sent every tick);
 * it flies at up to about 110 km/h (170 with the throttle open), its battery lasts a minute and a half,
 * and the video link fades with distance and with walls and hills between pilot and drone until it is
 * lost. It goes off on any hard impact or when the pilot presses the trigger; with the link or the
 * battery gone it falls out of the sky and goes off where it lands.
 */
public class FpvDroneEntity extends Entity {
	private static final EntityDataAccessor<Integer> DATA_PILOT = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_SIGNAL = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> DATA_BATTERY = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_THROTTLE = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_ROLL = SynchedEntityData.defineId(FpvDroneEntity.class, EntityDataSerializers.FLOAT);

	/** Battery, ticks of flight. */
	public static final int BATTERY = 1800;
	private static final double THRUST = 0.085;
	private static final double BOOST_THRUST = 0.14;
	private static final double DRAG = 0.94;
	/** Faster than this into anything and the warhead's fuse fires. */
	private static final double FUSE_SPEED = 0.3;

	public static final int ACTION_NONE = 0;
	public static final int ACTION_DETONATE = 1;
	public static final int ACTION_RELEASE = 2;

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
	/** Client: where it was looking, for the camera. */
	public float clientYaw;
	public float clientPitch;
	public boolean clientSoundStarted;

	public FpvDroneEntity(EntityType<? extends FpvDroneEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/** Launched from the hand: armed, buzzing up from in front of the pilot. */
	public static @Nullable FpvDroneEntity launch(ServerLevel level, ServerPlayer pilot) {
		FpvDroneEntity drone = ModRegistry.FPV_DRONE.create(level, EntitySpawnReason.TRIGGERED);
		if (drone == null) {
			return null;
		}
		Vec3 look = Vec3.directionFromRotation(0.0F, pilot.getYRot());
		Vec3 at = pilot.getEyePosition().add(look.scale(1.0)).add(0.0, -0.2, 0.0);
		drone.setPos(at);
		drone.yaw = pilot.getYRot();
		drone.pitch = pilot.getXRot();
		drone.setYRot(drone.yaw);
		drone.setXRot(drone.pitch);
		drone.velocity = look.scale(0.2).add(0.0, 0.25, 0.0);
		drone.entityData.set(DATA_PILOT, pilot.getId());
		drone.lastInput = 0;
		level.addFreshEntity(drone);
		level.playSound(null, at.x, at.y, at.z, ModRegistry.DRONE_ARM, SoundSource.PLAYERS, 1.0F, 1.0F);
		return drone;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_PILOT, -1);
		builder.define(DATA_SIGNAL, 1.0F);
		builder.define(DATA_BATTERY, BATTERY);
		builder.define(DATA_THROTTLE, 0.0F);
		builder.define(DATA_ROLL, 0.0F);
	}

	/** Entity id of the player flying it, or -1 once the link is gone. */
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

	/** How hard the motors are working, 0..1 (for the sound). */
	public float getThrottle() {
		return this.entityData.get(DATA_THROTTLE);
	}

	/** Bank angle, degrees (it rolls into turns and sideways moves). */
	public float getRoll() {
		return this.entityData.get(DATA_ROLL);
	}

	/** Server: the pilot's sticks this tick. */
	public void input(ServerPlayer player, float forward, float strafe, float lift, boolean boost, float yaw, float pitch, int action) {
		if (player.getId() != this.getPilotId() || this.dead) {
			return;
		}
		this.forward = Mth.clamp(forward, -1.0F, 1.0F);
		this.strafe = Mth.clamp(strafe, -1.0F, 1.0F);
		this.lift = Mth.clamp(lift, -1.0F, 1.0F);
		this.boost = boost;
		this.yaw = yaw;
		this.pitch = Mth.clamp(pitch, -90.0F, 90.0F);
		this.lastInput = this.tickCount;
		if (action == ACTION_DETONATE) {
			this.detonate((ServerLevel) this.level(), this.position());
		} else if (action == ACTION_RELEASE) {
			this.loseLink((ServerLevel) this.level(), "message.ballisticmissiles.drone_released");
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (!(this.level() instanceof ServerLevel level)) {
			de.rcm.ballistic.ClientHooks.droneClientTick.accept(this);
			return;
		}
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.blockPosition()), 2);
		Entity pilotEntity = level.getEntity(this.getPilotId());
		ServerPlayer pilot = pilotEntity instanceof ServerPlayer p && p.isAlive() ? p : null;
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
				if (signal <= 0.0F) {
					this.loseLink(level, "message.ballisticmissiles.drone_link_lost");
				}
			}
		}

		Vec3 v = this.velocity;
		if (!this.dead) {
			Vec3 look = Vec3.directionFromRotation(this.pitch, this.yaw);
			Vec3 right = Vec3.directionFromRotation(0.0F, this.yaw + 90.0F);
			double thrust = this.boost ? BOOST_THRUST : THRUST;
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
			struck.hurtServer(level, level.damageSources().explosion(this, pilot), 20.0F);
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
		this.setPos(next);
		this.setDeltaMovement(this.velocity);
	}

	/**
	 * Video link: full strength close by, fading with distance out to the edge of what the pilot's
	 * world reaches, and weakened by every bit of terrain between the goggles and the drone.
	 */
	private float signal(ServerLevel level, ServerPlayer pilot) {
		int view = level.getServer().getPlayerList().getViewDistance();
		double range = Mth.clamp((view - 1) * 16.0, 96.0, 320.0);
		Vec3 eye = pilot.getEyePosition();
		Vec3 here = this.position();
		double d = eye.distanceTo(here);
		float s = (float) (1.0 - Math.pow(d / range, 3.0));
		if (this.tickCount % 5 == 0 || this.blockedCache < 0.0F) {
			var los = level.clip(new ClipContext(eye, here, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pilot));
			// behind cover: how far into it the drone is, roughly
			this.blockedCache = los.getType() == HitResult.Type.MISS ? 0.0F : (float) Mth.clamp(here.distanceTo(los.getLocation()) / 40.0, 0.25, 0.9);
		}
		return Mth.clamp(s - this.blockedCache, 0.0F, 1.0F);
	}

	private float blockedCache = -1.0F;

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
				double d = clip.map(from::distanceToSqr).orElse(0.0);
				if (d < bestDist) {
					bestDist = d;
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
		this.entityData.set(DATA_PILOT, -1);
		this.entityData.set(DATA_SIGNAL, 0.0F);
	}

	/** The PG-7 warhead: a shaped-charge jet that punches through armour, and its blast. */
	private void detonate(ServerLevel level, Vec3 at) {
		Entity pilot = level.getEntity(this.getPilotId());
		this.entityData.set(DATA_PILOT, -1);
		DetonationManager.detonateRpg(level, at, pilot != null ? pilot : this);
		this.discard();
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
		if (source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION) || this.random.nextFloat() < 0.6F) {
			this.detonate(level, this.position());
		} else {
			this.loseLink(level, "message.ballisticmissiles.drone_shot_down");
		}
		return true;
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
