package de.rcm.ballistic.sun;

import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.EmpManager;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Helios launch complex's state: whether a round is in the shaft, the target, and - while a launch runs -
 * when the order was given, from which everything the complex does (doors, crane, lift, the coils charging,
 * the shot) follows by {@link SunTimeline}. The strike itself, once ordered, is carried on by {@link SunStrikes},
 * so it goes on when the complex's chunk is left behind.
 */
public class SunForgeBlockEntity extends BlockEntity implements de.rcm.ballistic.launch.Ownership.Owned {
	/** Closest and furthest it fires: the nova must not reach back to the complex. */
	public static final int MIN_RANGE = 340;
	public static final int MAX_RANGE = 6000;
	/** After the shot the doors close again and the complex is ready for the next round. */
	public static final int RESET = SunTimeline.FIRE + 240;

	private @Nullable UUID owner;
	private boolean loaded;
	private boolean hasTarget;
	private int targetX;
	private int targetZ;
	/** Game time the launch was ordered, -1 when none runs. */
	private long launchTime = -1L;
	private int flight;
	private int strikeId;
	/** Client: ticks since it came into view, for idle motion. */
	public int clientAge;

	public SunForgeBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.SUN_FORGE_BE, pos, state);
	}

	@Override
	public @Nullable UUID getOwner() {
		return this.owner;
	}

	@Override
	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
		this.setChanged();
	}

	public boolean loaded() {
		return this.loaded;
	}

	public boolean hasTarget() {
		return this.hasTarget;
	}

	public int targetX() {
		return this.targetX;
	}

	public int targetZ() {
		return this.targetZ;
	}

	public long launchTime() {
		return this.launchTime;
	}

	public int flight() {
		return this.flight;
	}

	/** Ticks since the launch order (with {@code partialTick}), or -1 when none runs. */
	public float launchAge(float partialTick) {
		if (this.launchTime < 0L || this.level == null) {
			return -1.0F;
		}
		return (float) (this.level.getGameTime() - this.launchTime) + partialTick;
	}

	public boolean busy() {
		return this.launchTime >= 0L;
	}

	public boolean load() {
		if (this.loaded) {
			return false;
		}
		this.loaded = true;
		if (this.level != null) {
			this.level.playSound(null, this.worldPosition, ModRegistry.SUN_LOAD, SoundSource.BLOCKS, 2.0F, 1.0F);
		}
		this.sync();
		return true;
	}

	/** An order from the console (or a designator): {@link SunNetwork#ACTION_TARGET}, launch or abort. */
	public void action(ServerPlayer player, int action, int x, int z) {
		if (!(this.level instanceof ServerLevel level) || de.rcm.ballistic.launch.Ownership.refuse(player, this)) {
			return;
		}
		switch (action) {
			case SunNetwork.ACTION_TARGET -> {
				if (this.busy()) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.sun_busy").withStyle(ChatFormatting.YELLOW), true);
					return;
				}
				double d = Math.hypot(x + 0.5 - (this.worldPosition.getX() + 0.5), z + 0.5 - (this.worldPosition.getZ() + 0.5));
				if (d < MIN_RANGE) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.sun_too_close", MIN_RANGE).withStyle(ChatFormatting.RED), true);
					return;
				}
				if (d > MAX_RANGE) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.sun_too_far", MAX_RANGE).withStyle(ChatFormatting.RED), true);
					return;
				}
				this.hasTarget = true;
				this.targetX = x;
				this.targetZ = z;
				level.playSound(null, this.worldPosition, ModRegistry.UPLINK_CONFIRM, SoundSource.BLOCKS, 1.0F, 0.8F);
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.sun_target", x, z, (int) d).withStyle(ChatFormatting.GOLD), true);
				this.sync();
			}
			case SunNetwork.ACTION_LAUNCH -> player.displayClientMessage(this.launch(level, player), false);
			case SunNetwork.ACTION_ABORT -> {
				float t = this.launchAge(0.0F);
				if (t < 0.0F) {
					return;
				}
				if (t >= SunTimeline.FIRE - 20) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.sun_no_abort").withStyle(ChatFormatting.RED), true);
					return;
				}
				SunStrikes.abort(level, this.strikeId);
				this.launchTime = -1L;
				level.playSound(null, this.worldPosition, ModRegistry.SUN_ABORT, SoundSource.BLOCKS, 3.0F, 1.0F);
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.sun_aborted").withStyle(ChatFormatting.AQUA), false);
				this.sync();
			}
			default -> {
			}
		}
	}

	private Component launch(ServerLevel level, ServerPlayer player) {
		if (this.busy()) {
			return Component.translatable("message.ballisticmissiles.sun_busy").withStyle(ChatFormatting.YELLOW);
		}
		if (!this.loaded) {
			return Component.translatable("message.ballisticmissiles.sun_not_loaded").withStyle(ChatFormatting.YELLOW);
		}
		if (!this.hasTarget) {
			return Component.translatable("message.ballisticmissiles.no_target").withStyle(ChatFormatting.YELLOW);
		}
		if (!de.rcm.ballistic.config.ServerConfig.nukes) {
			return Component.translatable("message.ballisticmissiles.sun_forbidden").withStyle(ChatFormatting.RED);
		}
		if (EmpManager.isJammed(level, this.worldPosition)) {
			return Component.translatable("message.ballisticmissiles.uplink_emp").withStyle(ChatFormatting.DARK_PURPLE);
		}
		// the ground at the target (its chunk is loaded for this, and stays loaded while the strike comes down)
		int y = level.getChunk(this.targetX >> 4, this.targetZ >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, this.targetX & 15, this.targetZ & 15) + 1;
		Vec3 target = new Vec3(this.targetX + 0.5, y, this.targetZ + 0.5);
		this.flight = SunTimeline.flightTicks(this.worldPosition, target);
		this.launchTime = level.getGameTime();
		this.strikeId = SunStrikes.start(level, this.worldPosition, target, this.launchTime, this.flight, player);
		level.playSound(null, this.worldPosition, ModRegistry.SUN_KLAXON, SoundSource.BLOCKS, 6.0F, 1.0F);
		this.sync();
		int seconds = (SunTimeline.FIRE + this.flight) / 20;
		return Component.literal("☀ ").append(Component.translatable("message.ballisticmissiles.sun_launch", this.targetX, this.targetZ, seconds))
			.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, SunForgeBlockEntity forge) {
		if (forge.launchTime < 0L) {
			return;
		}
		float t = forge.launchAge(0.0F);
		if (forge.loaded && t >= SunTimeline.FIRE) {
			forge.loaded = false; // gone up the barrel
			forge.sync();
		}
		if (t >= RESET) {
			forge.launchTime = -1L;
			forge.sync();
		}
	}

	public static void clientTick(Level level, BlockPos pos, BlockState state, SunForgeBlockEntity forge) {
		forge.clientAge++;
		ClientHooks.sunForgeClientTick.accept(forge);
	}

	private void sync() {
		this.setChanged();
		if (this.level != null && !this.level.isClientSide()) {
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (this.owner != null) {
			output.store("Owner", net.minecraft.core.UUIDUtil.CODEC, this.owner);
		}
		output.putBoolean("Loaded", this.loaded);
		output.putBoolean("HasTarget", this.hasTarget);
		output.putInt("TargetX", this.targetX);
		output.putInt("TargetZ", this.targetZ);
		output.putLong("LaunchTime", this.launchTime);
		output.putInt("Flight", this.flight);
		output.putInt("Strike", this.strikeId);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.owner = input.read("Owner", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
		this.loaded = input.getBooleanOr("Loaded", false);
		this.hasTarget = input.getBooleanOr("HasTarget", false);
		this.targetX = input.getIntOr("TargetX", 0);
		this.targetZ = input.getIntOr("TargetZ", 0);
		this.launchTime = input.getLongOr("LaunchTime", -1L);
		this.flight = input.getIntOr("Flight", 0);
		this.strikeId = input.getIntOr("Strike", 0);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return this.saveCustomOnly(registries);
	}
}
