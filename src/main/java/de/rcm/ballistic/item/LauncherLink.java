package de.rcm.ballistic.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** A launcher remote-controlled by a target designator: a launch pad, a silo or a mobile launcher truck. */
public record LauncherLink(int kind, BlockPos pos, Optional<UUID> entity) {
	public static final int PAD = 0;
	public static final int SILO = 1;
	public static final int TRUCK = 2;
	public static final int UPLINK = 3;

	public static final Codec<LauncherLink> CODEC = RecordCodecBuilder.create(
		instance -> instance.group(
				Codec.INT.fieldOf("kind").forGetter(LauncherLink::kind),
				BlockPos.CODEC.fieldOf("pos").forGetter(LauncherLink::pos),
				UUIDUtil.CODEC.optionalFieldOf("entity").forGetter(LauncherLink::entity)
			)
			.apply(instance, LauncherLink::new)
	);
	public static final StreamCodec<ByteBuf, LauncherLink> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, LauncherLink::kind,
		BlockPos.STREAM_CODEC, LauncherLink::pos,
		ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), LauncherLink::entity,
		LauncherLink::new
	);

	public static LauncherLink pad(BlockPos pos) {
		return new LauncherLink(PAD, pos.immutable(), Optional.empty());
	}

	public static LauncherLink silo(BlockPos pos) {
		return new LauncherLink(SILO, pos.immutable(), Optional.empty());
	}

	public static LauncherLink uplink(BlockPos pos) {
		return new LauncherLink(UPLINK, pos.immutable(), Optional.empty());
	}

	public static LauncherLink truck(UUID uuid, BlockPos pos) {
		return new LauncherLink(TRUCK, pos.immutable(), Optional.of(uuid));
	}

	public boolean sameLauncher(LauncherLink other) {
		if (this.kind != other.kind) {
			return false;
		}
		return this.kind == TRUCK ? this.entity.equals(other.entity) : this.pos.equals(other.pos);
	}

	public Component describe() {
		String key = switch (this.kind) {
			case SILO -> "launcher.ballisticmissiles.silo";
			case TRUCK -> "launcher.ballisticmissiles.truck";
			case UPLINK -> "launcher.ballisticmissiles.uplink";
			default -> "launcher.ballisticmissiles.pad";
		};
		return Component.translatable(key, this.pos.getX(), this.pos.getZ());
	}
}
