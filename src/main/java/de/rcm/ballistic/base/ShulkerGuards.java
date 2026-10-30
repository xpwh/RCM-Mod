package de.rcm.ballistic.base;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * The shulkers of the End cities stand guard like the soldiers of the bases: the first one that sees you raises
 * the alarm - a shrill call through the tower - and every shulker within thirty blocks turns on you and opens
 * fire.
 */
public final class ShulkerGuards {
	/** When each player last set off an alarm (game time), so it is not raised every second. */
	private static final Map<UUID, Long> ALARMED = new HashMap<>();

	private ShulkerGuards() {
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) {
			return;
		}
		long now = end.getGameTime();
		for (ServerPlayer p : end.players()) {
			if (p.isCreative() || p.isSpectator() || p.isInvisible()) {
				continue;
			}
			List<Shulker> near = end.getEntitiesOfClass(Shulker.class, p.getBoundingBox().inflate(20.0));
			Shulker spotter = null;
			for (Shulker s : near) {
				if (s.getTarget() == p || s.hasLineOfSight(p)) {
					spotter = s;
					break;
				}
			}
			if (spotter == null) {
				continue;
			}
			Long last = ALARMED.get(p.getUUID());
			boolean fresh = last == null || now - last > 400L;
			ALARMED.put(p.getUUID(), now);
			// the whole tower wakes: every shulker in reach turns on the intruder
			AABB reach = spotter.getBoundingBox().inflate(30.0);
			for (Shulker s : end.getEntitiesOfClass(Shulker.class, reach)) {
				if (s.getTarget() == null && s.distanceToSqr(p) < 40.0 * 40.0) {
					s.setTarget(p);
				}
			}
			if (fresh) {
				end.playSound(null, spotter.getX(), spotter.getY(), spotter.getZ(), SoundEvents.SHULKER_AMBIENT, SoundSource.HOSTILE, 3.0F, 1.6F);
				end.playSound(null, spotter.getX(), spotter.getY(), spotter.getZ(), SoundEvents.SHULKER_HURT, SoundSource.HOSTILE, 2.0F, 0.6F);
			}
		}
	}

	public static void clear() {
		ALARMED.clear();
	}
}
