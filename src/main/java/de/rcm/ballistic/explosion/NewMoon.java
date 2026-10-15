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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The new-moon night event: no moon, no stars, overcast - the nights go pitch black, as they do far from
 * any town. Only what gives off light can be seen: torches, fires, muzzle flashes, weapon lights and lasers.
 * Switched on and off from the control panel ({@code /bmworld neumond an|aus}); saved with the world and
 * sent to every player.
 */
public final class NewMoon {
	private static final String FILE = "ballisticmissiles_newmoon.json";
	private static boolean active;

	private NewMoon() {
	}

	public static boolean active() {
		return active;
	}

	public static void set(MinecraftServer server, boolean on) {
		active = on;
		save(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sync(player);
		}
	}

	public static void sync(ServerPlayer player) {
		ServerPlayNetworking.send(player, new NewMoonPayload(active));
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
	}

	public static void load(MinecraftServer server) {
		active = false;
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
