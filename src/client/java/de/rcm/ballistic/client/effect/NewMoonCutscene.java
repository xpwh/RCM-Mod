package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
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

	/**
	 * The moon's light while it goes out: it wavers and gutters like a dying lamp before it is gone (the
	 * fading itself follows {@link #darkness}); 1 outside the cutscene.
	 */
	public static float moonFlicker(float partialTick) {
		if (!playing || !on) {
			return 1.0F;
		}
		float t = time(partialTick);
		float w = ease(t, 92.0F, 104.0F) * (1.0F - ease(t, 140.0F, 155.0F));
		float f = 0.5F + 0.5F * Mth.sin(t * 1.9F) * Mth.sin(t * 0.73F + 1.3F);
		return 1.0F - w * 0.55F * f;
	}

	/** The view while it plays: the player's own pitch, lifted towards the sky. */
	public static float pitch(float playerPitch, float partialTick) {
		float t = time(partialTick);
		float up = lift(t);
		// a slow, steady drift up there, as if gazing; a small start when the thunder rolls
		float start = on ? 1.5F * Mth.sin(Mth.clamp((t - 104.0F) / 10.0F, 0.0F, 1.0F) * Mth.PI) : 0.0F;
		return Mth.lerp(up, playerPitch, LOOK_UP - 4.0F * Mth.sin(t * 0.02F) + start);
	}

	/** How far the eyes are lifted to the sky (0-1): slowly up, a long look, slowly back down. */
	private static float lift(float t) {
		return on ? ease(t, 4.0F, 58.0F) - ease(t, 158.0F, 200.0F) : ease(t, 4.0F, 45.0F) - ease(t, 98.0F, 138.0F);
	}

	public static float yaw(float playerYaw, float partialTick) {
		float t = time(partialTick);
		return playerYaw + lift(t) * 6.0F * Mth.sin(t * 0.012F);
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
				// the wind rising while the eyes go up, and dying away as the moon goes
				case 0 -> sound(ModRegistry.NUKE_WIND, 0.5F, 0.7F, 40, 70, 70);
				// one deep roll of thunder as the moon goes out: the swell of it, then gone in a few seconds
				case 104 -> sound(ModRegistry.NUKE_FAR, 0.9F, 0.5F, 3, 14, 50);
				case 128 -> sound(ModRegistry.HEARTBEAT, 0.6F, 0.85F, 1, 16, 4);
				case 148 -> sound(ModRegistry.HEARTBEAT, 0.45F, 0.85F, 1, 16, 4);
				case 168 -> sound(ModRegistry.HEARTBEAT, 0.3F, 0.85F, 1, 16, 4);
				default -> {
				}
			}
		} else {
			switch (age) {
				case 0 -> sound(ModRegistry.NUKE_WIND, 0.35F, 0.95F, 30, 60, 50);
				default -> {
				}
			}
		}
		if (++age >= (on ? LENGTH_ON : LENGTH_OFF)) {
			playing = false;
		}
	}

	/** A sound for everyone alike, swelling in over {@code attack} ticks, held, and dying away over {@code release}. */
	private static void sound(SoundEvent sound, float volume, float pitch, int attack, int hold, int release) {
		Minecraft.getInstance().getSoundManager().play(new FadingSound(sound, volume, pitch, attack, hold, release));
	}

	private static final Identifier VIGNETTE = Identifier.withDefaultNamespace("textures/misc/vignette.png");

	/** Black bars top and bottom, the dark closing in from the edges, and the title. */
	private static void hud(GuiGraphics g, float partialTick) {
		if (!playing) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		float t = time(partialTick);
		int length = on ? LENGTH_ON : LENGTH_OFF;
		int w = g.guiWidth();
		int h = g.guiHeight();
		// the dark closes in round the edges of sight, and, as the moon goes out, a heartbeat of black
		float edge = ease(t, 0.0F, 40.0F) - ease(t, length - 30.0F, length);
		if (on) {
			edge = Math.min(1.0F, edge * (0.55F + 0.45F * ease(t, 104.0F, 130.0F)));
		} else {
			edge *= 0.5F;
		}
		if (edge > 0.01F) {
			int v = Math.round(edge * 255.0F);
			g.blit(net.minecraft.client.renderer.RenderPipelines.VIGNETTE, VIGNETTE, 0, 0, 0.0F, 0.0F, w, h, w, h, 0xFF000000 | v << 16 | v << 8 | v);
		}
		if (on) {
			float dip = Mth.sin(Mth.clamp((t - 104.0F) / 22.0F, 0.0F, 1.0F) * Mth.PI);
			if (dip > 0.01F) {
				g.fill(0, 0, w, h, Math.round(dip * 0.45F * 255.0F) << 24);
			}
		}
		float bars = ease(t, 0.0F, 24.0F) - ease(t, length - 24.0F, length);
		int bar = Math.round(h * 0.15F * bars);
		if (bar > 0) {
			g.fill(0, 0, w, bar, 0xFF000000);
			g.fill(0, h - bar, w, h, 0xFF000000);
		}
		float in = on ? ease(t, 118.0F, 148.0F) : ease(t, 58.0F, 82.0F);
		float out = on ? ease(t, 170.0F, 192.0F) : ease(t, 106.0F, 126.0F);
		float title = in - out;
		float sub = (on ? ease(t, 140.0F, 160.0F) : ease(t, 74.0F, 92.0F)) - out;
		String key = on ? "cutscene.ballisticmissiles.new_moon" : "cutscene.ballisticmissiles.moon_return";
		int cy = Math.round(h * 0.43F);
		if (title > 0.01F) {
			spaced(g, mc, Component.translatable(key + ".title").getString(), w / 2.0F, cy, 2.6F, in, title);
			// a thin line drawn out from the middle under it
			int half = Math.round(w * 0.16F * ease(in, 0.3F, 1.0F));
			if (half > 0) {
				int a = Math.round(title * 140.0F);
				g.fill(w / 2 - half, cy + 16, w / 2 + half, cy + 17, a << 24 | 0xB8C0D8);
			}
		}
		if (sub > 0.01F) {
			int a = Math.round(sub * 230.0F);
			g.drawCenteredString(mc.font, Component.translatable(key + ".subtitle"), w / 2, cy + 24, a << 24 | 0x9AA2B8);
		}
	}

	/**
	 * The title, letter by letter: the letters come in one after another out of the dark, from wide apart
	 * closing to their place.
	 */
	private static void spaced(GuiGraphics g, Minecraft mc, String text, float cx, float cy, float scale, float reveal, float alpha) {
		int n = text.length();
		float gap = 1.5F + 5.0F * (1.0F - reveal);
		float width = 0.0F;
		for (int i = 0; i < n; i++) {
			width += mc.font.width(String.valueOf(text.charAt(i))) + gap;
		}
		width -= gap;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(scale, scale);
		float x = -width / 2.0F;
		for (int i = 0; i < n; i++) {
			String c = String.valueOf(text.charAt(i));
			float own = Mth.clamp(reveal * (n + 4) - i, 0.0F, 1.0F) * alpha;
			if (own > 0.01F) {
				int a = Math.round(own * 255.0F);
				g.drawString(mc.font, c, Math.round(x), -4, a << 24 | 0xD8DCE8, true);
			}
			x += mc.font.width(c) + gap;
		}
		g.pose().popMatrix();
	}
}
