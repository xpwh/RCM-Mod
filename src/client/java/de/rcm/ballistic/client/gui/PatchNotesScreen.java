package de.rcm.ballistic.client.gui;

import static de.rcm.ballistic.client.gui.UiKit.approach;
import static de.rcm.ballistic.client.gui.UiKit.argb;
import static de.rcm.ballistic.client.gui.UiKit.ease;
import static de.rcm.ballistic.client.gui.UiKit.easeOutExpo;
import static de.rcm.ballistic.client.gui.UiKit.lerpColor;

import de.rcm.ballistic.client.ModConfig;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * What is new: after an update, the first time the game starts, the changes since the version last played - read from
 * the changelog that ships in the jar (CHANGELOG.md, the same one in the repository). A dark card in a calm green:
 * a "NEU" badge, the version, the notes - headings, points and sub-points, bold where the changelog has it - scrolling
 * if they are long, and a button on. Also opened by hand from the settings (/bm).
 */
public final class PatchNotesScreen extends Screen {
	private static final long OUT_MS = 380L;
	private static final int PAD = 20;
	private static final int LINE = 11;
	private static final int HEAD = 46;
	private static final int BUTTON_H = 26;
	private static final int BACKDROP = 0x05090A;
	private static final int CARD = 0x111816;
	private static final int TEXT = 0xEDEDEF;
	private static final int BODY = 0xB8BCBA;
	private static final int MUTED = 0x8E9592;
	private static final int FAINT = 0x5E6662;
	private static final int BUTTON = 0xE8E8EA;
	private static final int BUTTON_INK = 0x111113;
	private static final int GREEN = 0x3FCF8E;
	private static final int DEEP_GREEN = 0x0F4A33;
	/** At most this many versions back. */
	private static final int MAX_VERSIONS = 6;

	/** One line of the notes as drawn: its text, how far in, and whether it is a version heading. */
	private record Line(FormattedCharSequence text, int indent, boolean heading) {
	}

	/** A version's notes: the heading line ("12.38.0 – ...") and its body lines (markdown). */
	private record Section(String version, String title, List<String> body) {
	}

	private final Screen next;
	private final List<Section> sections;
	private List<Line> lines = List.of();
	private long opened = -1L;
	private long lastFrame;
	private long closingAt = -1L;
	private float scroll;
	private float scrollTarget;
	private float hoverGo;

	private PatchNotesScreen(Screen next, List<Section> sections) {
		super(Component.translatable("patch_notes.ballisticmissiles.title"));
		this.next = next;
		this.sections = sections;
	}

	/** The mod's version as installed. */
	public static String version() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("ballisticmissiles")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
	}

	/**
	 * The notes to show before {@code next} after an update (null: nothing new, or the first start ever - then only
	 * remembered). Remembers the version as seen.
	 */
	public static Screen afterUpdate(Screen next) {
		String now = version();
		String seen = ModConfig.lastSeenVersion;
		if (now.isEmpty() || now.equals(seen)) {
			return null;
		}
		ModConfig.lastSeenVersion = now;
		ModConfig.save();
		if (seen.isEmpty() || !ModConfig.patchNotes) {
			return null; // a fresh install: nothing "new" to tell yet
		}
		List<Section> since = new ArrayList<>();
		for (Section s : read()) {
			if (s.version().equals(seen) || since.size() >= MAX_VERSIONS) {
				break;
			}
			since.add(s);
		}
		return since.isEmpty() ? null : new PatchNotesScreen(next, since);
	}

	/** The latest notes, opened by hand. */
	public static Screen latest(Screen back) {
		List<Section> all = read();
		return new PatchNotesScreen(back, all.subList(0, Math.min(3, all.size())));
	}

	/** The changelog's sections, newest first ("### 12.38.0 – title" headings). */
	private static List<Section> read() {
		List<Section> out = new ArrayList<>();
		try (InputStream in = PatchNotesScreen.class.getResourceAsStream("/assets/ballisticmissiles/changelog.md")) {
			if (in == null) {
				return out;
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			Section cur = null;
			for (String l = r.readLine(); l != null; l = r.readLine()) {
				if (l.startsWith("### ")) {
					String head = l.substring(4).trim();
					int dash = head.indexOf(" – ");
					String ver = (dash > 0 ? head.substring(0, dash) : head).split(" ")[0];
					cur = new Section(ver, dash > 0 ? head.substring(dash + 3) : "", new ArrayList<>());
					out.add(cur);
				} else if (l.startsWith("## ") || l.startsWith("# ")) {
					cur = null;
				} else if (cur != null && !l.isBlank() && !l.startsWith("---")) {
					cur.body().add(l);
				}
			}
		} catch (java.io.IOException e) {
			// no notes then
		}
		return out;
	}

	@Override
	protected void init() {
		UiKit.init();
		int w = this.cardW() - 2 * PAD - 10;
		List<Line> out = new ArrayList<>();
		for (Section s : this.sections) {
			MutableComponent head = Component.literal(s.version()).withStyle(ChatFormatting.BOLD);
			if (!s.title().isEmpty()) {
				head.append(Component.literal("  " + s.title()).withStyle(Style.EMPTY.withBold(false)));
			}
			for (FormattedCharSequence f : this.font.split(head, w)) {
				out.add(new Line(f, 0, true));
			}
			for (String raw : s.body()) {
				int spaces = raw.length() - raw.stripLeading().length();
				String l = raw.strip();
				int indent = 0;
				String bullet = "";
				if (l.startsWith("- ") || l.startsWith("* ")) {
					indent = 10 + spaces / 2 * 10;
					bullet = spaces > 0 ? "◦ " : "• ";
					l = l.substring(2);
				} else if (l.matches("\\d+\\.\\s.*")) {
					indent = 10;
					bullet = l.substring(0, l.indexOf('.') + 1) + " ";
					l = l.substring(l.indexOf('.') + 1).strip();
				} else if (l.startsWith("|")) {
					if (l.matches("\\|[\\s|:-]+\\|")) {
						continue; // a table's rule
					}
					l = String.join("  ·  ", java.util.Arrays.stream(l.split("\\|")).map(String::strip).filter(c -> !c.isEmpty()).toList());
					indent = 10;
				}
				MutableComponent c = Component.literal(bullet);
				// **bold** spans
				String[] parts = l.replace("`", "").split("\\*\\*");
				for (int i = 0; i < parts.length; i++) {
					c.append(Component.literal(parts[i]).withStyle(Style.EMPTY.withBold(i % 2 == 1)));
				}
				for (FormattedCharSequence f : this.font.split(c, w - indent)) {
					out.add(new Line(f, indent, false));
				}
			}
			out.add(new Line(FormattedCharSequence.EMPTY, 0, false));
		}
		this.lines = out;
		if (this.opened < 0L) {
			this.opened = Util.getMillis();
			this.lastFrame = this.opened;
		}
	}

	private int cardW() {
		return Math.min(460, this.width - 24);
	}

	private int contentH() {
		return this.lines.size() * LINE;
	}

	private int cardH() {
		return Math.min(this.height - 20, PAD + HEAD + this.contentH() + 12 + BUTTON_H + PAD);
	}

	private int viewH() {
		return this.cardH() - PAD - HEAD - 12 - BUTTON_H - PAD;
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

	private int buttonTop() {
		return this.cardY() + this.cardH() - PAD - BUTTON_H;
	}

	private boolean overGo(double mx, double my) {
		return mx >= this.cardX() + PAD && mx < this.cardX() + this.cardW() - PAD && my >= this.buttonTop() && my < this.buttonTop() + BUTTON_H;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && this.closingAt < 0L && this.overGo(event.x(), event.y())) {
			this.close();
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
			this.close();
			return true;
		}
		if (event.isEscape()) {
			this.close();
			return true;
		}
		return super.keyPressed(event);
	}

	private void close() {
		this.closingAt = Util.getMillis();
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.2F, 0.25F));
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

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		long now = Util.getMillis();
		float dt = Math.min(0.1F, (now - this.lastFrame) / 1000.0F);
		this.lastFrame = now;
		float t = (now - this.opened) / 1000.0F;
		float out = this.closingAt < 0L ? 0.0F : Mth.clamp((now - this.closingAt) / (float) OUT_MS, 0.0F, 1.0F);
		this.scroll = approach(this.scroll, this.scrollTarget, dt * 14.0F);
		this.hoverGo = approach(this.hoverGo, out <= 0.0F && this.overGo(mouseX, mouseY) ? 1.0F : 0.0F, dt * 12.0F);

		float bg = ease(Mth.clamp(t / 0.35F, 0.0F, 1.0F)) * (1.0F - ease(out));
		g.fill(0, 0, this.width, this.height, argb(bg, BACKDROP));
		UiKit.orb(g, this.width * 0.5F - this.width * 0.55F, this.height * 0.5F - this.height * 0.7F, this.width * 1.1F, this.height * 1.4F, DEEP_GREEN,
			bg * (0.35F + 0.08F * Mth.sin(t * 1.4F)));
		float enter = easeOutExpo(Mth.clamp(t / 0.6F, 0.0F, 1.0F));
		float a = enter * (1.0F - ease(out));
		if (a <= 0.005F) {
			return;
		}
		int lift = Math.round((1.0F - enter) * 14.0F + out * 8.0F);
		int x0 = this.cardX();
		int y0 = this.cardY() + lift;
		int cw = this.cardW();
		int ch = this.cardH();
		UiKit.orb(g, x0 - 36, y0 - 18, cw + 72, ch + 60, 0x000000, 0.7F * a);
		UiKit.round(g, UiKit.FILL, x0, y0, cw, ch, 12, argb(a, CARD));
		UiKit.round(g, UiKit.EDGE, x0, y0, cw, ch, 12, argb(0.45F * a, GREEN));

		// header: the NEU badge, the title, the version now installed
		Component badge = Component.translatable("patch_notes.ballisticmissiles.badge").withStyle(ChatFormatting.BOLD);
		int bw = this.font.width(badge) + 12;
		UiKit.round(g, UiKit.FILL, x0 + PAD, y0 + PAD, bw, 15, 7, argb(a, GREEN));
		g.drawString(this.font, badge, x0 + PAD + 6, y0 + PAD + 4, argb(a, 0x08140F), false);
		g.drawString(this.font, this.title.copy().withStyle(ChatFormatting.BOLD), x0 + PAD + bw + 8, y0 + PAD + 4, argb(a, TEXT), false);
		Component ver = Component.translatable("patch_notes.ballisticmissiles.version", version());
		g.drawString(this.font, ver, x0 + PAD, y0 + PAD + 22, argb(a, MUTED), false);
		g.fill(x0 + PAD, y0 + PAD + HEAD - 8, x0 + cw - PAD, y0 + PAD + HEAD - 7, argb(a * 0.6F, 0x24352E));

		// the notes
		int vt = y0 + PAD + HEAD;
		int vh = this.viewH();
		g.enableScissor(x0 + 1, vt, x0 + cw - 1, vt + vh);
		int y = vt - Math.round(this.scroll);
		for (int i = 0; i < this.lines.size(); i++) {
			Line l = this.lines.get(i);
			if (y > vt - LINE && y < vt + vh) {
				float s = easeOutExpo(Mth.clamp((t - 0.15F - Math.min(i, 30) * 0.02F) / 0.4F, 0.0F, 1.0F));
				int color = l.heading() ? GREEN : BODY;
				g.drawString(this.font, l.text(), x0 + PAD + l.indent() + Math.round((1.0F - s) * 6.0F), y, argb(a * s, color), false);
			}
			y += LINE;
		}
		g.disableScissor();
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

		// the button on
		int by = this.buttonTop() + lift;
		UiKit.round(g, UiKit.FILL, x0 + PAD, by, cw - 2 * PAD, BUTTON_H, 9, argb(a, lerpColor(BUTTON, 0xFFFFFF, this.hoverGo)));
		Component go = Component.translatable("patch_notes.ballisticmissiles.continue").withStyle(ChatFormatting.BOLD);
		g.drawString(this.font, go, x0 + (cw - this.font.width(go)) / 2, by + 9, argb(a, BUTTON_INK), false);
		Component hint = Component.translatable("patch_notes.ballisticmissiles.hint");
		g.drawString(this.font, hint, (this.width - this.font.width(hint)) / 2, Math.min(this.height - 10, y0 + ch + 6), argb(a * 0.9F, FAINT), false);
	}
}
