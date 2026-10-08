package de.rcm.ballistic.client.effect;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * {@code /volcloud}: shows whether the volumetric clouds are on and how cloudy it is;
 * {@code /volcloud an|aus} (or {@code on|off}) switches them (off, Minecraft's own clouds return);
 * {@code /volcloud <0-100>} (or {@code /volcloud menge <0-100>}) sets how much of a fair-weather sky
 * is clouded, in percent - rain and thunderstorms add to it.
 */
public final class VolCloudCommand {
	private VolCloudCommand() {
	}

	public static void init() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(ClientCommandManager.literal("volcloud")
			.executes(ctx -> status(ctx.getSource()))
			.then(ClientCommandManager.literal("an").executes(ctx -> toggle(ctx.getSource(), true)))
			.then(ClientCommandManager.literal("on").executes(ctx -> toggle(ctx.getSource(), true)))
			.then(ClientCommandManager.literal("aus").executes(ctx -> toggle(ctx.getSource(), false)))
			.then(ClientCommandManager.literal("off").executes(ctx -> toggle(ctx.getSource(), false)))
			.then(ClientCommandManager.argument("menge", IntegerArgumentType.integer(0, 100))
				.executes(ctx -> amount(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "menge"))))
			.then(ClientCommandManager.literal("menge").then(ClientCommandManager.argument("prozent", IntegerArgumentType.integer(0, 100))
				.executes(ctx -> amount(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "prozent")))))
			.then(ClientCommandManager.literal("amount").then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
				.executes(ctx -> amount(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "percent")))))));
	}

	private static int status(FabricClientCommandSource source) {
		source.sendFeedback(Component.translatable(VolumetricClouds.enabled() ? "message.ballisticmissiles.volcloud_status_on" : "message.ballisticmissiles.volcloud_status_off",
			VolumetricClouds.amount()).withStyle(ChatFormatting.AQUA));
		return 1;
	}

	private static int toggle(FabricClientCommandSource source, boolean on) {
		VolumetricClouds.setEnabled(on);
		source.sendFeedback(Component.translatable(on ? "message.ballisticmissiles.volcloud_on" : "message.ballisticmissiles.volcloud_off")
			.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		return 1;
	}

	private static int amount(FabricClientCommandSource source, int percent) {
		VolumetricClouds.setAmount(percent);
		source.sendFeedback(Component.translatable("message.ballisticmissiles.volcloud_amount", percent).withStyle(ChatFormatting.GREEN));
		if (!VolumetricClouds.enabled()) {
			source.sendFeedback(Component.translatable("message.ballisticmissiles.volcloud_hint").withStyle(ChatFormatting.GRAY));
		}
		return 1;
	}
}
