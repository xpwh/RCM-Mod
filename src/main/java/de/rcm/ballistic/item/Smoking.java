package de.rcm.ballistic.item;

import de.rcm.ballistic.BallisticMissiles;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who is smoking right now. The server only keeps when each cigarette goes out and tells everyone who can
 * see the smoker; everything else - the cigarette in the mouth, the drags, the smoke, the calm it brings -
 * every client plays out from when it was lit.
 */
public final class Smoking {
	/** How long a cigarette lasts once lit: about seven drags. */
	public static final int DURATION = 900;
	private static final Map<UUID, Long> UNTIL = new HashMap<>();

	private Smoking() {
	}

	public static boolean isSmoking(ServerPlayer player) {
		Long until = UNTIL.get(player.getUUID());
		return until != null && until > player.level().getGameTime();
	}

	/** The cigarette is lit: it burns for {@link #DURATION} ticks (or {@code 0} puts it out). */
	public static void start(ServerPlayer player, int ticks) {
		UNTIL.put(player.getUUID(), player.level().getGameTime() + ticks);
		slow(player, ticks > 0);
		SmokePayload payload = new SmokePayload(player.getId(), ticks);
		ServerPlayNetworking.send(player, payload);
		for (ServerPlayer other : PlayerLookup.tracking(player)) {
			if (other != player) {
				ServerPlayNetworking.send(other, payload);
			}
		}
	}

	/** A cigarette in the hand: you walk slower (and the client keeps you from sprinting). */
	private static final net.minecraft.resources.Identifier SLOW = BallisticMissiles.id("smoking");

	private static void slow(ServerPlayer player, boolean on) {
		var speed = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		speed.removeModifier(SLOW);
		if (on) {
			speed.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(SLOW, -0.35,
				net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			player.setSprinting(false);
		}
	}

	/** The smoker flicks the cigarette away. */
	public record StopPayload() implements CustomPacketPayload {
		public static final Type<StopPayload> TYPE = new Type<>(BallisticMissiles.id("smoke_stop"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StopPayload> CODEC = StreamCodec.unit(new StopPayload());

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** A player is lighting up (or stopped), so the others see the cigarette from the start. */
	public record SmokePayload(int entity, int ticks) implements CustomPacketPayload {
		public static final Type<SmokePayload> TYPE = new Type<>(BallisticMissiles.id("smoke"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SmokePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SmokePayload::entity,
			ByteBufCodecs.VAR_INT, SmokePayload::ticks,
			SmokePayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(SmokePayload.TYPE, SmokePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(StopPayload.TYPE, StopPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(StopPayload.TYPE, (payload, context) -> {
			ServerPlayer p = context.player();
			if (isSmoking(p)) {
				start(p, 0);
				p.level().playSound(null, p.getX(), p.getEyeY() - 0.3, p.getZ(), net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH,
					net.minecraft.sounds.SoundSource.PLAYERS, 0.15F, 2.0F);
			}
		});
		// a cigarette does not survive a dip
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (UNTIL.isEmpty()) {
				return;
			}
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				Long until = UNTIL.get(p.getUUID());
				if (until != null && until <= p.level().getGameTime() && until > p.level().getGameTime() - 2) {
					slow(p, false); // burnt down
				}
				if (isSmoking(p) && p.isSprinting()) {
					p.setSprinting(false);
				}
				if (server.getTickCount() % 10 == 0 && isSmoking(p) && (p.isUnderWater() || !p.isAlive())) {
					start(p, 0);
					p.level().playSound(null, p.getX(), p.getEyeY(), p.getZ(), net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH,
						net.minecraft.sounds.SoundSource.PLAYERS, 0.3F, 1.8F);
				}
			}
			UNTIL.values().removeIf(t -> t < server.overworld().getGameTime() - 20);
		});
	}
}
