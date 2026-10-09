package de.rcm.ballistic.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What soldiers can hear. Anything loud in the world reports itself here - a rifle shot, an RPG, an
 * explosion, someone running - with how far it carries, and every soldier within that range hears it,
 * the louder the nearer. Sight is each soldier's own business ({@link SoldierEntity}).
 */
public final class Senses {
	public static final int FOOTSTEP = 0;
	public static final int GUNSHOT = 1;
	public static final int EXPLOSION = 2;
	public static final int BULLET_PASS = 3;
	public static final int IMPACT = 4;

	/** How far each kind of noise carries, blocks. */
	public static final double GUNSHOT_RANGE = 160.0;
	public static final double RPG_RANGE = 220.0;
	public static final double EXPLOSION_RANGE = 320.0;

	private Senses() {
	}

	/** A noise at {@code at} that can be heard up to {@code range} blocks away; {@code source} made it, if anyone did. */
	public static void noise(ServerLevel level, Vec3 at, double range, int kind, @Nullable Entity source) {
		for (SoldierEntity soldier : level.getEntitiesOfClass(SoldierEntity.class, new AABB(at, at).inflate(range))) {
			if (soldier == source) {
				continue;
			}
			double d = soldier.getEyePosition().distanceTo(at);
			if (d <= range) {
				soldier.hear(at, kind, (float) (1.0 - d / range), source);
			}
		}
	}

	public static String name(int kind) {
		return switch (kind) {
			case FOOTSTEP -> "Schritte";
			case GUNSHOT -> "Schuss";
			case EXPLOSION -> "Explosion";
			case BULLET_PASS -> "Kugel vorbei";
			case IMPACT -> "Einschlag";
			default -> "?";
		};
	}
}
