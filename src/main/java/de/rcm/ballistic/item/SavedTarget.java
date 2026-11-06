package de.rcm.ballistic.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** A named entry in the designator's target memory. */
public record SavedTarget(String name, BlockPos pos, boolean surface) {
	public static final Codec<SavedTarget> CODEC = RecordCodecBuilder.create(
		instance -> instance.group(
				Codec.STRING.fieldOf("name").forGetter(SavedTarget::name),
				BlockPos.CODEC.fieldOf("pos").forGetter(SavedTarget::pos),
				Codec.BOOL.optionalFieldOf("surface", false).forGetter(SavedTarget::surface)
			)
			.apply(instance, SavedTarget::new)
	);
	public static final StreamCodec<ByteBuf, SavedTarget> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.stringUtf8(32), SavedTarget::name,
		BlockPos.STREAM_CODEC, SavedTarget::pos,
		ByteBufCodecs.BOOL, SavedTarget::surface,
		SavedTarget::new
	);

	public TargetData toTarget() {
		return new TargetData(this.pos, this.surface);
	}
}
