package de.rcm.ballistic.ai;

import de.rcm.ballistic.BallisticMissiles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The AI's mind, made visible: for players who switched it on ({@code /bmai debug}) or hold the AI
 * tool, the server sends what every soldier nearby sees, hears, wants and is doing, a few times a
 * second, and the client draws it.
 */
public final class SoldierDebug {
	private static final Set<UUID> VIEWERS = new HashSet<>();
	private static final double RANGE = 96.0;

	private SoldierDebug() {
	}

	public static boolean toggle(ServerPlayer player) {
		if (!VIEWERS.remove(player.getUUID())) {
			VIEWERS.add(player.getUUID());
			return true;
		}
		ServerPlayNetworking.send(player, new Payload(List.of()));
		return false;
	}

	public static void clear() {
		VIEWERS.clear();
	}

	private static boolean watching(ServerPlayer player) {
		return VIEWERS.contains(player.getUUID()) || player.getMainHandItem().is(de.rcm.ballistic.ModRegistry.AI_TOOL)
			|| player.getOffhandItem().is(de.rcm.ballistic.ModRegistry.AI_TOOL);
	}

	public static void tick(ServerLevel level) {
		if (level.getGameTime() % 4 != 0) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			if (!watching(player)) {
				continue;
			}
			List<Entry> entries = new ArrayList<>();
			for (SoldierEntity s : level.getEntitiesOfClass(SoldierEntity.class, player.getBoundingBox().inflate(RANGE))) {
				entries.add(s.debugEntry(level));
				if (entries.size() >= 24) {
					break;
				}
			}
			ServerPlayNetworking.send(player, new Payload(entries));
		}
	}

	/** One soldier's state of mind. Noises: {x, y, z, kind, age ticks, loudness}; path: {x, y, z}. */
	/** ... radio: {reporter id, age ticks}. */
	public record Entry(int id, int state, int team, float awareness, String reason, String role, String targetName, float targetDistance,
		int targetId, boolean targetVisible, @Nullable Vec3 lastKnown, @Nullable Vec3 investigate, @Nullable Vec3 cover, @Nullable Vec3 flank,
		@Nullable Vec3 grenade, int grenades, int rounds, boolean reloading, float suppression, float headYaw, List<double[]> noises,
		List<double[]> path, List<double[]> radio) {
	}

	public record Payload(List<Entry> entries) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(BallisticMissiles.id("soldier_debug"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeVarInt(p.entries().size());
			for (Entry e : p.entries()) {
				buf.writeVarInt(e.id());
				buf.writeVarInt(e.state());
				buf.writeVarInt(e.team());
				buf.writeFloat(e.awareness());
				buf.writeUtf(e.reason(), 64);
				buf.writeUtf(e.role(), 64);
				buf.writeUtf(e.targetName(), 64);
				buf.writeFloat(e.targetDistance());
				buf.writeVarInt(e.targetId());
				buf.writeBoolean(e.targetVisible());
				writeVec(buf, e.lastKnown());
				writeVec(buf, e.investigate());
				writeVec(buf, e.cover());
				writeVec(buf, e.flank());
				writeVec(buf, e.grenade());
				buf.writeVarInt(e.grenades());
				buf.writeVarInt(e.rounds());
				buf.writeBoolean(e.reloading());
				buf.writeFloat(e.suppression());
				buf.writeFloat(e.headYaw());
				writeRows(buf, e.noises(), 6);
				writeRows(buf, e.path(), 3);
				writeRows(buf, e.radio(), 2);
			}
		}, buf -> {
			int n = Math.min(64, buf.readVarInt());
			List<Entry> list = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				list.add(new Entry(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(), buf.readUtf(64), buf.readUtf(64), buf.readUtf(64),
					buf.readFloat(), buf.readVarInt(), buf.readBoolean(), readVec(buf), readVec(buf), readVec(buf), readVec(buf), readVec(buf), buf.readVarInt(),
					buf.readVarInt(), buf.readBoolean(), buf.readFloat(), buf.readFloat(), readRows(buf, 6), readRows(buf, 3), readRows(buf, 2)));
			}
			return new Payload(list);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private static void writeVec(RegistryFriendlyByteBuf buf, @Nullable Vec3 v) {
		buf.writeBoolean(v != null);
		if (v != null) {
			buf.writeDouble(v.x);
			buf.writeDouble(v.y);
			buf.writeDouble(v.z);
		}
	}

	private static @Nullable Vec3 readVec(RegistryFriendlyByteBuf buf) {
		return buf.readBoolean() ? new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) : null;
	}

	private static void writeRows(RegistryFriendlyByteBuf buf, List<double[]> rows, int width) {
		buf.writeVarInt(rows.size());
		for (double[] r : rows) {
			for (int i = 0; i < width; i++) {
				buf.writeDouble(r[i]);
			}
		}
	}

	private static List<double[]> readRows(RegistryFriendlyByteBuf buf, int width) {
		int n = Math.min(32, buf.readVarInt());
		List<double[]> rows = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			double[] r = new double[width];
			for (int k = 0; k < width; k++) {
				r[k] = buf.readDouble();
			}
			rows.add(r);
		}
		return rows;
	}
}
