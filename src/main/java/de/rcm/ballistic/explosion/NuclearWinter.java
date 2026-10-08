package de.rcm.ballistic.explosion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.network.ModNetworking.WinterPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Nuclear winter. Every nuclear fireball lofts soot into the stratosphere, where it spreads around
 * the world and blocks the sun; it rains out slowly over a few days. The more soot, the darker and
 * colder the overworld: a grey, dim sky, ash drifting down like snow, ponds freezing over, snow and
 * ash settling on the ground, crops in the open hardly growing, and - in a deep winter - anyone
 * outdoors away from a fire slowly freezing. Saved with the world and sent to every player.
 */
public final class NuclearWinter {
	/** Soot rains out with a half-life of three in-game days. */
	private static final double DECAY = Math.pow(0.5, 1.0 / 72000.0);
	/** Above this the winter is noticeable; the announcement is made when it is crossed. */
	public static final double ONSET = 0.15;
	private static final String FILE = "ballisticmissiles_winter.json";

	private static double soot;
	private static boolean announced;

	private NuclearWinter() {
	}

	/** Soot level 0-1 (server side). */
	public static double soot() {
		return soot;
	}

	/** A nuclear burst: {@code amount} of soot goes up (about 0.1 for a fission warhead). */
	public static void add(ServerLevel level, double amount) {
		soot = Math.min(1.0, soot + amount);
		MinecraftServer server = level.getServer();
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		if (!announced && soot >= ONSET && overworld != null) {
			announced = true;
			Component msg = Component.literal("☁ ").append(Component.translatable("message.ballisticmissiles.winter_begins"))
				.withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				player.displayClientMessage(msg, false);
			}
		}
		syncAll(server);
	}

	/** Whether a random growth tick at {@code pos} is lost to the cold and the dark. */
	public static boolean stuntsGrowth(ServerLevel level, BlockPos pos, RandomSource random) {
		return soot > 0.05 && level.dimension() == Level.OVERWORLD && level.canSeeSky(pos.above()) && random.nextDouble() < Math.min(0.9, soot * 1.4);
	}

	public static void tick(ServerLevel level) {
		if (level.dimension() != Level.OVERWORLD) {
			return;
		}
		if (soot <= 0.0) {
			return;
		}
		soot *= DECAY;
		if (soot < 0.002) {
			soot = 0.0;
		}
		if (announced && soot < ONSET * 0.5) {
			announced = false;
			Component msg = Component.literal("☀ ").append(Component.translatable("message.ballisticmissiles.winter_ends")).withStyle(ChatFormatting.YELLOW);
			for (ServerPlayer player : level.players()) {
				player.displayClientMessage(msg, false);
			}
		}
		long time = level.getGameTime();
		if (time % 200 == 0) {
			syncAll(level.getServer());
		}
		if (soot < ONSET) {
			return;
		}
		RandomSource random = level.getRandom();
		for (ServerPlayer player : level.players()) {
			if (time % 10 == 0) {
				settle(level, player.blockPosition(), random);
			}
			freeze(level, player);
		}
	}

	/** Snow and ash settle and ponds freeze over around a player. */
	private static void settle(ServerLevel level, BlockPos around, RandomSource random) {
		int tries = (int) (soot * 8) + 1;
		for (int i = 0; i < tries; i++) {
			int x = around.getX() + random.nextInt(97) - 48;
			int z = around.getZ() + random.nextInt(97) - 48;
			if (!level.hasChunk(x >> 4, z >> 4)) {
				continue;
			}
			BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
			BlockPos below = top.below();
			BlockState ground = level.getBlockState(below);
			if (ground.getFluidState().is(Fluids.WATER) && ground.getFluidState().isSource() && ground.is(Blocks.WATER)) {
				if (random.nextDouble() < soot) {
					level.setBlock(below, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
				}
				continue;
			}
			BlockState at = level.getBlockState(top);
			if (!at.isAir() || random.nextDouble() > soot * 0.6) {
				continue;
			}
			// grey snow: mostly snow, some of it ash
			BlockState layer = random.nextDouble() < 0.3 ? ModRegistry.ASH.defaultBlockState() : Blocks.SNOW.defaultBlockState();
			if (layer.canSurvive(level, top)) {
				level.setBlock(top, layer, Block.UPDATE_ALL);
			} else if (at.is(Blocks.SNOW) && at.getValue(SnowLayerBlock.LAYERS) < 3) {
				level.setBlock(top, at.setValue(SnowLayerBlock.LAYERS, at.getValue(SnowLayerBlock.LAYERS) + 1), Block.UPDATE_ALL);
			}
		}
	}

	/** In a deep winter, being outdoors away from a fire slowly freezes you (frost overlay, then damage). */
	private static void freeze(ServerLevel level, ServerPlayer player) {
		if (soot < 0.5 || player.isCreative() || player.isSpectator()) {
			return;
		}
		BlockPos pos = player.blockPosition();
		boolean outdoors = level.canSeeSky(pos.above());
		boolean warm = level.getBrightness(LightLayer.BLOCK, pos) >= 10;
		if (outdoors && !warm && player.canFreeze()) {
			// vanilla thaws 2 per tick outside powder snow; this outpaces it slowly
			int add = 2 + Mth.ceil((soot - 0.5) * 3.0);
			player.setTicksFrozen(Math.min(player.getTicksRequiredToFreeze() + 20, player.getTicksFrozen() + add));
		}
	}

	// ------------------------------------------------------------------ sync and persistence

	public static void sync(ServerPlayer player) {
		ServerPlayNetworking.send(player, new WinterPayload((float) soot));
	}

	private static void syncAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sync(player);
		}
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
	}

	public static void load(MinecraftServer server) {
		soot = 0.0;
		announced = false;
		Path path = file(server);
		if (!Files.exists(path)) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			soot = Mth.clamp(o.get("soot").getAsDouble(), 0.0, 1.0);
			announced = soot >= ONSET;
		} catch (IOException | RuntimeException e) {
			BallisticMissiles.LOGGER.warn("Could not read nuclear winter state from {}", path, e);
		}
	}

	public static void save(MinecraftServer server) {
		JsonObject o = new JsonObject();
		o.addProperty("soot", soot);
		Path path = file(server);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BallisticMissiles.LOGGER.warn("Could not save nuclear winter state to {}", path, e);
		}
	}

	public static void clear() {
		soot = 0.0;
		announced = false;
	}
}
