package de.rcm.ballistic.gun;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The server's idea of where smoke grenades have laid a screen, so that soldiers cannot see through it (the
 * smoke itself is drawn on the clients). Each cloud grows to some seven blocks across in the first seconds,
 * hangs while the grenade pours, and thins out over half a minute after it is spent.
 */
public final class SmokeScreens {
	private static final double FULL_RADIUS = 6.5;
	private static final int GROW = 120;
	private static final int LINGER = 600;

	private static final class Cloud {
		final ServerLevel level;
		final GrenadeEntity grenade;
		Vec3 centre;
		final long born;
		long spent = -1L;

		Cloud(ServerLevel level, GrenadeEntity grenade) {
			this.level = level;
			this.grenade = grenade;
			this.centre = grenade.position();
			this.born = level.getGameTime();
		}

		double radius(long now) {
			double grow = Math.min(1.0, (now - this.born) / (double) GROW);
			double fade = this.spent < 0L ? 1.0 : Math.max(0.0, 1.0 - (now - this.spent) / (double) LINGER);
			return FULL_RADIUS * Math.sqrt(grow) * fade;
		}
	}

	private static final List<Cloud> CLOUDS = new ArrayList<>();

	private SmokeScreens() {
	}

	public static void start(ServerLevel level, GrenadeEntity grenade) {
		CLOUDS.add(new Cloud(level, grenade));
	}

	/** The grenade has rolled on: the smoke pours from where it lies now. */
	public static void track(GrenadeEntity grenade) {
		for (Cloud c : CLOUDS) {
			if (c.grenade == grenade) {
				c.centre = c.centre.lerp(grenade.position().add(0, 1.2, 0), 0.05);
			}
		}
	}

	public static void tick(net.minecraft.server.MinecraftServer server) {
		for (Iterator<Cloud> it = CLOUDS.iterator(); it.hasNext(); ) {
			Cloud c = it.next();
			long now = c.level.getGameTime();
			if (c.spent < 0L && c.grenade.isRemoved()) {
				c.spent = now;
			}
			if (c.spent >= 0L && now - c.spent > LINGER) {
				it.remove();
			}
		}
	}

	public static void clear() {
		CLOUDS.clear();
	}

	/** Whether the line from {@code a} to {@code b} runs through more than a couple of blocks of thick smoke. */
	public static boolean blocks(ServerLevel level, Vec3 a, Vec3 b) {
		if (CLOUDS.isEmpty()) {
			return false;
		}
		Vec3 d = b.subtract(a);
		double len = d.length();
		if (len < 1.0E-3) {
			return false;
		}
		Vec3 dir = d.scale(1.0 / len);
		for (Cloud c : CLOUDS) {
			if (c.level != level) {
				continue;
			}
			double r = c.radius(level.getGameTime());
			if (r < 1.5) {
				continue;
			}
			// the chord the line cuts through the cloud's sphere, clipped to the stretch between the two ends
			Vec3 oc = a.subtract(c.centre);
			double half = dir.dot(oc);
			double disc = half * half - (oc.lengthSqr() - r * r);
			if (disc <= 0.0) {
				continue;
			}
			double root = Math.sqrt(disc);
			double t0 = Math.max(0.0, -half - root);
			double t1 = Math.min(len, -half + root);
			if (t1 - t0 > 2.5) {
				return true;
			}
		}
		return false;
	}
}
