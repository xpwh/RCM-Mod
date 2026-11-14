package de.rcm.ballistic.base;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.ai.SoldierEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The brain of a military base, in its flag. It knows the base's floodlights, alarm beacons, generators and
 * barracks door, and every tick or so:
 * <ul>
 *   <li><b>Alarm</b>: when a guard is in a fight the beacons turn, the siren wails and, while there are any
 *   left, reinforcements run out of the barracks.</li>
 *   <li><b>Sabotage</b>: once every generator is wrecked the base has no power - the floodlights go out
 *   (and the night is the attacker's).</li>
 *   <li><b>Capture</b>: with no guard of the base left alive, a player who holds the flag (stays close to
 *   it) for 30 seconds takes the base: its colours change, two friendly soldiers come to guard it.</li>
 * </ul>
 */
public class BaseFlagBlockEntity extends BlockEntity {
	public static final int CAPTURE_TICKS = 600;
	private static final double HOLD_RANGE = 8.0;
	private static final double BASE_RANGE = 48.0;

	private final List<BlockPos> lights = new ArrayList<>();
	private final List<BlockPos> alarms = new ArrayList<>();
	private final List<BlockPos> generators = new ArrayList<>();
	private @Nullable BlockPos barracks;
	private int variant;
	private @Nullable UUID owner;
	private boolean powered = true;
	private int capture;
	private long alarmUntil;
	private long nextSiren;
	private long nextReinforce;
	private int reinforcements = 6;
	private boolean alarmShown = true;
	private transient @Nullable ServerBossEvent bar;

	public BaseFlagBlockEntity(BlockPos pos, BlockState state) {
		super(BaseRegistry.FLAG_BE, pos, state);
	}

	/** Set up by the structure as it builds the base. */
	public void setup(int variant, List<BlockPos> lights, List<BlockPos> alarms, List<BlockPos> generators, BlockPos barracks) {
		this.variant = variant;
		this.lights.clear();
		this.lights.addAll(lights);
		this.alarms.clear();
		this.alarms.addAll(alarms);
		this.generators.clear();
		this.generators.addAll(generators);
		this.barracks = barracks;
		this.setChanged();
	}

	public boolean captured() {
		return this.owner != null;
	}

	public int variant() {
		return this.variant;
	}

	/** The flags of the bases loaded right now (for {@link #locked}). */
	private static final java.util.Set<BaseFlagBlockEntity> LOADED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

	/** Whether {@code device} stands in a base its garrison still holds (nobody may claim it yet). */
	public static boolean locked(Level level, de.rcm.ballistic.launch.Ownership.Owned device) {
		BlockPos at;
		if (device instanceof BlockEntity be) {
			at = be.getBlockPos();
		} else if (device instanceof net.minecraft.world.entity.Entity e) {
			at = e.blockPosition();
		} else {
			return false;
		}
		for (BaseFlagBlockEntity flag : LOADED) {
			if (flag.level == level && !flag.isRemoved() && !flag.captured()) {
				double dx = flag.worldPosition.getX() - at.getX();
				double dz = flag.worldPosition.getZ() - at.getZ();
				if (dx * dx + dz * dz < 72 * 72) {
					return true;
				}
			}
		}
		return false;
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, BaseFlagBlockEntity flag) {
		LOADED.add(flag);
		if (level instanceof ServerLevel server && server.getGameTime() % 10 == (pos.asLong() & 7)) {
			flag.tick(server, pos);
		}
	}

	private void tick(ServerLevel level, BlockPos pos) {
		long now = level.getGameTime();
		this.checkPower(level);
		if (this.owner != null) {
			this.setAlarm(level, false);
			this.hideBar();
			return;
		}
		AABB around = new AABB(pos).inflate(BASE_RANGE, 24.0, BASE_RANGE);
		List<SoldierEntity> guards = level.getEntitiesOfClass(SoldierEntity.class, around,
			s -> s.isAlive() && s.team() == SoldierEntity.TEAM_HOSTILE && !s.isCaptive() && pos.equals(s.base()));
		boolean fight = guards.stream().anyMatch(s -> s.aiState() == SoldierEntity.COMBAT);
		if (fight) {
			this.alarmUntil = now + 500;
		}
		boolean alarm = now < this.alarmUntil;
		this.setAlarm(level, alarm);
		if (alarm) {
			if (now >= this.nextSiren) {
				this.nextSiren = now + 90;
				level.playSound(null, pos.getX() + 0.5, pos.getY() + 6.0, pos.getZ() + 0.5, ModRegistry.SIREN, SoundSource.HOSTILE, 6.0F, 0.92F);
			}
			if (this.barracks != null && this.reinforcements > 0 && now >= this.nextReinforce && guards.size() < 6 && level.isLoaded(this.barracks)) {
				this.nextReinforce = now + 500;
				for (int i = 0; i < 2 && this.reinforcements > 0; i++) {
					this.reinforcements--;
					spawnGuard(level, Vec3.atBottomCenterOf(this.barracks).add(i - 0.5, 0.0, 0.0), -1.0F, pos, SoldierEntity.TEAM_HOSTILE);
				}
				this.setChanged();
			}
		}
		this.holdFlag(level, pos, guards.isEmpty());
	}

	/** Holding the flag: with the guards dead, a player close by for long enough takes the base. */
	private void holdFlag(ServerLevel level, BlockPos pos, boolean undefended) {
		Vec3 c = Vec3.atBottomCenterOf(pos);
		ServerPlayer holder = null;
		if (undefended) {
			for (ServerPlayer p : level.players()) {
				if (!p.isSpectator() && p.isAlive() && p.position().distanceToSqr(c) < HOLD_RANGE * HOLD_RANGE) {
					holder = p;
					break;
				}
			}
		}
		if (holder == null) {
			this.capture = Math.max(0, this.capture - 20);
			if (this.capture == 0) {
				this.hideBar();
			} else {
				this.showBar(level, c);
			}
			return;
		}
		this.capture += 10;
		this.showBar(level, c);
		if (this.capture < CAPTURE_TICKS) {
			if (this.capture % 100 == 0) {
				level.playSound(null, c.x, c.y + 1, c.z, SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.BLOCKS, 1.0F, 1.6F);
			}
			return;
		}
		// taken
		this.owner = holder.getUUID();
		this.capture = 0;
		this.hideBar();
		this.setAlarm(level, false);
		level.setBlockAndUpdate(pos, this.getBlockState().setValue(BaseBlocks.Flag.CAPTURED, true));
		level.playSound(null, c.x, c.y + 1, c.z, ModRegistry.UPLINK_CONFIRM, SoundSource.BLOCKS, 2.0F, 1.0F);
		level.playSound(null, c.x, c.y + 1, c.z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0F, 1.0F);
		Component msg = Component.translatable("message.ballisticmissiles.base.captured", holder.getDisplayName()).withStyle(ChatFormatting.GREEN);
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(c) < 128 * 128) {
				p.displayClientMessage(msg, false);
			}
		}
		for (int i = 0; i < 2; i++) {
			spawnGuard(level, c.add(i == 0 ? 2.5 : -2.5, 0.0, 1.5), 8.0F, pos, SoldierEntity.TEAM_FRIENDLY);
		}
		this.setChanged();
		level.sendBlockUpdated(pos, this.getBlockState(), this.getBlockState(), 3);
	}

	private void showBar(ServerLevel level, Vec3 c) {
		if (this.bar == null) {
			this.bar = new ServerBossEvent(Component.translatable("bossbar.ballisticmissiles.base.capture"), BossEvent.BossBarColor.GREEN,
				BossEvent.BossBarOverlay.NOTCHED_10);
		}
		this.bar.setProgress(Math.min(1.0F, this.capture / (float) CAPTURE_TICKS));
		for (ServerPlayer p : level.players()) {
			boolean near = p.position().distanceToSqr(c) < 32 * 32;
			if (near && !this.bar.getPlayers().contains(p)) {
				this.bar.addPlayer(p);
			} else if (!near && this.bar.getPlayers().contains(p)) {
				this.bar.removePlayer(p);
			}
		}
	}

	private void hideBar() {
		if (this.bar != null) {
			this.bar.removeAllPlayers();
			this.bar = null;
		}
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		LOADED.remove(this);
		this.hideBar();
	}

	/** The beacons turn while the alarm lasts. */
	private void setAlarm(ServerLevel level, boolean on) {
		if (on == this.alarmShown) {
			return;
		}
		this.alarmShown = on;
		for (BlockPos p : this.alarms) {
			setLit(level, p, on);
		}
		if (on) {
			Component msg = Component.translatable("message.ballisticmissiles.base.alarm").withStyle(ChatFormatting.RED);
			Vec3 c = Vec3.atCenterOf(this.worldPosition);
			for (ServerPlayer p : level.players()) {
				if (p.position().distanceToSqr(c) < 96 * 96) {
					p.displayClientMessage(msg, true);
				}
			}
		}
	}

	/** Power: as long as one generator still stands the floodlights burn. */
	private void checkPower(ServerLevel level) {
		if (!this.powered || this.generators.isEmpty() || level.getGameTime() % 20 >= 10) {
			return;
		}
		for (BlockPos g : this.generators) {
			if (!level.isLoaded(g) || level.getBlockState(g).is(BaseRegistry.FIELD_GENERATOR)) {
				return;
			}
		}
		this.powered = false;
		this.setChanged();
		for (BlockPos p : this.lights) {
			setLit(level, p, false);
		}
		Vec3 c = Vec3.atCenterOf(this.worldPosition);
		level.playSound(null, c.x, c.y + 2, c.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 3.0F, 0.6F);
		Component msg = Component.translatable("message.ballisticmissiles.base.power_out").withStyle(ChatFormatting.GOLD);
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(c) < 128 * 128) {
				p.displayClientMessage(msg, true);
			}
		}
	}

	private static void setLit(ServerLevel level, BlockPos p, boolean lit) {
		if (!level.isLoaded(p)) {
			return;
		}
		BlockState s = level.getBlockState(p);
		if (s.getBlock() instanceof BaseBlocks.Lamp && s.getValue(BaseBlocks.Lamp.LIT) != lit) {
			level.setBlock(p, s.setValue(BaseBlocks.Lamp.LIT, lit), 3);
		}
	}

	/** A soldier posted at the base ({@code radius} below 0: free patrol). */
	public static void spawnGuard(ServerLevel level, Vec3 at, float radius, BlockPos base, int team) {
		SoldierEntity s = ModRegistry.SOLDIER.create(level, EntitySpawnReason.EVENT);
		if (s == null) {
			return;
		}
		s.snapTo(at.x, at.y, at.z, level.getRandom().nextFloat() * 360.0F, 0.0F);
		s.setTeam(team);
		s.setPost(at, radius, base);
		level.addFreshEntity(s);
	}

	// ------------------------------------------------------------------ saving

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.store("Lights", BlockPos.CODEC.listOf(), this.lights);
		output.store("Alarms", BlockPos.CODEC.listOf(), this.alarms);
		output.store("Generators", BlockPos.CODEC.listOf(), this.generators);
		if (this.barracks != null) {
			output.store("Barracks", BlockPos.CODEC, this.barracks);
		}
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
		output.putInt("Variant", this.variant);
		output.putBoolean("Powered", this.powered);
		output.putInt("Reinforcements", this.reinforcements);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.lights.clear();
		this.lights.addAll(input.read("Lights", BlockPos.CODEC.listOf()).orElse(List.of()));
		this.alarms.clear();
		this.alarms.addAll(input.read("Alarms", BlockPos.CODEC.listOf()).orElse(List.of()));
		this.generators.clear();
		this.generators.addAll(input.read("Generators", BlockPos.CODEC.listOf()).orElse(List.of()));
		this.barracks = input.read("Barracks", BlockPos.CODEC).orElse(null);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		this.variant = input.getIntOr("Variant", 0);
		this.powered = input.getBooleanOr("Powered", true);
		this.reinforcements = input.getIntOr("Reinforcements", 6);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("Captured", this.owner != null);
		return tag;
	}
}
