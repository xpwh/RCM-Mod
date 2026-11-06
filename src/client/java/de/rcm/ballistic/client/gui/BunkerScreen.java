package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.network.ModNetworking.BunkerBuildPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Asked when a bunker kit is placed (and whenever it is right-clicked): which shelter to build - the basic
 * one, a single room behind an airlock, or the luxury one, a whole underground home. Closing it leaves the
 * kit standing for later.
 */
public final class BunkerScreen extends Screen {
	private static final int CARD_W = 180;
	private static final int CARD_H = 150;
	private final BlockPos pos;
	private MultiLineLabel basic = MultiLineLabel.EMPTY;
	private MultiLineLabel luxury = MultiLineLabel.EMPTY;

	public BunkerScreen(BlockPos pos) {
		super(Component.translatable("screen.ballisticmissiles.bunker.title"));
		this.pos = pos;
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int top = this.height / 2 - CARD_H / 2;
		this.basic = MultiLineLabel.create(this.font, Component.translatable("screen.ballisticmissiles.bunker.basic.text"), CARD_W - 16);
		this.luxury = MultiLineLabel.create(this.font, Component.translatable("screen.ballisticmissiles.bunker.luxury.text"), CARD_W - 16);
		this.addRenderableWidget(Button.builder(Component.translatable("screen.ballisticmissiles.bunker.basic"), b -> this.choose(false))
			.bounds(cx - CARD_W - 6, top + CARD_H - 26, CARD_W - 12, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("screen.ballisticmissiles.bunker.luxury"), b -> this.choose(true))
			.bounds(cx + 12, top + CARD_H - 26, CARD_W - 12, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose())
			.bounds(cx - 50, top + CARD_H + 10, 100, 20).build());
	}

	private void choose(boolean luxury) {
		ClientPlayNetworking.send(new BunkerBuildPayload(this.pos, luxury));
		this.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		int cx = this.width / 2;
		int top = this.height / 2 - CARD_H / 2;
		g.drawCenteredString(this.font, this.title, cx, top - 22, 0xFFFFFFFF);
		this.card(g, cx - CARD_W - 12, top, 0xFF8A8A84, "screen.ballisticmissiles.bunker.basic", this.basic);
		this.card(g, cx + 6, top, 0xFFE8C060, "screen.ballisticmissiles.bunker.luxury", this.luxury);
	}

	private void card(GuiGraphics g, int x, int y, int accent, String key, MultiLineLabel text) {
		g.fill(x, y, x + CARD_W, y + CARD_H, 0xC0101012);
		g.fill(x, y, x + CARD_W, y + 2, accent);
		g.drawString(this.font, Component.translatable(key), x + 8, y + 8, accent);
		text.visitLines(net.minecraft.client.gui.TextAlignment.LEFT, x + 8, y + 24, this.font.lineHeight + 1, g.textRenderer());
	}
}
