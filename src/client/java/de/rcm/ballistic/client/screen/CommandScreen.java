package de.rcm.ballistic.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.item.AirstrikeRadioItem;
import de.rcm.ballistic.network.ModNetworking.CommandActionPayload;
import de.rcm.ballistic.network.ModNetworking.CommandDataPayload;
import de.rcm.ballistic.network.ModNetworking.SiteInfo;
import de.rcm.ballistic.network.ModNetworking.TrackInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Command center situation map: terrain around the center, all defense sites, every track in the air
 * with its velocity leader and predicted impact, the player, and the selected target. Click the map
 * to pick a target, then fire the linked launchers or call in an air strike / fire mission.
 */
public class CommandScreen extends Screen {
	private static final Identifier MAP_ID = BallisticMissiles.id("dynamic/command_map");
	private static final int TEX = 256;
	private static final int[] RANGES = {600, 300, 150};
	private static final int PANEL = 190;

	private final BlockPos center;
	private CommandDataPayload data;
	private DynamicTexture texture;
	private int zoom;
	private boolean mapDirty = true;
	private int mapLeft;
	private int mapTop;
	private int mapSize;
	private Integer targetX;
	private Integer targetZ;
	private int mode;
	private long confirmFireUntil;

	public CommandScreen(CommandDataPayload data) {
		super(Component.translatable("screen.ballisticmissiles.command"));
		this.center = data.center();
		this.data = data;
	}

	public BlockPos centerPos() {
		return this.center;
	}

	public void update(CommandDataPayload data) {
		boolean relabel = data.links() != this.data.links() || data.cooldown() != this.data.cooldown();
		this.data = data;
		if (relabel) {
			this.rebuildWidgets();
		}
	}

	private int range() {
		return Math.min(RANGES[this.zoom], this.data.range());
	}

	@Override
	protected void init() {
		if (this.texture == null) {
			this.texture = new DynamicTexture(() -> "ballisticmissiles command map", TEX, TEX, false);
			this.minecraft.getTextureManager().register(MAP_ID, this.texture);
			this.mapDirty = true;
		}
		this.mapSize = Mth.clamp(Math.min(this.height - 24, this.width - PANEL - 24), 120, 420);
		this.mapLeft = Math.max(8, (this.width - this.mapSize - PANEL - 12) / 2);
		this.mapTop = (this.height - this.mapSize) / 2;
		int px = this.mapLeft + this.mapSize + 12;
		int y = this.mapTop + 40;
		AirstrikeRadioItem.Mode[] modes = AirstrikeRadioItem.Mode.values();
		boolean hasTarget = this.targetX != null;

		boolean confirm = System.currentTimeMillis() < this.confirmFireUntil;
		Button fire = Button.builder(Component.translatable(confirm ? "screen.ballisticmissiles.command_fire_confirm" : "screen.ballisticmissiles.command_fire", this.data.links()), b -> {
				if (System.currentTimeMillis() < this.confirmFireUntil) {
					this.send(CommandActionPayload.FIRE_LINKED);
					this.confirmFireUntil = 0;
				} else {
					this.confirmFireUntil = System.currentTimeMillis() + 3000;
				}
				this.rebuildWidgets();
			})
			.bounds(px, y, PANEL - 10, 20).build();
		fire.active = hasTarget && this.data.links() > 0;
		this.addRenderableWidget(fire);
		y += 22;
		Button abort = Button.builder(Component.translatable("screen.ballisticmissiles.command_abort"), b -> this.send(CommandActionPayload.ABORT_LINKED))
			.bounds(px, y, PANEL - 10, 20).build();
		abort.active = this.data.links() > 0;
		this.addRenderableWidget(abort);
		y += 30;
		this.addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
			this.mode = (this.mode + modes.length - 1) % modes.length;
			this.rebuildWidgets();
		}).bounds(px, y, 20, 20).build());
		this.addRenderableWidget(Button.builder(modes[this.mode].displayName(), b -> {
			this.mode = (this.mode + 1) % modes.length;
			this.rebuildWidgets();
		}).bounds(px + 22, y, PANEL - 54, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
			this.mode = (this.mode + 1) % modes.length;
			this.rebuildWidgets();
		}).bounds(px + PANEL - 30, y, 20, 20).build());
		y += 22;
		Button strike = Button.builder(this.data.cooldown() > 0
				? Component.translatable("screen.ballisticmissiles.command_cooldown", this.data.cooldown())
				: Component.translatable("screen.ballisticmissiles.command_strike"), b -> this.send(CommandActionPayload.AIRSTRIKE))
			.bounds(px, y, PANEL - 10, 20).build();
		strike.active = hasTarget && this.data.cooldown() <= 0;
		this.addRenderableWidget(strike);
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.setZoom(this.zoom + 1)).bounds(this.mapLeft + 4, this.mapTop + 4, 16, 16).build());
		this.addRenderableWidget(Button.builder(Component.literal("−"), b -> this.setZoom(this.zoom - 1)).bounds(this.mapLeft + 4, this.mapTop + 22, 16, 16).build());
	}

	private void setZoom(int zoom) {
		int z = Mth.clamp(zoom, 0, RANGES.length - 1);
		if (z != this.zoom) {
			this.zoom = z;
			this.mapDirty = true;
		}
	}

	private void send(int action) {
		if (this.targetX == null && action != CommandActionPayload.ABORT_LINKED) {
			return;
		}
		int x = this.targetX == null ? 0 : this.targetX;
		int z = this.targetZ == null ? 0 : this.targetZ;
		ClientPlayNetworking.send(new CommandActionPayload(this.center, action, x, z, this.mode));
	}

	@Override
	public void tick() {
		super.tick();
		if (this.minecraft.level != null && this.minecraft.level.getGameTime() % 100 == 0) {
			this.mapDirty = true; // chunks around may have loaded meanwhile
		}
		if (this.confirmFireUntil != 0 && System.currentTimeMillis() > this.confirmFireUntil) {
			this.confirmFireUntil = 0;
			this.rebuildWidgets();
		}
	}

	@Override
	public void removed() {
		ClientPlayNetworking.send(new CommandActionPayload(this.center, CommandActionPayload.CLOSE, 0, 0, 0));
		this.minecraft.getTextureManager().release(MAP_ID);
		this.texture = null;
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ map

	private double screenX(double worldX) {
		return this.mapLeft + (worldX - (this.center.getX() + 0.5) + this.range()) / (2.0 * this.range()) * this.mapSize;
	}

	private double screenY(double worldZ) {
		return this.mapTop + (worldZ - (this.center.getZ() + 0.5) + this.range()) / (2.0 * this.range()) * this.mapSize;
	}

	private boolean overMap(double mx, double my) {
		return mx >= this.mapLeft && my >= this.mapTop && mx < this.mapLeft + this.mapSize && my < this.mapTop + this.mapSize;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (this.overMap(event.x(), event.y())) {
			double k = 2.0 * this.range() / this.mapSize;
			this.targetX = Mth.floor(this.center.getX() + 0.5 - this.range() + (event.x() - this.mapLeft) * k);
			this.targetZ = Mth.floor(this.center.getZ() + 0.5 - this.range() + (event.y() - this.mapTop) * k);
			this.confirmFireUntil = 0;
			this.rebuildWidgets();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (this.overMap(mx, my)) {
			this.setZoom(this.zoom + (scrollY > 0 ? 1 : -1));
			return true;
		}
		return super.mouseScrolled(mx, my, scrollX, scrollY);
	}

	private void rebuildMap() {
		NativeImage pixels = this.texture.getPixels();
		ClientLevel level = this.minecraft.level;
		if (pixels == null || level == null) {
			return;
		}
		double step = 2.0 * this.range() / TEX;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		double x0 = this.center.getX() + 0.5 - this.range();
		double z0 = this.center.getZ() + 0.5 - this.range();
		int[] prev = new int[TEX];
		for (int py = -1; py < TEX; py++) {
			int wz = Mth.floor(z0 + py * step);
			for (int px = 0; px < TEX; px++) {
				int wx = Mth.floor(x0 + px * step);
				int color;
				int h = Integer.MIN_VALUE;
				if (level.hasChunk(wx >> 4, wz >> 4)) {
					h = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
					m.set(wx, h - 1, wz);
					BlockState state = level.getBlockState(m);
					MapColor mc = state.getMapColor(level, m);
					MapColor.Brightness b = prev[px] == Integer.MIN_VALUE || h == prev[px] ? MapColor.Brightness.NORMAL
						: h > prev[px] ? MapColor.Brightness.HIGH : MapColor.Brightness.LOW;
					if (mc == MapColor.WATER) {
						b = MapColor.Brightness.NORMAL;
					}
					int c = mc == MapColor.NONE ? 0xFF202020 : mc.calculateARGBColor(b);
					// darken and tint green like a military display
					int r = (c >> 16 & 255) * 5 / 10;
					int g = (c >> 8 & 255) * 6 / 10 + 10;
					int bl = (c & 255) * 5 / 10;
					color = 0xFF000000 | r << 16 | g << 8 | bl;
				} else {
					color = ((wx >> 5) + (wz >> 5) & 1) == 0 ? 0xFF0C120E : 0xFF0F1611;
				}
				prev[px] = h;
				if (py >= 0) {
					pixels.setPixel(px, py, color);
				}
			}
		}
		this.texture.upload();
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (this.texture != null && this.mapDirty) {
			this.rebuildMap();
			this.mapDirty = false;
		}
		g.fill(this.mapLeft - 6, this.mapTop - 6, this.mapLeft + this.mapSize + PANEL + 6, this.mapTop + this.mapSize + 6, 0xF0101410);
		g.fill(this.mapLeft - 6, this.mapTop - 6, this.mapLeft + this.mapSize + PANEL + 6, this.mapTop - 4, 0xFF2E5A36);
		if (this.texture != null) {
			g.blit(RenderPipelines.GUI_TEXTURED, MAP_ID, this.mapLeft, this.mapTop, 0, 0, this.mapSize, this.mapSize, TEX, TEX, TEX, TEX);
		}
		g.enableScissor(this.mapLeft, this.mapTop, this.mapLeft + this.mapSize, this.mapTop + this.mapSize);
		this.drawGrid(g);
		this.drawOverlay(g);
		g.disableScissor();
		super.render(g, mouseX, mouseY, partialTick);
		this.drawPanel(g, mouseX, mouseY);
	}

	private void drawGrid(GuiGraphics g) {
		int cx = (int) this.screenX(this.center.getX() + 0.5);
		int cy = (int) this.screenY(this.center.getZ() + 0.5);
		for (int ring = 1; ring <= 4; ring++) {
			int r = (int) (this.mapSize / 2.0 * ring / 4.0);
			this.circle(g, cx, cy, r, 0x6040FF70);
			g.drawString(this.font, String.valueOf(this.range() * ring / 4), cx + 2, cy - r + 1, 0xA040FF70, false);
		}
		g.fill(cx, this.mapTop, cx + 1, this.mapTop + this.mapSize, 0x3040FF70);
		g.fill(this.mapLeft, cy, this.mapLeft + this.mapSize, cy + 1, 0x3040FF70);
		g.drawCenteredString(this.font, "N", cx, this.mapTop + 3, 0xFF40FF70);
	}

	private void drawOverlay(GuiGraphics g) {
		for (SiteInfo site : this.data.sites()) {
			int x = (int) this.screenX(site.pos().getX() + 0.5);
			int y = (int) this.screenY(site.pos().getZ() + 0.5);
			int color = switch (site.kind()) {
				case 0 -> 0xFFFFFFFF; // radar
				case 1 -> 0xFF50E0FF; // air defense / Iron Dome
				case 2 -> 0xFFFFE040; // silo
				default -> 0xFFC060FF; // jammer
			};
			g.fill(x - 3, y - 3, x + 3, y + 3, 0xFF000000);
			g.fill(x - 2, y - 2, x + 2, y + 2, color);
		}
		// the center itself
		int ccx = (int) this.screenX(this.center.getX() + 0.5);
		int ccy = (int) this.screenY(this.center.getZ() + 0.5);
		g.fill(ccx - 3, ccy - 3, ccx + 4, ccy + 4, 0xFF40FF70);
		g.fill(ccx - 1, ccy - 1, ccx + 2, ccy + 2, 0xFF000000);
		for (TrackInfo t : this.data.tracks()) {
			int x = (int) this.screenX(t.x());
			int y = (int) this.screenY(t.z());
			boolean aircraft = t.threatClass() == AirThreat.ThreatClass.AIRCRAFT.ordinal();
			int color = !t.threat() ? 0xFF4090FF : aircraft ? 0xFFFFB030 : 0xFFFF4040;
			double k = this.mapSize / (2.0 * this.range());
			this.line(g, x, y, x + (int) (t.vx() * 80 * k), y + (int) (t.vz() * 80 * k), color & 0xB0FFFFFF);
			if (aircraft) {
				g.fill(x - 2, y - 2, x + 3, y + 3, color);
			} else {
				g.fill(x - 1, y - 2, x + 2, y + 3, color);
				g.fill(x - 2, y - 1, x + 3, y + 2, color);
			}
			g.drawString(this.font, String.format("T%02d", t.number()), x + 4, y - 9, color, false);
			if (t.threat() && !aircraft) {
				int ix = (int) this.screenX(t.impactX());
				int iy = (int) this.screenY(t.impactZ());
				this.line(g, ix - 3, iy - 3, ix + 3, iy + 3, 0xFFFF4040);
				this.line(g, ix - 3, iy + 3, ix + 3, iy - 3, 0xFFFF4040);
				this.line(g, x, y, ix, iy, 0x40FF4040);
			}
		}
		if (this.minecraft.player != null) {
			int x = (int) this.screenX(this.minecraft.player.getX());
			int y = (int) this.screenY(this.minecraft.player.getZ());
			g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
			g.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFFFFF);
		}
		if (this.targetX != null) {
			int tx = (int) this.screenX(this.targetX + 0.5);
			int ty = (int) this.screenY(this.targetZ + 0.5);
			g.fill(tx - 7, ty, tx + 8, ty + 1, 0xFFFF3030);
			g.fill(tx, ty - 7, tx + 1, ty + 8, 0xFFFF3030);
			this.circle(g, tx, ty, 5, 0xFFFF3030);
		}
	}

	private void drawPanel(GuiGraphics g, int mouseX, int mouseY) {
		int px = this.mapLeft + this.mapSize + 12;
		int y = this.mapTop;
		g.drawString(this.font, this.title, px, y, 0xFF40FF70);
		g.drawString(this.font, String.format("%d / %d", this.center.getX(), this.center.getZ()), px, y + 11, 0xFF1C7A34, false);
		Component tgt = this.targetX == null
			? Component.translatable("screen.ballisticmissiles.command_pick")
			: Component.translatable("screen.ballisticmissiles.command_target", this.targetX, this.targetZ);
		g.drawString(this.font, tgt, px, y + 24, this.targetX == null ? 0xFF9AA0A6 : 0xFFFF6060, false);
		if (this.overMap(mouseX, mouseY)) {
			double k = 2.0 * this.range() / this.mapSize;
			int wx = Mth.floor(this.center.getX() + 0.5 - this.range() + (mouseX - this.mapLeft) * k);
			int wz = Mth.floor(this.center.getZ() + 0.5 - this.range() + (mouseY - this.mapTop) * k);
			g.drawString(this.font, String.format("%d / %d", wx, wz), this.mapLeft + 4, this.mapTop + this.mapSize - 12, 0xFFB0C8B0, false);
		}
		int line = this.mapTop + 40 + 22 + 30 + 22 + 28;
		List<TrackInfo> sorted = new ArrayList<>(this.data.tracks());
		sorted.sort(Comparator.comparing((TrackInfo t) -> !t.threat()).thenComparingInt(TrackInfo::eta));
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.command_tracks", sorted.size()), px, line, 0xFF40FF70, false);
		line += 12;
		for (TrackInfo t : sorted) {
			if (line > this.mapTop + this.mapSize - 10) {
				break;
			}
			boolean aircraft = t.threatClass() == AirThreat.ThreatClass.AIRCRAFT.ordinal();
			int color = !t.threat() ? 0xFF4090FF : aircraft ? 0xFFFFB030 : 0xFFFF4040;
			Component name = t.nameKey().isEmpty() ? Component.literal("?") : Component.translatable(t.nameKey());
			String eta = aircraft ? "" : "  " + Math.max(0, t.eta() / 20) + "s";
			g.drawString(this.font, Component.literal(String.format("T%02d ", t.number())).append(name).append(eta), px, line, color, false);
			line += 10;
		}
	}

	private void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.min(600, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
		for (int i = 0; i <= steps; i++) {
			float f = steps == 0 ? 0 : (float) i / steps;
			int x = Math.round(Mth.lerp(f, x0, x1));
			int y = Math.round(Mth.lerp(f, y0, y1));
			g.fill(x, y, x + 1, y + 1, color);
		}
	}

	private void circle(GuiGraphics g, int cx, int cy, int r, int color) {
		int n = Math.max(16, r * 3);
		for (int i = 0; i < n; i++) {
			double a = Mth.TWO_PI * i / n;
			int x = cx + (int) Math.round(Math.cos(a) * r);
			int y = cy + (int) Math.round(Math.sin(a) * r);
			g.fill(x, y, x + 1, y + 1, color);
		}
	}
}
