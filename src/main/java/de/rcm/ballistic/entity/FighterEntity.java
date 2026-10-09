package de.rcm.ballistic.entity;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.explosion.DetonationManager;
import de.rcm.ballistic.gun.BulletEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A flyable F-35A or F-22A.
 * <p>
 * <b>Flying.</b> The jet goes where you look ("mouse aim"): it swings its nose towards your view as
 * fast as its airframe allows - limited by 9 g at speed and by control authority when slow - and banks
 * into the turn. W/S work the throttle, Ctrl lights the afterburner at full power, A/D roll. Thrust,
 * drag (which grows with the square of speed) and gravity along the flight path set the speed, so a
 * climb bleeds it off and a dive builds it; the F-22 goes supersonic without afterburner, the F-35
 * needs it. Below stall speed it sinks and the nose drops.
 * <p>
 * <b>Ground.</b> It stands on its gear; run up on the runway (about 80 m/s to rotate) and pull up.
 * Land gear down, gently and slow enough; anything else is a crash. The gear comes up by itself once
 * airborne and fast. Sneak in the air to eject: the seat throws you clear under a parachute, the jet
 * flies on and goes down.
 * <p>
 * <b>Weapons.</b> Left mouse: the internal cannon. Right mouse: a missile from the weapons bay - at a
 * target you have locked (hold it in the HUD circle), or at the point you are looking at on the ground.
 * F: flares. Opening the bay makes the jet briefly far easier for radar to see.
 * <p>
 * <b>Radar.</b> Both are stealthy: the radar cross section is a tiny fraction of an ordinary jet's, so
 * ground radars only pick them up close in. Defenses fire at it unless it belongs to their owner, and
 * the pilot hears the missile warning when something is launched at him.
 */
public class FighterEntity extends Entity implements AirThreat, de.rcm.ballistic.launch.Ownership.Owned {
	/** 9.81 m/s^2 in blocks per tick per tick. */
	public static final double GRAVITY = 0.0245;
	public static final double SOUND_SPEED = 17.15;
	/** Height of the jet's centreline above its wheels (the model is built around the centreline). */
	public static final double CENTRE = 2.3;

	public static final int ACTION_NONE = 0;
	public static final int ACTION_MISSILE = 1;
	public static final int ACTION_FLARES = 2;

	private static final EntityDataAccessor<Integer> DATA_TYPE = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_THROTTLE = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> DATA_AB = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Float> DATA_ROLL = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> DATA_G = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> DATA_GEAR = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> DATA_BAY = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> DATA_FIRING = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DATA_AMMO = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_MISSILES = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_FLARES = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_HEALTH = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> DATA_CRASHING = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DATA_WARNING = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.INT);

	private final InterpolationHandler interpolation = new InterpolationHandler(this, 2);

	// controls (from the pilot's packets)
	private float throttleAxis;
	private boolean afterburnerWanted;
	private float rollAxis;
	private float aimYaw;
	private float aimPitch;
	private boolean trigger;
	private int lastInput = -100;
	// flight
	private double speed;
	private double sink;
	private float aileron;
	private float gunCarry;
	private int missileCooldown;
	private int flareCooldown;
	private long flaresUntil;
	private long bayUntil;
	private int engagements;
	private @Nullable UUID owner;
	private int crashAge;
	// client
	public float rollO;
	public boolean clientInMachCone;
	public boolean clientSoundStarted;

	public FighterEntity(EntityType<? extends FighterEntity> type, Level level) {
		super(type, level);
	}

	public static FighterEntity place(ServerLevel level, Player owner, Vec3 at, float yaw, FighterType type) {
		FighterEntity jet = ModRegistry.FIGHTER.create(level, net.minecraft.world.entity.EntitySpawnReason.SPAWN_ITEM_USE);
		jet.entityData.set(DATA_TYPE, type.ordinal());
		jet.entityData.set(DATA_AMMO, type.gunRounds);
		jet.entityData.set(DATA_MISSILES, type.missiles);
		jet.entityData.set(DATA_HEALTH, type.maxHealth);
		jet.snapTo(at.x, at.y, at.z, yaw, 0.0F);
		jet.owner = owner.getUUID();
		level.addFreshEntity(jet);
		return jet;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_TYPE, 0);
		builder.define(DATA_THROTTLE, 0.0F);
		builder.define(DATA_AB, false);
		builder.define(DATA_ROLL, 0.0F);
		builder.define(DATA_SPEED, 0.0F);
		builder.define(DATA_G, 1.0F);
		builder.define(DATA_GEAR, true);
		builder.define(DATA_BAY, false);
		builder.define(DATA_FIRING, false);
		builder.define(DATA_AMMO, 0);
		builder.define(DATA_MISSILES, 0);
		builder.define(DATA_FLARES, 24);
		builder.define(DATA_HEALTH, 60.0F);
		builder.define(DATA_CRASHING, false);
		builder.define(DATA_WARNING, 0);
	}

	// ------------------------------------------------------------------ state

	public FighterType type() {
		return FighterType.byId(this.entityData.get(DATA_TYPE));
	}

	public float throttle() {
		return this.entityData.get(DATA_THROTTLE);
	}

	public boolean isAfterburner() {
		return this.entityData.get(DATA_AB);
	}

	public float roll() {
		return this.entityData.get(DATA_ROLL);
	}

	public float getRoll(float partialTick) {
		return Mth.rotLerp(partialTick, this.rollO, this.roll());
	}

	public float speed() {
		return this.entityData.get(DATA_SPEED);
	}

	public float mach() {
		return (float) (this.speed() / SOUND_SPEED);
	}

	public float gLoad() {
		return this.entityData.get(DATA_G);
	}

	public boolean gearDown() {
		return this.entityData.get(DATA_GEAR);
	}

	public boolean bayOpen() {
		return this.entityData.get(DATA_BAY);
	}

	public boolean isFiring() {
		return this.entityData.get(DATA_FIRING);
	}

	public int ammo() {
		return this.entityData.get(DATA_AMMO);
	}

	public int missiles() {
		return this.entityData.get(DATA_MISSILES);
	}

	public int flares() {
		return this.entityData.get(DATA_FLARES);
	}

	public float health() {
		return this.entityData.get(DATA_HEALTH);
	}

	public boolean isCrashing() {
		return this.entityData.get(DATA_CRASHING);
	}

	/** Ticks of missile warning left (something has been launched at this jet). */
	public int warning() {
		return this.entityData.get(DATA_WARNING);
	}

	public Vec3 forward() {
		return Vec3.directionFromRotation(this.getXRot(), this.getYRot());
	}

	public Vec3 forward(float partialTick) {
		return Vec3.directionFromRotation(this.getViewXRot(partialTick), this.getViewYRot(partialTick));
	}

	/** The jet's own "up", tilted by bank. */
	public Vec3 up(float roll) {
		Vec3 f = this.forward();
		Vec3 right = f.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 u = right.cross(f).normalize();
		double r = Math.toRadians(roll);
		return u.scale(Math.cos(r)).add(right.scale(Math.sin(r))).normalize();
	}

	public @Nullable Player pilot() {
		return this.getFirstPassenger() instanceof Player p ? p : null;
	}

	@Override
	public InterpolationHandler getInterpolation() {
		return this.interpolation;
	}

	// ------------------------------------------------------------------ the pilot's controls

	/** Server: the pilot's controls this tick. */
	public void input(ServerPlayer player, float throttleAxis, boolean afterburner, float roll, float yaw, float pitch, boolean trigger, int action,
		int lockTarget) {
		if (player != this.pilot() || !Float.isFinite(throttleAxis) || !Float.isFinite(roll) || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
			return;
		}
		this.throttleAxis = Mth.clamp(throttleAxis, -1.0F, 1.0F);
		this.afterburnerWanted = afterburner;
		this.rollAxis = Mth.clamp(roll, -1.0F, 1.0F);
		this.aimYaw = Mth.wrapDegrees(yaw);
		this.aimPitch = Mth.clamp(pitch, -90.0F, 90.0F);
		this.trigger = trigger;
		this.lastInput = this.tickCount;
		ServerLevel level = (ServerLevel) this.level();
		if (action == ACTION_MISSILE) {
			this.fireMissile(level, player, lockTarget);
		} else if (action == ACTION_FLARES) {
			this.releaseFlares(level);
		}
	}

	// ------------------------------------------------------------------ flight

	@Override
	public void tick() {
		this.rollO = this.roll();
		super.tick();
		if (this.level().isClientSide()) {
			return;
		}
		ServerLevel level = (ServerLevel) this.level();
		FighterType type = this.type();
		Player pilot = this.pilot();
		boolean flown = pilot != null && this.tickCount - this.lastInput < 10 && !this.isCrashing();
		boolean grounded = this.onGround();

		// throttle and afterburner
		float throttle = this.throttle();
		if (flown) {
			throttle = Mth.clamp(throttle + this.throttleAxis * 0.012F, 0.0F, 1.0F);
		} else if (grounded) {
			throttle = Math.max(0.0F, throttle - 0.02F);
		}
		boolean ab = flown && this.afterburnerWanted && throttle > 0.95F;
		this.entityData.set(DATA_THROTTLE, throttle);
		this.entityData.set(DATA_AB, ab);

		// steering: the nose swings towards where the pilot looks, as fast as the airframe allows
		Vec3 fwd = this.forward();
		double turnLimit = Math.toRadians(this.turnRate(type));
		Vec3 newFwd = fwd;
		if (flown) {
			Vec3 want = Vec3.directionFromRotation(this.aimPitch, this.aimYaw);
			if (grounded) {
				// on the wheels: steer with the nosewheel; the nose only comes up once fast enough to fly
				Vec3 flat = new Vec3(want.x, 0.0, want.z);
				flat = flat.lengthSqr() < 1.0E-4 ? new Vec3(fwd.x, 0.0, fwd.z).normalize() : flat.normalize();
				boolean rotate = this.speed > type.stallSpeed * 0.95;
				double climb = rotate ? Mth.clamp(want.y, 0.0, 0.45) : 0.0;
				want = flat.scale(Math.sqrt(1.0 - climb * climb)).add(0.0, climb, 0.0);
				turnLimit = Math.toRadians(rotate ? 1.5 : 1.2);
			}
			newFwd = rotateTowards(fwd, want.normalize(), turnLimit);
		} else if (!grounded) {
			// nobody at the stick (or shot to pieces): the nose sags, it goes down
			newFwd = rotateTowards(fwd, fwd.add(0, -0.3, 0).normalize(), Math.toRadians(this.isCrashing() ? 1.2 : 0.35));
		}
		// below flying speed the nose drops
		if (!grounded && this.speed < type.stallSpeed) {
			newFwd = rotateTowards(newFwd, newFwd.add(0, -0.6, 0).normalize(), Math.toRadians(1.5 * (1.0 - this.speed / type.stallSpeed)));
		}
		double turned = Math.acos(Mth.clamp(fwd.dot(newFwd), -1.0, 1.0));
		this.setRot((float) (Mth.atan2(-newFwd.x, newFwd.z) * Mth.RAD_TO_DEG), (float) (-Math.asin(Mth.clamp(newFwd.y, -1.0, 1.0)) * Mth.RAD_TO_DEG));

		// speed: thrust, drag rising with the square of speed, gravity along the climb or dive
		double thrust = this.isCrashing() ? 0.0 : throttle * (ab ? type.afterburnerThrust : type.dryThrust());
		double drag = type.drag() * this.speed * this.speed + (this.gearDown() ? 0.0004 * this.speed : 0.0);
		if (grounded) {
			drag += throttle < 0.05F ? 0.06 : 0.0015; // brakes on with the throttle closed, rolling resistance otherwise
		}
		this.speed = Math.max(0.0, this.speed + thrust - drag - GRAVITY * newFwd.y);

		// lift: at flying speed it goes where the nose points; slower, it sinks
		Vec3 vel = newFwd.scale(this.speed);
		if (!grounded && this.speed < type.stallSpeed) {
			this.sink += GRAVITY * (1.0 - this.speed / type.stallSpeed);
		} else {
			this.sink = Math.max(0.0, this.sink - GRAVITY * 2.0);
		}
		vel = vel.add(0.0, -this.sink, 0.0);
		if (grounded && vel.y < 0.0) {
			vel = new Vec3(vel.x, 0.0, vel.z);
		}
		Vec3 before = this.position();
		double fallSpeed = -vel.y;
		this.move(MoverType.SELF, vel.add(0.0, grounded ? -0.05 : 0.0, 0.0));
		this.setDeltaMovement(this.position().subtract(before));

		// touching down gently on the gear is a landing; anything else is a crash
		if (this.horizontalCollision && this.speed > 1.5 || this.verticalCollision && !grounded && this.onGround()
			&& !(this.gearDown() && fallSpeed < 0.45 && Math.abs(this.getXRot()) < 18.0F && this.speed < 7.5) && this.speed > 1.0) {
			this.crash(level);
			return;
		}
		if (this.onGround() && !grounded) {
			this.setXRot(Math.min(0.0F, this.getXRot()));
			level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.METAL_THUD, SoundSource.NEUTRAL, 2.0F, 0.7F);
		}
		if (this.isInWater() && this.speed > 1.0) {
			this.crash(level);
			return;
		}

		// gear: up once airborne and fast, down when slow and near the ground
		double agl = this.getY() - level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(this.getX()), Mth.floor(this.getZ()));
		boolean gear = this.onGround() || this.speed < 7.0 && agl < 40.0;
		if (gear != this.gearDown()) {
			this.entityData.set(DATA_GEAR, gear);
			level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.HYDRAULIC_EXTEND, SoundSource.NEUTRAL, 1.2F, 1.3F);
		}

		// bank into the turn (coordinated: tan(bank) = v * omega / g), plus aileron rolls on A/D
		double omegaMs = turned * 20.0;
		double vMs = this.speed * 20.0;
		double g = Math.sqrt(1.0 + Math.pow(vMs * omegaMs / 9.81, 2));
		this.entityData.set(DATA_G, (float) g);
		float bank = 0.0F;
		if (!this.onGround() && turned > 1.0E-4) {
			double side = fwd.cross(newFwd).y;
			bank = (float) (Math.toDegrees(Math.atan(vMs * omegaMs / 9.81)) * -Math.signum(side));
			bank = Mth.clamp(bank, -85.0F, 85.0F);
		}
		if (flown && !this.onGround()) {
			this.aileron += this.rollAxis * 14.0F;
		}
		if (Math.abs(this.rollAxis) < 0.1F) {
			float rest = Mth.wrapDegrees(this.aileron);
			this.aileron = Math.abs(rest) < 9.0F ? 0.0F : this.aileron - Math.signum(rest) * 9.0F;
		}
		this.bankSmoothed = Mth.approach(this.bankSmoothed, bank, 6.0F);
		this.entityData.set(DATA_ROLL, Mth.wrapDegrees(this.bankSmoothed + this.aileron));
		this.entityData.set(DATA_SPEED, (float) this.speed);

		// weapons, bay, damage smoke, chunk loading, radar
		this.gun(level, flown && this.trigger, newFwd);
		if (this.missileCooldown > 0) {
			this.missileCooldown--;
		}
		if (this.flareCooldown > 0) {
			this.flareCooldown--;
		}
		this.entityData.set(DATA_BAY, level.getGameTime() < this.bayUntil);
		if (this.warning() > 0) {
			this.entityData.set(DATA_WARNING, this.warning() - 1);
		}
		if (this.isCrashing() && ++this.crashAge > 600) {
			this.crash(level);
			return;
		}
		if (pilot != null || !this.onGround()) {
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, this.chunkPosition(), 2);
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(this.position().add(vel.scale(12.0)))), 2);
		}
		if (this.isActiveThreat()) {
			ThreatTracker.report(level, this);
		}
	}

	private float bankSmoothed;

	/** How fast the nose can turn now, degrees per tick: the g limit at speed, control authority when slow. */
	private double turnRate(FighterType type) {
		double vMs = Math.max(60.0, this.speed * 20.0);
		double byG = Math.toDegrees(type.maxG * 9.81 / vMs) / 20.0;
		double authority = Mth.clamp((this.speed - type.stallSpeed * 0.5) / type.stallSpeed, 0.15, 1.0);
		return Math.min(type.maxTurn, byG) * authority;
	}

	private static Vec3 rotateTowards(Vec3 from, Vec3 to, double maxAngle) {
		double cos = Mth.clamp(from.dot(to), -1.0, 1.0);
		double angle = Math.acos(cos);
		if (angle <= maxAngle || angle < 1.0E-6) {
			return to;
		}
		Vec3 axis = from.cross(to);
		if (axis.lengthSqr() < 1.0E-10) {
			axis = Math.abs(from.y) < 0.9 ? from.cross(new Vec3(0, 1, 0)) : from.cross(new Vec3(1, 0, 0));
		}
		axis = axis.normalize();
		double c = Math.cos(maxAngle);
		double s = Math.sin(maxAngle);
		// Rodrigues' rotation of 'from' about 'axis'
		return from.scale(c).add(axis.cross(from).scale(s)).add(axis.scale(axis.dot(from) * (1.0 - c))).normalize();
	}

	// ------------------------------------------------------------------ weapons

	private Vec3 rightVec(Vec3 fwd) {
		Vec3 r = fwd.cross(new Vec3(0, 1, 0));
		return r.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : r.normalize();
	}

	private void gun(ServerLevel level, boolean firing, Vec3 fwd) {
		boolean live = firing && this.ammo() > 0;
		if (live != this.isFiring()) {
			this.entityData.set(DATA_FIRING, live);
		}
		if (!live) {
			this.gunCarry = 0.0F;
			return;
		}
		FighterType type = this.type();
		Player pilot = this.pilot();
		Entity shooter = pilot != null ? pilot : this;
		Vec3 right = this.rightVec(fwd);
		// the gun: F-22 in the right wing root, F-35A over the left intake, firing along the nose
		Vec3 up = this.up(this.roll());
		Vec3 muzzle = this.position().add(0, CENTRE, 0).add(fwd.scale(type == FighterType.F22 ? 5.2 : 2.6))
			.add(right.scale(type == FighterType.F22 ? 1.05 : -0.75)).add(up.scale(type == FighterType.F22 ? 0.75 : 1.0));
		this.gunCarry += type.gunRate;
		var r = this.random;
		while (this.gunCarry >= 1.0F && this.ammo() > 0) {
			this.gunCarry -= 1.0F;
			this.entityData.set(DATA_AMMO, this.ammo() - 1);
			double spread = 0.004;
			Vec3 dir = fwd.add(r.nextGaussian() * spread, r.nextGaussian() * spread, r.nextGaussian() * spread).normalize();
			// 20/25 mm rounds leave at some 1050 m/s, on top of the jet's own speed
			BulletEntity.fire(level, shooter, muzzle, dir.scale(52.0).add(fwd.scale(this.speed)), this.ammo() % 5 == 0);
		}
		if (this.tickCount % 3 == 0) {
			level.playSound(null, muzzle.x, muzzle.y, muzzle.z, ModRegistry.A10_GUN, SoundSource.NEUTRAL, 6.0F, type == FighterType.F22 ? 1.35F : 1.15F);
			de.rcm.ballistic.ai.Senses.noise(level, muzzle, 300.0, de.rcm.ballistic.ai.Senses.GUNSHOT, shooter);
		}
	}

	private void fireMissile(ServerLevel level, ServerPlayer pilot, int lockTarget) {
		if (this.missiles() <= 0 || this.missileCooldown > 0 || this.isCrashing()) {
			return;
		}
		Vec3 fwd = this.forward();
		Entity target = lockTarget >= 0 ? level.getEntity(lockTarget) : null;
		// the lock has to hold up: in front, in range, in sight
		if (target != null) {
			Vec3 to = target.position().subtract(this.position());
			if (to.length() > AirMissileEntity.LOCK_RANGE * 1.15 || to.normalize().dot(fwd) < Math.cos(Math.toRadians(40.0)) || target == this || target == pilot) {
				target = null;
			}
		}
		Vec3 ground = null;
		if (target == null) {
			// no lock: at the spot under the pilot's sight
			Vec3 eye = pilot.getEyePosition();
			Vec3 far = eye.add(pilot.getLookAngle().scale(1500.0));
			HitResult hit = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
			ground = hit.getType() == HitResult.Type.MISS ? far : hit.getLocation();
		}
		int left = this.missiles() - 1;
		this.entityData.set(DATA_MISSILES, left);
		this.missileCooldown = 12;
		this.bayUntil = level.getGameTime() + 30;
		Vec3 right = this.rightVec(fwd);
		Vec3 from = this.position().add(0, CENTRE - 1.4, 0).add(right.scale(left % 2 == 0 ? 0.6 : -0.6));
		AirMissileEntity.launch(level, this, pilot, from, fwd.scale(this.speed).add(0, -0.4, 0), target, ground);
		if (target instanceof FighterEntity other) {
			other.missileWarning();
		} else if (target instanceof AirThreat threat) {
			threat.setEngagements(threat.getEngagements() + 1);
		}
	}

	private void releaseFlares(ServerLevel level) {
		if (this.flares() <= 0 || this.flareCooldown > 0) {
			return;
		}
		this.entityData.set(DATA_FLARES, this.flares() - 1);
		this.flareCooldown = 15;
		this.flaresUntil = level.getGameTime() + 60;
		Vec3 tail = this.position().add(0, CENTRE, 0).subtract(this.forward().scale(7.0));
		for (int i = 0; i < 6; i++) {
			level.sendParticles(ModRegistry.SPARK, true, true, tail.x, tail.y - i * 0.5, tail.z, 8, 1.2, 0.6, 1.2, 0.2);
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, true, true, tail.x, tail.y - i * 0.5, tail.z, 3, 1.0, 0.4, 1.0, 0.04);
		}
		level.playSound(null, tail.x, tail.y, tail.z, ModRegistry.FLARE_LAUNCH, SoundSource.NEUTRAL, 5.0F, 1.0F);
	}

	/** Something has been fired at this jet: the warning receiver goes off in the cockpit. */
	public void missileWarning() {
		this.entityData.set(DATA_WARNING, 100);
		if (this.pilot() instanceof ServerPlayer p) {
			p.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(net.minecraft.core.Holder.direct(ModRegistry.INCOMING),
				SoundSource.NEUTRAL, p.getX(), p.getEyeY(), p.getZ(), 1.0F, 1.2F, this.random.nextLong()));
		}
	}

	/** Flares in the air behind it right now (a missile chasing it may be decoyed). */
	public boolean flaresOut() {
		return this.level().getGameTime() < this.flaresUntil;
	}

	// ------------------------------------------------------------------ damage, crash, ejection

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (this.isRemoved() || this.isInvulnerableToBase(source)) {
			return false;
		}
		if (source.getEntity() instanceof Player p && p.getAbilities().instabuild && p.isShiftKeyDown() && this.pilot() == null) {
			this.discard(); // a creative player sneak-hitting an empty jet takes it away
			return true;
		}
		if (source.getEntity() == this.pilot()) {
			return false;
		}
		float health = this.health() - amount;
		this.entityData.set(DATA_HEALTH, health);
		this.markHurt();
		if (health <= 0.0F && !this.isCrashing()) {
			this.goDown(level);
		}
		return true;
	}

	private void goDown(ServerLevel level) {
		this.entityData.set(DATA_CRASHING, true);
		DetonationManager.intercepted(level, this.position().add(0, 1.0, 0), this);
		if (this.onGround()) {
			this.crash(level);
		}
	}

	private void crash(ServerLevel level) {
		Vec3 at = this.position();
		boolean water = this.isInWater();
		Player pilot = this.pilot();
		this.ejectPassengers();
		if (pilot != null) {
			pilot.hurtServer(level, level.damageSources().explosion(this, this), 60.0F);
		}
		DetonationManager.aircraftCrash(level, at, this, true, water);
		this.discard();
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		if (!this.level().isClientSide() && !this.onGround() && this.speed > 2.0 && passenger instanceof LivingEntity pilot && !this.isRemoved()) {
			// ejection: the seat fires the pilot clear above the jet, then the parachute opens
			Vec3 up = this.up(this.roll());
			passenger.setPos(this.getX() + up.x * 3.0, this.getY() + 2.0 + Math.max(0.0, up.y) * 3.0, this.getZ() + up.z * 3.0);
			passenger.setDeltaMovement(up.scale(1.4).add(0, 0.6, 0));
			passenger.hurtMarked = true;
			pilot.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 60, 0, false, false, true));
			this.level().playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.AIR_LAUNCH, SoundSource.NEUTRAL, 3.0F, 1.4F);
			this.entityData.set(DATA_CRASHING, true);
		}
	}

	// ------------------------------------------------------------------ boarding

	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		if (player.isSecondaryUseActive() || !this.getPassengers().isEmpty() || this.isCrashing()) {
			return InteractionResult.PASS;
		}
		if (!this.level().isClientSide()) {
			if (player instanceof ServerPlayer sp && !de.rcm.ballistic.network.ModNetworking.mayUse(sp, this.owner)) {
				sp.displayClientMessage(Component.translatable("message.ballisticmissiles.not_yours").withStyle(net.minecraft.ChatFormatting.RED), true);
				return InteractionResult.SUCCESS;
			}
			// rearm and refuel on the ground when boarding
			if (this.onGround()) {
				FighterType t = this.type();
				this.entityData.set(DATA_AMMO, t.gunRounds);
				this.entityData.set(DATA_MISSILES, t.missiles);
				this.entityData.set(DATA_FLARES, 24);
			}
			player.startRiding(this);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		return null; // flown from packets on the server, not driven like a boat
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return this.getPassengers().isEmpty();
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		// in the cockpit, well forward of the middle, head under the canopy
		// the model's centreline is GEAR_HEIGHT (2.3) above the entity's feet; the eye sits under the canopy,
		// 0.95 above the centreline. A seated player's eye is 1.62 above his origin, which hangs 0.6 below
		// the attachment point
		Vec3 fwd = this.forward();
		Vec3 up = this.up(this.roll());
		double cockpit = this.type() == FighterType.F22 ? 5.3 : 4.3;
		return new Vec3(0.0, 2.3 - 1.02, 0.0).add(fwd.scale(cockpit)).add(up.scale(0.95));
	}

	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		Vec3 side = this.rightVec(this.forward()).scale(4.0);
		return this.position().add(side).add(0, 0.2, 0);
	}

	/** Rolling on its gear it takes kerbs and slabs in its stride. */
	@Override
	public float maxUpStep() {
		return this.onGround() ? 1.0F : 0.0F;
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
		return distance < 1024.0 * 1024.0;
	}

	public ItemStack asItem() {
		return new ItemStack(this.type() == FighterType.F22 ? ModRegistry.F22_ITEM : ModRegistry.F35_ITEM);
	}

	// ------------------------------------------------------------------ radar and defenses

	@Override
	public Entity asEntity() {
		return this;
	}

	@Override
	public boolean isActiveThreat() {
		return !this.isRemoved() && !this.onGround() && this.speed > 2.0;
	}

	@Override
	public Vec3 aimPoint(int ticksAhead) {
		return this.position().add(0, CENTRE, 0).add(this.forward().scale(this.speed * ticksAhead));
	}

	@Override
	public Vec3 threatVelocity() {
		return this.forward().scale(this.speed);
	}

	@Override
	public Vec3 predictedImpact() {
		return this.position().add(this.threatVelocity().scale(40.0));
	}

	@Override
	public int etaTicks() {
		return 9999;
	}

	@Override
	public ThreatClass threatClass() {
		return ThreatClass.AIRCRAFT;
	}

	/** Stealthy - until the weapons bay opens or the gear is down. */
	@Override
	public double radarCrossSection() {
		double rcs = this.type().radarCrossSection;
		if (this.bayOpen()) {
			rcs *= 30.0;
		}
		if (this.gearDown()) {
			rcs *= 4.0;
		}
		return rcs;
	}

	@Override
	public float killProbability() {
		return this.flaresOut() ? 0.25F : 0.7F;
	}

	@Override
	public String nameKey() {
		return "entity.ballisticmissiles.fighter_" + this.type().id;
	}

	@Override
	public int getEngagements() {
		return this.engagements;
	}

	@Override
	public void setEngagements(int engagements) {
		if (engagements > this.engagements) {
			this.missileWarning(); // a SAM has been launched at us
		}
		this.engagements = engagements;
	}

	@Override
	public void destroyByInterceptor(ServerLevel level) {
		this.entityData.set(DATA_HEALTH, 0.0F);
		if (!this.isCrashing()) {
			this.goDown(level);
		}
	}

	@Override
	public @Nullable UUID getOwner() {
		return this.owner;
	}

	@Override
	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
	}

	/** Who the jet is flying for: its pilot, or else its owner. */
	public @Nullable UUID side() {
		Player p = this.pilot();
		return p != null ? p.getUUID() : this.owner;
	}

	// ------------------------------------------------------------------ saving

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("Type", this.entityData.get(DATA_TYPE));
		output.putInt("Ammo", this.ammo());
		output.putInt("Missiles", this.missiles());
		output.putInt("Flares", this.flares());
		output.putFloat("Health", this.health());
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.entityData.set(DATA_TYPE, input.getIntOr("Type", 0));
		this.entityData.set(DATA_AMMO, input.getIntOr("Ammo", this.type().gunRounds));
		this.entityData.set(DATA_MISSILES, input.getIntOr("Missiles", this.type().missiles));
		this.entityData.set(DATA_FLARES, input.getIntOr("Flares", 24));
		this.entityData.set(DATA_HEALTH, input.getFloatOr("Health", this.type().maxHealth));
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
	}
}
