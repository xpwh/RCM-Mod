package de.rcm.ballistic.defense;

import de.rcm.ballistic.entity.JetEntity;
import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.ReentryVehicleEntity;
import de.rcm.ballistic.network.ModNetworking.FarTrack;
import de.rcm.ballistic.network.ModNetworking.FarTrackPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Long-range view of everything in the air, for Distant Horizons and other far-render setups:
 * vanilla only sends entities within the player's tracking range, so a missile a few kilometres
 * away would be invisible against far terrain. Every few ticks each player gets the positions of
 * all missiles, warheads, rockets and aircraft within {@value #RANGE} blocks; the client draws the
 * ones it has no entity for as contrails and, at night, glowing points.
 */
public final class FarTracker {
	public static final double RANGE = 4000.0;
	private static final int INTERVAL = 4;

	private FarTracker() {
	}

	public static void tick(ServerLevel level) {
		if (level.getGameTime() % INTERVAL != 0 || level.players().isEmpty()) {
			return;
		}
		List<AirThreat> threats = ThreatTracker.threats(level);
		if (threats.isEmpty()) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			List<FarTrack> tracks = new ArrayList<>();
			for (AirThreat t : threats) {
				Entity e = t.asEntity();
				if (t.radarCrossSection() < 0.01 && Math.hypot(e.getX() - player.getX(), e.getZ() - player.getZ()) > 120.0) {
					continue; // stealthy: not seen from far off (only its contrail gives it away)
				}
				if (Math.hypot(e.getX() - player.getX(), e.getZ() - player.getZ()) > RANGE) {
					continue;
				}
				Vec3 v = t.threatVelocity();
				int kind;
				boolean burning;
				if (e instanceof MissileEntity m) {
					kind = 0;
					burning = m.isBoosterBurning() || m.isJetRunning();
				} else if (e instanceof ReentryVehicleEntity) {
					kind = 1;
					burning = true; // re-entry glow
				} else if (e instanceof JetEntity jet) {
					kind = 2;
					burning = jet.isAfterburner();
				} else {
					kind = 3;
					burning = true;
				}
				tracks.add(new FarTrack(e.getId(), kind, (float) e.getX(), (float) e.getY(), (float) e.getZ(), (float) v.x, (float) v.y, (float) v.z, burning));
				if (tracks.size() >= 128) {
					break;
				}
			}
			if (!tracks.isEmpty()) {
				ServerPlayNetworking.send(player, new FarTrackPayload(tracks));
			}
		}
	}
}
