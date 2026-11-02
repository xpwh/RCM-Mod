package de.rcm.ballistic.client.gun;

import de.rcm.ballistic.client.item.RpgClient;
import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.item.RocketLauncherItem;
import de.rcm.ballistic.network.ModNetworking.GunActionPayload;
import de.rcm.ballistic.network.ModNetworking.GunInputPayload;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Other players handling their weapons near you - checking a magazine, looking their rifle over,
 * checking the RPG's round: you hear it from where they stand, on the same timeline they act it out.
 */
final class RemoteGunActions {
	private static final int LONGEST = AkClient.INSPECT_TICKS;

	private record Action(int player, int action, long start) {
	}

	private static final List<Action> ACTIVE = new ArrayList<>();

	private RemoteGunActions() {
	}

	static void init() {
		ClientPlayNetworking.registerGlobalReceiver(GunActionPayload.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			if (mc.level != null) {
				ACTIVE.removeIf(a -> a.player() == payload.player());
				ACTIVE.add(new Action(payload.player(), payload.action(), mc.level.getGameTime()));
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(RemoteGunActions::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			ACTIVE.clear();
			return;
		}
		long now = mc.level.getGameTime();
		for (Iterator<Action> it = ACTIVE.iterator(); it.hasNext(); ) {
			Action a = it.next();
			long t = now - a.start();
			Entity e = mc.level.getEntity(a.player());
			if (t > LONGEST || !(e instanceof Player p)) {
				it.remove();
				continue;
			}
			var stack = p.getMainHandItem();
			switch (a.action()) {
				case GunInputPayload.AK_INSPECT -> {
					if (!(stack.getItem() instanceof AkItem)) {
						it.remove(); // put the rifle away: that ends it
						continue;
					}
					AkClient.inspectSound(t, p.getEyePosition(), AkItem.state(stack).hasMag());
				}
				case GunInputPayload.AK_CHECK -> AkClient.magCheckSound(t, p.getEyePosition());
				case GunInputPayload.RPG_CHECK -> RpgClient.checkSound(t, p.getEyePosition(), RocketLauncherItem.isLoaded(stack));
				default -> it.remove();
			}
		}
	}
}
