package de.rcm.ballistic.explosion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /bmworld} - the world after the blasts (operators only):
 * <ul>
 *   <li>{@code krater reparieren [radius]} - every block the explosions destroyed within the radius
 *       (default 64) put back as it was ({@link CraterLog})</li>
 *   <li>{@code krater info} - how many destroyed blocks are remembered here</li>
 *   <li>{@code neumond an|aus} - the new-moon night event: pitch-black nights ({@link NewMoon})</li>
 *   <li>{@code neumond dunkelheit <1-100>} - how dark it makes them</li>
 *   <li>{@code polarlicht an|aus} - the northern lights every night ({@link Aurora})</li>
 *   <li>{@code polarlicht zufall an|aus} - now and then, by chance, on a rare night</li>
 * </ul>
 */
public final class WorldCommand {
	private WorldCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("bmworld").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("krater")
				.then(Commands.literal("reparieren")
					.executes(ctx -> repair(ctx.getSource(), 64))
					.then(Commands.argument("radius", IntegerArgumentType.integer(1, 1024))
						.executes(ctx -> repair(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
				.then(Commands.literal("info").executes(ctx -> {
					int n = CraterLog.remembered(ctx.getSource().getLevel());
					ctx.getSource().sendSuccess(() -> Component.translatable("commands.ballisticmissiles.world.crater_info", n), false);
					return n;
				})))
			.then(Commands.literal("neumond")
				.then(Commands.literal("an").executes(ctx -> newMoon(ctx.getSource(), true)))
				.then(Commands.literal("aus").executes(ctx -> newMoon(ctx.getSource(), false)))
				.then(Commands.literal("dunkelheit")
					.then(Commands.argument("prozent", IntegerArgumentType.integer(1, 100)).executes(ctx -> {
						int v = IntegerArgumentType.getInteger(ctx, "prozent");
						NewMoon.setDarkness(ctx.getSource().getServer(), v);
						ctx.getSource().sendSuccess(() -> Component.translatable("commands.ballisticmissiles.world.new_moon_darkness", v), true);
						return v;
					}))))
			.then(Commands.literal("polarlicht")
				.then(Commands.literal("an").executes(ctx -> aurora(ctx.getSource(), true)))
				.then(Commands.literal("aus").executes(ctx -> aurora(ctx.getSource(), false)))
				.then(Commands.literal("zufall")
					.then(Commands.literal("an").executes(ctx -> auroraRandom(ctx.getSource(), true)))
					.then(Commands.literal("aus").executes(ctx -> auroraRandom(ctx.getSource(), false))))));
	}

	private static int aurora(CommandSourceStack source, boolean on) {
		Aurora.set(source.getServer(), on);
		source.sendSuccess(() -> Component.translatable(on ? "commands.ballisticmissiles.world.aurora_on" : "commands.ballisticmissiles.world.aurora_off"), true);
		return 1;
	}

	private static int auroraRandom(CommandSourceStack source, boolean on) {
		Aurora.setRandom(source.getServer(), on);
		source.sendSuccess(() -> Component.translatable(on ? "commands.ballisticmissiles.world.aurora_random_on"
			: "commands.ballisticmissiles.world.aurora_random_off"), true);
		return 1;
	}

	private static int newMoon(CommandSourceStack source, boolean on) {
		NewMoon.set(source.getServer(), on);
		source.sendSuccess(() -> Component.translatable(on ? "commands.ballisticmissiles.world.new_moon_on" : "commands.ballisticmissiles.world.new_moon_off"), true);
		return 1;
	}

	private static int repair(CommandSourceStack source, int radius) {
		ServerLevel level = source.getLevel();
		Vec3 at = source.getPosition();
		int n = CraterLog.repair(level, at.x, at.z, radius);
		source.sendSuccess(() -> n == 0 ? Component.translatable("commands.ballisticmissiles.world.crater_none", radius)
			: Component.translatable("commands.ballisticmissiles.world.crater_repair", n, radius), true);
		return n;
	}
}
