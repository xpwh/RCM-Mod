package de.rcm.ballistic.network;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.block.RadarBlockEntity;
import de.rcm.ballistic.item.TargetDesignatorItem;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;

public final class ModNetworking {
	private ModNetworking() {
	}

	/** Server -> client: a warhead went off, play the visual and audio effects. */
	public record DetonationPayload(int warhead, double x, double y, double z) implements CustomPacketPayload {
		public static final Type<DetonationPayload> TYPE = new Type<>(BallisticMissiles.id("detonation"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DetonationPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, DetonationPayload::warhead,
			ByteBufCodecs.DOUBLE, DetonationPayload::x,
			ByteBufCodecs.DOUBLE, DetonationPayload::y,
			ByteBufCodecs.DOUBLE, DetonationPayload::z,
			DetonationPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: store a target in the held designator. */
	public record SetTargetPayload(boolean offhand, BlockPos pos, boolean surface) implements CustomPacketPayload {
		public static final Type<SetTargetPayload> TYPE = new Type<>(BallisticMissiles.id("set_target"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetTargetPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, SetTargetPayload::offhand,
			BlockPos.STREAM_CODEC, SetTargetPayload::pos,
			ByteBufCodecs.BOOL, SetTargetPayload::surface,
			SetTargetPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Client -> server: targeting computer actions on the held designator.
	 * {@code index} is a list index, or a bit mask of launchers for {@link #FIRE}.
	 */
	public record DesignatorActionPayload(boolean offhand, int action, int index, String text) implements CustomPacketPayload {
		public static final int SAVE_TARGET = 0;
		public static final int DELETE_SAVED = 1;
		public static final int TARGET_PLAYER = 2;
		public static final int UNLINK = 3;
		public static final int FIRE = 4;
		public static final int ABORT = 5;

		public static final Type<DesignatorActionPayload> TYPE = new Type<>(BallisticMissiles.id("designator_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DesignatorActionPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, DesignatorActionPayload::offhand,
			ByteBufCodecs.VAR_INT, DesignatorActionPayload::action,
			ByteBufCodecs.VAR_INT, DesignatorActionPayload::index,
			ByteBufCodecs.stringUtf8(64), DesignatorActionPayload::text,
			DesignatorActionPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** One radar track as shown on the scope. */
	public record TrackInfo(
		int number, int threatClass, String nameKey, float x, float y, float z, float vx, float vz, float impactX, float impactZ, float impactError,
		int eta, boolean threat, int age
	) {
		static void write(FriendlyByteBuf buf, TrackInfo t) {
			buf.writeVarInt(t.number);
			buf.writeVarInt(t.threatClass);
			buf.writeUtf(t.nameKey, 96);
			buf.writeFloat(t.x);
			buf.writeFloat(t.y);
			buf.writeFloat(t.z);
			buf.writeFloat(t.vx);
			buf.writeFloat(t.vz);
			buf.writeFloat(t.impactX);
			buf.writeFloat(t.impactZ);
			buf.writeFloat(t.impactError);
			buf.writeVarInt(t.eta);
			buf.writeBoolean(t.threat);
			buf.writeVarInt(t.age);
		}

		static TrackInfo read(FriendlyByteBuf buf) {
			return new TrackInfo(
				buf.readVarInt(), buf.readVarInt(), buf.readUtf(96), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
				buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt()
			);
		}
	}

	/** Friendly site shown on the scope: 0 radar, 1 air defense, 2 silo. */
	public record SiteInfo(BlockPos pos, int kind) {
	}

	/** Server -> client: radar scope picture. {@code open} asks the client to open the scope screen. */
	public record RadarDataPayload(
		BlockPos radar, boolean open, int jammedTicks, int range, int protectedRadius, float sweepOffset, List<TrackInfo> tracks, List<SiteInfo> sites,
		List<BlockPos> interceptors
	) implements CustomPacketPayload {
		public static final Type<RadarDataPayload> TYPE = new Type<>(BallisticMissiles.id("radar_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RadarDataPayload> CODEC = StreamCodec.of(RadarDataPayload::write, RadarDataPayload::read);

		private static void write(RegistryFriendlyByteBuf buf, RadarDataPayload p) {
			buf.writeBlockPos(p.radar);
			buf.writeBoolean(p.open);
			buf.writeVarInt(p.jammedTicks);
			buf.writeVarInt(p.range);
			buf.writeVarInt(p.protectedRadius);
			buf.writeFloat(p.sweepOffset);
			buf.writeVarInt(p.tracks.size());
			for (TrackInfo t : p.tracks) {
				TrackInfo.write(buf, t);
			}
			buf.writeVarInt(p.sites.size());
			for (SiteInfo s : p.sites) {
				buf.writeBlockPos(s.pos());
				buf.writeVarInt(s.kind());
			}
			buf.writeVarInt(p.interceptors.size());
			for (BlockPos i : p.interceptors) {
				buf.writeBlockPos(i);
			}
		}

		private static RadarDataPayload read(RegistryFriendlyByteBuf buf) {
			BlockPos radar = buf.readBlockPos();
			boolean open = buf.readBoolean();
			int jammed = buf.readVarInt();
			int range = buf.readVarInt();
			int protectedRadius = buf.readVarInt();
			float sweep = buf.readFloat();
			int n = Math.min(256, buf.readVarInt());
			List<TrackInfo> tracks = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				tracks.add(TrackInfo.read(buf));
			}
			int m = Math.min(256, buf.readVarInt());
			List<SiteInfo> sites = new ArrayList<>(m);
			for (int i = 0; i < m; i++) {
				sites.add(new SiteInfo(buf.readBlockPos(), buf.readVarInt()));
			}
			int k = Math.min(256, buf.readVarInt());
			List<BlockPos> interceptors = new ArrayList<>(k);
			for (int i = 0; i < k; i++) {
				interceptors.add(buf.readBlockPos());
			}
			return new RadarDataPayload(radar, open, jammed, range, protectedRadius, sweep, tracks, sites, interceptors);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the radar scope was closed. */
	public record RadarClosePayload(BlockPos radar) implements CustomPacketPayload {
		public static final Type<RadarClosePayload> TYPE = new Type<>(BallisticMissiles.id("radar_close"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RadarClosePayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, RadarClosePayload::radar, RadarClosePayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: the command center's situation map. {@code open} asks the client to open it. */
	public record CommandDataPayload(BlockPos center, boolean open, int range, List<TrackInfo> tracks, List<SiteInfo> sites, int links, int cooldown)
		implements CustomPacketPayload {
		public static final Type<CommandDataPayload> TYPE = new Type<>(BallisticMissiles.id("command_data"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CommandDataPayload> CODEC = StreamCodec.of(CommandDataPayload::write, CommandDataPayload::read);

		private static void write(RegistryFriendlyByteBuf buf, CommandDataPayload p) {
			buf.writeBlockPos(p.center);
			buf.writeBoolean(p.open);
			buf.writeVarInt(p.range);
			buf.writeVarInt(p.tracks.size());
			for (TrackInfo t : p.tracks) {
				TrackInfo.write(buf, t);
			}
			buf.writeVarInt(p.sites.size());
			for (SiteInfo s : p.sites) {
				buf.writeBlockPos(s.pos());
				buf.writeVarInt(s.kind());
			}
			buf.writeVarInt(p.links);
			buf.writeVarInt(p.cooldown);
		}

		private static CommandDataPayload read(RegistryFriendlyByteBuf buf) {
			BlockPos center = buf.readBlockPos();
			boolean open = buf.readBoolean();
			int range = buf.readVarInt();
			int n = Math.min(256, buf.readVarInt());
			List<TrackInfo> tracks = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				tracks.add(TrackInfo.read(buf));
			}
			int m = Math.min(512, buf.readVarInt());
			List<SiteInfo> sites = new ArrayList<>(m);
			for (int i = 0; i < m; i++) {
				sites.add(new SiteInfo(buf.readBlockPos(), buf.readVarInt()));
			}
			return new CommandDataPayload(center, open, range, tracks, sites, buf.readVarInt(), buf.readVarInt());
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: an order from the command center. */
	public record CommandActionPayload(BlockPos center, int action, int x, int z, int mode) implements CustomPacketPayload {
		public static final int CLOSE = 0;
		public static final int FIRE_LINKED = 1;
		public static final int AIRSTRIKE = 2;
		public static final int ABORT_LINKED = 3;

		public static final Type<CommandActionPayload> TYPE = new Type<>(BallisticMissiles.id("command_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CommandActionPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, CommandActionPayload::center,
			ByteBufCodecs.VAR_INT, CommandActionPayload::action,
			ByteBufCodecs.VAR_INT, CommandActionPayload::x,
			ByteBufCodecs.VAR_INT, CommandActionPayload::z,
			ByteBufCodecs.VAR_INT, CommandActionPayload::mode,
			CommandActionPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** One far-away object in the air: entity id, kind (0 missile, 1 warhead, 2 aircraft, 3 rocket). */
	public record FarTrack(int id, int kind, float x, float y, float z, float vx, float vy, float vz, boolean burning) {
	}

	/** Server -> client: long-range positions of everything flying (for far-render setups like Distant Horizons). */
	public record FarTrackPayload(List<FarTrack> tracks) implements CustomPacketPayload {
		public static final Type<FarTrackPayload> TYPE = new Type<>(BallisticMissiles.id("far_tracks"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FarTrackPayload> CODEC = StreamCodec.of(FarTrackPayload::write, FarTrackPayload::read);

		private static void write(RegistryFriendlyByteBuf buf, FarTrackPayload p) {
			buf.writeVarInt(p.tracks.size());
			for (FarTrack t : p.tracks) {
				buf.writeVarInt(t.id());
				buf.writeByte(t.kind());
				buf.writeFloat(t.x());
				buf.writeFloat(t.y());
				buf.writeFloat(t.z());
				buf.writeFloat(t.vx());
				buf.writeFloat(t.vy());
				buf.writeFloat(t.vz());
				buf.writeBoolean(t.burning());
			}
		}

		private static FarTrackPayload read(RegistryFriendlyByteBuf buf) {
			int n = Math.min(256, buf.readVarInt());
			List<FarTrack> tracks = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				tracks.add(new FarTrack(buf.readVarInt(), buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readFloat(), buf.readBoolean()));
			}
			return new FarTrackPayload(tracks);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * A fallout plume in the receiving player's dimension: origin, downwind direction, length and width,
	 * peak dose rate, ticks since detonation and lifetime.
	 */
	public record FalloutPlume(float x, float z, float dirX, float dirZ, float length, float width, float peak, int age, int duration) {
	}

	/** Server -> client: the fallout plumes, so ash can be shown raining out of them. */
	public record FalloutPayload(List<FalloutPlume> plumes) implements CustomPacketPayload {
		public static final Type<FalloutPayload> TYPE = new Type<>(BallisticMissiles.id("fallout"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FalloutPayload> CODEC = StreamCodec.of(FalloutPayload::write, FalloutPayload::read);

		private static void write(RegistryFriendlyByteBuf buf, FalloutPayload p) {
			buf.writeVarInt(p.plumes.size());
			for (FalloutPlume f : p.plumes) {
				buf.writeFloat(f.x());
				buf.writeFloat(f.z());
				buf.writeFloat(f.dirX());
				buf.writeFloat(f.dirZ());
				buf.writeFloat(f.length());
				buf.writeFloat(f.width());
				buf.writeFloat(f.peak());
				buf.writeVarInt(f.age());
				buf.writeVarInt(f.duration());
			}
		}

		private static FalloutPayload read(RegistryFriendlyByteBuf buf) {
			int n = Math.min(64, buf.readVarInt());
			List<FalloutPlume> plumes = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				plumes.add(new FalloutPlume(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readVarInt(), buf.readVarInt()));
			}
			return new FalloutPayload(plumes);
		}

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> client: how much soot is in the stratosphere (nuclear winter, 0-1). */
	public record WinterPayload(float soot) implements CustomPacketPayload {
		public static final Type<WinterPayload> TYPE = new Type<>(BallisticMissiles.id("winter"));
		public static final StreamCodec<RegistryFriendlyByteBuf, WinterPayload> CODEC = StreamCodec.of((buf, p) -> buf.writeFloat(p.soot), buf -> new WinterPayload(buf.readFloat()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** A rifle shot, for the players around the shooter: sound with distance delay and reverb, shells. */
	/** A shot: muzzle, the bullet's direction, flags {@link #LAST} (magazine now empty) and {@link #TRACER}. */
	public record GunshotPayload(int shooter, double x, double y, double z, float dx, float dy, float dz, int flags) implements CustomPacketPayload {
		public static final int LAST = 1;
		public static final int TRACER = 2;
		public static final Type<GunshotPayload> TYPE = new Type<>(BallisticMissiles.id("gunshot"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GunshotPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, GunshotPayload::shooter,
			ByteBufCodecs.DOUBLE, GunshotPayload::x,
			ByteBufCodecs.DOUBLE, GunshotPayload::y,
			ByteBufCodecs.DOUBLE, GunshotPayload::z,
			ByteBufCodecs.FLOAT, GunshotPayload::dx,
			ByteBufCodecs.FLOAT, GunshotPayload::dy,
			ByteBufCodecs.FLOAT, GunshotPayload::dz,
			ByteBufCodecs.VAR_INT, GunshotPayload::flags,
			GunshotPayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** A bullet hole at (x, y, z) on face {@code face} (Direction 3D data value), {@code kind}: 0 rock, 1 wood, 2 metal, 3 earth. */
	public record BulletHolePayload(double x, double y, double z, int face, int kind) implements CustomPacketPayload {
		public static final Type<BulletHolePayload> TYPE = new Type<>(BallisticMissiles.id("bullet_hole"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BulletHolePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.DOUBLE, BulletHolePayload::x,
			ByteBufCodecs.DOUBLE, BulletHolePayload::y,
			ByteBufCodecs.DOUBLE, BulletHolePayload::z,
			ByteBufCodecs.VAR_INT, BulletHolePayload::face,
			ByteBufCodecs.VAR_INT, BulletHolePayload::kind,
			BulletHolePayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Gun keys: {@link #RELOAD}, {@link #RELOAD_SWITCH} (other ammunition), {@link #SELECTOR}. */
	public record GunInputPayload(int action) implements CustomPacketPayload {
		public static final int RELOAD = 0;
		public static final int RELOAD_SWITCH = 1;
		public static final int SELECTOR = 2;
		public static final int RPG_FIRE = 3;
		public static final int TRIGGER_DOWN = 4;
		public static final int TRIGGER_UP = 5;
		public static final Type<GunInputPayload> TYPE = new Type<>(BallisticMissiles.id("gun_input"));
		public static final StreamCodec<RegistryFriendlyByteBuf, GunInputPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, GunInputPayload::action, GunInputPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void init() {
		PayloadTypeRegistry.playS2C().register(GunshotPayload.TYPE, GunshotPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(BulletHolePayload.TYPE, BulletHolePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(GunInputPayload.TYPE, GunInputPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(GunInputPayload.TYPE, (payload, context) -> {
			switch (payload.action()) {
				case GunInputPayload.RELOAD -> de.rcm.ballistic.gun.AkItem.requestReload(context.player(), false);
				case GunInputPayload.RELOAD_SWITCH -> de.rcm.ballistic.gun.AkItem.requestReload(context.player(), true);
				case GunInputPayload.SELECTOR -> de.rcm.ballistic.gun.AkItem.cycleMode(context.player());
				case GunInputPayload.TRIGGER_DOWN -> de.rcm.ballistic.gun.AkItem.trigger(context.player(), true);
				case GunInputPayload.TRIGGER_UP -> de.rcm.ballistic.gun.AkItem.trigger(context.player(), false);
				case GunInputPayload.RPG_FIRE -> de.rcm.ballistic.item.RocketLauncherItem.serverTrigger(context.player());
				default -> {
				}
			}
		});
		PayloadTypeRegistry.playS2C().register(WinterPayload.TYPE, WinterPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(FalloutPayload.TYPE, FalloutPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(DetonationPayload.TYPE, DetonationPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(RadarDataPayload.TYPE, RadarDataPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(SetTargetPayload.TYPE, SetTargetPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(DesignatorActionPayload.TYPE, DesignatorActionPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RadarClosePayload.TYPE, RadarClosePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(CommandDataPayload.TYPE, CommandDataPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(FarTrackPayload.TYPE, FarTrackPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(CommandActionPayload.TYPE, CommandActionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(CommandActionPayload.TYPE, (payload, context) -> {
			var player = context.player();
			if (player.level().isLoaded(payload.center()) && player.level().getBlockEntity(payload.center()) instanceof de.rcm.ballistic.block.CommandCenterBlockEntity center) {
				center.handleAction(player, payload);
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(SetTargetPayload.TYPE, (payload, context) -> {
			BlockPos pos = payload.pos();
			if (Math.abs(pos.getX()) > 29_999_984 || Math.abs(pos.getZ()) > 29_999_984) {
				return;
			}
			InteractionHand hand = payload.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
			TargetDesignatorItem.applyTarget(context.player(), hand, pos, payload.surface());
		});
		ServerPlayNetworking.registerGlobalReceiver(DesignatorActionPayload.TYPE, (payload, context) -> {
			InteractionHand hand = payload.offhand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
			TargetDesignatorItem.handleAction(context.player(), hand, payload.action(), payload.index(), payload.text());
		});
		ServerPlayNetworking.registerGlobalReceiver(RadarClosePayload.TYPE, (payload, context) -> {
			if (context.player().level().isLoaded(payload.radar()) && context.player().level().getBlockEntity(payload.radar()) instanceof RadarBlockEntity radar) {
				radar.removeViewer(context.player().getUUID());
			}
		});
	}
}
