package de.rcm.ballistic;

import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.explosion.DetonationManager;
import de.rcm.ballistic.launch.RemoteLaunch;
import de.rcm.ballistic.network.ModNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
		ServerTickEvents.END_WORLD_TICK.register(DetonationManager::tick);
		ServerTickEvents.END_WORLD_TICK.register(RemoteLaunch::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ThreatTracker.clear();
			DefenseNetwork.clear();
			EmpManager.clear();
			RemoteLaunch.clear();
		});
		LOGGER.info("Ballistic Missiles geladen - Startrampen bereit.");
	}
}
