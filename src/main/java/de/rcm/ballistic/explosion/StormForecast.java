package de.rcm.ballistic.explosion;

import de.rcm.ballistic.network.ModNetworking.StormForecastPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * Tells the players in the Overworld when the next thunderstorm will break, a few minutes ahead (Minecraft's
 * weather is decided by timers, so we know), so they can watch it come in over the horizon (the client's
 * StormFront).
 */
public final class StormForecast {
	/** How far ahead the storm shows (ticks): the client's StormFront.LEAD. */
	public static final int LEAD = 9600;
	private static int last = Integer.MIN_VALUE;

	private StormForecast() {
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 40 != 0) {
			return;
		}
		int ticks = forecast(server);
		// on a change, and now and then anyway (the clock drifts, players come and go)
		if (ticks < 0 && last < 0 && server.getTickCount() % 600 != 0) {
			return;
		}
		if (ticks >= 0 && last >= 0 && Math.abs(ticks - last) < 80 && server.getTickCount() % 400 != 0) {
			last = ticks;
			return;
		}
		last = ticks;
		for (ServerPlayer player : server.overworld().players()) {
			ServerPlayNetworking.send(player, new StormForecastPayload(ticks));
		}
	}

	public static void sync(ServerPlayer player) {
		if (player.level() == player.level().getServer().overworld()) {
			ServerPlayNetworking.send(player, new StormForecastPayload(forecast(player.level().getServer())));
		}
	}

	/** In how many ticks the next thunderstorm breaks over the Overworld, if within {@link #LEAD}; else -1. */
	static int forecast(MinecraftServer server) {
		ServerLevel level = server.overworld();
		ServerLevelData data = server.getWorldData().overworldData();
		if (!level.dimensionType().hasSkyLight() || !level.getGameRules().get(GameRules.ADVANCE_WEATHER) || data.getClearWeatherTime() > 0) {
			return -1;
		}
		boolean thunder = data.isThundering();
		boolean rain = data.isRaining();
		if (thunder && rain) {
			return -1; // it is here
		}
		int tT = data.getThunderTime();
		int rT = data.getRainTime();
		int thunderAt = thunder ? 0 : tT;
		int rainAt = rain ? 0 : rT;
		int at = Math.max(thunderAt, rainAt);
		// what is on now must still be on then; what comes on first must not be over again before the other
		if (thunder && tT <= at || rain && rT <= at || !thunder && !rain && Math.abs(thunderAt - rainAt) > 3600) {
			return -1;
		}
		return at <= LEAD ? at : -1;
	}

	/** Brings a thunderstorm in {@code ticks} from now (for the command). */
	public static void schedule(MinecraftServer server, int ticks) {
		server.overworld().setWeatherParameters(0, ticks, false, false);
		last = Integer.MIN_VALUE;
	}
}
