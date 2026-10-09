package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.BallisticMissiles;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * The mod's title screen: real photographs of Minuteman III ICBM test launches from Vandenberg at night
 * (U.S. Air Force / Space Force, public domain) instead of the panorama, as a slow slideshow - each
 * picture drifts and zooms a little ("Ken Burns"), then dissolves into the next; the mouse adds a touch
 * of parallax. The Minecraft logo makes way for the mod's own, the splash for a radio call, and a small
 * caption says what the picture shows.
 */
public final class TitleMenu {
	private static final int TW = 1600;
	private static final int TH = 900;
	private static final Identifier[] SLIDES = {
		BallisticMissiles.id("textures/gui/title/launch1.png"),
		BallisticMissiles.id("textures/gui/title/launch2.png"),
		BallisticMissiles.id("textures/gui/title/launch3.png")
	};
	private static final String[] CAPTIONS = {
		"Minuteman III ICBM operational test launch, Vandenberg SFB  -  U.S. Space Force photo",
		"Unarmed Minuteman III test launch, Vandenberg  -  U.S. Air Force photo",
		"Minuteman III launch, Vandenberg AFB, October 2019  -  U.S. Air Force photo"
	};
	/** Which way each picture drifts while it is shown (x, y; -1..1). */
	private static final float[][] DRIFT = {{0.6F, -0.4F}, {-0.7F, 0.3F}, {0.4F, 0.5F}};
	private static final Identifier LOGO = BallisticMissiles.id("textures/gui/title/logo.png");
	private static final int LOGO_W = 1024;
	private static final int LOGO_H = 300;
	private static final float SHOW = 12.0F;
	private static final float FADE = 2.5F;
	private static final String[] CALLS = {
		"FOX THREE", "DANGER CLOSE", "LAUNCH CODES ACCEPTED", "DEFCON 1", "SPLASH ONE", "WEAPONS FREE", "FIRE FOR EFFECT",
		"BROKEN ARROW", "BANDITS, BANDITS", "TALLY HO", "RIFLE, RIFLE", "BINGO FUEL", "MISSILE AWAY", "GOOD LOCK, SHOOT"
	};
	private static final String CALL = CALLS[(int) (Math.random() * CALLS.length)];
	private static final long START = Util.getMillis();
	private static String version;

	private TitleMenu() {
	}

	/** The slideshow, darkened at the top and bottom so the title and the menu read clearly. */
	public static void background(GuiGraphics g, int w, int h) {
		float t = (Util.getMillis() - START) / 1000.0F;
		int n = SLIDES.length;
		int cur = (int) (t / SHOW) % n;
		float into = t % SHOW;
		if (into < FADE && t >= SHOW) {
			int prev = (cur + n - 1) % n;
			slide(g, w, h, prev, into + SHOW, 1.0F);
			slide(g, w, h, cur, into, smooth(into / FADE));
		} else {
			slide(g, w, h, cur, into, 1.0F);
		}
		g.fillGradient(0, 0, w, h / 3, 0xB0000000, 0x00000000);
		g.fillGradient(0, h - h / 3, w, h, 0x00000000, 0xC0000000);
		g.fillGradient(0, h / 3, w, h - h / 3, 0x20000000, 0x20000000);
		// what the picture shows, small, above the version line
		Font font = Minecraft.getInstance().font;
		String caption = "Ballistic Missiles v" + version() + "   |   " + CAPTIONS[cur];
		g.drawString(font, caption, 2, h - 20, 0x80B0B4BA, false);
	}

	/** One picture, cover-fitted, drifting and zooming with its age {@code age} seconds. */
	private static void slide(GuiGraphics g, int w, int h, int index, float age, float alpha) {
		Minecraft mc = Minecraft.getInstance();
		float life = Mth.clamp(age / (SHOW + FADE), 0.0F, 1.0F);
		float zoom = 1.04F + 0.08F * life;
		// the part of the picture that covers the screen at this zoom
		float aspect = w / (float) h;
		float uw = aspect > TW / (float) TH ? TW : TH * aspect;
		float vh = uw / aspect;
		uw /= zoom;
		vh /= zoom;
		float mx = (float) (mc.mouseHandler.xpos() / Math.max(1, mc.getWindow().getScreenWidth()) - 0.5);
		float my = (float) (mc.mouseHandler.ypos() / Math.max(1, mc.getWindow().getScreenHeight()) - 0.5);
		float[] drift = DRIFT[index];
		float px = Mth.clamp(0.5F + drift[0] * (life - 0.5F) * 0.8F + mx * 0.12F, 0.0F, 1.0F);
		float py = Mth.clamp(0.5F + drift[1] * (life - 0.5F) * 0.8F + my * 0.12F, 0.0F, 1.0F);
		float u = (TW - uw) * px;
		float v = (TH - vh) * py;
		int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
		if (a == 0) {
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, SLIDES[index], 0, 0, u, v, w, h, Math.round(uw), Math.round(vh), TW, TH, a << 24 | 0xFFFFFF);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** The mod's title in place of the Minecraft logo. */
	public static void logo(GuiGraphics g, int w, float alpha) {
		int lw = logoWidth(w);
		int lh = lw * LOGO_H / LOGO_W;
		int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
		g.blit(RenderPipelines.GUI_TEXTURED, LOGO, (w - lw) / 2, 14, 0.0F, 0.0F, lw, lh, LOGO_W, LOGO_H, LOGO_W, LOGO_H, a << 24 | 0xFFFFFF);
	}

	/** Where the yellow splash would be: a radio call and the version, typed out under the title. */
	public static void call(GuiGraphics g, int w, float alpha) {
		Font font = Minecraft.getInstance().font;
		int lw = logoWidth(w);
		int y = 14 + lw * LOGO_H / LOGO_W + 1;
		float t = (Util.getMillis() - START) / 1000.0F;
		int shown = Math.min(CALL.length(), (int) (t * 14.0F));
		boolean cursor = (int) (t * 2.0F) % 2 == 0;
		String text = "// " + CALL.substring(0, shown) + (cursor ? "_" : " ");
		int a = Mth.clamp((int) (alpha * 255.0F), 4, 255) << 24;
		String full = "// " + CALL + "_";
		g.drawString(font, text, w / 2 - font.width(full) / 2, y, a | 0xFFB040, true);
	}

	/** As wide as fits: not wider than the screen allows, nor so tall it runs into the buttons. */
	private static int logoWidth(int w) {
		int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();
		int room = h / 4 + 48 - 14 - 14; // down to the first button, leaving a line for the radio call
		return Math.max(120, Math.min(Math.min(330, (int) (w * 0.62F)), room * LOGO_W / LOGO_H));
	}

	private static String version() {
		if (version == null) {
			version = FabricLoader.getInstance().getModContainer(BallisticMissiles.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		}
		return version;
	}
}
