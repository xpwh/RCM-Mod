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
	private static final double GUN_CEASE = 55.0;
	/** Terrain guard: climb gradient being held (decays slowly, so the nose doesn't bob). */
	private double guardSlope = -1.0;
	private double pullUp;
	/** Heading chosen once when the attack is over. */
	private @Nullable Vec3 egressHeading;
	/** A-10 pattern: phase, passes flown, ticks in the phase, and the terrain guard it asks for. */
	private static final int A10_INBOUND = 0;
	private static final int A10_ATTACK = 1;
	private static final int A10_REPOSITION = 2;
	private static final int A10_PASSES = 2;
	private static final double ROLL_IN = 230.0;
	private int a10Phase;
	private int a10Passes;
	private int a10PhaseAge;
	private double guardRange;
	private double guardClearance;

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
		this.processShells(level);
		if (this.getJetType() == JetType.APACHE) {
			this.apacheTick(level);
			return;
		}
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
				this.guardRange = guardRange;
				this.guardClearance = guardClearance;
				desired = this.warthogPattern(level, pos, flat, alt, desired);
				guardRange = Math.min(guardRange, this.guardRange);
				guardClearance = this.guardClearance;
			} else if (type == JetType.GUNSHIP || type == JetType.REAPER) {
				level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(this.target)), 2);
				desired = this.orbitPattern(level, pos, alt, desired);
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
				// after the last pass: climb away on the break-turn heading
				if (this.egressHeading == null) {
					this.egressHeading = flat;
				}
				desired = this.egressHeading.add(0, 0.28, 0).normalize();
			} else {
				desired = flat.add(0, 0.12, 0).normalize(); // the bomber just keeps going, climbing gently
			}
			if (this.egressAge > 160) {
				this.discard();
				return;
			}
			desired = this.avoidTerrain(level, pos, dir, desired, 12.0, lookahead(speed));
		}

		double turnRate = (this.egress ? 0.06 : 0.08) + 0.12 * this.pullUp;
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

	// ------------------------------------------------------------------ AC-130, MQ-9, AH-64

	/** A delayed impact: shells in flight from the gunship, detonating when they arrive. */
	private record Shell(int dueTick, Vec3 pos, boolean heavy) {
	}

	private final java.util.List<Shell> shells = new java.util.ArrayList<>();
	private int orbitTicks = -1;
	private int shotsFired;
	private Vec3 hoverVelocity = Vec3.ZERO;

	private void processShells(ServerLevel level) {
		for (java.util.Iterator<Shell> it = this.shells.iterator(); it.hasNext(); ) {
			Shell s = it.next();
			if (this.tickCount >= s.dueTick()) {
				if (s.heavy()) {
					DetonationManager.detonateHowitzerShell(level, s.pos(), this);
				} else {
					DetonationManager.detonateSmallRound(level, s.pos(), this, 1.7F);
				}
				it.remove();
			}
		}
	}

	/** Where to shoot: a living thing near the target (not the caller), else the target itself. */
	private Vec3 pickAim(ServerLevel level, double spread) {
		var random = level.getRandom();
		var nearby = level.getEntitiesOfClass(LivingEntity.class, new AABB(this.target, this.target).inflate(28.0, 16.0, 28.0),
			e -> e.isAlive() && !e.getUUID().equals(this.caller));
		Vec3 aim = !nearby.isEmpty() && random.nextInt(3) > 0 ? nearby.get(random.nextInt(nearby.size())).position() : this.target;
		double x = aim.x + random.nextGaussian() * spread;
		double z = aim.z + random.nextGaussian() * spread;
		int y = level.hasChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z)) : (int) aim.y;
		return new Vec3(x, Math.max(y, aim.y - 3.0), z);
	}

	private @Nullable LivingEntity pickTargetEntity(ServerLevel level) {
		var nearby = level.getEntitiesOfClass(LivingEntity.class, new AABB(this.target, this.target).inflate(28.0, 16.0, 28.0),
			e -> e.isAlive() && !e.getUUID().equals(this.caller));
		return nearby.isEmpty() ? null : nearby.get(level.getRandom().nextInt(nearby.size()));
	}

	/**
	 * AC-130 and MQ-9: fly to the target, then circle it in a left-hand pylon turn (guns and sensors
	 * look out of the left side) and work it over: the gunship with its 105 mm howitzer and 40 mm
	 * Bofors, the Reaper with four Hellfires. Then they leave.
	 */
	private Vec3 orbitPattern(ServerLevel level, Vec3 pos, double alt, Vec3 cruise) {
		JetType type = this.getJetType();
		double radius = type == JetType.GUNSHIP ? 60.0 : 150.0;
		Vec3 rel = new Vec3(pos.x - this.target.x, 0, pos.z - this.target.z);
		double r = rel.length();
		if (this.orbitTicks < 0) {
			if (r > radius + 50.0) {
				return cruise;
			}
			this.orbitTicks = 0;
			this.tellCaller(level, Component.translatable(type == JetType.GUNSHIP ? "message.ballisticmissiles.ac130_orbit" : "message.ballisticmissiles.reaper_orbit")
				.withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
		}
		this.orbitTicks++;
		Vec3 radial = r > 1.0E-3 ? rel.scale(1.0 / r) : new Vec3(1, 0, 0);
		Vec3 tangent = new Vec3(radial.z, 0, -radial.x); // counter-clockwise seen from above: target on the left
		Vec3 horizontal = tangent.add(radial.scale(Mth.clamp((radius - r) * 0.03, -0.6, 0.6))).normalize();
		Vec3 desired = horizontal.add(0, Mth.clamp((alt - pos.y) * 0.03, -0.2, 0.2), 0).normalize();

		var random = level.getRandom();
		// the open door on the side facing the target, where the door gunner sits
		Vec3 gun = pos.add(radial.scale(-1.6)).add(0, 0.6, 0);
		if (type == JetType.GUNSHIP) {
			// the door gunner works the target over with his autocannon in long bursts
			int t = this.orbitTicks;
			int cycle = t % 50;
			boolean burst = t > 30 && cycle < 26;
			if (burst != this.isFiring()) {
				this.entityData.set(DATA_FIRING, burst);
			}
			if (burst && cycle % 2 == 0) {
				Vec3 aim = this.pickAim(level, 3.5);
				var hit = level.clip(new ClipContext(gun, gun.add(aim.subtract(gun).normalize().scale(200.0)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
				Vec3 p = hit.getLocation();
				this.shells.add(new Shell(this.tickCount + (int) (p.distanceTo(gun) / 30.0) + 1, p, false));
				level.sendParticles(ParticleTypes.SMOKE, gun.x, gun.y, gun.z, 3, 0.1, 0.1, 0.1, 0.02);
				if (cycle % 6 == 0) {
					level.playSound(null, gun.x, gun.y, gun.z, ModRegistry.CHAIN_GUN, SoundSource.HOSTILE, 14.0F, 0.85F + random.nextFloat() * 0.1F);
				}
			}
			if (t > 700) {
				this.entityData.set(DATA_FIRING, false);
				this.egress = true;
			}
		} else {
			if (this.orbitTicks > 60 && this.orbitTicks % 70 == 0 && this.shotsFired < 4) {
				LivingEntity victim = this.pickTargetEntity(level);
				Vec3 aim = victim != null ? victim.position() : this.target;
				Vec3 rail = pos.add(0, -0.6, 0);
				RocketEntity.fire(level, RocketEntity.Kind.HELLFIRE, this, rail, this.getDir().scale(2.5).add(0, -0.3, 0), aim, victim);
				level.playSound(null, rail.x, rail.y, rail.z, ModRegistry.SAM_LAUNCH, SoundSource.HOSTILE, 10.0F, 1.35F);
				if (this.shotsFired == 0) {
					this.tellCaller(level, Component.translatable("message.ballisticmissiles.rifle").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
				}
				this.shotsFired++;
			}
			if (this.shotsFired >= 4 && this.orbitTicks % 70 == 60) {
				this.egress = true;
			}
		}
		return desired;
	}

	/**
	 * AH-64 Apache: comes in low and slow, stops about 110 blocks short of the target and hovers
	 * there nose on, firing Hellfires, Hydra rocket salvos and its 30 mm chain gun; then it turns
	 * round and leaves low.
	 */
	private void apacheTick(ServerLevel level) {
		Vec3 pos = this.position();
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(this.blockPosition()), 2);
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(this.target)), 2);
		Vec3 toTarget = new Vec3(this.target.x - pos.x, 0, this.target.z - pos.z);
		double dist = toTarget.length();
		Vec3 toDir = dist > 1.0E-3 ? toTarget.scale(1.0 / dist) : this.getDir();
		this.a10PhaseAge++;
		Vec3 wantVel;
		Vec3 facing;
		var random = level.getRandom();
		if (this.a10Phase == 0) {
			double sp = Mth.clamp((dist - 110.0) * 0.04, 0.0, JetType.APACHE.attackSpeed);
			wantVel = toDir.scale(sp);
			facing = toDir;
			if (dist < 125.0 && this.hoverVelocity.length() < 0.4) {
				this.a10Phase = 1;
				this.a10PhaseAge = 0;
				this.tellCaller(level, Component.translatable("message.ballisticmissiles.apache_engaging").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			}
		} else if (this.a10Phase == 1) {
			wantVel = Vec3.ZERO;
			facing = toDir;
			int t = this.a10PhaseAge;
			Vec3 nose = pos.add(toDir.scale(3.0)).add(0, -0.6, 0);
			Vec3 side = new Vec3(-toDir.z, 0, toDir.x);
			if (t > 20 && t % 30 == 0 && this.shotsFired < 4) {
				LivingEntity victim = this.pickTargetEntity(level);
				Vec3 rail = pos.add(side.scale(this.shotsFired % 2 == 0 ? 1.6 : -1.6)).add(0, -0.4, 0);
				RocketEntity.fire(level, RocketEntity.Kind.HELLFIRE, this, rail, toDir.scale(1.5).add(0, 0.15, 0), victim != null ? victim.position() : this.target, victim);
				level.playSound(null, rail.x, rail.y, rail.z, ModRegistry.SAM_LAUNCH, SoundSource.HOSTILE, 10.0F, 1.4F);
				this.shotsFired++;
			}
			// two Hydra salvos of eight rockets
			int salvo = t - 45;
			if ((salvo >= 0 && salvo < 16 || salvo >= 80 && salvo < 96) && salvo % 2 == 0) {
				Vec3 pod = pos.add(side.scale(salvo % 4 == 0 ? 1.4 : -1.4)).add(0, -0.5, 0);
				Vec3 aim = this.pickAim(level, 4.0);
				Vec3 v = aim.subtract(pod).normalize().add(random.nextGaussian() * 0.025, 0.02 + random.nextGaussian() * 0.02, random.nextGaussian() * 0.025).normalize().scale(4.5);
				RocketEntity.fire(level, RocketEntity.Kind.HYDRA, this, pod, v, aim, null);
				level.playSound(null, pod.x, pod.y, pod.z, ModRegistry.SAM_LAUNCH, SoundSource.HOSTILE, 6.0F, 1.8F + random.nextFloat() * 0.2F);
			}
			// 30 mm chain gun bursts (625 rounds a minute)
			int gun = t % 50;
			if (t > 30 && gun < 14 && gun % 2 == 0) {
				Vec3 aim = this.pickAim(level, 2.5);
				var hit = level.clip(new ClipContext(nose, nose.add(aim.subtract(nose).normalize().scale(220.0)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this));
				Vec3 p = hit.getLocation();
				this.shells.add(new Shell(this.tickCount + (int) (p.distanceTo(nose) / 25.0), p, false));
				level.sendParticles(ParticleTypes.EXPLOSION, nose.x, nose.y, nose.z, 1, 0.1, 0.1, 0.1, 0.0);
				if (gun % 6 == 0) {
					level.playSound(null, nose.x, nose.y, nose.z, ModRegistry.CHAIN_GUN, SoundSource.HOSTILE, 12.0F, 0.95F + random.nextFloat() * 0.1F);
				}
			}
			if (t > 190) {
				this.a10Phase = 2;
				this.a10PhaseAge = 0;
				this.egressHeading = toDir.scale(-1.0);
			}
		} else {
			if (this.egressHeading == null) {
				this.egressHeading = toDir.scale(-1.0);
			}
			wantVel = this.egressHeading.scale(Math.min(3.2, 0.5 + this.a10PhaseAge * 0.03));
			facing = this.egressHeading;
			if (this.a10PhaseAge > 260) {
				this.discard();
				return;
			}
		}
		// ease the velocity like a helicopter, hold a low altitude above the terrain
		this.hoverVelocity = this.hoverVelocity.add(wantVel.subtract(this.hoverVelocity).scale(0.06));
		double ground = this.target.y;
		for (int k = 0; k <= 40; k += 8) {
			Vec3 probe = pos.add(this.hoverVelocity.normalize().scale(k));
			int px = Mth.floor(probe.x);
			int pz = Mth.floor(probe.z);
			if (level.hasChunk(px >> 4, pz >> 4)) {
				ground = Math.max(ground, level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz));
			}
		}
		double alt = Math.max(this.target.y + JetType.APACHE.runAltitude, ground + 14.0);
		double vy = Mth.clamp((alt - pos.y) * 0.06, -0.5, 0.6);
		Vec3 next = pos.add(this.hoverVelocity).add(0, vy, 0);
		if (!level.isPositionEntityTicking(BlockPos.containing(next))) {
			if (this.a10Phase == 2) {
				this.discard();
			}
			return;
		}
		// nose down when accelerating forward, banked into turns
		Vec3 oldDir = this.getDir();
		Vec3 face = oldDir.add(new Vec3(facing.x, 0, facing.z).normalize().subtract(new Vec3(oldDir.x, 0, oldDir.z)).scale(0.08));
		Vec3 flatFace = new Vec3(face.x, 0, face.z).normalize();
		double pitch = -0.08 * this.hoverVelocity.length();
		Vec3 newDir = flatFace.add(0, pitch, 0).normalize();
		double turn = oldDir.x * newDir.z - oldDir.z * newDir.x;
		this.entityData.set(DATA_BANK, Mth.lerp(0.15F, this.getBank(), (float) Mth.clamp(turn * 12.0, -0.5, 0.5)));
		this.entityData.set(DATA_SPEED, (float) this.hoverVelocity.length());
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
		// hold the required climb and let it go slowly, instead of flicking between climb and descent
		this.guardSlope = Math.max(need > -0.05 ? need + 0.08 : -1.0, this.guardSlope - 0.012);
		if (this.guardSlope <= slope) {
			this.pullUp = Math.max(0.0, this.pullUp - 0.1);
			return desired;
		}
		this.pullUp = Mth.clamp((this.guardSlope - slope) * 2.0, 0.0, 1.0);
		return wanted.add(0, Math.min(this.guardSlope, 1.5), 0).normalize();
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
	 * A-10 close air support pattern, flown like the real thing: it arrives at orbit altitude, rolls in
	 * from about 230 blocks out into a 15-20 degree dive, walks a burst of 30 mm rounds through the
	 * target, pops flares and breaks away in a climbing turn, swings round and comes in for a second
	 * pass from a new angle. After the last pass it leaves.
	 */
	private Vec3 warthogPattern(ServerLevel level, Vec3 pos, Vec3 flat, double alt, Vec3 cruise) {
		this.a10PhaseAge++;
		Vec3 toTarget = new Vec3(this.target.x - pos.x, 0, this.target.z - pos.z);
		double dist = toTarget.length();
		Vec3 toDir = dist > 1.0E-3 ? toTarget.scale(1.0 / dist) : flat;
		if (this.a10Phase == A10_INBOUND) {
			if (dist < ROLL_IN && flat.dot(toDir) > 0.92) {
				this.a10Phase = A10_ATTACK;
				this.a10PhaseAge = 0;
			} else if (dist < 90.0) {
				// came in badly lined up: go round again without wasting the pass
				this.a10Phase = A10_REPOSITION;
				this.a10PhaseAge = 0;
				this.egressHeading = flat;
			}
			return cruise;
		}
		if (this.a10Phase == A10_REPOSITION) {
			// climbing break away, then swing back round towards the target
			if (this.a10PhaseAge > 55 || this.egressHeading == null) {
				this.a10Phase = A10_INBOUND;
				this.a10PhaseAge = 0;
				return cruise;
			}
			return this.egressHeading.add(0, Mth.clamp((alt - pos.y) * 0.03, -0.1, 0.32), 0).normalize();
		}

		// attack: dive at an aim point that walks through the target
		double along = toTarget.dot(flat);
		if (along < GUN_CEASE) {
			this.entityData.set(DATA_FIRING, false);
			this.releaseFlares(level);
			this.a10Passes++;
			double side = this.a10Passes % 2 == 0 ? 1.0 : -1.0;
			this.egressHeading = new Vec3(flat.x * 0.34 - flat.z * 0.94 * side, 0, flat.z * 0.34 + flat.x * 0.94 * side).normalize();
			this.a10PhaseAge = 0;
			if (this.a10Passes >= A10_PASSES) {
				this.egress = true;
			} else {
				this.a10Phase = A10_REPOSITION;
			}
			return this.egressHeading.add(0, 0.3, 0).normalize();
		}
		double f = Mth.clamp((GUN_OPEN - along) / (GUN_OPEN - GUN_CEASE), 0.0, 1.0);
		Vec3 aim = this.target.add(flat.scale(-20.0 + 40.0 * f));
		Vec3 dive = aim.subtract(pos).normalize();
		// only the ground between us and the aim point matters during the dive
		this.guardRange = Math.max(12.0, along - 35.0);
		this.guardClearance = 5.0;
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
			this.releaseFlares(level);
		}
		this.engagements = engagements;
	}

	/** Flare salvo: burning decoys tumbling out behind the tail. */
	private void releaseFlares(ServerLevel level) {
		{
			this.lastFlareTick = this.tickCount;
			Vec3 tail = this.position().add(0, 0.5, 0).subtract(this.getDir().scale(5.0));
			for (int i = 0; i < 12; i++) {
				level.sendParticles(ParticleTypes.FIREWORK, tail.x, tail.y, tail.z, 4, 1.5, 1.0, 1.5, 0.25);
				level.sendParticles(ParticleTypes.FLAME, tail.x, tail.y - i * 0.6, tail.z, 3, 1.2, 0.4, 1.2, 0.05);
			}
			level.playSound(null, tail.x, tail.y, tail.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.HOSTILE, 6.0F, 0.6F);
		}
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
