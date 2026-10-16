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
 * <p>
 * 2.0: tracks move live between the updates (carried on along their velocity), friend and foe in NATO
 * symbols (blue circle friendly, red diamond hostile, with the kind of target and its altitude), the chance
 * the defenses bring each hostile down, and a 3D view - the terrain tilted, every flight path drawn at its
 * height with the predicted arc down to impact - that can be dragged round.
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
	/** The list in the panel: tracks in the air, or the state of the sites. */
	private boolean showSites;
	/** 3D view: on, and its viewing angles (turned by dragging, slowly by itself otherwise). */
	private boolean view3d;
	private float yaw3d = 0.6F;
	private float pitch3d = 0.62F;
	private long lastDrag;
	/** When the last update came in: the tracks are carried on along their velocity from there. */
	private long receivedAt = System.currentTimeMillis();
	/** Where each track has been (entity id -> x, y, z points, oldest first): its flight path so far. */
	private final java.util.Map<Integer, java.util.ArrayDeque<float[]>> trails = new java.util.HashMap<>();

	public CommandScreen(CommandDataPayload data) {
		super(Component.translatable("screen.ballisticmissiles.command"));
		this.center = data.center();
		this.data = data;
		this.recordTrails();
	}

	public BlockPos centerPos() {
		return this.center;
	}

	public void update(CommandDataPayload data) {
		boolean relabel = data.links() != this.data.links() || data.cooldown() != this.data.cooldown();
		this.data = data;
		this.receivedAt = System.currentTimeMillis();
		this.recordTrails();
		if (relabel) {
			this.rebuildWidgets();
		}
	}

	private void recordTrails() {
		java.util.Set<Integer> live = new java.util.HashSet<>();
		for (TrackInfo t : this.data.tracks()) {
			if (t.entityId() < 0) {
				continue;
			}
			live.add(t.entityId());
			var trail = this.trails.computeIfAbsent(t.entityId(), k -> new java.util.ArrayDeque<>());
			float[] last = trail.peekLast();
			if (last == null || Math.abs(last[0] - t.x()) + Math.abs(last[2] - t.z()) + Math.abs(last[1] - t.y()) > 1.0F) {
				trail.addLast(new float[] {t.x(), t.y(), t.z()});
				while (trail.size() > 120) {
					trail.removeFirst();
				}
			}
		}
		this.trails.keySet().retainAll(live);
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
		y += 28;
		this.addRenderableWidget(Button.builder(Component.translatable(this.showSites ? "screen.ballisticmissiles.command_list_sites"
			: "screen.ballisticmissiles.command_list_tracks"), b -> {
			this.showSites = !this.showSites;
			this.rebuildWidgets();
		}).bounds(px, y, PANEL - 10, 16).build());
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> this.setZoom(this.zoom + 1)).bounds(this.mapLeft + 4, this.mapTop + 4, 16, 16).build());
		this.addRenderableWidget(Button.builder(Component.literal("−"), b -> this.setZoom(this.zoom - 1)).bounds(this.mapLeft + 4, this.mapTop + 22, 16, 16).build());
		this.addRenderableWidget(Button.builder(Component.literal(this.view3d ? "2D" : "3D"), b -> {
			this.view3d = !this.view3d;
			this.rebuildWidgets();
		}).bounds(this.mapLeft + 4, this.mapTop + 44, 22, 16).build());
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
		if (this.overMap(event.x(), event.y()) && event.button() == 0) {
			if (this.view3d) {
				double[] ground = this.unproject(event.x(), event.y());
				this.targetX = Mth.floor(ground[0]);
				this.targetZ = Mth.floor(ground[1]);
				this.confirmFireUntil = 0;
				this.rebuildWidgets();
				return true;
			}
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
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (this.view3d && this.overMap(event.x(), event.y()) && event.button() == 1) {
			this.yaw3d += (float) dx * 0.01F;
			this.pitch3d = Mth.clamp(this.pitch3d - (float) dy * 0.01F, 0.2F, 1.45F);
			this.lastDrag = System.currentTimeMillis();
			return true;
		}
		return super.mouseDragged(event, dx, dy);
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
		g.enableScissor(this.mapLeft, this.mapTop, this.mapLeft + this.mapSize, this.mapTop + this.mapSize);
		if (this.view3d) {
			g.fill(this.mapLeft, this.mapTop, this.mapLeft + this.mapSize, this.mapTop + this.mapSize, 0xFF070B08);
			this.draw3d(g);
		} else {
			if (this.texture != null) {
				g.blit(RenderPipelines.GUI_TEXTURED, MAP_ID, this.mapLeft, this.mapTop, 0, 0, this.mapSize, this.mapSize, TEX, TEX, TEX, TEX);
			}
			this.drawGrid(g);
			this.drawOverlay(g);
		}
		this.drawAlarmAndLegend(g);
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
		boolean blink = (System.currentTimeMillis() / 400L) % 2 == 0;
		for (SiteInfo site : this.data.sites()) {
			int x = (int) this.screenX(site.pos().getX() + 0.5);
			int y = (int) this.screenY(site.pos().getZ() + 0.5);
			if ((site.flags() & SiteInfo.DESTROYED) != 0) {
				// a red cross where it was
				this.line(g, x - 3, y - 3, x + 3, y + 3, 0xFFFF2020);
				this.line(g, x - 3, y + 3, x + 3, y - 3, 0xFFFF2020);
				continue;
			}
			int color = switch (site.kind()) {
				case 0 -> 0xFFFFFFFF; // radar
				case 1 -> 0xFF50E0FF; // air defense / Iron Dome
				case 2 -> 0xFFFFE040; // silo
				default -> 0xFFC060FF; // jammer
			};
			this.siteSymbol(g, x, y, site, color, blink);
		}
		// where everything has flown: the path so far, fading towards its start
		for (var e : this.trails.entrySet()) {
			TrackInfo t = null;
			for (TrackInfo c : this.data.tracks()) {
				if (c.entityId() == e.getKey()) {
					t = c;
					break;
				}
			}
			if (t == null) {
				continue;
			}
			boolean aircraft = t.threatClass() == AirThreat.ThreatClass.AIRCRAFT.ordinal();
			int rgb = (!t.threat() ? 0x4090FF : aircraft ? 0xFFB030 : 0xFF4040);
			float[] prev = null;
			int i = 0;
			int n = e.getValue().size();
			for (float[] p : e.getValue()) {
				if (prev != null) {
					int alpha = 0x30 + 0x70 * i / Math.max(1, n);
					this.line(g, (int) this.screenX(prev[0]), (int) this.screenY(prev[2]), (int) this.screenX(p[0]), (int) this.screenY(p[2]), alpha << 24 | rgb);
				}
				prev = p;
				i++;
			}
		}
		// the center itself
		int ccx = (int) this.screenX(this.center.getX() + 0.5);
		int ccy = (int) this.screenY(this.center.getZ() + 0.5);
		g.fill(ccx - 3, ccy - 3, ccx + 4, ccy + 4, 0xFF40FF70);
		g.fill(ccx - 1, ccy - 1, ccx + 2, ccy + 2, 0xFF000000);
		for (TrackInfo t : this.data.tracks()) {
			int x = (int) this.screenX(this.liveX(t));
			int y = (int) this.screenY(this.liveZ(t));
			boolean aircraft = t.threatClass() == AirThreat.ThreatClass.AIRCRAFT.ordinal();
			int color = trackColor(t);
			double k = this.mapSize / (2.0 * this.range());
			this.line(g, x, y, x + (int) (t.vx() * 80 * k), y + (int) (t.vz() * 80 * k), color & 0xB0FFFFFF);
			this.symbol(g, x, y, t);
			this.label(g, x + 7, y - 10, t);
			if (t.threat() && !aircraft) {
				int ix = (int) this.screenX(t.impactX());
				int iy = (int) this.screenY(t.impactZ());
				this.line(g, ix - 3, iy - 3, ix + 3, iy + 3, 0xFFFF4040);
				this.line(g, ix - 3, iy + 3, ix + 3, iy - 3, 0xFFFF4040);
				this.dashed(g, x, y, ix, iy, 0x90FF4040);
				// the danger zone round the impact point, pulsing, and the time to impact
				float pulse = (System.currentTimeMillis() % 1000L) / 1000.0F;
				int zone = Math.max(4, (int) (40.0 * this.mapSize / (2.0 * this.range())));
				this.circle(g, ix, iy, zone, 0x80FF4040);
				this.circle(g, ix, iy, (int) (zone * (0.4F + 0.6F * pulse)), ((int) (0xA0 * (1.0F - pulse)) << 24) | 0xFF4040);
				g.drawString(this.font, Math.max(0, t.eta() / 20) + "s", ix + 5, iy + 2, 0xFFFF6060, false);
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
		int line = this.mapTop + 40 + 22 + 30 + 22 + 28 + 20;
		// batteries out of rounds with nothing to reload from
		for (SiteInfo site : this.data.sites()) {
			if (site.ammo() == 0 && site.spares() == 0 && (site.flags() & (SiteInfo.RELOADING | SiteInfo.DESTROYED)) == 0) {
				g.drawString(this.font, Component.translatable("screen.ballisticmissiles.command_battery_empty", this.siteName(site), site.pos().getX(),
					site.pos().getZ()), px, line, (System.currentTimeMillis() / 500L) % 2 == 0 ? 0xFFFF3030 : 0xFFB02020, false);
				line += 10;
			}
		}
		if (this.showSites) {
			this.drawSites(g, px, line);
			return;
		}
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
			if (t.threat() && t.intercept() >= 0.0F) {
				String pct = Math.round(t.intercept() * 100.0F) + "%";
				g.drawString(this.font, pct, px + PANEL - 14 - this.font.width(pct), line, interceptColor(t.intercept()), false);
			}
			line += 10;
		}
	}

	private Component siteName(SiteInfo site) {
		return Component.translatable("screen.ballisticmissiles.site_type." + site.type());
	}

	/** Colour of a site's state (0 = all well): red empty, yellow reloading, purple jammed, grey without power. */
	private int stateColor(SiteInfo site) {
		int f = site.flags();
		if ((f & SiteInfo.JAMMED) != 0) {
			return 0xFFC060FF;
		}
		if (site.ammo() == 0 || (f & SiteInfo.UNPOWERED) != 0) {
			return 0xFFFF3030;
		}
		if ((f & SiteInfo.RELOADING) != 0) {
			return 0xFFFFD040;
		}
		return 0;
	}

	/** The state of every site: what it is, where, rounds, and what is wrong with it. */
	private void drawSites(GuiGraphics g, int px, int line) {
		List<SiteInfo> sites = new ArrayList<>(this.data.sites());
		sites.sort(Comparator.comparingInt((SiteInfo s) -> (s.flags() & SiteInfo.DESTROYED) != 0 ? 0 : s.ammo() == 0 ? 1 : 2)
			.thenComparingDouble(s -> s.pos().distSqr(this.center)));
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.command_sites", sites.size()), px, line, 0xFF40FF70, false);
		line += 12;
		for (SiteInfo site : sites) {
			if (line > this.mapTop + this.mapSize - 10) {
				break;
			}
			int f = site.flags();
			String key = (f & SiteInfo.DESTROYED) != 0 ? "destroyed" : (f & SiteInfo.OFFLINE) != 0 ? "offline" : (f & SiteInfo.JAMMED) != 0 ? "jammed"
				: (f & SiteInfo.UNPOWERED) != 0 ? "unpowered" : site.ammo() == 0 && (f & SiteInfo.RELOADING) == 0 ? "empty"
				: (f & SiteInfo.RELOADING) != 0 ? "reloading" : (f & SiteInfo.ACTIVE) != 0 ? "active" : "ready";
			int color = switch (key) {
				case "destroyed", "empty", "unpowered" -> 0xFFFF4040;
				case "offline" -> 0xFF808080;
				case "jammed" -> 0xFFC060FF;
				case "reloading" -> 0xFFFFD040;
				case "active" -> 0xFFFFA040;
				default -> 0xFF60E070;
			};
			Component text = this.siteName(site).copy();
			if (site.ammo() >= 0 && (f & SiteInfo.DESTROYED) == 0) {
				text = text.copy().append(" " + site.ammo() + "/" + site.magazine());
				if (site.spares() >= 0) {
					text = text.copy().append(" +" + site.spares());
				}
			}
			g.drawString(this.font, text, px, line, color, false);
			line += 9;
			g.drawString(this.font, Component.literal("  ").append(Component.translatable("screen.ballisticmissiles.site_state." + key))
				.append(String.format("  %d/%d", site.pos().getX(), site.pos().getZ())), px, line, (color & 0x00FFFFFF) | 0xA0000000, false);
			line += 11;
		}
	}

	// ------------------------------------------------------------------ 2.0: live tracks, symbols, 3D

	/** How far (ticks) the tracks are carried on along their velocity since the last update. */
	private float lead() {
		return Math.min(12.0F, (System.currentTimeMillis() - this.receivedAt) / 50.0F);
	}

	private double liveX(TrackInfo t) {
		return t.x() + t.vx() * this.lead();
	}

	private double liveY(TrackInfo t) {
		return t.y() + t.vy() * this.lead();
	}

	private double liveZ(TrackInfo t) {
		return t.z() + t.vz() * this.lead();
	}

	private static int trackColor(TrackInfo t) {
		boolean aircraft = t.threatClass() == AirThreat.ThreatClass.AIRCRAFT.ordinal();
		return !t.threat() ? 0xFF4090FF : aircraft ? 0xFFFFB030 : 0xFFFF4040;
	}

	private static int interceptColor(float p) {
		return p >= 0.8F ? 0xFF50FF70 : p >= 0.5F ? 0xFFFFD040 : 0xFFFF4040;
	}

	private static String classLetter(TrackInfo t) {
		AirThreat.ThreatClass[] classes = AirThreat.ThreatClass.values();
		AirThreat.ThreatClass c = t.threatClass() >= 0 && t.threatClass() < classes.length ? classes[t.threatClass()] : AirThreat.ThreatClass.UNKNOWN;
		return switch (c) {
			case BALLISTIC -> "BM";
			case CRUISE -> "CM";
			case HYPERSONIC -> "HGV";
			case REENTRY -> "RV";
			case AIRCRAFT -> "AC";
			default -> "?";
		};
	}

	/** NATO-style air track symbol: friendly a blue circle, hostile a red diamond (filled dark, outlined bright). */
	private void symbol(GuiGraphics g, int x, int y, TrackInfo t) {
		int color = trackColor(t);
		if (t.threat()) {
			for (int i = -5; i <= 5; i++) {
				int w = 5 - Math.abs(i);
				g.fill(x - w, y + i, x + w + 1, y + i + 1, 0xC0200808);
				g.fill(x - w, y + i, x - w + 1, y + i + 1, color);
				g.fill(x + w, y + i, x + w + 1, y + i + 1, color);
			}
		} else {
			for (int i = -4; i <= 4; i++) {
				int w = (int) Math.round(Math.sqrt(16 - i * i));
				g.fill(x - w, y + i, x + w + 1, y + i + 1, 0xC0081830);
				g.fill(x - w, y + i, x - w + 1, y + i + 1, color);
				g.fill(x + w, y + i, x + w + 1, y + i + 1, color);
			}
		}
		g.fill(x, y, x + 1, y + 1, color);
	}

	/** Track label: number, kind, altitude, and for a hostile the chance it is shot down. */
	private void label(GuiGraphics g, int x, int y, TrackInfo t) {
		int color = trackColor(t);
		String head = String.format("T%02d %s", t.number(), classLetter(t));
		g.drawString(this.font, head, x, y, color, false);
		String alt = Math.round(this.liveY(t)) + "m";
		g.drawString(this.font, alt, x, y + 9, (color & 0x00FFFFFF) | 0xB0000000, false);
		if (t.threat() && t.intercept() >= 0.0F) {
			String pct = "ABF " + Math.round(t.intercept() * 100.0F) + "%";
			g.drawString(this.font, pct, x + this.font.width(alt) + 4, y + 9, interceptColor(t.intercept()), false);
		}
	}

	/** Friendly site: a blue-framed box in the colour of its kind, its border flashing with what is wrong. */
	private void siteSymbol(GuiGraphics g, int x, int y, SiteInfo site, int color, boolean blink) {
		int state = this.stateColor(site);
		int frame = state != 0 && (blink || (site.flags() & SiteInfo.RELOADING) != 0) ? state : 0xFF4090FF;
		g.fill(x - 4, y - 3, x + 5, y + 4, frame);
		g.fill(x - 3, y - 2, x + 4, y + 3, 0xFF0A1420);
		g.fill(x - 2, y - 1, x + 3, y + 2, (site.flags() & SiteInfo.OFFLINE) != 0 ? 0xFF606060 : color);
	}

	private void drawAlarmAndLegend(GuiGraphics g) {
		if (this.data.alarm() && (System.currentTimeMillis() / 500L) % 2 == 0) {
			Component alarm = Component.translatable("screen.ballisticmissiles.command_alarm");
			int w = this.font.width(alarm) + 12;
			int ax = this.mapLeft + (this.mapSize - w) / 2;
			g.fill(ax, this.mapTop + 16, ax + w, this.mapTop + 30, 0xE0A00000);
			g.drawString(this.font, alarm, ax + 6, this.mapTop + 19, 0xFFFFFFFF, false);
		}
		// legend along the bottom
		int ly = this.mapTop + this.mapSize - 24;
		int lx = this.mapLeft + this.mapSize - 150;
		g.fill(lx - 4, ly - 3, this.mapLeft + this.mapSize - 2, ly + 21, 0xA0000000);
		g.fill(lx, ly + 2, lx + 5, ly + 7, 0xFFFF4040);
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.command_hostile"), lx + 8, ly, 0xFFFF6060, false);
		g.fill(lx + 70, ly + 2, lx + 75, ly + 7, 0xFF4090FF);
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.command_friendly"), lx + 78, ly, 0xFF70B0FF, false);
		g.fill(lx, ly + 12, lx + 5, ly + 17, 0xFFFFB030);
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.command_aircraft"), lx + 8, ly + 10, 0xFFFFC060, false);
		if (this.view3d) {
			g.drawString(this.font, Component.translatable("screen.ballisticmissiles.command_3d_hint"), this.mapLeft + 30, this.mapTop + 4, 0xA0B0C8B0, false);
		}
	}

	private static final float VERTICAL = 1.5F;

	private float scale3d() {
		return (float) (this.mapSize / (2.0 * this.range()) * 0.72);
	}

	private float cx3d() {
		return this.mapLeft + this.mapSize * 0.5F;
	}

	private float cy3d() {
		return this.mapTop + this.mapSize * 0.58F;
	}

	/** World point to the 3D view: turned by the yaw, tilted by the pitch, heights stretched a little. */
	private float[] project(double wx, double wy, double wz) {
		double dx = wx - (this.center.getX() + 0.5);
		double dz = wz - (this.center.getZ() + 0.5);
		double dy = (wy - this.center.getY()) * VERTICAL;
		double cs = Math.cos(this.yaw3d);
		double sn = Math.sin(this.yaw3d);
		double rx = dx * cs - dz * sn;
		double rz = dx * sn + dz * cs;
		float s = this.scale3d();
		return new float[] {(float) (this.cx3d() + rx * s), (float) (this.cy3d() + (rz * Math.sin(this.pitch3d) - dy * Math.cos(this.pitch3d)) * s)};
	}

	/** A point on the screen back to the ground it shows: {x, z}. */
	private double[] unproject(double mx, double my) {
		float s = this.scale3d();
		double rx = (mx - this.cx3d()) / s;
		double rz = (my - this.cy3d()) / s / Math.sin(this.pitch3d);
		double cs = Math.cos(this.yaw3d);
		double sn = Math.sin(this.yaw3d);
		return new double[] {this.center.getX() + 0.5 + rx * cs + rz * sn, this.center.getZ() + 0.5 - rx * sn + rz * cs};
	}

	private void line3d(GuiGraphics g, double x0, double y0, double z0, double x1, double y1, double z1, int color, boolean dashed) {
		float[] a = this.project(x0, y0, z0);
		float[] b = this.project(x1, y1, z1);
		if (dashed) {
			this.dashed(g, (int) a[0], (int) a[1], (int) b[0], (int) b[1], color);
		} else {
			this.line(g, (int) a[0], (int) a[1], (int) b[0], (int) b[1], color);
		}
	}

	private void draw3d(GuiGraphics g) {
		if (System.currentTimeMillis() - this.lastDrag > 4000L) {
			this.yaw3d += 0.0015F; // a slow turn while nobody holds it
		}
		double gx = this.center.getX() + 0.5;
		double gy = this.center.getY();
		double gz = this.center.getZ() + 0.5;
		int range = this.range();
		// the terrain map laid flat and tilted: the ground plane maps to the screen by a 2x2 matrix
		if (this.texture != null) {
			float k = 0.72F;
			float cs = Mth.cos(this.yaw3d);
			float sn = Mth.sin(this.yaw3d);
			float sp = Mth.sin(this.pitch3d);
			float m00 = k * cs;
			float m10 = -k * sn;
			float m01 = k * sn * sp;
			float m11 = k * cs * sp;
			float mcx = this.mapLeft + this.mapSize * 0.5F;
			float mcy = this.mapTop + this.mapSize * 0.5F;
			org.joml.Matrix3x2f m = new org.joml.Matrix3x2f(m00, m01, m10, m11, this.cx3d() - (m00 * mcx + m10 * mcy), this.cy3d() - (m01 * mcx + m11 * mcy));
			g.pose().pushMatrix();
			g.pose().mul(m);
			g.blit(RenderPipelines.GUI_TEXTURED, MAP_ID, this.mapLeft, this.mapTop, 0, 0, this.mapSize, this.mapSize, TEX, TEX, TEX, TEX);
			g.pose().popMatrix();
		}
		// range rings and the cross on the ground, north marked
		for (int ring = 1; ring <= 4; ring++) {
			double r = range * ring / 4.0;
			int n = 64;
			for (int i = 0; i < n; i++) {
				double a0 = Mth.TWO_PI * i / n;
				double a1 = Mth.TWO_PI * (i + 1) / n;
				this.line3d(g, gx + Math.cos(a0) * r, gy, gz + Math.sin(a0) * r, gx + Math.cos(a1) * r, gy, gz + Math.sin(a1) * r, 0x6040FF70, false);
			}
		}
		this.line3d(g, gx - range, gy, gz, gx + range, gy, gz, 0x3040FF70, false);
		this.line3d(g, gx, gy, gz - range, gx, gy, gz + range, 0x3040FF70, false);
		float[] north = this.project(gx, gy, gz - range * 1.05);
		g.drawCenteredString(this.font, "N", (int) north[0], (int) north[1] - 4, 0xFF40FF70);
		// a height scale at the centre: a mast with marks every 100 m
		for (int h = 100; h <= 400; h += 100) {
			float[] p = this.project(gx, gy + h, gz);
			g.fill((int) p[0] - 2, (int) p[1], (int) p[0] + 3, (int) p[1] + 1, 0x8040FF70);
			g.drawString(this.font, h + "m", (int) p[0] + 4, (int) p[1] - 4, 0x8040FF70, false);
		}
		this.line3d(g, gx, gy, gz, gx, gy + 400, gz, 0x5040FF70, false);

		boolean blink = (System.currentTimeMillis() / 400L) % 2 == 0;
		for (SiteInfo site : this.data.sites()) {
			float[] base = this.project(site.pos().getX() + 0.5, site.pos().getY(), site.pos().getZ() + 0.5);
			if ((site.flags() & SiteInfo.DESTROYED) != 0) {
				this.line(g, (int) base[0] - 3, (int) base[1] - 3, (int) base[0] + 3, (int) base[1] + 3, 0xFFFF2020);
				this.line(g, (int) base[0] - 3, (int) base[1] + 3, (int) base[0] + 3, (int) base[1] - 3, 0xFFFF2020);
				continue;
			}
			int color = switch (site.kind()) {
				case 0 -> 0xFFFFFFFF;
				case 1 -> 0xFF50E0FF;
				case 2 -> 0xFFFFE040;
				default -> 0xFFC060FF;
			};
			g.fill((int) base[0], (int) base[1] - 8, (int) base[0] + 1, (int) base[1], 0xA04090FF);
			this.siteSymbol(g, (int) base[0], (int) base[1] - 10, site, color, blink);
		}
		float[] own = this.project(gx, gy, gz);
		g.fill((int) own[0] - 3, (int) own[1] - 3, (int) own[0] + 4, (int) own[1] + 4, 0xFF40FF70);
		g.fill((int) own[0] - 1, (int) own[1] - 1, (int) own[0] + 2, (int) own[1] + 2, 0xFF000000);

		for (TrackInfo t : this.data.tracks()) {
			int rgb = trackColor(t) & 0xFFFFFF;
			// where it has flown, at its height
			var trail = t.entityId() < 0 ? null : this.trails.get(t.entityId());
			if (trail != null) {
				float[] prev = null;
				int i = 0;
				int n = trail.size();
				for (float[] p : trail) {
					if (prev != null) {
						int alpha = 0x30 + 0x90 * i / Math.max(1, n);
						this.line3d(g, prev[0], prev[1], prev[2], p[0], p[1], p[2], alpha << 24 | rgb, false);
					}
					prev = p;
					i++;
				}
			}
			double x = this.liveX(t);
			double y = this.liveY(t);
			double z = this.liveZ(t);
			// its shadow on the ground and the drop line down to it
			this.line3d(g, x, y, z, x, gy, z, 0x50000000 | rgb, true);
			float[] shadow = this.project(x, gy, z);
			g.fill((int) shadow[0] - 1, (int) shadow[1], (int) shadow[0] + 2, (int) shadow[1] + 1, 0x90000000 | rgb);
			boolean aircraft = t.threatClass() == AirThreat.ThreatClass.AIRCRAFT.ordinal();
			if (t.threat() && !aircraft && t.eta() > 0) {
				// the predicted arc down to impact: straight over the ground, a parabola in height
				double tt = t.eta();
				double accel = (gy - y - t.vy() * tt) / (tt * tt);
				double px = x;
				double py = y;
				double pz = z;
				int steps = 24;
				for (int i = 1; i <= steps; i++) {
					double f = (double) i / steps;
					double tau = f * tt;
					double nx = x + (t.impactX() - x) * f;
					double nz = z + (t.impactZ() - z) * f;
					double ny = y + t.vy() * tau + accel * tau * tau;
					this.line3d(g, px, py, pz, nx, ny, nz, 0xA0000000 | rgb, true);
					px = nx;
					py = ny;
					pz = nz;
				}
				float[] hit = this.project(t.impactX(), gy, t.impactZ());
				float pulse = (System.currentTimeMillis() % 1000L) / 1000.0F;
				int zone = Math.max(3, (int) (40.0 * this.scale3d()));
				this.circle(g, (int) hit[0], (int) hit[1], zone, 0x80FF4040);
				this.circle(g, (int) hit[0], (int) hit[1], (int) (zone * (0.4F + 0.6F * pulse)), ((int) (0xA0 * (1.0F - pulse)) << 24) | 0xFF4040);
				g.drawString(this.font, Math.max(0, t.eta() / 20) + "s", (int) hit[0] + 5, (int) hit[1] + 2, 0xFFFF6060, false);
			}
			float[] at = this.project(x, y, z);
			this.symbol(g, (int) at[0], (int) at[1], t);
			this.label(g, (int) at[0] + 7, (int) at[1] - 10, t);
		}
		if (this.targetX != null) {
			float[] tp = this.project(this.targetX + 0.5, gy, this.targetZ + 0.5);
			int tx = (int) tp[0];
			int ty = (int) tp[1];
			g.fill(tx - 7, ty, tx + 8, ty + 1, 0xFFFF3030);
			g.fill(tx, ty - 7, tx + 1, ty + 8, 0xFFFF3030);
			this.circle(g, tx, ty, 5, 0xFFFF3030);
		}
	}

	private void dashed(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.min(600, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)));
		for (int i = 0; i <= steps; i++) {
			if (i / 3 % 2 == 1) {
				continue;
			}
			float f = steps == 0 ? 0 : (float) i / steps;
			int x = Math.round(Mth.lerp(f, x0, x1));
			int y = Math.round(Mth.lerp(f, y0, y1));
			g.fill(x, y, x + 1, y + 1, color);
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
