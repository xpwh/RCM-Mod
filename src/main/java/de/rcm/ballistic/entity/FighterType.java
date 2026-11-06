package de.rcm.ballistic.entity;

/**
 * The two flyable fifth-generation fighters, from their published figures (1 block = 1 m, speeds in
 * blocks per tick: 1 b/t = 20 m/s, Mach 1 = 17.15 b/t).
 * <ul>
 *   <li><b>F-22A Raptor</b>: 18.9 m long, 13.6 m span, two F119 engines with 2D thrust-vectoring
 *       nozzles. Supercruises at Mach 1.5 without afterburner, Mach 2 with. M61A2 20 mm, 480 rounds,
 *       some 6000 rounds a minute. Six AIM-120 and two AIM-9 in its bays.</li>
 *   <li><b>F-35A Lightning II</b>: 15.7 m long, 10.7 m span, one F135 engine (round nozzle, the most
 *       powerful fighter engine in service). Mach 1.6 top speed, does not supercruise. GAU-22/A 25 mm,
 *       180 rounds, 3300 a minute. Four AIM-120 inside.</li>
 * </ul>
 * Radar cross section relative to the mod's ICBM (1.0); detection range goes with its fourth root.
 * As asked, the F-35 is the harder of the two to find (in reality the F-22's is the smaller).
 */
public enum FighterType {
	F35("f35", 15.7F, 10.7F, 27.4, 18.0, 0.024, 0.0006, 9.0F, 2.0F, 4.2, 180, 2.75F, 4, 60.0F, 0.88F),
	F22("f22", 18.9F, 13.6F, 34.3, 25.7, 0.027, 0.0015, 9.0F, 2.4F, 3.8, 480, 5.0F, 8, 70.0F, 1.0F);

	public final String id;
	public final float length;
	public final float span;
	/** Top speed with afterburner, and the most the engines manage without it (b/t). */
	public final double maxSpeed;
	public final double dryMaxSpeed;
	/** Full afterburner thrust as an acceleration (b/t per tick; 0.0245 = 1 g). */
	public final double afterburnerThrust;
	public final double radarCrossSection;
	public final float maxG;
	/** Fastest the nose can be swung round, degrees per tick. */
	public final float maxTurn;
	/** Below this it falls out of the sky (b/t). */
	public final double stallSpeed;
	public final int gunRounds;
	/** Cannon rounds per tick. */
	public final float gunRate;
	public final int missiles;
	public final float maxHealth;
	/** Engine sound pitch: the F-35's single big F135 is deeper. */
	public final float enginePitch;

	FighterType(String id, float length, float span, double maxSpeed, double dryMaxSpeed, double afterburnerThrust, double radarCrossSection, float maxG,
		float maxTurn, double stallSpeed, int gunRounds, float gunRate, int missiles, float maxHealth, float enginePitch) {
		this.id = id;
		this.length = length;
		this.span = span;
		this.maxSpeed = maxSpeed;
		this.dryMaxSpeed = dryMaxSpeed;
		this.afterburnerThrust = afterburnerThrust;
		this.radarCrossSection = radarCrossSection;
		this.maxG = maxG;
		this.maxTurn = maxTurn;
		this.stallSpeed = stallSpeed;
		this.gunRounds = gunRounds;
		this.gunRate = gunRate;
		this.missiles = missiles;
		this.maxHealth = maxHealth;
		this.enginePitch = enginePitch;
	}

	/** Drag coefficient: drag = k v^2 balances afterburner thrust at top speed. */
	public double drag() {
		return this.afterburnerThrust / (this.maxSpeed * this.maxSpeed);
	}

	/** Thrust without afterburner: balances drag at the dry top speed. */
	public double dryThrust() {
		return this.drag() * this.dryMaxSpeed * this.dryMaxSpeed;
	}

	public static FighterType byId(int ordinal) {
		FighterType[] all = values();
		return all[Math.floorMod(ordinal, all.length)];
	}
}
