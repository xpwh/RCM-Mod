package de.rcm.ballistic.client.screen;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.block.JammerBlockEntity;
import de.rcm.ballistic.item.TargetData;
import de.rcm.ballistic.network.ModNetworking.JammerPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The jammer's panel: switch it on or off, choose between plain jamming (missiles miss by tens of
 * blocks) and GPS spoofing (missiles are sent to a point you choose), set that point by hand or take
 * it from the target designator you carry, and whether your own drones are spared.
 */
public class JammerScreen extends Screen {
	private static final int W = 220;
	private final BlockPos pos;
	private boolean active;
	private int mode;
	private boolean spareOwn;
	private String spoofX;
	private String spoofZ;
	private boolean hasSpoof;
	private EditBox xBox;
	private EditBox zBox;

	public JammerScreen(JammerPayload p) {
		super(Component.translatable("screen.ballisticmissiles.jammer"));
		this.pos = p.pos();
		this.active = p.active();
		this.mode = p.mode();
		this.spareOwn = p.spareOwn();
		this.hasSpoof = p.hasSpoof();
		this.spoofX = Integer.toString(p.spoofX());
		this.spoofZ = Integer.toString(p.spoofZ());
	}

	@Override
	protected void init() {
		if (this.xBox != null) {
			this.spoofX = this.xBox.getValue();
			this.spoofZ = this.zBox.getValue();
		}
		int left = (this.width - W) / 2;
		int y = this.height / 2 - 82;
		this.addRenderableWidget(Button.builder(Component.translatable(this.active ? "screen.ballisticmissiles.jammer_on" : "screen.ballisticmissiles.jammer_off"),
			b -> {
				this.active = !this.active;
				this.rebuildWidgets();
			}).bounds(left, y, W, 20).build());
		y += 24;
		this.addRenderableWidget(Button.builder(Component.translatable(this.mode == JammerBlockEntity.MODE_SPOOF
			? "screen.ballisticmissiles.jammer_mode_spoof" : "screen.ballisticmissiles.jammer_mode_jam"), b -> {
				this.mode = this.mode == JammerBlockEntity.MODE_SPOOF ? JammerBlockEntity.MODE_JAM : JammerBlockEntity.MODE_SPOOF;
				this.rebuildWidgets();
			}).bounds(left, y, W, 20).build());
		y += 36;
		this.xBox = new EditBox(this.font, left + 20, y, 70, 18, Component.literal("X"));
		this.xBox.setFilter(s -> s.matches("-?\\d{0,8}"));
		this.xBox.setValue(this.spoofX);
		this.zBox = new EditBox(this.font, left + 120, y, 70, 18, Component.literal("Z"));
		this.zBox.setFilter(s -> s.matches("-?\\d{0,8}"));
		this.zBox.setValue(this.spoofZ);
		boolean spoofing = this.mode == JammerBlockEntity.MODE_SPOOF;
		this.xBox.setEditable(spoofing);
		this.zBox.setEditable(spoofing);
		this.addRenderableWidget(this.xBox);
		this.addRenderableWidget(this.zBox);
		y += 22;
		Button fromDesignator = Button.builder(Component.translatable("screen.ballisticmissiles.jammer_from_designator"), b -> {
			TargetData t = this.designatorTarget();
			if (t != null) {
				this.xBox.setValue(Integer.toString(t.pos().getX()));
				this.zBox.setValue(Integer.toString(t.pos().getZ()));
				this.hasSpoof = true;
			}
		}).bounds(left, y, W, 20).build();
		fromDesignator.active = spoofing && this.designatorTarget() != null;
		this.addRenderableWidget(fromDesignator);
		y += 24;
		this.addRenderableWidget(Button.builder(Component.translatable(this.spareOwn ? "screen.ballisticmissiles.jammer_spare_on"
			: "screen.ballisticmissiles.jammer_spare_off"), b -> {
				this.spareOwn = !this.spareOwn;
				this.rebuildWidgets();
			}).bounds(left, y, W, 20).build());
		y += 30;
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(left + W / 2 - 60, y, 120, 20).build());
	}

	/** The target set on the first target designator you carry. */
	private TargetData designatorTarget() {
		if (this.minecraft == null || this.minecraft.player == null) {
			return null;
		}
		for (ItemStack s : this.minecraft.player.getInventory()) {
			if (s.is(ModRegistry.TARGET_DESIGNATOR) && s.get(ModRegistry.TARGET) != null) {
				return s.get(ModRegistry.TARGET);
			}
		}
		return null;
	}

	@Override
	public void onClose() {
		int x;
		int z;
		boolean spoof = this.mode == JammerBlockEntity.MODE_SPOOF;
		try {
			x = Integer.parseInt(this.xBox.getValue());
			z = Integer.parseInt(this.zBox.getValue());
		} catch (NumberFormatException e) {
			x = this.pos.getX();
			z = this.pos.getZ();
			spoof = false;
		}
		ClientPlayNetworking.send(new JammerPayload(this.pos, this.active, this.mode, spoof, x, z, this.spareOwn));
		super.onClose();
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		int left = (this.width - W) / 2;
		int y = this.height / 2 - 82;
		g.drawCenteredString(this.font, this.title, this.width / 2, y - 26, 0xFFC080FF);
		g.drawCenteredString(this.font, Component.translatable("screen.ballisticmissiles.jammer_info", (int) JammerBlockEntity.RADIUS,
			(int) JammerBlockEntity.DRONE_RADIUS), this.width / 2, y - 13, 0xFFA0A0A0);
		boolean spoofing = this.mode == JammerBlockEntity.MODE_SPOOF;
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.jammer_spoof_point", (int) JammerBlockEntity.SPOOF_RANGE), left, y + 50,
			spoofing ? 0xFFE0E0E0 : 0xFF707070, false);
		g.drawString(this.font, "X", left + 8, y + 65, spoofing ? 0xFFE0E0E0 : 0xFF707070, false);
		g.drawString(this.font, "Z", left + 108, y + 65, spoofing ? 0xFFE0E0E0 : 0xFF707070, false);
		g.drawCenteredString(this.font, Component.translatable(spoofing ? "screen.ballisticmissiles.jammer_explain_spoof" : "screen.ballisticmissiles.jammer_explain_jam"),
			this.width / 2, y + 160, 0xFFB0B0B0);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
