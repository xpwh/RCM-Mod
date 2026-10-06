package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.EmpManager;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Electronic-warfare jammer. While switched on it floods GPS and the missiles' guidance bands
 * around itself: missiles aimed into its zone lose their satellite fix and miss by tens of blocks,
 * and other players' radars nearby see their tracks smeared out. Switch it on and off by hand.
 */
public class JammerBlockEntity extends BlockEntity {
	/** Missiles aimed within this radius are thrown off. */
	public static final double RADIUS = 160.0;
	/** Hostile radars within this radius get noisy tracks. */
	public static final double RADAR_RADIUS = 320.0;

	private boolean active = true;
	private @Nullable UUID owner;

	public JammerBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.JAMMER_BE, pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, JammerBlockEntity jammer) {
		if (jammer.isJamming()) {
			DefenseNetwork.register(level, pos, DefenseNetwork.Kind.JAMMER);
		} else {
			DefenseNetwork.unregister(level, pos);
		}
	}

	/** On and not knocked out by an EMP. */
	public boolean isJamming() {
		return this.active && (this.level == null || !EmpManager.isJammed(this.level, this.worldPosition));
	}

	public boolean isActive() {
		return this.active;
	}

	public void toggle() {
		this.active = !this.active;
		this.setChanged();
		if (this.level != null && !this.level.isClientSide()) {
			if (!this.active) {
				DefenseNetwork.unregister(this.level, this.worldPosition);
			}
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	public @Nullable UUID getOwner() {
		return this.owner;
	}

	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
		this.setChanged();
	}

	/** Is {@code target} inside the zone of any working jammer? */
	public static boolean covers(Level level, BlockPos target) {
		return !DefenseNetwork.find(level, DefenseNetwork.Kind.JAMMER, Vec3.atCenterOf(target), RADIUS).isEmpty();
	}

	/** Is a radar owned by {@code radarOwner} at {@code radar} being jammed by someone else's jammer? */
	public static boolean jamsRadar(Level level, BlockPos radar, @Nullable UUID radarOwner) {
		for (BlockPos p : DefenseNetwork.find(level, DefenseNetwork.Kind.JAMMER, Vec3.atCenterOf(radar), RADAR_RADIUS)) {
			if (level.getBlockEntity(p) instanceof JammerBlockEntity jammer && jammer.owner != null && !jammer.owner.equals(radarOwner)) {
				return true;
			}
		}
		return false;
	}

	public Component status() {
		return this.active
			? Component.translatable("message.ballisticmissiles.jammer_on", (int) RADIUS).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
			: Component.translatable("message.ballisticmissiles.jammer_off").withStyle(ChatFormatting.GRAY);
	}

	@Override
	public void setRemoved() {
		if (this.level != null && !this.level.isClientSide()) {
			DefenseNetwork.unregister(this.level, this.worldPosition);
		}
		super.setRemoved();
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putBoolean("Active", this.active);
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.active = input.getBooleanOr("Active", true);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
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
