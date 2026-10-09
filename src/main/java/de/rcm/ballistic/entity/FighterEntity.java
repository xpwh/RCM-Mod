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
	public static final int ACTION_CRASH = 3;

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
	/** Game time the engine start began, or -1 with the engine off. */
	private static final EntityDataAccessor<Integer> DATA_ENGINE = SynchedEntityData.defineId(FighterEntity.class, EntityDataSerializers.INT);
	/** Ticks from pressing the starter to a stable idle (the jet fuel starter spins the engine up, light-off, spool-up). */
	public static final int START_TICKS = 170;

	private final InterpolationHandler interpolation = new InterpolationHandler(this, 2);

	// controls (from the pilot's packets)
	private float throttleAxis;
	/** Afterburner lit: hold "throttle up" at full power to light it, "throttle down" puts it out. */
	private boolean afterburnerOn;
	private int afterburnerHold;
	private float pitchAxis;
	/** Stick position, eased towards the keys (a key is all or nothing, a stick is not). */
	private float pitchStick;
	private float rollStick;
	/** The jet's own "up" (the authoritative side keeps the attitude as vectors: no gimbal flips in a loop). */
	private @Nullable Vec3 bodyUp;
	private float rollAxis;
	private boolean trigger;
	private int lastInput = -100;
	// flight, worked out by whichever side flies the jet (see fly())
	private double speed;
	private float throttleL;
	private boolean abL;
	private float rollL;
	private float gL = 1.0F;
	private boolean gearL = true;
	private boolean hadAuthority;
	private boolean wasOnGround = true;
	private boolean crashReported;
	private boolean crashSent;
	private double sink;
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
		this.setNoGravity(true); // it flies on its own wings (and servers would otherwise kick the pilot for flying)
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
		builder.define(DATA_ENGINE, -1);
	}

	// ------------------------------------------------------------------ state

	public FighterType type() {
		return FighterType.byId(this.entityData.get(DATA_TYPE));
	}

	public float throttle() {
		return this.simulatedHere() ? this.throttleL : this.entityData.get(DATA_THROTTLE);
	}

	public boolean isAfterburner() {
		return this.simulatedHere() ? this.abL : this.entityData.get(DATA_AB);
	}

	public float roll() {
		return this.simulatedHere() ? this.rollL : this.entityData.get(DATA_ROLL);
	}

	public float getRoll(float partialTick) {
		return Mth.rotLerp(partialTick, this.rollO, this.roll());
	}

	public float speed() {
		return this.simulatedHere() ? (float) this.speed : this.entityData.get(DATA_SPEED);
	}

	/** The pilot's own client works the flight out itself; everyone else is told by the server. */
	private boolean simulatedHere() {
		return this.level().isClientSide() && this.hadAuthority;
	}

	public float mach() {
		return (float) (this.speed() / SOUND_SPEED);
	}

	public float gLoad() {
		return this.simulatedHere() ? this.gL : this.entityData.get(DATA_G);
	}

	public boolean gearDown() {
		return this.simulatedHere() ? this.gearL : this.entityData.get(DATA_GEAR);
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

	/** How far the engine has spooled up: 0 off, 1 running (idle or above). */
	public float spool() {
		int start = this.entityData.get(DATA_ENGINE);
		return start < 0 ? 0.0F : Mth.clamp((float) (this.level().getGameTime() - start) / START_TICKS, 0.0F, 1.0F);
	}

	public boolean engineRunning() {
		return this.spool() >= 1.0F;
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

	/**
	 * Pilot's client, before the jet ticks: throttle (+1 up, -1 down), stick pitch (+1 pull = nose up) and
	 * roll (+1 right). Where the pilot looks does not steer: he can look around freely.
	 */
	public void setControls(float throttleAxis, float pitch, float roll, boolean trigger) {
		this.throttleAxis = Mth.clamp(throttleAxis, -1.0F, 1.0F);
		this.pitchAxis = Mth.clamp(pitch, -1.0F, 1.0F);
		this.rollAxis = Mth.clamp(roll, -1.0F, 1.0F);
		this.trigger = trigger;
	}

	/** Speed at which the nose can be lifted off the runway (b/t). */
	public double rotateSpeed() {
		return this.type().stallSpeed * 0.85;
	}

	/** Pilot's client: true once, after the jet hit something it could not survive (the server then wrecks it). */
	public boolean takeCrashReport() {
		boolean r = this.crashReported && !this.crashSent;
		this.crashSent |= r;
		return r;
	}

	/** Pilot's client: the flight state it has worked out, for the server to pass on to everyone else. */
	public float simThrottle() {
		return this.throttleL;
	}

	public boolean simAfterburner() {
		return this.abL;
	}

	public float simRoll() {
		return this.rollL;
	}

	public float simG() {
		return this.gL;
	}

	public boolean simGear() {
		return this.gearL;
	}

	public double simSpeed() {
		return this.speed;
	}

	/**
	 * Server: the pilot's state this tick. His client flies the jet (like a boat), so it moves smoothly for
	 * him at any speed; the server takes the flight state from him and runs the weapons, damage and radar.
	 */
	public void input(ServerPlayer player, float throttle, boolean afterburner, float roll, float speed, float g, boolean gear, Vec3 velocity,
		boolean trigger, int action, int lockTarget) {
		if (player != this.pilot() || !Float.isFinite(throttle) || !Float.isFinite(roll) || !Float.isFinite(speed) || !Float.isFinite(g)
			|| !Double.isFinite(velocity.lengthSqr())) {
			return;
		}
		FighterType type = this.type();
		this.throttleL = Mth.clamp(throttle, 0.0F, 1.0F);
		this.abL = afterburner && this.throttleL > 0.95F;
		this.rollL = Mth.wrapDegrees(roll);
		this.speed = Mth.clamp(speed, 0.0F, type.maxSpeed * 1.3F);
		this.gL = Mth.clamp(g, 0.0F, 15.0F);
		this.gearL = gear;
		double v = velocity.length();
		double cap = type.maxSpeed * 1.5;
		this.setDeltaMovement(v > cap ? velocity.scale(cap / v) : velocity);
		this.trigger = trigger;
		this.lastInput = this.tickCount;
		ServerLevel level = (ServerLevel) this.level();
		if (action == ACTION_MISSILE) {
			this.fireMissile(level, player, lockTarget);
		} else if (action == ACTION_FLARES) {
			this.releaseFlares(level);
		} else if (action == ACTION_CRASH && !this.isRemoved()) {
			this.crash(level);
		}
	}

	// ------------------------------------------------------------------ flight

	@Override
	public void tick() {
		this.rollO = this.roll();
		super.tick();
		this.interpolation.interpolate();
		boolean authority = this.isLocalInstanceAuthoritative();
		if (authority && !this.hadAuthority) {
			// taking over the flight (boarding, or the server after an ejection): carry on from where it is
			this.throttleL = this.entityData.get(DATA_THROTTLE);
			this.abL = this.entityData.get(DATA_AB);
			this.rollL = this.entityData.get(DATA_ROLL);
			this.bodyUp = null;
			this.afterburnerOn = this.abL;
			this.gL = this.entityData.get(DATA_G);
			this.gearL = this.entityData.get(DATA_GEAR);
			this.speed = this.entityData.get(DATA_SPEED);
			this.crashReported = false;
			this.crashSent = false;
		}
		this.hadAuthority = authority;
		if (this.level().isClientSide()) {
			if (authority && !this.crashReported && this.fly()) {
				this.crashReported = true; // reported with the next input; the server wrecks it
				this.speed = 0.0;
			}
			return;
		}
		ServerLevel level = (ServerLevel) this.level();
		if (authority && this.fly()) {
			this.crash(level);
			return;
		}
		if (!authority && this.tickCount - this.lastInput > 10) {
			this.trigger = false; // the pilot's packets stopped coming
		}
		if (this.isInWater() && this.speed > 1.0) {
			this.crash(level);
			return;
		}
		boolean ground = this.onGround();
		if (ground && !this.wasOnGround && this.tickCount > 5) {
			if (this.speed > 2.5 && this.gearDown()) {
				// the main wheels spin up from standstill: a screech and a puff of rubber smoke
				level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.JET_TYRE, SoundSource.NEUTRAL, 2.5F, 0.9F + this.random.nextFloat() * 0.2F);
				Vec3 r = this.rightVec(this.forward());
				for (int side = -1; side <= 1; side += 2) {
					Vec3 wheel = this.position().add(r.scale(side * 1.6)).subtract(this.forward().scale(1.0));
					level.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, true, false, wheel.x, wheel.y + 0.2, wheel.z, 6, 0.3, 0.1, 0.3, 0.02);
				}
			} else {
				level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.METAL_THUD, SoundSource.NEUTRAL, 2.0F, 0.7F);
			}
		}
		this.wasOnGround = ground;
		if (this.gearL != this.gearDown()) {
			level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.HYDRAULIC_EXTEND, SoundSource.NEUTRAL, 1.2F, 1.3F);
		}
		// pass the flight state on to everyone watching
		this.entityData.set(DATA_THROTTLE, this.throttleL);
		this.entityData.set(DATA_AB, this.abL);
		this.entityData.set(DATA_ROLL, this.rollL);
		this.entityData.set(DATA_SPEED, (float) this.speed);
		this.entityData.set(DATA_G, this.gL);
		this.entityData.set(DATA_GEAR, this.gearL);

		// weapons, bay, chunk loading, radar
		Player pilot = this.pilot();
		this.gun(level, pilot != null && !this.isCrashing() && this.trigger, this.forward());
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
			Vec3 ahead = this.forward().scale(this.speed * 12.0);
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, this.chunkPosition(), 2);
			level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(this.position().add(ahead))), 2);
		}
		if (this.isActiveThreat()) {
			ThreatTracker.report(level, this);
		}
	}

	/**
	 * One tick of flight, on whichever side flies the jet: the pilot's client, or the server when nobody
	 * is at the stick. Returns true if it hit something it cannot survive.
	 */
	private boolean fly() {
		Level level = this.level();
		FighterType type = this.type();
		boolean flown = this.getControllingPassenger() != null;
		boolean grounded = this.onGround();

		// throttle and afterburner
		float throttle = this.throttleL;
		boolean running = this.engineRunning();
		if (!running) {
			throttle = 0.0F; // still starting up (or off): the throttle stays at idle
		} else if (flown) {
			throttle = Mth.clamp(throttle + this.throttleAxis * 0.015F, 0.0F, 1.0F);
			// full power, keep pushing: through the gate into afterburner
			this.afterburnerHold = this.throttleAxis > 0.0F && throttle >= 1.0F ? this.afterburnerHold + 1 : 0;
			if (this.afterburnerHold > 6) {
				this.afterburnerOn = true;
			}
			if (this.throttleAxis < 0.0F) {
				this.afterburnerOn = false;
			}
		} else if (grounded) {
			throttle = Math.max(0.0F, throttle - 0.02F);
		}
		this.throttleL = throttle;
		this.abL = flown && running && this.afterburnerOn && throttle > 0.95F;

		// attitude: the nose (fwd) and the jet's own up, kept as vectors
		Vec3 fwd = this.forward();
		Vec3 up = this.bodyUp != null ? this.bodyUp : this.up(this.rollL);
		up = up.subtract(fwd.scale(up.dot(fwd)));
		up = up.lengthSqr() < 1.0E-6 ? this.up(this.rollL) : up.normalize();
		float oldYaw = this.getYRot();
		float oldPitch = this.getXRot();
		this.pitchStick = Mth.approach(this.pitchStick, flown ? this.pitchAxis : 0.0F, 0.15F);
		this.rollStick = Mth.approach(this.rollStick, flown ? this.rollAxis : 0.0F, 0.2F);
		Vec3 newFwd;
		if (grounded) {
			// on the wheels: A/D steer the nosewheel, S lifts the nose once fast enough to fly
			Vec3 flat = new Vec3(fwd.x, 0.0, fwd.z);
			flat = flat.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, 1) : flat.normalize();
			double steer = Math.toRadians(1.6) * this.rollStick * Mth.clamp(this.speed / 0.4, 0.35, 1.0) / (1.0 + this.speed * 0.4);
			Vec3 dir = rotY(flat, -steer);
			double pitch = Math.asin(Mth.clamp(fwd.y, -1.0, 1.0));
			if (this.speed > this.rotateSpeed() && this.pitchStick > 0.0F) {
				pitch = Math.min(Math.toRadians(16.0), pitch + Math.toRadians(1.2) * this.pitchStick);
			} else {
				pitch = Math.max(0.0, pitch - Math.toRadians(1.0));
			}
			newFwd = dir.scale(Math.cos(pitch)).add(0.0, Math.sin(pitch), 0.0);
			Vec3 right = newFwd.cross(new Vec3(0, 1, 0)).normalize();
			up = right.cross(newFwd).normalize();
		} else {
			Vec3[] frame = {fwd, up};
			double v = Math.max(this.speed, 1.0);
			if (flown) {
				// roll about the nose: the F-22 and F-35 roll at some 180 degrees a second
				roll(frame, Math.toRadians(type == FighterType.F22 ? 9.0 : 8.0) * this.rollStick);
				// pitch: the stick pulls the nose towards the jet's own up, as hard as the g limit allows
				pitch(frame, Math.toRadians(this.turnRate(type)) * this.pitchStick);
			} else {
				// nobody at the stick (or shot to pieces): it rolls off and the nose sags
				roll(frame, Math.toRadians(this.isCrashing() ? 2.0 : 0.5));
			}
			// the wings' lift tilts with the bank and turns the jet; what it no longer holds up, gravity pulls down
			Vec3 rightH = frame[0].cross(new Vec3(0, 1, 0));
			if (rightH.lengthSqr() > 1.0E-6) {
				rightH = rightH.normalize();
				double bank = frame[1].dot(rightH);
				turnY(frame, -GRAVITY * bank / v);
				double lost = GRAVITY * (1.0 - frame[1].y) / v;
				// below flying speed the wings stop holding it up at all and the nose drops
				if (this.speed < type.stallSpeed) {
					lost += Math.toRadians(1.5) * (1.0 - this.speed / type.stallSpeed);
				}
				pitchDown(frame, lost);
			}
			newFwd = frame[0];
			up = frame[1];
		}
		this.bodyUp = up;
		double turned = Math.acos(Mth.clamp(fwd.dot(newFwd), -1.0, 1.0));
		this.setRot((float) (Mth.atan2(-newFwd.x, newFwd.z) * Mth.RAD_TO_DEG), (float) (-Math.asin(Mth.clamp(newFwd.y, -1.0, 1.0)) * Mth.RAD_TO_DEG));
		// the roll the renderer and everyone else see: the angle of our up from the unbanked up
		Vec3 r0 = newFwd.cross(new Vec3(0, 1, 0));
		r0 = r0.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : r0.normalize();
		Vec3 u0 = r0.cross(newFwd).normalize();
		this.rollL = (float) Math.toDegrees(Math.atan2(up.dot(r0), up.dot(u0)));
		// the pilot's head turns with the jet: what he looks at stays where it is in the cockpit
		if (flown && this.level().isClientSide() && this.getControllingPassenger() instanceof Player pilot) {
			float dy = Mth.wrapDegrees(this.getYRot() - oldYaw);
			float dp = this.getXRot() - oldPitch;
			pilot.setYRot(pilot.getYRot() + dy);
			pilot.setYHeadRot(pilot.getYHeadRot() + dy);
			pilot.setXRot(Mth.clamp(pilot.getXRot() + dp, -90.0F, 90.0F));
		}

		// speed: thrust, drag rising with the square of speed, gravity along the climb or dive
		// even at idle a jet engine pushes: the jet creeps forward with the brakes off
		// a jet engine gives most thrust standing still and loses some as the air rams in: off the brakes it
		// pushes like a real one (about 0.7 g dry, 1.1 g with afterburner), up high it balances the drag
		double base = this.abL ? type.afterburnerThrust : type.dryThrust();
		double ram = Math.max(0.0, 1.0 - this.speed / (type.stallSpeed * 2.0));
		double thrust = this.isCrashing() || !running ? 0.0
			: Math.max(throttle, 0.03F) * (base + Math.max(0.0, (this.abL ? 0.03 : 0.02) - base) * ram);
		double drag = type.drag() * this.speed * this.speed + (this.gearL ? 0.0004 * this.speed : 0.0);
		if (grounded) {
			// brakes on with the throttle closed; otherwise rolling resistance - small on a runway, on grass and
			// dirt the wheels sink in and the jet barely gets to flying speed
			boolean runway = de.rcm.ballistic.runway.RunwayBuilder.isRunway(level.getBlockState(this.getOnPos()));
			drag += throttle < 0.05F ? 0.06 : runway ? 0.0008 : 0.0016 + 0.004 * Math.min(1.0, this.speed / type.stallSpeed);
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
			&& !(this.gearL && fallSpeed < 0.45 && Math.abs(this.getXRot()) < 18.0F && this.speed < 7.5) && this.speed > 1.0) {
			return true;
		}
		if (this.onGround() && !grounded) {
			this.setXRot(Math.min(0.0F, this.getXRot()));
		}
		if (this.isInWater() && this.speed > 1.0) {
			return true;
		}

		// gear: up once airborne and fast, down when slow and near the ground
		double agl = this.getY() - level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(this.getX()), Mth.floor(this.getZ()));
		this.gearL = this.onGround() || this.speed < 7.0 && agl < 40.0;

		// g: how hard the flight path is being bent
		double omegaMs = turned * 20.0;
		double vMs = this.speed * 20.0;
		this.gL = (float) Math.sqrt(1.0 + Math.pow(vMs * omegaMs / 9.81, 2));
		return false;
	}

	private static Vec3 rot(Vec3 v, Vec3 axis, double angle) {
		double c = Math.cos(angle);
		double sn = Math.sin(angle);
		return v.scale(c).add(axis.cross(v).scale(sn)).add(axis.scale(axis.dot(v) * (1.0 - c)));
	}

	private static Vec3 rotY(Vec3 v, double angle) {
		return rot(v, new Vec3(0, 1, 0), angle);
	}

	/** Roll the frame {fwd, up} about the nose; positive banks right. */
	private static void roll(Vec3[] frame, double angle) {
		frame[1] = rot(frame[1], frame[0], angle).normalize();
	}

	/** Pitch the frame: positive lifts the nose towards the jet's up. */
	private static void pitch(Vec3[] frame, double angle) {
		Vec3 f = frame[0].scale(Math.cos(angle)).add(frame[1].scale(Math.sin(angle))).normalize();
		Vec3 u = frame[1].scale(Math.cos(angle)).subtract(frame[0].scale(Math.sin(angle))).normalize();
		frame[0] = f;
		frame[1] = u;
	}

	/** Turn the whole frame about the vertical. */
	private static void turnY(Vec3[] frame, double angle) {
		frame[0] = rotY(frame[0], angle).normalize();
		frame[1] = rotY(frame[1], angle).normalize();
	}

	/** Bend the flight path down towards the ground (the frame turns with it). */
	private static void pitchDown(Vec3[] frame, double angle) {
		Vec3 axis = frame[0].cross(new Vec3(0, -1, 0));
		if (axis.lengthSqr() < 1.0E-6 || angle <= 0.0) {
			return;
		}
		axis = axis.normalize();
		frame[0] = rot(frame[0], axis, angle).normalize();
		frame[1] = rot(frame[1], axis, angle).normalize();
	}

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
		if (!this.level().isClientSide() && this.onGround() && !this.isRemoved() && this.entityData.get(DATA_ENGINE) >= 0) {
			// climbing out on the ground: engine shut down, canopy up
			this.entityData.set(DATA_ENGINE, -1);
			this.throttleL = 0.0F;
			this.level().playSound(null, this.getX(), this.getY() + CENTRE, this.getZ(), ModRegistry.JET_SHUTDOWN, SoundSource.NEUTRAL, 2.5F,
				this.type().enginePitch);
			this.level().playSound(null, this.getX(), this.getY() + CENTRE, this.getZ(), ModRegistry.HYDRAULIC_EXTEND, SoundSource.NEUTRAL, 1.0F, 1.4F);
		}
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
			if (player.startRiding(this)) {
				// canopy down, then the start: the jet fuel starter winds the engine up to light-off and idle
				this.level().playSound(null, this.getX(), this.getY() + CENTRE, this.getZ(), ModRegistry.HYDRAULIC_RETRACT, SoundSource.NEUTRAL, 1.0F, 1.4F);
				if (this.entityData.get(DATA_ENGINE) < 0) {
					this.entityData.set(DATA_ENGINE, (int) this.level().getGameTime());
					this.level().playSound(null, this.getX(), this.getY() + CENTRE, this.getZ(), ModRegistry.JET_STARTUP, SoundSource.NEUTRAL, 3.0F,
						this.type().enginePitch);
				}
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		// the pilot's client flies it (like a boat); once it is going down, the server takes over
		return !this.isCrashing() && this.getFirstPassenger() instanceof Player p ? p : null;
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
		output.putBoolean("EngineRunning", this.entityData.get(DATA_ENGINE) >= 0);
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
		this.entityData.set(DATA_ENGINE, input.getBooleanOr("EngineRunning", false) ? 0 : -1);
	}
}
