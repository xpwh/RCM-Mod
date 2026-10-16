package de.rcm.ballistic.sun;

import de.rcm.ballistic.sun.SunNetwork.StrikePayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Sun Destroyer strikes under way, from the order to the last of the nova: the countdown, the shot and the
 * flight (which need nothing from the server but time - everyone draws them from {@link SunTimeline}), and the
 * sun at the target ({@link SunDetonation}). Keeps the chunks round the complex and the target loaded while it
 * matters, and, for whoever gave the order, moves what the server sends them to where their cutscene looks.
 */
public final class SunStrikes {
	private static final List<Strike> STRIKES = new ArrayList<>();
	/** Who is watching a cutscene: player to strike. */
	private static final Map<UUID, Strike> WATCHING = new HashMap<>();
	private static int nextId = 1;

	private SunStrikes() {
	}

	private static final class Strike {
		final int id;
		final ServerLevel level;
		final BlockPos launcher;
		final Vec3 target;
		final long launchTime;
		final int flight;
		@Nullable UUID watcher;
		/** Where the watcher's view is anchored now: 0 the complex, 1 the target. */
		int anchor = -1;
		@Nullable SunDetonation detonation;

		Strike(int id, ServerLevel level, BlockPos launcher, Vec3 target, long launchTime, int flight) {
			this.id = id;
			this.level = level;
			this.launcher = launcher;
			this.target = target;
			this.launchTime = launchTime;
			this.flight = flight;
		}

		long age() {
			return this.level.getGameTime() - this.launchTime;
		}

		long impact() {
			return SunTimeline.FIRE + this.flight;
		}

		StrikePayload payload(boolean cutscene, boolean aborted) {
			return new StrikePayload(this.id, this.launcher, this.target, this.launchTime, this.flight, cutscene, aborted);
		}
	}

	/** Starts a strike ordered by {@code by}, who watches it; returns its id. */
	public static int start(ServerLevel level, BlockPos launcher, Vec3 target, long launchTime, int flight, @Nullable ServerPlayer by) {
		Strike s = new Strike(nextId++, level, launcher, target, launchTime, flight);
		if (by != null) {
			Strike old = WATCHING.remove(by.getUUID());
			if (old != null) {
				old.watcher = null;
			}
			s.watcher = by.getUUID();
			WATCHING.put(by.getUUID(), s);
		}
		STRIKES.add(s);
		for (ServerPlayer p : level.players()) {
			ServerPlayNetworking.send(p, s.payload(by != null && p.getUUID().equals(by.getUUID()), false));
		}
		return s.id;
	}

	public static void abort(ServerLevel level, int id) {
		for (Iterator<Strike> it = STRIKES.iterator(); it.hasNext();) {
			Strike s = it.next();
			if (s.id == id) {
				it.remove();
				release(s);
				for (ServerPlayer p : level.players()) {
					ServerPlayNetworking.send(p, s.payload(false, true));
				}
			}
		}
	}

	/** The watcher had enough: their view comes home. */
	public static void skip(ServerPlayer player, int id) {
		Strike s = WATCHING.get(player.getUUID());
		if (s != null && s.id == id) {
			release(s);
		}
	}

	/** Someone joined (or came into the dimension): show them what is going on. */
	public static void sync(ServerPlayer player) {
		for (Strike s : STRIKES) {
			if (s.level == player.level()) {
				ServerPlayNetworking.send(player, s.payload(false, false));
			}
		}
	}

	private static void release(Strike s) {
		if (s.watcher == null) {
			return;
		}
		UUID who = s.watcher;
		s.watcher = null;
		WATCHING.remove(who);
		if (s.level.getServer().getPlayerList().getPlayer(who) instanceof ServerPlayer p) {
			de.rcm.ballistic.entity.FpvDroneEntity.refreshView(p);
		}
	}

	/** Where {@code player}'s cutscene keeps their view, or null when they watch none. */
	public static @Nullable Vec3 view(ServerPlayer player) {
		if (WATCHING.isEmpty()) {
			return null;
		}
		Strike s = WATCHING.get(player.getUUID());
		if (s == null || s.anchor < 0 || s.level != player.level()) {
			return null;
		}
		return s.anchor == 0 ? Vec3.atCenterOf(s.launcher) : SunTimeline.wideShotAnchor(s.launcher, s.target);
	}

	public static void tick(ServerLevel level) {
		for (Iterator<Strike> it = STRIKES.iterator(); it.hasNext();) {
			Strike s = it.next();
			if (s.level != level) {
				continue;
			}
			long t = s.age();
			long impact = s.impact();
			// keep the complex loaded until the round is gone, and the target from before it gets there
			if (t % 20 == 0) {
				if (t < SunTimeline.FIRE + 60) {
					level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(s.launcher), 3);
				}
				if (t > impact - 200) {
					int r = Mth.ceil(SunTimeline.RADIUS * 2.7 / 16.0) + 1;
					level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(BlockPos.containing(s.target)), r);
				}
			}
			if (t == SunTimeline.FIRE) {
				tell(s, Component.translatable("message.ballisticmissiles.sun_fired", s.flight / 20).withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
			}
			if (t == impact) {
				s.detonation = new SunDetonation(level, s.target);
				tell(s, Component.translatable("message.ballisticmissiles.sun_impact").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			}
			if (s.detonation != null) {
				s.detonation.tick((int) (t - impact));
			}
			watch(s, t);
			if (t > impact + SunDetonation.LENGTH && t > SunTimeline.cutsceneTo(s.flight)) {
				release(s);
				it.remove();
			}
		}
	}

	/** Moves the watcher's view: at the complex for the countdown, at the target from halfway through the flight. */
	private static void watch(Strike s, long t) {
		if (s.watcher == null) {
			return;
		}
		ServerPlayer p = s.level.getServer().getPlayerList().getPlayer(s.watcher);
		if (p == null || p.level() != s.level || t >= SunTimeline.cutsceneTo(s.flight)) {
			release(s);
			return;
		}
		int want = t < SunTimeline.cutsceneFrom() ? -1 : t < SunTimeline.FIRE + s.flight / 2 ? 0 : 1;
		if (want != s.anchor) {
			s.anchor = want;
			de.rcm.ballistic.entity.FpvDroneEntity.refreshView(p);
		}
		Vec3 at = view(p);
		if (at != null && t % 20 == 0) {
			// the land the camera looks at, loaded out to the view distance (from just before it is needed)
			int r = Mth.clamp(s.level.getServer().getPlayerList().getViewDistance(), 3, 10);
			s.level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, new ChunkPos(BlockPos.containing(at)), r);
		}
		if (t % 20 == 0 && t > SunTimeline.FIRE + s.flight / 2 - 120 && s.anchor != 1) {
			int r = Mth.clamp(s.level.getServer().getPlayerList().getViewDistance(), 3, 10);
			s.level.getChunkSource().addTicketWithRadius(TicketType.PORTAL,
				new ChunkPos(BlockPos.containing(SunTimeline.wideShotAnchor(s.launcher, s.target))), r);
		}
	}

	private static void tell(Strike s, Component message) {
		if (s.watcher != null && s.level.getServer().getPlayerList().getPlayer(s.watcher) instanceof ServerPlayer p) {
			p.displayClientMessage(message, true);
		}
	}

	public static void clear() {
		STRIKES.clear();
		WATCHING.clear();
	}
}
