package de.rcm.ballistic.base;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * {@code /bmfinden <ort>} (also {@code /bmlocate}): the nearest of the mod's own places - the city, a military base,
 * the carrier, the secret lab ... - with the names offered as you type (vanilla's /locate only offers what the client
 * knows, and that is the tags). Searches further than /locate does: the big ones are rare. The answer gives the
 * distance and coordinates you can click to teleport.
 */
public final class FindCommand {
	private static final String[] PLACES = {"city", "military_base", "checkpoint", "sam_site", "weapons_cache", "aircraft_carrier", "war_zone",
		"crash_site", "submarine_pen", "secret_lab", "border_trenches"};
	/** How far to look, in chunks (/locate looks 100). */
	private static final int RADIUS = 250;

	private FindCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		for (String name : new String[] {"bmfinden", "bmlocate"}) {
			dispatcher.register(Commands.literal(name).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("ort", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PLACES, builder))
					.executes(ctx -> find(ctx.getSource(), StringArgumentType.getString(ctx, "ort")))));
		}
	}

	private static int find(CommandSourceStack source, String place) {
		ServerLevel level = source.getLevel();
		var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		var holder = registry.get(ResourceKey.create(Registries.STRUCTURE, de.rcm.ballistic.BallisticMissiles.id(place)));
		if (holder.isEmpty()) {
			source.sendFailure(Component.translatable("message.ballisticmissiles.find_unknown", place));
			return 0;
		}
		BlockPos from = BlockPos.containing(source.getPosition());
		Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
			.findNearestMapStructure(level, HolderSet.direct(holder.get()), from, RADIUS, false);
		if (found == null) {
			source.sendFailure(Component.translatable("message.ballisticmissiles.find_none", place, RADIUS * 16));
			return 0;
		}
		BlockPos at = found.getFirst();
		int distance = (int) Math.round(Math.sqrt(from.distSqr(new BlockPos(at.getX(), from.getY(), at.getZ()))));
		Component coords = Component.literal("[" + at.getX() + ", ~, " + at.getZ() + "]").withStyle(s -> s.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + at.getX() + " ~ " + at.getZ()))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.coordinates.tooltip"))));
		source.sendSuccess(() -> Component.translatable("message.ballisticmissiles.find_found", place, coords, distance), false);
		return distance;
	}
}
