package de.rcm.ballistic.client.drone;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.FpvDroneEntity;
import de.rcm.ballistic.network.ModNetworking.DroneInputPayload;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Flying the FPV drone, client side: the view switches to the drone's camera, the movement keys and
 * the mouse become the sticks (W/S forward and back along the camera, A/D sideways, jump/sneak up and
 * down, sprint for full throttle), left-click sets the warhead off and right-click cuts the link. The
 * picture is the analog video of a real FPV feed: a slightly tinted, grainy image with scan lines that
 * breaks up into static as the signal weakens, and the OSD over it (battery, signal, speed, height,
 * distance, flight time, artificial horizon). When the link goes, the screen fills with static for a
 * moment before the view comes back to your own eyes.
 */
public final class DroneClient {
	private static @Nullable FpvDroneEntity flying;
	private static float savedYaw;
	private static float savedPitch;
	private static @Nullable CameraType savedCamera;
	private static int pendingAction;
	private static long started;
	/** Client ticks of static still to show after the link went. */
	private static int staticTicks;
	private static String staticKey = "";
	private static @Nullable DroneSound fpvSound;
	private static final Random NOISE = new Random();
	private static final Identifier OSD = BallisticMissiles.id("drone_osd");

	private DroneClient() {
	}

	public static void init() {
		ClientHooks.droneClientTick = DroneClient::droneTick;
		ClientHooks.droneView = (drone, partialTick, pitch) -> {
			Minecraft mc = Minecraft.getInstance();
			if (drone == flying && mc.player != null) {
				return pitch ? mc.player.getViewXRot(partialTick) : mc.player.getViewYRot(partialTick);
			}
			return null;
		};
		// the buttons: taken before Minecraft sees them (no attacking or using things while flying)
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (flying == null) {
				return;
			}
			while (mc.options.keyAttack.consumeClick()) {
				pendingAction = FpvDroneEntity.ACTION_DETONATE;
			}
			while (mc.options.keyUse.consumeClick()) {
				if (pendingAction == FpvDroneEntity.ACTION_NONE) {
					pendingAction = FpvDroneEntity.ACTION_RELEASE;
				}
			}
			while (mc.options.keyTogglePerspective.consumeClick()) {
				// the goggles only show the camera
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(DroneClient::tick);
		HudElementRegistry.addLast(OSD, (graphics, tickCounter) -> hud(graphics, tickCounter.getGameTimeDeltaPartialTick(false)));
		// while flying, the goggles show nothing of your own body's HUD
		for (Identifier id : new Identifier[] {VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.ARMOR_BAR,
			VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR,
			VanillaHudElements.CROSSHAIR, VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.STATUS_EFFECTS}) {
			HudElementRegistry.replaceElement(id, element -> (graphics, tickCounter) -> {
				if (flying == null && staticTicks <= 0) {
					element.render(graphics, tickCounter);
				}
			});
		}
	}

	public static boolean isFlying() {
		return flying != null;
	}

	/** Roll of the camera (degrees): the drone banks into turns and sideways moves. */
	public static float cameraRoll(float partialTick) {
		return flying == null ? 0.0F : flying.getRoll();
	}

	/** Every drone in view: its buzz. */
	private static void droneTick(FpvDroneEntity drone) {
		if (!drone.clientSoundStarted) {
			drone.clientSoundStarted = true;
			Minecraft.getInstance().getSoundManager().play(new DroneSound(drone, false));
		}
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (staticTicks > 0) {
			staticTicks--;
		}
		if (player == null || mc.level == null) {
			flying = null;
			return;
		}
		if (flying == null) {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof FpvDroneEntity drone && drone.getPilotId() == player.getId() && !drone.isRemoved()) {
					start(mc, player, drone);
					break;
				}
			}
			return;
		}
		FpvDroneEntity drone = flying;
		if (drone.isRemoved() || drone.getPilotId() != player.getId() || !player.isAlive()) {
			stop(mc, player, drone.isRemoved() && drone.getPilotId() == player.getId() ? "screen.ballisticmissiles.drone_no_signal"
				: drone.isRemoved() ? "screen.ballisticmissiles.drone_impact" : "screen.ballisticmissiles.drone_signal_lost");
			return;
		}
		var o = mc.options;
		float forward = (o.keyUp.isDown() ? 1.0F : 0.0F) - (o.keyDown.isDown() ? 1.0F : 0.0F);
		float strafe = (o.keyRight.isDown() ? 1.0F : 0.0F) - (o.keyLeft.isDown() ? 1.0F : 0.0F);
		float lift = (o.keyJump.isDown() ? 1.0F : 0.0F) - (o.keyShift.isDown() ? 1.0F : 0.0F);
		ClientPlayNetworking.send(new DroneInputPayload(drone.getId(), forward, strafe, lift, o.keySprint.isDown(), player.getYRot(), player.getXRot(),
			pendingAction));
		pendingAction = FpvDroneEntity.ACTION_NONE;
		if (mc.getCameraEntity() != drone) {
			mc.setCameraEntity(drone);
		}
	}

	private static void start(Minecraft mc, LocalPlayer player, FpvDroneEntity drone) {
		flying = drone;
		savedYaw = player.getYRot();
		savedPitch = player.getXRot();
		savedCamera = mc.options.getCameraType();
		mc.options.setCameraType(CameraType.FIRST_PERSON);
		mc.setCameraEntity(drone);
		pendingAction = FpvDroneEntity.ACTION_NONE;
		started = mc.level.getGameTime();
		fpvSound = new DroneSound(drone, true);
		mc.getSoundManager().play(fpvSound);
	}

	private static void stop(Minecraft mc, LocalPlayer player, String why) {
		flying = null;
		mc.setCameraEntity(player);
		player.setYRot(savedYaw);
		player.setXRot(savedPitch);
		player.yRotO = savedYaw;
		player.xRotO = savedPitch;
		if (savedCamera != null) {
			mc.options.setCameraType(savedCamera);
		}
		if (fpvSound != null) {
			fpvSound.release();
			fpvSound = null;
		}
		staticTicks = 14;
		staticKey = why;
	}

	// ------------------------------------------------------------------ the goggles' picture

	private static void hud(GuiGraphics g, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		int w = g.guiWidth();
		int h = g.guiHeight();
		if (flying == null) {
			if (staticTicks > 0) {
				noise(g, w, h, 1.0F, 6000);
				g.drawCenteredString(mc.font, Component.translatable(staticKey), w / 2, h / 2 - 4, 0xFFFFFFFF);
			}
			return;
		}
		FpvDroneEntity drone = flying;
		float signal = drone.getSignal();
		// the analog look: a cold tint, scan lines, a dark edge, and static that grows as the link weakens
		g.fill(0, 0, w, h, 0x10203850);
		for (int y = 0; y < h; y += 3) {
			g.fill(0, y, w, y + 1, 0x16000000);
		}
		for (int i = 0; i < 12; i++) {
			int a = (12 - i) * 6;
			g.fill(i * 2, 0, i * 2 + 2, h, a << 24);
			g.fill(w - i * 2 - 2, 0, w - i * 2, h, a << 24);
			g.fill(0, i * 2, w, i * 2 + 2, a << 24);
			g.fill(0, h - i * 2 - 2, w, h - i * 2, a << 24);
		}
		float bad = 1.0F - signal;
		noise(g, w, h, 0.12F + 0.88F * bad * bad, (int) (150 + 4500 * bad * bad));
		if (signal < 0.35F && NOISE.nextFloat() < (0.35F - signal) * 1.5F) {
			// a frame torn or blacked out
			int y = NOISE.nextInt(h);
			g.fill(0, y, w, Math.min(h, y + 4 + NOISE.nextInt(h / 3)), NOISE.nextBoolean() ? 0xD0000000 : 0x90A0A0A0);
		}

		// OSD: white text on the picture, Betaflight style
		int white = 0xFFF0F0F0;
		var font = mc.font;
		int battery = drone.getBattery();
		float charge = battery / (float) FpvDroneEntity.BATTERY;
		float volts = 13.2F + 3.6F * charge - 0.4F * drone.getThrottle();
		boolean blink = (mc.level.getGameTime() / 6) % 2 == 0;
		int batColor = charge < 0.2F ? (blink ? 0xFFFF4040 : 0x00000000) : white;
		g.drawString(font, String.format("%.1fV", volts), 10, 10, batColor, true);
		g.drawString(font, String.format("BAT %d%%", Math.round(charge * 100)), 10, 20, batColor, true);
		int rssi = Math.round(signal * 99);
		g.drawString(font, "RSSI " + rssi, w - 10 - font.width("RSSI 99"), 10, signal < 0.3F ? 0xFFFF6060 : white, true);
		for (int i = 0; i < 5; i++) {
			int bx = w - 46 + i * 7;
			int bh = 3 + i * 2;
			g.fill(bx, 32 - bh, bx + 5, 32, signal * 5 > i + 0.5F ? white : 0x50FFFFFF);
		}
		Vec3 now = drone.position();
		Vec3 before = new Vec3(drone.xo, drone.yo, drone.zo);
		int kmh = (int) Math.round(now.distanceTo(before) * 20.0 * 3.6);
		int alt = mc.player == null ? 0 : (int) Math.round(now.y - mc.player.getY());
		int dist = mc.player == null ? 0 : (int) Math.round(now.distanceTo(mc.player.position()));
		g.drawString(font, kmh + " km/h", 10, h - 30, white, true);
		g.drawString(font, "ALT " + alt + "m", 10, h - 20, white, true);
		g.drawString(font, "DST " + dist + "m", w / 2 - font.width("DST 000m") / 2, h - 20, white, true);
		long t = (mc.level.getGameTime() - started) / 20;
		g.drawString(font, String.format("%02d:%02d", t / 60, t % 60), w - 10 - font.width("00:00"), h - 20, white, true);
		g.drawString(font, "ARMED", w - 10 - font.width("ARMED"), h - 30, 0xFFFF5050, true);
		// warnings
		String warn = signal < 0.25F ? "screen.ballisticmissiles.drone_weak_signal" : charge < 0.15F ? "screen.ballisticmissiles.drone_low_battery" : null;
		if (warn != null && blink) {
			g.drawCenteredString(font, Component.translatable(warn), w / 2, 30, 0xFFFF4040);
		}
		// crosshair and artificial horizon (it rolls with the drone, rises and sinks as it pitches)
		int cx = w / 2;
		int cy = h / 2;
		g.fill(cx - 4, cy, cx - 1, cy + 1, white);
		g.fill(cx + 2, cy, cx + 5, cy + 1, white);
		g.fill(cx, cy - 4, cx + 1, cy - 1, white);
		float roll = -drone.getRoll() * Mth.DEG_TO_RAD;
		float pitch = mc.player == null ? 0.0F : mc.player.getViewXRot(partialTick);
		int offset = (int) (pitch * 1.6F);
		for (int i = -60; i <= 60; i += 2) {
			if (Math.abs(i) < 14) {
				continue;
			}
			int x = cx + Math.round(Mth.cos(roll) * i);
			int y = cy + offset + Math.round(Mth.sin(roll) * i);
			g.fill(x, y, x + 2, y + 1, 0xC0F0F0F0);
		}
	}

	/** Analog static: grey speckles and streaks, {@code amount} 0..1 strong, {@code count} of them. */
	private static void noise(GuiGraphics g, int w, int h, float amount, int count) {
		if (amount >= 0.99F) {
			g.fill(0, 0, w, h, 0xFF101010);
		}
		for (int i = 0; i < count; i++) {
			int x = NOISE.nextInt(w);
			int y = NOISE.nextInt(h);
			int v = 60 + NOISE.nextInt(196);
			int a = (int) (Math.min(1.0F, amount * (0.4F + NOISE.nextFloat())) * 200);
			int len = 1 + NOISE.nextInt(amount > 0.6F ? 8 : 3);
			g.fill(x, y, Math.min(w, x + len), y + 1, a << 24 | v << 16 | v << 8 | v);
		}
	}

	static boolean isLocalPilot(FpvDroneEntity drone) {
		return drone == flying;
	}
}
