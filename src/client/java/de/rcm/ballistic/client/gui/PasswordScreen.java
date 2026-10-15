package de.rcm.ballistic.client.gui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The lock in front of the control panel: a password prompt that boots up like a military terminal - the
 * frame drawing itself in, the title typing out through glitching characters, a ring spinning round the
 * padlock. A wrong password shakes the panel red and glitches it, three in a row lock the prompt for a
 * few seconds; the right one turns it green, springs the padlock open and zooms through into the menu.
 * Once unlocked it stays open until the game is restarted. Only the password's SHA-256 is kept here.
 */
public class PasswordScreen extends Screen {
	private static final String HASH = "97db65274cf09892773eed0fb7751716d93f33ae995029961402d2e9b7a5d9df";
	private static final String TITLE = "ZUGRIFF GESCHÜTZT";
	private static final String HEADER = "BALLISTIC MISSILES // KONTROLLZENTRUM";
	private static final String GLITCH = "#%&@$*+=?/<>01X";
	private static final int W = 280;
	private static final int H = 164;
	private static final int CYAN = 0x3CE6FF;
	private static final int RED = 0xFF3B3B;
	private static final int GREEN = 0x3CFF7A;
	private static final long GRANT_TIME = 950L;

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

	public PasswordScreen(@Nullable Screen parent, Supplier<Screen> target) {
		super(Component.literal("Passwort"));
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
		this.box = new EditBox(this.font, this.width / 2 - 90, this.height / 2 + 6, 180, 20, Component.literal("Passwort"));
		this.box.setMaxLength(32);
		this.box.setValue(typed);
		this.box.setFilter(s -> !s.contains(" ") || s.isEmpty());
		this.addWidget(this.box); // takes the keys, but the field is drawn here, masked
		this.setInitialFocus(this.box);
		if (this.opened < 0L) {
			this.opened = Util.getMillis();
			play(SoundEvents.UI_TOAST_IN, 1.4F, 0.6F);
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
			play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.6F);
			return;
		}
		if (sha256(this.box.getValue().trim()).equals(HASH)) {
			this.grantedAt = now;
			unlocked = true;
			play(SoundEvents.BEACON_ACTIVATE, 1.7F, 0.7F);
			play(SoundEvents.NOTE_BLOCK_PLING.value(), 2.0F, 0.5F);
		} else {
			this.deniedAt = now;
			this.fails++;
			this.box.setValue("");
			this.lastLength = 0;
			play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.55F, 1.0F);
			play(SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), 0.5F, 0.6F);
			if (this.fails >= 3) {
				this.fails = 0;
				this.lockedUntil = now + 5000L;
				play(SoundEvents.IRON_DOOR_CLOSE, 0.6F, 0.8F);
			}
		}
	}

	@Override
	public void tick() {
		if (this.grantedAt >= 0L && Util.getMillis() - this.grantedAt > GRANT_TIME) {
			this.minecraft.setScreen(this.target.get());
		}
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		long now = Util.getMillis();
		float t = (now - this.opened) / 1000.0F;
		float denied = 1.0F - Mth.clamp((now - this.deniedAt) / 650.0F, 0.0F, 1.0F);
		float granted = this.grantedAt < 0L ? 0.0F : Mth.clamp((now - this.grantedAt) / (float) GRANT_TIME, 0.0F, 1.0F);
		boolean locked = now < this.lockedUntil;
		int accent = granted > 0.0F ? GREEN : denied > 0.0F || locked ? RED : CYAN;

		// keystrokes: a click, and the newest dot pops
		int length = this.box.getValue().length();
		if (length != this.lastLength) {
			if (length > this.lastLength) {
				play(SoundEvents.UI_BUTTON_CLICK.value(), 1.6F + (float) Math.random() * 0.3F, 0.18F);
			}
			this.lastLength = length;
			this.lastKey = now;
		}

		backdrop(g, t, accent, granted, denied);

		int cx = this.width / 2;
		int cy = this.height / 2;
		float intro = Mth.clamp(t / 0.45F, 0.0F, 1.0F);
		float scale = easeOutBack(intro) * (1.0F + granted * granted * 0.35F);
		float shake = denied * denied * Mth.sin(t * 70.0F) * 9.0F;
		float fade = intro * (1.0F - smooth(Mth.clamp((granted - 0.55F) / 0.45F, 0.0F, 1.0F)));
		if (fade <= 0.01F) {
			return;
		}

		g.pose().pushMatrix();
		g.pose().translate(cx + shake, cy);
		g.pose().scale(scale, scale);
		panel(g, t, now, accent, fade, denied, granted, locked);
		g.pose().popMatrix();
	}

	private void backdrop(GuiGraphics g, float t, int accent, float granted, float denied) {
		int w = this.width;
		int h = this.height;
		float in = Mth.clamp(t / 0.3F, 0.0F, 1.0F);
		g.fill(0, 0, w, h, argb(0.78F * in, 0x05080C));
		// a scrolling grid
		int step = 24;
		int off = (int) (t * 12.0F) % step;
		int grid = argb(0.06F * in, accent);
		for (int x = -step + off; x < w; x += step) {
			g.fill(x, 0, x + 1, h, grid);
		}
		for (int y = -step + off; y < h; y += step) {
			g.fill(0, y, w, y + 1, grid);
		}
		// scan lines, and a bright band sweeping down
		for (int y = 0; y < h; y += 3) {
			g.fill(0, y, w, y + 1, argb(0.12F * in, 0x000000));
		}
		int sweep = (int) ((t * 0.35F % 1.0F) * (h + 80)) - 40;
		g.fillGradient(0, sweep - 30, w, sweep, argb(0.0F, accent), argb(0.07F * in, accent));
		g.fill(0, sweep, w, sweep + 1, argb(0.18F * in, accent));
		// the screen's corners bracketed
		int b = 18;
		int c = argb(0.5F * in, accent);
		bracket(g, 8, 8, b, 1, 1, c);
		bracket(g, w - 9, 8, b, -1, 1, c);
		bracket(g, 8, h - 9, b, 1, -1, c);
		bracket(g, w - 9, h - 9, b, -1, -1, c);
		// a red flash for a wrong password, a green one for the right
		if (denied > 0.0F) {
			g.fill(0, 0, w, h, argb(0.22F * denied * denied, RED));
		}
		if (granted > 0.0F) {
			float flash = granted < 0.15F ? granted / 0.15F : 1.0F - (granted - 0.15F) / 0.85F;
			g.fill(0, 0, w, h, argb(0.25F * flash, GREEN));
			// light lines bursting out sideways from the panel
			int reach = (int) (smooth(granted) * w * 0.6F);
			int cy = h / 2;
			g.fillGradient(w / 2 - reach, cy - 1, w / 2 + reach, cy + 1, argb(0.7F * (1.0F - granted), GREEN), argb(0.7F * (1.0F - granted), GREEN));
		}
	}

	private void panel(GuiGraphics g, float t, long now, int accent, float fade, float denied, float granted, boolean locked) {
		int x0 = -W / 2;
		int y0 = -H / 2;
		int x1 = W / 2;
		int y1 = H / 2;
		// body, with a soft glow round it
		for (int i = 4; i >= 1; i--) {
			g.fill(x0 - i * 2, y0 - i * 2, x1 + i * 2, y1 + i * 2, argb(0.035F * fade, accent));
		}
		g.fill(x0, y0, x1, y1, argb(0.9F * fade, 0x0A0F14));
		g.fillGradient(x0, y0, x1, y0 + 26, argb(0.25F * fade, accent), argb(0.0F, accent));

		// the frame drawing itself round, then the corner ticks
		float draw = smooth(Mth.clamp((t - 0.1F) / 0.55F, 0.0F, 1.0F));
		frame(g, x0, y0, x1, y1, draw, argb(fade, accent));
		if (draw >= 1.0F) {
			int c = argb(fade, accent);
			bracket(g, x0 - 3, y0 - 3, 10, 1, 1, c);
			bracket(g, x1 + 2, y0 - 3, 10, -1, 1, c);
			bracket(g, x0 - 3, y1 + 2, 10, 1, -1, c);
			bracket(g, x1 + 2, y1 + 2, 10, -1, -1, c);
		}

		// header, typed out
		String header = typed(HEADER, t - 0.25F, 60.0F);
		g.drawString(this.font, header, x0 + 10, y0 + 8, argb(0.75F * fade, accent), false);
		boolean blink = (now / 400L) % 2L == 0L;
		g.fill(x1 - 14, y0 + 9, x1 - 9, y0 + 14, argb((blink ? 0.9F : 0.25F) * fade, granted > 0.0F ? GREEN : denied > 0.0F || locked ? RED : 0xFFC03C));

		// padlock with a spinning ring
		int lx = x0 + 42;
		int ly = y0 + 74;
		padlock(g, t, lx, ly, accent, fade, granted);

		// title, typed out through glitching characters
		String title = granted > 0.0F ? "ZUGRIFF GEWÄHRT" : denied > 0.0F ? "ZUGRIFF VERWEIGERT" : locked ? "TERMINAL GESPERRT" : glitched(TITLE, t - 0.35F, now);
		g.pose().pushMatrix();
		g.pose().translate(x0 + 84, y0 + 40);
		g.pose().scale(1.5F, 1.5F);
		g.drawString(this.font, title, 0, 0, argb(fade, granted > 0.0F || denied > 0.0F || locked ? accent : 0xFFFFFF), true);
		g.pose().popMatrix();
		String sub = locked ? "Zu viele Fehlversuche - warte " + ((this.lockedUntil - now) / 1000L + 1L) + " s"
			: granted > 0.0F ? "Willkommen, Kommandant." : "Passwort eingeben, um fortzufahren";
		g.drawString(this.font, typed(sub, t - 0.7F, 70.0F), x0 + 84, y0 + 58, argb(0.7F * fade, 0xB8C4CC), false);

		// the field: a dot per character, the newest popping in, and a blinking caret
		int fx0 = x0 + 84;
		int fx1 = x1 - 14;
		int fy0 = y0 + 76;
		int fy1 = fy0 + 22;
		float fieldIn = smooth(Mth.clamp((t - 0.55F) / 0.3F, 0.0F, 1.0F));
		int mid = (fx0 + fx1) / 2;
		int half = (int) ((fx1 - fx0) / 2 * fieldIn);
		g.fill(mid - half, fy0, mid + half, fy1, argb(0.85F * fade, 0x04070A));
		frame(g, mid - half, fy0, mid + half, fy1, 1.0F, argb(0.8F * fade * fieldIn, accent));
		if (fieldIn >= 1.0F) {
			int n = this.box.getValue().length();
			int spacing = 9;
			int sx = fx0 + 8;
			for (int i = 0; i < n && sx + i * spacing < fx1 - 10; i++) {
				float pop = i == n - 1 ? Mth.clamp((now - this.lastKey) / 120.0F, 0.0F, 1.0F) : 1.0F;
				int r = Math.round(2.0F + (1.0F - pop) * 2.5F);
				int px = sx + i * spacing;
				int py = (fy0 + fy1) / 2;
				g.fill(px - r, py - r, px + r + 1, py + r + 1, argb(fade, i == n - 1 && pop < 1.0F ? 0xFFFFFF : accent));
			}
			if (blink && granted <= 0.0F && !locked) {
				int caret = sx + Math.min(n, (fx1 - fx0 - 18) / spacing) * spacing - 3;
				g.fill(caret, fy0 + 5, caret + 1, fy1 - 5, argb(fade, 0xFFFFFF));
			}
		}

		// fails so far, and the hint
		for (int i = 0; i < 3; i++) {
			int on = i < this.fails || locked ? RED : 0x33404A;
			g.fill(fx0 + i * 10, fy1 + 7, fx0 + i * 10 + 6, fy1 + 10, argb(fade, on));
		}
		String hint = "ENTER bestätigen  ·  ESC abbrechen";
		g.drawString(this.font, hint, x1 - 12 - this.font.width(hint), y1 - 14, argb(0.45F * fade, 0xB8C4CC), false);

		// a wrong password tears the panel: bars of it slide sideways for a moment
		if (denied > 0.0F) {
			long seed = now / 40L;
			for (int i = 0; i < 5; i++) {
				float r1 = hash(seed * 7 + i);
				float r2 = hash(seed * 13 + i * 3);
				int by = y0 + (int) (r1 * H);
				int bh = 2 + (int) (r2 * 6);
				int shift = (int) ((hash(seed * 3 + i * 5) - 0.5F) * 40.0F * denied);
				g.fill(x0 + shift, by, x1 + shift, Math.min(y1, by + bh), argb(0.35F * denied * fade, RED));
			}
		}
	}

	private void padlock(GuiGraphics g, float t, int x, int y, int accent, float fade, float granted) {
		// a ring of ticks spinning round it, faster as it opens
		float spin = t * 1.4F + granted * granted * 9.0F;
		int ticks = 16;
		for (int i = 0; i < ticks; i++) {
			g.pose().pushMatrix();
			g.pose().translate(x, y);
			g.pose().rotate(spin + i * Mth.TWO_PI / ticks);
			float a = (i % 4 == 0 ? 0.9F : 0.4F) * fade;
			g.fill(24, -1, i % 4 == 0 ? 30 : 27, 1, argb(a, accent));
			g.pose().popMatrix();
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().rotate(-t * 0.6F);
		for (int i = 0; i < 3; i++) {
			g.pose().rotate(Mth.TWO_PI / 3.0F);
			g.fill(-2, -20, 2, -19, argb(0.6F * fade, accent));
		}
		g.pose().popMatrix();

		int body = argb(fade, accent);
		int dark = argb(fade, 0x0A0F14);
		// shackle: lifts and swings open when granted
		int lift = Math.round(smooth(Mth.clamp(granted / 0.35F, 0.0F, 1.0F)) * 6.0F);
		int sy = y - 13 - lift;
		g.fill(x - 7, sy, x + 7, sy + 2, body);
		g.fill(x - 7, sy, x - 5, y - 3 - lift, body);
		if (lift == 0) {
			g.fill(x + 5, sy, x + 7, y - 3, body);
		} else {
			g.fill(x + 5, sy, x + 7, sy + 4, body);
		}
		// body with a keyhole
		g.fill(x - 10, y - 4, x + 10, y + 12, body);
		g.fill(x - 2, y + 1, x + 2, y + 5, dark);
		g.fill(x - 1, y + 5, x + 1, y + 9, dark);
	}

	// ------------------------------------------------------------------ helpers

	/** The outline drawn in clockwise from the top left, {@code p} of the way round. */
	private static void frame(GuiGraphics g, int x0, int y0, int x1, int y1, float p, int color) {
		int w = x1 - x0;
		int h = y1 - y0;
		float len = p * 2.0F * (w + h);
		float top = Math.min(len, w);
		g.fill(x0, y0, x0 + (int) top, y0 + 1, color);
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

	private static void bracket(GuiGraphics g, int x, int y, int size, int dx, int dy, int color) {
		g.fill(Math.min(x, x + dx * size), y, Math.max(x, x + dx * size) + 1, y + 1, color);
		g.fill(x, Math.min(y, y + dy * size), x + 1, Math.max(y, y + dy * size) + 1, color);
	}

	private static String typed(String text, float t, float perSecond) {
		int n = Mth.clamp((int) (t * perSecond), 0, text.length());
		return text.substring(0, n);
	}

	private static String glitched(String text, float t, long now) {
		int shown = Mth.clamp((int) (t * 22.0F), 0, text.length());
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
		float c3 = c1 + 1.0F;
		float k = x - 1.0F;
		return 1.0F + c3 * k * k * k + c1 * k * k;
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
