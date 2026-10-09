package de.rcm.ballistic.injury;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A player's wounds: the legs and the arms (0 none, 1 light, 2 heavy), bleeding (0 none, 1 light - it
 * clots by itself -, 2 heavy, 3 arterial: only a tourniquet stops it), a leg lost (0 none, 1 left,
 * 2 right: shot away below the knee) and, while dying, the ticks left before the end (0: not dying).
 */
public record Wounds(int leg, int arm, int bleed, int lost, int dying) {
	public static final Wounds NONE = new Wounds(0, 0, 0, 0, 0);
	public static final int ARTERIAL = 3;
	public static final int LEFT = 1;
	public static final int RIGHT = 2;
	public static final int BOTH = LEFT | RIGHT;

	public static final Codec<Wounds> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("leg").forGetter(Wounds::leg),
		Codec.INT.fieldOf("arm").forGetter(Wounds::arm),
		Codec.INT.fieldOf("bleed").forGetter(Wounds::bleed),
		Codec.INT.optionalFieldOf("lost", 0).forGetter(Wounds::lost),
		Codec.INT.optionalFieldOf("dying", 0).forGetter(Wounds::dying)).apply(i, Wounds::new));
	public static final StreamCodec<ByteBuf, Wounds> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, Wounds::leg,
		ByteBufCodecs.VAR_INT, Wounds::arm,
		ByteBufCodecs.VAR_INT, Wounds::bleed,
		ByteBufCodecs.VAR_INT, Wounds::lost,
		ByteBufCodecs.VAR_INT, Wounds::dying, Wounds::new);

	public Wounds(int leg, int arm, int bleed) {
		this(leg, arm, bleed, 0, 0);
	}

	public boolean any() {
		return this.leg > 0 || this.arm > 0 || this.bleed > 0 || this.lost > 0 || this.dying > 0;
	}

	/** Down on the ground: the leg gone, or dying. */
	public boolean down() {
		return this.lost > 0 || this.dying > 0;
	}

	public Wounds withLeg(int leg) {
		return new Wounds(Math.max(this.lost > 0 ? 2 : 0, Math.min(2, leg)), this.arm, this.bleed, this.lost, this.dying);
	}

	public Wounds withArm(int arm) {
		return new Wounds(this.leg, Math.max(0, Math.min(2, arm)), this.bleed, this.lost, this.dying);
	}

	public Wounds withBleed(int bleed) {
		return new Wounds(this.leg, this.arm, Math.max(0, Math.min(ARTERIAL, bleed)), this.lost, this.dying);
	}

	/** A leg ({@link #LEFT} or {@link #RIGHT}) gone as well as any already lost: {@code lost} is a bit set, {@link #BOTH} with both gone. */
	public Wounds withLost(int side) {
		return new Wounds(2, this.arm, ARTERIAL, this.lost | side, this.dying);
	}

	public boolean lostLeg(int side) {
		return (this.lost & side) != 0;
	}

	public Wounds withDying(int dying) {
		return new Wounds(this.leg, this.arm, this.bleed, this.lost, dying);
	}
}
