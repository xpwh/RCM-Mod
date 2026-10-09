package de.rcm.ballistic.client.fighter;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.effect.Contrails;
import de.rcm.ballistic.client.effect.DynamicLights;
import de.rcm.ballistic.client.effect.SmokeField;
import de.rcm.ballistic.client.render.FighterRenderer;
import de.rcm.ballistic.client.render.TracerFx;
import de.rcm.ballistic.client.sound.FighterSound;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.entity.AirMissileEntity;
import de.rcm.ballistic.entity.FighterEntity;
import de.rcm.ballistic.entity.FighterType;
import de.rcm.ballistic.network.ModNetworking.FighterInputPayload;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The cockpit of the flyable F-35 / F-22 - controls, HUD, target lock - and everything the jets do in
 * the air for whoever watches: engine sound, contrails, wingtip vapour, the sonic boom, cannon tracers,
 * missile smoke trails.
 * <p>
 * Controls: look where you want to fly (the jet follows), W/S throttle, Ctrl afterburner (at full
 * throttle), A/D roll, left mouse cannon, right mouse missile, F flares, sneak to eject (on the ground:
 * climb out).
 */
public final class FighterClient {
	private static @Nullable FighterEntity flying;
	private static int pendingAction;
	// the target lock: what the seeker is looking at, how long, and what it has locked
	private static @Nullable Entity candidate;
	private static int lockTicks;
	private static @Nullable Entity locked;
	private static final int LOCK_TIME = 20;
	private static final double LOCK_CONE = Math.cos(Math.toRadians(4.0));
	private static final double HOLD_CONE = Math.cos(Math.toRadians(25.0));
	private static final Set<Integer> SOUNDED = new HashSet<>();

	private FighterClient() {
	}

	public static void init() {
		ClientTickEvents.START_CLIENT_TICK.register(FighterClient::startTick);
		ClientTickEvents.END_CLIENT_TICK.register(FighterClient::endTick);
		HudElementRegistry.addLast(BallisticMissiles.id("fighter_hud"), (graphics, tick) -> hud(graphics, tick.getGameTimeDeltaPartialTick(false)));
	}

	public static boolean isFlying() {
		return flying != null;
	}

	/** How far the picture tilts with the bank (a part of it - full roll in a mouse-flown jet only confuses). */
	public static float cameraRoll(float partialTick) {
		return flying == null ? 0.0F : flying.getRoll(partialTick) * 0.4F;
	}

	// ------------------------------------------------------------------ controls

	private static void startTick(Minecraft mc) {
		LocalPlayer player = mc.player;
		flying = player != null && player.getVehicle() instanceof FighterEntity jet ? jet : null;
		if (flying == null) {
			return;
		}
		// the mouse buttons and F belong to the jet now
		while (mc.options.keyUse.consumeClick()) {
			pendingAction = FighterEntity.ACTION_MISSILE;
		}
		while (mc.options.keySwapOffhand.consumeClick()) {
			pendingAction = FighterEntity.ACTION_FLARES;
		}
		while (mc.options.keyAttack.consumeClick()) {
			// held state is read below
		}
		// this client flies the jet: hand it the stick before it ticks
		var o = mc.options;
		boolean free = mc.screen == null;
		// Space/Ctrl throttle (keep Space held at full power for afterburner), S pulls, W pushes, A/D roll;
		// the mouse only looks around
		float throttle = free ? (o.keyJump.isDown() ? 1.0F : 0.0F) - (o.keySprint.isDown() ? 1.0F : 0.0F) : 0.0F;
		float pitch = free ? (o.keyDown.isDown() ? 1.0F : 0.0F) - (o.keyUp.isDown() ? 1.0F : 0.0F) : 0.0F;
		float roll = free ? (o.keyRight.isDown() ? 1.0F : 0.0F) - (o.keyLeft.isDown() ? 1.0F : 0.0F) : 0.0F;
		flying.setControls(throttle, pitch, roll, free && o.keyAttack.isDown());
	}

	private static void endTick(Minecraft mc) {
		if (mc.level == null) {
			SOUNDED.clear();
			return;
		}
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof FighterEntity jet) {
				effects(mc, jet);
			} else if (e instanceof AirMissileEntity m) {
				missileEffects(m);
			}
		}
		FighterEntity jet = flying;
		LocalPlayer player = mc.player;
		if (jet == null || player == null || jet.isRemoved()) {
			locked = null;
			candidate = null;
			return;
		}
		boolean gun = mc.options.keyAttack.isDown() && mc.screen == null;
		lock(mc, player, jet);
		warnings(mc, jet);
		int action = jet.takeCrashReport() ? FighterEntity.ACTION_CRASH : pendingAction;
		Vec3 v = jet.getDeltaMovement();
		ClientPlayNetworking.send(new FighterInputPayload(jet.getId(), jet.simThrottle(), jet.simAfterburner(), jet.simRoll(), (float) jet.simSpeed(),
			jet.simG(), jet.simGear(), (float) v.x, (float) v.y, (float) v.z, gun, action, locked != null ? locked.getId() : -1));
		pendingAction = FighterEntity.ACTION_NONE;
	}

	/** The seeker's audio state for the cockpit: 0 silent (no missiles), 1 searching, 2 tracking something, 3 locked. */
	public static int seeker() {
		FighterEntity jet = flying;
		if (jet == null || jet.missiles() <= 0 || !jet.engineRunning()) {
			return 0;
		}
		if (locked != null) {
			return 3;
		}
		return candidate != null && lockTicks > 0 ? 2 : 1;
	}

	/** How far the lock has come along, 0..1. */
	public static float lockProgress() {
		return Mth.clamp(lockTicks / (float) LOCK_TIME, 0.0F, 1.0F);
	}

	/** Cockpit warnings: stall beeps, and the "pull up" warble when the ground is coming up fast. */
	private static void warnings(Minecraft mc, FighterEntity jet) {
		if (jet.onGround() || jet.isCrashing()) {
			return;
		}
		float speed = jet.speed();
		if (speed < jet.type().stallSpeed && jet.tickCount % 12 == 0) {
			mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(ModRegistry.JET_WARN_STALL, 1.0F, 0.45F));
		}
		Vec3 f = jet.forward();
		double agl = jet.getY() - mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(jet.getX()), Mth.floor(jet.getZ()));
		if (f.y < -0.2 && agl / Math.max(0.1, -f.y * speed) < 60.0 && jet.tickCount % 16 == 0) {
			mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(ModRegistry.JET_WARN_PULLUP, 1.0F, 0.5F));
		}
	}

	/** The seeker: hold a target in the small circle for a second and it locks. */
	private static void lock(Minecraft mc, Player player, FighterEntity jet) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		if (locked != null && (locked.isRemoved() || !locked.isAlive() || locked.distanceTo(jet) > AirMissileEntity.LOCK_RANGE * 1.15
			|| locked.getBoundingBox().getCenter().subtract(eye).normalize().dot(look) < HOLD_CONE)) {
			locked = null;
		}
		Entity best = null;
		double bestScore = 0.0;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == jet || e == player || e instanceof AirMissileEntity || !(e instanceof AirThreat || e instanceof LivingEntity)) {
				continue;
			}
			Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
			double d = to.length();
			if (d > AirMissileEntity.LOCK_RANGE || d < 30.0) {
				continue;
			}
			double cos = to.scale(1.0 / d).dot(look);
			if (cos < LOCK_CONE) {
				continue;
			}
			// aircraft first, then anything else in the circle
			double score = cos + (e instanceof AirThreat ? 1.0 : 0.0);
			if (score > bestScore) {
				bestScore = score;
				best = e;
			}
		}
		if (best != null && best == candidate) {
			lockTicks++;
		} else {
			candidate = best;
			lockTicks = 0;
		}
		if (candidate != null && lockTicks == LOCK_TIME && locked != candidate) {
			locked = candidate;
			player.playSound(ModRegistry.TARGET_LOCK, 0.8F, 1.3F);
		}
	}

	// ------------------------------------------------------------------ what everybody sees and hears

	/** Model space (x right, y forward, z up, about the centreline) to the world. */
	private static Vec3 toWorld(FighterEntity jet, Vec3 local, float partial) {
		Vec3 f = jet.forward(partial);
		Vec3 right = f.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 up = right.cross(f).normalize();
		double r = Math.toRadians(jet.getRoll(partial));
		Vec3 r2 = right.scale(Math.cos(r)).subtract(up.scale(Math.sin(r)));
		Vec3 u2 = up.scale(Math.cos(r)).add(right.scale(Math.sin(r)));
		Vec3 centre = jet.getPosition(partial).add(0, FighterRenderer.GEAR_HEIGHT, 0);
		return centre.add(r2.scale(local.x)).add(f.scale(local.y)).add(u2.scale(local.z));
	}

	private static void effects(Minecraft mc, FighterEntity jet) {
		FighterType type = jet.type();
		if (SOUNDED.add(jet.getId())) {
			for (FighterSound.Layer layer : new FighterSound.Layer[] {FighterSound.Layer.NEAR, FighterSound.Layer.FAR, FighterSound.Layer.AFTERBURNER,
				FighterSound.Layer.SUB}) {
				mc.getSoundManager().play(new FighterSound(jet, layer));
			}
		}
		if (mc.player != null && jet.pilot() == mc.player && !jet.clientSoundStarted) {
			jet.clientSoundStarted = true;
			for (FighterSound.Layer layer : new FighterSound.Layer[] {FighterSound.Layer.COCKPIT, FighterSound.Layer.WIND, FighterSound.Layer.GROWL,
				FighterSound.Layer.LOCK}) {
				mc.getSoundManager().play(new FighterSound(jet, layer));
			}
		} else if (jet.pilot() != mc.player) {
			jet.clientSoundStarted = false;
		}
		if (jet.isCrashing()) {
			// on fire, trailing black smoke
			Vec3 tail = toWorld(jet, new Vec3(0, -4.0, 0.3), 1.0F);
			SmokeField.trail((5_000_000 + jet.getId() * 8) + 7, tail, SmokeField.Style.exhaust(1.6F), 1.0F);
			DynamicLights.steady(tail, 0xFF7A30, 0.8F, 14.0F);
		}
		boolean airborne = !jet.onGround() && jet.speed() > 2.0F;
		Vec3 centre = jet.position().add(0, FighterRenderer.GEAR_HEIGHT, 0);
		// contrails from the engines high up (condensed exhaust), the cut when it lands
		int nozzles = type == FighterType.F22 ? 2 : 1;
		for (int i = 0; i < nozzles; i++) {
			Vec3 n = toWorld(jet, FighterRenderer.nozzle(type, i).add(0, -1.5, 0), 1.0F);
			double strength = airborne ? Contrails.altitudeFactor(n.y) : 0.0;
			if (strength > 0.0) {
				Contrails.trail((5_000_000 + jet.getId() * 8) + i, n, type == FighterType.F22 ? 0.8 : 1.0, strength);
			} else {
				Contrails.cut((5_000_000 + jet.getId() * 8) + i);
			}
			if (jet.isAfterburner()) {
				DynamicLights.steady(n, 0xFFA060, 0.9F, 16.0F);
			}
		}
		// wingtip vortices: thin white threads off the tips when it pulls hard, or in damp air at speed
		float half = type.span * 0.5F - 0.1F;
		boolean vapour = airborne && (jet.gLoad() > 3.5F || jet.mach() > 0.85F && jet.mach() < 1.05F);
		for (int s = 0; s < 2; s++) {
			int key = (5_000_000 + jet.getId() * 8) + 4 + s;
			if (vapour) {
				Vec3 tip = toWorld(jet, new Vec3(s == 0 ? -half : half, type == FighterType.F22 ? -3.3 : -2.8, -0.25), 1.0F);
				Contrails.trail(key, tip, 0.25, Math.min(1.0, (jet.gLoad() - 3.0) / 4.0 + 0.3));
			} else {
				Contrails.cut(key);
			}
		}
		// the cannon: tracers out of the gun port, a flash
		if (jet.isFiring() && jet.tickCount % 2 == 0) {
			Vec3 port = toWorld(jet, type == FighterType.F22 ? new Vec3(1.05, 4.7, 0.75) : new Vec3(-0.75, 2.0, 1.0), 1.0F);
			Vec3 f = jet.forward();
			Vec3 v = f.scale(52.0 + jet.speed()).add(ClientEffects.rand() * 0.3 - 0.15, ClientEffects.rand() * 0.3 - 0.15, ClientEffects.rand() * 0.3 - 0.15);
			TracerFx.fire(port, v, jet.pilot() != null ? jet.pilot().getId() : jet.getId());
			DynamicLights.flash(port.add(f.scale(1.0)), 0xFFC878, 0.8F, 9.0F, 50.0F);
		}
		// the sonic boom: heard the moment the Mach cone trailing the jet sweeps over you (never by the pilot)
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		float mach = jet.mach();
		boolean inCone = false;
		if (mach > 1.0F && jet.pilot() != mc.player) {
			Vec3 toEar = ear.subtract(centre);
			double d = toEar.length();
			Vec3 dir = jet.forward();
			double cone = Math.asin(1.0 / mach);
			double angle = d > 1.0E-3 ? Math.acos(Mth.clamp(dir.scale(-1).dot(toEar.scale(1.0 / d)), -1.0, 1.0)) : 0.0;
			inCone = angle < cone;
			if (inCone && !jet.clientInMachCone && d < 1600.0) {
				// the N-wave: a sharp double crack close by, a deep thud far off
				float volume = (float) Mth.clamp(1.3 - d / 1300.0, 0.25, 1.3);
				ClientEffects.playDistant(mc, ModRegistry.JET_BOOM, centre, volume, 0.95F + ClientEffects.rand() * 0.1F);
				ClientEffects.addShake((float) Math.max(0.0, 2.6 - d / 220.0));
			}
		}
		jet.clientInMachCone = inCone || jet.clientInMachCone && mach > 1.0F;
	}

	private static void missileEffects(AirMissileEntity m) {
		if (m.isBurning()) {
			Vec3 v = m.getDeltaMovement();
			Vec3 tail = m.position().subtract(v.lengthSqr() > 1.0E-6 ? v.normalize().scale(1.9) : Vec3.ZERO);
			SmokeField.trail(9_000_000 + m.getId(), tail, SmokeField.Style.smallRocket(0.6F), 1.0F);
			DynamicLights.steady(tail, 0xFFC070, 0.8F, 12.0F);
		} else {
			SmokeField.cut(9_000_000 + m.getId());
		}
	}

	// ------------------------------------------------------------------ HUD

	private static final int HUD = 0xFF5CFF7A;
	private static final int HUD_DIM = 0xA05CFF7A;
	private static final int WARN = 0xFFFF4040;

	private static void hud(GuiGraphics g, float partial) {
		Minecraft mc = Minecraft.getInstance();
		FighterEntity jet = flying;
		if (jet == null || mc.options.hideGui || mc.player == null) {
			return;
		}
		Font font = mc.font;
		int w = g.guiWidth();
		int h = g.guiHeight();
		int cx = w / 2;
		int cy = h / 2;
		FighterType type = jet.type();
		// flight-path marker: where the nose really points
		int[] nose = project(mc, jet.getPosition(partial).add(0, FighterRenderer.GEAR_HEIGHT, 0).add(jet.forward(partial).scale(500.0)), w, h);
		if (nose != null) {
			ring(g, nose[0], nose[1], 5, HUD);
			g.fill(nose[0] - 12, nose[1], nose[0] - 6, nose[1] + 1, HUD);
			g.fill(nose[0] + 6, nose[1], nose[0] + 12, nose[1] + 1, HUD);
			g.fill(nose[0], nose[1] - 9, nose[0] + 1, nose[1] - 5, HUD);
		}
		// the seeker circle and the lock box
		ring(g, cx, cy, 26, HUD_DIM);
		Entity box = locked != null ? locked : candidate;
		if (box != null) {
			int[] p = project(mc, box.getBoundingBox().getCenter(), w, h);
			if (p != null) {
				int col = locked != null ? WARN : 0xFFFFE040;
				int s = 9;
				g.fill(p[0] - s, p[1] - s, p[0] + s, p[1] - s + 1, col);
				g.fill(p[0] - s, p[1] + s - 1, p[0] + s, p[1] + s, col);
				g.fill(p[0] - s, p[1] - s, p[0] - s + 1, p[1] + s, col);
				g.fill(p[0] + s - 1, p[1] - s, p[0] + s, p[1] + s, col);
				String label = (locked != null ? "LOCK " : "") + Math.round(box.distanceTo(jet)) + "m";
				g.drawString(font, label, p[0] - font.width(label) / 2, p[1] + s + 2, col, true);
			}
		}
		// speed tape (left), altitude (right)
		float speed = jet.speed();
		int kmh = Math.round(speed * 20.0F * 3.6F);
		String spd = kmh + " km/h";
		String mach = String.format("M %.2f", jet.mach());
		g.drawString(font, spd, cx - 120 - font.width(spd), cy - 4, HUD, true);
		g.drawString(font, mach, cx - 120 - font.width(mach), cy + 8, jet.mach() >= 1.0F ? 0xFFFFE040 : HUD, true);
		int alt = (int) Math.round(jet.getY());
		int ground = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(jet.getX()), Mth.floor(jet.getZ()));
		g.drawString(font, "ALT " + alt, cx + 120, cy - 4, HUD, true);
		g.drawString(font, "AGL " + Math.max(0, alt - ground), cx + 120, cy + 8, HUD_DIM, true);
		g.drawString(font, String.format("G %.1f", jet.gLoad()), cx - 160, cy + 30, jet.gLoad() > 8.0F ? WARN : HUD, true);
		// heading
		int heading = Math.floorMod(Math.round(jet.getYRot() + 180.0F), 360);
		String hdg = String.format("%03d", heading);
		g.drawString(font, hdg, cx - font.width(hdg) / 2, cy - 70, HUD, true);
		// engine and weapons (bottom)
		String thr = !jet.engineRunning() ? "ENG START " + Math.round(jet.spool() * 100.0F) + "%"
			: "THR " + Math.round(jet.throttle() * 100.0F) + "%" + (jet.isAfterburner() ? "  AB" : "");
		g.drawString(font, thr, cx - 160, h - 70, jet.isAfterburner() ? 0xFFFFA040 : HUD, true);
		String weapons = "GUN " + jet.ammo() + "   AIM-120 x" + jet.missiles() + "   FLR " + jet.flares();
		g.drawString(font, weapons, cx - font.width(weapons) / 2, h - 58, HUD, true);
		String name = type == FighterType.F22 ? "F-22A" : "F-35A";
		g.drawString(font, name + (jet.gearDown() ? "  GEAR DN" : "") + (jet.bayOpen() ? "  BAY" : ""), cx + 60, h - 70, HUD_DIM, true);
		// warnings
		boolean blink = (jet.tickCount / 5) % 2 == 0;
		if (jet.warning() > 0 && blink) {
			String warn = "MISSILE";
			g.drawString(font, warn, cx - font.width(warn) / 2, cy - 50, WARN, true);
		}
		if (!jet.onGround() && speed < type.stallSpeed && blink) {
			g.drawString(font, "STALL", cx - font.width("STALL") / 2, cy + 40, WARN, true);
		}
		Vec3 f = jet.forward();
		double agl = jet.getY() - ground;
		if (!jet.onGround() && f.y < -0.2 && agl / Math.max(0.1, -f.y * speed) < 60.0 && blink) {
			g.drawString(font, "PULL UP", cx - font.width("PULL UP") / 2, cy + 52, WARN, true);
		}
		// on the ground: the controls, and the call to rotate once fast enough
		if (jet.onGround()) {
			String keys = "SPACE thr+   CTRL thr-   S pull   W push   A/D roll/steer";
			g.drawString(font, keys, cx - font.width(keys) / 2, h - 46, HUD_DIM, true);
			if (jet.engineRunning() && speed >= jet.rotateSpeed() && blink) {
				g.drawString(font, "ROTATE - S", cx - font.width("ROTATE - S") / 2, cy + 40, HUD, true);
			} else if (jet.engineRunning()) {
				String vr = "VR " + Math.round(jet.rotateSpeed() * 72.0) + " km/h";
				g.drawString(font, vr, cx - 160, cy + 42, HUD_DIM, true);
			}
		}
		if (jet.health() < type.maxHealth * 0.35F) {
			g.drawString(font, "DAMAGE", cx + 60, h - 82, WARN, true);
		}
	}

	private static void ring(GuiGraphics g, int x, int y, int r, int col) {
		for (int i = 0; i < 36; i++) {
			double a = i * Math.PI * 2.0 / 36.0;
			int px = x + (int) Math.round(Math.cos(a) * r);
			int py = y + (int) Math.round(Math.sin(a) * r);
			g.fill(px, py, px + 1, py + 1, col);
		}
	}

	/** Screen position (gui pixels) of a world point, or null if it is behind the camera. */
	private static int @Nullable [] project(Minecraft mc, Vec3 world, int w, int h) {
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 rel = world.subtract(camera.position());
		Vector3f fwd = new Vector3f(camera.forwardVector());
		Vector3f up = new Vector3f(camera.upVector());
		Vector3f left = new Vector3f(camera.leftVector());
		double z = rel.x * fwd.x + rel.y * fwd.y + rel.z * fwd.z;
		if (z <= 0.1) {
			return null;
		}
		double x = -(rel.x * left.x + rel.y * left.y + rel.z * left.z);
		double y = rel.x * up.x + rel.y * up.y + rel.z * up.z;
		double fovY = Math.toRadians(mc.options.fov().get());
		double tanY = Math.tan(fovY / 2.0);
		double tanX = tanY * w / (double) h;
		int sx = (int) Math.round(w / 2.0 * (1.0 + x / z / tanX));
		int sy = (int) Math.round(h / 2.0 * (1.0 - y / z / tanY));
		return new int[] {sx, sy};
	}
}
