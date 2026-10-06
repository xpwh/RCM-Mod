package de.rcm.ballistic.entity;

import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.explosion.DetonationManager;
import net.minecraft.core.particles.ParticleTypes;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
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

	public JetEntity(EntityType<? extends JetEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	/**
	 * Spawns a jet behind {@code caller} that will bomb {@code target}. Returns null if the target is
	 * out of range.
	 */
	public static @Nullable JetEntity callIn(ServerLevel level, ServerPlayer caller, Vec3 target) {
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
		double altitude = Math.max(target.y, from.y) + RUN_ALTITUDE;
		Vec3 start = new Vec3(from.x, altitude, from.z).subtract(heading.scale(SPAWN_BEHIND));
		jet.setPos(start);
		jet.target = target;
		jet.caller = caller.getUUID();
		jet.setDir(heading);
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(BlockPos.containing(start)), 2);
		level.addFreshEntity(jet);
		return jet;
	}

	/** Seconds until the bombs hit, roughly: flight to the release point plus the fall. */
	public int etaSeconds() {
		double run = Math.hypot(this.target.x - this.getX(), this.target.z - this.getZ());
		return (int) Math.ceil((run / ATTACK_SPEED + 60) / 20.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_DIR, new Vector3f(1, 0, 0));
		builder.define(DATA_BANK, 0.0F);
		builder.define(DATA_BOMBS, BOMBS);
		builder.define(DATA_SPEED, (float) ATTACK_SPEED);
		builder.define(DATA_BAY, false);
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

	/** Afterburners light once the stick is gone and the jet climbs out. */
	public boolean isAfterburner() {
		return this.getBombsLeft() <= 0;
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
			double alt = this.target.y + RUN_ALTITUDE;
			for (int k = 0; k <= 96; k += 16) {
				Vec3 probe = pos.add(flat.scale(k));
				int px = Mth.floor(probe.x);
				int pz = Mth.floor(probe.z);
				if (level.hasChunk(px >> 4, pz >> 4)) {
					alt = Math.max(alt, level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz) + CLEARANCE);
				}
			}
			Vec3 toTarget = new Vec3(this.target.x - pos.x, 0, this.target.z - pos.z);
			Vec3 heading = toTarget.lengthSqr() > 900.0 && this.bombsLeft == BOMBS ? toTarget.normalize() : flat;
			desired = heading.add(0, Mth.clamp((alt - pos.y) * 0.03, -0.25, 0.25), 0).normalize();
			this.bombRun(level, pos, flat, speed);
		} else {
			// afterburner climb-out, accelerating through the sound barrier
			this.egressAge++;
			speed = Math.min(DASH_SPEED, speed + ACCELERATION);
			desired = flat.add(0, 0.38, 0).normalize();
			if (this.egressAge > 160) {
				this.discard();
				return;
			}
		}

		Vec3 newDir = dir.add(desired.subtract(dir).scale(this.egress ? 0.05 : 0.08)).normalize();
		Vec3 next = pos.add(newDir.scale(speed));
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

	private void bombRun(ServerLevel level, Vec3 pos, Vec3 flat, double speed) {
		double fall = Math.max(1.0, pos.y - this.target.y);
		double throwDistance = forwardThrow(speed, fall);
		// distance of the target ahead of us along the track
		double along = (this.target.x - pos.x) * flat.x + (this.target.z - pos.z) * flat.z;
		double halfStick = (BOMBS - 1) * RELEASE_INTERVAL * speed * 0.5;
		if (this.bombsLeft == BOMBS && along > throwDistance + halfStick) {
			if (!this.isBayOpen() && along < throwDistance + halfStick + BAY_LEAD) {
				this.entityData.set(DATA_BAY, true);
			}
			return;
		}
		if (this.bombsLeft == BOMBS) {
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
			bomb.setPos(release);
			bomb.setDeltaMovement(flat.scale(speed));
			level.addFreshEntity(bomb);
			if (this.bombsLeft % 3 == 0) {
				level.playSound(null, release.x, release.y, release.z, ModRegistry.BOMB_WHISTLE, SoundSource.HOSTILE, 8.0F, 0.9F + level.getRandom().nextFloat() * 0.2F);
			}
		}
		this.entityData.set(DATA_BOMBS, --this.bombsLeft);
		if (this.bombsLeft <= 0) {
			this.egress = true;
			this.entityData.set(DATA_BAY, false);
		}
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

	/** Stealth airframe: smaller on radar than a cruise missile. */
	@Override
	public double radarCrossSection() {
		return 0.05;
	}

	/** Flares and hard manoeuvring: a fresh flare salvo decoys most missiles. */
	@Override
	public float killProbability() {
		return this.tickCount - this.lastFlareTick < 50 ? 0.2F : 0.6F;
	}

	@Override
	public String nameKey() {
		return "entity.ballisticmissiles.strike_jet";
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
