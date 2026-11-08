package de.rcm.ballistic.entity;

/**
 * The two flyable fifth-generation fighters, from their published figures.
 * <ul>
 *   <li><b>F-22A Raptor</b>: 18.9 m long, 13.6 m span, two F119 engines with 2D thrust-vectoring
 *       nozzles, thrust-to-weight about 1.1 with afterburner. Supercruises at Mach 1.6-1.8 high up
 *       without afterburner, Mach 2.25 with. M61A2 20 mm, 480 rounds, some 6000 rounds a minute. Six
 *       AIM-120 and two AIM-9 in its bays.</li>
 *   <li><b>F-35A Lightning II</b>: 15.7 m long, 10.7 m span, one F135 engine (round nozzle, the most
 *       powerful fighter engine in service), thrust-to-weight about 0.9. Mach 1.6 top speed, does not
 *       really supercruise. GAU-22/A 25 mm, 180 rounds, 3300 a minute. Four AIM-120 inside.</li>
 * </ul>
 * The flight model (FighterEntity) works in real units - metres per second and g - through a standard
 * atmosphere: thrust falls off and true airspeed rises with height, drag climbs steeply through the
 * sound barrier. The envelope below was fitted so that, as in reality, near the ground both top out a
 * little over Mach 1 with afterburner, and high up the Raptor supercruises past Mach 1.6 and reaches
 * Mach 2.2, the F-35 Mach 1.6. Radar cross section relative to the mod's ICBM (1.0); detection range goes
 * with its fourth root. As asked, the F-35 is the harder of the two to find (in reality the F-22's is the
 * smaller).
 */
public enum FighterType {
	F35("f35", 15.7F, 10.7F, 0.6, 0.9, 5.0E-6, 1.5, 0.8, 0.5, 1.6, 0.0006, 9.0F, 3.3, 180, 2.75F, 4, 60.0F, 0.88F),
	F22("f22", 18.9F, 13.6F, 0.75, 1.1, 3.6E-6, 1.0, 0.6, 0.1, 2.25, 0.0015, 9.0F, 3.0, 480, 5.0F, 8, 70.0F, 1.0F);

	public final String id;
	public final float length;
	public final float span;
	/** Static thrust at sea level, military power and full afterburner, in g (thrust over weight). */
	public final double dryThrust;
	public final double abThrust;
	/** Drag in g per (m/s)^2 at sea-level density, below the transonic rise. */
	public final double dragCoeff;
	/** How much the drag rises through Mach 1 (wave drag, relative). */
	public final double waveDrag;
	/** Thrust lapse with air density (thrust ~ density^lapse), and the ram gain with Mach (1 + ram M^2). */
	public final double lapse;
	public final double ram;
	/** The airframe's limit (inlets, heating): drag rises like a wall above it. */
	public final double machLimit;
	public final double radarCrossSection;
	public final float maxG;
	/** 1 g stall at sea level, clean (true airspeed scales with 1/sqrt(density) up high), b/t real. */
	public final double stallSpeed;
	public final int gunRounds;
	/** Cannon rounds per tick. */
	public final float gunRate;
	public final int missiles;
	public final float maxHealth;
	/** Engine sound pitch: the F-35's single big F135 is deeper. */
	public final float enginePitch;

	FighterType(String id, float length, float span, double dryThrust, double abThrust, double dragCoeff, double waveDrag, double lapse, double ram,
		double machLimit, double radarCrossSection, float maxG, double stallSpeed, int gunRounds, float gunRate, int missiles, float maxHealth,
		float enginePitch) {
		this.id = id;
		this.length = length;
		this.span = span;
		this.dryThrust = dryThrust;
		this.abThrust = abThrust;
		this.dragCoeff = dragCoeff;
		this.waveDrag = waveDrag;
		this.lapse = lapse;
		this.ram = ram;
		this.machLimit = machLimit;
		this.radarCrossSection = radarCrossSection;
		this.maxG = maxG;
		this.stallSpeed = stallSpeed;
		this.gunRounds = gunRounds;
		this.gunRate = gunRate;
		this.missiles = missiles;
		this.maxHealth = maxHealth;
		this.enginePitch = enginePitch;
	}

	/** The fastest it can go anywhere, real blocks per tick (for sanity caps). */
	public double maxSpeed() {
		return this.machLimit * 340.3 / 20.0;
	}

	/** Thrust in g: {@code rpm} 0..1 of the core, {@code burner} 0..1 of the afterburner lit, at {@code mach} and {@code sigma}. */
	public double thrust(double rpm, double burner, double mach, double sigma) {
		double dry = this.dryThrust * Math.pow(rpm, 1.6);
		double t = dry + (this.abThrust - this.dryThrust) * burner;
		return t * Math.pow(sigma, this.lapse) * (1.0 + this.ram * mach * mach);
	}

	/** Parasitic and wave drag in g at true airspeed {@code v} m/s. */
	public double drag(double v, double mach, double sigma) {
		double wave = this.waveDrag * smooth(0.82, 1.08, mach) * (1.0 - 0.4 * smooth(1.15, 2.3, mach));
		double wall = 4.0 * smooth(this.machLimit - 0.05, this.machLimit + 0.3, mach);
		return this.dragCoeff * sigma * v * v * (1.0 + wave + wall);
	}

	static double smooth(double a, double b, double x) {
		double t = Math.max(0.0, Math.min(1.0, (x - a) / (b - a)));
		return t * t * (3.0 - 2.0 * t);
	}

	public static FighterType byId(int ordinal) {
		FighterType[] all = values();
		return all[Math.floorMod(ordinal, all.length)];
	}
}
