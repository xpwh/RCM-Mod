package de.rcm.ballistic.launch;

import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Who a launcher belongs to. Silos, missiles on the pad, launcher trucks and the orbital uplink only
 * take orders from their owner (or an operator) - nobody else can fire, abort or unload them, in
 * person or by remote. One that has no owner yet (built before owners existed) becomes the property
 * of the first player who works it.
 */
public final class Ownership {
	public interface Owned {
		@Nullable UUID getOwner();

		void setOwner(@Nullable UUID owner);
	}

	private Ownership() {
	}

	/** Whether {@code player} may work {@code device} (claiming it if it has no owner yet). */
	public static boolean claim(ServerPlayer player, Owned device) {
		if (device.getOwner() == null) {
			device.setOwner(player.getUUID());
			return true;
		}
		return de.rcm.ballistic.network.ModNetworking.mayUse(player, device.getOwner());
	}

	/** Like {@link #claim}, but tells the player off when it is not theirs; true when refused. */
	public static boolean refuse(ServerPlayer player, Owned device) {
		if (claim(player, device)) {
			return false;
		}
		player.displayClientMessage(notYours(), true);
		return true;
	}

	/** For a remote order, which may arrive when the player who sent it is offline. */
	public static boolean allows(ServerLevel level, UUID playerId, Owned device) {
		UUID owner = device.getOwner();
		if (owner == null || owner.equals(playerId)) {
			return true;
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
		return player != null && de.rcm.ballistic.network.ModNetworking.mayConfigure(player);
	}

	public static Component notYours() {
		return Component.translatable("message.ballisticmissiles.not_yours").withStyle(ChatFormatting.RED);
	}
}
