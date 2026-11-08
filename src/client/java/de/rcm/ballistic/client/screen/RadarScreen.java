package de.rcm.ballistic.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.block.RadarBlockEntity;
import de.rcm.ballistic.defense.AirThreat;
import de.rcm.ballistic.network.ModNetworking.RadarClosePayload;
import de.rcm.ballistic.network.ModNetworking.RadarDataPayload;
import de.rcm.ballistic.network.ModNetworking.SiteInfo;
import de.rcm.ballistic.network.ModNetworking.TrackInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Plan-position indicator: a rotating beam paints the contacts onto a phosphor screen where they
 * slowly fade until the next sweep. Track labels, velocity leaders, predicted impact points with
 * their error circles, friendly sites and interceptors are overlaid.
 */
public class RadarScreen extends Screen {
	private static final Identifier TEXTURE_ID = BallisticMissiles.id("dynamic/radar_scope");
	private static final int S = 256;
	private static final float HALF = S / 2.0F - 3.0F;
	private static final int GREEN = 0xFF40FF70;
	private static final int DIM = 0xFF1C7A34;
	private static final int AMBER = 0xFFFFB030;
	private static final int RED = 0xFFFF4040;
	private static final int CYAN = 0xFF50E0FF;

	private final BlockPos radar;
	private RadarDataPayload data;
	private final float[] phosphor = new float[S * S];
	private final int[] backdrop = new int[S * S];
	private final Random noise = new Random();
	private DynamicTexture texture;
	private float lastAzimuth = -1;
	private long lastFrame;

	public RadarScreen(RadarDataPayload data) {
		super(Component.translatable("screen.ballisticmissiles.radar"));
		this.radar = data.radar();
		this.data = data;
	}

	public BlockPos radarPos() {
		return this.radar;
	}

	public void update(RadarDataPayload data) {
		this.data = data;
	}

	@Override
	protected void init() {
		if (this.texture == null) {
			this.texture = new DynamicTexture(() -> "ballisticmissiles radar scope", S, S, false);
			this.minecraft.getTextureManager().register(TEXTURE_ID, this.texture);
			this.buildBackdrop();
		}
	}

	@Override
	public void removed() {
		ClientPlayNetworking.send(new RadarClosePayload(this.radar));
		this.minecraft.getTextureManager().release(TEXTURE_ID);
		this.texture = null;
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ phosphor screen

	private void buildBackdrop() {
		float ringStep = HALF / 4.0F;
		float protectedR = (float) this.data.protectedRadius() / this.data.range() * HALF;
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				float dx = x + 0.5F - S / 2.0F;
				float dy = y + 0.5F - S / 2.0F;
				float r = Mth.sqrt(dx * dx + dy * dy);
				int c;
				if (r > HALF + 2) {
					c = 0;
				} else if (r > HALF) {
					c = 0xFF2A3A2C; // bezel
				} else {
					c = 0xFF041006;
					float ring = r % ringStep;
					if (ring < 0.7F || ringStep - ring < 0.3F) {
						c = 0xFF0E3A18;
					}
					if (Math.abs(dx) < 0.5F || Math.abs(dy) < 0.5F) {
						c = 0xFF0B2E13;
					}
					if (Math.abs(r - protectedR) < 0.6F && ((int) (Math.atan2(dy, dx) * 40) & 1) == 0) {
						c = 0xFF4A3608; // protected zone, dashed
					}
				}
				this.backdrop[y * S + x] = c;
			}
		}
	}

	private float currentAzimuth(float partialTick) {
		long time = this.minecraft.level == null ? 0 : this.minecraft.level.getGameTime();
		float az = RadarBlockEntity.azimuth(time, this.data.sweepOffset()) + partialTick * 360.0F / RadarBlockEntity.SWEEP_PERIOD;
		return az % 360.0F;
	}

	private void updatePhosphor(float partialTick) {
		long now = System.nanoTime();
		float dt = this.lastFrame == 0 ? 0.016F : Math.min(0.2F, (now - this.lastFrame) / 1.0E9F);
		this.lastFrame = now;
		float decay = (float) Math.exp(-dt / 1.1);
		for (int i = 0; i < this.phosphor.length; i++) {
			this.phosphor[i] *= decay;
		}
		float az = this.currentAzimuth(partialTick);
		float from = this.lastAzimuth < 0 ? az - 2 : this.lastAzimuth;
		float sweep = az - from;
		if (sweep < 0) {
			sweep += 360;
		}
		if (sweep > 60) {
			from = az - 2;
			sweep = 2;
		}
		boolean jammed = this.data.jammedTicks() > 0;
		if (!jammed) {
			for (float a = 0; a <= sweep; a += 0.6F) {
				this.beam(from + a, 0.32F);
			}
			this.paintContacts(from, az);
			// a little ground clutter around the antenna
			for (int i = 0; i < 6; i++) {
				double a = Math.toRadians(az - this.noise.nextFloat() * 4);
				double r = this.noise.nextFloat() * 10;
				this.stamp((float) (S / 2.0 + Math.sin(a) * r), (float) (S / 2.0 - Math.cos(a) * r), 0, 0.5F);
			}
		} else {
			for (int i = 0; i < 900; i++) {
				int x = this.noise.nextInt(S);
				int y = this.noise.nextInt(S);
				this.phosphor[y * S + x] = Math.max(this.phosphor[y * S + x], this.noise.nextFloat() * 0.7F);
			}
		}
		this.lastAzimuth = az;

		NativeImage pixels = this.texture.getPixels();
		if (pixels == null) {
			return;
		}
		for (int y = 0; y < S; y++) {
			for (int x = 0; x < S; x++) {
				int base = this.backdrop[y * S + x];
				if (base == 0) {
					pixels.setPixel(x, y, 0);
					continue;
				}
				float p = Math.min(1.0F, this.phosphor[y * S + x]);
				int r = Math.min(255, (base >> 16 & 255) + (int) (110 * p * p));
				int g = Math.min(255, (base >> 8 & 255) + (int) (255 * p));
				int b = Math.min(255, (base & 255) + (int) (90 * p * p));
				pixels.setPixel(x, y, 0xFF000000 | r << 16 | g << 8 | b);
			}
		}
		this.texture.upload();
	}

	private void beam(float azimuth, float strength) {
		double a = Math.toRadians(azimuth);
		double sx = Math.sin(a);
		double sy = -Math.cos(a);
		for (int r = 0; r < HALF; r++) {
			int x = (int) (S / 2.0 + sx * r);
			int y = (int) (S / 2.0 + sy * r);
			int i = y * S + x;
			if (i >= 0 && i < this.phosphor.length) {
				this.phosphor[i] = Math.max(this.phosphor[i], strength);
			}
		}
	}

	private void paintContacts(float from, float to) {
		double cx = this.radar.getX() + 0.5;
		double cz = this.radar.getZ() + 0.5;
		for (TrackInfo t : this.data.tracks()) {
			float bearing = RadarBlockEntity.bearing(t.x() - cx, t.z() - cz);
			boolean inside = from <= to ? bearing >= from && bearing <= to : bearing >= from || bearing <= to;
			if (inside && t.age() < RadarBlockEntity.SWEEP_PERIOD * 2) {
				float[] p = this.toScope(t.x(), t.z());
				this.stamp(p[0], p[1], 1, 1.3F);
			}
		}
		for (BlockPos i : this.data.interceptors()) {
			float bearing = RadarBlockEntity.bearing(i.getX() - cx, i.getZ() - cz);
			boolean inside = from <= to ? bearing >= from && bearing <= to : bearing >= from || bearing <= to;
			if (inside) {
				float[] p = this.toScope(i.getX(), i.getZ());
				this.stamp(p[0], p[1], 0, 0.9F);
			}
		}
	}

	private void stamp(float px, float py, int radius, float strength) {
		for (int dy = -radius; dy <= radius; dy++) {
			for (int dx = -radius; dx <= radius; dx++) {
				int x = (int) px + dx;
				int y = (int) py + dy;
				if (x < 0 || y < 0 || x >= S || y >= S) {
					continue;
				}
				float f = strength * (dx == 0 && dy == 0 ? 1.0F : 0.6F);
				this.phosphor[y * S + x] = Math.max(this.phosphor[y * S + x], f);
			}
		}
	}

	/** World x/z to scope texture pixels (north up). */
	private float[] toScope(double x, double z) {
		float k = HALF / this.data.range();
		return new float[] {(float) (S / 2.0 + (x - (this.radar.getX() + 0.5)) * k), (float) (S / 2.0 + (z - (this.radar.getZ() + 0.5)) * k)};
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		if (this.texture == null) {
			return;
		}
		this.updatePhosphor(partialTick);

		int size = Mth.clamp(Math.min(this.height - 30, this.width - 190), 120, 400);
		int left = Math.max(8, (this.width - size - 180) / 2);
		int top = (this.height - size) / 2;
		float scale = size / (float) S;

		g.fill(left - 6, top - 6, left + size + 186, top + size + 6, 0xF0101410);
		g.fill(left - 6, top - 6, left + size + 186, top - 4, 0xFF2E5A36);
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, left, top, 0, 0, size, size, S, S, S, S);

		int cx = left + size / 2;
		int cy = top + size / 2;
		g.drawCenteredString(this.font, "N", cx, top - 2 + 4, DIM);
		g.drawCenteredString(this.font, "S", cx, top + size - 12, DIM);
		g.drawString(this.font, "W", left + 4, cy - 4, DIM);
		g.drawString(this.font, "E", left + size - 10, cy - 4, DIM);
		int range = this.data.range();
		for (int ring = 1; ring <= 4; ring++) {
			int r = (int) (size / 2.0F * (S / 2.0F - 3) / (S / 2.0F) * ring / 4.0F);
			g.drawString(this.font, String.valueOf(range * ring / 4), cx + 2, cy - r + 1, 0xFF1F6A30, false);
		}

		boolean jammed = this.data.jammedTicks() > 0;
		if (jammed) {
			Component msg = Component.translatable("screen.ballisticmissiles.radar_jammed", this.data.jammedTicks() / 20);
			g.drawCenteredString(this.font, msg, cx, cy - 4, 0xFFC060FF);
		} else {
			this.drawOverlay(g, left, top, scale, mouseX, mouseY);
		}
		this.drawPanel(g, left + size + 12, top, size, jammed);
	}

	private void drawOverlay(GuiGraphics g, int left, int top, float scale, int mouseX, int mouseY) {
		// friendly sites
		for (SiteInfo site : this.data.sites()) {
			float[] p = this.toScope(site.pos().getX() + 0.5, site.pos().getZ() + 0.5);
			int x = left + (int) (p[0] * scale);
			int y = top + (int) (p[1] * scale);
			int color = switch (site.kind()) {
				case 1 -> CYAN;
				case 2 -> 0xFFFFE040;
				default -> 0xFFFFFFFF;
			};
			g.fill(x - 2, y - 2, x + 2, y + 2, color);
			g.fill(x - 1, y - 1, x + 1, y + 1, 0xFF000000);
		}
		// own position
		if (this.minecraft.player != null) {
			float[] p = this.toScope(this.minecraft.player.getX(), this.minecraft.player.getZ());
			int x = left + (int) (p[0] * scale);
			int y = top + (int) (p[1] * scale);
			g.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFFFFF);
		}
		for (TrackInfo t : this.data.tracks()) {
			float[] p = this.toScope(t.x(), t.z());
			int x = left + (int) (p[0] * scale);
			int y = top + (int) (p[1] * scale);
			int color = t.threat() ? RED : AMBER;
			// velocity leader: where it will be in 4 seconds
			float k = HALF / this.data.range() * scale;
			this.line(g, x, y, x + (int) (t.vx() * 80 * k), y + (int) (t.vz() * 80 * k), color & 0xA0FFFFFF);
			g.drawString(this.font, String.format("T%02d", t.number()), x + 4, y - 9, color, false);
			if (t.threat()) {
				float[] ip = this.toScope(t.impactX(), t.impactZ());
				int ix = left + (int) (ip[0] * scale);
				int iy = top + (int) (ip[1] * scale);
				this.line(g, ix - 3, iy - 3, ix + 3, iy + 3, RED);
				this.line(g, ix - 3, iy + 3, ix + 3, iy - 3, RED);
				this.circle(g, ix, iy, Math.max(3, (int) (t.impactError() * k)), 0x90FF4040);
			}
		}
	}

	private void drawPanel(GuiGraphics g, int x, int y, int height, boolean jammed) {
		g.drawString(this.font, this.title, x, y, GREEN);
		g.drawString(this.font, String.format("%d / %d", this.radar.getX(), this.radar.getZ()), x, y + 11, DIM, false);
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.radar_range", this.data.range(), RadarBlockEntity.SWEEP_PERIOD / 20.0F), x, y + 21, DIM, false);
		int line = y + 38;
		if (jammed) {
			g.drawString(this.font, Component.translatable("screen.ballisticmissiles.radar_emp"), x, line, 0xFFC060FF, false);
			return;
		}
		List<TrackInfo> sorted = new ArrayList<>(this.data.tracks());
		sorted.sort(Comparator.comparingInt(TrackInfo::eta));
		if (sorted.isEmpty()) {
			g.drawString(this.font, Component.translatable("screen.ballisticmissiles.radar_clear"), x, line, DIM, false);
		}
		double rx = this.radar.getX() + 0.5;
		double ry = this.radar.getY() + 0.5;
		double rz = this.radar.getZ() + 0.5;
		for (TrackInfo t : sorted) {
			if (line > y + height - 40) {
				break;
			}
			int color = t.threat() ? RED : AMBER;
			AirThreat.ThreatClass cls = AirThreat.ThreatClass.byOrdinal(t.threatClass());
			Component clsName = Component.translatable("radar.ballisticmissiles.class." + cls.key);
			g.drawString(this.font, Component.literal(String.format("T%02d ", t.number())).append(clsName), x, line, color, false);
			line += 10;
			int dist = (int) Math.hypot(t.x() - rx, t.z() - rz);
			String info = String.format("%dm  ↑%d  %ds", dist, (int) (t.y() - ry), Math.max(0, t.eta() / 20));
			g.drawString(this.font, info, x + 6, line, 0xFFB0C8B0, false);
			line += 10;
			if (!t.nameKey().isEmpty()) {
				g.drawString(this.font, Component.translatable(t.nameKey()), x + 6, line, 0xFF7A9A7E, false);
				line += 10;
			}
			if (t.threat()) {
				g.drawString(this.font, String.format("⌖ %d / %d ±%d", (int) t.impactX(), (int) t.impactZ(), (int) t.impactError()), x + 6, line, 0xFFFF8080, false);
				line += 10;
			}
			line += 3;
		}
		int ly = y + height - 30;
		g.fill(x, ly + 2, x + 4, ly + 6, 0xFFFFFFFF);
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.legend_radar"), x + 7, ly, 0xFF7A9A7E, false);
		g.fill(x, ly + 12, x + 4, ly + 16, CYAN);
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.legend_sam"), x + 7, ly + 10, 0xFF7A9A7E, false);
		g.fill(x, ly + 22, x + 4, ly + 26, 0xFFFFE040);
		g.drawString(this.font, Component.translatable("screen.ballisticmissiles.legend_silo"), x + 7, ly + 20, 0xFF7A9A7E, false);
	}

	private void line(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
		int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int i = 0; i <= steps; i++) {
			float f = steps == 0 ? 0 : (float) i / steps;
			int x = Math.round(Mth.lerp(f, x0, x1));
			int y = Math.round(Mth.lerp(f, y0, y1));
			g.fill(x, y, x + 1, y + 1, color);
		}
	}

	private void circle(GuiGraphics g, int cx, int cy, int r, int color) {
		int n = Math.max(12, r * 4);
		for (int i = 0; i < n; i += 2) {
			double a = Mth.TWO_PI * i / n;
			int x = cx + (int) Math.round(Math.cos(a) * r);
			int y = cy + (int) Math.round(Math.sin(a) * r);
			g.fill(x, y, x + 1, y + 1, color);
		}
	}
}
