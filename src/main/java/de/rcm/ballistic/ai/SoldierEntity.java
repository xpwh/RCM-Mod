package de.rcm.ballistic.ai;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.gun.AkItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A rifleman with an AK. He does not know where you are: he has to see you or hear you.
 * <p>
 * <b>Sight.</b> He looks where his head points: sharp in a cone of 60 degrees either side, dimly out
 * to 100 degrees, nothing behind him (unless you are close enough to feel). A wall or a hill hides you.
 * How fast he picks you out depends on distance, light (darkness hides, a muzzle flash gives you away),
 * and on what you do - crouching makes you hard to spot, running easy. Every enemy has an
 * <i>awareness</i> that climbs while he can see them and sinks when he cannot; at 100% he has spotted you.
 * <p>
 * <b>Hearing.</b> Shots, explosions, bullets cracking past, footsteps (running loud, walking quiet,
 * sneaking near silent) reach him through {@link Senses}. A sound makes him turn and go to look -
 * roughly where it came from, the fainter the vaguer - and a shot from an enemy raises his awareness of
 * the shooter, but only sight gives him a target.
 * <p>
 * <b>Fighting.</b> With a target in sight he aims (a moment to react, then bursts of three to five
 * rounds, less accurate far off, on the move or under fire), keeps his distance, sidesteps, crouches to
 * shoot further. Under heavy fire, hurt, or reloading he runs for cover - a spot the enemy cannot see.
 * Lose sight of you and he goes to where he last saw you and searches; give up after a while and he
 * goes back to his patrol.
 */
public class SoldierEntity extends PathfinderMob {
	public static final int TEAM_HOSTILE = 0;
	public static final int TEAM_FRIENDLY = 1;

	public static final int PATROL = 0;
	public static final int INVESTIGATE = 1;
	public static final int COMBAT = 2;
	public static final int SEARCH = 3;
	public static final int COVER = 4;
	public static final int SUPPRESS = 5;

	public static final double VIEW_RANGE = 96.0;
	private static final double COS_SHARP = Math.cos(Math.toRadians(60.0));
	private static final double COS_DIM = Math.cos(Math.toRadians(100.0));
	public static final int MAGAZINE = 30;
	private static final int RELOAD_TICKS = 50;

	/** Whether creative-mode players are ignored (for watching the AI work you can switch it off: {@code /bmai kreativ an}). */
	public static boolean ignoreCreative = true;

	/** Recent muzzle flashes: entity id -> game time. A flash gives away the shooter, even in the dark. */
	private static final Map<Integer, Long> FLASHES = new HashMap<>();

	private static final EntityDataAccessor<Integer> DATA_TEAM = SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(SoldierEntity.class, EntityDataSerializers.INT);

	/** Something heard: where, what, when and how loud (0..1). */
	public record Heard(Vec3 pos, int kind, long time, float loud) {
	}

	// awareness of each enemy, 0..1 (1 = spotted)
	private final Map<Integer, Float> awareness = new HashMap<>();
	private @Nullable LivingEntity target;
	private Vec3 lastKnown = Vec3.ZERO;
	private long lastSeen = -10000L;
	private long acquired;
	private @Nullable Vec3 investigate;
	private long investigateUntil;
	private final List<Heard> heard = new ArrayList<>();
	private int rounds = MAGAZINE;
	private int reload;
	private int burst;
	private int shotTimer;
	private float suppression;
	private @Nullable Vec3 cover;
	private long coverUntil;
	private @Nullable Vec3 home;
	private int patrolTimer;
	private float strafeDir = 1.0F;
	private int strafeTimer;
	private float lookAroundYaw;
	private int lookAroundTimer;
	private boolean crouch;
	private String reason = "";

	// the squad: soldiers of the same side within 32 blocks; the lowest id leads
	private int squadSize = 1;
	private int squadIndex;
	private int leaderId;
	/** Gives covering fire (the leader and every third man) rather than working round the flank. */
	private boolean support = true;
	private float flankSide = 1.0F;
	private @Nullable Vec3 flank;
	private long flankAt;
	// radio: reports sent and received (reporter id, game time)
	private long lastReport = -1000L;
	private final List<long[]> radioIn = new ArrayList<>();
	// grenades: his own, and other people's he has to get away from
	private int grenades = 2;
	private long grenadeReady;
	private @Nullable Vec3 grenadeTarget;
	private long grenadeThrown = -1000L;
	private long fleeUntil;
	private @Nullable Vec3 fleeFrom;
	/** Rounds fired in the current burst: each one climbs a little further off the aim. */
	private int burstShots;

	public SoldierEntity(EntityType<? extends SoldierEntity> type, Level level) {
		super(type, level);
		this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(ModRegistry.AK47));
		this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
		this.setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 20.0)
			.add(Attributes.MOVEMENT_SPEED, 0.28)
			.add(Attributes.FOLLOW_RANGE, VIEW_RANGE)
			.add(Attributes.ARMOR, 4.0);
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_TEAM, TEAM_HOSTILE);
		builder.define(DATA_STATE, PATROL);
	}

	public int team() {
		return this.entityData.get(DATA_TEAM);
	}

	public void setTeam(int team) {
		this.entityData.set(DATA_TEAM, team);
	}

	public int aiState() {
		return this.entityData.get(DATA_STATE);
	}

	private void setState(int state, String why) {
		this.entityData.set(DATA_STATE, state);
		this.reason = why;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	/** Someone fired: their muzzle flash shows them for a moment. */
	public static void flash(LivingEntity shooter) {
		FLASHES.put(shooter.getId(), shooter.level().getGameTime());
		if (FLASHES.size() > 512) {
			FLASHES.clear();
		}
	}

	public static void clearStatic() {
		FLASHES.clear();
	}

	// ------------------------------------------------------------------ who is who

	public boolean isEnemy(LivingEntity e) {
		if (e == this || !e.isAlive() || e.isRemoved()) {
			return false;
		}
		if (e instanceof SoldierEntity s) {
			return s.team() != this.team();
		}
		if (e instanceof Player p) {
			return this.team() == TEAM_HOSTILE && !p.isSpectator() && !(ignoreCreative && p.isCreative());
		}
		return this.team() == TEAM_FRIENDLY && e instanceof Enemy;
	}

	// ------------------------------------------------------------------ the brain

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		long now = level.getGameTime();
		if (this.home == null) {
			this.home = this.position();
		}
		this.suppression = Math.max(0.0F, this.suppression - 0.008F);
		if (this.tickCount % 2 == 0) {
			this.look(level, now);
		}
		if (this.tickCount % 5 == 0) {
			this.listenForFootsteps(level);
		}
		this.heard.removeIf(h -> now - h.time() > 200);
		this.reloadTick(level);
		if (this.shotTimer > 0) {
			this.shotTimer--;
		}
		if (this.tickCount % 20 == 0) {
			this.organiseSquad(level);
		}
		if (this.tickCount % 4 == 0) {
			this.watchForGrenades(level, now);
		}
		this.radioIn.removeIf(r -> now - r[1] > 60);

		LivingEntity t = this.target;
		if (t != null && (!t.isAlive() || t.isRemoved() || !this.isEnemy(t) || t.level() != level || t.distanceTo(this) > VIEW_RANGE * 1.4)) {
			this.awareness.remove(t.getId());
			this.target = null;
			t = null;
		}
		boolean inSight = t != null && now - this.lastSeen < 6;
		this.crouch = false;
		if (now < this.fleeUntil && this.fleeFrom != null) {
			this.flee(level, now);
		} else if (inSight) {
			this.combat(level, t, now);
		} else if (t != null && now - this.lastSeen < 400) {
			this.search(level, now);
		} else if (this.investigate != null && now < this.investigateUntil) {
			this.target = null;
			this.investigate(level, now);
		} else {
			this.target = null;
			this.investigate = null;
			this.patrol(level, now);
		}
		Pose want = this.crouch ? Pose.CROUCHING : Pose.STANDING;
		if (this.getPose() != want) {
			this.setPose(want);
		}
	}

	// ------------------------------------------------------------------ sight

	private Vec3 viewDir() {
		return Vec3.directionFromRotation(this.getXRot(), this.getYHeadRot());
	}

	/** 0 (cannot see it at all) .. about 1 (in plain view close by, in good light). */
	public float visibility(Level level, LivingEntity e) {
		Vec3 eye = this.getEyePosition();
		Vec3 other = e.getEyePosition();
		double d = other.distanceTo(eye);
		if (d > VIEW_RANGE) {
			return 0.0F;
		}
		double cos = other.subtract(eye).normalize().dot(this.viewDir());
		float cone = cos >= COS_SHARP ? 1.0F : cos >= COS_DIM ? 0.25F : d < 2.5 ? 0.6F : 0.0F;
		if (cone <= 0.0F) {
			return 0.0F;
		}
		if (!this.lineOfSight(level, eye, other, e) && !this.lineOfSight(level, eye, e.getBoundingBox().getCenter(), e)) {
			return 0.0F;
		}
		float light = level.getMaxLocalRawBrightness(e.blockPosition()) / 15.0F;
		Long flash = FLASHES.get(e.getId());
		float lit = Math.max(0.15F + 0.85F * light, flash != null && level.getGameTime() - flash < 30 ? 1.0F : 0.0F);
		double moved = Mth.square(e.getX() - e.xo) + Mth.square(e.getZ() - e.zo);
		float stance = e.isCrouching() ? 0.45F : e.isSprinting() ? 1.3F : 1.0F;
		float motion = moved > 0.003 ? 1.15F : 0.8F;
		float near = (float) Mth.clamp(1.0 - d / VIEW_RANGE, 0.0, 1.0);
		return cone * lit * stance * motion * (0.12F + 0.88F * near * near);
	}

	private boolean lineOfSight(Level level, Vec3 from, Vec3 to, LivingEntity e) {
		HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, this));
		return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) < 0.25;
	}

	private void look(ServerLevel level, long now) {
		List<LivingEntity> seen = new ArrayList<>();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(VIEW_RANGE), this::isEnemy)) {
			float vis = this.visibility(level, e);
			float a = this.awareness.getOrDefault(e.getId(), 0.0F);
			if (vis > 0.0F) {
				a = Math.min(1.0F, a + vis * 0.12F);
				if (a >= 1.0F) {
					seen.add(e);
				}
			} else {
				a -= 0.01F;
			}
			if (a <= 0.0F) {
				this.awareness.remove(e.getId());
			} else {
				this.awareness.put(e.getId(), a);
			}
		}
		// forget enemies that are gone from the world or far away
		this.awareness.keySet().removeIf(id -> {
			var e = level.getEntity(id);
			return e == null || e.distanceToSqr(this) > VIEW_RANGE * VIEW_RANGE * 2.0;
		});
		if (seen.isEmpty()) {
			return;
		}
		// keep the one we are fighting while we still see him; otherwise the nearest
		LivingEntity pick = this.target != null && seen.contains(this.target) ? this.target : null;
		if (pick == null) {
			double best = Double.MAX_VALUE;
			for (LivingEntity e : seen) {
				double d = e.distanceToSqr(this);
				if (d < best) {
					best = d;
					pick = e;
				}
			}
		}
		if (pick != this.target || now - this.lastSeen > 40) {
			this.acquired = now;
			if (pick instanceof Player) {
				level.playSound(null, this.getX(), this.getEyeY(), this.getZ(), ModRegistry.RADIO_SQUELCH, SoundSource.HOSTILE, 0.8F, 1.0F);
			}
		}
		this.target = pick;
		this.lastSeen = now;
		this.lastKnown = pick.position();
		if (now - this.lastReport > 40) {
			this.report(level, pick, now);
		}
	}

	// ------------------------------------------------------------------ hearing

	/** Called by {@link Senses}: a noise reached him. */
	public void hear(Vec3 at, int kind, float loud, net.minecraft.world.entity.@Nullable Entity source) {
		long now = this.level().getGameTime();
		this.heard.add(new Heard(at, kind, now, loud));
		while (this.heard.size() > 6) {
			this.heard.remove(0);
		}
		if (source instanceof LivingEntity le && this.isEnemy(le)) {
			// a shot gives the shooter away a good deal - but only sight makes him a target
			float gain = kind == Senses.GUNSHOT ? 0.15F + 0.6F * loud : kind == Senses.FOOTSTEP ? 0.05F + 0.25F * loud : kind == Senses.BULLET_PASS ? 0.4F : 0.1F;
			this.awareness.merge(le.getId(), gain, (a, b) -> Math.min(0.95F, a + b));
		}
		if (kind == Senses.BULLET_PASS) {
			this.suppression = Math.min(1.0F, this.suppression + 0.35F);
		} else if (kind == Senses.EXPLOSION) {
			this.suppression = Math.min(1.0F, this.suppression + 0.6F * loud * loud);
		}
		boolean busy = this.target != null && now - this.lastSeen < 6;
		if (!busy && (kind != Senses.FOOTSTEP || source == null || !(source instanceof SoldierEntity s) || s.team() != this.team())) {
			// go and look - roughly where it came from: the fainter, the vaguer
			double err = (1.0 - loud) * 8.0;
			var r = this.getRandom();
			Vec3 guess = at.add((r.nextDouble() - 0.5) * err, 0.0, (r.nextDouble() - 0.5) * err);
			if (this.target != null && kind != Senses.BULLET_PASS) {
				return; // searching for someone already: a noise elsewhere does not distract him
			}
			this.investigate = guess;
			this.investigateUntil = now + 300;
			this.getLookControl().setLookAt(at.x, at.y, at.z, 60.0F, 40.0F);
			this.lookAroundTimer = 0;
		}
	}

	/** A round cracked past his head (from the server's bullet). */
	public void bulletPassed(Vec3 at, net.minecraft.world.entity.@Nullable Entity shooter) {
		this.hear(shooter != null ? shooter.position() : at, Senses.BULLET_PASS, 1.0F, shooter);
	}

	private void listenForFootsteps(ServerLevel level) {
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(22.0), this::isEnemy)) {
			double moved = Mth.square(e.getX() - e.xo) + Mth.square(e.getZ() - e.zo);
			if (moved < 0.0025 || !e.onGround()) {
				continue;
			}
			double range = e.isCrouching() ? 2.5 : e.isSprinting() ? 20.0 : 9.0;
			double d = e.distanceTo(this);
			if (d < range) {
				this.hear(e.position(), Senses.FOOTSTEP, (float) (1.0 - d / range), e);
			}
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		boolean hurt = super.hurtServer(level, source, amount);
		if (hurt && source.getEntity() instanceof LivingEntity attacker && this.isEnemy(attacker)) {
			// hit: he knows roughly where it came from, and wants out of it
			this.awareness.put(attacker.getId(), 1.0F);
			this.lastKnown = attacker.position();
			if (this.target == null) {
				this.target = attacker;
				this.lastSeen = level.getGameTime() - 10; // known, not necessarily seen: search unless in sight
			}
			this.suppression = Math.min(1.0F, this.suppression + 0.5F);
		}
		return hurt;
	}

	// ------------------------------------------------------------------ behaviour

	private void combat(ServerLevel level, LivingEntity t, long now) {
		double d = this.distanceTo(t);
		this.getLookControl().setLookAt(t, 50.0F, 50.0F);
		boolean wantsCover = this.reload > 0 || this.suppression > 0.6F || this.getHealth() < this.getMaxHealth() * 0.4F;
		if (wantsCover && (this.cover == null || now > this.coverUntil)) {
			this.cover = this.findCover(level, t.getEyePosition());
			this.coverUntil = now + 80;
		}
		boolean firing = true;
		if (wantsCover && this.cover != null) {
			this.setState(COVER, this.reload > 0 ? "lädt nach" : this.suppression > 0.6F ? "unter Beschuss" : "verwundet");
			if (this.position().distanceToSqr(this.cover) > 1.0) {
				this.getNavigation().moveTo(this.cover.x, this.cover.y, this.cover.z, 1.25);
			} else {
				this.getNavigation().stop();
				this.crouch = true;
			}
		} else if (this.squadSize > 1 && !this.support && d > 10.0 && this.movePhase(now)) {
			// fire and movement: while the others keep his head down, this one works round the side
			if (this.flank == null || now - this.flankAt > 40) {
				this.flank = this.flankPoint(t.position());
				this.flankAt = now;
			}
			this.setState(COMBAT, this.flankSide > 0 ? "flankiert rechts" : "flankiert links");
			if (this.position().distanceToSqr(this.flank) > 9.0) {
				this.getNavigation().moveTo(this.flank.x, this.flank.y, this.flank.z, 1.2);
				firing = d < 14.0; // on the move he only fires when it is close
			} else {
				this.getNavigation().stop();
			}
		} else {
			this.setState(COMBAT, this.squadSize > 1 && this.support ? "gibt Feuerschutz" : "Ziel in Sicht");
			if (d > 45.0) {
				this.getNavigation().moveTo(t, 1.0);
			} else if (d < 7.0) {
				Vec3 away = this.position().subtract(t.position()).normalize().scale(6.0);
				this.getNavigation().moveTo(this.getX() + away.x, this.getY(), this.getZ() + away.z, 1.1);
			} else {
				this.getNavigation().stop();
				if (--this.strafeTimer <= 0) {
					this.strafeDir = this.getRandom().nextBoolean() ? 1.0F : -1.0F;
					this.strafeTimer = 30 + this.getRandom().nextInt(40);
				}
				if (d > 25.0) {
					this.crouch = true; // steadier shooting far off
				} else {
					this.getMoveControl().strafe(0.0F, this.strafeDir * 0.4F);
				}
			}
		}
		if (this.reload == 0 && this.rounds <= 0) {
			this.startReload(level);
			return;
		}
		// fire: after a moment to react, in bursts, only with a clear line and nobody friendly in the way
		Vec3 aim = t.getBoundingBox().getCenter().add(0.0, 0.15, 0.0);
		if (!firing || this.reload > 0 || this.shotTimer > 0 || now - this.acquired < 12 || !this.clearShot(level, aim)) {
			return;
		}
		Vec3 vel = new Vec3(t.getX() - t.xo, 0.0, t.getZ() - t.zo);
		double targetSpeed = vel.length();
		this.fireBurst(level, aim.add(vel.scale(d / AkItem.MUZZLE_VELOCITY)), d, Math.min(0.025, targetSpeed * 0.1), false);
	}

	/** Bounding: flankers move in one phase and shoot in the next, staggered across the squad. */
	private boolean movePhase(long now) {
		return ((now / 70L) + this.squadIndex) % 2L == 0L;
	}

	/** A point off to his side of the enemy, about 16 blocks out, from where he can fire into the flank. */
	private Vec3 flankPoint(Vec3 enemy) {
		Vec3 v = this.position().subtract(enemy);
		v = new Vec3(v.x, 0.0, v.z);
		v = v.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : v.normalize();
		Vec3 perp = new Vec3(-v.z, 0.0, v.x).scale(this.flankSide);
		Vec3 dir = v.scale(0.5).add(perp).normalize();
		return enemy.add(dir.scale(16.0));
	}

	private void fireBurst(ServerLevel level, Vec3 aim, double d, double extraSpread, boolean suppressive) {
		if (this.burst <= 0) {
			this.burst = suppressive ? 2 + this.getRandom().nextInt(3) : 3 + this.getRandom().nextInt(3);
			this.burstShots = 0;
		}
		this.shoot(level, aim, d, extraSpread);
		this.burst--;
		this.burstShots++;
		this.shotTimer = this.burst > 0 ? AkItem.CYCLE : suppressive ? 18 + this.getRandom().nextInt(22) : 14 + this.getRandom().nextInt(18);
	}

	private void search(ServerLevel level, long now) {
		long lost = now - this.lastSeen;
		double d = this.position().distanceTo(this.lastKnown);
		Vec3 spot = this.lastKnown.add(0.0, 1.0, 0.0);
		// gone to ground behind something: a grenade over it
		if (this.grenades > 0 && now >= this.grenadeReady && lost > 30 && lost < 300 && d > 6.0 && d < 28.0 && !this.sees(level, spot)
			&& this.noFriendNear(level, this.lastKnown, 7.0) && this.reload == 0) {
			this.throwGrenade(level, this.lastKnown, now);
			return;
		}
		// keep his head down: fire at where he was (the support men, or a man on his own)
		if (lost < 120 && this.rounds > 4 && this.reload == 0 && (this.squadSize == 1 || this.support)) {
			Vec3 at = this.suppressPoint(level, spot);
			if (at != null) {
				this.setState(SUPPRESS, "Unterdrückungsfeuer");
				this.getNavigation().stop();
				this.crouch = true;
				this.getLookControl().setLookAt(at.x, at.y, at.z, 40.0F, 40.0F);
				if (this.shotTimer <= 0 && now - this.acquired > 12 && this.clearShot(level, at)) {
					this.fireBurst(level, at, d, 0.035, true);
				}
				return;
			}
		}
		if (this.reload == 0 && this.rounds <= 0) {
			this.startReload(level);
		}
		this.setState(SEARCH, this.squadSize > 1 && !this.support ? "rückt von der Seite vor" : "sucht letzte Position");
		// the flankers close in from the side, the others go straight for it
		Vec3 goal = this.squadSize > 1 && !this.support && d > 10.0 ? this.flankPoint(this.lastKnown) : this.lastKnown;
		if (this.position().distanceToSqr(goal) > 4.0) {
			this.getNavigation().moveTo(goal.x, goal.y, goal.z, 0.95);
			this.getLookControl().setLookAt(spot.x, spot.y + 0.5, spot.z, 30.0F, 30.0F);
		} else {
			this.getNavigation().stop();
			this.lookAround();
		}
	}

	private boolean sees(ServerLevel level, Vec3 spot) {
		HitResult hit = level.clip(new ClipContext(this.getEyePosition(), spot, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(spot) < 1.0;
	}

	/** Where to fire to keep someone at {@code spot} down: the spot, or the cover in front of it; null if his own view is blocked well short. */
	private @Nullable Vec3 suppressPoint(ServerLevel level, Vec3 spot) {
		HitResult hit = level.clip(new ClipContext(this.getEyePosition(), spot, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		if (hit.getType() == HitResult.Type.MISS) {
			return spot;
		}
		return hit.getLocation().distanceTo(spot) < 4.0 ? hit.getLocation() : null;
	}

	private boolean noFriendNear(ServerLevel level, Vec3 at, double r) {
		return level.getEntitiesOfClass(SoldierEntity.class, new AABB(at, at).inflate(r), s -> s.team() == this.team()).isEmpty();
	}

	/** Lobs a grenade at {@code at}: the arc worked out for its gravity and drag, a second cooked off. */
	private void throwGrenade(ServerLevel level, Vec3 at, long now) {
		var r = this.getRandom();
		Vec3 to = at.add((r.nextDouble() - 0.5) * 3.0, 0.0, (r.nextDouble() - 0.5) * 3.0);
		Vec3 from = this.getEyePosition().add(this.viewDir().scale(0.4));
		double dx = to.x - from.x;
		double dz = to.z - from.z;
		double dy = to.y + 0.2 - from.y;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		int ticks = Mth.clamp((int) (horiz * 1.2) + 12, 16, 45);
		// positions are linear in the initial velocity: sum of the drag factors, and the drop gravity alone causes
		double sum = 0.0;
		double drop = 0.0;
		double vyg = 0.0;
		double k = 1.0;
		for (int i = 0; i < ticks; i++) {
			sum += k;
			vyg = (vyg - 0.04);
			drop += vyg;
			vyg *= 0.99;
			k *= 0.99;
		}
		Vec3 vel = new Vec3(dx / sum, (dy - drop) / sum, dz / sum);
		this.getLookControl().setLookAt(to.x, to.y, to.z, 60.0F, 60.0F);
		this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		de.rcm.ballistic.gun.GrenadeEntity.throwFrom(level, this, from, vel, de.rcm.ballistic.gun.GrenadeItem.FUSE - 20);
		level.playSound(null, from.x, from.y, from.z, ModRegistry.GRENADE_PIN, SoundSource.HOSTILE, 0.8F, 1.0F);
		level.playSound(null, from.x, from.y, from.z, ModRegistry.GRENADE_SPOON, SoundSource.HOSTILE, 0.7F, 1.0F);
		this.grenades--;
		this.grenadeReady = now + 160;
		this.grenadeTarget = to;
		this.grenadeThrown = now;
		this.setState(COMBAT, "wirft Granate");
	}

	/** A live grenade near him: get away from it. */
	private void watchForGrenades(ServerLevel level, long now) {
		var near = level.getEntitiesOfClass(de.rcm.ballistic.gun.GrenadeEntity.class, this.getBoundingBox().inflate(7.0));
		if (near.isEmpty()) {
			return;
		}
		var g = near.get(0);
		if (now >= this.fleeUntil || this.fleeFrom == null || this.fleeFrom.distanceToSqr(g.position()) > 4.0) {
			this.fleeFrom = g.position();
			this.fleeUntil = now + 50;
			this.getNavigation().stop();
		}
	}

	private void flee(ServerLevel level, long now) {
		this.setState(COVER, "flieht vor Granate");
		Vec3 away = this.position().subtract(this.fleeFrom);
		away = new Vec3(away.x, 0.0, away.z);
		if (away.lengthSqr() < 1.0E-3) {
			away = new Vec3(this.getRandom().nextDouble() - 0.5, 0.0, this.getRandom().nextDouble() - 0.5);
		}
		Vec3 dest = this.position().add(away.normalize().scale(10.0));
		if (this.tickCount % 10 == 0 || this.getNavigation().isDone()) {
			this.getNavigation().moveTo(dest.x, dest.y, dest.z, 1.45);
		}
	}

	// ------------------------------------------------------------------ squad and radio

	private void organiseSquad(ServerLevel level) {
		List<SoldierEntity> mates = level.getEntitiesOfClass(SoldierEntity.class, this.getBoundingBox().inflate(32.0),
			s -> s.team() == this.team() && s.isAlive());
		mates.sort(java.util.Comparator.comparingInt(net.minecraft.world.entity.Entity::getId));
		this.squadSize = Math.max(1, mates.size());
		this.squadIndex = Math.max(0, mates.indexOf(this));
		this.leaderId = mates.isEmpty() ? this.getId() : mates.get(0).getId();
		this.support = this.squadSize == 1 || this.squadIndex == 0 || this.squadIndex % 3 == 0;
		this.flankSide = this.squadIndex % 2 == 1 ? 1.0F : -1.0F;
	}

	/** On the radio: "contact, there" - to everyone on his side within 64 blocks. */
	private void report(ServerLevel level, LivingEntity enemy, long now) {
		this.lastReport = now;
		boolean heard = false;
		for (SoldierEntity m : level.getEntitiesOfClass(SoldierEntity.class, this.getBoundingBox().inflate(64.0),
			s -> s != this && s.team() == this.team() && s.isAlive())) {
			m.radioReport(this, enemy, now);
			heard = true;
		}
		if (heard) {
			level.playSound(null, this.getX(), this.getEyeY(), this.getZ(), ModRegistry.RADIO_CLICK, SoundSource.HOSTILE, 0.5F, 1.0F);
		}
	}

	/** A comrade's report: he now knows roughly where the enemy is, without seeing him. */
	void radioReport(SoldierEntity reporter, LivingEntity enemy, long now) {
		this.radioIn.add(new long[] {reporter.getId(), now});
		while (this.radioIn.size() > 4) {
			this.radioIn.remove(0);
		}
		if (this.target == enemy && now - this.lastSeen < 6) {
			return; // sees him himself
		}
		var r = this.getRandom();
		if (this.target != enemy) {
			this.acquired = now;
		}
		this.target = enemy;
		this.lastKnown = enemy.position().add((r.nextDouble() - 0.5) * 5.0, 0.0, (r.nextDouble() - 0.5) * 5.0);
		if (now - this.lastSeen > 7) {
			this.lastSeen = now - 7;
		}
		this.awareness.merge(enemy.getId(), 0.6F, (a, b) -> Math.min(0.95F, Math.max(a, b)));
		this.level().playSound(null, this.getX(), this.getEyeY(), this.getZ(), ModRegistry.RADIO_SQUELCH, SoundSource.HOSTILE, 0.45F, 1.05F);
	}

	private void investigate(ServerLevel level, long now) {
		this.setState(INVESTIGATE, "prüft Geräusch");
		Vec3 p = this.investigate;
		if (this.position().distanceToSqr(p) > 4.0) {
			this.getNavigation().moveTo(p.x, p.y, p.z, 0.8);
			if (this.tickCount % 20 == 0) {
				this.getLookControl().setLookAt(p.x, p.y + 1.0, p.z, 20.0F, 20.0F);
			}
		} else {
			this.getNavigation().stop();
			this.lookAround();
		}
	}

	private void patrol(ServerLevel level, long now) {
		this.setState(PATROL, "Patrouille");
		if (this.reload == 0 && this.rounds < MAGAZINE / 2) {
			this.startReload(level); // top up while it is quiet
		}
		if (--this.patrolTimer <= 0 || this.getNavigation().isDone() && this.getRandom().nextInt(80) == 0) {
			this.patrolTimer = 120 + this.getRandom().nextInt(160);
			Vec3 h = this.home;
			double a = this.getRandom().nextDouble() * Math.PI * 2.0;
			double r = 3.0 + this.getRandom().nextDouble() * 9.0;
			this.getNavigation().moveTo(h.x + Math.cos(a) * r, h.y, h.z + Math.sin(a) * r, 0.55);
		}
		if (this.getNavigation().isDone()) {
			this.lookAround();
		}
	}

	/** Standing still: scan slowly left and right. */
	private void lookAround() {
		if (--this.lookAroundTimer <= 0) {
			this.lookAroundTimer = 25 + this.getRandom().nextInt(30);
			this.lookAroundYaw = this.getYHeadRot() + (this.getRandom().nextFloat() - 0.5F) * 160.0F;
		}
		Vec3 dir = Vec3.directionFromRotation(0.0F, this.lookAroundYaw);
		Vec3 eye = this.getEyePosition();
		this.getLookControl().setLookAt(eye.x + dir.x * 8.0, eye.y, eye.z + dir.z * 8.0, 10.0F, 10.0F);
	}

	/** A spot near him that {@code threat} cannot see: nearest first. */
	private @Nullable Vec3 findCover(ServerLevel level, Vec3 threat) {
		Vec3 best = null;
		double bestD = Double.MAX_VALUE;
		BlockPos base = this.blockPosition();
		for (int i = 0; i < 32; i++) {
			double a = i * (Math.PI * 2.0 / 16.0);
			double r = 2.0 + (i / 16) * 4.0 + this.getRandom().nextDouble() * 3.0;
			BlockPos p = base.offset((int) Math.round(Math.cos(a) * r), 0, (int) Math.round(Math.sin(a) * r));
			BlockPos stand = null;
			for (int dy = 2; dy >= -3; dy--) {
				BlockPos q = p.above(dy);
				if (level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()
					&& !level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty()) {
					stand = q;
					break;
				}
			}
			if (stand == null) {
				continue;
			}
			Vec3 head = Vec3.atBottomCenterOf(stand).add(0.0, 1.0, 0.0); // crouched head height
			HitResult hit = level.clip(new ClipContext(threat, head, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
			if (hit.getType() == HitResult.Type.MISS) {
				continue; // he would still be seen there
			}
			double d = Vec3.atBottomCenterOf(stand).distanceToSqr(this.position());
			if (d < bestD) {
				bestD = d;
				best = Vec3.atBottomCenterOf(stand);
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ the rifle

	private boolean clearShot(ServerLevel level, Vec3 aim) {
		Vec3 eye = this.getEyePosition();
		HitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceToSqr(aim) > 1.0) {
			return false;
		}
		// nobody on our side in the line of fire
		Vec3 line = aim.subtract(eye);
		double len2 = line.lengthSqr();
		for (SoldierEntity s : level.getEntitiesOfClass(SoldierEntity.class, new AABB(eye, aim).inflate(1.5), s -> s != this && s.team() == this.team())) {
			Vec3 c = s.getBoundingBox().getCenter();
			double f = Mth.clamp(c.subtract(eye).dot(line) / len2, 0.0, 1.0);
			if (eye.add(line.scale(f)).distanceToSqr(c) < 1.2) {
				return false;
			}
		}
		return true;
	}

	private void shoot(ServerLevel level, Vec3 aimPoint, double d, double extraSpread) {
		Vec3 eye = this.getEyePosition();
		Vec3 look = this.viewDir();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 muzzle = eye.add(look.scale(0.8)).add(right.scale(0.12)).add(0, -0.12, 0);
		Vec3 dir = aimPoint.subtract(eye).normalize();
		// a man with a rifle in a fight is no marksman: distance, his own movement, being shot at,
		// the climb of the burst and the first moments on a new target all throw the round off
		double moving = Math.sqrt(Mth.square(this.getX() - this.xo) + Mth.square(this.getZ() - this.zo));
		double settle = 6 - Math.min(6, (int) ((level.getGameTime() - this.acquired) / 10));
		double spread = 0.016 + d * 0.0005 + this.suppression * 0.05 + Math.min(0.04, moving * 0.15) + this.burstShots * 0.005 + settle * 0.004
			+ extraSpread + (this.crouch ? -0.003 : 0.0);
		var r = this.getRandom();
		dir = dir.add(r.nextGaussian() * spread, r.nextGaussian() * spread, r.nextGaussian() * spread).normalize();
		this.rounds--;
		// soldiers load like players do: every fourth a tracer, the last three tracers
		boolean tracer = this.rounds % 4 == 0 || this.rounds < 3;
		AkItem.shoot(level, this, eye, muzzle, dir, tracer, this.rounds == 0);
		// the rifle in his hands kicks, flashes and cycles its bolt (the model reads the shot from the stack)
		ItemStack rifle = this.getMainHandItem();
		if (rifle.getItem() instanceof AkItem) {
			AkItem.setState(rifle, AkItem.state(rifle).fired(this.rounds, level.getGameTime()));
		}
	}

	private void startReload(ServerLevel level) {
		this.reload = RELOAD_TICKS;
		this.burst = 0;
		level.playSound(null, this.getX(), this.getEyeY(), this.getZ(), ModRegistry.AK_MAG_OUT, SoundSource.HOSTILE, 0.8F, 1.0F);
	}

	private void reloadTick(ServerLevel level) {
		if (this.reload <= 0) {
			return;
		}
		this.reload--;
		if (this.reload == 22) {
			level.playSound(null, this.getX(), this.getEyeY(), this.getZ(), ModRegistry.AK_MAG_IN, SoundSource.HOSTILE, 0.8F, 1.0F);
		} else if (this.reload == 8) {
			level.playSound(null, this.getX(), this.getEyeY(), this.getZ(), ModRegistry.AK_CHARGE, SoundSource.HOSTILE, 0.8F, 1.0F);
		} else if (this.reload == 0) {
			this.rounds = MAGAZINE;
		}
	}

	// ------------------------------------------------------------------ debug view

	/** What the debug view shows of him (see {@link SoldierDebug}). */
	SoldierDebug.Entry debugEntry(ServerLevel level) {
		long now = level.getGameTime();
		float best = 0.0F;
		for (float a : this.awareness.values()) {
			best = Math.max(best, a);
		}
		List<double[]> noises = new ArrayList<>();
		for (Heard h : this.heard) {
			noises.add(new double[] {h.pos().x, h.pos().y, h.pos().z, h.kind(), now - h.time(), h.loud()});
		}
		List<double[]> path = new ArrayList<>();
		var p = this.getNavigation().getPath();
		if (p != null) {
			for (int i = p.getNextNodeIndex(); i < p.getNodeCount() && path.size() < 12; i++) {
				var n = p.getNode(i);
				path.add(new double[] {n.x + 0.5, n.y + 0.1, n.z + 0.5});
			}
		}
		String targetName = this.target == null ? "" : this.target.getName().getString();
		double targetDist = this.target == null ? 0.0 : this.distanceTo(this.target);
		String role = this.squadSize <= 1 ? "allein" : (this.getId() == this.leaderId ? "Truppführer" : this.support ? "Feuerschutz" : "Flanke")
			+ " " + (this.squadIndex + 1) + "/" + this.squadSize;
		List<double[]> radio = new ArrayList<>();
		for (long[] r : this.radioIn) {
			radio.add(new double[] {r[0], now - r[1]});
		}
		return new SoldierDebug.Entry(this.getId(), this.aiState(), this.team(), best, this.reason, role, targetName, (float) targetDist,
			this.target == null ? -1 : this.target.getId(), this.target != null && now - this.lastSeen < 6,
			this.target == null ? null : this.lastKnown, this.investigate != null && now < this.investigateUntil ? this.investigate : null,
			this.cover, this.flank, now - this.grenadeThrown < 80 ? this.grenadeTarget : null, this.grenades, this.rounds, this.reload > 0, this.suppression,
			this.getYHeadRot(), noises, path, radio);
	}

	// ------------------------------------------------------------------ saving

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("Team", this.team());
		output.putInt("Rounds", this.rounds);
		if (this.home != null) {
			output.putDouble("HomeX", this.home.x);
			output.putDouble("HomeY", this.home.y);
			output.putDouble("HomeZ", this.home.z);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.setTeam(input.getIntOr("Team", TEAM_HOSTILE));
		this.rounds = input.getIntOr("Rounds", MAGAZINE);
		double hx = input.getDoubleOr("HomeX", Double.NaN);
		if (!Double.isNaN(hx)) {
			this.home = new Vec3(hx, input.getDoubleOr("HomeY", 0.0), input.getDoubleOr("HomeZ", 0.0));
		}
	}

	/** For the AI tool: a full report on what he knows. */
	public List<String> report(ServerLevel level) {
		long now = level.getGameTime();
		List<String> out = new ArrayList<>();
		String[] states = {"PATROUILLE", "UNTERSUCHT", "KAMPF", "SUCHE", "DECKUNG", "UNTERDRÜCKT"};
		out.add("§6Soldat #" + this.getId() + " §7(" + (this.team() == TEAM_HOSTILE ? "§cfeindlich" : "§afreundlich") + "§7) – §f"
			+ states[Mth.clamp(this.aiState(), 0, 5)] + " §7(" + this.reason + ")");
		out.add("§7Trupp: §f" + (this.squadSize <= 1 ? "allein" : (this.squadIndex + 1) + " von " + this.squadSize + ", "
			+ (this.getId() == this.leaderId ? "Truppführer" : this.support ? "Feuerschutz" : "Flanke " + (this.flankSide > 0 ? "rechts" : "links")))
			+ " §7· Granaten §f" + this.grenades);
		out.add("§7Munition §f" + this.rounds + "/" + MAGAZINE + (this.reload > 0 ? " §e(lädt)" : "") + " §7· Unterdrückung §f" + Math.round(this.suppression * 100)
			+ "% §7· Leben §f" + Math.round(this.getHealth()) + "/" + Math.round(this.getMaxHealth()));
		if (this.target != null) {
			out.add("§7Ziel §c" + this.target.getName().getString() + " §7" + Math.round(this.distanceTo(this.target)) + " m, "
				+ (now - this.lastSeen < 6 ? "§asichtbar" : "§ezuletzt gesehen vor " + (now - this.lastSeen) / 20 + " s"));
		}
		for (Iterator<Map.Entry<Integer, Float>> it = this.awareness.entrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			var ent = level.getEntity(e.getKey());
			if (ent instanceof LivingEntity le) {
				out.add("§7 Erkennung §f" + le.getName().getString() + "§7: " + Math.round(e.getValue() * 100) + "% · Sichtbarkeit jetzt "
					+ Math.round(this.visibility(level, le) * 100) + "%");
			}
		}
		for (Heard h : this.heard) {
			out.add("§7 Gehört: §e" + Senses.name(h.kind()) + " §7" + Math.round(h.pos().distanceTo(this.position())) + " m, Lautstärke "
				+ Math.round(h.loud() * 100) + "%, vor " + (now - h.time()) / 20 + " s");
		}
		return out;
	}
}
