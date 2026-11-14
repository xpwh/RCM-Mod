package de.rcm.ballistic.gun;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * How steady a shooter's hands are, as the server sees it: shot at or shelled a moment ago, hurt, bleeding, frozen
 * stiff - the more of it, the more a reload under pressure goes wrong: the magazine catches the lip of the well and
 * has to be put in again, or slips out of the fingers altogether and drops.
 */
public final class Nerves {
	private static final Map<UUID, Long> THREAT = new HashMap<>();
	private static final RandomSource R = RandomSource.create();

	private Nerves() {
	}

	/** Something dangerous at {@code at}: everyone within {@code radius} (but the one doing it) gets a fright. */
	public static void threat(ServerLevel level, Vec3 at, double radius, ServerPlayer except) {
		long now = level.getGameTime();
		for (ServerPlayer p : level.players()) {
			if (p != except && p.distanceToSqr(at) < radius * radius) {
				THREAT.put(p.getUUID(), now);
			}
		}
	}

	/** Hurt: that rattles you too. */
	public static void hurt(ServerPlayer p) {
		THREAT.put(p.getUUID(), p.level().getGameTime());
	}

	/** 0 calm .. 1 badly rattled. */
	public static float level(ServerPlayer p) {
		Long last = THREAT.get(p.getUUID());
		float fear = last == null ? 0.0F : Mth.clamp(1.0F - (p.level().getGameTime() - last) / 240.0F, 0.0F, 1.0F) * 0.7F;
		float cold = Mth.clamp((-de.rcm.ballistic.injury.BodyHeat.body(p) - 0.3F) / 0.6F, 0.0F, 1.0F) * 0.6F;
		float hurt = p.getHealth() / Math.max(1.0F, p.getMaxHealth()) < 0.4F ? 0.3F : 0.0F;
		float bleeding = de.rcm.ballistic.injury.Injuries.get(p).bleed() > 0 ? 0.25F : 0.0F;
		return Mth.clamp(fear + cold + hurt + bleeding, 0.0F, 1.0F);
	}

	/** How this tactical reload goes, by how rattled the shooter is. */
	public static int reloadVariant(ServerPlayer p) {
		float n = level(p);
		if (n < 0.2F || p.getAbilities().instabuild) {
			return GunState.CLEAN;
		}
		float f = R.nextFloat();
		if (f < 0.22F * n * n) {
			return GunState.DROPPED;
		}
		return f < 0.6F * n ? GunState.FUMBLE : GunState.CLEAN;
	}

	public static void clear() {
		THREAT.clear();
	}
}
