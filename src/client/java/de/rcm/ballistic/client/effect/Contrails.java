package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.client.particle.CloudParticle;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Condensation trails high up: a thin bright line a little behind the engines that slowly spreads
 * into a wide, soft band, drifts and twists with the wind and stays in the sky for a minute or more.
 * Puffs are strung along the path at even spacing (so fast missiles don't leave gaps), and a budget
 * keeps the long-living trails from crowding out the explosion particles: when too many are alive,
 * puffs are spaced further apart and made larger instead.
 */
public final class Contrails {
	/** No contrail below this height, fully persistent from {@link #FULL_Y} up. */
	private static final double MIN_Y = 110.0;
	private static final double FULL_Y = 190.0;
	/** Longest puff life, ticks. */
	private static final int MAX_LIFE = 2000;
	/** About this many contrail puffs alive at once before they are thinned out. */
	private static final int BUDGET = 5000;

	private static final class Trail {
		Vec3 last;
		double carry;
		long used;
	}

	private static final Map<Integer, Trail> TRAILS = new HashMap<>();
	private static final int[] EXPIRY = new int[MAX_LIFE + 64];
	private static int live;
	private static long clock;

	private Contrails() {
	}

	/** 0 below the contrail level, 1 high up where every trail persists. */
	public static double altitudeFactor(double y) {
		return Mth.clamp((y - MIN_Y) / (FULL_Y - MIN_Y), 0.0, 1.0);
	}

	/**
	 * Continues the trail {@code key} to {@code now}.
	 *
	 * @param width    width of the fresh trail in blocks
	 * @param strength 0-1, how dense and long-living (usually the altitude factor)
	 */
	public static void trail(int key, Vec3 now, double width, double strength) {
		Trail trail = TRAILS.computeIfAbsent(key, k -> new Trail());
		Vec3 from = trail.last;
		trail.last = now;
		trail.used = clock;
		if (from == null || strength <= 0.02 || from.distanceToSqr(now) > 300.0 * 300.0) {
			trail.carry = 0.0;
			return;
		}
		double crowd = Math.max(1.0, (double) live / BUDGET);
		double spacing = Math.max(1.6, width * 1.3) * crowd;
		double length = from.distanceTo(now);
		double pos = trail.carry;
		int life = (int) (260 + (MAX_LIFE - 260) * strength * strength);
		float grow = (float) Math.sqrt(crowd);
		while (pos <= length) {
			Vec3 p = from.lerp(now, length < 1.0E-6 ? 1.0 : pos / length);
			CloudParticle c = ClientEffects.cloud(false, p.x + ClientEffects.gauss() * width * 0.08, p.y + ClientEffects.gauss() * width * 0.08,
				p.z + ClientEffects.gauss() * width * 0.08, 0, 0, 0);
			if (c != null) {
				int l = life + (int) (ClientEffects.rand() * life * 0.2);
				float start = (float) (width * 0.45) * grow;
				float end = (float) (width * (2.5 + 6.0 * strength)) * grow;
				c.configure(l, start, end, 0xF8F9FA, 0xDCDFE4, (float) (0.5 + 0.3 * strength))
					.physics(0.9F, 0.0F)
					.shade(0.92F + ClientEffects.rand() * 0.1F)
					.turbulence(0.006F);
				live++;
				EXPIRY[(int) ((clock + l) % EXPIRY.length)]++;
			}
			pos += spacing;
		}
		trail.carry = pos - length;
	}

	/** Forgets the trail {@code key} (the next call starts a new one). */
	public static void cut(int key) {
		TRAILS.remove(key);
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			TRAILS.clear();
			live = 0;
			java.util.Arrays.fill(EXPIRY, 0);
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		clock++;
		int slot = (int) (clock % EXPIRY.length);
		live = Math.max(0, live - EXPIRY[slot]);
		EXPIRY[slot] = 0;
		Iterator<Trail> it = TRAILS.values().iterator();
		while (it.hasNext()) {
			if (clock - it.next().used > 40) {
				it.remove();
			}
		}
	}
}
