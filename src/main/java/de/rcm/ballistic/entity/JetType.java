package de.rcm.ballistic.entity;

/** The aircraft the airstrike radio can call in. */
public enum JetType {
	/** F-22-style stealth fighter: carpet of ten bombs, then a supersonic dash. */
	STRIKE("strike_jet", 60.0, 8.0, 10, 0.05, 180.0, false),
	/** A-10-style ground attacker: low and slow, strafes the target with its 30 mm cannon. */
	WARTHOG("warthog", 70.0, 6.0, 0, 0.6, 420.0, false),
	/** B-2-style stealth bomber: very high, drops one MOAB. */
	SPIRIT("spirit", 130.0, 7.0, 1, 0.02, 260.0, false),
	/** Gunship helicopter (MH-60 style): circles the target low, a door gunner fires a cannon out of the side door. */
	GUNSHIP("gunship", 40.0, 2.2, 0, 0.6, 150.0, true),
	/** MQ-9 Reaper drone used as a kamikaze: flies in fast and dives into the target itself. */
	REAPER("reaper", 95.0, 4.5, 0, 0.3, 55.0, false),
	/** AH-64 Apache: hovers low in front of the target, Hellfires, Hydra rockets and 30 mm chain gun. */
	APACHE("apache", 26.0, 2.4, 0, 0.5, 190.0, true);

	public final String id;
	/** Height of the attack run above the target. */
	public final double runAltitude;
	/** Speed during the attack, blocks per tick. */
	public final double attackSpeed;
	public final int bombs;
	public final double radarCrossSection;
	/**
	 * How much it takes to bring it down: rifle rounds count as they are, blasts several times over
	 * (an RPG hit brings down a helicopter or a drone; the armoured A-10 takes a lot of punishment).
	 */
	public final float maxHealth;
	/** Rotorcraft: shot down, it spins round its rotor mast and drops instead of diving. */
	public final boolean rotorcraft;

	JetType(String id, double runAltitude, double attackSpeed, int bombs, double radarCrossSection, double maxHealth, boolean rotorcraft) {
		this.maxHealth = (float) maxHealth;
		this.rotorcraft = rotorcraft;
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
