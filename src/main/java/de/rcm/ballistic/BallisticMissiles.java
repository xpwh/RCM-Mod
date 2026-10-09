package de.rcm.ballistic;

import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.explosion.DetonationManager;
import de.rcm.ballistic.explosion.RadiationManager;
import de.rcm.ballistic.launch.RemoteLaunch;
import de.rcm.ballistic.network.ModNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BallisticMissiles implements ModInitializer {
	public static final String MOD_ID = "ballisticmissiles";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModRegistry.init();
		ModNetworking.init();
		// charred wood still burns, just not as eagerly as fresh logs
		net.fabricmc.fabric.api.registry.FlammableBlockRegistry.getDefaultInstance().add(ModRegistry.CHARRED_LOG, 3, 4);
		ServerTickEvents.END_WORLD_TICK.register(DetonationManager::tick);
		ServerTickEvents.END_WORLD_TICK.register(RemoteLaunch::tick);
		ServerTickEvents.END_WORLD_TICK.register(RadiationManager::tick);
		ServerTickEvents.END_WORLD_TICK.register(de.rcm.ballistic.explosion.NuclearWinter::tick);
		ServerTickEvents.END_WORLD_TICK.register(de.rcm.ballistic.defense.FarTracker::tick);
		ServerTickEvents.END_WORLD_TICK.register(de.rcm.ballistic.gun.MagazineLanding::tick);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 6000 == 0) {
				RadiationManager.save(server);
				de.rcm.ballistic.explosion.NuclearWinter.save(server);
			}
		});
		de.rcm.ballistic.config.ServerConfig.load();
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
			de.rcm.ballistic.config.ServerConfigCommand.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(RadiationManager::load);
		ServerLifecycleEvents.SERVER_STARTED.register(de.rcm.ballistic.explosion.NuclearWinter::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(de.rcm.ballistic.explosion.NuclearWinter::save);
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
			(handler, sender, server) -> {
				ServerPlayNetworking.send(handler.player, new de.rcm.ballistic.network.ModNetworking.ServerConfigPayload(
					de.rcm.ballistic.config.ServerConfig.misfireChance));
				RadiationManager.sync(handler.player.level(), handler.player);
				de.rcm.ballistic.explosion.NuclearWinter.sync(handler.player);
			});
		net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
			(player, origin, destination) -> RadiationManager.sync(destination, player));
		ServerLifecycleEvents.SERVER_STOPPING.register(RadiationManager::save);
		// each world starts from the saved settings, not from what a server visited before left behind
		ServerLifecycleEvents.SERVER_STARTING.register(server -> de.rcm.ballistic.config.ServerConfig.load());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ThreatTracker.clear();
			DefenseNetwork.clear();
			EmpManager.clear();
			RadiationManager.clear();
			de.rcm.ballistic.bunker.BunkerManager.clear();
			de.rcm.ballistic.explosion.NuclearWinter.clear();
			RemoteLaunch.clear();
			de.rcm.ballistic.explosion.DetonationManager.clear();
			de.rcm.ballistic.gun.MagazineLanding.clear();
			de.rcm.ballistic.entity.FpvDroneEntity.clearPilots();
			de.rcm.ballistic.gun.AkItem.release(null);
			de.rcm.ballistic.gun.GrenadeItem.forget(null);
		});
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			de.rcm.ballistic.gun.AkItem.release(handler.player.getUUID());
			de.rcm.ballistic.gun.GrenadeItem.forget(handler.player.getUUID());
		});
		LOGGER.info("Ballistic Missiles geladen - Startrampen bereit.");
	}
}
