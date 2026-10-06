package de.rcm.ballistic.entity;

/** The aircraft the airstrike radio can call in. */
public enum JetType {
	/** F-22-style stealth fighter: carpet of ten bombs, then a supersonic dash. */
	STRIKE("strike_jet", 60.0, 8.0, 10, 0.05),
	/** A-10-style ground attacker: low and slow, strafes the target with its 30 mm cannon. */
	WARTHOG("warthog", 28.0, 6.0, 0, 0.6),
	/** B-2-style stealth bomber: very high, drops one MOAB. */
	SPIRIT("spirit", 130.0, 7.0, 1, 0.02);

	public final String id;
	/** Height of the attack run above the target. */
	public final double runAltitude;
	/** Speed during the attack, blocks per tick. */
	public final double attackSpeed;
	public final int bombs;
	public final double radarCrossSection;

	JetType(String id, double runAltitude, double attackSpeed, int bombs, double radarCrossSection) {
		this.id = id;
		this.runAltitude = runAltitude;
		this.attackSpeed = attackSpeed;
		this.bombs = bombs;
		this.radarCrossSection = radarCrossSection;
	}

	public static JetType byOrdinal(int i) {
		JetType[] all = values();
		return i >= 0 && i < all.length ? all[i] : STRIKE;
	}
}
