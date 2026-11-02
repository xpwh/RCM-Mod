package de.rcm.ballistic.client.gui;

import static de.rcm.ballistic.client.gui.UiKit.approach;
import static de.rcm.ballistic.client.gui.UiKit.argb;
import static de.rcm.ballistic.client.gui.UiKit.ease;
import static de.rcm.ballistic.client.gui.UiKit.easeOutBack;
import static de.rcm.ballistic.client.gui.UiKit.easeOutExpo;
import static de.rcm.ballistic.client.gui.UiKit.lerpArgb;
import static de.rcm.ballistic.client.gui.UiKit.lerpColor;

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
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The lock in front of the control panel: a clean, modern sign-in card. A frosted dark card with soft
 * rounded corners and a deep shadow floats over the blurred world, slow coloured lights drifting behind it;
 * a lock badge with a breathing glow, a short title, the password field as a pill where every character
 * pops in as a dot, and an unlock button. Everything eases in one after another. A wrong password shakes
 * the card like a spring and turns the field red; three in a row lock it for a few seconds with a draining
 * bar. The right one turns the badge green, springs the lock open, draws a check mark and lets the card
 * sink away into the menu. Once unlocked it stays open until the game is restarted.
 * <p>
 * The soft shapes (rounded corners, circles, glows) come from {@link UiKit}. Only the password's SHA-256 is kept.
 */
public class PasswordScreen extends Screen {
	private static final String HASH = "97db65274cf09892773eed0fb7751716d93f33ae995029961402d2e9b7a5d9df";
	private static final int W = 248;
	private static final int H = 206;
	private static final int FIELD_W = 196;
	private static final int FIELD_H = 26;
	// a quiet, neutral palette: near-black greys, off-white for what matters, colour only to say something
	private static final int CARD = 0x161618;
	private static final int FIELD = 0x0E0E10;
	private static final int TEXT = 0xEDEDEF;
	private static final int MUTED = 0x8E8E95;
	private static final int FAINT = 0x5E5E66;
	private static final int BUTTON = 0xE8E8EA;
	private static final int BUTTON_INK = 0x111113;
	private static final int ERROR = 0xE5484D;
	private static final int SUCCESS = 0x30A46C;
	private static final long OUT_TIME = 950L;


	private static boolean unlocked;

	private final Supplier<Screen> target;
	private final @Nullable Screen parent;
	private EditBox box;
	private long opened = -1L;
	private long deniedAt = -100_000L;
	/** When a wrong password was last entered (the message below the field). */
	private long wrongAt = -100_000L;
	private long grantedAt = -1L;
	private long lockedUntil;
	private long lockedFrom;
	private final long[] typedAt = new long[64];
	private int fails;
	private int lastLength;
	private float hover;
	private float focus;
	private long lastFrame;

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
		UiKit.init();
		String typed = this.box != null ? this.box.getValue() : "";
		this.box = new EditBox(this.font, this.width / 2, this.height / 2, FIELD_W, FIELD_H, Component.literal("Passwort"));
		this.box.setMaxLength(48);
		this.box.setValue(typed);
		this.box.setFilter(s -> !s.contains(" "));
		this.addWidget(this.box); // takes the keys; the field is drawn here
		this.setInitialFocus(this.box);
		if (this.opened < 0L) {
			this.opened = Util.getMillis();
			this.lastFrame = this.opened;
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
			this.submit();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && this.grantedAt < 0L && this.overButton(event.x(), event.y())) {
			this.submit();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void submit() {
		long now = Util.getMillis();
		if (now < this.lockedUntil) {
			return;
		}
		if (this.box.getValue().isEmpty()) {
			this.deniedAt = now; // a gentle shake: nothing typed yet
			return;
		}
		if (sha256(this.box.getValue().trim()).equals(HASH)) {
			this.grantedAt = now;
			unlocked = true;
			play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.5F, 0.9F);
			play(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.8F, 0.35F);
		} else {
			this.deniedAt = now;
			this.wrongAt = now;
			this.fails++;
			this.box.setValue("");
			this.lastLength = 0;
			play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.35F);
			if (this.fails >= 3) {
				this.fails = 0;
				this.lockedFrom = now;
				this.lockedUntil = now + 5000L;
			}
		}
	}

	@Override
	public void tick() {
		if (this.grantedAt >= 0L && Util.getMillis() - this.grantedAt > OUT_TIME) {
			this.minecraft.setScreen(this.target.get());
		}
	}

	// ------------------------------------------------------------------ layout

	private float cardX() {
		return (this.width - W) / 2.0F;
	}

	private float cardY() {
		return (this.height - H) / 2.0F;
	}

	private boolean overButton(double mx, double my) {
		float x = this.cardX() + (W - FIELD_W) / 2.0F;
		float y = this.cardY() + 150;
		return mx >= x && mx < x + FIELD_W && my >= y && my < y + 24;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		long now = Util.getMillis();
		float dt = Math.min(0.1F, (now - this.lastFrame) / 1000.0F);
		this.lastFrame = now;
		float t = (now - this.opened) / 1000.0F;
		float out = this.grantedAt < 0L ? 0.0F : Mth.clamp((now - this.grantedAt) / (float) OUT_TIME, 0.0F, 1.0F);
		boolean locked = now < this.lockedUntil;
		float err = (now - this.deniedAt) / 1000.0F;

		int length = this.box.getValue().length();
		if (length != this.lastLength) {
			if (length > this.lastLength) {
				for (int i = this.lastLength; i < length && i < this.typedAt.length; i++) {
					this.typedAt[i] = now;
				}
				play(SoundEvents.UI_BUTTON_CLICK.value(), 2.0F, 0.06F);
			}
			this.lastLength = length;
		}
		this.hover = approach(this.hover, this.overButton(mouseX, mouseY) && !locked && out <= 0.0F ? 1.0F : 0.0F, dt * 10.0F);
		this.focus = approach(this.focus, length > 0 ? 1.0F : 0.0F, dt * 6.0F);

		// ---- backdrop: dimmed, with soft lights drifting slowly behind the card
		float bgIn = ease(Mth.clamp(t / 0.35F, 0.0F, 1.0F)) * (1.0F - ease(Mth.clamp((out - 0.6F) / 0.4F, 0.0F, 1.0F)));
		g.fillGradient(0, 0, this.width, this.height, argb(0.74F * bgIn, 0x0A0A0B), argb(0.84F * bgIn, 0x050505));
		float cx = this.width * 0.5F;
		float cy = this.height * 0.5F;

		// ---- the card: rises in, springs when the password is wrong, sinks away when it is right
		float enter = easeOutExpo(Mth.clamp(t / 0.6F, 0.0F, 1.0F));
		float shake = err < 0.7F ? (float) (Math.exp(-err * 7.0) * Math.sin(err * 46.0) * 9.0) : 0.0F;
		float leave = ease(Mth.clamp((out - 0.45F) / 0.55F, 0.0F, 1.0F));
		float alpha = enter * (1.0F - leave);
		if (alpha <= 0.005F) {
			return;
		}
		float scale = (0.94F + 0.06F * enter) * (1.0F - 0.05F * leave);
		float lift = (1.0F - enter) * 18.0F + leave * 10.0F;
		float x0 = this.cardX();
		float y0 = this.cardY();

		g.pose().pushMatrix();
		g.pose().translate(cx + shake, cy + lift);
		g.pose().scale(scale, scale);
		g.pose().translate(-cx, -cy);

		// soft shadow, then the card with a hairline edge and a faint sheen along its top
		UiKit.orb(g, x0 - 24, y0 - 4, W + 48, H + 44, 0x000000, 0.5F * alpha);
		UiKit.round(g, UiKit.FILL, (int) x0, (int) y0, W, H, 12, argb(0.97F * alpha, CARD));
		UiKit.round(g, UiKit.EDGE, (int) x0, (int) y0, W, H, 12, argb(0.08F * alpha, 0xFFFFFF));

		int left = (int) x0;
		int top = (int) y0;
		boolean success = out > 0.0F;
		float successIn = ease(Mth.clamp(out / 0.3F, 0.0F, 1.0F));
		boolean showError = err < 2.4F && !success;

		// ---- lock badge: a plain dark circle with a hairline ring; the lock takes the colour of what happened
		float s1 = stagger(t, 0);
		int bx = left + W / 2;
		float by = top + 40 + (1.0F - s1) * 6.0F;
		float wrongFlash = showError && err < 0.8F && this.wrongAt == this.deniedAt ? 1.0F - err / 0.8F : 0.0F;
		int iconColor = success ? lerpColor(TEXT, SUCCESS, successIn) : lerpColor(TEXT, ERROR, wrongFlash);
		UiKit.disc(g, bx, by, 20.0F, argb(0.12F * alpha * s1, 0xFFFFFF));
		UiKit.disc(g, bx, by, 19.0F, argb(alpha * s1, 0x1D1D20));
		this.lockIcon(g, bx, Math.round(by), alpha * s1, iconColor, success ? ease(Mth.clamp(out / 0.35F, 0.0F, 1.0F)) : 0.0F);

		// ---- title and subtitle
		float s2 = stagger(t, 1);
		Component title = Component.literal(success ? "Entsperrt" : locked ? "Kurz gesperrt" : "Zugriff geschützt");
		this.centered(g, title, left + W / 2, top + 76 + (1.0F - s2) * 6.0F, argb(alpha * s2, TEXT));
		float s3 = stagger(t, 2);
		String sub = success ? "Willkommen zurück." : locked ? "Zu viele Versuche. Bitte warte kurz." : "Gib dein Passwort ein, um fortzufahren.";
		this.centered(g, Component.literal(sub), left + W / 2, top + 90 + (1.0F - s3) * 6.0F, argb(alpha * s3, MUTED));

		// ---- password field
		float s4 = stagger(t, 3);
		int fx = left + (W - FIELD_W) / 2;
		int fy = (int) (top + 110 + (1.0F - s4) * 6.0F);
		float sinceWrong = (now - this.wrongAt) / 1000.0F;
		float redness = locked ? 1.0F : sinceWrong < 2.4F && length == 0 ? 1.0F - sinceWrong / 2.4F : 0.0F;
		// the edge: faint at rest, brighter while typing, red after a wrong password, green when it was right
		int edge = success ? argb(0.8F * alpha * s4, SUCCESS)
			: redness > 0.0F ? lerpArgb(argb((0.1F + 0.2F * this.focus) * alpha * s4, 0xFFFFFF), argb(0.85F * alpha * s4, ERROR), redness)
			: argb((0.1F + 0.22F * this.focus) * alpha * s4, 0xFFFFFF);
		UiKit.round(g, UiKit.FILL, fx, fy, FIELD_W, FIELD_H, 8, argb(alpha * s4, FIELD));
		UiKit.round(g, UiKit.EDGE, fx, fy, FIELD_W, FIELD_H, 8, edge);
		int n = this.box.getValue().length();
		int midY = fy + FIELD_H / 2;
		if (n == 0 && !success) {
			g.drawString(this.font, "Passwort", fx + 12, midY - 4, argb(alpha * s4, FAINT), false);
		}
		int spacing = 11;
		int maxDots = (FIELD_W - 28) / spacing;
		int shown = Math.min(n, maxDots);
		int start = fx + 12 + 3;
		for (int i = 0; i < shown; i++) {
			int idx = n - shown + i;
			float pop = Mth.clamp((now - this.typedAt[Math.min(idx, this.typedAt.length - 1)]) / 260.0F, 0.0F, 1.0F);
			float k = easeOutBack(pop);
			float r = 2.8F * k;
			int dot = success ? lerpColor(TEXT, SUCCESS, successIn) : TEXT;
			UiKit.disc(g, start + i * spacing, midY, Math.max(0.1F, r), argb(alpha * s4 * Math.min(1.0F, pop * 2.0F), dot));
		}
		if (!success && !locked) {
			float caretOn = 0.5F + 0.5F * Mth.cos((now % 1100L) / 1100.0F * Mth.TWO_PI);
			int caretX = n == 0 ? fx + 11 : start + (shown - 1) * spacing + 7;
			g.fill(caretX, midY - 5, caretX + 1, midY + 5, argb(caretOn * 0.8F * alpha * s4, TEXT));
		}
		// below the field: the error, or the lock-out draining away
		if (locked) {
			float left01 = (this.lockedUntil - now) / (float) (this.lockedUntil - this.lockedFrom);
			int barW = (int) (FIELD_W * Mth.clamp(left01, 0.0F, 1.0F));
			g.fill(fx, fy + FIELD_H + 7, fx + FIELD_W, fy + FIELD_H + 8, argb(0.08F * alpha, 0xFFFFFF));
			g.fill(fx, fy + FIELD_H + 7, fx + barW, fy + FIELD_H + 8, argb(0.8F * alpha, ERROR));
		} else if (!success && n == 0 && now - this.wrongAt < 2400L) {
			float since = (now - this.wrongAt) / 1000.0F;
			float msg = ease(Mth.clamp(since / 0.25F, 0.0F, 1.0F)) * (1.0F - ease(Mth.clamp((since - 1.8F) / 0.6F, 0.0F, 1.0F)));
			this.centered(g, Component.literal("Falsches Passwort"), left + W / 2, fy + FIELD_H + 4 - (1.0F - msg) * 3.0F, argb(alpha * msg, ERROR));
		}

		// ---- the unlock button
		float s5 = stagger(t, 4);
		int btnY = (int) (top + 150 + (1.0F - s5) * 6.0F);
		// an off-white button, a touch brighter under the mouse; grey while locked, green once unlocked
		int btnColor = success ? lerpColor(BUTTON, SUCCESS, successIn) : locked ? 0x2A2A2E : lerpColor(BUTTON, 0xFFFFFF, this.hover);
		UiKit.round(g, UiKit.FILL, fx, btnY, FIELD_W, 24, 8, argb(alpha * s5, btnColor));
		if (success) {
			this.checkMark(g, fx + FIELD_W / 2, btnY + 12, alpha, ease(Mth.clamp((out - 0.08F) / 0.32F, 0.0F, 1.0F)));
		} else {
			Component label = Component.literal(locked ? "Warte " + ((this.lockedUntil - now) / 1000L + 1L) + " s" : "Entsperren");
			this.centered(g, label, fx + FIELD_W / 2, btnY + 8, argb(alpha * s5, locked ? MUTED : BUTTON_INK));
		}

		// ---- footer
		float s6 = stagger(t, 5);
		this.centered(g, Component.literal("Enter zum Entsperren  ·  Esc zum Abbrechen"), left + W / 2, top + H - 18 + (1.0F - s6) * 4.0F,
			argb(alpha * s6, FAINT));
		g.pose().popMatrix();
	}

	/** Each element eases in a little after the one above it. */
	private static float stagger(float t, int index) {
		return easeOutExpo(Mth.clamp((t - 0.12F - index * 0.06F) / 0.5F, 0.0F, 1.0F));
	}

	private void centered(GuiGraphics g, Component text, int x, float y, int color) {
		if ((color >>> 24) < 5) {
			return;
		}
		g.drawString(this.font, text, x - this.font.width(text) / 2, Math.round(y), color, false);
	}

	/** The padlock: a body with a keyhole and a shackle that lifts and swings open when unlocked. */
	private void lockIcon(GuiGraphics g, int x, int y, float alpha, int color, float open) {
		int white = argb(alpha, color);
		int dark = argb(alpha, 0x1D1D20);
		// shackle: an arc of short segments over two legs; opening lifts it and the right leg comes free
		float lift = open * 4.0F;
		float r = 4.5F;
		float sy = y - 3 - lift;
		for (int i = 0; i <= 12; i++) {
			float a = Mth.PI * i / 12.0F;
			g.pose().pushMatrix();
			g.pose().translate(x + Mth.cos(a) * r, sy - Mth.sin(a) * r);
			g.pose().rotate(-a + Mth.HALF_PI);
			g.fill(-1, -1, 1, 1, white);
			g.pose().popMatrix();
		}
		g.fill(Math.round(x - r - 1), Math.round(sy), Math.round(x - r + 1), y + 1, white);
		g.fill(Math.round(x + r - 1), Math.round(sy), Math.round(x + r + 1), Math.round(sy + 3 + (1.0F - open) * 1.0F), white);
		// body and keyhole
		UiKit.round(g, UiKit.FILL, x - 7, y - 1, 14, 11, 3, white);
		UiKit.disc(g, x, y + 3.5F, 1.6F, dark);
		g.fill(x, y + 4, x + 1, y + 7, dark);
	}

	/** A check mark drawing itself, short stroke first. */
	private void checkMark(GuiGraphics g, int x, int y, float alpha, float p) {
		int white = argb(alpha, 0xFFFFFF);
		float first = Mth.clamp(p / 0.4F, 0.0F, 1.0F);
		float second = Mth.clamp((p - 0.4F) / 0.6F, 0.0F, 1.0F);
		this.stroke(g, x - 6, y, x - 2, y + 4, first, white);
		if (second > 0.0F) {
			this.stroke(g, x - 2, y + 4, x + 7, y - 5, second, white);
		}
	}

	private void stroke(GuiGraphics g, float ax, float ay, float bx, float by, float p, int color) {
		float dx = (bx - ax) * p;
		float dy = (by - ay) * p;
		float len = Mth.sqrt(dx * dx + dy * dy);
		if (len < 0.1F) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(ax, ay);
		g.pose().rotate((float) Math.atan2(dy, dx));
		g.fill(0, -1, Math.max(1, Math.round(len)), 1, color);
		g.pose().popMatrix();
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
