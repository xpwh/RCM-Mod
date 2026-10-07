package de.rcm.ballistic.entity;

/** The aircraft the airstrike radio can call in. */
public enum JetType {
	/** F-22-style stealth fighter: carpet of ten bombs, then a supersonic dash. */
	STRIKE("strike_jet", 60.0, 8.0, 10, 0.05),
	/** A-10-style ground attacker: low and slow, strafes the target with its 30 mm cannon. */
	WARTHOG("warthog", 70.0, 6.0, 0, 0.6),
	/** B-2-style stealth bomber: very high, drops one MOAB. */
	SPIRIT("spirit", 130.0, 7.0, 1, 0.02),
	/** Gunship helicopter (MH-60 style): circles the target low, a door gunner fires a cannon out of the side door. */
	GUNSHIP("gunship", 40.0, 2.2, 0, 0.6),
	/** MQ-9 Reaper drone: circles fast, fires four Hellfires, then dives into the target itself. */
	REAPER("reaper", 95.0, 4.5, 0, 0.3),
	/** AH-64 Apache: hovers low in front of the target, Hellfires, Hydra rockets and 30 mm chain gun. */
	APACHE("apache", 26.0, 2.4, 0, 0.5);

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
