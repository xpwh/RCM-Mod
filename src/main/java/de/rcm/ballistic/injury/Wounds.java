package de.rcm.ballistic.injury;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A player's wounds: the legs and the arms (0 none, 1 light, 2 heavy) and bleeding (0 none, 1 light -
 * it clots by itself -, 2 heavy, 3 arterial: only a tourniquet stops it).
 */
public record Wounds(int leg, int arm, int bleed) {
	public static final Wounds NONE = new Wounds(0, 0, 0);
	public static final int ARTERIAL = 3;

	public static final Codec<Wounds> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("leg").forGetter(Wounds::leg),
		Codec.INT.fieldOf("arm").forGetter(Wounds::arm),
		Codec.INT.fieldOf("bleed").forGetter(Wounds::bleed)).apply(i, Wounds::new));
	public static final StreamCodec<ByteBuf, Wounds> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, Wounds::leg,
		ByteBufCodecs.VAR_INT, Wounds::arm,
		ByteBufCodecs.VAR_INT, Wounds::bleed, Wounds::new);

	public boolean any() {
		return this.leg > 0 || this.arm > 0 || this.bleed > 0;
	}

	public Wounds withLeg(int leg) {
		return new Wounds(Math.max(0, Math.min(2, leg)), this.arm, this.bleed);
	}

	public Wounds withArm(int arm) {
		return new Wounds(this.leg, Math.max(0, Math.min(2, arm)), this.bleed);
	}

	public Wounds withBleed(int bleed) {
		return new Wounds(this.leg, this.arm, Math.max(0, Math.min(ARTERIAL, bleed)));
	}
}
