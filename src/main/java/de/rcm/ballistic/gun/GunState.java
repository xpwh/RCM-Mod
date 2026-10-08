package de.rcm.ballistic.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * State of a rifle, kept on the item stack (and so synced to every player who sees it, for the
 * animations):
 *
 * @param rounds      cartridges in the gun (magazine plus the one in the chamber)
 * @param ammo        what the loaded magazine holds: {@link #BALL} or {@link #TRACER}
 * @param hasMag      whether a magazine is in the gun at all
 * @param mode        {@link #AUTO}, {@link #SEMI} or {@link #SAFE}
 * @param lastShot    game time of the last shot (recoil, bolt and muzzle flash animations)
 * @param reloadStart game time the current reload started, or -1
 * @param reloadKind  {@link #TACTICAL} (round still chambered) or {@link #EMPTY} (bolt must be charged)
 * @param reloadAmmo  the kind of magazine going in
 */
public record GunState(int rounds, int ammo, boolean hasMag, int mode, long lastShot, long reloadStart, int reloadKind, int reloadAmmo) {
	public static final int BALL = 0;
	public static final int TRACER = 1;
	public static final int AUTO = 0;
	public static final int SEMI = 1;
	public static final int SAFE = 2;
	public static final int TACTICAL = 1;
	public static final int EMPTY = 2;

	public static final GunState DEFAULT = new GunState(0, BALL, false, AUTO, -100L, -1L, 0, BALL);

	public static final Codec<GunState> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("rounds").forGetter(GunState::rounds),
		Codec.INT.fieldOf("ammo").forGetter(GunState::ammo),
		Codec.BOOL.fieldOf("mag").forGetter(GunState::hasMag),
		Codec.INT.fieldOf("mode").forGetter(GunState::mode),
		Codec.LONG.optionalFieldOf("last_shot", -100L).forGetter(GunState::lastShot),
		Codec.LONG.optionalFieldOf("reload_start", -1L).forGetter(GunState::reloadStart),
		Codec.INT.optionalFieldOf("reload_kind", 0).forGetter(GunState::reloadKind),
		Codec.INT.optionalFieldOf("reload_ammo", 0).forGetter(GunState::reloadAmmo)
	).apply(i, GunState::new));

	public static final StreamCodec<ByteBuf, GunState> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, GunState::rounds,
		ByteBufCodecs.VAR_INT, GunState::ammo,
		ByteBufCodecs.BOOL, GunState::hasMag,
		ByteBufCodecs.VAR_INT, GunState::mode,
		ByteBufCodecs.VAR_LONG, GunState::lastShot,
		ByteBufCodecs.VAR_LONG, GunState::reloadStart,
		ByteBufCodecs.VAR_INT, GunState::reloadKind,
		ByteBufCodecs.VAR_INT, GunState::reloadAmmo,
		GunState::new
	);

	public boolean reloading() {
		return this.reloadStart >= 0;
	}

	public GunState withRounds(int rounds) {
		return new GunState(rounds, this.ammo, this.hasMag, this.mode, this.lastShot, this.reloadStart, this.reloadKind, this.reloadAmmo);
	}

	public GunState fired(int rounds, long time) {
		return new GunState(rounds, this.ammo, this.hasMag, this.mode, time, this.reloadStart, this.reloadKind, this.reloadAmmo);
	}

	public GunState withMode(int mode) {
		return new GunState(this.rounds, this.ammo, this.hasMag, mode, this.lastShot, this.reloadStart, this.reloadKind, this.reloadAmmo);
	}

	public GunState startReload(long time, int kind, int ammo) {
		return new GunState(this.rounds, this.ammo, this.hasMag, this.mode, this.lastShot, time, kind, ammo);
	}

	public GunState loaded(int rounds, int ammo) {
		return new GunState(rounds, ammo, true, this.mode, this.lastShot, -1L, 0, ammo);
	}

	public GunState cancelReload() {
		return new GunState(this.rounds, this.ammo, this.hasMag, this.mode, this.lastShot, -1L, 0, this.reloadAmmo);
	}
}
