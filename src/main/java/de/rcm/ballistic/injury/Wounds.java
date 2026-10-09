package de.rcm.ballistic.injury;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A player's wounds: the legs and the arms (0 none, 1 light, 2 heavy), bleeding (0 none, 1 light - it
 * clots by itself -, 2 heavy, 3 arterial: only a tourniquet stops it), the legs lost below the knee and
 * the arms lost below the elbow (bit sets: {@link #LEFT}, {@link #RIGHT}), the head (0 whole,
 * {@link #GRAZED}: a round tore the scalp open to the skull, {@link #SHATTERED}: the skull blown open -
 * dead), while dying the ticks left before the end (0: not dying), and after a shot through the head the
 * ticks left of the collapse - swaying on your feet, then falling - and which way you fall ({@link #FORWARD},
 * {@link #BACKWARD}; kept on the body after).
 */
public record Wounds(int leg, int arm, int bleed, int lost, int dying, int armsLost, int head, int collapse, int fall) {
	public static final Wounds NONE = new Wounds(0, 0, 0, 0, 0, 0, 0, 0, 0);
	public static final int ARTERIAL = 3;
	public static final int LEFT = 1;
	public static final int RIGHT = 2;
	public static final int BOTH = LEFT | RIGHT;
	public static final int GRAZED = 1;
	public static final int SHATTERED = 2;
	public static final int FORWARD = 1;
	public static final int BACKWARD = 2;

	public static final Codec<Wounds> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("leg").forGetter(Wounds::leg),
		Codec.INT.fieldOf("arm").forGetter(Wounds::arm),
		Codec.INT.fieldOf("bleed").forGetter(Wounds::bleed),
		Codec.INT.optionalFieldOf("lost", 0).forGetter(Wounds::lost),
		Codec.INT.optionalFieldOf("dying", 0).forGetter(Wounds::dying),
		Codec.INT.optionalFieldOf("arms_lost", 0).forGetter(Wounds::armsLost),
		Codec.INT.optionalFieldOf("head", 0).forGetter(Wounds::head),
		Codec.INT.optionalFieldOf("collapse", 0).forGetter(Wounds::collapse),
		Codec.INT.optionalFieldOf("fall", 0).forGetter(Wounds::fall)).apply(i, Wounds::new));
	public static final StreamCodec<ByteBuf, Wounds> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, Wounds::leg,
		ByteBufCodecs.VAR_INT, Wounds::arm,
		ByteBufCodecs.VAR_INT, Wounds::bleed,
		ByteBufCodecs.VAR_INT, Wounds::lost,
		ByteBufCodecs.VAR_INT, Wounds::dying,
		ByteBufCodecs.VAR_INT, Wounds::armsLost,
		ByteBufCodecs.VAR_INT, Wounds::head,
		ByteBufCodecs.VAR_INT, Wounds::collapse,
		ByteBufCodecs.VAR_INT, Wounds::fall, Wounds::new);

	public Wounds(int leg, int arm, int bleed) {
		this(leg, arm, bleed, 0, 0, 0, 0, 0, 0);
	}

	public boolean any() {
		return this.leg > 0 || this.arm > 0 || this.bleed > 0 || this.lost > 0 || this.dying > 0 || this.armsLost > 0 || this.head > 0 || this.collapse > 0 || this.fall > 0;
	}

	/** Down on the ground: a leg gone, or dying. */
	public boolean down() {
		return this.lost > 0 || this.dying > 0;
	}

	public Wounds withLeg(int leg) {
		return new Wounds(Math.max(this.lost > 0 ? 2 : 0, Math.min(2, leg)), this.arm, this.bleed, this.lost, this.dying, this.armsLost, this.head, this.collapse, this.fall);
	}

	public Wounds withArm(int arm) {
		return new Wounds(this.leg, Math.max(this.armsLost > 0 ? 2 : 0, Math.min(2, arm)), this.bleed, this.lost, this.dying, this.armsLost, this.head, this.collapse, this.fall);
	}

	public Wounds withBleed(int bleed) {
		return new Wounds(this.leg, this.arm, Math.max(0, Math.min(ARTERIAL, bleed)), this.lost, this.dying, this.armsLost, this.head, this.collapse, this.fall);
	}

	/** A leg ({@link #LEFT} or {@link #RIGHT}) gone as well as any already lost: {@code lost} is a bit set, {@link #BOTH} with both gone. */
	public Wounds withLost(int side) {
		return new Wounds(2, this.arm, ARTERIAL, this.lost | side, this.dying, this.armsLost, this.head, this.collapse, this.fall);
	}

	public boolean lostLeg(int side) {
		return (this.lost & side) != 0;
	}

	public Wounds withDying(int dying) {
		return new Wounds(this.leg, this.arm, this.bleed, this.lost, dying, this.armsLost, this.head, this.collapse, this.fall);
	}

	/** An arm gone below the elbow as well as any already lost. */
	public Wounds withArmLost(int side) {
		return new Wounds(this.leg, 2, ARTERIAL, this.lost, this.dying, this.armsLost | side, this.head, this.collapse, this.fall);
	}

	public boolean lostArm(int side) {
		return (this.armsLost & side) != 0;
	}

	public Wounds withHead(int head) {
		return new Wounds(this.leg, this.arm, Math.max(this.bleed, head == SHATTERED ? ARTERIAL : 2), this.lost, this.dying, this.armsLost,
			Math.max(this.head, head), this.collapse, this.fall);
	}

	/** A graze on the head bandaged: out of sight (a blown-open skull stays what it is). */
	public Wounds headDressed() {
		return this.head == GRAZED ? new Wounds(this.leg, this.arm, this.bleed, this.lost, this.dying, this.armsLost, 0, this.collapse, this.fall) : this;
	}

	/** Shot through the head: on your feet a moment longer, then down, falling {@code fall}. */
	public Wounds withCollapse(int ticks, int fall) {
		return new Wounds(this.leg, this.arm, this.bleed, this.lost, this.dying, this.armsLost, this.head, ticks, fall);
	}

	/** Bleeding out or collapsing: past fighting, past using anything. */
	public boolean incapacitated() {
		return this.dying > 0 || this.collapse > 0;
	}
}
