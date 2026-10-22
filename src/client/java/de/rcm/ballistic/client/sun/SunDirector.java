package de.rcm.ballistic.client.sun;

import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.sun.SunTimeline;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The cutscene's shot list: where the camera stands, where it looks, how far it rolls and how wide it sees, for
 * every moment of the strike - cut like a film.
 * <ol>
 * <li>A crane shot sinking out of the sky onto the Helios tower.</li>
 * <li>Close on the coils, sliding up the tower as ring after ring lights.</li>
 * <li>The countdown from the foot of the tower, straight up the barrel, the frame tilting, the lens closing in.</li>
 * <li>The shot from across the complex, the lens kicked wide by the blast, tearing up after the round.</li>
 * <li>Chasing the round, banking with it through its arc.</li>
 * <li>At the target, low on the ground, watching it come down out of the sky.</li>
 * <li>The core collapsing in a dolly zoom: the camera falls back while the lens closes, the world stretching.</li>
 * <li>The star born: whipped back and up as it swells.</li>
 * <li>It burns: a slow arc round it.</li>
 * <li>It collapses: pushing in, closer than is safe.</li>
 * <li>Nova: the lens blown wide, the frame knocked over.</li>
 * <li>From far off and low, the fireball climbing into its mushroom, the shock rolling over the land.</li>
 * </ol>
 * The target's shots stay between the target and the land the server loads for them.
 */
public final class SunDirector {
	/** A camera: where it is, where it looks, its roll (degrees, clockwise) and its field of view (degrees). */
	public record Shot(Vec3 eye, Vec3 look, float roll, float fov) {
	}

	private SunDirector() {
	}

	private static float span(float t, float a, float b) {
		return SunTimeline.smooth((t - a) / (b - a));
	}

	private static float lin(float t, float a, float b) {
		return Mth.clamp((t - a) / (b - a), 0.0F, 1.0F);
	}

	private static Vec3 ring(Vec3 c, double angle, double r, double y) {
		return c.add(Math.cos(angle) * r, y, Math.sin(angle) * r);
	}

	/** The direction from the target back towards the launcher, flat. */
	private static Vec3 away(SunClient.Strike s) {
		Vec3 a = new Vec3(s.launcher.getX() - s.target.x, 0.0, s.launcher.getZ() - s.target.z);
		return a.lengthSqr() < 1.0 ? new Vec3(1, 0, 0) : a.normalize();
	}

	public static Shot shot(SunClient.Strike s, float t) {
		Vec3 base = new Vec3(s.launcher.getX() + 0.5, s.launcher.getY(), s.launcher.getZ() + 0.5);
		float fire = SunTimeline.FIRE;
		float impact = fire + s.flight;
		float d = t - impact;
		float r0 = SunTimeline.RADIUS;
		Vec3 c = SunTimeline.sunCentre(s.target);
		Vec3 away = away(s);
		Vec3 side = new Vec3(-away.z, 0.0, away.x);
		double awayAngle = Math.atan2(away.z, away.x);
		Shot shot;
		if (t < fire - 95.0F) {
			// 1. the crane down out of the sky
			float k = span(t, fire - 140.0F, fire - 95.0F);
			double a = 0.6 + k * 0.5;
			Vec3 eye = ring(base, a, Mth.lerp(k, 120.0F, 58.0F), Mth.lerp(k, 85.0F, 20.0F));
			shot = new Shot(eye, base.add(0.0, 24.0 + 8.0 * (1.0F - k), 0.0), 0.0F, Mth.lerp(k, 58.0F, 70.0F));
		} else if (t < fire - 60.0F) {
			// 2. close on the coils, sliding up the tower
			float k = lin(t, fire - 95.0F, fire - 60.0F);
			double a = 1.4 + k * 0.35;
			double y = Mth.lerp(SunTimeline.smooth(k), 6.0F, SunTimeline.RING_BASE + SunTimeline.RINGS * SunTimeline.RING_STEP);
			Vec3 eye = ring(base, a, 13.0, y);
			shot = new Shot(eye, base.add(0.0, y + 9.0, 0.0), -4.0F + 6.0F * k, 82.0F);
		} else if (t < fire) {
			// 3. the countdown, from the foot of the tower up the barrel
			float k = lin(t, fire - 60.0F, fire);
			Vec3 eye = ring(base, 2.3, 9.0, 2.2);
			shot = new Shot(eye, SunTimeline.muzzle(s.launcher), 14.0F * k * k, Mth.lerp(k * k, 88.0F, 52.0F));
		} else if (t < fire + 32.0F) {
			// 4. the shot, from across the complex: the lens kicked wide, tearing up after the round
			float k = t - fire;
			Vec3 eye = ring(base, 0.9, 72.0, 9.0);
			double f = Mth.clamp(k / s.flight, 0.0, 1.0);
			Vec3 look = SunTimeline.flight(s.launcher, s.target, f).add(0.0, -6.0 * (1.0 - lin(k, 0.0F, 10.0F)), 0.0);
			float kick = (float) Math.exp(-k / 6.0F);
			shot = new Shot(eye, look, -6.0F * kick, 72.0F + 34.0F * kick * lin(k, 0.0F, 1.5F));
		} else if (t < impact - 110.0F) {
			// 5. the chase, banking through the arc
			double f = Mth.clamp((t - fire) / s.flight, 0.0, 1.0);
			Vec3 at = SunTimeline.flight(s.launcher, s.target, f);
			Vec3 dir = SunTimeline.heading(s.launcher, s.target, f);
			Vec3 ahead = SunTimeline.heading(s.launcher, s.target, Math.min(1.0, f + 0.03));
			Vec3 sideways = dir.cross(new Vec3(0, 1, 0));
			sideways = sideways.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : sideways.normalize();
			// bank into the pull of the arc
			float bank = (float) Mth.clamp(ahead.subtract(dir).dot(new Vec3(0, -1, 0)) * 900.0, -18.0, 18.0);
			Vec3 eye = at.subtract(dir.scale(22.0)).add(0.0, 5.0, 0.0).add(sideways.scale(5.0));
			shot = new Shot(eye, at.add(dir.scale(34.0)), bank, 94.0F);
		} else if (t < impact - 18.0F) {
			// 6. at the target, low, the round coming down out of the sky
			float k = lin(t, impact - 110.0F, impact - 18.0F);
			Vec3 eye = s.target.add(away.scale(r0 * 2.3)).add(side.scale(r0 * 0.5)).add(0.0, 5.0, 0.0);
			Vec3 round = s.round(t);
			Vec3 look = round == null ? c : round;
			look = look.scale(0.85).add(c.scale(0.15));
			shot = new Shot(eye, look, 3.0F * k, Mth.lerp(k, 64.0F, 74.0F));
		} else if (d < SunTimeline.IMPLODE) {
			// 7. the core collapsing: a dolly zoom - backing away while the lens closes in
			float k = span(d, -18.0F, SunTimeline.IMPLODE);
			double dist = Mth.lerp(k, r0 * 1.5F, r0 * 2.7F);
			float fov = (float) Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(40.0)) * r0 * 1.5 / dist));
			Vec3 eye = c.add(away.scale(dist)).add(side.scale(dist * 0.18)).add(0.0, r0 * 0.15, 0.0);
			shot = new Shot(eye, c, 0.0F, fov);
		} else if (d < SunTimeline.GROWN) {
			// 8. the star born: whipped back and up as it swells
			float k = span(d, SunTimeline.IMPLODE, SunTimeline.IMPLODE + 70.0F);
			double dist = Mth.lerp(k, r0 * 2.7F, r0 * 3.7F);
			Vec3 eye = c.add(away.scale(dist)).add(side.scale(r0 * 0.5)).add(0.0, Mth.lerp(k, r0 * 0.15F, r0 * 1.1F), 0.0);
			shot = new Shot(eye, c.add(0.0, r0 * 0.2, 0.0), -8.0F * (float) Math.sin(k * Math.PI), Mth.lerp(k, 46.0F, 78.0F));
		} else if (d < SunTimeline.BURN_END) {
			// 9. it burns: a slow arc round it, within the land loaded for it
			float k = lin(d, SunTimeline.GROWN, SunTimeline.BURN_END);
			double a = awayAngle + Math.toRadians(-25.0 + 50.0 * k);
			Vec3 eye = ring(c, a, r0 * 3.5, r0 * (0.9 - 0.5 * k));
			shot = new Shot(eye, c, 3.0F * Mth.sin(k * 5.0F), 72.0F);
		} else if (d < SunTimeline.NOVA) {
			// 10. it collapses: pushing in, closer than is safe
			float k = span(d, SunTimeline.BURN_END, SunTimeline.NOVA);
			double a = awayAngle + Math.toRadians(25.0);
			Vec3 eye = ring(c, a, r0 * Mth.lerp(k, 3.5F, 2.3F), r0 * 0.4);
			shot = new Shot(eye, c, 4.0F * k, Mth.lerp(k, 72.0F, 54.0F));
		} else if (d < SunTimeline.NOVA + 70.0F) {
			// 11. nova: the lens blown wide, the frame knocked over
			float k = d - SunTimeline.NOVA;
			double a = awayAngle + Math.toRadians(25.0);
			Vec3 eye = ring(c, a, r0 * (2.3F + 0.6F * lin(k, 0.0F, 70.0F)), r0 * 0.4);
			float kick = (float) Math.exp(-k / 9.0F);
			shot = new Shot(eye, c.add(0.0, r0 * 0.6 * lin(k, 0.0F, 70.0F), 0.0), -16.0F * kick * lin(k, 0.0F, 2.0F) + 3.0F,
				54.0F + 50.0F * kick * lin(k, 0.0F, 1.0F) + 16.0F * (1.0F - kick));
		} else {
			// 12. from far off and low: the fireball climbing into its mushroom, the shock rolling over the land
			float k = lin(d, SunTimeline.NOVA + 70.0F, SunTimeline.NOVA + 330.0F);
			Vec3 eye = s.target.add(away.scale(r0 * Mth.lerp(k, 3.6F, 3.95F))).add(side.scale(-r0 * 0.6)).add(0.0, 4.0, 0.0);
			double top = c.y + r0 * (0.4 + 1.6 * k);
			shot = new Shot(eye, new Vec3(c.x, top, c.z), 2.0F * Mth.sin(k * 3.0F), 80.0F);
		}
		// never inside the ground
		Minecraft mc = Minecraft.getInstance();
		Vec3 eye = shot.eye;
		if (mc.level != null) {
			double ground = ClientEffects.groundY(mc, eye.x, eye.y - 50.0, eye.z);
			if (mc.level.hasChunkAt(BlockPos.containing(eye)) && eye.y < ground + 2.0) {
				eye = new Vec3(eye.x, ground + 2.0, eye.z);
			}
		}
		return new Shot(eye, shot.look, shot.roll, Mth.clamp(shot.fov, 20.0F, 120.0F));
	}
}
