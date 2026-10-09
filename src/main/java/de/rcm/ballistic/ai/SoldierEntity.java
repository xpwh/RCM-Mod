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

		LivingEntity t = this.target;
		if (t != null && (!t.isAlive() || t.isRemoved() || !this.isEnemy(t) || t.level() != level || t.distanceTo(this) > VIEW_RANGE * 1.4)) {
			this.awareness.remove(t.getId());
			this.target = null;
			t = null;
		}
		boolean inSight = t != null && now - this.lastSeen < 6;
		this.crouch = false;
		if (inSight) {
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
		if (wantsCover && this.cover != null) {
			this.setState(COVER, this.reload > 0 ? "lädt nach" : this.suppression > 0.6F ? "unter Beschuss" : "verwundet");
			if (this.position().distanceToSqr(this.cover) > 1.0) {
				this.getNavigation().moveTo(this.cover.x, this.cover.y, this.cover.z, 1.25);
			} else {
				this.getNavigation().stop();
				this.crouch = true;
			}
		} else {
			this.setState(COMBAT, "Ziel in Sicht");
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
		// fire: after a moment to react, in bursts, only with a clear line and nobody friendly in the way
		if (this.reload == 0 && this.rounds <= 0) {
			this.startReload(level);
			return;
		}
		if (this.reload > 0 || this.shotTimer > 0 || now - this.acquired < 10 || !this.clearShot(level, t)) {
			return;
		}
		if (this.burst <= 0) {
			this.burst = 3 + this.getRandom().nextInt(3);
		}
		this.shoot(level, t, d);
		this.burst--;
		this.shotTimer = this.burst > 0 ? AkItem.CYCLE : 12 + this.getRandom().nextInt(16);
	}

	private void search(ServerLevel level, long now) {
		this.setState(SEARCH, "sucht letzte Position");
		if (this.position().distanceToSqr(this.lastKnown) > 4.0) {
			this.getNavigation().moveTo(this.lastKnown.x, this.lastKnown.y, this.lastKnown.z, 0.95);
			Vec3 l = this.lastKnown;
			this.getLookControl().setLookAt(l.x, l.y + 1.5, l.z, 30.0F, 30.0F);
		} else {
			this.getNavigation().stop();
			this.lookAround();
		}
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

	private boolean clearShot(ServerLevel level, LivingEntity t) {
		Vec3 eye = this.getEyePosition();
		Vec3 aim = t.getBoundingBox().getCenter();
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

	private void shoot(ServerLevel level, LivingEntity t, double d) {
		Vec3 look = this.viewDir();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 muzzle = this.getEyePosition().add(look.scale(0.8)).add(right.scale(0.12)).add(0, -0.12, 0);
		// lead a moving target a little, aim at the chest
		Vec3 vel = new Vec3(t.getX() - t.xo, 0.0, t.getZ() - t.zo);
		Vec3 aim = t.getBoundingBox().getCenter().add(0.0, 0.15, 0.0).add(vel.scale(d / AkItem.MUZZLE_VELOCITY));
		Vec3 dir = aim.subtract(muzzle).normalize();
		double moving = Math.sqrt(Mth.square(this.getX() - this.xo) + Mth.square(this.getZ() - this.zo));
		double spread = 0.004 + d * 0.00022 + this.suppression * 0.03 + Math.min(0.03, moving * 0.12) + (this.crouch ? -0.0015 : 0.0)
			+ (6 - Math.min(6, (int) ((level.getGameTime() - this.acquired) / 10))) * 0.002; // settles onto the target
		var r = this.getRandom();
		dir = dir.add(r.nextGaussian() * spread, r.nextGaussian() * spread, r.nextGaussian() * spread).normalize();
		this.rounds--;
		// soldiers load like players do: every fourth a tracer, the last three tracers
		boolean tracer = this.rounds % 4 == 0 || this.rounds < 3;
		AkItem.shoot(level, this, muzzle, dir, tracer, this.rounds == 0);
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
		return new SoldierDebug.Entry(this.getId(), this.aiState(), this.team(), best, this.reason, targetName, (float) targetDist,
			this.target == null ? -1 : this.target.getId(), this.target != null && now - this.lastSeen < 6,
			this.target == null ? null : this.lastKnown, this.investigate != null && now < this.investigateUntil ? this.investigate : null,
			this.cover, this.rounds, this.reload > 0, this.suppression, this.getYHeadRot(), noises, path);
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
		String[] states = {"PATROUILLE", "UNTERSUCHT", "KAMPF", "SUCHE", "DECKUNG"};
		out.add("§6Soldat #" + this.getId() + " §7(" + (this.team() == TEAM_HOSTILE ? "§cfeindlich" : "§afreundlich") + "§7) – §f"
			+ states[Mth.clamp(this.aiState(), 0, 4)] + " §7(" + this.reason + ")");
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
