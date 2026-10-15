package de.rcm.ballistic.explosion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.network.ModNetworking.NewMoonPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The new-moon night event: no moon, no stars, overcast - the nights go pitch black, as they do far from
 * any town. Only what gives off light can be seen: torches, fires, muzzle flashes, weapon lights and lasers.
 * Switched on and off from the control panel ({@code /bmworld neumond an|aus}); saved with the world and
 * sent to every player.
 * <p>
 * Switching it on plays a cutscene for everyone at once (the client side is {@code NewMoonCutscene}):
 * eyes go to the sky, and if it is still day the day races by to midnight - the server drives the clock
 * for that, sending the time every tick so the sun sweeps smoothly across - then the moon goes out.
 */
public final class NewMoon {
	private static final String FILE = "ballisticmissiles_newmoon.json";
	/** The time-lapse in the cutscene: when it starts and ends (ticks into it), and midnight. */
	public static final int LAPSE_FROM = 20;
	public static final int LAPSE_TO = 110;
	private static final long MIDNIGHT = 18000L;
	private static boolean active;
	/** Server game time the time-lapse began, or -1; the day time it runs from and to. */
	private static long lapseStart = -1L;
	private static long lapseFrom;
	private static long lapseTo;

	private NewMoon() {
	}

	public static boolean active() {
		return active;
	}

	public static void set(MinecraftServer server, boolean on) {
		boolean changed = active != on;
		active = on;
		save(server);
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		if (on && changed && overworld != null) {
			// still day (or dusk/dawn): the cutscene races the clock on to midnight
			long now = overworld.getDayTime();
			long inDay = Math.floorMod(now, 24000L);
			if (inDay < 13000L || inDay >= 23000L) {
				lapseFrom = now;
				lapseTo = now - inDay + MIDNIGHT + (inDay >= 23000L ? 24000L : 0L);
				lapseStart = overworld.getGameTime();
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, new NewMoonPayload(active, changed));
		}
	}

	public static void sync(ServerPlayer player) {
		ServerPlayNetworking.send(player, new NewMoonPayload(active, false));
	}

	/** Every server tick: the cutscene's time-lapse, eased in and out. */
	public static void tick(MinecraftServer server) {
		if (lapseStart < 0L) {
			return;
		}
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		if (overworld == null) {
			lapseStart = -1L;
			return;
		}
		long t = overworld.getGameTime() - lapseStart;
		if (t < LAPSE_FROM) {
			return;
		}
		double x = Math.min(1.0, (t - LAPSE_FROM) / (double) (LAPSE_TO - LAPSE_FROM));
		double f = x * x * (3.0 - 2.0 * x);
		overworld.setDayTime(lapseFrom + Math.round((lapseTo - lapseFrom) * f));
		server.getPlayerList().broadcastAll(new ClientboundSetTimePacket(overworld.getGameTime(), overworld.getDayTime(),
			overworld.getGameRules().get(GameRules.ADVANCE_TIME)), Level.OVERWORLD);
		if (x >= 1.0) {
			lapseStart = -1L;
		}
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
	}

	public static void load(MinecraftServer server) {
		active = false;
		lapseStart = -1L;
		Path path = file(server);
		if (!Files.exists(path)) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			active = o.get("active").getAsBoolean();
		} catch (IOException | RuntimeException e) {
			BallisticMissiles.LOGGER.warn("Could not read new moon state from {}", path, e);
		}
	}

	public static void save(MinecraftServer server) {
		JsonObject o = new JsonObject();
		o.addProperty("active", active);
		Path path = file(server);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BallisticMissiles.LOGGER.warn("Could not save new moon state to {}", path, e);
		}
	}
}
