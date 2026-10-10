package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.defense.DefenseNetwork;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.defense.ThreatTracker;
import de.rcm.ballistic.entity.InterceptorEntity;
import de.rcm.ballistic.network.ModNetworking.RadarDataPayload;
import de.rcm.ballistic.network.ModNetworking.SiteInfo;
import de.rcm.ballistic.network.ModNetworking.TrackInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Rotating early-warning radar.
 * <ul>
 *   <li>The antenna turns once every {@value #SWEEP_PERIOD} ticks; an object is only painted when the beam passes over it.</li>
 *   <li>Detection range follows the radar equation: it scales with the fourth root of the radar cross-section, so
 *       ICBMs are seen far out and small cruise missiles late.</li>
 *   <li>Terrain blocks the beam (radar horizon) and very low fliers vanish in the ground clutter.</li>
 *   <li>Every object becomes a numbered track. It is classified after two paints and its predicted impact point
 *       gets more precise with every further paint.</li>
 *   <li>Tracks heading into the protected zone raise the alarm, set the redstone output and drive the
 *       comparator output (stronger the closer the impact).</li>
 * </ul>
 * Right-click opens the radar scope.
 */
public class RadarBlockEntity extends BlockEntity {
	public static final double RANGE = 512.0;
	/** Missiles aimed within this distance of the radar count as a threat. */
	public static final double PROTECTED_RADIUS = 320.0;
	/** Players within this distance of the radar get the warnings. */
	public static final double WARN_RADIUS = 128.0;
	public static final int SWEEP_PERIOD = 40;

	private static final class Track {
		final int number;
		final Vec3 errorDir;
		Vec3 pos = Vec3.ZERO;
		Vec3 vel = Vec3.ZERO;
		long lastSeen;
		int paints;
		AirThreat.ThreatClass cls = AirThreat.ThreatClass.UNKNOWN;
		String nameKey = "";
		Vec3 impact = Vec3.ZERO;
		double impactError = 999;
		int eta;
		boolean threat;

		Track(int number, RandomSource random) {
			this.number = number;
			double a = random.nextDouble() * Mth.TWO_PI;
			this.errorDir = new Vec3(Math.cos(a), 0, Math.sin(a)).scale(0.4 + random.nextDouble() * 0.6);
		}
	}

	private final Map<Integer, Track> tracks = new HashMap<>();
	private final Set<UUID> viewers = new HashSet<>();
	private int nextNumber = 1;
	private int alarmCooldown;
	private int signal;
	private @Nullable UUID owner;
	/** Someone else's jammer is close: tracks come out smeared. */
	private boolean jammed;

	public RadarBlockEntity(BlockPos pos, BlockState state) {
		super(ModRegistry.RADAR_BE, pos, state);
	}

	/** Antenna azimuth in degrees (0 = north, clockwise) at a game time. */
	public static float azimuth(long gameTime, float offset) {
		return ((gameTime % SWEEP_PERIOD) / (float) SWEEP_PERIOD * 360.0F + offset) % 360.0F;
	}

	private float sweepOffset() {
		return (float) Math.floorMod(this.worldPosition.hashCode(), 360);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, RadarBlockEntity radar) {
		if (!(level instanceof ServerLevel server)) {
			return;
		}
		DefenseNetwork.register(level, pos, DefenseNetwork.Kind.RADAR);
		long now = level.getGameTime();
		if (now % 20 == 0) {
			radar.jammed = JammerBlockEntity.jamsRadar(level, pos, radar.owner);
		}
		int jammed = EmpManager.jammedTicks(level, pos);
		if (jammed > 0) {
			radar.tracks.clear();
			radar.setSignal(server, pos, state, false, 0);
		} else {
			radar.sweep(server, now);
			if (now % 10 == 0) {
				radar.evaluate(server, pos, state);
			}
		}
		if (!radar.viewers.isEmpty() && now % 4 == 0) {
			radar.sendPicture(server, null, jammed);
		}
	}

	// ------------------------------------------------------------------ detection

	private void sweep(ServerLevel level, long now) {
		float offset = this.sweepOffset();
		float from = azimuth(now - 1, offset);
		float to = azimuth(now, offset);
		Vec3 antenna = Vec3.atCenterOf(this.worldPosition).add(0, 0.9, 0);
		for (AirThreat threat : ThreatTracker.threats(level)) {
			Entity e = threat.asEntity();
			Vec3 p = e.position();
			double dx = p.x - antenna.x;
			double dz = p.z - antenna.z;
			float bearing = bearing(dx, dz);
			if (!inSector(bearing, from, to)) {
				continue;
			}
			if (!detects(level, antenna, threat, p)) {
				continue;
			}
			this.paint(level, now, threat, p);
		}
		// tracks that were not painted for more than two turns are lost
		Iterator<Track> it = this.tracks.values().iterator();
		while (it.hasNext()) {
			if (now - it.next().lastSeen > SWEEP_PERIOD * 2L + 4) {
				it.remove();
			}
		}
	}

	private static boolean inSector(float bearing, float from, float to) {
		if (from <= to) {
			return bearing >= from && bearing < to;
		}
		return bearing >= from || bearing < to; // wrapped past north
	}

	public static float bearing(double dx, double dz) {
		float b = (float) Math.toDegrees(Math.atan2(dx, -dz));
		return b < 0 ? b + 360.0F : b;
	}

	private static boolean detects(ServerLevel level, Vec3 antenna, AirThreat threat, Vec3 p) {
		double dx = p.x - antenna.x;
		double dz = p.z - antenna.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		double maxRange = RANGE * Math.pow(threat.radarCrossSection(), 0.25);
		if (horizontal > maxRange) {
			return false;
		}
		int px = Mth.floor(p.x);
		int pz = Mth.floor(p.z);
		if (horizontal > 40 && level.hasChunk(px >> 4, pz >> 4)) {
			int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
			if (p.y - ground < 5) {
				return false; // lost in the ground clutter
			}
		}
		// terrain masking along the beam (only where the terrain is known)
		int steps = (int) (horizontal / 8.0);
		for (int i = 1; i < steps; i++) {
			double f = (double) i / steps;
			double x = antenna.x + dx * f;
			double z = antenna.z + dz * f;
			int bx = Mth.floor(x);
			int bz = Mth.floor(z);
			if (!level.hasChunk(bx >> 4, bz >> 4)) {
				continue;
			}
			double beamY = antenna.y + (p.y - antenna.y) * f;
			if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) > beamY + 1.0) {
				return false;
			}
		}
		return true;
	}

	private void paint(ServerLevel level, long now, AirThreat threat, Vec3 p) {
		int id = threat.asEntity().getId();
		Track track = this.tracks.get(id);
		if (track == null) {
			track = new Track(this.nextNumber, level.getRandom());
			this.nextNumber = this.nextNumber % 99 + 1;
			this.tracks.put(id, track);
		}
		track.vel = threat.threatVelocity();
		if (this.jammed) {
			// jamming noise: range and bearing jump around, the impact estimate is nearly worthless
			RandomSource r = level.getRandom();
			p = p.add(r.nextGaussian() * 30.0, r.nextGaussian() * 8.0, r.nextGaussian() * 30.0);
		}
		track.pos = p;
		track.lastSeen = now;
		track.paints++;
		if (track.paints >= 2) {
			track.cls = threat.threatClass();
			track.nameKey = threat.nameKey();
		}
		track.eta = threat.etaTicks();
		// impact point estimate: converges as the track matures
		Vec3 trueImpact = threat.predictedImpact();
		if (track.cls == AirThreat.ThreatClass.CRUISE || track.cls == AirThreat.ThreatClass.UNKNOWN) {
			// a cruise missile's goal is unknown: extrapolate the heading
			Vec3 v = new Vec3(track.vel.x, 0, track.vel.z);
			double toGo = Math.hypot(trueImpact.x - p.x, trueImpact.z - p.z);
			track.impactError = Math.max(8.0, Math.min(120.0, toGo * 0.25));
			track.impact = v.lengthSqr() < 1.0E-4 ? trueImpact : new Vec3(p.x, trueImpact.y, p.z).add(v.normalize().scale(toGo));
		} else {
			track.impactError = Math.max(3.0, 160.0 / (1.0 + track.paints * 0.7));
			track.impact = trueImpact.add(track.errorDir.scale(track.impactError));
		}
		if (this.jammed) {
			track.impactError = Math.min(400.0, track.impactError * 4.0 + 60.0);
			track.impact = track.impact.add(level.getRandom().nextGaussian() * 60.0, 0, level.getRandom().nextGaussian() * 60.0);
		}
		Vec3 here = Vec3.atCenterOf(this.worldPosition);
		track.threat = Math.hypot(track.impact.x - here.x, track.impact.z - here.z) <= PROTECTED_RADIUS + track.impactError * 0.5;
	}

	// ------------------------------------------------------------------ alarm

	private void evaluate(ServerLevel level, BlockPos pos, BlockState state) {
		Track closest = null;
		int count = 0;
		for (Track t : this.tracks.values()) {
			if (!t.threat) {
				continue;
			}
			count++;
			if (closest == null || t.eta < closest.eta) {
				closest = t;
			}
		}
		if (closest == null) {
			this.alarmCooldown = 0;
			this.setSignal(level, pos, state, false, 0);
			return;
		}
		int seconds = Math.max(0, closest.eta / 20);
		this.setSignal(level, pos, state, true, Mth.clamp(15 - seconds / 4, 1, 15));

		if (this.alarmCooldown-- <= 0) {
			this.alarmCooldown = 4;
			level.playSound(null, pos, ModRegistry.RADAR_ALARM, SoundSource.BLOCKS, 6.0F, 1.0F);
		}
		Vec3 here = Vec3.atCenterOf(pos);
		Vec3 p = closest.pos;
		Component cls = Component.translatable("radar.ballisticmissiles.class." + closest.cls.key);
		Component from = Component.translatable("radar.ballisticmissiles.compass." + compass(bearing(p.x - here.x, p.z - here.z)));
		MutableComponent msg = Component.literal("⚠ ")
			.append(Component.translatable(
				"message.ballisticmissiles.radar_track", String.format("T%02d", closest.number), cls, from, (int) Math.hypot(p.x - here.x, p.z - here.z),
				(int) (p.y - here.y), (int) closest.impact.x, (int) closest.impact.z, (int) closest.impactError, seconds
			));
		if (count > 1) {
			msg.append(Component.translatable("message.ballisticmissiles.radar_more", count - 1));
		}
		Component out = msg.withStyle(seconds <= 5 ? ChatFormatting.DARK_RED : ChatFormatting.RED, ChatFormatting.BOLD);
		for (ServerPlayer player : level.players()) {
			if (player.position().distanceToSqr(here) < WARN_RADIUS * WARN_RADIUS && !this.viewers.contains(player.getUUID())) {
				player.displayClientMessage(out, true);
			}
		}
	}

	public static String compass(float bearing) {
		String[] names = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
		return names[Math.floorMod(Math.round(bearing / 45.0F), 8)];
	}

	private void setSignal(ServerLevel level, BlockPos pos, BlockState state, boolean powered, int strength) {
		if (state.getValue(RadarBlock.POWERED) != powered) {
			level.setBlock(pos, state.setValue(RadarBlock.POWERED, powered), 3);
		}
		if (this.signal != strength) {
			this.signal = strength;
			level.updateNeighbourForOutputSignal(pos, state.getBlock());
		}
	}

	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
		this.setChanged();
	}

	public int comparatorSignal() {
		return this.signal;
	}

	// ------------------------------------------------------------------ scope

	public void addViewer(ServerPlayer player) {
		this.viewers.add(player.getUUID());
		if (this.level instanceof ServerLevel server) {
			this.sendPicture(server, player, EmpManager.jammedTicks(server, this.worldPosition));
		}
	}

	public void removeViewer(UUID player) {
		this.viewers.remove(player);
	}

	private void sendPicture(ServerLevel level, @Nullable ServerPlayer opening, int jammed) {
		Vec3 here = Vec3.atCenterOf(this.worldPosition);
		long now = level.getGameTime();
		List<TrackInfo> infos = new ArrayList<>();
		for (Track t : this.tracks.values()) {
			infos.add(new TrackInfo(
				t.number, t.cls.ordinal(), t.nameKey, (float) t.pos.x, (float) t.pos.y, (float) t.pos.z, (float) t.vel.x, (float) t.vel.z, (float) t.impact.x,
				(float) t.impact.z, (float) t.impactError, t.eta, t.threat, (int) (now - t.lastSeen)
			));
		}
		List<SiteInfo> sites = new ArrayList<>();
		for (DefenseNetwork.Kind kind : DefenseNetwork.Kind.values()) {
			for (BlockPos site : DefenseNetwork.find(level, kind, here, RANGE)) {
				sites.add(new SiteInfo(site, kind.ordinal()));
			}
		}
		List<BlockPos> interceptors = new ArrayList<>();
		for (InterceptorEntity i : level.getEntitiesOfClass(InterceptorEntity.class, new AABB(this.worldPosition).inflate(RANGE, 600, RANGE))) {
			interceptors.add(i.blockPosition());
		}
		Iterator<UUID> it = this.viewers.iterator();
		while (it.hasNext()) {
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(it.next());
			if (player == null || player.level() != level || player.position().distanceToSqr(here) > 16 * 16) {
				it.remove();
				continue;
			}
			RadarDataPayload payload = new RadarDataPayload(
				this.worldPosition, player == opening, jammed, (int) RANGE, (int) PROTECTED_RADIUS, this.sweepOffset(), infos, sites, interceptors
			);
			ServerPlayNetworking.send(player, payload);
		}
	}

	@Override
	public void setRemoved() {
		if (this.level != null && !this.level.isClientSide()) {
			DefenseNetwork.unregister(this.level, this.worldPosition);
		}
		super.setRemoved();
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (this.owner != null) {
			output.store("Owner", UUIDUtil.CODEC, this.owner);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
	}
}
