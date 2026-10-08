package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.entity.KineticRodEntity;
import de.rcm.ballistic.item.TargetData;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The ground station's side of an orbital strike. On an order the dish slews onto the weapons
 * platform passing overhead and uplinks the target; the platform fires the de-orbit burn and lets a
 * rod go, which comes down on the target some seconds later. The platform carries a few rods; more
 * are sent up from here.
 */
public class OrbitalUplinkBlockEntity extends BlockEntity {
	public static final int CAPACITY = 6;
	private static final int ACQUIRE_TICKS = 50;
	private static final int COOLDOWN_TICKS = 160;
	public static final int IDLE = 0;
	public static final int ACQUIRING = 1;
	public static final int STRIKING = 2;

	private int rods = 3;
	private int phase = IDLE;
	private int timer;
	private BlockPos target = BlockPos.ZERO;
	private @Nullable UUID player;
	/** Azimuth (radians, 0 = +Z) the dish points to while working. */
	private float workAzimuth;

	/** Client: current dish angles. */
	public float azimuth;
	public float azimuthO;
	public float elevation = 0.8F;
	public float elevationO = 0.8F;
	public int clientAge;

	public OrbitalUplinkBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.ORBITAL_UPLINK_BE, pos, state);
	}

	public int rods() {
		return this.rods;
	}

	public int phase() {
		return this.phase;
	}

	public boolean loadRod() {
		if (this.rods >= CAPACITY) {
			return false;
		}
		this.rods++;
		this.sync();
		return true;
	}

	/** Orders a strike on {@code order} for {@code by} (or a remote order). Returns the report. */
	public Component strike(@Nullable ServerPlayer by, TargetData order) {
		if (this.level == null) {
			return Component.empty();
		}
		if (EmpManager.isJammed(this.level, this.worldPosition)) {
			return Component.translatable("message.ballisticmissiles.uplink_emp").withStyle(ChatFormatting.DARK_PURPLE);
		}
		if (this.phase != IDLE) {
			return Component.translatable("message.ballisticmissiles.uplink_busy").withStyle(ChatFormatting.YELLOW);
		}
		if (this.rods <= 0) {
			return Component.translatable("message.ballisticmissiles.uplink_empty").withStyle(ChatFormatting.YELLOW);
		}
		this.rods--;
		this.phase = ACQUIRING;
		this.timer = 0;
		this.target = order.pos();
		this.player = by != null ? by.getUUID() : null;
		double dx = this.target.getX() - this.worldPosition.getX();
		double dz = this.target.getZ() - this.worldPosition.getZ();
		this.workAzimuth = (float) Math.atan2(dx, dz);
		this.sync();
		this.level.playSound(null, this.worldPosition, ModRegistry.UPLINK_CONFIRM, SoundSource.BLOCKS, 1.0F, 1.0F);
		this.level.playSound(null, this.worldPosition.above(2), ModRegistry.UPLINK_SERVO, SoundSource.BLOCKS, 1.4F, 0.9F);
		return Component.literal("☄ ").append(Component.translatable("message.ballisticmissiles.uplink_acquire", this.target.getX(), this.target.getZ(), this.rods))
			.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, OrbitalUplinkBlockEntity uplink) {
		if (!(level instanceof ServerLevel server)) {
			return;
		}
		if (uplink.phase == IDLE) {
			// now and then the dish steps along after the platform crossing the sky
			if ((level.getGameTime() + pos.asLong()) % 300 == 0) {
				level.playSound(null, pos.above(2), ModRegistry.UPLINK_SERVO, SoundSource.BLOCKS, 0.35F, 1.1F);
			}
			return;
		}
		uplink.timer++;
		if (uplink.phase == ACQUIRING && uplink.timer % 22 == 8) {
			// telemetry and the target data going up the link
			level.playSound(null, pos, ModRegistry.UPLINK_DATA, SoundSource.BLOCKS, 0.9F, 0.95F + server.getRandom().nextFloat() * 0.1F);
		}
		if (uplink.phase == ACQUIRING && uplink.timer >= ACQUIRE_TICKS) {
			uplink.phase = STRIKING;
			Vec3 aim = Vec3.atBottomCenterOf(uplink.target);
			KineticRodEntity.drop(server, aim, Vec3.atCenterOf(pos));
			uplink.tell(server, Component.translatable("message.ballisticmissiles.uplink_release").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			level.playSound(null, pos, ModRegistry.UPLINK_ALARM, SoundSource.BLOCKS, 2.0F, 1.0F);
			level.playSound(null, pos, ModRegistry.UPLINK_CONFIRM, SoundSource.BLOCKS, 1.0F, 0.8F);
			uplink.sync();
		}
		if (uplink.phase == STRIKING && uplink.timer >= ACQUIRE_TICKS + COOLDOWN_TICKS) {
			uplink.phase = IDLE;
			uplink.sync();
		}
	}

	public static void clientTick(Level level, BlockPos pos, BlockState state, OrbitalUplinkBlockEntity uplink) {
		uplink.clientAge++;
		uplink.azimuthO = uplink.azimuth;
		uplink.elevationO = uplink.elevation;
		float wantAz;
		float wantEl;
		if (uplink.phase == IDLE) {
			// tracking the platform slowly across the sky between orders
			wantAz = (float) Math.sin(uplink.clientAge * 0.004) * 1.2F;
			wantEl = 0.9F + (float) Math.sin(uplink.clientAge * 0.003) * 0.2F;
		} else {
			wantAz = uplink.workAzimuth;
			wantEl = 1.25F;
		}
		float dAz = Mth.wrapDegrees((wantAz - uplink.azimuth) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD;
		uplink.azimuth += Mth.clamp(dAz, -0.035F, 0.035F);
		uplink.elevation += Mth.clamp(wantEl - uplink.elevation, -0.02F, 0.02F);
		if (uplink.azimuth - uplink.azimuthO > Math.PI) {
			uplink.azimuthO += Mth.TWO_PI;
		} else if (uplink.azimuthO - uplink.azimuth > Math.PI) {
			uplink.azimuthO -= Mth.TWO_PI;
		}
	}

	private void tell(ServerLevel level, Component message) {
		if (this.player != null && level.getServer().getPlayerList().getPlayer(this.player) instanceof ServerPlayer p) {
			p.displayClientMessage(message, false);
		}
	}

	public Component status() {
		String key = switch (this.phase) {
			case ACQUIRING -> "message.ballisticmissiles.uplink_status_acquiring";
			case STRIKING -> "message.ballisticmissiles.uplink_status_cooldown";
			default -> "message.ballisticmissiles.uplink_status_ready";
		};
		return Component.translatable(key, this.rods, CAPACITY).withStyle(this.phase == IDLE ? ChatFormatting.AQUA : ChatFormatting.GOLD);
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
		output.putInt("Rods", this.rods);
		output.putInt("Phase", this.phase);
		output.putInt("Timer", this.timer);
		output.putFloat("WorkAzimuth", this.workAzimuth);
		output.store("Target", BlockPos.CODEC, this.target);
		if (this.player != null) {
			output.store("Player", UUIDUtil.CODEC, this.player);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.rods = input.getIntOr("Rods", 3);
		this.phase = input.getIntOr("Phase", IDLE);
		this.timer = input.getIntOr("Timer", 0);
		this.workAzimuth = input.getFloatOr("WorkAzimuth", 0.0F);
		this.target = input.read("Target", BlockPos.CODEC).orElse(BlockPos.ZERO);
		this.player = input.read("Player", UUIDUtil.CODEC).orElse(null);
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
