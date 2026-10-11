package de.rcm.ballistic.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.rcm.ballistic.network.ModNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * The world settings (operators only):
 * <ul>
 *   <li>{@code /bmserver fehlstarts <0-100>|aus} (or {@code misfires}) - the chance in percent that a launch goes wrong</li>
 *   <li>{@code /bmserver gore an|aus} - gore allowed on this server, or forbidden for everyone</li>
 *   <li>{@code /bmserver nukes erlauben|verbieten} - nuclear and antimatter weapons</li>
 *   <li>{@code /bmgun schaden <0-1000>} - the guns' damage, percent of normal</li>
 * </ul>
 */
public final class ServerConfigCommand {
	private ServerConfigCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var root = Commands.literal("bmserver").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
		for (String name : new String[] {"fehlstarts", "misfires"}) {
			root.then(Commands.literal(name)
				.executes(ctx -> {
					ctx.getSource().sendSuccess(() -> Component.translatable("message.ballisticmissiles.misfire_chance", ServerConfig.misfireChance), false);
					return ServerConfig.misfireChance;
				})
				.then(Commands.literal("aus").executes(ctx -> set(ctx.getSource(), 0)))
				.then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), 0)))
				.then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
					.executes(ctx -> set(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "percent")))));
		}
		root.then(Commands.literal("gore")
			.executes(ctx -> report(ctx.getSource()))
			.then(Commands.literal("an").executes(ctx -> gore(ctx.getSource(), true)))
			.then(Commands.literal("aus").executes(ctx -> gore(ctx.getSource(), false))));
		root.then(Commands.literal("nukes")
			.executes(ctx -> report(ctx.getSource()))
			.then(Commands.literal("erlauben").executes(ctx -> nukes(ctx.getSource(), true)))
			.then(Commands.literal("verbieten").executes(ctx -> nukes(ctx.getSource(), false))));
		dispatcher.register(root);
		dispatcher.register(Commands.literal("bmgun").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("schaden")
				.executes(ctx -> report(ctx.getSource()))
				.then(Commands.argument("prozent", IntegerArgumentType.integer(0, 1000)).executes(ctx -> {
					ServerConfig.gunDamage = IntegerArgumentType.getInteger(ctx, "prozent");
					return changed(ctx.getSource());
				}))));
	}

	private static int gore(CommandSourceStack source, boolean on) {
		ServerConfig.gore = on;
		return changed(source);
	}

	private static int nukes(CommandSourceStack source, boolean on) {
		ServerConfig.nukes = on;
		return changed(source);
	}

	private static int changed(CommandSourceStack source) {
		ServerConfig.save();
		ModNetworking.broadcastServerConfig(source.getServer());
		return report(source);
	}

	/** All world settings at once, as they are now. */
	private static int report(CommandSourceStack source) {
		source.sendSuccess(() -> Component.translatable("message.ballisticmissiles.server_settings", ServerConfig.misfireChance, ServerConfig.gunDamage,
			Component.translatable(ServerConfig.gore ? "options.on" : "options.off"),
			Component.translatable(ServerConfig.nukes ? "message.ballisticmissiles.allowed" : "message.ballisticmissiles.forbidden")), true);
		return 1;
	}

	private static int set(CommandSourceStack source, int chance) {
		ServerConfig.misfireChance = chance;
		ServerConfig.save();
		ModNetworking.broadcastServerConfig(source.getServer());
		source.sendSuccess(() -> Component.translatable("message.ballisticmissiles.misfire_chance", chance), true);
		return 1;
	}
}
