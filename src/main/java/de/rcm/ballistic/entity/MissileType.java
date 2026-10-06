package de.rcm.ballistic.entity;

import net.minecraft.world.item.Rarity;

public enum MissileType {
	/** Conventional high-explosive tactical ballistic missile (Iskander-style). */
	CONVENTIONAL("ballistic_missile", Flight.BALLISTIC, Warhead.HIGH_EXPLOSIVE, Model.TACTICAL, 1.0F, 1.2F, 9.0F, 200, 40, 1.0, 1.0, Rarity.UNCOMMON),
	/** Penetrates deep into the ground before detonating. */
	BUNKER_BUSTER("bunker_buster", Flight.BALLISTIC, Warhead.BUNKER_BUSTER, Model.TACTICAL, 1.0F, 1.2F, 9.0F, 200, 40, 1.15, 1.0, Rarity.UNCOMMON),
	/** Opens above the target and scatters bomblets. */
	CLUSTER("cluster_missile", Flight.BALLISTIC, Warhead.CLUSTER, Model.TACTICAL, 1.0F, 1.2F, 9.0F, 200, 40, 0.9, 1.0, Rarity.RARE),
	/** Fuel-air warhead: gigantic rolling fireball, everything around burns. */
	THERMOBARIC("thermobaric_missile", Flight.BALLISTIC, Warhead.THERMOBARIC, Model.TACTICAL, 1.0F, 1.2F, 9.0F, 200, 40, 1.0, 1.0, Rarity.RARE),
	/** Air-launched-style hypersonic missile: flat, extremely fast trajectory, glowing plasma sheath. */
	HYPERSONIC("hypersonic_missile", Flight.BALLISTIC, Warhead.HYPERSONIC, Model.HYPERSONIC, 1.0F, 1.1F, 8.0F, 200, 30, 0.4, 0.42, Rarity.EPIC),
	/** Low-flying, terrain-following cruise missile with wings and a turbofan (Tomahawk-style). */
	CRUISE("cruise_missile", Flight.CRUISE, Warhead.CRUISE, Model.CRUISE, 1.0F, 0.8F, 6.5F, 160, 25, 1.0, 1.0, Rarity.RARE),
	/** Airburst over the target, showers it with burning incendiary sub-munitions. */
	INCENDIARY("incendiary_missile", Flight.BALLISTIC, Warhead.INCENDIARY, Model.TACTICAL, 1.0F, 1.2F, 9.0F, 200, 40, 1.0, 1.0, Rarity.RARE),
	/** Anti-radiation cruise missile (HARM-style): homes onto the nearest radar or air-defense site near its aim point. */
	ANTI_RADAR("anti_radar_missile", Flight.CRUISE, Warhead.ANTI_RADAR, Model.CRUISE, 0.9F, 0.75F, 5.9F, 160, 25, 1.0, 1.0, Rarity.RARE),
	/** High-altitude nuclear burst: no crater, but an electromagnetic pulse fries electronics, radars and missiles. */
	EMP("emp_missile", Flight.BALLISTIC, Warhead.EMP, Model.TACTICAL, 1.1F, 1.3F, 9.9F, 200, 40, 1.6, 1.0, Rarity.EPIC),
	/** Low-yield nuclear warhead on the tactical airframe: small crater, still a mushroom cloud. */
	TACTICAL_NUKE("tactical_nuke", Flight.BALLISTIC, Warhead.TACTICAL_NUKE, Model.TACTICAL, 1.0F, 1.2F, 9.0F, 240, 40, 1.05, 1.0, Rarity.EPIC),
	/** Nuclear ICBM (Minuteman-style, four first-stage nozzles). */
	NUCLEAR("nuclear_missile", Flight.BALLISTIC, Warhead.NUCLEAR, Model.ICBM, 1.0F, 1.3F, 12.5F, 300, 60, 1.25, 1.0, Rarity.EPIC),
	/** ICBM that releases five independently targeted nuclear re-entry vehicles. */
	MIRV("mirv_missile", Flight.BALLISTIC, Warhead.MIRV, Model.ICBM, 1.1F, 1.4F, 13.7F, 300, 60, 1.45, 1.0, Rarity.EPIC),
	/** Thermonuclear heavy ICBM (Titan-style, two first-stage engines): the biggest bang in the mod. */
	HYDROGEN("hydrogen_bomb", Flight.BALLISTIC, Warhead.HYDROGEN, Model.HEAVY_ICBM, 1.35F, 1.75F, 16.8F, 400, 80, 1.5, 1.0, Rarity.EPIC);

	public enum Flight {
		BALLISTIC,
		CRUISE
	}

	public enum Warhead {
		HIGH_EXPLOSIVE,
		BUNKER_BUSTER,
		CLUSTER,
		THERMOBARIC,
		CRUISE,
		NUCLEAR,
		HYDROGEN,
		/** Not a missile: a single cluster sub-munition. */
		BOMBLET,
		/** Not a detonation: the cluster missile opening up. */
		CLUSTER_RELEASE,
		HYPERSONIC,
		MIRV,
		/** One of the MIRV re-entry vehicles. */
		MIRV_WARHEAD,
		/** Not a detonation: the MIRV bus releasing its warheads. */
		MIRV_RELEASE,
		/** A missile destroyed in the air by an interceptor. */
		INTERCEPT,
		TACTICAL_NUKE,
		EMP,
		INCENDIARY,
		/** Not a detonation: the incendiary missile opening up. */
		INCENDIARY_RELEASE,
		ANTI_RADAR
	}

	public enum Model {
		TACTICAL(9.0F, 0.55F),
		ICBM(12.0F, 0.6F),
		HEAVY_ICBM(12.0F, 0.6F),
		CRUISE(6.0F, 0.3F),
		HYPERSONIC(8.0F, 0.5F);

		public final float length;
		public final float radius;

		Model(float length, float radius) {
			this.length = length;
			this.radius = radius;
		}
	}

	public final String id;
	public final Flight flight;
	public final Warhead warhead;
	public final Model model;
	/** Render scale of the model. */
	public final float scale;
	public final float width;
	public final float height;
	public final float length;
	public final float radius;
	public final int countdownTicks;
	public final int ignitionTicks;
	public final double apexScale;
	/** Flight time multiplier (lower = faster). */
	public final double durationScale;
	public final Rarity rarity;

	MissileType(
		String id, Flight flight, Warhead warhead, Model model, float scale, float width, float height, int countdownTicks, int ignitionTicks, double apexScale,
		double durationScale, Rarity rarity
	) {
		this.id = id;
		this.flight = flight;
		this.warhead = warhead;
		this.model = model;
		this.scale = scale;
		this.width = width;
		this.height = height;
		this.length = model.length * scale;
		this.radius = model.radius * scale;
		this.countdownTicks = countdownTicks;
		this.ignitionTicks = ignitionTicks;
		this.apexScale = apexScale;
		this.durationScale = durationScale;
		this.rarity = rarity;
	}

	public boolean isNuclear() {
		return this.warhead == Warhead.NUCLEAR || this.warhead == Warhead.HYDROGEN || this.warhead == Warhead.MIRV || this.warhead == Warhead.TACTICAL_NUKE
			|| this.warhead == Warhead.EMP;
	}

	/** Strategic missiles are too long for the mobile launcher truck. */
	public boolean fitsOnTruck() {
		return this.model == Model.TACTICAL || this.model == Model.CRUISE || this.model == Model.HYPERSONIC;
	}

	/**
	 * Radar cross-section relative to an ICBM. Detection range scales with the fourth root of it
	 * (radar equation), so small, stealthy cruise missiles are seen much later.
	 */
	public double radarCrossSection() {
		return switch (this.model) {
			case ICBM, HEAVY_ICBM -> 1.0;
			case TACTICAL -> 0.55;
			case HYPERSONIC -> 0.35;
			case CRUISE -> 0.12;
		};
	}

	/** Single-shot kill probability of one interceptor against this missile. */
	public float killProbability() {
		if (this.warhead == Warhead.HYPERSONIC) {
			return 0.3F; // manoeuvring glide body inside a plasma sheath
		}
		return switch (this.model) {
			case CRUISE -> 0.9F;
			case TACTICAL -> 0.8F;
			case ICBM, HEAVY_ICBM -> 0.65F;
			case HYPERSONIC -> 0.3F;
		};
	}

	public static MissileType byId(String id) {
		for (MissileType type : values()) {
			if (type.id.equals(id)) {
				return type;
			}
		}
		return null;
	}

	public boolean isCruise() {
		return this.flight == Flight.CRUISE;
	}
}
