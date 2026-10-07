package de.rcm.ballistic.defense;

import de.rcm.ballistic.entity.JetEntity;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Friend-or-foe for defenses: missiles are engaged no matter who fired them, but aircraft are only
 * engaged when they belong to someone other than the player who built the defense.
 */
public final class DefenseOwner {
	private DefenseOwner() {
	}

	/** Should a defense owned by {@code owner} hold fire on {@code threat}? */
	public static boolean isFriendly(@Nullable UUID owner, AirThreat threat) {
		if (threat instanceof de.rcm.ballistic.entity.RocketEntity rocket) {
			// rockets fired by your own aircraft are not shot down by your own defenses
			return owner != null && owner.equals(rocket.getOwnerUuid());
		}
		if (threat instanceof JetEntity jet) {
			// defenses without a known builder (placed before 1.3) never shoot at aircraft
			return owner == null || owner.equals(jet.getCaller());
		}
		return false;
	}
}
