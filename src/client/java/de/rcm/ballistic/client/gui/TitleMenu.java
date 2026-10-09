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
 * The title screen of the mod: a missile base at dusk instead of the panorama - stars, the sunset behind
 * the mountains, fighters drawing contrails, the gantry with its missile, the radar dish - drawn crisp as
 * pixel art, panning slightly with the mouse. Every so often a missile launches from the silo and climbs
 * away on its column of fire and smoke; the gantry's warning light blinks. The Minecraft logo makes way
 * for the mod's own title, the splash for a radio call, and the menu sits on a dark panel.
 */
public final class TitleMenu {
	private static final Identifier BACKGROUND = BallisticMissiles.id("textures/gui/title_background.png");
	private static final int TW = 640;
	private static final int TH = 360;
	private static final float LAUNCH_PERIOD = 18.0F;
	private static final String[] CALLS = {
		"Fox Three!", "Danger close!", "Launch codes accepted", "DEFCON 1", "Splash one!", "Duck and cover!", "Fire for effect!",
		"Broken arrow", "Bandits, bandits!", "Tally ho!", "Weapons free", "Rifle! Rifle!", "Mach 2 and climbing", "Bingo fuel"
	};
	private static final String CALL = CALLS[(int) (Math.random() * CALLS.length)];
	private static String version;

	private TitleMenu() {
	}

	/** Background, launches, vignette and the panel behind the buttons. */
	public static void background(GuiGraphics g, int w, int h) {
		Minecraft mc = Minecraft.getInstance();
		float t = (Util.getMillis() % 3_600_000L) / 1000.0F;
		// cover the screen, a little oversized so the picture can drift with the mouse
		float scale = Math.max(w / (float) TW, h / (float) TH) * 1.06F;
		int dw = Mth.ceil(TW * scale);
		int dh = Mth.ceil(TH * scale);
		double mx = mc.mouseHandler.xpos() / Math.max(1, mc.getWindow().getScreenWidth()) - 0.5;
		double my = mc.mouseHandler.ypos() / Math.max(1, mc.getWindow().getScreenHeight()) - 0.5;
		int x = (int) ((w - dw) / 2.0 - mx * (dw - w) * 0.9);
		int y = (int) ((h - dh) / 2.0 - my * (dh - h) * 0.9);
		g.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, x, y, 0.0F, 0.0F, dw, dh, TW, TH, TW, TH);
		launch(g, x, y, scale, t);
		// warning light on top of the gantry, the radar's beacon
		if ((t * 1.1F) % 1.0F < 0.45F) {
			dot(g, x, y, scale, 485.5F, 146.5F, 2.0F, 0xFFFF3020);
		}
		if ((t * 0.7F + 0.3F) % 1.0F < 0.3F) {
			dot(g, x, y, scale, 164.5F, 232.5F, 1.5F, 0xFFFF3020);
		}
		// vignette
		g.fillGradient(0, 0, w, h / 4, 0x90000000, 0x00000000);
		g.fillGradient(0, h - h / 4, w, h, 0x00000000, 0xB0000000);
		// the menu panel
		int px0 = w / 2 - 110;
		int px1 = w / 2 + 110;
		int py0 = h / 4 + 40;
		int py1 = h / 4 + 164;
		g.fill(px0, py0, px1, py1, 0x9A0A0C10);
		g.fill(px0, py0, px1, py0 + 1, 0xFFF0A830);
		g.fill(px0, py1 - 1, px1, py1, 0x80F0A830);
		g.fill(px0, py0, px0 + 1, py1, 0x60F0A830);
		g.fill(px1 - 1, py0, px1, py1, 0x60F0A830);
	}

	/** A missile out of the silo every few seconds: fire, a growing smoke column, the glow on the ground. */
	private static void launch(GuiGraphics g, int x, int y, float scale, float t) {
		float p = t % LAUNCH_PERIOD;
		float burn = 9.0F;
		if (p > burn + 6.0F) {
			return;
		}
		float sx = 566.0F;
		float sy = 310.0F;
		// smoke: puffs left along the path, spreading and fading
		for (float s = 0.0F; s < Math.min(p, burn); s += 0.06F) {
			float age = p - s;
			float fade = Mth.clamp(1.0F - age / 9.0F, 0.0F, 1.0F);
			if (fade <= 0.0F) {
				continue;
			}
			float px = sx + 2.2F * s * s + age * 1.5F;
			float py = sy - 5.5F * s * s;
			float r = 1.5F + age * 1.6F;
			int a = (int) (fade * 120.0F);
			int grey = 150 + (int) (60 * fade);
			dot(g, x, y, scale, px, py, r, a << 24 | grey << 16 | grey << 8 | grey);
		}
		if (p < burn) {
			float px = sx + 2.2F * p * p;
			float py = sy - 5.5F * p * p;
			if (p < 2.5F) {
				float glow = 1.0F - p / 2.5F;
				dot(g, x, y, scale, sx, sy, 10.0F * glow + 4.0F, (int) (glow * 140) << 24 | 0xFFB050);
			}
			dot(g, x, y, scale, px, py + 3.0F, 2.5F, 0xC0FFA030);
			dot(g, x, y, scale, px, py + 1.5F, 1.5F, 0xFFFFF0C0);
			dot(g, x, y, scale, px, py - 1.0F, 1.0F, 0xFF202028);
		}
	}

	private static void dot(GuiGraphics g, int x, int y, float scale, float px, float py, float r, int argb) {
		int x0 = Math.round(x + (px - r) * scale);
		int y0 = Math.round(y + (py - r) * scale);
		int x1 = Math.max(x0 + 1, Math.round(x + (px + r) * scale));
		int y1 = Math.max(y0 + 1, Math.round(y + (py + r) * scale));
		g.fill(x0, y0, x1, y1, argb);
	}

	/** The mod's title in place of the Minecraft logo. */
	public static void logo(GuiGraphics g, int w, float alpha) {
		Font font = Minecraft.getInstance().font;
		int a = Mth.clamp((int) (alpha * 255.0F), 4, 255) << 24;
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(w / 2.0F, 18.0F);
		pose.scale(4.0F, 4.0F);
		String title = "BALLISTIC";
		g.drawString(font, title, -font.width(title) / 2 + 1, 1, a | 0x5A2A08, false);
		g.drawString(font, title, -font.width(title) / 2, 0, a | 0xF2F2F2, false);
		pose.popMatrix();
		pose.pushMatrix();
		pose.translate(w / 2.0F, 54.0F);
		pose.scale(2.5F, 2.5F);
		String sub = "MISSILES";
		g.drawString(font, sub, -font.width(sub) / 2 + 1, 1, a | 0x2A1004, false);
		g.drawString(font, sub, -font.width(sub) / 2, 0, a | 0xFF8A2A, false);
		pose.popMatrix();
		String line = "MINECRAFT MOD  -  v" + version();
		g.drawString(font, line, w / 2 - font.width(line) / 2, 78, a | 0x9AA0A8, true);
	}

	/** The radio call where the yellow splash text would be. */
	public static void call(GuiGraphics g, int w, float alpha) {
		Font font = Minecraft.getInstance().font;
		float pulse = 1.8F - Mth.abs(Mth.sin((Util.getMillis() % 1000L) / 1000.0F * Mth.TWO_PI) * 0.1F);
		pulse = pulse * 100.0F / (font.width(CALL) + 32);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(w / 2.0F + 118.0F, 44.0F);
		pose.rotate(-0.35F);
		pose.scale(pulse, pulse);
		int a = Mth.clamp((int) (alpha * 255.0F), 4, 255) << 24;
		g.drawCenteredString(font, CALL, 0, -8, a | 0xFFD040);
		pose.popMatrix();
	}

	private static String version() {
		if (version == null) {
			version = FabricLoader.getInstance().getModContainer(BallisticMissiles.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		}
		return version;
	}
}
