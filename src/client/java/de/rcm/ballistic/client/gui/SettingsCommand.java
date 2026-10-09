package de.rcm.ballistic.client.gui;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** {@code /bmconfig} (or {@code /bmeinstellungen}): opens the settings screen. */
public final class SettingsCommand {
	/** Opened on the next tick: the chat closing after the command would close it again straight away. */
	private static boolean pending;

	private SettingsCommand() {
	}

	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			for (String name : new String[] {"bmconfig", "bmeinstellungen"}) {
				dispatcher.register(ClientCommandManager.literal(name).executes(ctx -> {
					pending = true;
					return 1;
				}));
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (pending && mc.screen == null) {
				pending = false;
				mc.setScreen(new SettingsScreen(null));
			}
		});
	}
}
