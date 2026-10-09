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
 * Shown once at the start, before the title screen: this mod shows realistic violence (blood, limbs shot
 * off, bleeding to death) and is meant for adults. Go on with it all, or turn the gore off - and, ticked,
 * never be asked again (both can be changed later in the settings).
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

	@Override
	protected void init() {
		int w = Math.min(this.width - 40, 360);
		this.text = MultiLineLabel.create(this.font, Component.translatable("gore_warning.ballisticmissiles.text"), w);
		int y = this.height / 2 + 30;
		this.dontShow = this.addRenderableWidget(Checkbox.builder(Component.translatable("gore_warning.ballisticmissiles.dont_show"), this.font)
			.pos(this.width / 2 - 100, y).selected(false).build());
		y += 28;
		this.addRenderableWidget(Button.builder(Component.translatable("gore_warning.ballisticmissiles.continue"), b -> this.close(true))
			.bounds(this.width / 2 - 154, y, 150, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gore_warning.ballisticmissiles.disable"), b -> this.close(false))
			.bounds(this.width / 2 + 4, y, 150, 20).build());
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
		g.fill(0, 0, this.width, this.height, 0xFF0A0505);
		g.fillGradient(0, 0, this.width, this.height / 3, 0x40700000, 0x00000000);
		int y = this.height / 2 - 92;
		// the sign: a red triangle with the age mark
		int cx = this.width / 2;
		for (int i = 0; i < 22; i++) {
			g.fill(cx - i, y + i, cx + i + 1, y + i + 1, 0xFFB01818);
		}
		g.drawCenteredString(this.font, "18+", cx, y + 11, 0xFFFFFFFF);
		g.pose().pushMatrix();
		g.pose().translate(cx, y + 32);
		g.pose().scale(1.6F, 1.6F);
		g.drawCenteredString(this.font, this.title, 0, 0, 0xFFFF4A3A);
		g.pose().popMatrix();
		this.text.visitLines(net.minecraft.client.gui.TextAlignment.CENTER, cx, y + 56, this.font.lineHeight + 2, g.textRenderer());
		super.render(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		// drawn in render()
	}
}
