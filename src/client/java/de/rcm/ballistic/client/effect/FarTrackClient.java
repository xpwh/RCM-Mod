package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.client.particle.CloudParticle;
import de.rcm.ballistic.network.ModNetworking.FarTrack;
import de.rcm.ballistic.network.ModNetworking.FarTrackPayload;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Draws missiles, warheads, rockets and aircraft that are too far away to exist as entities on this
 * client (beyond the entity tracking range - with Distant Horizons the terrain behind them is still
 * visible): positions come from the server's long-range tracks and are extrapolated between
 * updates. A burning motor leaves a contrail and, at night, shows as an orange point of light;
 * aircraft leave thin contrails. Everything is sized with distance so it stays visible.
 */
public final class FarTrackClient {
	private static final int STALE_TICKS = 12;

	private static final class Track {
		FarTrack data;
		int age;
	}

	private static final Map<Integer, Track> TRACKS = new HashMap<>();

	private FarTrackClient() {
	}

	public static void receive(FarTrackPayload payload) {
		for (FarTrack t : payload.tracks()) {
			Track track = TRACKS.computeIfAbsent(t.id(), k -> new Track());
			track.data = t;
			track.age = 0;
		}
	}

	public static void clear() {
		TRACKS.clear();
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) {
			if (mc.level == null) {
				TRACKS.clear();
			}
			return;
		}
		Vec3 eye = mc.gameRenderer.getMainCamera().position();
		float night = night(mc.level.getDayTime());
		Iterator<Track> it = TRACKS.values().iterator();
		while (it.hasNext()) {
			Track track = it.next();
			if (++track.age > STALE_TICKS) {
				it.remove();
				continue;
			}
			FarTrack t = track.data;
			if (mc.level.getEntity(t.id()) != null) {
				continue; // close enough to be a real entity: it draws itself
			}
			Vec3 v = new Vec3(t.vx(), t.vy(), t.vz());
			Vec3 p = new Vec3(t.x(), t.y(), t.z()).add(v.scale(track.age));
			double d = p.distanceTo(eye);
			float size = (float) Math.max(2.0, d * 0.004);
			if (t.burning() || t.kind() == 2) {
				// the same contrail the entity leaves when close (same key, so it continues seamlessly);
				// thicker far away so it stays visible, and a short smoke trail even down low
				double strength = Math.max(Contrails.altitudeFactor(p.y), t.kind() == 2 ? 0.0 : 0.25);
				double width = Math.max(t.kind() == 2 ? 0.9 : 2.2, size * (t.kind() == 2 ? 0.5 : 0.9));
				Contrails.trail(t.id() * 4, p, width, strength);
			}
			if (t.burning() && night > 0.02F) {
				// the motor (or the re-entry plasma) as a short-lived bright point, renewed every tick
				CloudParticle glow = ClientEffects.cloud(true, p.x, p.y, p.z, 0, 0, 0);
				if (glow != null) {
					float g = (float) Math.max(1.5, d * 0.008) * (t.kind() == 2 ? 0.6F : 1.0F);
					glow.configure(2, g, g, 0xFFD890, 0xFF8030, night);
				}
			}
		}
	}

	/** 0 by day, 1 at night, with dusk and dawn in between. */
	static float night(long dayTime) {
		float t = dayTime % 24000L;
		if (t < 12000.0F) {
			return 0.0F;
		}
		if (t < 13800.0F) {
			return (t - 12000.0F) / 1800.0F;
		}
		if (t < 22200.0F) {
			return 1.0F;
		}
		return Math.max(0.0F, (24000.0F - t) / 1800.0F);
	}
}
