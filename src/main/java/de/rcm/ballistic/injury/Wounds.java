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
 * {@link #BACKWARD}; kept on the body after), the rounds in the body (0..3: one hole, several, a load of
 * buckshot) and a seed that picks which version of each wound this one is, so no two look the same.
 */
public record Wounds(int leg, int arm, int bleed, int lost, int dying, int armsLost, int head, int collapse, int fall, int torso, int seed, int extra) {
	public static final Wounds NONE = new Wounds(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	/** {@link #extra}: the lower jaw shot away. */
	public static final int JAW = 1;
	/** {@link #extra}: a round gone right through the body (where, how big, which way - see {@link #through}). */
	private static final int HOLE = 1 << 2;
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
		Codec.INT.optionalFieldOf("fall", 0).forGetter(Wounds::fall),
		Codec.INT.optionalFieldOf("torso", 0).forGetter(Wounds::torso),
		Codec.INT.optionalFieldOf("seed", 0).forGetter(Wounds::seed),
		Codec.INT.optionalFieldOf("extra", 0).forGetter(Wounds::extra)).apply(i, Wounds::new));
	public static final StreamCodec<ByteBuf, Wounds> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, Wounds::leg,
		ByteBufCodecs.VAR_INT, Wounds::arm,
		ByteBufCodecs.VAR_INT, Wounds::bleed,
		ByteBufCodecs.VAR_INT, Wounds::lost,
		ByteBufCodecs.VAR_INT, Wounds::dying,
		ByteBufCodecs.VAR_INT, Wounds::armsLost,
		ByteBufCodecs.VAR_INT, Wounds::head,
		ByteBufCodecs.VAR_INT, Wounds::collapse,
		ByteBufCodecs.VAR_INT, Wounds::fall,
		ByteBufCodecs.VAR_INT, Wounds::torso,
		ByteBufCodecs.INT, Wounds::seed,
		ByteBufCodecs.VAR_INT, Wounds::extra, Wounds::new);

	public Wounds(int leg, int arm, int bleed) {
		this(leg, arm, bleed, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}

	public boolean any() {
		return this.leg > 0 || this.arm > 0 || this.bleed > 0 || this.lost > 0 || this.dying > 0 || this.armsLost > 0 || this.head > 0 || this.collapse > 0 || this.fall > 0 || this.torso > 0 || this.extra != 0;
	}

	/** Down on the ground: a leg gone, or dying. */
	public boolean down() {
		return this.lost > 0 || this.dying > 0;
	}

	public Wounds withLeg(int leg) {
		return new Wounds(Math.max(this.lost > 0 ? 2 : 0, Math.min(2, leg)), this.arm, this.bleed, this.lost, this.dying, this.armsLost, this.head, this.collapse, this.fall, this.torso, this.seed, this.extra);
	}

	public Wounds withArm(int arm) {
		return new Wounds(this.leg, Math.max(this.armsLost > 0 ? 2 : 0, Math.min(2, arm)), this.bleed, this.lost, this.dying, this.armsLost, this.head, this.collapse, this.fall, this.torso, this.seed, this.extra);
	}

	public Wounds withBleed(int bleed) {
		return new Wounds(this.leg, this.arm, Math.max(0, Math.min(ARTERIAL, bleed)), this.lost, this.dying, this.armsLost, this.head, this.collapse, this.fall, this.torso, this.seed, this.extra);
	}

	/** A leg ({@link #LEFT} or {@link #RIGHT}) gone as well as any already lost: {@code lost} is a bit set, {@link #BOTH} with both gone. */
	public Wounds withLost(int side) {
		return new Wounds(2, this.arm, ARTERIAL, this.lost | side, this.dying, this.armsLost, this.head, this.collapse, this.fall, this.torso, this.seed, this.extra);
	}

	public boolean lostLeg(int side) {
		return (this.lost & side) != 0;
	}

	public Wounds withDying(int dying) {
		return new Wounds(this.leg, this.arm, this.bleed, this.lost, dying, this.armsLost, this.head, this.collapse, this.fall, this.torso, this.seed, this.extra);
	}

	/** An arm gone below the elbow as well as any already lost. */
	public Wounds withArmLost(int side) {
		return new Wounds(this.leg, 2, ARTERIAL, this.lost, this.dying, this.armsLost | side, this.head, this.collapse, this.fall, this.torso, this.seed, this.extra);
	}

	public boolean lostArm(int side) {
		return (this.armsLost & side) != 0;
	}

	public Wounds withHead(int head) {
		return new Wounds(this.leg, this.arm, Math.max(this.bleed, head == SHATTERED ? ARTERIAL : 2), this.lost, this.dying, this.armsLost,
			Math.max(this.head, head), this.collapse, this.fall, this.torso, this.seed, this.extra);
	}

	/** A graze on the head bandaged: out of sight (a blown-open skull stays what it is). */
	public Wounds headDressed() {
		return this.head == GRAZED ? new Wounds(this.leg, this.arm, this.bleed, this.lost, this.dying, this.armsLost, 0, this.collapse, this.fall, this.torso, this.seed, this.extra) : this;
	}

	/** Shot through the head: on your feet a moment longer, then down, falling {@code fall}. */
	public Wounds withCollapse(int ticks, int fall) {
		return new Wounds(this.leg, this.arm, this.bleed, this.lost, this.dying, this.armsLost, this.head, ticks, fall, this.torso, this.seed, this.extra);
	}

	/** Bleeding out or collapsing: past fighting, past using anything. */
	public boolean incapacitated() {
		return this.dying > 0 || this.collapse > 0;
	}

	public Wounds withTorso(int torso) {
		return new Wounds(this.leg, this.arm, this.bleed, this.lost, this.dying, this.armsLost, this.head, this.collapse, this.fall, Math.min(3, torso), this.seed, this.extra);
	}

	public Wounds withSeed(int seed) {
		return new Wounds(this.leg, this.arm, this.bleed, this.lost, this.dying, this.armsLost, this.head, this.collapse, this.fall, this.torso, seed, this.extra);
	}

	public Wounds withExtra(int extra) {
		return new Wounds(this.leg, this.arm, this.bleed, this.lost, this.dying, this.armsLost, this.head, this.collapse, this.fall, this.torso, this.seed, extra);
	}

	public boolean jaw() {
		return (this.extra & JAW) != 0;
	}

	/** The lower jaw shot away: it bleeds hard. */
	public Wounds withJaw() {
		return this.withExtra(this.extra | JAW).withBleed(Math.max(this.bleed, 2));
	}

	public boolean holed() {
		return (this.extra & HOLE) != 0;
	}

	/** How big the hole right through the body is (1..3: a rifle round, a second through the same, torn wide). */
	public int holeSize() {
		return (this.extra >> 3) & 3;
	}

	/** Where it went in, in the body's own pixels: x -4..4 (+x the left), y 0 (neck) .. 12, half a pixel steps. */
	public float holeX() {
		return ((this.extra >> 5) & 31) * 0.5F - 4.0F;
	}

	public float holeY() {
		return ((this.extra >> 10) & 31) * 0.5F;
	}

	/** Shot from behind: in through the back, out the chest. */
	public boolean holeFromBehind() {
		return (this.extra & (1 << 15)) != 0;
	}

	/** A round right through the body at (x, y): a new hole, or the old one torn bigger. */
	public Wounds through(float x, float y, boolean fromBehind) {
		int size = this.holed() ? Math.min(3, this.holeSize() + 1) : 1;
		int hx = this.holed() ? (this.extra >> 5) & 31 : Math.round((Math.max(-3.0F, Math.min(3.0F, x)) + 4.0F) * 2.0F);
		int hy = this.holed() ? (this.extra >> 10) & 31 : Math.round(Math.max(2.0F, Math.min(10.0F, y)) * 2.0F);
		boolean back = this.holed() ? this.holeFromBehind() : fromBehind;
		int bits = HOLE | size << 3 | hx << 5 | hy << 10 | (back ? 1 << 15 : 0);
		return this.withExtra(this.extra & ~0xFFFC | bits);
	}
}
