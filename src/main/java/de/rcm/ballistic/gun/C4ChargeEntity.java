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
import org.jspecify.annotations.Nullable;

/**
 * A C4 charge stuck to a wall, a floor or a ceiling: four M112 blocks with a radio receiver and two blasting caps
 * (C4Item puts it there). It sits armed, its red LED blinking and the receiver giving a soft beep now and then,
 * until its owner's firing device (C4DetonatorItem) sends the signal: the LED goes steady, a last beep, and a moment
 * later - the radio's lag - it goes off. A blast close by sets it off too (sympathetic detonation); a jammer near it
 * keeps the signal from getting through. Anyone can take it off again: right-click it with an empty hand.
 * <p>
 * Its position is the point on the face it is stuck to; {@link #face()} is the way out of that face.
 */
public class C4ChargeEntity extends Entity {
	private static final EntityDataAccessor<Byte> FACE = SynchedEntityData.defineId(C4ChargeEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Float> SPIN = SynchedEntityData.defineId(C4ChargeEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> TRIGGERED = SynchedEntityData.defineId(C4ChargeEntity.class, EntityDataSerializers.BOOLEAN);
	/** How far the firing device reaches, blocks. */
	public static final double RADIO_RANGE = 400.0;

	private @Nullable UUID owner;
	private long placedAt;
	/** Ticks to go once the signal came, or -1. */
	private int countdown = -1;

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

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(FACE, (byte) Direction.UP.get3DDataValue());
		builder.define(SPIN, 0.0F);
		builder.define(TRIGGERED, false);
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
		Direction f = this.face();
		return this.position().add(f.getStepX() * 0.05, f.getStepY() * 0.05, f.getStepZ() * 0.05);
	}

	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		if (this.entityData == null) {
			return super.makeBoundingBox(pos);
		}
		Direction f = this.face();
		Vec3 c = pos.add(f.getStepX() * 0.05, f.getStepY() * 0.05, f.getStepZ() * 0.05);
		return new AABB(c, c).inflate(0.17);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (FACE.equals(key)) {
			this.setBoundingBox(this.makeBoundingBox(this.position()));
		}
	}

	@Override
	public void tick() {
		super.tick();
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
		Vec3 at = this.centre();
		Entity source = this.owner == null ? null : level.getPlayerByUUID(this.owner);
		this.discard();
		de.rcm.ballistic.explosion.DetonationManager.detonateC4(level, at, source);
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
	}
}
