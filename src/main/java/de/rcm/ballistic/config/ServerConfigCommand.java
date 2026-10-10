package de.rcm.ballistic.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.rcm.ballistic.network.ModNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /bmserver fehlstarts <0-100>} (or {@code misfires}), {@code aus}/{@code off}: the chance in
 * percent that a launch goes wrong. Operators only.
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
		dispatcher.register(root);
	}

	private static int set(CommandSourceStack source, int chance) {
		ServerConfig.misfireChance = chance;
		ServerConfig.save();
		ModNetworking.broadcastServerConfig(source.getServer());
		source.sendSuccess(() -> Component.translatable("message.ballisticmissiles.misfire_chance", chance), true);
		return 1;
	}
}
