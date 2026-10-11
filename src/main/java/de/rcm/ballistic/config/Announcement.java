package de.rcm.ballistic.config;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A world setting changed (always in English): announced to everyone on the server, big in the middle of the screen (a title,
 * the setting and its new value; below it who changed it), with a chime - and in the chat, to read again.
 */
public final class Announcement {
	private Announcement() {
	}

	/**
	 * @param setting what was changed (e.g. "Atomwaffen")
	 * @param value   its new value, coloured: green for on/allowed, red for off/forbidden
	 * @param by      who changed it (null: the server itself)
	 */
	public static void setting(MinecraftServer server, Component setting, Component value, String by) {
		Component title = Component.empty().append(setting.copy().withStyle(ChatFormatting.WHITE)).append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
			.append(value);
		Component subtitle = Component.literal("⚠ ").withStyle(ChatFormatting.GOLD)
			.append(by == null ? Component.literal("Server setting changed")
				: Component.literal("Changed by " + by)).withStyle(ChatFormatting.GOLD);
		Component chat = Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal("Server").withStyle(ChatFormatting.GOLD))
			.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY)).append(title)
			.append(Component.literal(by == null ? "" : " (" + by + ")").withStyle(ChatFormatting.GRAY));
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.connection.send(new ClientboundSetTitlesAnimationPacket(8, 60, 20));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			player.connection.send(new ClientboundSetTitleTextPacket(title));
			player.connection.send(new ClientboundSoundPacket(SoundEvents.NOTE_BLOCK_BELL, SoundSource.MASTER, player.getX(), player.getEyeY(),
				player.getZ(), 0.8F, 1.2F, player.getRandom().nextLong()));
			player.sendSystemMessage(chat);
		}
	}

	/** Green "allowed"/"on" or red "forbidden"/"off". */
	public static Component state(boolean on, String onText, String offText) {
		return Component.literal(on ? onText : offText).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD);
	}

	public static Component percent(int value, boolean normal) {
		return Component.literal(value + " %").withStyle(normal ? ChatFormatting.GREEN : ChatFormatting.YELLOW, ChatFormatting.BOLD);
	}
}
