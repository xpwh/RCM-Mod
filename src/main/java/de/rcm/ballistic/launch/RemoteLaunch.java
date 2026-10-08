package de.rcm.ballistic.launch;

import de.rcm.ballistic.block.MissileSiloBlockEntity;
import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.MobileLauncherEntity;
import de.rcm.ballistic.item.LauncherLink;
import de.rcm.ballistic.item.TargetData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * Launches linked launchers from anywhere. The launcher's chunk is force-loaded until the order
 * went through (entities need a few ticks to load), then the result is reported back.
 */
public final class RemoteLaunch {
	private static final int TIMEOUT = 100;

	private static final class Order {
		final ServerLevel level;
		final LauncherLink link;
		final @Nullable TargetData target;
		final UUID player;
		int age;

		Order(ServerLevel level, LauncherLink link, @Nullable TargetData target, UUID player) {
			this.level = level;
			this.link = link;
			this.target = target;
			this.player = player;
		}
	}

	private static final List<Order> ORDERS = new ArrayList<>();
	/** Last known position of every mobile launcher, so its chunk can be loaded for a remote order. */
	private static final Map<UUID, BlockPos> TRUCKS = new HashMap<>();

	private RemoteLaunch() {
	}

	public static void truckMoved(UUID truck, BlockPos pos) {
		TRUCKS.put(truck, pos.immutable());
	}

	public static void fire(ServerPlayer player, List<LauncherLink> links, TargetData target) {
		for (LauncherLink link : links) {
			ORDERS.add(new Order(player.level(), link, target, player.getUUID()));
		}
		player.displayClientMessage(Component.translatable("message.ballisticmissiles.remote_sent", links.size()).withStyle(ChatFormatting.GOLD), true);
	}

	public static void abort(ServerPlayer player, List<LauncherLink> links) {
		for (LauncherLink link : links) {
			ORDERS.add(new Order(player.level(), link, null, player.getUUID()));
		}
	}

	public static void tick(ServerLevel level) {
		if (ORDERS.isEmpty()) {
			return;
		}
		Iterator<Order> it = ORDERS.iterator();
		List<Runnable> results = new ArrayList<>();
		while (it.hasNext()) {
			Order order = it.next();
			if (order.level != level) {
				continue;
			}
			Component result = execute(order);
			if (result == null && ++order.age < TIMEOUT) {
				continue;
			}
			it.remove();
			Component msg = Component.empty()
				.append(order.link.describe().copy().withStyle(ChatFormatting.AQUA))
				.append(Component.literal(": "))
				.append(result != null ? result : Component.translatable("message.ballisticmissiles.remote_unreachable").withStyle(ChatFormatting.RED));
			UUID playerId = order.player;
			results.add(() -> {
				ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
				if (player != null) {
					player.displayClientMessage(msg, false);
				}
			});
		}
		results.forEach(Runnable::run);
	}

	/** @return the outcome, or null while the launcher is still loading */
	private static @Nullable Component execute(Order order) {
		ServerLevel level = order.level;
		LauncherLink link = order.link;
		BlockPos pos = link.kind() == LauncherLink.TRUCK ? TRUCKS.getOrDefault(link.entity().orElseThrow(), link.pos()) : link.pos();
		level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(pos), 2);
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(order.player);

		switch (link.kind()) {
			case LauncherLink.SILO -> {
				if (!level.isLoaded(pos)) {
					return null;
				}
				if (!(level.getBlockEntity(pos) instanceof MissileSiloBlockEntity silo)) {
					return Component.translatable("message.ballisticmissiles.remote_gone").withStyle(ChatFormatting.RED);
				}
				if (order.target == null) {
					return silo.abort() ? aborted() : nothingToAbort();
				}
				return silo.arm(null, order.target);
			}
			case LauncherLink.UPLINK -> {
				if (!level.isLoaded(pos)) {
					return null;
				}
				if (!(level.getBlockEntity(pos) instanceof de.rcm.ballistic.block.OrbitalUplinkBlockEntity uplink)) {
					return Component.translatable("message.ballisticmissiles.remote_gone").withStyle(ChatFormatting.RED);
				}
				if (order.target == null) {
					return nothingToAbort();
				}
				return uplink.strike(player, order.target);
			}
			case LauncherLink.TRUCK -> {
				Entity e = level.getEntity(link.entity().orElseThrow());
				if (!(e instanceof MobileLauncherEntity truck)) {
					return null;
				}
				if (order.target == null) {
					return truck.abort() ? aborted() : nothingToAbort();
				}
				return truck.arm(null, order.target);
			}
			default -> {
				if (!level.isPositionEntityTicking(pos)) {
					return null;
				}
				List<MissileEntity> missiles = level.getEntitiesOfClass(MissileEntity.class, new AABB(pos).expandTowards(0, 2.5, 0).inflate(0.2));
				if (missiles.isEmpty()) {
					return Component.translatable("message.ballisticmissiles.remote_empty").withStyle(ChatFormatting.YELLOW);
				}
				MissileEntity missile = missiles.get(0);
				if (order.target == null) {
					return missile.abort() ? aborted() : nothingToAbort();
				}
				if (missile.getState() != MissileEntity.IDLE) {
					return Component.translatable("message.ballisticmissiles.remote_busy").withStyle(ChatFormatting.YELLOW);
				}
				return missile.arm(player, order.target)
					? Component.translatable("message.ballisticmissiles.remote_launching").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
					: Component.translatable("message.ballisticmissiles.remote_refused").withStyle(ChatFormatting.YELLOW);
			}
		}
	}

	private static Component aborted() {
		return Component.translatable("message.ballisticmissiles.aborted").withStyle(ChatFormatting.GREEN);
	}

	private static Component nothingToAbort() {
		return Component.translatable("message.ballisticmissiles.remote_idle").withStyle(ChatFormatting.GRAY);
	}

	public static void clear() {
		ORDERS.clear();
		TRUCKS.clear();
	}
}
