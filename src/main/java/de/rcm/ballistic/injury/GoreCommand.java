package de.rcm.ballistic.injury;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /bmgore} - trying out the wounds (operators only), on yourself or on others:
 * <ul>
 *   <li>{@code bein <spieler> links|rechts|beide} - the leg shot off below the knee</li>
 *   <li>{@code sterben <spieler>} - down and bleeding out</li>
 *   <li>{@code heilen <spieler>} - every wound gone</li>
 * </ul>
 */
public final class GoreCommand {
	private GoreCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("bmgore").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("bein")
				.then(Commands.argument("spieler", EntityArgument.players())
					.then(Commands.literal("links").executes(ctx -> leg(ctx, Wounds.LEFT)))
					.then(Commands.literal("rechts").executes(ctx -> leg(ctx, Wounds.RIGHT)))
					.then(Commands.literal("beide").executes(ctx -> leg(ctx, Wounds.BOTH)))))
			.then(Commands.literal("sterben")
				.then(Commands.argument("spieler", EntityArgument.players()).executes(ctx -> {
					Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "spieler");
					players.forEach(Injuries::startDying);
					ctx.getSource().sendSuccess(() -> Component.translatable("commands.ballisticmissiles.gore.dying", players.size()), true);
					return players.size();
				})))
			.then(Commands.literal("heilen")
				.then(Commands.argument("spieler", EntityArgument.players()).executes(ctx -> {
					Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "spieler");
					players.forEach(Injuries::heal);
					ctx.getSource().sendSuccess(() -> Component.translatable("commands.ballisticmissiles.gore.healed", players.size()), true);
					return players.size();
				}))));
	}

	private static int leg(CommandContext<CommandSourceStack> ctx, int side) throws CommandSyntaxException {
		Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "spieler");
		for (ServerPlayer p : players) {
			Injuries.amputate(p, side);
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.ballisticmissiles.gore.leg", players.size()), true);
		return players.size();
	}
}
