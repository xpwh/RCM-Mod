package de.rcm.ballistic.ai;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.rcm.ballistic.ModRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /bmai} - the AI test bench (operators only):
 * <ul>
 *   <li>{@code spawn [anzahl] [feind|freund]} - soldiers where you are looking</li>
 *   <li>{@code debug} - show / hide what every soldier near you sees, hears and does</li>
 *   <li>{@code werkzeug} - the AI tool (only to be had this way)</li>
 *   <li>{@code kreativ an|aus} - whether soldiers also notice players in creative mode</li>
 *   <li>{@code entfernen} - remove every soldier in this world</li>
 * </ul>
 */
public final class SoldierCommand {
	private SoldierCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("bmai").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("spawn")
				.executes(ctx -> spawn(ctx.getSource(), 1, SoldierEntity.TEAM_HOSTILE))
				.then(Commands.argument("anzahl", IntegerArgumentType.integer(1, 16))
					.executes(ctx -> spawn(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "anzahl"), SoldierEntity.TEAM_HOSTILE))
					.then(Commands.literal("feind").executes(ctx -> spawn(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "anzahl"), SoldierEntity.TEAM_HOSTILE)))
					.then(Commands.literal("freund").executes(ctx -> spawn(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "anzahl"), SoldierEntity.TEAM_FRIENDLY)))))
			.then(Commands.literal("debug").executes(ctx -> {
				ServerPlayer player = ctx.getSource().getPlayerOrException();
				boolean on = SoldierDebug.toggle(player);
				ctx.getSource().sendSuccess(() -> Component.translatable(on ? "message.ballisticmissiles.ai_debug_on" : "message.ballisticmissiles.ai_debug_off"), false);
				return 1;
			}))
			.then(Commands.literal("werkzeug").executes(ctx -> giveTool(ctx.getSource())))
			.then(Commands.literal("tool").executes(ctx -> giveTool(ctx.getSource())))
			.then(Commands.literal("kreativ")
				.then(Commands.literal("an").executes(ctx -> creative(ctx.getSource(), false)))
				.then(Commands.literal("aus").executes(ctx -> creative(ctx.getSource(), true))))
			.then(Commands.literal("entfernen").executes(ctx -> {
				ServerLevel level = ctx.getSource().getLevel();
				int n = 0;
				for (var e : level.getAllEntities()) {
					if (e instanceof SoldierEntity s) {
						s.discard();
						n++;
					}
				}
				int count = n;
				ctx.getSource().sendSuccess(() -> Component.translatable("message.ballisticmissiles.ai_removed", count), true);
				return count;
			})));
	}

	private static int giveTool(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ItemStack tool = new ItemStack(ModRegistry.AI_TOOL);
		if (!player.getInventory().add(tool)) {
			player.drop(tool, false);
		}
		source.sendSuccess(() -> Component.translatable("message.ballisticmissiles.ai_tool_given"), false);
		return 1;
	}

	private static int creative(CommandSourceStack source, boolean ignore) {
		SoldierEntity.ignoreCreative = ignore;
		source.sendSuccess(() -> Component.translatable(ignore ? "message.ballisticmissiles.ai_creative_off" : "message.ballisticmissiles.ai_creative_on"), true);
		return 1;
	}

	private static int spawn(CommandSourceStack source, int count, int team) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ServerLevel level = source.getLevel();
		Vec3 eye = player.getEyePosition();
		Vec3 far = eye.add(player.getLookAngle().scale(64.0));
		HitResult hit = level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 at = hit.getType() == HitResult.Type.MISS ? eye.add(player.getLookAngle().scale(12.0)) : hit.getLocation();
		int made = 0;
		for (int i = 0; i < count; i++) {
			double a = i * 2.4;
			double r = i == 0 ? 0.0 : 1.5 + i * 0.6;
			if (spawnAt(level, at.add(Math.cos(a) * r, 0.0, Math.sin(a) * r), team, player.getYRot() + 180.0F) != null) {
				made++;
			}
		}
		int n = made;
		source.sendSuccess(() -> Component.translatable(team == SoldierEntity.TEAM_HOSTILE ? "message.ballisticmissiles.ai_spawned_hostile"
			: "message.ballisticmissiles.ai_spawned_friendly", n), true);
		return n;
	}

	/** A soldier standing on the ground at (or near) {@code at}. */
	public static SoldierEntity spawnAt(ServerLevel level, Vec3 at, int team, float yaw) {
		SoldierEntity s = ModRegistry.SOLDIER.create(level, EntitySpawnReason.COMMAND);
		if (s == null) {
			return null;
		}
		net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.containing(at);
		for (int dy = 0; dy < 8 && !level.getBlockState(p).getCollisionShape(level, p).isEmpty(); dy++) {
			p = p.above();
		}
		s.snapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, yaw, 0.0F);
		s.setYHeadRot(yaw);
		s.setTeam(team);
		level.addFreshEntity(s);
		return s;
	}
}
