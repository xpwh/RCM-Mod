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
 * The mod's title screen: a live 3D scene of the launch complex at night instead of the panorama (see
 * {@link de.rcm.ballistic.client.render.TitleScene}). The Minecraft logo makes way for the mod's own, the
 * splash for a radio call.
 */
public final class TitleMenu {
	private static final Identifier LOGO = BallisticMissiles.id("textures/gui/title/logo.png");
	private static final int LOGO_W = 1024;
	private static final int LOGO_H = 300;
	private static final String[] CALLS = {
		"FOX THREE", "DANGER CLOSE", "LAUNCH CODES ACCEPTED", "DEFCON 1", "SPLASH ONE", "WEAPONS FREE", "FIRE FOR EFFECT",
		"BROKEN ARROW", "BANDITS, BANDITS", "TALLY HO", "RIFLE, RIFLE", "BINGO FUEL", "MISSILE AWAY", "GOOD LOCK, SHOOT"
	};
	private static final String CALL = CALLS[(int) (Math.random() * CALLS.length)];
	private static final long START = Util.getMillis();
	private static String version;

	private TitleMenu() {
	}

	/** The live 3D scene (see {@link de.rcm.ballistic.client.render.TitleScene}), darkened at the top and bottom for the title and menu. */
	public static void background(GuiGraphics g, int w, int h) {
		de.rcm.ballistic.client.render.TitleScene.draw(g, w, h);
		g.fillGradient(0, 0, w, h / 3, 0x90000000, 0x00000000);
		g.fillGradient(0, h - h / 3, w, h, 0x00000000, 0xA0000000);
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
