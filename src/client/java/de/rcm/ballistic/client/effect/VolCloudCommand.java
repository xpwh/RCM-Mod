package de.rcm.ballistic.client.effect;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import de.rcm.ballistic.client.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * {@code /volcloud}: shows whether the volumetric clouds are on and how cloudy it is;
 * {@code /volcloud an|aus} (or {@code on|off}) switches them (off, Minecraft's own clouds return);
 * {@code /volcloud <0-100>} (or {@code /volcloud menge <0-100>}) sets how much of a fair-weather sky
 * is clouded, in percent - rain and thunderstorms add to it; {@code /volcloud qualitaet
 * niedrig|mittel|hoch|ultra} sets the quality, {@code /volcloud schatten an|aus} the cloud shadows and
 * {@code /volcloud strahlen an|aus} the light shafts. Everything is also in the settings screen ({@code /bmconfig}).
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
				.executes(ctx -> amount(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "percent")))))
			.then(quality("qualitaet"))
			.then(quality("quality"))
			.then(toggle("schatten", true))
			.then(toggle("shadows", true))
			.then(toggle("strahlen", false))
			.then(toggle("rays", false))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> quality(String name) {
		var node = ClientCommandManager.literal(name);
		for (String level : new String[] {"niedrig", "mittel", "hoch", "ultra", "low", "medium", "high"}) {
			node.then(ClientCommandManager.literal(level).executes(ctx -> {
				ModConfig.cloudQuality = ModConfig.Quality.parse(level, ModConfig.cloudQuality);
				ModConfig.save();
				ctx.getSource().sendFeedback(Component.translatable("message.ballisticmissiles.volcloud_quality",
					Component.translatable("options.ballisticmissiles.quality." + ModConfig.cloudQuality.name().toLowerCase(java.util.Locale.ROOT)))
					.withStyle(ChatFormatting.GREEN));
				return 1;
			}));
		}
		return node;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, boolean shadows) {
		var node = ClientCommandManager.literal(name);
		for (String word : new String[] {"an", "on", "aus", "off"}) {
			boolean on = word.equals("an") || word.equals("on");
			node.then(ClientCommandManager.literal(word).executes(ctx -> {
				if (shadows) {
					ModConfig.cloudShadows = on;
				} else {
					ModConfig.lightShafts = on;
				}
				ModConfig.save();
				ctx.getSource().sendFeedback(Component.translatable(shadows ? "message.ballisticmissiles.volcloud_shadows" : "message.ballisticmissiles.volcloud_rays",
					Component.translatable(on ? "options.on" : "options.off")).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
				return 1;
			}));
		}
		return node;
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
