package de.rcm.ballistic.client.gui;

import static de.rcm.ballistic.client.gui.UiKit.approach;
import static de.rcm.ballistic.client.gui.UiKit.argb;
import static de.rcm.ballistic.client.gui.UiKit.ease;
import static de.rcm.ballistic.client.gui.UiKit.easeOutBack;
import static de.rcm.ballistic.client.gui.UiKit.easeOutExpo;
import static de.rcm.ballistic.client.gui.UiKit.lerpColor;

import de.rcm.ballistic.client.ModConfig;
import java.util.ArrayList;
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
import org.lwjgl.glfw.GLFW;

/**
 * Shown at the start, before the title screen, and meant seriously: this mod shows extreme, realistic violence and
 * is for adults only.
 * <p>
 * One dark card, in three parts. On top what to expect - six tiles, each with a small pixel icon (wounds, severed
 * limbs, head wounds, bodies torn apart, organs and corpses, bleeding to death) - a short word on who it is for and
 * the note that the mod is an early alpha. Below, the choice: three levels side by side - no blood, blood only, or
 * everything (18+). And the one button, saying what it will do. The mild levels go at once; everything only once the
 * card has been up long enough to be read, read to the end, the 18+ box ticked - and then the button must be held
 * down a moment, a ring of red filling it, so nobody slips into it by a stray click or Enter. Pressing on too early
 * shakes the button and lights up what is missing. Ticked, it is not shown again (all of it is in the settings).
 * <p>
 * Round it: the red throb, hazard bands, ash drifting up, the big 3D warning sign bleeding off its lower edge and
 * the turning 18+ coin either side when there is room.
 */
public final class GoreWarningScreen extends Screen {
	/** How long it must stay up before everything can be chosen: long enough to actually be read. */
	private static final long READ_MS = 8000L;
	/** How long the button must be held for everything. */
	private static final long HOLD_MS = 1100L;
	private static final long OUT_MS = 520L;
	private static final int PAD = 20;
	private static final int LINE = 11;
	private static final int HEADER_H = 54;
	private static final int TILE_H = 24;
	private static final int OPTION_H = 46;
	private static final int ROW_H = 18;
	private static final int BUTTON_H = 28;
	/** The fixed footer: the level label, the three levels, two check rows, the button, gaps. */
	private static final int FOOTER_H = 14 + OPTION_H + 10 + ROW_H + 2 + ROW_H + 10 + BUTTON_H;

	private static final int BACKDROP = 0x0A0506;
	private static final int CARD = 0x171112;
	private static final int TILE = 0x211819;
	private static final int TEXT = 0xEDEDEF;
	private static final int BODY = 0xB4B4BB;
	private static final int MUTED = 0x8E8E95;
	private static final int FAINT = 0x5E5E66;
	private static final int BUTTON = 0xE8E8EA;
	private static final int BUTTON_INK = 0x111113;
	private static final int DISABLED = 0x242022;
	private static final int RED = 0xE5484D;
	private static final int DEEP_RED = 0x8E1B1F;
	private static final int BLOOD = 0x8A0B10;
	private static final int AMBER = 0xF5A524;
	private static final int CALM = 0x5FBF91;
	private static final int ORANGE = 0xF0883C;

	/** The levels: no blood and no gore, blood only, everything. */
	private static final int OFF = 0;
	private static final int BLOOD_ONLY = 1;
	private static final int FULL = 2;
	private static final int[] LEVEL_COLOR = {CALM, ORANGE, RED};
	private static final String[] LEVEL_KEY = {"off", "blood", "full"};

	/** What the content is: icon, then the label's key. */
	private static final String[][] ICONS = {
		{
			"....#....",
			"...###...",
			"...###...",
			"..#####..",
			".#######.",
			".###.###.",
			".##.####.",
			"..#####..",
			"...###..."},
		{
			".#.....#.",
			"###...###",
			".#######.",
			"###...###",
			".#.....#."},
		{
			"..#####..",
			".#######.",
			"#########",
			"##..#..##",
			"##..#..##",
			"####.####",
			".#######.",
			"..#.#.#.."},
		{
			"#...#...#",
			".#..#..#.",
			"...###...",
			"#.#####.#",
			"..#####..",
			"...###...",
			".#..#..#.",
			"#...#...#"},
		{
			".##...##.",
			"####.####",
			"#########",
			"#########",
			".#######.",
			"..#####..",
			"...###...",
			"....#...."},
		{
			"....#......",
			"...##......",
			"...#.#.....",
			"####.#.####",
			".....#.#...",
			"......#...."}};

	private static boolean shown;
	private final Screen next;
	private long opened = -1L;
	private long lastFrame;
	private int level;
	private long levelAt = -100_000L;
	private boolean adult;
	private boolean dontShow;
	private long adultAt = -100_000L;
	private long dontShowAt = -100_000L;
	/** Pressed on before it may be: when, for the shake and the hint. */
	private long refusedAt = -100_000L;
	private long closingAt = -1L;
	/** Holding the button down for everything: since when (-1: not holding). */
	private long holdingSince = -1L;
	private float hold;
	private float scroll;
	private float scrollTarget;
	private boolean readToEnd;
	private final float[] hoverLevel = new float[3];
	private float hoverGo;
	private float hoverAdult;
	private float hoverDont;
	private float ready;
	private final float[] picked = new float[3];
	/** The two warning tones as it comes up. */
	private int tones;
	private List<FormattedCharSequence> body = List.of();
	private List<FormattedCharSequence> alpha = List.of();
	private static final List<Gui3D.Part> SIGN = Gui3D.warningSign();
	private static final List<Gui3D.Part> COIN = Gui3D.coin();
	/** Blood running off the bottom edge of the big sign: where along it (model x) and when each drop formed. */
	private final List<float[]> drips = new ArrayList<>();
	private float nextDrip = 1.4F;
	/** Ash and embers drifting up through the dark: x, y (0-1 of the screen), speed, size, phase. */
	private final List<float[]> ash = new ArrayList<>();

	private GoreWarningScreen(Screen next) {
		super(Component.translatable("gore_warning.ballisticmissiles.title"));
		this.next = next;
		// start from what is set now
		this.level = ModConfig.gore ? FULL : ModConfig.blood ? BLOOD_ONLY : OFF;
		this.picked[this.level] = 1.0F;
		java.util.Random r = new java.util.Random(7L);
		for (int i = 0; i < 46; i++) {
			this.ash.add(new float[] {r.nextFloat(), r.nextFloat(), 0.012F + r.nextFloat() * 0.03F, 0.6F + r.nextFloat() * 1.6F, r.nextFloat() * 6.28F});
		}
	}

	/** Puts the warning up the first time the title screen appears (unless it was turned off), then the update note. */
	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!shown && mc.screen instanceof TitleScreen title) {
				shown = true;
				// then the note on how updates come, unless that was turned off too
				// after an update: what is new (last, right before the title screen)
				Screen notes = PatchNotesScreen.afterUpdate(title);
				Screen beforeTitle = notes != null ? notes : title;
				Screen after = ModConfig.updateNotice ? new UpdateNoticeScreen(beforeTitle) : beforeTitle;
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
		return Math.min(520, this.width - 24);
	}

	private int textW() {
		return this.cardW() - 2 * PAD;
	}

	private int columns() {
		return this.textW() >= 360 ? 3 : 2;
	}

	private int tilesH() {
		int rows = (ICONS.length + this.columns() - 1) / this.columns();
		return rows * TILE_H + (rows - 1) * 5;
	}

	/** Height of what scrolls: what to expect, the word on it, the alpha note. */
	private int contentH() {
		return 14 + this.tilesH() + 10 + this.body.size() * LINE + 10 + this.alphaBoxH();
	}

	private int alphaBoxH() {
		return 24 + this.alpha.size() * LINE + 8;
	}

	private int cardH() {
		return Math.min(this.height - 20, PAD + HEADER_H + this.contentH() + 12 + FOOTER_H + PAD);
	}

	private int viewH() {
		return Math.max(24, this.cardH() - PAD - HEADER_H - 12 - FOOTER_H - PAD);
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
		return this.cardY() + PAD + HEADER_H;
	}

	private int footerTop() {
		return this.viewTop() + this.viewH() + 12;
	}

	private int optionsTop() {
		return this.footerTop() + 14;
	}

	private int optionW() {
		return (this.textW() - 2 * 8) / 3;
	}

	private int optionX(int i) {
		return this.cardX() + PAD + i * (this.optionW() + 8);
	}

	private int adultTop() {
		return this.optionsTop() + OPTION_H + 10;
	}

	private int dontTop() {
		return this.adultTop() + ROW_H + 2;
	}

	private int buttonTop() {
		return this.dontTop() + ROW_H + 10;
	}

	private boolean over(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private int overLevel(double mx, double my) {
		for (int i = 0; i < 3; i++) {
			if (this.over(mx, my, this.optionX(i), this.optionsTop(), this.optionW(), OPTION_H)) {
				return i;
			}
		}
		return -1;
	}

	private boolean overAdult(double mx, double my) {
		return this.level == FULL && this.over(mx, my, this.cardX() + PAD - 4, this.adultTop(), this.textW() + 8, ROW_H);
	}

	private boolean overDontShow(double mx, double my) {
		return this.over(mx, my, this.cardX() + PAD - 4, this.dontTop(), this.textW() + 8, ROW_H);
	}

	private boolean overGo(double mx, double my) {
		return this.over(mx, my, this.cardX() + PAD, this.buttonTop(), this.textW(), BUTTON_H);
	}

	// ------------------------------------------------------------------ state

	private float readProgress(long now) {
		return Mth.clamp((now - this.opened) / (float) READ_MS, 0.0F, 1.0F);
	}

	/** Can it go on now (for everything: read, read to the end, 18+ ticked - the hold comes on top)? */
	private boolean mayGo(long now) {
		return this.level != FULL || this.readProgress(now) >= 1.0F && this.readToEnd && this.adult;
	}

	private void pick(int level) {
		if (level == this.level) {
			return;
		}
		this.level = level;
		this.levelAt = Util.getMillis();
		this.holdingSince = -1L;
		click(level == FULL ? 0.8F : level == BLOOD_ONLY ? 1.1F : 1.4F);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || this.closingAt >= 0L) {
			return true;
		}
		double mx = event.x();
		double my = event.y();
		long now = Util.getMillis();
		int lv = this.overLevel(mx, my);
		if (lv >= 0) {
			this.pick(lv);
		} else if (this.overAdult(mx, my)) {
			this.adult = !this.adult;
			this.adultAt = now;
			click(this.adult ? 1.6F : 1.3F);
		} else if (this.overDontShow(mx, my)) {
			this.dontShow = !this.dontShow;
			this.dontShowAt = now;
			click(this.dontShow ? 1.6F : 1.3F);
		} else if (this.overGo(mx, my)) {
			this.press(now);
		}
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		this.holdingSince = -1L;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		this.scrollTarget = Mth.clamp(this.scrollTarget - (float) scrollY * LINE * 3, 0.0F, this.maxScroll());
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.closingAt < 0L) {
			if (event.isConfirmation()) {
				if (this.holdingSince < 0L) {
					this.press(Util.getMillis());
				}
				return true;
			}
			int key = event.key();
			if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_3) {
				this.pick(key - GLFW.GLFW_KEY_1);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (event.isConfirmation()) {
			this.holdingSince = -1L;
		}
		return super.keyReleased(event);
	}

	/** The button pressed: the mild levels go at once; everything starts the hold, once it may. */
	private void press(long now) {
		if (!this.mayGo(now)) {
			this.refusedAt = now;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8F, 0.3F));
			return;
		}
		if (this.level == FULL) {
			this.holdingSince = now;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5F, 0.35F));
		} else {
			this.close();
		}
	}

	private static void click(float pitch) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.25F));
	}

	private void close() {
		this.closingAt = Util.getMillis();
		this.holdingSince = -1L;
		ModConfig.gore = this.level == FULL;
		ModConfig.blood = this.level != OFF;
		if (this.dontShow) {
			ModConfig.goreWarning = false;
		}
		ModConfig.save();
		if (this.level == FULL) {
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
		int hovered = live ? this.overLevel(mouseX, mouseY) : -1;
		for (int i = 0; i < 3; i++) {
			this.hoverLevel[i] = approach(this.hoverLevel[i], hovered == i ? 1.0F : 0.0F, dt * 12.0F);
			this.picked[i] = approach(this.picked[i], this.level == i ? 1.0F : 0.0F, dt * 10.0F);
		}
		this.hoverGo = approach(this.hoverGo, live && this.overGo(mouseX, mouseY) ? 1.0F : 0.0F, dt * 12.0F);
		this.hoverAdult = approach(this.hoverAdult, live && this.overAdult(mouseX, mouseY) ? 1.0F : 0.0F, dt * 12.0F);
		this.hoverDont = approach(this.hoverDont, live && this.overDontShow(mouseX, mouseY) ? 1.0F : 0.0F, dt * 12.0F);
		// the hold for everything: let go and it drains away again
		if (this.holdingSince >= 0L && (!may || this.level != FULL)) {
			this.holdingSince = -1L;
		}
		if (this.holdingSince >= 0L) {
			this.hold = Mth.clamp((now - this.holdingSince) / (float) HOLD_MS, 0.0F, 1.0F);
			if (this.hold >= 1.0F && live) {
				this.close();
			}
		} else {
			this.hold = approach(this.hold, 0.0F, dt * 4.0F);
		}

		// two warning tones as it comes up
		if (this.tones < 2 && t > 0.12F + this.tones * 0.32F) {
			this.tones++;
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT.value(), 0.62F, 0.45F));
			Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6F, 0.5F));
		}

		// ---- the backdrop: near black, a red glow throbbing behind the card (harder the more is chosen), ash, stripes
		float bg = ease(Mth.clamp(t / 0.4F, 0.0F, 1.0F)) * (1.0F - ease(Mth.clamp((out - 0.35F) / 0.65F, 0.0F, 1.0F)));
		g.fill(0, 0, this.width, this.height, argb(bg, BACKDROP));
		float throb = 0.5F + 0.5F * Mth.sin(t * (2.6F + 1.6F * this.picked[FULL]));
		float heat = 0.55F + 0.45F * this.picked[FULL] + 0.6F * this.hold;
		UiKit.orb(g, this.width * 0.5F - this.width * 0.6F, this.height * 0.5F - this.height * 0.75F, this.width * 1.2F, this.height * 1.5F, DEEP_RED,
			bg * (0.25F + 0.2F * throb) * heat);
		this.ash(g, t, bg);
		// the corners sink into black
		UiKit.orb(g, -this.width * 0.35F, -this.height * 0.45F, this.width * 0.7F, this.height * 0.9F, 0x000000, 0.6F * bg);
		UiKit.orb(g, this.width * 0.65F, this.height * 0.55F, this.width * 0.7F, this.height * 0.9F, 0x000000, 0.6F * bg);
		float band = easeOutExpo(Mth.clamp((t - 0.1F) / 0.6F, 0.0F, 1.0F)) * bg;
		this.hazard(g, 0, 0, this.width, 5, t, band);
		this.hazard(g, 0, this.height - 5, this.width, 5, -t, band);
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
		int textW = this.textW();
		float jolt = t < 0.5F ? (float) (Math.exp(-t * 9.0) * Math.sin(t * 60.0) * 5.0) : 0.0F;
		x0 += Math.round(jolt);
		UiKit.orb(g, x0 - 40, y0 - 20, cw + 80, ch + 70, 0x000000, 0.75F * a);
		UiKit.orb(g, x0 - 24, y0 - 18, cw + 48, ch + 36, RED, (0.1F + 0.08F * throb) * a * heat);
		UiKit.round(g, UiKit.FILL, x0, y0, cw, ch, 12, argb(a, CARD));
		// a faint lighter band across the top: the header sits on it
		UiKit.round(g, UiKit.FILL, x0 + 1, y0 + 1, cw - 2, PAD + HEADER_H - 8, 11, argb(a * 0.5F, 0x1F1516));
		UiKit.round(g, UiKit.EDGE, x0, y0, cw, ch, 12, argb((0.35F + 0.25F * throb) * a * heat, RED));

		this.header(g, t, a, x0, y0, textW, throb);

		// ---- what to expect, the word on it and the alpha note (scrolling if the screen is too small)
		int vt = this.viewTop() + lift;
		int vh = this.viewH();
		g.enableScissor(x0 + 1, vt, x0 + cw - 1, vt + vh);
		int y = vt - Math.round(this.scroll);
		float s2 = stagger(t, 2);
		g.drawString(this.font, Component.translatable("gore_warning.ballisticmissiles.expect").withStyle(ChatFormatting.BOLD), x0 + PAD, y + 2,
			argb(a * s2, MUTED), false);
		y += 14;
		this.tiles(g, t, a, x0 + PAD, y, textW);
		y += this.tilesH() + 10;
		float s4 = stagger(t, 4);
		for (FormattedCharSequence l : this.body) {
			if (y > vt - LINE && y < vt + vh) {
				g.drawString(this.font, l, x0 + PAD, y + Math.round((1.0F - s4) * 5.0F), argb(a * s4, BODY), false);
			}
			y += LINE;
		}
		y += 10;
		float s5 = stagger(t, 5);
		int boxH = this.alphaBoxH();
		UiKit.round(g, UiKit.FILL, x0 + PAD, y, textW, boxH, 8, argb(0.07F * a * s5, AMBER));
		UiKit.round(g, UiKit.FILL, x0 + PAD, y, 3, boxH, 2, argb(0.9F * a * s5, AMBER));
		Component label = Component.translatable("gore_warning.ballisticmissiles.alpha_label").withStyle(ChatFormatting.BOLD);
		int lw = this.font.width(label) + 12;
		UiKit.round(g, UiKit.FILL, x0 + PAD + 14, y + 7, lw, 13, 6, argb(0.16F * a * s5, AMBER));
		g.drawString(this.font, label, x0 + PAD + 20, y + 10, argb(a * s5, AMBER), false);
		int ay = y + 26;
		for (FormattedCharSequence l : this.alpha) {
			g.drawString(this.font, l, x0 + PAD + 14, ay, argb(a * s5, 0xD9D4C7), false);
			ay += LINE;
		}
		g.disableScissor();
		// more below: a soft fade into the card, a thin scroll bar and a nudge
		int max = this.maxScroll();
		if (max > 0) {
			float more = Mth.clamp((max - this.scroll) / 20.0F, 0.0F, 1.0F);
			if (more > 0.0F) {
				g.fillGradient(x0 + 1, vt + vh - 22, x0 + cw - 1, vt + vh, argb(0.0F, CARD), argb(a * more, CARD));
				Component down = Component.literal("▼");
				float bob = Mth.sin(t * 4.0F) * 1.5F;
				g.drawString(this.font, down, x0 + cw / 2 - this.font.width(down) / 2, vt + vh - 10 + Math.round(bob), argb(a * more * 0.7F, MUTED), false);
			}
			int barH = Math.max(14, Math.round(vh * vh / (float) this.contentH()));
			int barY = vt + Math.round((vh - barH) * this.scroll / max);
			UiKit.round(g, UiKit.FILL, x0 + cw - 8, barY, 3, barH, 1, argb(0.35F * a, 0xFFFFFF));
		}
		// a hairline between what is told and what is chosen
		g.fill(x0 + PAD, vt + vh + 5, x0 + cw - PAD, vt + vh + 6, argb(a * 0.5F, 0x2E2628));

		// ---- the choice
		float refused = (now - this.refusedAt) / 1000.0F;
		float point = refused < 1.4F ? (1.0F - refused / 1.4F) : 0.0F;
		int ft = this.footerTop() + lift;
		float s6 = stagger(t, 6);
		g.drawString(this.font, Component.translatable("gore_warning.ballisticmissiles.level").withStyle(ChatFormatting.BOLD), x0 + PAD, ft + 2,
			argb(a * s6, MUTED), false);
		Component keys = Component.translatable("gore_warning.ballisticmissiles.level_keys");
		g.drawString(this.font, keys, x0 + cw - PAD - this.font.width(keys), ft + 2, argb(a * s6 * 0.8F, FAINT), false);
		for (int i = 0; i < 3; i++) {
			this.option(g, t, i, a * stagger(t, 6 + i), this.optionX(i) + Math.round(jolt), this.optionsTop() + lift);
		}

		// ---- 18+ (only needed - and only live - for everything) and don't show again
		float need = this.picked[FULL];
		this.checkRow(g, x0, this.adultTop() + lift, a * stagger(t, 9) * (0.35F + 0.65F * need), "gore_warning.ballisticmissiles.adult", this.adult,
			now - this.adultAt, this.hoverAdult, this.level == FULL && !this.adult ? point : 0.0F, true);
		if (need < 0.99F) {
			Component only = Component.translatable("gore_warning.ballisticmissiles.adult_only");
			g.drawString(this.font, only, x0 + cw - PAD - this.font.width(only), this.adultTop() + lift + 5, argb(a * (1.0F - need) * 0.8F, FAINT), false);
		}
		this.checkRow(g, x0, this.dontTop() + lift, a * stagger(t, 10), "gore_warning.ballisticmissiles.dont_show", this.dontShow, now - this.dontShowAt,
			this.hoverDont, 0.0F, false);

		// ---- the button, saying what it will do
		this.button(g, t, now, a * stagger(t, 11), x0 + PAD, this.buttonTop() + lift, textW, may, refused, point);

		// how to go on, very quietly under the card
		Component hint = Component.translatable("gore_warning.ballisticmissiles.hint");
		g.drawString(this.font, hint, (this.width - this.font.width(hint)) / 2, Math.min(this.height - 10, y0 + ch + 6), argb(a * stagger(t, 12) * 0.9F, FAINT),
			false);
	}

	/** The header: the 3D sign with a pulse ring, the title (big), the 18+ tag, the subtitle, a red rule drawing itself. */
	private void header(GuiGraphics g, float t, float a, int x0, int y0, int textW, float throb) {
		float s0 = stagger(t, 0);
		int bx = x0 + PAD;
		int by = y0 + PAD - 2 + Math.round((1.0F - s0) * 6.0F);
		float pulse = (t % 1.6F) / 1.6F;
		if (t > 0.6F) {
			float grow = easeOutExpo(pulse) * 10.0F;
			UiKit.triangle(g, bx - grow, by - 2 - grow, 38 + 2 * grow, 36 + 2 * grow, argb(0.35F * (1.0F - pulse) * a * s0, RED));
		}
		float pop = easeOutBack(Mth.clamp((t - 0.1F) / 0.4F, 0.0F, 1.0F));
		Gui3D.draw(g, SIGN, new Gui3D.View(bx + 19, by + 18, 15.5F * pop, Mth.sin(t * 1.3F) * 0.45F + (1.0F - pop) * 2.0F, Mth.sin(t * 0.9F) * 0.12F, 0.0F),
			a * s0, RED, 0.6F + 0.4F * throb);
		int tx = bx + 50;
		// the title half again as big, if it fits; the 18+ tag after it
		Component title = this.title.copy().withStyle(ChatFormatting.BOLD);
		Component age = Component.literal("18+").withStyle(ChatFormatting.BOLD);
		int room = x0 + this.cardW() - PAD - tx - this.font.width(age) - 18;
		float scale = this.font.width(title) * 1.5F <= room ? 1.5F : this.font.width(title) <= room ? 1.0F : Math.max(0.6F, room / (float) this.font.width(title));
		int titleColor = lerpColor(0xFFFFFF, lerpColor(RED, 0xFF7A7E, throb * 0.5F), Mth.clamp((t - 0.2F) / 0.5F, 0.0F, 1.0F));
		g.pose().pushMatrix();
		g.pose().translate(tx, by + 3);
		g.pose().scale(scale, scale);
		g.drawString(this.font, title, 0, 0, argb(a * s0, titleColor), false);
		g.pose().popMatrix();
		int pillX = tx + Math.round(this.font.width(title) * scale) + 8;
		int pillY = by + 3 + Math.round((9 * scale - 14) / 2.0F);
		UiKit.round(g, UiKit.FILL, pillX, pillY, this.font.width(age) + 10, 14, 7, argb(a * s0, RED));
		g.drawString(this.font, age, pillX + 5, pillY + 3, argb(a * s0, 0xFFFFFF), false);
		g.drawString(this.font, Component.translatable("gore_warning.ballisticmissiles.subtitle"), tx, by + 7 + Math.round(9 * scale) + 3,
			argb(a * stagger(t, 1), TEXT), false);
		float line = easeOutExpo(Mth.clamp((t - 0.35F) / 0.8F, 0.0F, 1.0F));
		int ly = y0 + PAD + HEADER_H - 10;
		g.fill(x0 + PAD, ly, x0 + PAD + Math.round(textW * line), ly + 2, argb(a * (0.6F + 0.3F * throb), RED));
	}

	/** The six tiles of what to expect, each popping in after the last, its icon in red. */
	private void tiles(GuiGraphics g, float t, float a, int x, int y, int textW) {
		int cols = this.columns();
		int gap = 5;
		int w = (textW - (cols - 1) * gap) / cols;
		for (int i = 0; i < ICONS.length; i++) {
			int c = i % cols;
			int r = i / cols;
			float s = easeOutBack(Mth.clamp((t - 0.35F - i * 0.08F) / 0.45F, 0.0F, 1.0F));
			float k = Mth.clamp(s, 0.0F, 1.0F) * a;
			if (k <= 0.005F) {
				continue;
			}
			int tx = x + c * (w + gap);
			int ty = y + r * (TILE_H + gap) + Math.round((1.0F - s) * 6.0F);
			UiKit.round(g, UiKit.FILL, tx, ty, w, TILE_H, 6, argb(k, TILE));
			UiKit.round(g, UiKit.EDGE, tx, ty, w, TILE_H, 6, argb(k * 0.5F, 0x3A2A2C));
			// the icon on a dark red disc
			UiKit.disc(g, tx + 13, ty + TILE_H / 2.0F, 8.5F, argb(k * 0.9F, 0x3A1214));
			String[] icon = ICONS[i];
			int iw = icon[0].length();
			int ih = icon.length;
			this.pixels(g, icon, tx + 13 - iw / 2, ty + TILE_H / 2 - ih / 2, argb(k, i == 5 ? 0xFF6A6E : RED));
			Component name = Component.translatable("gore_warning.ballisticmissiles.expect." + (i + 1));
			int nameW = w - 30;
			List<FormattedCharSequence> lines = this.font.split(name, nameW);
			if (lines.size() == 1) {
				g.drawString(this.font, lines.get(0), tx + 26, ty + (TILE_H - 8) / 2, argb(k, TEXT), false);
			} else {
				g.drawString(this.font, lines.get(0), tx + 26, ty + 3, argb(k, TEXT), false);
				g.drawString(this.font, lines.get(1), tx + 26, ty + 13, argb(k, TEXT), false);
			}
		}
	}

	/** A little pixel picture, one fill per pixel: crisp at any GUI scale. */
	private void pixels(GuiGraphics g, String[] rows, int x, int y, int argb) {
		for (int r = 0; r < rows.length; r++) {
			String row = rows[r];
			for (int c = 0; c < row.length(); c++) {
				if (row.charAt(c) == '#') {
					g.fill(x + c, y + r, x + c + 1, y + r + 1, argb);
				}
			}
		}
	}

	/** One level to pick: a radio dot, the name in its colour, what it means; chosen, it lights up in that colour. */
	private void option(GuiGraphics g, float t, int i, float a, int x, int y) {
		if (a <= 0.005F) {
			return;
		}
		int w = this.optionW();
		int color = LEVEL_COLOR[i];
		float p = this.picked[i];
		float h = this.hoverLevel[i];
		// chosen: a small spring as it is taken
		float since = (Util.getMillis() - this.levelAt) / 1000.0F;
		int bump = this.level == i && since < 0.35F ? Math.round((float) (Math.sin(since / 0.35F * Math.PI) * -2.0)) : 0;
		y += bump;
		UiKit.round(g, UiKit.FILL, x, y, w, OPTION_H, 8, argb(a, lerpColor(TILE, lerpColor(TILE, color, 0.16F), p)));
		UiKit.round(g, UiKit.EDGE, x, y, w, OPTION_H, 8, argb(a * (0.2F + 0.15F * h + 0.65F * p), lerpColor(0x5A4A4C, color, Math.max(p, h * 0.6F))));
		if (i == FULL && p > 0.0F) {
			// everything chosen: its edge breathes red
			float br = 0.5F + 0.5F * Mth.sin(t * 4.0F);
			UiKit.orb(g, x - 10, y - 8, w + 20, OPTION_H + 16, RED, a * p * (0.1F + 0.12F * br + 0.3F * this.hold));
		}
		// the radio dot
		float cx = x + 12;
		float cy = y + 12;
		UiKit.disc(g, cx, cy, 5.0F, argb(a * (0.35F + 0.3F * h), lerpColor(0x8A8A90, color, p)));
		UiKit.disc(g, cx, cy, 3.8F, argb(a, TILE));
		if (p > 0.01F) {
			UiKit.disc(g, cx, cy, 2.6F * easeOutBack(p), argb(a * p, color));
		}
		Component name = Component.translatable("gore_warning.ballisticmissiles.level." + LEVEL_KEY[i]).withStyle(ChatFormatting.BOLD);
		g.drawString(this.font, name, x + 22, y + 8, argb(a, lerpColor(lerpColor(BODY, TEXT, h), color, p)), false);
		if (i == FULL) {
			Component tag = Component.literal("18+").withStyle(ChatFormatting.BOLD);
			int tw = this.font.width(tag) + 6;
			int tagX = x + w - tw - 6;
			if (tagX > x + 22 + this.font.width(name) + 4) {
				UiKit.round(g, UiKit.FILL, tagX, y + 6, tw, 12, 5, argb(a * (0.5F + 0.5F * p), RED));
				g.drawString(this.font, tag, tagX + 3, y + 8, argb(a, 0xFFFFFF), false);
			}
		}
		List<FormattedCharSequence> desc = this.font.split(Component.translatable("gore_warning.ballisticmissiles.level." + LEVEL_KEY[i] + "_desc"), w - 16);
		int dy = y + 22;
		for (int k = 0; k < Math.min(2, desc.size()); k++) {
			g.drawString(this.font, desc.get(k), x + 8, dy, argb(a, lerpColor(MUTED, BODY, Math.max(p, h))), false);
			dy += 10;
		}
		// the key for it, quietly in the corner
		if (i != FULL) {
			Component n = Component.literal(String.valueOf(i + 1));
			g.drawString(this.font, n, x + w - 6 - this.font.width(n), y + 8, argb(a * 0.6F, FAINT), false);
		}
	}

	/** The one button: says what it does; for everything it fills while it may not yet, then wants to be held. */
	private void button(GuiGraphics g, float t, long now, float a, int x, int y, int w, boolean may, float refused, float point) {
		if (a <= 0.005F) {
			return;
		}
		int shake = refused < 0.6F ? Math.round((float) (Math.exp(-refused * 7.0) * Math.sin(refused * 48.0) * 6.0)) : 0;
		x += shake;
		boolean full = this.level == FULL;
		int accent = LEVEL_COLOR[this.level];
		int fill = lerpColor(DISABLED, lerpColor(BUTTON, 0xFFFFFF, this.hoverGo), this.ready);
		UiKit.round(g, UiKit.FILL, x, y, w, BUTTON_H, 9, argb(a, fill));
		float read = this.readProgress(now);
		if (full && read < 1.0F) {
			// the time to read it, filling the button
			int pw = Math.round(w * read);
			if (pw > 2) {
				UiKit.round(g, UiKit.FILL, x, y, pw, BUTTON_H, Math.min(9, pw / 2), argb(a * 0.08F, 0xFFFFFF));
			}
		}
		if (full && this.hold > 0.0F) {
			// held: red fills it from the left
			int hw = Math.round(w * this.hold);
			if (hw > 2) {
				UiKit.round(g, UiKit.FILL, x, y, hw, BUTTON_H, Math.min(9, hw / 2), argb(a, lerpColor(DEEP_RED, RED, this.hold)));
			}
		}
		// a stripe of the level's colour on the left
		UiKit.round(g, UiKit.FILL, x + 6, y + 8, 3, BUTTON_H - 16, 1, argb(a * (0.3F + 0.7F * this.ready), accent));
		Component go;
		if (full && read < 1.0F) {
			go = Component.translatable("gore_warning.ballisticmissiles.continue_wait", (int) Math.ceil((1.0F - read) * READ_MS / 1000.0F));
		} else if (full && !this.readToEnd) {
			go = Component.translatable("gore_warning.ballisticmissiles.scroll");
		} else if (full && !this.adult) {
			go = Component.translatable("gore_warning.ballisticmissiles.need_adult");
		} else if (full) {
			go = Component.translatable(this.hold > 0.02F ? "gore_warning.ballisticmissiles.holding" : "gore_warning.ballisticmissiles.go.full");
		} else {
			go = Component.translatable("gore_warning.ballisticmissiles.go." + LEVEL_KEY[this.level]);
		}
		int ink = lerpColor(FAINT, BUTTON_INK, this.ready);
		if (full && this.hold > 0.35F) {
			ink = lerpColor(ink, 0xFFFFFF, Mth.clamp((this.hold - 0.35F) / 0.3F, 0.0F, 1.0F));
		}
		if (!may && point > 0.0F) {
			ink = lerpColor(ink, RED, point);
		}
		g.drawString(this.font, go.copy().withStyle(ChatFormatting.BOLD), x + (w - this.font.width(go.copy().withStyle(ChatFormatting.BOLD))) / 2,
			y + (BUTTON_H - 8) / 2, argb(a, ink), false);
	}

	/**
	 * Either side of the card, when the screen is wide enough: a big 3D warning sign that spins in and then hangs
	 * rocking, blood forming on its lower edge and dripping off, and a thick red 18+ coin turning slowly.
	 */
	private void hero(GuiGraphics g, float t, float alpha, float throb) {
		int side = this.cardX();
		if (side < 110) {
			return;
		}
		float size = Math.min(side * 0.3F, Math.min(64.0F, this.height * 0.16F));
		float cy = this.height * 0.46F;
		float e = easeOutBack(Mth.clamp((t - 0.35F) / 1.1F, 0.0F, 1.0F));
		float spin = 1.0F - easeOutExpo(Mth.clamp((t - 0.35F) / 1.4F, 0.0F, 1.0F));
		float lx = side * 0.5F;
		float ly = cy + Mth.sin(t * 1.1F) * 4.0F;
		Gui3D.View sign = new Gui3D.View(lx, ly, size * e, -spin * 9.4F + Mth.sin(t * 0.7F) * 0.5F, Mth.sin(t * 0.5F) * 0.14F - spin * 0.5F,
			Mth.sin(t * 0.8F) * 0.05F);
		UiKit.orb(g, lx - size * 1.1F, ly + size * 1.05F, size * 2.2F, size * 0.5F, 0x000000, 0.55F * alpha * e);
		Gui3D.draw(g, SIGN, sign, alpha * Mth.clamp(e * 1.5F, 0.0F, 1.0F), RED, 0.5F + 0.5F * throb);
		this.drips(g, t, sign, size, alpha * Mth.clamp(e, 0.0F, 1.0F));

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
			if (age < hang) {
				float k = age / hang;
				float rr = r * (0.4F + 0.8F * k);
				UiKit.disc(g, at[0], at[1] + rr * 0.6F * k, rr, argb(alpha, BLOOD));
				continue;
			}
			float f = age - hang;
			float fall = 0.5F * 520.0F * f * f;
			float y = at[1] + r + fall;
			float fade = 1.0F - Mth.clamp(fall / (this.height * 0.4F), 0.0F, 1.0F);
			if (fade <= 0.0F) {
				continue;
			}
			float stretch = Math.min(r * 3.0F, 520.0F * f * 0.012F);
			g.fill(Math.round(at[0] - r * 0.35F), Math.round(y - stretch - r), Math.round(at[0] + r * 0.35F), Math.round(y), argb(alpha * fade * 0.8F, BLOOD));
			UiKit.disc(g, at[0], y, r, argb(alpha * fade, BLOOD));
		}
	}

	/** Ash and a few embers drifting up through the dark behind everything, swaying as they go. */
	private void ash(GuiGraphics g, float t, float alpha) {
		if (alpha <= 0.01F) {
			return;
		}
		for (int i = 0; i < this.ash.size(); i++) {
			float[] p = this.ash.get(i);
			float y = 1.0F - ((p[1] + t * p[2]) % 1.1F);
			float x = p[0] + Mth.sin(t * 0.6F + p[4]) * 0.015F;
			boolean ember = i % 7 == 0;
			float flicker = ember ? 0.6F + 0.4F * Mth.sin(t * 5.0F + p[4] * 3.0F) : 1.0F;
			// fade in at the bottom, out near the top
			float edge = Mth.clamp(y / 0.15F, 0.0F, 1.0F) * Mth.clamp((1.0F - y) / 0.4F, 0.0F, 1.0F);
			int col = ember ? 0xFF5A30 : 0x6A3434;
			UiKit.disc(g, x * this.width, y * this.height, p[3] * (ember ? 0.9F : 1.0F), argb(alpha * edge * flicker * (ember ? 0.8F : 0.35F), col));
		}
	}

	/** Diagonal red and black hazard stripes in a band, sliding along with {@code t}. */
	private void hazard(GuiGraphics g, int x, int y, int w, int h, float t, float alpha) {
		if (alpha <= 0.01F) {
			return;
		}
		g.fill(x, y, x + w, y + h, argb(alpha, 0x120607));
		g.enableScissor(x, y, x + w, y + h);
		int step = 14;
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
		int w = this.textW();
		if (hover > 0.01F || point > 0.0F) {
			UiKit.round(g, UiKit.FILL, x - 4, y, w + 8, ROW_H, 6, argb(alpha * 0.05F * hover, 0xFFFFFF));
		}
		if (point > 0.0F) {
			UiKit.round(g, UiKit.EDGE, x - 4, y, w + 8, ROW_H, 6, argb(alpha * 0.8F * point, RED));
		}
		int bx = x + 1;
		int by = y + 2;
		float p = Mth.clamp(since / 260.0F, 0.0F, 1.0F);
		float k = on ? easeOutBack(p) : 1.0F - ease(p);
		UiKit.round(g, UiKit.EDGE, bx, by, 14, 14, 4, argb(alpha * (0.25F + 0.2F * hover), 0xFFFFFF));
		int s = Math.round(14 * Mth.clamp(k, 0.0F, 1.2F));
		if (s > 1) {
			UiKit.round(g, UiKit.FILL, bx + (14 - s) / 2, by + (14 - s) / 2, s, s, 4, argb(alpha * Mth.clamp(k, 0.0F, 1.0F), primary ? RED : BUTTON));
		}
		if (on) {
			float d = Mth.clamp((since - 90) / 220.0F, 0.0F, 1.0F);
			this.tick(g, bx + 7, by + 7, d, argb(alpha, primary ? 0xFFFFFF : BUTTON_INK));
		}
		Component label = Component.translatable(key);
		int color = primary ? lerpColor(BODY, TEXT, on ? 1.0F : hover) : lerpColor(MUTED, BODY, on ? 1.0F : hover);
		g.drawString(this.font, label, x + 24, y + 5, argb(alpha, color), false);
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
