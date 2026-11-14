package de.rcm.ballistic.explosion;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.config.ServerConfig;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The killcam: when something big goes off (and the server allows it, {@code /bmserver killcam}), the whole world
 * runs in slow motion for a few seconds - the server's tick rate is lowered for everyone, so the fireball, the
 * debris, the shock wave and everyone in it really do move slowly - and every player near enough to see it gets
 * the camera taken off them for the moment: it is shown from three angles, one after another. Then the world's
 * time runs on as it was.
 */
public final class Killcam {
	/** How much the world slows down. */
	public static final float SLOW = 0.3F;
	/** How long it lasts, in real seconds. */
	public static final float SECONDS = 9.0F;
	private static int remaining = -1;
	private static float savedRate = 20.0F;

	private Killcam() {
	}

	/** To players near a big explosion: where, how big (blocks), how long the replay runs (milliseconds). */
	public record Payload(Vec3 pos, float size, int millis) implements CustomPacketPayload {
		public static final Type<Payload> TYPE = new Type<>(BallisticMissiles.id("killcam"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeDouble(p.pos.x);
			buf.writeDouble(p.pos.y);
			buf.writeDouble(p.pos.z);
			buf.writeFloat(p.size);
			buf.writeVarInt(p.millis);
		}, buf -> new Payload(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readFloat(), buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(Payload.TYPE, Payload.CODEC);
	}

	/** Something big went off at {@code pos}; {@code size}: roughly how far its fireball and cloud reach. */
	public static void trigger(ServerLevel level, Vec3 pos, float size) {
		if (!ServerConfig.killcam || remaining >= 0) {
			return;
		}
		MinecraftServer server = level.getServer();
		ServerTickRateManager rate = server.tickRateManager();
		if (rate.isSprinting() || rate.isFrozen()) {
			return;
		}
		savedRate = rate.tickrate();
		float slow = Math.max(1.0F, savedRate * SLOW);
		rate.setTickRate(slow);
		remaining = Math.round(SECONDS * slow);
		Payload payload = new Payload(pos, size, Math.round(SECONDS * 1000.0F));
		// shown to those close enough that the land round it is loaded for them (the camera goes there)
		double reach = server.getPlayerList().getViewDistance() * 16.0 * 0.7;
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceTo(pos) < reach) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	public static void tick(MinecraftServer server) {
		if (remaining < 0) {
			return;
		}
		if (--remaining < 0) {
			server.tickRateManager().setTickRate(savedRate);
		}
	}

	/** The server stops: never leave the world slowed down. */
	public static void stop(MinecraftServer server) {
		if (remaining >= 0) {
			server.tickRateManager().setTickRate(savedRate);
			remaining = -1;
		}
	}
}
