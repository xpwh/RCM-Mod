package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.ModRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The lock in front of the control panel, made up as a missile launch-control terminal: amber CRT text,
 * classification banners, hazard stripes, a radar scope sweeping for contacts, DEFCON and silo status, and
 * the launch code typed into its boxes. A wrong code sets off the radar alarm and tears the panel red,
 * three in a row seal the terminal for a few seconds; the right code drops to DEFCON 1, launches a missile
 * off the scope and zooms through into the menu. Once unlocked it stays open until the game is restarted.
 * Only the password's SHA-256 is kept here.
 */
public class PasswordScreen extends Screen {
	private static final String HASH = "97db65274cf09892773eed0fb7751716d93f33ae995029961402d2e9b7a5d9df";
	private static final String BANNER = "STRENG GEHEIM // NUR FÜR BEFUGTES PERSONAL // STRATEGISCHES RAKETENKOMMANDO // ZUGRIFF WIRD PROTOKOLLIERT // ";
	private static final String GLITCH = "#%&@$*+=?/<>0123456789ABCDEF";
	private static final int W = 340;
	private static final int H = 206;
	private static final int SLOTS = 14;
	private static final int AMBER = 0xFFB020;
	private static final int RED = 0xFF3030;
	private static final int GREEN = 0x40FF70;
	private static final int DARK = 0x080A08;
	private static final long GRANT_TIME = 1700L;
	/** Contacts on the scope: angle (radians), distance (0..1). */
	private static final float[][] BLIPS = {{0.7F, 0.62F}, {2.1F, 0.38F}, {3.4F, 0.8F}, {4.6F, 0.55F}, {5.5F, 0.27F}};

	private static boolean unlocked;

	private final Supplier<Screen> target;
	private final @Nullable Screen parent;
	private EditBox box;
	private long opened = -1L;
	private long deniedAt = -10_000L;
	private long grantedAt = -1L;
	private long lockedUntil;
	private long lastKey = -10_000L;
	private int fails;
	private int lastLength;
	private boolean bootBeep;

	public PasswordScreen(@Nullable Screen parent, Supplier<Screen> target) {
		super(Component.literal("Startfreigabe"));
		this.parent = parent;
		this.target = target;
	}

	/** The menu straight away when it was unlocked before, otherwise the prompt in front of it. */
	public static Screen guard(@Nullable Screen parent, Supplier<Screen> target) {
		return unlocked ? target.get() : new PasswordScreen(parent, target);
	}

	@Override
	protected void init() {
		String typed = this.box != null ? this.box.getValue() : "";
		this.box = new EditBox(this.font, this.width / 2, this.height / 2, 120, 20, Component.literal("Startcode"));
		this.box.setMaxLength(32);
		this.box.setValue(typed);
		this.box.setFilter(s -> !s.contains(" "));
		this.addWidget(this.box); // takes the keys; the code boxes are drawn here
		this.setInitialFocus(this.box);
		if (this.opened < 0L) {
			this.opened = Util.getMillis();
			play(ModRegistry.RADIO_CLICK, 0.9F, 0.8F);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.grantedAt >= 0L) {
			return true;
		}
		if (event.isConfirmation()) {
			submit();
			return true;
		}
		return super.keyPressed(event);
	}

	private void submit() {
		long now = Util.getMillis();
		if (now < this.lockedUntil || this.box.getValue().isEmpty()) {
			play(ModRegistry.COUNTDOWN_BEEP, 0.5F, 0.5F);
			return;
		}
		if (sha256(this.box.getValue().trim()).equals(HASH)) {
			this.grantedAt = now;
			unlocked = true;
			play(ModRegistry.TARGET_LOCK, 1.0F, 0.8F);
			play(ModRegistry.COUNTDOWN_BEEP, 1.4F, 0.6F);
		} else {
			this.deniedAt = now;
			this.fails++;
			this.box.setValue("");
			this.lastLength = 0;
			play(ModRegistry.RADAR_ALARM, 1.0F, 0.55F);
			if (this.fails >= 3) {
				this.fails = 0;
				this.lockedUntil = now + 5000L;
				play(ModRegistry.SILO_LID_LOCK, 1.0F, 0.8F);
			}
		}
	}

	@Override
	public void tick() {
		long now = Util.getMillis();
		if (!this.bootBeep && now - this.opened > 650L) {
			this.bootBeep = true;
			play(ModRegistry.COUNTDOWN_BEEP, 1.2F, 0.35F);
		}
		if (this.grantedAt >= 0L) {
			long since = now - this.grantedAt;
			if (since > 450L && since < 500L) {
				play(ModRegistry.SAM_LAUNCH, 1.3F, 0.35F); // the missile leaving the scope
			}
			if (since > GRANT_TIME) {
				this.minecraft.setScreen(this.target.get());
			}
		}
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		long now = Util.getMillis();
		float t = (now - this.opened) / 1000.0F;
		float denied = 1.0F - Mth.clamp((now - this.deniedAt) / 700.0F, 0.0F, 1.0F);
		float granted = this.grantedAt < 0L ? 0.0F : Mth.clamp((now - this.grantedAt) / (float) GRANT_TIME, 0.0F, 1.0F);
		boolean locked = now < this.lockedUntil;
		int accent = granted > 0.0F ? GREEN : denied > 0.0F || locked ? RED : AMBER;

		int length = this.box.getValue().length();
		if (length != this.lastLength) {
			if (length > this.lastLength) {
				play(ModRegistry.RADIO_CLICK, 1.7F + (float) Math.random() * 0.3F, 0.25F);
			}
			this.lastLength = length;
			this.lastKey = now;
		}

		backdrop(g, t, now, accent, granted, denied);

		float intro = Mth.clamp(t / 0.5F, 0.0F, 1.0F);
		float out = smooth(Mth.clamp((granted - 0.7F) / 0.3F, 0.0F, 1.0F));
		float scale = easeOutBack(intro) * (1.0F + out * 0.4F);
		float shake = denied * denied * Mth.sin(t * 75.0F) * 10.0F;
		float fade = intro * (1.0F - out);
		if (fade > 0.01F) {
			g.pose().pushMatrix();
			g.pose().translate(this.width / 2.0F + shake, this.height / 2.0F);
			g.pose().scale(scale, scale);
			panel(g, t, now, accent, fade, denied, granted, locked);
			g.pose().popMatrix();
		}
	}

	private void backdrop(GuiGraphics g, float t, long now, int accent, float granted, float denied) {
		int w = this.width;
		int h = this.height;
		float in = Mth.clamp(t / 0.3F, 0.0F, 1.0F);
		g.fill(0, 0, w, h, argb(0.84F * in, DARK));
		// map grid, scrolling slowly, with coordinates along the edge
		int step = 32;
		int off = (int) (t * 6.0F) % step;
		for (int x = -step + off; x < w; x += step) {
			g.fill(x, 0, x + 1, h, argb(0.05F * in, accent));
		}
		for (int y = -step + off; y < h; y += step) {
			g.fill(0, y, w, y + 1, argb(0.05F * in, accent));
		}
		// CRT scan lines and a sweep running down
		for (int y = 0; y < h; y += 3) {
			g.fill(0, y, w, y + 1, argb(0.14F * in, 0x000000));
		}
		int sweep = (int) ((t * 0.3F % 1.0F) * (h + 80)) - 40;
		g.fillGradient(0, sweep - 40, w, sweep, argb(0.0F, accent), argb(0.06F * in, accent));
		// classification banners, top and bottom, crawling
		banner(g, 0, t, in, granted, denied);
		banner(g, h - 11, -t, in, granted, denied);
		// telemetry down the sides
		g.drawString(this.font, "LAT 52°31'N  LON 13°24'E", 6, 16, argb(0.35F * in, accent), false);
		g.drawString(this.font, "UPLINK: AKTIV  KANAL 7", 6, 26, argb(0.35F * in, accent), false);
		String utc = "UTC " + LocalTime.now(ZoneOffset.UTC).withNano(0);
		g.drawString(this.font, utc, w - 6 - this.font.width(utc), 16, argb(0.5F * in, accent), false);
		String net = "NETZ: GESICHERT";
		g.drawString(this.font, net, w - 6 - this.font.width(net), 26, argb(0.35F * in, accent), false);
		if (denied > 0.0F) {
			g.fill(0, 0, w, h, argb(0.25F * denied * denied, RED));
		}
		if (granted > 0.0F) {
			float flash = granted < 0.1F ? granted / 0.1F : 1.0F - (granted - 0.1F) / 0.9F;
			g.fill(0, 0, w, h, argb(0.2F * flash, GREEN));
		}
	}

	private void banner(GuiGraphics g, int y, float t, float in, float granted, float denied) {
		int w = this.width;
		int color = granted > 0.0F ? 0x1C7A30 : 0x8A1010;
		g.fill(0, y, w, y + 11, argb(0.9F * in, color));
		String text = granted > 0.0F ? "STARTFREIGABE ERTEILT // WAFFENSYSTEME FREIGEGEBEN // " : BANNER;
		int tw = this.font.width(text);
		int x = (int) (-((t * 30.0F) % tw + tw) % tw);
		for (int i = x - tw; i < w; i += tw) {
			g.drawString(this.font, text, i, y + 2, argb(in, 0xFFE8E8), false);
		}
	}

	private void panel(GuiGraphics g, float t, long now, int accent, float fade, float denied, float granted, boolean locked) {
		int x0 = -W / 2;
		int y0 = -H / 2;
		int x1 = W / 2;
		int y1 = H / 2;
		for (int i = 4; i >= 1; i--) {
			g.fill(x0 - i * 2, y0 - i * 2, x1 + i * 2, y1 + i * 2, argb(0.03F * fade, accent));
		}
		g.fill(x0, y0, x1, y1, argb(0.93F * fade, 0x0C0E0A));

		// hazard stripes along the top, crawling
		hazard(g, x0, y0, x1, y0 + 7, t, fade, granted > 0.0F ? GREEN : denied > 0.0F || locked ? RED : 0xFFC81E);
		// title bar
		g.fill(x0, y0 + 7, x1, y0 + 21, argb(0.2F * fade, accent));
		g.drawString(this.font, typed("STRATCOM  //  STARTKONTROLLE  //  SILO 07", t - 0.2F, 70.0F), x0 + 8, y0 + 10, argb(fade, accent), false);
		boolean blink = (now / 350L) % 2L == 0L;
		String rec = "● REC";
		g.drawString(this.font, rec, x1 - 8 - this.font.width(rec), y0 + 10, argb((blink ? 1.0F : 0.3F) * fade, RED), false);

		float draw = smooth(Mth.clamp((t - 0.1F) / 0.6F, 0.0F, 1.0F));
		frame(g, x0, y0, x1, y1, draw, argb(fade, accent));
		if (draw >= 1.0F) {
			int c = argb(fade, accent);
			bracket(g, x0 - 3, y0 - 3, 12, 1, 1, c);
			bracket(g, x1 + 2, y0 - 3, 12, -1, 1, c);
			bracket(g, x0 - 3, y1 + 2, 12, 1, -1, c);
			bracket(g, x1 + 2, y1 + 2, 12, -1, -1, c);
		}

		// radar scope on the left
		int rx = x0 + 58;
		int ry = y0 + 82;
		radar(g, t, rx, ry, 44, accent, fade, granted, denied);
		g.drawString(this.font, "RADAR // LUFTRAUM", rx - 44, ry + 52, argb(0.6F * fade, accent), false);
		String contacts = granted > 0.0F ? "1 ABGANG" : BLIPS.length + " KONTAKTE";
		g.drawString(this.font, contacts, rx - 44, ry + 62, argb(0.45F * fade, accent), false);

		// status on the right
		int sx = x0 + 120;
		String title = granted > 0.0F ? "STARTFREIGABE ERTEILT"
			: denied > 0.0F ? "UNGÜLTIGER STARTCODE"
			: locked ? "TERMINAL VERSIEGELT"
			: glitched("AUTORISIERUNG ERFORDERLICH", t - 0.35F, now);
		g.pose().pushMatrix();
		g.pose().translate(sx, y0 + 30);
		g.pose().scale(1.3F, 1.3F);
		g.drawString(this.font, title, 0, 0, argb(fade, granted > 0.0F || denied > 0.0F || locked ? accent : 0xFFF2D8), true);
		g.pose().popMatrix();

		// DEFCON box, dropping to 1 when cleared
		int defcon = granted > 0.0F ? Math.max(1, 3 - (int) (granted * 8.0F)) : 3;
		int defColor = defcon == 1 ? RED : defcon == 2 ? 0xFF8020 : AMBER;
		boolean defBlink = defcon == 1 ? (now / 150L) % 2L == 0L : true;
		int dy = y0 + 48;
		g.fill(sx, dy, sx + 62, dy + 26, argb(0.9F * fade, defBlink ? defColor : 0x2A1A08));
		g.drawString(this.font, "DEFCON", sx + 4, dy + 3, argb(fade, DARK), false);
		g.pose().pushMatrix();
		g.pose().translate(sx + 44, dy + 4);
		g.pose().scale(2.0F, 2.0F);
		g.drawString(this.font, String.valueOf(defcon), 0, 0, argb(fade, DARK), false);
		g.pose().popMatrix();
		String[] lines = {
			"SILO 07: " + (granted > 0.0F ? "OFFEN" : "GESICHERT"),
			"SPRENGKOPF: " + (granted > 0.0F ? "SCHARF" : "GESICHERT"),
			"ZIELDATEN: " + (granted > 0.0F ? "GELADEN" : "---"),
		};
		for (int i = 0; i < lines.length; i++) {
			g.drawString(this.font, typed(lines[i], t - 0.5F - i * 0.15F, 60.0F), sx + 70, dy + 1 + i * 9, argb(0.75F * fade, i == 0 && granted > 0.0F ? GREEN : 0xD8C8A0), false);
		}

		// the launch code, one box per character
		int cy = y0 + 104;
		String label = locked ? "SICHERHEITSSPERRE  -  " + ((this.lockedUntil - now) / 1000L + 1L) + " s" : "STARTCODE EINGEBEN";
		g.drawString(this.font, typed(label, t - 0.7F, 60.0F), sx, cy - 11, argb(0.8F * fade, locked ? RED : accent), false);
		float codeIn = smooth(Mth.clamp((t - 0.6F) / 0.4F, 0.0F, 1.0F));
		int n = this.box.getValue().length();
		int box = 12;
		int gap = 2;
		for (int i = 0; i < SLOTS; i++) {
			float show = Mth.clamp(codeIn * SLOTS - i, 0.0F, 1.0F);
			if (show <= 0.0F) {
				break;
			}
			int bx = sx + i * (box + gap);
			boolean filled = i < n;
			boolean current = i == Math.min(n, SLOTS - 1) && granted <= 0.0F && !locked;
			g.fill(bx, cy, bx + box, cy + 16, argb(0.9F * fade * show, filled ? 0x2A2008 : 0x111309));
			outline(g, bx, cy, bx + box, cy + 16, argb(fade * show * (current && blink ? 1.0F : 0.55F), accent));
			if (filled) {
				// a typed character rolls through random symbols before it settles as a mark
				float settle = i == n - 1 ? Mth.clamp((now - this.lastKey) / 180.0F, 0.0F, 1.0F) : 1.0F;
				String ch = settle < 1.0F ? String.valueOf(GLITCH.charAt((int) (hash(now / 30L + i) * GLITCH.length()))) : "*";
				g.drawString(this.font, ch, bx + (box - this.font.width(ch)) / 2 + 1, cy + 5, argb(fade, settle < 1.0F ? 0xFFFFFF : accent), false);
			}
		}
		if (n > SLOTS) {
			g.drawString(this.font, "+" + (n - SLOTS), sx + SLOTS * (box + gap) + 2, cy + 5, argb(fade, accent), false);
		}

		// attempts and keys
		g.drawString(this.font, "VERSUCHE", sx, cy + 24, argb(0.55F * fade, 0xD8C8A0), false);
		for (int i = 0; i < 3; i++) {
			int on = i < this.fails || locked ? RED : 0x3A3A2A;
			g.fill(sx + 50 + i * 10, cy + 24, sx + 56 + i * 10, cy + 31, argb(fade, on));
		}
		String keys = "[ENTER] AUTORISIEREN    [ESC] ABBRECHEN";
		g.drawString(this.font, keys, x1 - 10 - this.font.width(keys), y1 - 14, argb(0.5F * fade, 0xD8C8A0), false);
		hazard(g, x0, y1 - 4, x1, y1, -t, fade * 0.7F, accent);

		// a wrong code tears the panel: bars of it slide sideways for a moment
		if (denied > 0.0F) {
			long seed = now / 40L;
			for (int i = 0; i < 6; i++) {
				int by = y0 + (int) (hash(seed * 7 + i) * H);
				int bh = 2 + (int) (hash(seed * 13 + i * 3) * 7);
				int shift = (int) ((hash(seed * 3 + i * 5) - 0.5F) * 46.0F * denied);
				g.fill(x0 + shift, by, x1 + shift, Math.min(y1, by + bh), argb(0.35F * denied * fade, RED));
			}
		}
	}

	private void radar(GuiGraphics g, float t, int x, int y, int r, int accent, float fade, float granted, float denied) {
		// rings and cross hairs
		for (int ring = 1; ring <= 3; ring++) {
			circle(g, x, y, r * ring / 3.0F, ring == 3 ? 72 : 48, argb((ring == 3 ? 0.8F : 0.3F) * fade, accent));
		}
		g.fill(x - r, y, x + r, y + 1, argb(0.25F * fade, accent));
		g.fill(x, y - r, x + 1, y + r, argb(0.25F * fade, accent));
		// bearing ticks
		for (int i = 0; i < 36; i++) {
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().rotate(i * Mth.TWO_PI / 36.0F);
			g.fill(r + 2, 0, r + (i % 9 == 0 ? 7 : 4), 1, argb(0.5F * fade, accent));
			g.pose().popMatrix();
		}
		// the sweep, with a fading wake
		float sweep = t * 2.4F % Mth.TWO_PI;
		for (int k = 0; k < 14; k++) {
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().rotate(sweep - k * 0.045F);
			g.fill(0, 0, r, 1, argb((k == 0 ? 0.9F : 0.3F * (1.0F - k / 14.0F)) * fade, accent));
			g.pose().popMatrix();
		}
		// contacts, lit as the sweep passes and fading after
		for (float[] blip : BLIPS) {
			float behind = (sweep - blip[0]) % Mth.TWO_PI;
			if (behind < 0.0F) {
				behind += Mth.TWO_PI;
			}
			float glow = 1.0F - behind / Mth.TWO_PI;
			glow = glow * glow;
			int bx = x + Math.round(Mth.cos(blip[0]) * blip[1] * r);
			int by = y + Math.round(Mth.sin(blip[0]) * blip[1] * r);
			int c = argb(glow * fade, denied > 0.0F ? RED : accent);
			g.fill(bx - 1, by - 1, bx + 2, by + 2, c);
		}
		// cleared: a missile lifts off the middle and climbs out of the scope, trailing smoke
		if (granted > 0.0F) {
			float climb = smooth(Mth.clamp((granted - 0.2F) / 0.6F, 0.0F, 1.0F));
			int my = y - Math.round(climb * (r + 30.0F));
			for (int k = 1; k < 12; k++) {
				int ty = my + 7 + k * 3;
				if (ty > y + 2) {
					break;
				}
				int spread = k / 3;
				g.fill(x - spread, ty, x + 1 + spread, ty + 3, argb(0.5F * (1.0F - k / 12.0F) * fade, 0xC8C8B8));
			}
			int body = argb(fade, 0xF0F0E0);
			g.fill(x, my - 3, x + 1, my - 2, body); // nose
			g.fill(x - 1, my - 2, x + 2, my + 6, body);
			g.fill(x - 2, my + 4, x + 3, my + 7, body); // fins
			boolean flicker = (Util.getMillis() / 40L) % 2L == 0L;
			g.fill(x - 1, my + 7, x + 2, my + (flicker ? 11 : 9), argb(fade, 0xFF9020));
		} else {
			// the silo at the centre
			g.fill(x - 2, y - 2, x + 3, y + 3, argb(fade, accent));
		}
	}

	// ------------------------------------------------------------------ helpers

	private static void circle(GuiGraphics g, int x, int y, float r, int segments, int color) {
		for (int i = 0; i < segments; i++) {
			float a = i * Mth.TWO_PI / segments;
			int px = x + Math.round(Mth.cos(a) * r);
			int py = y + Math.round(Mth.sin(a) * r);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}

	/** Diagonal warning stripes in a strip, crawling sideways. */
	private static void hazard(GuiGraphics g, int x0, int y0, int x1, int y1, float t, float fade, int color) {
		int period = 12;
		int shift = Math.floorMod((int) (t * 20.0F), period);
		int on = argb(0.85F * fade, color);
		int offc = argb(0.85F * fade, 0x101008);
		g.fill(x0, y0, x1, y1, offc);
		for (int y = y0; y < y1; y++) {
			int s = shift + (y - y0);
			for (int x = x0 - period + Math.floorMod(s, period); x < x1; x += period) {
				g.fill(Math.max(x0, x), y, Math.min(x1, x + period / 2), y + 1, on);
			}
		}
	}

	/** The outline drawn in clockwise from the top left, {@code p} of the way round. */
	private static void frame(GuiGraphics g, int x0, int y0, int x1, int y1, float p, int color) {
		int w = x1 - x0;
		int h = y1 - y0;
		float len = p * 2.0F * (w + h);
		g.fill(x0, y0, x0 + (int) Math.min(len, w), y0 + 1, color);
		len -= w;
		if (len > 0.0F) {
			g.fill(x1 - 1, y0, x1, y0 + (int) Math.min(len, h), color);
		}
		len -= h;
		if (len > 0.0F) {
			g.fill(x1 - (int) Math.min(len, w), y1 - 1, x1, y1, color);
		}
		len -= w;
		if (len > 0.0F) {
			g.fill(x0, y1 - (int) Math.min(len, h), x0 + 1, y1, color);
		}
	}

	private static void outline(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
		frame(g, x0, y0, x1, y1, 1.0F, color);
	}

	private static void bracket(GuiGraphics g, int x, int y, int size, int dx, int dy, int color) {
		g.fill(Math.min(x, x + dx * size), y, Math.max(x, x + dx * size) + 1, y + 1, color);
		g.fill(x, Math.min(y, y + dy * size), x + 1, Math.max(y, y + dy * size) + 1, color);
	}

	private static String typed(String text, float t, float perSecond) {
		return text.substring(0, Mth.clamp((int) (t * perSecond), 0, text.length()));
	}

	private static String glitched(String text, float t, long now) {
		int shown = Mth.clamp((int) (t * 24.0F), 0, text.length());
		StringBuilder out = new StringBuilder(text.substring(0, shown));
		int scramble = Math.min(text.length() - shown, 4);
		for (int i = 0; i < scramble; i++) {
			out.append(GLITCH.charAt((int) (hash(now / 50L + i * 31L) * GLITCH.length())));
		}
		return out.toString();
	}

	private static float hash(long n) {
		n = (n << 13) ^ n;
		return ((n * (n * n * 15731L + 789221L) + 1376312589L) & 0x7fffffffL) / (float) 0x7fffffffL;
	}

	private static float smooth(float x) {
		return x * x * (3.0F - 2.0F * x);
	}

	private static float easeOutBack(float x) {
		float c1 = 1.70158F;
		float k = x - 1.0F;
		return 1.0F + (c1 + 1.0F) * k * k * k + c1 * k * k;
	}

	private static int argb(float alpha, int rgb) {
		return Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24 | rgb & 0xFFFFFF;
	}

	private static void play(SoundEvent sound, float pitch, float volume) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}

	private static String sha256(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			return "";
		}
	}
}
