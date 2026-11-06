package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * A C4 charge stuck to a wall, a floor or a ceiling: four M112 blocks with a radio receiver and two blasting caps
 * (C4Item puts it there). It sits armed, its red LED blinking and the receiver giving a soft beep now and then,
 * until its owner's firing device (C4DetonatorItem) sends the signal: the LED goes steady, a last beep, and a moment
 * later - the radio's lag - it goes off. A blast close by sets it off too (sympathetic detonation); a jammer near it
 * keeps the signal from getting through. Anyone can take it off again: right-click it with an empty hand.
 * <p>
 * Its position is the point on the face it is stuck to; {@link #face()} is the way out of that face.
 * <p>
 * It can be stuck to something that moves, too - a mob, a player, a truck, a helicopter, a jet (C4Mount): then it
 * keeps its place and turn in the host's model space and rides along; if the host is gone, it falls off.
 */
public class C4ChargeEntity extends Entity {
	private static final EntityDataAccessor<Byte> FACE = SynchedEntityData.defineId(C4ChargeEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Float> SPIN = SynchedEntityData.defineId(C4ChargeEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> TRIGGERED = SynchedEntityData.defineId(C4ChargeEntity.class, EntityDataSerializers.BOOLEAN);
	/** The entity it is stuck to (its network id), or -1: on a block. */
	private static final EntityDataAccessor<Integer> HOST = SynchedEntityData.defineId(C4ChargeEntity.class, EntityDataSerializers.INT);
	/** On a host: where it sits and how it is turned, in the host's model space (C4Mount). */
	private static final EntityDataAccessor<Vector3fc> LOCAL_POS = SynchedEntityData.defineId(C4ChargeEntity.class, EntityDataSerializers.VECTOR3);
	private static final EntityDataAccessor<Quaternionfc> LOCAL_ROT = SynchedEntityData.defineId(C4ChargeEntity.class, EntityDataSerializers.QUATERNION);
	/** How far the firing device reaches, blocks. */
	public static final double RADIO_RANGE = 400.0;

	private @Nullable UUID owner;
	private long placedAt;
	/** Ticks to go once the signal came, or -1. */
	private int countdown = -1;
	/** On a host: who (kept across saves), and how long it has been missing. */
	private @Nullable UUID hostId;
	private @Nullable Entity host;
	private int hostMissing;

	public C4ChargeEntity(EntityType<? extends C4ChargeEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
	}

	/** A charge stuck to {@code face} of the block at {@code block}, at {@code at} on it. */
	public static C4ChargeEntity place(ServerLevel level, Vec3 at, Direction face, float spin, Player owner) {
		C4ChargeEntity c = new C4ChargeEntity(ModRegistry.C4_CHARGE, level);
		c.entityData.set(FACE, (byte) face.get3DDataValue());
		c.entityData.set(SPIN, spin);
		c.owner = owner.getUUID();
		c.placedAt = level.getGameTime();
		c.setPos(at.add(face.getStepX() * 0.002, face.getStepY() * 0.002, face.getStepZ() * 0.002));
		level.addFreshEntity(c);
		return c;
	}

	/** A charge stuck to {@code spot.host()} (C4Mount.aim): it keeps that place on it, wherever it goes. */
	public static C4ChargeEntity placeOn(ServerLevel level, C4Mount.Spot spot, Player owner) {
		C4ChargeEntity c = new C4ChargeEntity(ModRegistry.C4_CHARGE, level);
		Entity host = spot.host();
		Vec3 pivot = C4Mount.pivot(host, 1.0F);
		Quaternionf inv = new Quaternionf(C4Mount.attitude(host, 1.0F)).conjugate();
		Vec3 d = spot.at().subtract(pivot);
		c.entityData.set(LOCAL_POS, inv.transform(new Vector3f((float) d.x, (float) d.y, (float) d.z)));
		c.entityData.set(LOCAL_ROT, new Quaternionf(inv).mul(spot.rotation()).normalize());
		c.entityData.set(HOST, host.getId());
		c.host = host;
		c.hostId = host.getUUID();
		c.owner = owner.getUUID();
		c.placedAt = level.getGameTime();
		c.setPos(spot.at());
		level.addFreshEntity(c);
		return c;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(FACE, (byte) Direction.UP.get3DDataValue());
		builder.define(SPIN, 0.0F);
		builder.define(TRIGGERED, false);
		builder.define(HOST, -1);
		builder.define(LOCAL_POS, new Vector3f());
		builder.define(LOCAL_ROT, new Quaternionf());
	}

	/** Stuck to something that moves (it may not be loaded just now)? */
	public boolean onHost() {
		return this.hostId != null || this.entityData.get(HOST) >= 0;
	}

	/** What it is stuck to, if that is here: null on a block, or while the host is missing. */
	public @Nullable Entity host() {
		if (this.host != null && !this.host.isRemoved()) {
			return this.host;
		}
		this.host = null;
		if (this.level() instanceof ServerLevel level) {
			if (this.hostId != null) {
				Entity e = level.getEntity(this.hostId);
				if (e != null && !e.isRemoved()) {
					this.host = e;
					this.entityData.set(HOST, e.getId());
				}
			}
		} else {
			int id = this.entityData.get(HOST);
			Entity e = id < 0 ? null : this.level().getEntity(id);
			if (e != null && !e.isRemoved()) {
				this.host = e;
			}
		}
		return this.host;
	}

	/** On its host, {@code partial} into the tick: where it is and how it is turned (world). */
	public record Pose(Vec3 pos, Quaternionf rotation) {
	}

	public @Nullable Pose hostPose(float partial) {
		Entity h = this.host();
		if (h == null) {
			return null;
		}
		Quaternionf att = C4Mount.attitude(h, partial);
		Vector3f p = att.transform(new Vector3f(this.entityData.get(LOCAL_POS)));
		Vec3 pivot = C4Mount.pivot(h, partial);
		return new Pose(pivot.add(p.x, p.y, p.z), att.mul(new Quaternionf(this.entityData.get(LOCAL_ROT))));
	}

	/** The way out of what it is stuck to (world). */
	public Vec3 outward() {
		if (this.onHost()) {
			Pose pose = this.hostPose(1.0F);
			if (pose != null) {
				Vector3f n = pose.rotation().transform(new Vector3f(0, 1, 0));
				return new Vec3(n.x, n.y, n.z);
			}
			return new Vec3(0, 1, 0);
		}
		Direction f = this.face();
		return new Vec3(f.getStepX(), f.getStepY(), f.getStepZ());
	}

	public Direction face() {
		return Direction.from3DDataValue(this.entityData.get(FACE));
	}

	/** Its turn about the face's normal, degrees (on a floor or a ceiling: the way the placer looked). */
	public float spin() {
		return this.entityData.get(SPIN);
	}

	public boolean triggered() {
		return this.entityData.get(TRIGGERED);
	}

	public @Nullable UUID owner() {
		return this.owner;
	}

	public long placedAt() {
		return this.placedAt;
	}

	/** Where the explosive is: a little out from the face it is stuck to. */
	public Vec3 centre() {
		return this.position().add(this.outward().scale(0.05));
	}

	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		if (this.entityData == null) {
			return super.makeBoundingBox(pos);
		}
		if (this.entityData.get(HOST) >= 0 || this.hostId != null) {
			return new AABB(pos, pos).inflate(0.2);
		}
		Direction f = this.face();
		Vec3 c = pos.add(f.getStepX() * 0.05, f.getStepY() * 0.05, f.getStepZ() * 0.05);
		return new AABB(c, c).inflate(0.17);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (FACE.equals(key) || HOST.equals(key)) {
			this.setBoundingBox(this.makeBoundingBox(this.position()));
		}
	}

	@Override
	public void tick() {
		super.tick();
		// on a host: ride along
		if (this.onHost()) {
			Pose pose = this.hostPose(1.0F);
			if (pose != null) {
				this.setPos(pose.pos());
				this.hostMissing = 0;
			} else {
				this.hostMissing++;
			}
		}
		if (!(this.level() instanceof ServerLevel level)) {
			return;
		}
		if (this.countdown >= 0) {
			if (this.countdown-- == 0) {
				this.explode(level);
			}
			return;
		}
		// the receiver: a soft beep every couple of seconds while it waits
		if ((this.tickCount + this.getId()) % 50 == 0) {
			level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.C4_BEEP, SoundSource.BLOCKS, 0.25F, 1.0F);
		}
		if (this.onHost()) {
			// the host is gone (killed, wrecked, out of this world): it falls off
			if (this.hostMissing > 100) {
				this.spawnAtLocation(level, new ItemStack(ModRegistry.C4));
				this.discard();
			}
			return;
		}
		// the wall it was stuck to is gone: it drops off
		if (this.tickCount % 10 == 0) {
			Direction f = this.face();
			BlockPos behind = BlockPos.containing(this.position().subtract(f.getStepX() * 0.05, f.getStepY() * 0.05, f.getStepZ() * 0.05));
			if (level.getBlockState(behind).getCollisionShape(level, behind).isEmpty()) {
				this.spawnAtLocation(level, new ItemStack(ModRegistry.C4));
				this.discard();
			}
		}
	}

	/**
	 * The signal came: the LED goes steady and the receiver beeps once, then in {@code delay} ticks it goes off. With
	 * {@code radio} false (another blast set it off) there is no beep and no lag to speak of.
	 */
	public void trigger(int delay, boolean radio) {
		if (this.countdown >= 0 || !(this.level() instanceof ServerLevel level)) {
			return;
		}
		this.countdown = Math.max(0, delay);
		this.entityData.set(TRIGGERED, true);
		if (radio) {
			level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.C4_BEEP, SoundSource.BLOCKS, 0.8F, 1.35F);
		}
	}

	private void explode(ServerLevel level) {
		if (this.onHost()) {
			// a fast jet has moved on since this ticked: go off where it is now
			Pose pose = this.hostPose(1.0F);
			if (pose != null) {
				this.setPos(pose.pos());
			}
		}
		Vec3 at = this.centre();
		Entity source = this.owner == null ? null : level.getPlayerByUUID(this.owner);
		Entity on = this.onHost() ? this.host() : null;
		this.discard();
		de.rcm.ballistic.explosion.DetonationManager.detonateC4(level, at, source);
		if (on != null && !on.isRemoved()) {
			// right on its skin: the full force of it goes into what it was stuck to
			on.hurtServer(level, level.damageSources().explosion(this, source), 90.0F);
		}
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (this.isRemoved()) {
			return false;
		}
		if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			// the shock of a blast close by sets it off
			this.trigger(1 + this.random.nextInt(3), false);
			return true;
		}
		if (source.getDirectEntity() instanceof Player player && !source.is(DamageTypeTags.IS_PROJECTILE)) {
			// knocked off the wall: C4 does not go off from a blow
			this.knockOff(level, player);
			return true;
		}
		return false;
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		if (!player.getItemInHand(hand).isEmpty() || this.countdown >= 0) {
			return InteractionResult.PASS;
		}
		if (this.level() instanceof ServerLevel level) {
			// pulled off the wall, the caps drawn: safe to carry
			this.knockOff(level, player);
		}
		return InteractionResult.SUCCESS;
	}

	private void knockOff(ServerLevel level, Player player) {
		if (this.countdown >= 0) {
			return;
		}
		level.playSound(null, this.getX(), this.getY(), this.getZ(), ModRegistry.C4_PLACE, SoundSource.PLAYERS, 0.7F, 1.15F);
		ItemStack stack = new ItemStack(ModRegistry.C4);
		if (!player.getAbilities().instabuild && !player.addItem(stack)) {
			this.spawnAtLocation(level, stack);
		}
		this.discard();
	}

	@Override
	public boolean shouldRender(double x, double y, double z) {
		// on a fast jet it is a tick behind its own position: drawn wherever the host is
		return this.onHost() ? this.shouldRenderAtSqrDistance(this.distanceToSqr(x, y, z)) : super.shouldRender(x, y, z);
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double d2) {
		return d2 < 96.0 * 96.0;
	}

	/** The charges {@code player} has placed within radio range, in the order they were placed. */
	public static List<C4ChargeEntity> charges(ServerPlayer player) {
		List<C4ChargeEntity> out = new ArrayList<>();
		UUID id = player.getUUID();
		for (C4ChargeEntity c : player.level().getEntities(ModRegistry.C4_CHARGE, player.getBoundingBox().inflate(RADIO_RANGE),
			c -> id.equals(c.owner) && !c.isRemoved())) {
			out.add(c);
		}
		out.sort(Comparator.comparingLong(C4ChargeEntity::placedAt).thenComparingInt(Entity::getId));
		return out;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.entityData.set(FACE, (byte) input.getIntOr("Face", Direction.UP.get3DDataValue()));
		this.entityData.set(SPIN, input.getFloatOr("Spin", 0.0F));
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		this.placedAt = input.getLongOr("PlacedAt", 0L);
		this.hostId = input.read("Host", UUIDUtil.CODEC).orElse(null);
		if (this.hostId != null) {
			this.entityData.set(LOCAL_POS, new Vector3f(input.getFloatOr("LX", 0.0F), input.getFloatOr("LY", 0.0F), input.getFloatOr("LZ", 0.0F)));
			this.entityData.set(LOCAL_ROT, new Quaternionf(input.getFloatOr("QX", 0.0F), input.getFloatOr("QY", 0.0F), input.getFloatOr("QZ", 0.0F),
				input.getFloatOr("QW", 1.0F)).normalize());
		}
		this.setBoundingBox(this.makeBoundingBox(this.position()));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("Face", this.face().get3DDataValue());
		output.putFloat("Spin", this.spin());
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
		output.putLong("PlacedAt", this.placedAt);
		if (this.hostId != null) {
			output.store("Host", UUIDUtil.CODEC, this.hostId);
			Vector3fc p = this.entityData.get(LOCAL_POS);
			Quaternionfc q = this.entityData.get(LOCAL_ROT);
			output.putFloat("LX", p.x());
			output.putFloat("LY", p.y());
			output.putFloat("LZ", p.z());
			output.putFloat("QX", q.x());
			output.putFloat("QY", q.y());
			output.putFloat("QZ", q.z());
			output.putFloat("QW", q.w());
		}
	}
}
