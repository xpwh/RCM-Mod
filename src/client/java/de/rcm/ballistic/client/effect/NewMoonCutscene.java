package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/**
 * The new-moon night's cutscene, played for every player at once when the event is switched on: black
 * bars close in, the HUD and your hands go, your eyes go up to the sky while the wind rises - if it is
 * still day, the day races by to midnight (the server runs the clock, {@link de.rcm.ballistic.explosion.NewMoon}) - the moon stands overhead, a deep
 * rumble rolls over the land and the moon goes out, the stars after it; the darkness comes down, the
 * title rises out of it, and the view comes back down into the black. Switched off, a shorter one: the
 * moon comes back. Walking is held while it plays; looking round is not (the view returns to yours).
 */
public final class NewMoonCutscene {
	public static final int LENGTH_ON = 210;
	public static final int LENGTH_OFF = 150;
	/** How far up the eyes go (pitch, degrees; up is negative). */
	private static final float LOOK_UP = -72.0F;

	private static boolean playing;
	private static boolean on;
	private static int age;

	private NewMoonCutscene() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(NewMoonCutscene::tick);
		HudElementRegistry.addLast(BallisticMissiles.id("new_moon_cutscene"), (graphics, tickCounter) -> hud(graphics, tickCounter.getGameTimeDeltaPartialTick(false)));
		for (Identifier id : new Identifier[] {VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.ARMOR_BAR,
			VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR,
			VanillaHudElements.CROSSHAIR, VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.STATUS_EFFECTS, VanillaHudElements.MOUNT_HEALTH}) {
			HudElementRegistry.replaceElement(id, element -> (graphics, tickCounter) -> {
				if (!playing) {
					element.render(graphics, tickCounter);
				}
			});
		}
	}

	/** The event was switched on (or off) just now: play its cutscene. */
	public static void start(boolean switchedOn) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
			return;
		}
		playing = true;
		on = switchedOn;
		age = 0;
	}

	public static boolean playing() {
		return playing;
	}

	public static void clear() {
		playing = false;
	}

	private static float time(float partialTick) {
		return age + partialTick;
	}

	private static float ease(float t, float a, float b) {
		float x = Mth.clamp((t - a) / (b - a), 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** How dark it has become while it plays (0-1), for {@link NewMoonClient}. */
	public static float darkness() {
		float t = age;
		return on ? ease(t, 110.0F, 160.0F) : 1.0F - ease(t, 45.0F, 95.0F);
	}

	/** The view while it plays: the player's own pitch, lifted towards the sky. */
	public static float pitch(float playerPitch, float partialTick) {
		float t = time(partialTick);
		float up = on ? ease(t, 5.0F, 45.0F) - ease(t, 160.0F, 195.0F) : ease(t, 5.0F, 40.0F) - ease(t, 100.0F, 135.0F);
		// a slow, steady drift up there, as if gazing
		return Mth.lerp(up, playerPitch, LOOK_UP - 4.0F * Mth.sin(t * 0.02F));
	}

	public static float yaw(float playerYaw, float partialTick) {
		float t = time(partialTick);
		float up = on ? ease(t, 5.0F, 45.0F) - ease(t, 160.0F, 195.0F) : ease(t, 5.0F, 40.0F) - ease(t, 100.0F, 135.0F);
		return playerYaw + up * 6.0F * Mth.sin(t * 0.012F);
	}

	private static void tick(Minecraft mc) {
		if (!playing) {
			return;
		}
		if (mc.level == null || mc.player == null) {
			playing = false;
			return;
		}
		if (on) {
			switch (age) {
				case 0 -> sound(ModRegistry.NUKE_WIND, 0.55F, 0.7F);
				case 60 -> sound(ModRegistry.NUKE_WIND, 0.45F, 0.6F);
				case 108 -> sound(ModRegistry.NUKE_FAR, 1.0F, 0.5F);
				case 112 -> sound(ModRegistry.NUKE_FAR, 0.6F, 0.42F);
				case 128, 146, 164 -> sound(ModRegistry.HEARTBEAT, 0.7F, 0.85F);
				default -> {
				}
			}
		} else {
			switch (age) {
				case 0 -> sound(ModRegistry.NUKE_WIND, 0.4F, 0.9F);
				case 50 -> sound(ModRegistry.NUKE_WIND, 0.3F, 1.2F);
				default -> {
				}
			}
		}
		if (++age >= (on ? LENGTH_ON : LENGTH_OFF)) {
			playing = false;
		}
	}

	private static void sound(SoundEvent sound, float volume, float pitch) {
		Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(sound.location(), SoundSource.MASTER, volume, pitch,
			SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true));
	}

	/** Black bars top and bottom, and the title. */
	private static void hud(GuiGraphics g, float partialTick) {
		if (!playing) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		float t = time(partialTick);
		int length = on ? LENGTH_ON : LENGTH_OFF;
		float bars = ease(t, 0.0F, 20.0F) - ease(t, length - 20.0F, length);
		int w = g.guiWidth();
		int h = g.guiHeight();
		int bar = Math.round(h * 0.16F * bars);
		if (bar > 0) {
			g.fill(0, 0, w, bar, 0xFF000000);
			g.fill(0, h - bar, w, h, 0xFF000000);
		}
		float title = on ? ease(t, 120.0F, 142.0F) - ease(t, 172.0F, 192.0F) : ease(t, 60.0F, 78.0F) - ease(t, 108.0F, 126.0F);
		float sub = on ? ease(t, 134.0F, 152.0F) - ease(t, 172.0F, 192.0F) : ease(t, 70.0F, 88.0F) - ease(t, 108.0F, 126.0F);
		String key = on ? "cutscene.ballisticmissiles.new_moon" : "cutscene.ballisticmissiles.moon_return";
		if (title > 0.02F) {
			int a = Math.round(title * 255.0F);
			g.pose().pushMatrix();
			g.pose().translate(w / 2.0F, h * 0.44F);
			// it rises slowly as it appears
			float s = 3.0F + 0.15F * title;
			g.pose().scale(s, s);
			g.drawCenteredString(mc.font, Component.translatable(key + ".title"), 0, -4, a << 24 | 0xD8DCE8);
			g.pose().popMatrix();
		}
		if (sub > 0.02F) {
			int a = Math.round(sub * 230.0F);
			g.drawCenteredString(mc.font, Component.translatable(key + ".subtitle"), w / 2, Math.round(h * 0.44F) + 22, a << 24 | 0x9AA2B8);
		}
	}
}
