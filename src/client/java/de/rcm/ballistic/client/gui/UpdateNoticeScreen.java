package de.rcm.ballistic.client.gui;

import static de.rcm.ballistic.client.gui.UiKit.approach;
import static de.rcm.ballistic.client.gui.UiKit.argb;
import static de.rcm.ballistic.client.gui.UiKit.ease;
import static de.rcm.ballistic.client.gui.UiKit.easeOutBack;
import static de.rcm.ballistic.client.gui.UiKit.easeOutExpo;
import static de.rcm.ballistic.client.gui.UiKit.lerpColor;

import de.rcm.ballistic.client.ModConfig;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * Shown after the 18+ warning: a calm note that updates do not come all the time but now and then, whenever the
 * developer wants to - no fixed schedule. The same dark card as the warning, in a quiet blue: an "i" badge, the
 * note, a check box to not show it again, and a button that can be pressed after a few seconds.
 */
public final class UpdateNoticeScreen extends Screen {
	private static final long READ_MS = 4000L;
	private static final long OUT_MS = 420L;
	private static final int PAD = 22;
	private static final int LINE = 11;
	private static final int ROW_H = 20;
	private static final int BUTTON_H = 26;
	private static final int HEAD = 54;
	private static final int BACKDROP = 0x05080C;
	private static final int CARD = 0x12161C;
	private static final int TEXT = 0xEDEDEF;
	private static final int BODY = 0xB4B4BB;
	private static final int MUTED = 0x8E8E95;
	private static final int FAINT = 0x5E5E66;
	private static final int BUTTON = 0xE8E8EA;
	private static final int BUTTON_INK = 0x111113;
	private static final int DISABLED = 0x232326;
	private static final int BLUE = 0x4C9AFF;
	private static final int DEEP_BLUE = 0x173B6E;

	private final Screen next;
	private long opened = -1L;
	private long lastFrame;
	private boolean dontShow;
	private long dontShowAt = -100_000L;
	private long closingAt = -1L;
	private float hoverGo;
	private float hoverDont;
	private float ready;
	private boolean chimed;
	private List<FormattedCharSequence> body = List.of();

	public UpdateNoticeScreen(Screen next) {
		super(Component.translatable("update_notice.ballisticmissiles.title"));
		this.next = next;
	}

	@Override
	protected void init() {
		UiKit.init();
		this.body = this.font.split(Component.translatable("update_notice.ballisticmissiles.text"), this.cardW() - 2 * PAD);
		if (this.opened < 0L) {
			this.opened = Util.getMillis();
			this.lastFrame = this.opened;
		}
	}

	private int cardW() {
		return Math.min(400, this.width - 32);
	}

	private int cardH() {
		return PAD + HEAD + this.body.size() * LINE + 14 + ROW_H + 12 + BUTTON_H + PAD;
	}

	private int cardX() {
		return (this.width - this.cardW()) / 2;
	}

	private int cardY() {
		return Math.max(8, (this.height - this.cardH()) / 2);
	}

	private int rowTop() {
		return this.cardY() + PAD + HEAD + this.body.size() * LINE + 14;
	}

	private int buttonTop() {
		return this.rowTop() + ROW_H + 12;
	}

	private boolean overDont(double mx, double my) {
		return mx >= this.cardX() + PAD - 4 && mx < this.cardX() + this.cardW() - PAD + 4 && my >= this.rowTop() && my < this.rowTop() + ROW_H;
	}

	private boolean overGo(double mx, double my) {
		return mx >= this.cardX() + PAD && mx < this.cardX() + this.cardW() - PAD && my >= this.buttonTop() && my < this.buttonTop() + BUTTON_H;
	}

	private float readProgress(long now) {
		return Mth.clamp((now - this.opened) / (float) READ_MS, 0.0F, 1.0F);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || this.closingAt >= 0L) {
			return true;
		}
		long now = Util.getMillis();
		if (this.overDont(event.x(), event.y())) {
			this.dontShow = !this.dontShow;
			this.dontShowAt = now;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), this.dontShow ? 1.6F : 1.3F, 0.25F));
		} else if (this.overGo(event.x(), event.y())) {
			this.tryGo(now);
		}
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
		if (this.readProgress(now) < 1.0F) {
			return;
		}
		this.closingAt = now;
		if (this.dontShow) {
			ModConfig.updateNotice = false;
			ModConfig.save();
		}
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 0.3F));
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

	private static float stagger(float t, int index) {
		return easeOutExpo(Mth.clamp((t - 0.1F - index * 0.07F) / 0.5F, 0.0F, 1.0F));
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		long now = Util.getMillis();
		float dt = Math.min(0.1F, (now - this.lastFrame) / 1000.0F);
		this.lastFrame = now;
		float t = (now - this.opened) / 1000.0F;
		float out = this.closingAt < 0L ? 0.0F : Mth.clamp((now - this.closingAt) / (float) OUT_MS, 0.0F, 1.0F);
		boolean live = out <= 0.0F;
		float read = this.readProgress(now);
		this.ready = approach(this.ready, read >= 1.0F ? 1.0F : 0.0F, dt * 8.0F);
		this.hoverGo = approach(this.hoverGo, live && this.overGo(mouseX, mouseY) ? 1.0F : 0.0F, dt * 12.0F);
		this.hoverDont = approach(this.hoverDont, live && this.overDont(mouseX, mouseY) ? 1.0F : 0.0F, dt * 12.0F);
		if (!this.chimed && t > 0.15F) {
			this.chimed = true;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2F, 0.35F));
		}

		// the backdrop: near black with a slow blue glow behind the card
		float bg = ease(Mth.clamp(t / 0.35F, 0.0F, 1.0F)) * (1.0F - ease(Mth.clamp((out - 0.3F) / 0.7F, 0.0F, 1.0F)));
		g.fill(0, 0, this.width, this.height, argb(bg, BACKDROP));
		float breathe = 0.5F + 0.5F * Mth.sin(t * 1.6F);
		UiKit.orb(g, this.width * 0.5F - this.width * 0.55F, this.height * 0.5F - this.height * 0.7F, this.width * 1.1F, this.height * 1.4F, DEEP_BLUE,
			bg * (0.3F + 0.12F * breathe));

		float enter = easeOutExpo(Mth.clamp(t / 0.6F, 0.0F, 1.0F));
		float leave = ease(Mth.clamp(out / 0.8F, 0.0F, 1.0F));
		float a = enter * (1.0F - leave);
		if (a <= 0.005F) {
			return;
		}
		int lift = Math.round((1.0F - enter) * 14.0F + leave * 10.0F);
		int x0 = this.cardX();
		int y0 = this.cardY() + lift;
		int cw = this.cardW();
		int ch = this.cardH();
		UiKit.orb(g, x0 - 40, y0 - 20, cw + 80, ch + 70, 0x000000, 0.6F * a);
		UiKit.orb(g, x0 - 24, y0 - 18, cw + 48, ch + 36, BLUE, (0.1F + 0.05F * breathe) * a);
		UiKit.round(g, UiKit.FILL, x0, y0, cw, ch, 12, argb(a, CARD));
		UiKit.round(g, UiKit.EDGE, x0, y0, cw, ch, 12, argb((0.35F + 0.15F * breathe) * a, BLUE));

		// header: a blue "i" disc popping in, the title, a line drawing itself under it
		float s0 = stagger(t, 0);
		int bx = x0 + PAD;
		int by = y0 + PAD + Math.round((1.0F - s0) * 6.0F);
		float pop = easeOutBack(Mth.clamp((t - 0.08F) / 0.4F, 0.0F, 1.0F));
		UiKit.disc(g, bx + 17, by + 15, 17.0F * pop, argb(a * s0, BLUE));
		if (pop > 0.6F) {
			int ink = argb(a * s0 * Mth.clamp((pop - 0.6F) / 0.4F, 0.0F, 1.0F), 0x0A1424);
			g.fill(bx + 15, by + 7, bx + 19, by + 11, ink); // the dot of the i
			g.fill(bx + 15, by + 13, bx + 19, by + 24, ink);
		}
		int tx = bx + 46;
		g.drawString(this.font, this.title.copy().withStyle(ChatFormatting.BOLD), tx, by + 6, argb(a * s0, TEXT), false);
		g.drawString(this.font, Component.translatable("update_notice.ballisticmissiles.subtitle"), tx, by + 19, argb(a * stagger(t, 1), MUTED), false);
		float line = easeOutExpo(Mth.clamp((t - 0.3F) / 0.8F, 0.0F, 1.0F));
		g.fill(x0 + PAD, y0 + PAD + 42, x0 + PAD + Math.round((cw - 2 * PAD) * line), y0 + PAD + 43, argb(a * 0.6F, BLUE));

		// the note
		float s2 = stagger(t, 2);
		int y = y0 + PAD + HEAD + Math.round((1.0F - s2) * 6.0F);
		for (FormattedCharSequence l : this.body) {
			g.drawString(this.font, l, x0 + PAD, y, argb(a * s2, BODY), false);
			y += LINE;
		}

		// don't show it again
		float s3 = stagger(t, 3);
		int ry = this.rowTop() + lift;
		int rx = x0 + PAD;
		if (this.hoverDont > 0.01F) {
			UiKit.round(g, UiKit.FILL, rx - 4, ry, cw - 2 * PAD + 8, ROW_H, 6, argb(a * s3 * 0.04F * this.hoverDont, 0xFFFFFF));
		}
		float p = Mth.clamp((now - this.dontShowAt) / 260.0F, 0.0F, 1.0F);
		float k = this.dontShow ? easeOutBack(p) : 1.0F - ease(p);
		UiKit.round(g, UiKit.EDGE, rx + 1, ry + 3, 14, 14, 4, argb(a * s3 * (0.25F + 0.2F * this.hoverDont), 0xFFFFFF));
		int s = Math.round(14 * Mth.clamp(k, 0.0F, 1.2F));
		if (s > 1) {
			UiKit.round(g, UiKit.FILL, rx + 1 + (14 - s) / 2, ry + 3 + (14 - s) / 2, s, s, 4, argb(a * s3 * Mth.clamp(k, 0.0F, 1.0F), BUTTON));
		}
		if (this.dontShow && p > 0.4F) {
			int ink = argb(a * s3, BUTTON_INK);
			g.fill(rx + 4, ry + 10, rx + 7, ry + 13, ink);
			g.fill(rx + 7, ry + 7, rx + 12, ry + 10, ink);
			g.fill(rx + 6, ry + 11, rx + 9, ry + 13, ink);
		}
		g.drawString(this.font, Component.translatable("update_notice.ballisticmissiles.dont_show"), rx + 24, ry + 6,
			argb(a * s3, lerpColor(MUTED, BODY, this.dontShow ? 1.0F : this.hoverDont)), false);

		// the button: fills while it cannot be pressed yet
		float s4 = stagger(t, 4);
		int bty = this.buttonTop() + lift + Math.round((1.0F - s4) * 5.0F);
		int gw = cw - 2 * PAD;
		int fill = lerpColor(DISABLED, lerpColor(BUTTON, 0xFFFFFF, this.hoverGo), this.ready);
		UiKit.round(g, UiKit.FILL, rx, bty, gw, BUTTON_H, 8, argb(a * s4, fill));
		if (read < 1.0F) {
			int pw = Math.round(gw * read);
			if (pw > 2) {
				UiKit.round(g, UiKit.FILL, rx, bty, pw, BUTTON_H, Math.min(8, pw / 2), argb(a * s4 * 0.08F, BLUE));
			}
		}
		Component go = read < 1.0F
			? Component.translatable("update_notice.ballisticmissiles.continue_wait", (int) Math.ceil((1.0F - read) * READ_MS / 1000.0F))
			: Component.translatable("update_notice.ballisticmissiles.continue");
		g.drawString(this.font, go, rx + (gw - this.font.width(go)) / 2, bty + 9, argb(a * s4, lerpColor(FAINT, BUTTON_INK, this.ready)), false);
		Component hint = Component.translatable("update_notice.ballisticmissiles.hint");
		g.drawString(this.font, hint, (this.width - this.font.width(hint)) / 2, Math.min(this.height - 11, y0 + ch + 8), argb(a * stagger(t, 5) * 0.9F, FAINT),
			false);
	}
}
