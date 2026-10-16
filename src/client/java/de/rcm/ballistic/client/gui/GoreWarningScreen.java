package de.rcm.ballistic.client.gui;

import static de.rcm.ballistic.client.gui.UiKit.approach;
import static de.rcm.ballistic.client.gui.UiKit.argb;
import static de.rcm.ballistic.client.gui.UiKit.ease;
import static de.rcm.ballistic.client.gui.UiKit.easeOutBack;
import static de.rcm.ballistic.client.gui.UiKit.easeOutExpo;
import static de.rcm.ballistic.client.gui.UiKit.lerpColor;

import de.rcm.ballistic.client.ModConfig;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * Shown at the start, before the title screen, and meant seriously: this mod shows extreme, realistic violence and
 * is for adults only. A quiet dark card - a red 18+ badge, the warning, the note that the mod is an early alpha, two
 * check boxes and two buttons - eases in piece by piece. It cannot be passed before it has been up long enough to
 * be read (the button fills as the time runs) and read to the end (it scrolls when the screen is small), and only
 * by someone who confirms they are at least 18; pressing on too early shakes the button and points at what is
 * missing. Anyone else turns the gore off. Ticked, it is not shown again (both can be changed in the settings).
 */
public final class GoreWarningScreen extends Screen {
	/** How long it must stay up before it can be passed: long enough to actually be read. */
	private static final long READ_MS = 8000L;
	private static final long OUT_MS = 520L;
	private static final int PAD = 22;
	private static final int LINE = 11;
	private static final int ROW_H = 20;
	private static final int BUTTON_H = 26;
	// the palette of the mod's other cards: near-black greys, off-white for what matters, colour only to say something
	private static final int BACKDROP = 0x0A0506;
	private static final int CARD = 0x1A1213;
	private static final int TEXT = 0xEDEDEF;
	private static final int BODY = 0xB4B4BB;
	private static final int MUTED = 0x8E8E95;
	private static final int FAINT = 0x5E5E66;
	private static final int HAIRLINE = 0x26262A;
	private static final int BUTTON = 0xE8E8EA;
	private static final int BUTTON_INK = 0x111113;
	private static final int DISABLED = 0x232326;
	private static final int RED = 0xE5484D;
	private static final int DEEP_RED = 0x8E1B1F;
	private static final int AMBER = 0xF5A524;

	private static boolean shown;
	private final Screen next;
	private long opened = -1L;
	private long lastFrame;
	private boolean adult;
	private boolean dontShow;
	private long adultAt = -100_000L;
	private long dontShowAt = -100_000L;
	/** Pressed on before it may be: when, for the shake and the hint. */
	private long refusedAt = -100_000L;
	private long closingAt = -1L;
	private float scroll;
	private float scrollTarget;
	private boolean readToEnd;
	private float hoverGo;
	private float hoverOff;
	private float hoverAdult;
	private float hoverDont;
	private float ready;
	/** The two warning tones as it comes up. */
	private int tones;
	private List<FormattedCharSequence> body = List.of();
	private List<FormattedCharSequence> alpha = List.of();
	private static final List<Gui3D.Part> SIGN = Gui3D.warningSign();
	private static final List<Gui3D.Part> COIN = Gui3D.coin();
	/** Blood running off the bottom edge of the big sign: where along it (model x) and when each drop formed. */
	private final List<float[]> drips = new java.util.ArrayList<>();
	private float nextDrip = 1.4F;

	private GoreWarningScreen(Screen next) {
		super(Component.translatable("gore_warning.ballisticmissiles.title"));
		this.next = next;
	}

	/** Puts the warning up the first time the title screen appears (unless it was turned off), then the update note. */
	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!shown && mc.screen instanceof TitleScreen title) {
				shown = true;
				// then the note on how updates come, unless that was turned off too
				Screen after = ModConfig.updateNotice ? new UpdateNoticeScreen(title) : title;
				if (ModConfig.goreWarning) {
					mc.setScreen(new GoreWarningScreen(after));
				} else if (after != title) {
					mc.setScreen(after);
				}
			}
		});
	}

	@Override
	protected void init() {
		UiKit.init();
		int textW = this.cardW() - 2 * PAD;
		this.body = this.font.split(Component.translatable("gore_warning.ballisticmissiles.text"), textW);
		this.alpha = this.font.split(Component.translatable("gore_warning.ballisticmissiles.alpha"), textW - 24);
		if (this.opened < 0L) {
			this.opened = Util.getMillis();
			this.lastFrame = this.opened;
		}
	}

	// ------------------------------------------------------------------ layout

	private int cardW() {
		return Math.min(440, this.width - 32);
	}

	/** Height of what scrolls: the warning, a gap, the alpha note in its box. */
	private int contentH() {
		return this.body.size() * LINE + 14 + this.alphaBoxH();
	}

	private int alphaBoxH() {
		return 26 + this.alpha.size() * LINE + 10;
	}

	/** The fixed parts round the scrolling text: header, the gap under it, the check boxes and the buttons. */
	private static final int CHROME = PAD + 58 + 12 + 2 * ROW_H + 4 + 14 + BUTTON_H + PAD;

	private int cardH() {
		return Math.min(this.height - 24, CHROME + this.contentH());
	}

	private int viewH() {
		return Math.max(20, this.cardH() - CHROME);
	}

	private int maxScroll() {
		return Math.max(0, this.contentH() - this.viewH());
	}

	private int cardX() {
		return (this.width - this.cardW()) / 2;
	}

	private int cardY() {
		return (this.height - this.cardH()) / 2;
	}

	private int viewTop() {
		return this.cardY() + PAD + 58;
	}

	private int rowsTop() {
		return this.viewTop() + this.viewH() + 12;
	}

	private int buttonsTop() {
		return this.rowsTop() + 2 * ROW_H + 4 + 14;
	}

	private int offW() {
		return Math.round((this.cardW() - 2 * PAD) * 0.4F);
	}

	private boolean over(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private boolean overAdult(double mx, double my) {
		return this.over(mx, my, this.cardX() + PAD - 4, this.rowsTop(), this.cardW() - 2 * PAD + 8, ROW_H);
	}

	private boolean overDontShow(double mx, double my) {
		return this.over(mx, my, this.cardX() + PAD - 4, this.rowsTop() + ROW_H + 4, this.cardW() - 2 * PAD + 8, ROW_H);
	}

	private boolean overOff(double mx, double my) {
		return this.over(mx, my, this.cardX() + PAD, this.buttonsTop(), this.offW(), BUTTON_H);
	}

	private boolean overGo(double mx, double my) {
		int x = this.cardX() + PAD + this.offW() + 8;
		return this.over(mx, my, x, this.buttonsTop(), this.cardW() - 2 * PAD - this.offW() - 8, BUTTON_H);
	}

	// ------------------------------------------------------------------ state

	private float readProgress(long now) {
		return Mth.clamp((now - this.opened) / (float) READ_MS, 0.0F, 1.0F);
	}

	private boolean mayGo(long now) {
		return this.readProgress(now) >= 1.0F && this.readToEnd && this.adult;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || this.closingAt >= 0L) {
			return true;
		}
		double mx = event.x();
		double my = event.y();
		long now = Util.getMillis();
		if (this.overAdult(mx, my)) {
			this.adult = !this.adult;
			this.adultAt = now;
			click(this.adult ? 1.6F : 1.3F);
		} else if (this.overDontShow(mx, my)) {
			this.dontShow = !this.dontShow;
			this.dontShowAt = now;
			click(this.dontShow ? 1.6F : 1.3F);
		} else if (this.overOff(mx, my)) {
			this.close(false);
		} else if (this.overGo(mx, my)) {
			this.tryGo(now);
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		this.scrollTarget = Mth.clamp(this.scrollTarget - (float) scrollY * LINE * 3, 0.0F, this.maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.closingAt < 0L && event.isConfirmation()) {
			this.tryGo(Util.getMillis());
			return true;
		}
		return super.keyPressed(event);
	}

	private void tryGo(long now) {
		if (this.mayGo(now)) {
			this.close(true);
		} else {
			this.refusedAt = now;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.3F));
		}
	}

	private static void click(float pitch) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.25F));
	}

	private void close(boolean gore) {
		this.closingAt = Util.getMillis();
		ModConfig.gore = gore;
		if (!gore) {
			ModConfig.blood = false;
		}
		if (this.dontShow) {
			ModConfig.goreWarning = false;
		}
		ModConfig.save();
		if (gore) {
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 0.7F));
		}
	}

	@Override
	public void tick() {
		if (this.closingAt >= 0L && Util.getMillis() - this.closingAt > OUT_MS) {
			Minecraft.getInstance().setScreen(this.next);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// drawn in render()
	}

	// ------------------------------------------------------------------ drawing

	/** Each part eases in a little after the one above it. */
	private static float stagger(float t, int index) {
		return easeOutExpo(Mth.clamp((t - 0.15F - index * 0.07F) / 0.55F, 0.0F, 1.0F));
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		long now = Util.getMillis();
		float dt = Math.min(0.1F, (now - this.lastFrame) / 1000.0F);
		this.lastFrame = now;
		float t = (now - this.opened) / 1000.0F;
		float out = this.closingAt < 0L ? 0.0F : Mth.clamp((now - this.closingAt) / (float) OUT_MS, 0.0F, 1.0F);
		boolean live = out <= 0.0F;

		this.scroll = approach(this.scroll, this.scrollTarget, dt * 14.0F);
		if (this.maxScroll() <= 0 || this.scrollTarget >= this.maxScroll() - 2) {
			this.readToEnd = true;
		}
		boolean may = this.mayGo(now);
		this.ready = approach(this.ready, may ? 1.0F : 0.0F, dt * 8.0F);
		this.hoverGo = approach(this.hoverGo, live && this.overGo(mouseX, mouseY) ? 1.0F : 0.0F, dt * 12.0F);
		this.hoverOff = approach(this.hoverOff, live && this.overOff(mouseX, mouseY) ? 1.0F : 0.0F, dt * 12.0F);
		this.hoverAdult = approach(this.hoverAdult, live && this.overAdult(mouseX, mouseY) ? 1.0F : 0.0F, dt * 12.0F);
		this.hoverDont = approach(this.hoverDont, live && this.overDontShow(mouseX, mouseY) ? 1.0F : 0.0F, dt * 12.0F);

		// two warning tones as it comes up
		if (this.tones < 2 && t > 0.12F + this.tones * 0.32F) {
			this.tones++;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT.value(), 0.62F, 0.45F));
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6F, 0.5F));
		}

		// ---- the backdrop: near black, a red glow throbbing behind the card, hazard stripes along top and bottom
		float bg = ease(Mth.clamp(t / 0.4F, 0.0F, 1.0F)) * (1.0F - ease(Mth.clamp((out - 0.35F) / 0.65F, 0.0F, 1.0F)));
		g.fill(0, 0, this.width, this.height, argb(bg, BACKDROP));
		float throb = 0.5F + 0.5F * Mth.sin(t * 3.4F);
		UiKit.orb(g, this.width * 0.5F - this.width * 0.6F, this.height * 0.5F - this.height * 0.75F, this.width * 1.2F, this.height * 1.5F, DEEP_RED,
			bg * (0.35F + 0.2F * throb));
		float band = easeOutExpo(Mth.clamp((t - 0.1F) / 0.6F, 0.0F, 1.0F)) * bg;
		this.hazard(g, 0, 0, this.width, 7, t, band);
		this.hazard(g, 0, this.height - 7, this.width, 7, -t, band);
		// a red flash as it slams up
		float flash = t < 0.45F ? 1.0F - t / 0.45F : 0.0F;
		if (flash > 0.0F) {
			g.fill(0, 0, this.width, this.height, argb(0.35F * flash * flash, RED));
		}

		float enter = easeOutExpo(Mth.clamp(t / 0.7F, 0.0F, 1.0F));
		float leave = ease(Mth.clamp(out / 0.8F, 0.0F, 1.0F));
		float a = enter * (1.0F - leave);
		if (a <= 0.005F) {
			return;
		}
		this.hero(g, t, 1.0F - leave, throb);
		int lift = Math.round((1.0F - enter) * 16.0F + leave * 10.0F);
		int x0 = this.cardX();
		int y0 = this.cardY() + lift;
		int cw = this.cardW();
		int ch = this.cardH();
		int textW = cw - 2 * PAD;
		// the card slams in with a jolt
		float jolt = t < 0.5F ? (float) (Math.exp(-t * 9.0) * Math.sin(t * 60.0) * 5.0) : 0.0F;
		x0 += Math.round(jolt);
		UiKit.orb(g, x0 - 40, y0 - 20, cw + 80, ch + 70, 0x000000, 0.7F * a);
		UiKit.orb(g, x0 - 24, y0 - 18, cw + 48, ch + 36, RED, (0.16F + 0.1F * throb) * a);
		UiKit.round(g, UiKit.FILL, x0, y0, cw, ch, 12, argb(a, CARD));
		UiKit.round(g, UiKit.EDGE, x0, y0, cw, ch, 12, argb((0.45F + 0.3F * throb) * a, RED));

		// ---- header: a red warning triangle (an outline pulsing out of it), the title in red, the 18+ tag
		float s0 = stagger(t, 0);
		int bx = x0 + PAD;
		int by = y0 + PAD + Math.round((1.0F - s0) * 6.0F);
		float pulse = (t % 1.6F) / 1.6F;
		if (t > 0.6F) {
			float grow = easeOutExpo(pulse) * 10.0F;
			UiKit.triangle(g, bx - grow, by - 2 - grow, 38 + 2 * grow, 36 + 2 * grow, argb(0.35F * (1.0F - pulse) * a * s0, RED));
		}
		// the sign itself, in 3D, rocking gently
		float pop = easeOutBack(Mth.clamp((t - 0.1F) / 0.4F, 0.0F, 1.0F));
		Gui3D.draw(g, SIGN, new Gui3D.View(bx + 19, by + 18, 15.5F * pop, Mth.sin(t * 1.3F) * 0.45F + (1.0F - pop) * 2.0F, Mth.sin(t * 0.9F) * 0.12F, 0.0F),
			a * s0, RED, 0.6F + 0.4F * throb);
		Component title = this.title.copy().withStyle(ChatFormatting.BOLD);
		int tx = bx + 50;
		int titleColor = lerpColor(0xFFFFFF, lerpColor(RED, 0xFF7A7E, throb * 0.5F), Mth.clamp((t - 0.2F) / 0.5F, 0.0F, 1.0F));
		g.drawString(this.font, title, tx, by + 6, argb(a * s0, titleColor), false);
		Component age = Component.literal("18+").withStyle(ChatFormatting.BOLD);
		int pillX = tx + this.font.width(title) + 8;
		if (pillX + this.font.width(age) + 10 < x0 + cw - PAD) {
			UiKit.round(g, UiKit.FILL, pillX, by + 3, this.font.width(age) + 10, 14, 7, argb(a * s0, RED));
			g.drawString(this.font, age, pillX + 5, by + 6, argb(a * s0, 0xFFFFFF), false);
		}
		g.drawString(this.font, Component.translatable("gore_warning.ballisticmissiles.subtitle"), tx, by + 20, argb(a * stagger(t, 1), TEXT), false);
		float line = easeOutExpo(Mth.clamp((t - 0.35F) / 0.8F, 0.0F, 1.0F));
		g.fill(x0 + PAD, y0 + PAD + 46, x0 + PAD + Math.round(textW * line), y0 + PAD + 48, argb(a * (0.6F + 0.3F * throb), RED));

		// ---- the warning and the alpha note, scrolling if the screen is too small for them
		int vt = this.viewTop() + lift;
		int vh = this.viewH();
		g.enableScissor(x0 + 1, vt, x0 + cw - 1, vt + vh);
		float s2 = stagger(t, 2);
		int y = vt - Math.round(this.scroll) + Math.round((1.0F - s2) * 6.0F);
		for (FormattedCharSequence l : this.body) {
			if (y > vt - LINE && y < vt + vh) {
				g.drawString(this.font, l, x0 + PAD, y, argb(a * s2, BODY), false);
			}
			y += LINE;
		}
		y += 14;
		float s3 = stagger(t, 3);
		y += Math.round((1.0F - s3) * 6.0F);
		int boxH = this.alphaBoxH();
		UiKit.round(g, UiKit.FILL, x0 + PAD, y, textW, boxH, 8, argb(0.07F * a * s3, AMBER));
		UiKit.round(g, UiKit.FILL, x0 + PAD, y, 3, boxH, 2, argb(0.9F * a * s3, AMBER));
		Component label = Component.translatable("gore_warning.ballisticmissiles.alpha_label").withStyle(ChatFormatting.BOLD);
		int lw = this.font.width(label) + 12;
		UiKit.round(g, UiKit.FILL, x0 + PAD + 14, y + 9, lw, 13, 6, argb(0.16F * a * s3, AMBER));
		g.drawString(this.font, label, x0 + PAD + 20, y + 12, argb(a * s3, AMBER), false);
		int ay = y + 28;
		for (FormattedCharSequence l : this.alpha) {
			g.drawString(this.font, l, x0 + PAD + 14, ay, argb(a * s3, 0xD9D4C7), false);
			ay += LINE;
		}
		g.disableScissor();
		// more below: a soft fade into the card and a thin scroll bar
		int max = this.maxScroll();
		if (max > 0) {
			float more = Mth.clamp((max - this.scroll) / 20.0F, 0.0F, 1.0F);
			if (more > 0.0F) {
				g.fillGradient(x0 + 1, vt + vh - 18, x0 + cw - 1, vt + vh, argb(0.0F, CARD), argb(a * more, CARD));
			}
			int barH = Math.max(14, Math.round(vh * vh / (float) this.contentH()));
			int barY = vt + Math.round((vh - barH) * this.scroll / max);
			UiKit.round(g, UiKit.FILL, x0 + cw - 8, barY, 3, barH, 1, argb(0.35F * a, 0xFFFFFF));
		}

		// ---- the check boxes
		float refused = (now - this.refusedAt) / 1000.0F;
		float point = refused < 1.4F ? (1.0F - refused / 1.4F) : 0.0F;
		int ry = this.rowsTop() + lift;
		this.checkRow(g, x0, ry, a * stagger(t, 4), "gore_warning.ballisticmissiles.adult", this.adult, now - this.adultAt, this.hoverAdult,
			!this.adult ? point : 0.0F, true);
		this.checkRow(g, x0, ry + ROW_H + 4, a * stagger(t, 5), "gore_warning.ballisticmissiles.dont_show", this.dontShow, now - this.dontShowAt,
			this.hoverDont, 0.0F, false);

		// ---- the buttons: turn gore off (quiet), and go on (fills while it may not yet be passed)
		float s6 = stagger(t, 6);
		int bty = this.buttonsTop() + lift + Math.round((1.0F - s6) * 5.0F);
		int offW = this.offW();
		UiKit.round(g, UiKit.FILL, x0 + PAD, bty, offW, BUTTON_H, 8, argb(a * s6 * (0.04F + 0.05F * this.hoverOff), 0xFFFFFF));
		UiKit.round(g, UiKit.EDGE, x0 + PAD, bty, offW, BUTTON_H, 8, argb(a * s6 * (0.12F + 0.1F * this.hoverOff), 0xFFFFFF));
		Component off = Component.translatable("gore_warning.ballisticmissiles.disable");
		g.drawString(this.font, off, x0 + PAD + (offW - this.font.width(off)) / 2, bty + 9, argb(a * s6, lerpColor(MUTED, TEXT, this.hoverOff)), false);

		int shake = refused < 0.6F ? Math.round((float) (Math.exp(-refused * 7.0) * Math.sin(refused * 48.0) * 6.0)) : 0;
		int gx = x0 + PAD + offW + 8 + shake;
		int gw = textW - offW - 8;
		float read = this.readProgress(now);
		int fill = lerpColor(DISABLED, lerpColor(BUTTON, 0xFFFFFF, this.hoverGo), this.ready);
		UiKit.round(g, UiKit.FILL, gx, bty, gw, BUTTON_H, 8, argb(a * s6, fill));
		if (read < 1.0F) {
			// the time to read it, filling the button
			int pw = Math.round(gw * read);
			if (pw > 2) {
				UiKit.round(g, UiKit.FILL, gx, bty, pw, BUTTON_H, Math.min(8, pw / 2), argb(a * s6 * 0.07F, 0xFFFFFF));
			}
		}
		Component go;
		if (read < 1.0F) {
			go = Component.translatable("gore_warning.ballisticmissiles.continue_wait", (int) Math.ceil((1.0F - read) * READ_MS / 1000.0F));
		} else if (!this.readToEnd) {
			go = Component.translatable("gore_warning.ballisticmissiles.scroll");
		} else {
			go = Component.translatable("gore_warning.ballisticmissiles.continue");
		}
		int ink = lerpColor(FAINT, BUTTON_INK, this.ready);
		if (!may && point > 0.0F && read >= 1.0F) {
			ink = lerpColor(ink, RED, point);
		}
		g.drawString(this.font, go, gx + (gw - this.font.width(go)) / 2, bty + 9, argb(a * s6, ink), false);
		// how to go on, very quietly under the card
		float s7 = stagger(t, 7);
		Component hint = Component.translatable("gore_warning.ballisticmissiles.hint");
		g.drawString(this.font, hint, (this.width - this.font.width(hint)) / 2, Math.min(this.height - 11, y0 + ch + 8), argb(a * s7 * 0.9F, FAINT), false);
	}

	/**
	 * Either side of the card, when the screen is wide enough: a big 3D warning sign that spins in and then hangs
	 * rocking, blood forming on its lower edge and dripping off, and a thick red 18+ coin turning slowly.
	 */
	private void hero(GuiGraphics g, float t, float alpha, float throb) {
		int side = this.cardX();
		if (side < 120) {
			return;
		}
		float size = Math.min(side * 0.3F, Math.min(64.0F, this.height * 0.16F));
		float cy = this.height * 0.46F;
		// the sign: in from far off, spinning down to rest, then rocking on its own
		float e = easeOutBack(Mth.clamp((t - 0.35F) / 1.1F, 0.0F, 1.0F));
		float spin = 1.0F - easeOutExpo(Mth.clamp((t - 0.35F) / 1.4F, 0.0F, 1.0F));
		float lx = side * 0.5F;
		float ly = cy + Mth.sin(t * 1.1F) * 4.0F;
		Gui3D.View sign = new Gui3D.View(lx, ly, size * e, -spin * 9.4F + Mth.sin(t * 0.7F) * 0.5F, Mth.sin(t * 0.5F) * 0.14F - spin * 0.5F,
			Mth.sin(t * 0.8F) * 0.05F);
		UiKit.orb(g, lx - size * 1.1F, ly + size * 1.05F, size * 2.2F, size * 0.5F, 0x000000, 0.55F * alpha * e);
		Gui3D.draw(g, SIGN, sign, alpha * Mth.clamp(e * 1.5F, 0.0F, 1.0F), RED, 0.5F + 0.5F * throb);
		this.drips(g, t, sign, size, alpha * Mth.clamp(e, 0.0F, 1.0F));

		// the coin: tossed in, then turning over and over
		float c = easeOutBack(Mth.clamp((t - 0.55F) / 1.0F, 0.0F, 1.0F));
		float rx = this.width - side * 0.5F;
		float ry = cy + Mth.sin(t * 1.1F + 1.7F) * 4.0F;
		Gui3D.View coin = new Gui3D.View(rx, ry, size * 0.85F * c, t * 1.25F + (1.0F - c) * 6.0F, 0.18F + Mth.sin(t * 0.6F) * 0.1F, -0.12F);
		UiKit.orb(g, rx - size, ry + size * 0.95F, size * 2.0F, size * 0.45F, 0x000000, 0.5F * alpha * c);
		float ca = alpha * Mth.clamp(c * 1.5F, 0.0F, 1.0F);
		Gui3D.draw(g, COIN, coin, ca, RED, 0.5F + 0.5F * throb);
		Component age = Component.literal("18+").withStyle(ChatFormatting.BOLD);
		int ink = argb(ca, 0xFFFFFF);
		Gui3D.faceText(g, this.font, age, coin, 0.0F, 0.0F, 0.171F, 0.62F, ink);
		Gui3D.faceText(g, this.font, age, coin, 0.0F, 0.0F, -0.171F, 0.62F, ink);
	}

	/** Drops of blood gathering on the sign's lower edge, swelling, then letting go and falling away. */
	private void drips(GuiGraphics g, float t, Gui3D.View sign, float size, float alpha) {
		if (t > this.nextDrip) {
			this.nextDrip = t + 0.6F + (float) Math.random() * 1.1F;
			this.drips.add(new float[] {(float) (Math.random() * 1.5 - 0.75), t});
		}
		this.drips.removeIf(d -> t - d[1] > 3.0F);
		for (float[] d : this.drips) {
			float age = t - d[1];
			float[] at = Gui3D.screen(new org.joml.Vector3f(d[0], -0.86F, 0.05F), sign);
			float r = Math.max(1.0F, size * 0.045F);
			float hang = 0.9F;
			int col = 0x8A0B10;
			if (age < hang) {
				// a bead swelling on the edge, sagging as it grows
				float k = age / hang;
				float rr = r * (0.4F + 0.8F * k);
				UiKit.disc(g, at[0], at[1] + rr * 0.6F * k, rr, argb(alpha, col));
				continue;
			}
			float f = age - hang;
			float fall = 0.5F * 520.0F * f * f;
			float y = at[1] + r + fall;
			float fade = 1.0F - Mth.clamp(fall / (this.height * 0.4F), 0.0F, 1.0F);
			if (fade <= 0.0F) {
				continue;
			}
			// falling it draws out into a tear: a round head below, a thin tail above
			float stretch = Math.min(r * 3.0F, 520.0F * f * 0.012F);
			g.fill(Math.round(at[0] - r * 0.35F), Math.round(y - stretch - r), Math.round(at[0] + r * 0.35F), Math.round(y), argb(alpha * fade * 0.8F, col));
			UiKit.disc(g, at[0], y, r, argb(alpha * fade, col));
		}
	}

	/** Diagonal red and black hazard stripes in a band, sliding along with {@code t}. */
	private void hazard(GuiGraphics g, int x, int y, int w, int h, float t, float alpha) {
		if (alpha <= 0.01F) {
			return;
		}
		g.fill(x, y, x + w, y + h, argb(alpha, 0x120607));
		g.enableScissor(x, y, x + w, y + h);
		int step = 16;
		float shift = (t * 12.0F) % step;
		for (float sx = x - step * 2 + shift; sx < x + w + step; sx += step) {
			g.pose().pushMatrix();
			g.pose().translate(sx, y);
			g.pose().mul(new org.joml.Matrix3x2f(1.0F, 0.0F, -1.0F, 1.0F, 0.0F, 0.0F)); // slanted
			g.fill(0, 0, step / 2, h, argb(alpha * 0.85F, DEEP_RED));
			g.pose().popMatrix();
		}
		g.disableScissor();
	}

	/** A check box with its label: the box fills with a little spring and the tick draws itself. */
	private void checkRow(GuiGraphics g, int x0, int y, float alpha, String key, boolean on, long since, float hover, float point, boolean primary) {
		if (alpha <= 0.005F) {
			return;
		}
		int x = x0 + PAD;
		int w = this.cardW() - 2 * PAD;
		if (hover > 0.01F || point > 0.0F) {
			UiKit.round(g, UiKit.FILL, x - 4, y, w + 8, ROW_H, 6, argb(alpha * 0.04F * hover, 0xFFFFFF));
		}
		if (point > 0.0F) {
			UiKit.round(g, UiKit.EDGE, x - 4, y, w + 8, ROW_H, 6, argb(alpha * 0.8F * point, RED));
		}
		int bx = x + 1;
		int by = y + 3;
		float p = Mth.clamp(since / 260.0F, 0.0F, 1.0F);
		float k = on ? easeOutBack(p) : 1.0F - ease(p);
		UiKit.round(g, UiKit.EDGE, bx, by, 14, 14, 4, argb(alpha * (0.25F + 0.2F * hover), 0xFFFFFF));
		int s = Math.round(14 * Mth.clamp(k, 0.0F, 1.2F));
		if (s > 1) {
			UiKit.round(g, UiKit.FILL, bx + (14 - s) / 2, by + (14 - s) / 2, s, s, 4, argb(alpha * Mth.clamp(k, 0.0F, 1.0F), BUTTON));
		}
		if (on) {
			float d = Mth.clamp((since - 90) / 220.0F, 0.0F, 1.0F);
			this.tick(g, bx + 7, by + 7, d, argb(alpha, BUTTON_INK));
		}
		Component label = Component.translatable(key);
		int color = primary ? lerpColor(BODY, TEXT, on ? 1.0F : hover) : lerpColor(MUTED, BODY, on ? 1.0F : hover);
		g.drawString(this.font, label, x + 24, y + 6, argb(alpha, color), false);
	}

	/** A tick drawing itself, short stroke first. */
	private void tick(GuiGraphics g, int x, int y, float p, int color) {
		float first = Mth.clamp(p / 0.4F, 0.0F, 1.0F);
		float second = Mth.clamp((p - 0.4F) / 0.6F, 0.0F, 1.0F);
		stroke(g, x - 4, y, x - 1, y + 3, first, color);
		if (second > 0.0F) {
			stroke(g, x - 1, y + 3, x + 4, y - 3, second, color);
		}
	}

	private static void stroke(GuiGraphics g, float ax, float ay, float bx, float by, float p, int color) {
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
}
