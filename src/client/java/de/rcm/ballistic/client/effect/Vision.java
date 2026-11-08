package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.platform.InputConstants;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.drone.DroneClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import org.lwjgl.glfw.GLFW;

/**
 * Seeing in the dark: night-vision goggles (worn on the head, flipped down with the key) amplify what
 * little light there is into a grainy green picture in which lamps and fire flare and infrared lasers
 * show; in a drone or a vehicle the same key switches its camera to thermal - warm bodies and hot
 * engines white, the cold land dark. The pictures themselves are drawn by the blast post effect.
 */
public final class Vision {
	private static KeyMapping key;
	private static boolean nightVisionOn;
	private static boolean thermalOn;
	private static float nightVision;
	private static float nightVisionO;
	private static float thermal;
	private static float thermalO;
	/** The tube warming up after it is switched on: a bright bloom settling (ticks left). */
	private static int warmUp;

	private Vision() {
	}

	public static void init(KeyMapping.Category category) {
		key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ballisticmissiles.vision", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, category));
		ClientTickEvents.END_CLIENT_TICK.register(Vision::tick);
	}

	/** The local player is looking through a vehicle's or a drone's camera. */
	private static boolean inVehicle(Minecraft mc) {
		return DroneClient.isFlying() || mc.player != null && mc.player.isPassenger();
	}

	private static boolean wearing(Minecraft mc) {
		return mc.player != null && mc.player.getItemBySlot(EquipmentSlot.HEAD).is(ModRegistry.NIGHT_VISION_GOGGLES);
	}

	private static void tick(Minecraft mc) {
		nightVisionO = nightVision;
		thermalO = thermal;
		if (mc.player == null || mc.level == null) {
			nightVisionOn = false;
			thermalOn = false;
			nightVision = 0.0F;
			thermal = 0.0F;
			return;
		}
		while (key.consumeClick()) {
			if (inVehicle(mc)) {
				thermalOn = !thermalOn;
				mc.player.displayClientMessage(Component.translatable(thermalOn ? "message.ballisticmissiles.thermal_on" : "message.ballisticmissiles.thermal_off")
					.withStyle(ChatFormatting.GRAY), true);
				click(mc, thermalOn ? 1.6F : 1.3F);
			} else if (wearing(mc)) {
				nightVisionOn = !nightVisionOn;
				if (nightVisionOn) {
					warmUp = 14;
				}
				click(mc, nightVisionOn ? 1.2F : 0.9F);
			} else {
				mc.player.displayClientMessage(Component.translatable("message.ballisticmissiles.vision_none").withStyle(ChatFormatting.GRAY), true);
			}
		}
		if (!wearing(mc)) {
			nightVisionOn = false;
		}
		if (!inVehicle(mc)) {
			thermalOn = false;
		}
		// flipped down in a moment; the picture comes up as the tube warms
		nightVision += Mth.clamp((nightVisionOn ? 1.0F : 0.0F) - nightVision, -0.34F, 0.2F);
		thermal += Mth.clamp((thermalOn ? 1.0F : 0.0F) - thermal, -0.34F, 0.25F);
		if (warmUp > 0) {
			warmUp--;
		}
	}

	private static void click(Minecraft mc, float pitch) {
		if (mc.player != null) {
			de.rcm.ballistic.client.gun.GunAudio.play(ModRegistry.AK_SELECTOR, mc.player.getEyePosition(), 0.35F, pitch);
		}
	}

	/** How far the night-vision picture is up (0-1). */
	public static float nightVision(float partialTick) {
		return Mth.lerp(partialTick, nightVisionO, nightVision) * TsarAftermath.electronics();
	}

	public static float thermal(float partialTick) {
		return Mth.lerp(partialTick, thermalO, thermal) * TsarAftermath.electronics();
	}

	/** The goggles are down and working: infrared shows. */
	public static boolean seesInfrared() {
		return nightVision > 0.5F;
	}

	public static boolean thermalActive() {
		return thermal > 0.5F;
	}

	/** Either picture, 0-1: the world need not be drawn as dark as the eye sees it. */
	public static float any() {
		return Math.max(nightVision, thermal);
	}

	// a flash seen through the tube: the picture blooms out white, then the tube's automatic gain throttles it
	// down and it comes back over the better part of a second
	private static float flashAmount;
	private static long flashAt;

	/** Something flashed (a muzzle, an explosion) at {@code at}, this bright: what it does to the goggles' picture. */
	public static void flashSeen(net.minecraft.world.phys.Vec3 at, float strength, float radius) {
		Minecraft mc = Minecraft.getInstance();
		if (nightVision < 0.05F || mc.player == null) {
			return;
		}
		net.minecraft.client.Camera cam = mc.gameRenderer.getMainCamera();
		net.minecraft.world.phys.Vec3 d = at.subtract(cam.position());
		double dist = Math.max(1.0, d.length());
		// in front: full; beside or behind, only the light thrown about the scene
		float facing = (float) Mth.clamp(d.normalize().dot(new net.minecraft.world.phys.Vec3(cam.forwardVector())), 0.0, 1.0);
		float a = Mth.clamp(strength * (float) (radius / (dist * 2.0)), 0.0F, 1.0F) * (0.35F + 0.65F * facing * facing);
		float now = flare();
		if (a > now) {
			flashAmount = Math.min(1.0F, Math.max(a, now + a * 0.4F));
			flashAt = System.nanoTime();
		}
	}

	/** The bloom of the last flash now (0-1): white-out for a blink. */
	public static float flare() {
		float t = (System.nanoTime() - flashAt) / 1.0E9F;
		return flashAmount * (float) Math.exp(-t / 0.09F);
	}

	/** The gain throttled down after it (0-1): the picture dim, recovering. */
	public static float gainDip() {
		float t = (System.nanoTime() - flashAt) / 1.0E9F;
		return flashAmount * Mth.clamp(t / 0.06F, 0.0F, 1.0F) * (float) Math.exp(-t / 0.7F);
	}

	/** The warm-up bloom of a freshly switched-on tube (0-1). */
	public static float warmUp() {
		return warmUp / 14.0F;
	}
}
