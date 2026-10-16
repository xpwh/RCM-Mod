package de.rcm.ballistic.sun;

import de.rcm.ballistic.BallisticMissiles;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** What goes over the wire for the Sun Destroyer. */
public final class SunNetwork {
	public static final int ACTION_TARGET = 0;
	public static final int ACTION_LAUNCH = 1;
	public static final int ACTION_ABORT = 2;

	private SunNetwork() {
	}

	/**
	 * A strike, to everyone in the dimension: where it is launched from and goes to, when the order was given (game
	 * time) and how long the flight is - the rest follows from {@link SunTimeline}. {@code cutscene}: this player gave
	 * the order and watches it. {@code aborted}: the countdown was stopped, forget it.
	 */
	public record StrikePayload(int id, BlockPos launcher, Vec3 target, long launchTime, int flight, boolean cutscene, boolean aborted)
		implements CustomPacketPayload {
		public static final Type<StrikePayload> TYPE = new Type<>(BallisticMissiles.id("sun_strike"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StrikePayload> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeVarInt(p.id);
			buf.writeBlockPos(p.launcher);
			buf.writeDouble(p.target.x);
			buf.writeDouble(p.target.y);
			buf.writeDouble(p.target.z);
			buf.writeLong(p.launchTime);
			buf.writeVarInt(p.flight);
			buf.writeBoolean(p.cutscene);
			buf.writeBoolean(p.aborted);
		}, buf -> new StrikePayload(buf.readVarInt(), buf.readBlockPos(), new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readLong(),
			buf.readVarInt(), buf.readBoolean(), buf.readBoolean()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** From the launch console: set the target (x, z), launch, or abort. */
	public record ActionPayload(BlockPos pos, int action, int x, int z) implements CustomPacketPayload {
		public static final Type<ActionPayload> TYPE = new Type<>(BallisticMissiles.id("sun_forge_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ActionPayload> CODEC = StreamCodec.of((buf, p) -> {
			buf.writeBlockPos(p.pos);
			buf.writeVarInt(p.action);
			buf.writeInt(p.x);
			buf.writeInt(p.z);
		}, buf -> new ActionPayload(buf.readBlockPos(), buf.readVarInt(), buf.readInt(), buf.readInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** The watcher skipped the cutscene. */
	public record SkipPayload(int id) implements CustomPacketPayload {
		public static final Type<SkipPayload> TYPE = new Type<>(BallisticMissiles.id("sun_skip"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SkipPayload> CODEC = StreamCodec.of((buf, p) -> buf.writeVarInt(p.id),
			buf -> new SkipPayload(buf.readVarInt()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(StrikePayload.TYPE, StrikePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ActionPayload.TYPE, ActionPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(SkipPayload.TYPE, SkipPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE, (payload, context) -> {
			var player = context.player();
			if (!(player.level() instanceof ServerLevel level) || player.distanceToSqr(Vec3.atCenterOf(payload.pos())) > 48.0 * 48.0) {
				return;
			}
			if (level.getBlockEntity(payload.pos()) instanceof SunForgeBlockEntity forge) {
				forge.action(player, payload.action(), payload.x(), payload.z());
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(SkipPayload.TYPE, (payload, context) -> SunStrikes.skip(context.player(), payload.id()));
	}
}
