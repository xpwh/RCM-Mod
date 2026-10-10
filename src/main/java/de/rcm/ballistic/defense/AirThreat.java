package de.rcm.ballistic.defense;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Anything in the air that radars track and air defense can shoot at (server side). */
public interface AirThreat {
	enum ThreatClass {
		UNKNOWN("unknown"),
		BALLISTIC("ballistic"),
		CRUISE("cruise"),
		HYPERSONIC("hypersonic"),
		REENTRY("reentry"),
		AIRCRAFT("aircraft");

		public final String key;

		ThreatClass(String key) {
			this.key = key;
		}

		public static ThreatClass byOrdinal(int i) {
			ThreatClass[] all = values();
			return i >= 0 && i < all.length ? all[i] : UNKNOWN;
		}
	}

	Entity asEntity();

	/** True while the object flies freely and can be tracked and engaged. */
	boolean isActiveThreat();

	/** Centre of the body {@code ticksAhead} ticks from now (0 = now). */
	Vec3 aimPoint(int ticksAhead);

	/** Velocity in blocks per tick. */
	Vec3 threatVelocity();

	/** Where it is going to hit the ground. */
	Vec3 predictedImpact();

	/** Estimated ticks until impact. */
	int etaTicks();

	ThreatClass threatClass();

	/** Radar cross-section relative to an ICBM (1.0). */
	double radarCrossSection();

	float killProbability();

	/** Translation key of the object's name. */
	String nameKey();

	int getEngagements();

	void setEngagements(int engagements);

	/** Hit by an interceptor: breaks up in the air without its warhead going off. */
	void destroyByInterceptor(ServerLevel level);
}
