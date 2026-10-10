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
 *   <li>{@code arm <spieler> links|rechts|beide} - the arm shot off below the elbow</li>
 *   <li>{@code kopf <spieler> streifschuss|toedlich} - a round across the scalp, or the skull blown open (dead)</li>
 *   <li>{@code kiefer <spieler>} - the lower jaw shot away</li>
 *   <li>{@code durchschuss <spieler>} - a rifle round right through the chest</li>
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
			.then(Commands.literal("arm")
				.then(Commands.argument("spieler", EntityArgument.players())
					.then(Commands.literal("links").executes(ctx -> arm(ctx, Wounds.LEFT)))
					.then(Commands.literal("rechts").executes(ctx -> arm(ctx, Wounds.RIGHT)))
					.then(Commands.literal("beide").executes(ctx -> arm(ctx, Wounds.BOTH)))))
			.then(Commands.literal("kopf")
				.then(Commands.argument("spieler", EntityArgument.players())
					.then(Commands.literal("streifschuss").executes(ctx -> head(ctx, false)))
					.then(Commands.literal("toedlich").executes(ctx -> head(ctx, true)))))
			.then(Commands.literal("kiefer")
				.then(Commands.argument("spieler", EntityArgument.players()).executes(ctx -> {
					Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "spieler");
					players.forEach(p -> Injuries.jaw(p, p.getLookAngle().scale(-1.0)));
					ctx.getSource().sendSuccess(() -> Component.translatable("commands.ballisticmissiles.gore.jaw", players.size()), true);
					return players.size();
				})))
			.then(Commands.literal("durchschuss")
				.then(Commands.argument("spieler", EntityArgument.players()).executes(ctx -> {
					Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "spieler");
					for (ServerPlayer p : players) {
						// from in front, into the chest a little off the middle
						net.minecraft.world.phys.Vec3 line = p.getLookAngle().multiply(1, 0, 1).normalize().scale(-1.0);
						net.minecraft.world.phys.Vec3 at = p.position().add(0, p.getBbHeight() * 0.62, 0).subtract(line.scale(0.3));
						Injuries.set(p, Injuries.get(p).withTorso(Injuries.get(p).torso() + 1).withBleed(Math.max(2, Injuries.get(p).bleed())));
						Injuries.through(p, at, line);
					}
					ctx.getSource().sendSuccess(() -> Component.translatable("commands.ballisticmissiles.gore.through", players.size()), true);
					return players.size();
				})))
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

	private static int arm(CommandContext<CommandSourceStack> ctx, int side) throws CommandSyntaxException {
		Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "spieler");
		for (ServerPlayer p : players) {
			Injuries.amputateArm(p, side);
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.ballisticmissiles.gore.arm", players.size()), true);
		return players.size();
	}

	private static int head(CommandContext<CommandSourceStack> ctx, boolean lethal) throws CommandSyntaxException {
		Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "spieler");
		for (ServerPlayer p : players) {
			Injuries.headTest(p, lethal);
		}
		ctx.getSource().sendSuccess(() -> Component.translatable(lethal ? "commands.ballisticmissiles.gore.head_lethal" : "commands.ballisticmissiles.gore.head",
			players.size()), true);
		return players.size();
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
