package de.rcm.ballistic.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A stored missile target. When {@code surface} is true the Y coordinate is only a guess
 * and the missile will resolve the real ground height once the target area is loaded.
 */
public record TargetData(BlockPos pos, boolean surface) {
	public static final Codec<TargetData> CODEC = RecordCodecBuilder.create(
		instance -> instance.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(TargetData::pos),
				Codec.BOOL.optionalFieldOf("surface", false).forGetter(TargetData::surface)
			)
			.apply(instance, TargetData::new)
	);
	public static final StreamCodec<ByteBuf, TargetData> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, TargetData::pos, ByteBufCodecs.BOOL, TargetData::surface, TargetData::new
	);

	public String describe() {
		return pos.getX() + ", " + (surface ? "~" : String.valueOf(pos.getY())) + ", " + pos.getZ();
	}
}
