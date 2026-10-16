package de.rcm.ballistic.client.sun;

import de.rcm.ballistic.sun.SunForgeBlockEntity;
import de.rcm.ballistic.sun.SunNetwork;
import de.rcm.ballistic.sun.SunTimeline;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The Helios launch console: the round's state, the target (typed in, or given with a target designator), range
 * and time of flight, and the launch - armed with the first press, fired with the second - or the abort while
 * the countdown still allows it.
 */
public final class SunForgeScreen extends Screen {
	private static final int W = 300;
	private static final int H = 196;
	private static final int ACCENT = 0xFFFFB347;
	private static final int TEXT = 0xFFE8E8EA;
	private static final int MUTED = 0xFF8E8E95;
	private static final int OK = 0xFF30A46C;
	private static final int BAD = 0xFFE5484D;
	private final BlockPos pos;
	private EditBox xBox;
	private EditBox zBox;
	private Button launch;
	private Button abort;
	/** Armed: the next press fires; it disarms itself after a few seconds. */
	private int armed;
	private int age;

	public SunForgeScreen(BlockPos pos) {
		super(Component.translatable("screen.ballisticmissiles.sun.title"));
		this.pos = pos;
	}

	private SunForgeBlockEntity forge() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.level.getBlockEntity(this.pos) instanceof SunForgeBlockEntity f ? f : null;
	}

	@Override
	protected void init() {
		int x0 = (this.width - W) / 2;
		int y0 = (this.height - H) / 2;
		SunForgeBlockEntity f = this.forge();
		this.xBox = new EditBox(this.font, x0 + 34, y0 + 100, 74, 18, Component.literal("X"));
		this.zBox = new EditBox(this.font, x0 + 134, y0 + 100, 74, 18, Component.literal("Z"));
		for (EditBox box : new EditBox[] {this.xBox, this.zBox}) {
			box.setMaxLength(9);
			box.setFilter(s -> s.matches("-?\\d*"));
			this.addRenderableWidget(box);
		}
		if (f != null && f.hasTarget()) {
			this.xBox.setValue(String.valueOf(f.targetX()));
			this.zBox.setValue(String.valueOf(f.targetZ()));
		}
		this.addRenderableWidget(Button.builder(Component.translatable("screen.ballisticmissiles.sun.set_target"), b -> this.setTarget())
			.bounds(x0 + 214, y0 + 99, 76, 20).build());
		this.launch = this.addRenderableWidget(Button.builder(Component.translatable("screen.ballisticmissiles.sun.launch"), b -> this.launch())
			.bounds(x0 + 10, y0 + H - 30, 186, 20).build());
		this.abort = this.addRenderableWidget(Button.builder(Component.translatable("screen.ballisticmissiles.sun.abort"), b -> this.send(SunNetwork.ACTION_ABORT, 0, 0))
			.bounds(x0 + 202, y0 + H - 30, 88, 20).build());
	}

	private void setTarget() {
		try {
			int x = Integer.parseInt(this.xBox.getValue());
			int z = Integer.parseInt(this.zBox.getValue());
			this.send(SunNetwork.ACTION_TARGET, x, z);
		} catch (NumberFormatException e) {
			// nothing typed in
		}
	}

	private void launch() {
		if (this.armed <= 0) {
			this.armed = 100;
			return;
		}
		this.armed = 0;
		this.send(SunNetwork.ACTION_LAUNCH, 0, 0);
		this.onClose();
	}

	private void send(int action, int x, int z) {
		ClientPlayNetworking.send(new SunNetwork.ActionPayload(this.pos, action, x, z));
	}

	@Override
	public void tick() {
		this.age++;
		if (this.armed > 0) {
			this.armed--;
		}
		SunForgeBlockEntity f = this.forge();
		if (f == null) {
			this.onClose();
			return;
		}
		float t = f.launchAge(0.0F);
		this.launch.active = !f.busy() && f.loaded() && f.hasTarget();
		this.launch.setMessage(Component.translatable(this.armed > 0 ? "screen.ballisticmissiles.sun.confirm" : "screen.ballisticmissiles.sun.launch"));
		this.abort.active = f.busy() && t < SunTimeline.FIRE - 20;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		int x0 = (this.width - W) / 2;
		int y0 = (this.height - H) / 2;
		float in = Mth.clamp((this.age + partialTick) / 8.0F, 0.0F, 1.0F);
		g.fill(0, 0, this.width, this.height, Math.round(in * 140.0F) << 24);
		g.fill(x0, y0, x0 + W, y0 + H, 0xF0141416);
		g.fill(x0, y0, x0 + W, y0 + 1, ACCENT);
		g.fill(x0, y0 + H - 1, x0 + W, y0 + H, 0xFF26262A);
		g.drawString(this.font, this.title, x0 + 10, y0 + 10, ACCENT, false);
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.sun.subtitle"), x0 + 10, y0 + 22, MUTED, false);
		SunForgeBlockEntity f = this.forge();
		if (f != null) {
			int y = y0 + 42;
			this.row(g, x0, y, "screen.ballisticmissiles.sun.round", Component.translatable(f.loaded() ? "screen.ballisticmissiles.sun.loaded"
				: "screen.ballisticmissiles.sun.empty"), f.loaded() ? OK : BAD);
			Component target = f.hasTarget() ? Component.literal(f.targetX() + " / " + f.targetZ()) : Component.translatable("screen.ballisticmissiles.sun.none");
			this.row(g, x0, y + 12, "screen.ballisticmissiles.sun.target", target, f.hasTarget() ? TEXT : MUTED);
			if (f.hasTarget()) {
				double d = Math.hypot(f.targetX() - this.pos.getX(), f.targetZ() - this.pos.getZ());
				int flight = Mth.clamp((int) (90 + d / 9.0), 110, 520);
				this.row(g, x0, y + 24, "screen.ballisticmissiles.sun.range",
					Component.translatable("screen.ballisticmissiles.sun.range_value", (int) d, (SunTimeline.FIRE + flight) / 20), TEXT);
			}
			float t = f.launchAge(partialTick);
			Component status;
			int color;
			if (t < 0.0F) {
				status = Component.translatable(f.loaded() && f.hasTarget() ? "screen.ballisticmissiles.sun.ready" : "screen.ballisticmissiles.sun.standby");
				color = f.loaded() && f.hasTarget() ? OK : MUTED;
			} else if (t < SunTimeline.FIRE) {
				status = Component.translatable("screen.ballisticmissiles.sun.countdown", String.format("%.1f", (SunTimeline.FIRE - t) / 20.0F));
				color = (this.age / 6) % 2 == 0 ? ACCENT : 0xFFFFD9A0;
			} else {
				status = Component.translatable("screen.ballisticmissiles.sun.fired");
				color = BAD;
			}
			this.row(g, x0, y + 36, "screen.ballisticmissiles.sun.status", status, color);
			// the coils' charge as a bar
			if (t >= 0.0F) {
				float charge = Mth.clamp((t - SunTimeline.CHARGE_FROM) / (SunTimeline.CHARGE_TO - SunTimeline.CHARGE_FROM), 0.0F, 1.0F);
				int bx = x0 + 10;
				int by = y0 + 128;
				g.fill(bx, by, bx + W - 20, by + 4, 0xFF26262A);
				g.fill(bx, by, bx + Math.round((W - 20) * charge), by + 4, 0xFF9CC4FF);
			}
		}
		g.drawString(this.font, "X", x0 + 22, y0 + 105, MUTED, false);
		g.drawString(this.font, "Z", x0 + 122, y0 + 105, MUTED, false);
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.sun.hint"), x0 + 10, y0 + 140, MUTED, false);
		if (this.armed > 0) {
			g.drawString(this.font, Component.translatable("screen.ballisticmissiles.sun.armed"), x0 + 10, y0 + 152, (this.age / 4) % 2 == 0 ? BAD : 0xFFFF9A9D, false);
		}
		super.render(g, mouseX, mouseY, partialTick);
	}

	private void row(GuiGraphics g, int x0, int y, String key, Component value, int color) {
		g.drawString(this.font, Component.translatable(key), x0 + 10, y, MUTED, false);
		g.drawString(this.font, value, x0 + 110, y, color, false);
	}
}
