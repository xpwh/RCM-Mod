package de.rcm.ballistic.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import de.rcm.ballistic.BallisticMissiles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * The soft shapes (rounded corners, circles, glows) come from small textures drawn here at start-up and
 * scaled with smooth filtering, so the edges stay clean at any GUI scale. Only the password's SHA-256 is kept.
 */
public class PasswordScreen extends Screen {
	private static final String HASH = "97db65274cf09892773eed0fb7751716d93f33ae995029961402d2e9b7a5d9df";
	private static final int W = 248;
	private static final int H = 206;
	private static final int FIELD_W = 196;
	private static final int FIELD_H = 26;
	private static final int ACCENT_A = 0x6366F1; // indigo
	private static final int ACCENT_B = 0x8B5CF6; // violet
	private static final int ERROR = 0xF43F5E;
	private static final int SUCCESS = 0x22C55E;
	private static final long OUT_TIME = 950L;

	private static final Identifier ORB = BallisticMissiles.id("dynamic/ui_orb");
	private static final Identifier FILL = BallisticMissiles.id("dynamic/ui_round_fill");
	private static final Identifier EDGE = BallisticMissiles.id("dynamic/ui_round_edge");
	private static final Identifier DISC = BallisticMissiles.id("dynamic/ui_disc");
	/** Rounded-rectangle textures: 64 px with a 24 px corner radius (nine-sliced), the edge 2 px wide. */
	private static final int TEX = 64;
	private static final int CORNER = 24;
	private static boolean texturesMade;

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
		makeTextures();
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
		this.focus = approach(this.focus, length > 0 || this.box.isFocused() ? 1.0F : 0.0F, dt * 6.0F);

		// ---- backdrop: dimmed, with soft lights drifting slowly behind the card
		float bgIn = ease(Mth.clamp(t / 0.35F, 0.0F, 1.0F)) * (1.0F - ease(Mth.clamp((out - 0.6F) / 0.4F, 0.0F, 1.0F)));
		g.fillGradient(0, 0, this.width, this.height, argb(0.72F * bgIn, 0x0B0D14), argb(0.86F * bgIn, 0x050608));
		float cx = this.width * 0.5F;
		float cy = this.height * 0.5F;
		this.orb(g, cx + Mth.sin(t * 0.21F) * 170.0F - 60.0F, cy + Mth.cos(t * 0.17F) * 90.0F - 40.0F, 260.0F, ACCENT_A, 0.22F * bgIn);
		this.orb(g, cx + Mth.cos(t * 0.13F + 1.0F) * 190.0F + 80.0F, cy + Mth.sin(t * 0.19F + 2.0F) * 80.0F + 50.0F, 300.0F, ACCENT_B, 0.17F * bgIn);
		this.orb(g, cx + Mth.sin(t * 0.11F + 4.0F) * 220.0F, cy + Mth.cos(t * 0.23F + 3.0F) * 120.0F, 220.0F, 0x06B6D4, 0.1F * bgIn);

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
		this.orbStretched(g, x0 - 30, y0 - 10, W + 60, H + 60, 0x000000, 0.65F * alpha);
		this.round(g, FILL, (int) x0, (int) y0, W, H, 14, argb(0.92F * alpha, 0x12141C));
		this.round(g, EDGE, (int) x0, (int) y0, W, H, 14, argb(0.14F * alpha, 0xFFFFFF));
		g.fillGradient((int) x0 + 14, (int) y0 + 1, (int) x0 + W - 14, (int) y0 + 2, argb(0.18F * alpha, 0xFFFFFF), argb(0.0F, 0xFFFFFF));

		int left = (int) x0;
		int top = (int) y0;
		boolean success = out > 0.0F;
		float successIn = ease(Mth.clamp(out / 0.3F, 0.0F, 1.0F));
		boolean showError = err < 2.4F && !success;

		// ---- lock badge with a breathing glow
		float s1 = stagger(t, 0);
		int bx = left + W / 2;
		int by = top + 40;
		int badgeColor = success ? lerpColor(ACCENT_A, SUCCESS, successIn) : showError && err < 0.6F ? lerpColor(ERROR, ACCENT_A, err / 0.6F) : ACCENT_A;
		float breathe = 0.5F + 0.5F * Mth.sin(t * 2.2F);
		this.orb(g, bx, by, 90.0F + 10.0F * breathe, badgeColor, (0.28F + 0.12F * breathe) * alpha * s1);
		this.disc(g, bx, by + (1.0F - s1) * 6.0F, 21.0F, argb(alpha * s1, badgeColor));
		this.disc(g, bx, by - 4 + (1.0F - s1) * 6.0F, 17.0F, argb(0.18F * alpha * s1, 0xFFFFFF));
		this.lockIcon(g, bx, (int) (by + (1.0F - s1) * 6.0F), alpha * s1, success ? ease(Mth.clamp(out / 0.35F, 0.0F, 1.0F)) : 0.0F);

		// ---- title and subtitle
		float s2 = stagger(t, 1);
		Component title = Component.literal(success ? "Entsperrt" : locked ? "Kurz gesperrt" : "Zugriff geschützt").withStyle(ChatFormatting.BOLD);
		this.centered(g, title, left + W / 2, top + 76 + (1.0F - s2) * 6.0F, argb(alpha * s2, 0xF4F4F6));
		float s3 = stagger(t, 2);
		String sub = success ? "Willkommen zurück." : locked ? "Zu viele Versuche. Bitte warte kurz." : "Gib dein Passwort ein, um fortzufahren.";
		this.centered(g, Component.literal(sub), left + W / 2, top + 90 + (1.0F - s3) * 6.0F, argb(0.55F * alpha * s3, 0xC9CCD6));

		// ---- password field
		float s4 = stagger(t, 3);
		int fx = left + (W - FIELD_W) / 2;
		int fy = (int) (top + 110 + (1.0F - s4) * 6.0F);
		float sinceWrong = (now - this.wrongAt) / 1000.0F;
		float redness = locked ? 1.0F : sinceWrong < 2.4F && length == 0 ? 1.0F - sinceWrong / 2.4F : 0.0F;
		int ring = success ? SUCCESS : lerpColor(ACCENT_A, ERROR, redness);
		float ringAlpha = Math.max(this.focus * 0.9F, redness);
		if (ringAlpha > 0.01F) {
			this.orbStretched(g, fx - 14, fy - 12, FIELD_W + 28, FIELD_H + 24, ring, 0.22F * ringAlpha * alpha * s4);
		}
		this.round(g, FILL, fx, fy, FIELD_W, FIELD_H, 13, argb(alpha * s4, 0x0B0C12));
		this.round(g, EDGE, fx, fy, FIELD_W, FIELD_H, 13, lerpArgb(argb(0.16F * alpha * s4, 0xFFFFFF), argb(alpha * s4, ring), ringAlpha));
		int n = this.box.getValue().length();
		int midY = fy + FIELD_H / 2;
		if (n == 0 && !success) {
			g.drawString(this.font, "Passwort", fx + 14, midY - 4, argb(0.35F * alpha * s4, 0xC9CCD6), false);
		}
		int spacing = 11;
		int maxDots = (FIELD_W - 28) / spacing;
		int shown = Math.min(n, maxDots);
		int start = fx + 14 + 3;
		for (int i = 0; i < shown; i++) {
			int idx = n - shown + i;
			float pop = Mth.clamp((now - this.typedAt[Math.min(idx, this.typedAt.length - 1)]) / 260.0F, 0.0F, 1.0F);
			float k = easeOutBack(pop);
			float r = 3.2F * k;
			int dot = success ? lerpColor(0xF4F4F6, SUCCESS, successIn) : 0xF4F4F6;
			this.disc(g, start + i * spacing, midY, Math.max(0.1F, r), argb(alpha * s4 * Math.min(1.0F, pop * 2.0F), dot));
		}
		if (!success && !locked) {
			float caretOn = 0.5F + 0.5F * Mth.cos((now % 1100L) / 1100.0F * Mth.TWO_PI);
			int caretX = n == 0 ? fx + 13 : start + (shown - 1) * spacing + 7;
			g.fill(caretX, midY - 6, caretX + 1, midY + 6, argb(caretOn * 0.85F * alpha * s4, ACCENT_A | 0x303030));
		}
		// below the field: the error, or the lock-out draining away
		if (locked) {
			float left01 = (this.lockedUntil - now) / (float) (this.lockedUntil - this.lockedFrom);
			int barW = (int) (FIELD_W * Mth.clamp(left01, 0.0F, 1.0F));
			g.fill(fx, fy + FIELD_H + 6, fx + FIELD_W, fy + FIELD_H + 8, argb(0.12F * alpha, 0xFFFFFF));
			g.fill(fx, fy + FIELD_H + 6, fx + barW, fy + FIELD_H + 8, argb(0.9F * alpha, ERROR));
		} else if (!success && n == 0 && now - this.wrongAt < 2400L) {
			float since = (now - this.wrongAt) / 1000.0F;
			float msg = ease(Mth.clamp(since / 0.25F, 0.0F, 1.0F)) * (1.0F - ease(Mth.clamp((since - 1.8F) / 0.6F, 0.0F, 1.0F)));
			this.centered(g, Component.literal("Falsches Passwort"), left + W / 2, fy + FIELD_H + 4 - (1.0F - msg) * 3.0F, argb(0.9F * alpha * msg, ERROR));
		}

		// ---- the unlock button
		float s5 = stagger(t, 4);
		int btnY = (int) (top + 150 + (1.0F - s5) * 6.0F);
		int btnColor = success ? lerpColor(ACCENT_A, SUCCESS, successIn) : locked ? 0x3A3D4A : lerpColor(ACCENT_A, ACCENT_B, this.hover * 0.5F);
		float press = this.hover * 0.04F;
		this.orbStretched(g, fx - 10, btnY - 6, FIELD_W + 20, 36, btnColor, (0.18F + 0.22F * this.hover) * alpha * s5);
		this.round(g, FILL, fx, btnY, FIELD_W, 24, 12, argb(alpha * s5, btnColor));
		g.fillGradient(fx + 12, btnY + 1, fx + FIELD_W - 12, btnY + 12, argb((0.16F + press) * alpha * s5, 0xFFFFFF), argb(0.0F, 0xFFFFFF));
		if (success) {
			this.checkMark(g, fx + FIELD_W / 2, btnY + 12, alpha, ease(Mth.clamp((out - 0.08F) / 0.32F, 0.0F, 1.0F)));
		} else {
			Component label = Component.literal(locked ? ((this.lockedUntil - now) / 1000L + 1L) + " s" : "Entsperren").withStyle(ChatFormatting.BOLD);
			this.centered(g, label, fx + FIELD_W / 2, btnY + 8, argb(alpha * s5 * (locked ? 0.6F : 1.0F), 0xFFFFFF));
		}

		// ---- footer
		float s6 = stagger(t, 5);
		this.centered(g, Component.literal("Enter zum Entsperren  ·  Esc zum Abbrechen"), left + W / 2, top + H - 18 + (1.0F - s6) * 4.0F,
			argb(0.32F * alpha * s6, 0xC9CCD6));
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
	private void lockIcon(GuiGraphics g, int x, int y, float alpha, float open) {
		int white = argb(alpha, 0xFFFFFF);
		int dark = argb(alpha * 0.9F, 0x2A2A40);
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
		this.round(g, FILL, x - 7, y - 1, 14, 11, 3, white);
		this.disc(g, x, y + 3.5F, 1.6F, dark);
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

	// ------------------------------------------------------------------ soft shapes from the textures

	/** A soft round light (radial falloff), centred on (x, y). */
	private void orb(GuiGraphics g, float x, float y, float size, int rgb, float alpha) {
		this.orbStretched(g, x - size / 2.0F, y - size / 2.0F, size, size, rgb, alpha);
	}

	private void orbStretched(GuiGraphics g, float x, float y, float w, float h, int rgb, float alpha) {
		if (alpha <= 0.003F) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / 128.0F, h / 128.0F);
		g.blit(RenderPipelines.GUI_TEXTURED, ORB, 0, 0, 0.0F, 0.0F, 128, 128, 128, 128, 128, 128, argb(alpha, rgb));
		g.pose().popMatrix();
	}

	/** A filled circle with a clean edge. */
	private void disc(GuiGraphics g, float x, float y, float radius, int argb) {
		if ((argb >>> 24) == 0) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x - radius, y - radius);
		g.pose().scale(radius * 2.0F / 128.0F, radius * 2.0F / 128.0F);
		g.blit(RenderPipelines.GUI_TEXTURED, DISC, 0, 0, 0.0F, 0.0F, 128, 128, 128, 128, 128, 128, argb);
		g.pose().popMatrix();
	}

	/** A rounded rectangle (filled, or just its edge), nine-sliced from a texture so the corners stay smooth. */
	private void round(GuiGraphics g, Identifier tex, int x, int y, int w, int h, int r, int argb) {
		if ((argb >>> 24) == 0) {
			return;
		}
		r = Math.min(r, Math.min(w, h) / 2);
		int c = CORNER;
		int m = TEX - 2 * c;
		int far = TEX - c;
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, r, r, c, c, TEX, TEX, argb);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x + w - r, y, far, 0, r, r, c, c, TEX, TEX, argb);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y + h - r, 0, far, r, r, c, c, TEX, TEX, argb);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x + w - r, y + h - r, far, far, r, r, c, c, TEX, TEX, argb);
		if (w > 2 * r) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x + r, y, c, 0, w - 2 * r, r, m, c, TEX, TEX, argb);
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x + r, y + h - r, c, far, w - 2 * r, r, m, c, TEX, TEX, argb);
		}
		if (h > 2 * r) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y + r, 0, c, r, h - 2 * r, c, m, TEX, TEX, argb);
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x + w - r, y + r, far, c, r, h - 2 * r, c, m, TEX, TEX, argb);
		}
		if (w > 2 * r && h > 2 * r) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x + r, y + r, c, c, w - 2 * r, h - 2 * r, m, m, TEX, TEX, argb);
		}
	}

	/** Draws the soft shapes once: a radial glow, a disc, and a rounded square filled and as an outline. */
	private static void makeTextures() {
		if (texturesMade) {
			return;
		}
		texturesMade = true;
		var manager = Minecraft.getInstance().getTextureManager();
		manager.register(ORB, smooth("ui orb", 128, (px, py) -> {
			double d = Math.hypot(px - 64.0, py - 64.0) / 64.0;
			double f = Math.max(0.0, 1.0 - d);
			return f * f * (3.0 - 2.0 * f) * f;
		}));
		manager.register(DISC, smooth("ui disc", 128, (px, py) -> coverage(62.0 - Math.hypot(px - 64.0, py - 64.0))));
		manager.register(FILL, smooth("ui round fill", TEX, (px, py) -> coverage(-roundedBox(px, py))));
		manager.register(EDGE, smooth("ui round edge", TEX, (px, py) -> {
			double d = roundedBox(px, py);
			return coverage(-d) - coverage(-(d + 2.2));
		}));
	}

	/** Signed distance (texture pixels) to the rounded square filling the texture, negative inside. */
	private static double roundedBox(double px, double py) {
		double half = TEX / 2.0;
		double qx = Math.abs(px - half) - (half - CORNER);
		double qy = Math.abs(py - half) - (half - CORNER);
		double outside = Math.hypot(Math.max(qx, 0.0), Math.max(qy, 0.0));
		return outside + Math.min(Math.max(qx, qy), 0.0) - CORNER;
	}

	private static double coverage(double inside) {
		return Mth.clamp(inside + 0.5, 0.0, 1.0);
	}

	private interface Shape {
		double at(double x, double y);
	}

	/** A white texture whose alpha is the shape, supersampled 4x4, with smooth (linear) filtering. */
	private static DynamicTexture smooth(String name, int size, Shape shape) {
		NativeImage image = new NativeImage(size, size, false);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				double sum = 0.0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						sum += shape.at(x + (sx + 0.5) / 4.0, y + (sy + 0.5) / 4.0);
					}
				}
				int a = (int) Math.round(Mth.clamp(sum / 16.0, 0.0, 1.0) * 255.0);
				image.setPixel(x, y, a << 24 | 0xFFFFFF);
			}
		}
		return new SmoothTexture(name, image);
	}

	private static final class SmoothTexture extends DynamicTexture {
		SmoothTexture(String name, NativeImage image) {
			super(() -> name, image);
			this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
		}
	}

	// ------------------------------------------------------------------ helpers

	private static float approach(float value, float target, float rate) {
		return value + (target - value) * Math.min(1.0F, rate);
	}

	private static float ease(float x) {
		return x * x * (3.0F - 2.0F * x);
	}

	private static float easeOutExpo(float x) {
		return x >= 1.0F ? 1.0F : 1.0F - (float) Math.pow(2.0, -10.0 * x);
	}

	private static float easeOutBack(float x) {
		float c1 = 1.9F;
		float k = x - 1.0F;
		return 1.0F + (c1 + 1.0F) * k * k * k + c1 * k * k;
	}

	private static int argb(float alpha, int rgb) {
		return Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24 | rgb & 0xFFFFFF;
	}

	private static int lerpColor(int a, int b, float f) {
		f = Mth.clamp(f, 0.0F, 1.0F);
		int r = Math.round(Mth.lerp(f, a >> 16 & 255, b >> 16 & 255));
		int gr = Math.round(Mth.lerp(f, a >> 8 & 255, b >> 8 & 255));
		int bl = Math.round(Mth.lerp(f, a & 255, b & 255));
		return r << 16 | gr << 8 | bl;
	}

	private static int lerpArgb(int a, int b, float f) {
		f = Mth.clamp(f, 0.0F, 1.0F);
		int al = Math.round(Mth.lerp(f, a >>> 24, b >>> 24));
		return al << 24 | lerpColor(a, b, f);
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
