package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.client.ModConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Shown at the start, before the title screen, and meant seriously: this mod shows extreme, realistic
 * violence and is for adults only. It cannot be passed before it has been up long enough to be read, and
 * only by someone who confirms they are at least 18; anyone else turns the gore off. Ticked, it is not
 * shown again (both can be changed later in the settings).
 */
public final class GoreWarningScreen extends Screen {
	private static boolean shown;
	private final Screen next;
	private Checkbox dontShow;
	private MultiLineLabel text = MultiLineLabel.EMPTY;

	private GoreWarningScreen(Screen next) {
		super(Component.translatable("gore_warning.ballisticmissiles.title"));
		this.next = next;
	}

	/** Puts the warning up the first time the title screen appears (unless it was turned off). */
	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!shown && mc.screen instanceof TitleScreen title) {
				shown = true;
				if (ModConfig.goreWarning) {
					mc.setScreen(new GoreWarningScreen(title));
				}
			}
		});
	}

	/** Ticks the warning must stay up before it can be passed: long enough to actually be read. */
	private static final int READ_TICKS = 160;
	private Checkbox adult;
	private Button go;
	private int shownFor;

	@Override
	protected void init() {
		int w = Math.min(this.width - 40, 420);
		this.text = MultiLineLabel.create(this.font, Component.translatable("gore_warning.ballisticmissiles.text"), w);
		int textTop = Math.max(70, this.height / 2 - 120);
		int y = Math.min(this.height - 86, textTop + this.text.getLineCount() * (this.font.lineHeight + 2) + 10);
		this.adult = this.addRenderableWidget(Checkbox.builder(Component.translatable("gore_warning.ballisticmissiles.adult"), this.font)
			.pos(this.width / 2 - 150, y).selected(false).build());
		y += 22;
		this.dontShow = this.addRenderableWidget(Checkbox.builder(Component.translatable("gore_warning.ballisticmissiles.dont_show"), this.font)
			.pos(this.width / 2 - 150, y).selected(false).build());
		y += 26;
		this.go = this.addRenderableWidget(Button.builder(Component.translatable("gore_warning.ballisticmissiles.continue"), b -> this.close(true))
			.bounds(this.width / 2 - 154, y, 150, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gore_warning.ballisticmissiles.disable"), b -> this.close(false))
			.bounds(this.width / 2 + 4, y, 150, 20).build());
		this.updateGo();
	}

	@Override
	public void tick() {
		super.tick();
		this.shownFor++;
		this.updateGo();
	}

	/** Only once it has been read, and only for someone who says they are an adult. */
	private void updateGo() {
		if (this.go == null) {
			return;
		}
		int left = (READ_TICKS - this.shownFor + 19) / 20;
		this.go.active = left <= 0 && this.adult != null && this.adult.selected();
		this.go.setMessage(left > 0 ? Component.translatable("gore_warning.ballisticmissiles.continue_wait", left)
			: Component.translatable("gore_warning.ballisticmissiles.continue"));
	}

	private void close(boolean gore) {
		ModConfig.gore = gore;
		if (!gore) {
			ModConfig.blood = false;
		}
		if (this.dontShow != null && this.dontShow.selected()) {
			ModConfig.goreWarning = false;
		}
		ModConfig.save();
		Minecraft.getInstance().setScreen(this.next);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, this.width, this.height, 0xFF070303);
		// a dark red glow in from the edges, slowly pulsing
		float pulse = 0.6F + 0.4F * net.minecraft.util.Mth.sin((this.shownFor + partialTick) * 0.12F);
		int a = (int) (0x60 * pulse);
		g.fillGradient(0, 0, this.width, this.height / 4, a << 24 | 0x900000, 0x00900000);
		g.fillGradient(0, this.height * 3 / 4, this.width, this.height, 0x00900000, a << 24 | 0x900000);
		int border = 0xFF000000 | ((int) (0x70 + 0x60 * pulse) << 16);
		g.fill(0, 0, this.width, 3, border);
		g.fill(0, this.height - 3, this.width, this.height, border);
		g.fill(0, 0, 3, this.height, border);
		g.fill(this.width - 3, 0, this.width, this.height, border);
		int cx = this.width / 2;
		int top = Math.max(70, this.height / 2 - 120);
		// the sign: a red triangle with the age mark
		int y = top - 64;
		for (int i = 0; i < 26; i++) {
			g.fill(cx - i, y + i, cx + i + 1, y + i + 1, 0xFFC01414);
		}
		g.drawCenteredString(this.font, "18+", cx, y + 14, 0xFFFFFFFF);
		g.pose().pushMatrix();
		g.pose().translate(cx, top - 30);
		g.pose().scale(1.8F, 1.8F);
		g.drawCenteredString(this.font, this.title, 0, 0, 0xFFFF3A2A);
		g.pose().popMatrix();
		g.drawCenteredString(this.font, Component.translatable("gore_warning.ballisticmissiles.subtitle"), cx, top - 12, 0xFFE0B0B0);
		this.text.visitLines(net.minecraft.client.gui.TextAlignment.CENTER, cx, top, this.font.lineHeight + 2, g.textRenderer());
		super.render(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// drawn in render()
	}
}
