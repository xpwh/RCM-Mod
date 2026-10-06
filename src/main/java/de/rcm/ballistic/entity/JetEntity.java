package de.rcm.ballistic.entity;

import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.explosion.DetonationManager;
import java.util.UUID;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Stealth strike fighter called in with the airstrike radio. It comes in fast and low from behind
 * the caller, opens its weapons bay, lays a carpet of free-fall bombs along its track centred on the
 * target, then lights both afterburners, pulls up and goes supersonic (with a sonic boom). The
 * release point is computed from the bombs' real fall (gravity and drag), so the stick lands where it
 * was ordered.
 */
public class JetEntity extends Entity implements AirThreat {
	private static final EntityDataAccessor<Vector3fc> DATA_DIR = SynchedEntityData.defineId(JetEntity.class, EntityDataSerializers.VECTOR3);
	private static final EntityDataAccessor<Float> DATA_BANK = SynchedEntityData.defineId(JetEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Integer> DATA_BOMBS = SynchedEntityData.defineId(JetEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> DATA_SPEED = SynchedEntityData.defineId(JetEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> DATA_BAY = SynchedEntityData.defineId(JetEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DATA_TYPE = SynchedEntityData.defineId(JetEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> DATA_FIRING = SynchedEntityData.defineId(JetEntity.class, EntityDataSerializers.BOOLEAN);
	/** A-10 gun run: opens fire this far before the target, ceases this close. */
	private static final double GUN_OPEN = 170.0;
	private static final double GUN_CEASE = 45.0;
	private boolean pullingUp;

	/** Speed of sound in blocks per tick (343 m/s). */
	public static final double SOUND_SPEED = 17.15;
	/** High-subsonic attack speed (about 600 km/h). */
	public static final double ATTACK_SPEED = 8.0;
	/** Supersonic dash after the attack, about Mach 1.3. */
	public static final double DASH_SPEED = 22.0;
	private static final double ACCELERATION = 0.45;
	/** Height of the bomb run above the target. */
	private static final double RUN_ALTITUDE = 60.0;
	/** Minimum clearance over terrain on the way in. */
	private static final double CLEARANCE = 28.0;
	public static final int BOMBS = 10;
	private static final int RELEASE_INTERVAL = 1;
	private static final double SPAWN_BEHIND = 320.0;
	public static final double MAX_RANGE = 1500.0;
	/** The bay opens this many blocks before the first release. */
	private static final double BAY_LEAD = 160.0;

	private Vec3 target = Vec3.ZERO;
	private int bombsLeft = BOMBS;
	private int releaseTimer;
	private boolean egress;
	private int egressAge;
	private @Nullable UUID caller;

	private int engagements;
	private int flares = 6;
	private int lastFlareTick = -1000;

	/** Client only: the listener is inside the trailing Mach cone (the boom has been heard). */
	public boolean clientInMachCone;
	/** Client: was the cannon firing last tick, and ticks until its sound reaches the listener. */
	public boolean clientWasFiring;
	public int clientGunSoundIn = -1;
	public Vec3 clientGunSoundFrom = Vec3.ZERO;

	public JetEntity(EntityType<? extends JetEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/**
	 * Spawns a jet behind {@code caller} that will bomb {@code target}. Returns null if the target is
	 * out of range.
	 */
	public static @Nullable JetEntity callIn(ServerLevel level, ServerPlayer caller, Vec3 target) {
		return callIn(level, caller, target, JetType.STRIKE, 0.0, 0.0);
	}

	/**
	 * Spawns an aircraft of the given type. {@code lateral} shifts both the jet and its aim point
	 * sideways (formation flying), {@code behind} lets it trail the lead.
	 */
	public static @Nullable JetEntity callIn(ServerLevel level, ServerPlayer caller, Vec3 target, JetType type, double lateral, double behind) {
		Vec3 from = caller.position();
		Vec3 heading = new Vec3(target.x - from.x, 0, target.z - from.z);
		double distance = heading.length();
		if (distance > MAX_RANGE) {
			return null;
		}
		heading = distance < 8.0 ? Vec3.directionFromRotation(0, caller.getYRot()) : heading.scale(1.0 / distance);
		JetEntity jet = ModRegistry.JET.create(level, EntitySpawnReason.TRIGGERED);
		if (jet == null) {
			return null;
		}
		Vec3 side = new Vec3(-heading.z, 0, heading.x).scale(lateral);
		double altitude = Math.max(target.y, from.y) + type.runAltitude;
		Vec3 start = new Vec3(from.x, altitude, from.z).subtract(heading.scale(SPAWN_BEHIND + behind)).add(side);
		jet.setPos(start);
		jet.target = target.add(side);
		jet.entityData.set(DATA_TYPE, type.ordinal());
		jet.entityData.set(DATA_SPEED, (float) type.attackSpeed);
		jet.bombsLeft = type.bombs;
		jet.entityData.set(DATA_BOMBS, type.bombs);
		jet.caller = caller.getUUID();
		jet.setDir(heading);
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(start)), 2);
		level.addFreshEntity(jet);
		return jet;
	}

	/** Seconds until the bombs hit, roughly: flight to the release point plus the fall. */
	public int etaSeconds() {
		double run = Math.hypot(this.target.x - this.getX(), this.target.z - this.getZ());
		return (int) Math.ceil((run / this.getJetType().attackSpeed + 60) / 20.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_DIR, new Vector3f(1, 0, 0));
		builder.define(DATA_BANK, 0.0F);
		builder.define(DATA_BOMBS, BOMBS);
		builder.define(DATA_SPEED, (float) ATTACK_SPEED);
		builder.define(DATA_BAY, false);
		builder.define(DATA_TYPE, JetType.STRIKE.ordinal());
		builder.define(DATA_FIRING, false);
	}

	public Vec3 getDir() {
		Vector3fc v = this.entityData.get(DATA_DIR);
		return new Vec3(v.x(), v.y(), v.z());
	}

	private void setDir(Vec3 d) {
		this.entityData.set(DATA_DIR, new Vector3f((float) d.x, (float) d.y, (float) d.z));
	}

	/** Roll angle in radians (positive = right wing down). */
	public float getBank() {
		return this.entityData.get(DATA_BANK);
	}

	/** Current airspeed in blocks per tick. */
	public float getSpeed() {
		return this.entityData.get(DATA_SPEED);
	}

	public float getMach() {
		return (float) (this.getSpeed() / SOUND_SPEED);
	}

	/** Bombs still in the bay (synced, so the renderer can show them). */
	public int getBombsLeft() {
		return this.entityData.get(DATA_BOMBS);
	}

	public boolean isBayOpen() {
		return this.entityData.get(DATA_BAY);
	}

	public JetType getJetType() {
		return JetType.byOrdinal(this.entityData.get(DATA_TYPE));
	}

	/** A-10 cannon firing (synced, for the muzzle flash and tracers). */
	public boolean isFiring() {
		return this.entityData.get(DATA_FIRING);
	}

	/** The fighter lights its afterburners once the stick is gone and it climbs out. */
	public boolean isAfterburner() {
		return this.getJetType() == JetType.STRIKE && this.getBombsLeft() <= 0;
	}

	@Override
	public void tick() {
		super.tick();
		if (!(this.level() instanceof ServerLevel level)) {
			ClientHooks.jetClientTick.accept(this);
			return;
		}
		ThreatTracker.report(level, this);
		Vec3 pos = this.position();
		Vec3 dir = this.getDir();
		double speed = this.getSpeed();
		Vec3 flat = new Vec3(dir.x, 0, dir.z).normalize();
		Vec3 desired;
		if (!this.egress) {
			for (int k = 0; k <= 160; k += 16) {
				level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(pos.add(flat.scale(k)))), 2);
			}
			// terrain following on the way in, level over the target area
			JetType type = this.getJetType();
			double alt = this.target.y + type.runAltitude;
			for (int k = 0; k <= 96; k += 16) {
				Vec3 probe = pos.add(flat.scale(k));
				int px = Mth.floor(probe.x);
				int pz = Mth.floor(probe.z);
				if (level.hasChunk(px >> 4, pz >> 4)) {
					alt = Math.max(alt, level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz) + (type == JetType.WARTHOG ? 16.0 : CLEARANCE));
				}
			}
			Vec3 toTarget = new Vec3(this.target.x - pos.x, 0, this.target.z - pos.z);
			Vec3 heading = toTarget.lengthSqr() > 900.0 && this.bombsLeft == type.bombs && !this.isFiring() ? toTarget.normalize() : flat;
			desired = heading.add(0, Mth.clamp((alt - pos.y) * 0.03, -0.25, 0.25), 0).normalize();
			double guardRange = lookahead(speed);
			double guardClearance = type == JetType.WARTHOG ? 8.0 : 12.0;
			if (type == JetType.WARTHOG) {
				Vec3 dive = this.gunRun(level, pos, flat);
				if (dive != null) {
					desired = dive;
					// only the ground between us and the aim point matters during the dive
					double along = (this.target.x - pos.x) * flat.x + (this.target.z - pos.z) * flat.z;
					guardRange = Math.min(guardRange, Math.max(12.0, along - 35.0));
					guardClearance = 5.0;
				}
			} else {
				this.bombRun(level, pos, flat, speed);
			}
			desired = this.avoidTerrain(level, pos, dir, desired, guardClearance, guardRange);
		} else {
			this.egressAge++;
			JetType type = this.getJetType();
			if (type == JetType.STRIKE) {
				// afterburner climb-out, accelerating through the sound barrier
				speed = Math.min(DASH_SPEED, speed + ACCELERATION);
				desired = flat.add(0, 0.38, 0).normalize();
			} else if (type == JetType.WARTHOG) {
				// pull off the target in a climbing break turn
				Vec3 away = new Vec3(flat.x * 0.5 - flat.z * 0.87, 0, flat.z * 0.5 + flat.x * 0.87);
				desired = away.add(0, 0.3, 0).normalize();
			} else {
				desired = flat.add(0, 0.12, 0).normalize(); // the bomber just keeps going, climbing gently
			}
			if (this.egressAge > 160) {
				this.discard();
				return;
			}
			desired = this.avoidTerrain(level, pos, dir, desired, 12.0, lookahead(speed));
		}

		double turnRate = this.pullingUp ? 0.22 : this.egress ? 0.05 : 0.08;
		Vec3 newDir = dir.add(desired.subtract(dir).scale(turnRate)).normalize();
		Vec3 next = pos.add(newDir.scale(speed));
		// last line of defence: never end a tick inside the terrain
		int nx = Mth.floor(next.x);
		int nz = Mth.floor(next.z);
		if (level.hasChunk(nx >> 4, nz >> 4)) {
			double floor = level.getHeight(Heightmap.Types.MOTION_BLOCKING, nx, nz) + 3.0;
			if (next.y < floor) {
				next = new Vec3(next.x, floor, next.z);
				newDir = new Vec3(newDir.x, Math.max(newDir.y, 0.35), newDir.z).normalize();
			}
		}
		if (!level.isPositionEntityTicking(BlockPos.containing(next))) {
			if (this.egress) {
				this.discard(); // flown out of the loaded world
			}
			return; // on the way in: wait for the chunk to finish loading
		}
		// bank into turns: sign of the heading change around the vertical axis
		double turn = dir.x * newDir.z - dir.z * newDir.x;
		float bank = (float) Mth.clamp(turn * 25.0, -1.1, 1.1);
		this.entityData.set(DATA_BANK, Mth.lerp(0.2F, this.getBank(), bank));
		this.entityData.set(DATA_SPEED, (float) speed);
		this.setDir(newDir);
		this.setPos(next);
		if (this.tickCount > 6000) {
			this.discard();
		}
	}

	/** How far ahead the terrain guard looks: about four seconds of flight. */
	private static double lookahead(double speed) {
		return Mth.clamp(speed * 40.0, 120.0, 420.0);
	}

	/**
	 * Terrain guard: scans the ground ahead (along the current heading and the one we want) and, if
	 * the planned path would come closer than {@code clearance} to a hill, ridge or treetop, replaces
	 * it with a climb steep enough to clear the highest obstacle - pulling up hard while it lasts.
	 */
	private Vec3 avoidTerrain(ServerLevel level, Vec3 pos, Vec3 dir, Vec3 desired, double clearance, double range) {
		Vec3 wanted = new Vec3(desired.x, 0, desired.z);
		Vec3 current = new Vec3(dir.x, 0, dir.z);
		if (wanted.lengthSqr() < 1.0E-6) {
			wanted = current;
		}
		if (current.lengthSqr() < 1.0E-6) {
			current = wanted;
		}
		wanted = wanted.normalize();
		current = current.normalize();
		double need = Double.NEGATIVE_INFINITY; // climb gradient that clears everything ahead
		for (double k = 4.0; k <= range; k += 4.0) {
			for (Vec3 heading : new Vec3[] {wanted, current}) {
				Vec3 probe = pos.add(heading.scale(k));
				int px = Mth.floor(probe.x);
				int pz = Mth.floor(probe.z);
				if (!level.hasChunk(px >> 4, pz >> 4)) {
					continue;
				}
				double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz);
				need = Math.max(need, (ground + clearance - pos.y) / k);
			}
		}
		double horizontal = Math.max(1.0E-3, Math.hypot(desired.x, desired.z));
		double slope = desired.y / horizontal;
		this.pullingUp = need > slope && need > -0.05;
		if (!this.pullingUp) {
			return desired;
		}
		return wanted.add(0, Math.min(need + 0.08, 1.5), 0).normalize();
	}

	private void bombRun(ServerLevel level, Vec3 pos, Vec3 flat, double speed) {
		double fall = Math.max(1.0, pos.y - this.target.y);
		double throwDistance = forwardThrow(speed, fall);
		// distance of the target ahead of us along the track
		double along = (this.target.x - pos.x) * flat.x + (this.target.z - pos.z) * flat.z;
		int bombs = this.getJetType().bombs;
		double halfStick = (bombs - 1) * RELEASE_INTERVAL * speed * 0.5;
		if (this.bombsLeft == bombs && along > throwDistance + halfStick) {
			if (!this.isBayOpen() && along < throwDistance + halfStick + BAY_LEAD) {
				this.entityData.set(DATA_BAY, true);
			}
			return;
		}
		if (this.bombsLeft == bombs) {
			this.tellCaller(level, Component.translatable("message.ballisticmissiles.airstrike_release").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		}
		if (this.releaseTimer-- > 0) {
			return;
		}
		this.releaseTimer = RELEASE_INTERVAL - 1;
		AerialBombEntity bomb = ModRegistry.AERIAL_BOMB.create(level, EntitySpawnReason.TRIGGERED);
		if (bomb != null) {
			Vec3 side = new Vec3(-flat.z, 0, flat.x).scale((level.getRandom().nextDouble() - 0.5) * 3.0);
			Vec3 release = pos.add(0, -1.2, 0).add(side);
			boolean moab = this.getJetType() == JetType.SPIRIT;
			if (moab) {
				bomb.setMoab(true);
				release = pos.add(0, -2.5, 0);
			}
			bomb.setPos(release);
			bomb.setDeltaMovement(flat.scale(speed));
			level.addFreshEntity(bomb);
			if (moab) {
				level.playSound(null, release.x, release.y, release.z, ModRegistry.BOMB_WHISTLE, SoundSource.HOSTILE, 16.0F, 0.55F);
			} else if (this.bombsLeft % 3 == 0) {
				level.playSound(null, release.x, release.y, release.z, ModRegistry.BOMB_WHISTLE, SoundSource.HOSTILE, 8.0F, 0.9F + level.getRandom().nextFloat() * 0.2F);
			}
		}
		this.entityData.set(DATA_BOMBS, --this.bombsLeft);
		if (this.bombsLeft <= 0) {
			this.egress = true;
			this.entityData.set(DATA_BAY, false);
		}
	}

	/**
	 * A-10 strafing run: dives shallowly onto the target and walks a burst of 30 mm rounds through it,
	 * about three rounds a tick. Returns the dive direction while the run is on, else null.
	 */
	private @Nullable Vec3 gunRun(ServerLevel level, Vec3 pos, Vec3 flat) {
		double along = (this.target.x - pos.x) * flat.x + (this.target.z - pos.z) * flat.z;
		if (along > GUN_OPEN + 120.0) {
			return null;
		}
		if (along < GUN_CEASE) {
			this.entityData.set(DATA_FIRING, false);
			this.egress = true;
			return null;
		}
		// the burst walks along the track through the target
		double f = Mth.clamp((GUN_OPEN - along) / (GUN_OPEN - GUN_CEASE), 0.0, 1.0);
		Vec3 aim = this.target.add(flat.scale(-20.0 + 40.0 * f));
		Vec3 dive = aim.subtract(pos).normalize();
		if (along > GUN_OPEN) {
			return dive;
		}
		if (!this.isFiring()) {
			this.entityData.set(DATA_FIRING, true);
			this.tellCaller(level, Component.translatable("message.ballisticmissiles.a10_guns").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		}
		var random = level.getRandom();
		Vec3 muzzle = pos.add(0, 0.6, 0).add(this.getDir().scale(7.5));
		for (int i = 0; i < 3; i++) {
			Vec3 spread = aim.add(random.nextGaussian() * 1.6, 0, random.nextGaussian() * 1.6);
			Vec3 end = muzzle.add(spread.subtract(muzzle).normalize().scale(300.0));
			var hit = level.clip(new ClipContext(muzzle, end, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.ANY, this));
			Vec3 p = hit.getLocation();
			level.sendParticles(ParticleTypes.EXPLOSION, p.x, p.y, p.z, 1, 0.2, 0.2, 0.2, 0.0);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y + 0.3, p.z, 3, 0.4, 0.4, 0.4, 0.02);
			for (var living : level.getEntitiesOfClass(LivingEntity.class, new AABB(p, p).inflate(2.0))) {
				living.hurtServer(level, level.damageSources().explosion(this, null), 14.0F);
			}
			if (random.nextInt(8) == 0) {
				level.explode(this, p.x, p.y, p.z, 1.6F, false, Level.ExplosionInteraction.TNT);
			}
		}
		return dive;
	}

	/** Horizontal distance a bomb released at {@code speed} travels while falling {@code height} blocks. */
	public static double forwardThrow(double speed, double height) {
		double x = 0.0;
		double y = 0.0;
		double vx = speed;
		double vy = 0.0;
		for (int t = 0; t < 2000 && y < height; t++) {
			vx *= 0.992;
			vy = (vy + 0.05) * 0.992;
			x += vx;
			y += vy;
		}
		return x;
	}

	private void tellCaller(ServerLevel level, Component message) {
		if (this.caller != null && level.getPlayerByUUID(this.caller) instanceof ServerPlayer player) {
			player.displayClientMessage(message, true);
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	// ------------------------------------------------------------------ as a target for air defense

	public @Nullable UUID getCaller() {
		return this.caller;
	}

	@Override
	public Entity asEntity() {
		return this;
	}

	@Override
	public boolean isActiveThreat() {
		return this.isAlive();
	}

	@Override
	public Vec3 aimPoint(int ticksAhead) {
		return this.position().add(0, 0.8, 0).add(this.getDir().scale(this.getSpeed() * ticksAhead));
	}

	@Override
	public Vec3 threatVelocity() {
		return this.getDir().scale(this.getSpeed());
	}

	/** A strike aircraft does not hit the ground: defenses engage it over the point it flies across. */
	@Override
	public Vec3 predictedImpact() {
		Vec3 ahead = this.aimPoint(20);
		return new Vec3(ahead.x, this.target.y, ahead.z);
	}

	@Override
	public int etaTicks() {
		return 20;
	}

	@Override
	public ThreatClass threatClass() {
		return ThreatClass.AIRCRAFT;
	}

	/** Stealth airframes are tiny on radar; the A-10 is not. */
	@Override
	public double radarCrossSection() {
		return this.getJetType().radarCrossSection;
	}

	/** Flares and hard manoeuvring: a fresh flare salvo decoys most missiles. */
	@Override
	public float killProbability() {
		return this.tickCount - this.lastFlareTick < 50 ? 0.2F : 0.6F;
	}

	@Override
	public String nameKey() {
		return "entity.ballisticmissiles." + this.getJetType().id;
	}

	@Override
	public int getEngagements() {
		return this.engagements;
	}

	/** A new missile fired at the jet: the warning receiver triggers a flare salvo. */
	@Override
	public void setEngagements(int engagements) {
		if (engagements > this.engagements && this.flares > 0 && this.level() instanceof ServerLevel level) {
			this.flares--;
			this.lastFlareTick = this.tickCount;
			Vec3 tail = this.position().add(0, 0.5, 0).subtract(this.getDir().scale(5.0));
			for (int i = 0; i < 12; i++) {
				level.sendParticles(ParticleTypes.FIREWORK, tail.x, tail.y, tail.z, 4, 1.5, 1.0, 1.5, 0.25);
				level.sendParticles(ParticleTypes.FLAME, tail.x, tail.y - i * 0.6, tail.z, 3, 1.2, 0.4, 1.2, 0.05);
			}
			level.playSound(null, tail.x, tail.y, tail.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 6.0F, 0.6F);
		}
		this.engagements = engagements;
	}

	@Override
	public void destroyByInterceptor(ServerLevel level) {
		Vec3 p = this.position().add(0, 0.8, 0);
		DetonationManager.intercepted(level, p, this);
		level.explode(this, p.x, p.y, p.z, 4.0F, true, Level.ExplosionInteraction.NONE);
		this.tellCaller(level, Component.translatable("message.ballisticmissiles.jet_shot_down").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		this.discard();
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 1024 * 1024;
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
