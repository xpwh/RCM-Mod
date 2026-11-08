package de.rcm.ballistic.explosion;

import de.rcm.ballistic.network.ModNetworking.AshStormPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;

/**
 * Ash storms in the Nether: now and then (every half hour or so of play) a hot gale of ash and embers sweeps
 * through for a few minutes. Out in the open it burns - a little, steadily, now and then setting you alight -
 * under a roof, in a tunnel, you are out of it. The clients draw and hear it (AshStorm).
 */
public final class AshStorms {
	private static final RandomSource R = RandomSource.create();
	/** When the current storm ends (game time of the Nether), or 0. */
	private static long until;
	/** When the next one comes. */
	private static long next;

	private AshStorms() {
	}

	public static void tick(MinecraftServer server) {
		ServerLevel nether = server.getLevel(Level.NETHER);
		if (nether == null) {
			return;
		}
		long now = nether.getGameTime();
		if (!de.rcm.ballistic.config.ServerConfig.nature(de.rcm.ballistic.config.ServerConfig.ASH_STORMS)) {
			if (until > 0L) {
				stop(server);
			}
			next = now + 1200L;
			return;
		}
		if (next == 0L) {
			next = now + 18000 + R.nextInt(24000);
		}
		if (until == 0L && now >= next) {
			start(server, 2400 + R.nextInt(3600));
		} else if (until > 0L && now >= until) {
			stop(server);
		}
		if (until > 0L && now % 40L == 0L && now - (until - length) > 200L) {
			for (ServerPlayer p : nether.players()) {
				burn(nether, p);
			}
		}
	}

	private static long length;

	public static void start(MinecraftServer server, int ticks) {
		ServerLevel nether = server.getLevel(Level.NETHER);
		if (nether == null) {
			return;
		}
		length = ticks;
		until = nether.getGameTime() + ticks;
		syncAll(server);
	}

	public static void stop(MinecraftServer server) {
		until = 0L;
		ServerLevel nether = server.getLevel(Level.NETHER);
		next = (nether == null ? 0L : nether.getGameTime()) + 18000 + R.nextInt(24000);
		syncAll(server);
	}

	public static void clear() {
		until = 0L;
		next = 0L;
	}

	/** Out in the open, the hot ash burns. */
	private static void burn(ServerLevel level, ServerPlayer p) {
		if (p.isCreative() || p.isSpectator() || p.hasEffect(MobEffects.FIRE_RESISTANCE) || p.isInWater() || !exposed(level, p)) {
			return;
		}
		p.hurtServer(level, level.damageSources().onFire(), 1.0F);
		if (R.nextFloat() < 0.15F) {
			p.igniteForSeconds(2.0F);
		}
	}

	/** Nothing solid over your head for a few blocks. */
	public static boolean exposed(Level level, ServerPlayer p) {
		BlockPos.MutableBlockPos m = p.blockPosition().above(2).mutable();
		for (int i = 0; i < 6; i++, m.move(0, 1, 0)) {
			if (level.getBlockState(m).blocksMotion()) {
				return false;
			}
		}
		return true;
	}

	private static int remaining(MinecraftServer server) {
		ServerLevel nether = server.getLevel(Level.NETHER);
		return until == 0L || nether == null ? 0 : (int) Math.max(0L, until - nether.getGameTime());
	}

	private static void syncAll(MinecraftServer server) {
		ServerLevel nether = server.getLevel(Level.NETHER);
		if (nether == null) {
			return;
		}
		for (ServerPlayer p : nether.players()) {
			ServerPlayNetworking.send(p, new AshStormPayload(remaining(server)));
		}
	}

	public static void sync(ServerPlayer player) {
		ServerPlayNetworking.send(player, new AshStormPayload(player.level().dimension() == Level.NETHER ? remaining(player.level().getServer()) : 0));
	}
}
