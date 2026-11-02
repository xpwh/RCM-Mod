package de.rcm.ballistic.injury;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Dead animals and monsters do not vanish in a puff after a second: the body lies where it fell, on its
 * side, for a minute and a half - with its wounds - and only then is gone. (Players leave their own body,
 * see {@link Corpses}; what does not bleed - skeletons, golems, slimes - goes as before.)
 */
public final class MobCorpses {
	/** How long a dead creature lies there (ticks). */
	public static final int TICKS = 1800;
	/** Never more bodies than this lying about at once (a mob farm would drown in them). */
	private static final int MAX = 80;
	private static final Set<LivingEntity> LYING = Collections.newSetFromMap(new WeakHashMap<>());

	private MobCorpses() {
	}

	/** Whether this body stays lying a while (decided once, as it dies). */
	public static boolean lingers(LivingEntity e) {
		if (e instanceof Player || !Blood.bleeds(e)) {
			return false;
		}
		if (e.level().isClientSide()) {
			return true; // the server decides when it goes
		}
		if (LYING.contains(e)) {
			return true;
		}
		LYING.removeIf(LivingEntity::isRemoved);
		if (LYING.size() >= MAX) {
			return false;
		}
		LYING.add(e);
		return true;
	}

	/** The dead creatures lying about now. */
	public static Set<LivingEntity> lying() {
		return LYING;
	}

	public static void clear() {
		LYING.clear();
	}
}
