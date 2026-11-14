package de.rcm.ballistic.explosion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.network.ModNetworking.AuroraPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The northern lights, a rare night event: green curtains with red and violet tops waving over the northern
 * sky. Either switched on for good from the control panel ({@code /bmworld polarlicht an|aus}), or left to
 * chance ({@code /bmworld polarlicht zufall an|aus}, on by default): then each nightfall there is a
 * {@value #CHANCE_PERCENT} % chance they come out, until the morning. Saved with the world and sent to every
 * player (the sky itself is drawn on the client, {@code NightSky}).
 */
public final class Aurora {
	private static final String FILE = "ballisticmissiles_aurora.json";
	private static final int CHANCE_PERCENT = 8;
	private static final long DUSK = 12500L;
	private static boolean forced;
	private static boolean random = true;
	/** Chance struck at nightfall: they are out tonight. */
	private static boolean tonight;
	private static long lastDayTime = -1L;

	private Aurora() {
	}

	public static boolean active() {
		return forced || tonight;
	}

	public static void set(MinecraftServer server, boolean on) {
		forced = on;
		if (!on) {
			tonight = false;
		}
		save(server);
		broadcast(server);
	}

	public static void setRandom(MinecraftServer server, boolean on) {
		random = on;
		if (!on) {
			tonight = false;
		}
		save(server);
		broadcast(server);
	}

	public static void sync(ServerPlayer player) {
		ServerPlayNetworking.send(player, new AuroraPayload(active(), forced, random));
	}

	private static void broadcast(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sync(player);
		}
	}

	/** Once a second: nightfall rolls the dice, daybreak ends a chance night. */
	public static void tick(MinecraftServer server) {
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		if (overworld == null || overworld.getGameTime() % 20L != 0L) {
			return;
		}
		long day = Math.floorMod(overworld.getDayTime(), 24000L);
		long last = lastDayTime;
		lastDayTime = day;
		if (last < 0L) {
			return;
		}
		boolean before = active();
		if (last < DUSK && day >= DUSK) {
			tonight = random && overworld.getRandom().nextInt(100) < CHANCE_PERCENT;
		} else if (day < last && tonight) {
			tonight = false; // the day has come round
		} else if (day >= 23500L && tonight) {
			tonight = false;
		}
		if (before != active()) {
			save(server);
			broadcast(server);
		}
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
	}

	public static void load(MinecraftServer server) {
		forced = false;
		random = true;
		tonight = false;
		lastDayTime = -1L;
		Path path = file(server);
		if (!Files.exists(path)) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			forced = o.has("forced") && o.get("forced").getAsBoolean();
			random = !o.has("random") || o.get("random").getAsBoolean();
			tonight = o.has("tonight") && o.get("tonight").getAsBoolean();
		} catch (IOException | RuntimeException e) {
			BallisticMissiles.LOGGER.warn("Could not read aurora state from {}", path, e);
		}
	}

	public static void save(MinecraftServer server) {
		JsonObject o = new JsonObject();
		o.addProperty("forced", forced);
		o.addProperty("random", random);
		o.addProperty("tonight", tonight);
		Path path = file(server);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BallisticMissiles.LOGGER.warn("Could not save aurora state to {}", path, e);
		}
	}
}
