package de.rcm.ballistic.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.effect.AshStorm;
import de.rcm.ballistic.client.effect.CloudDensity;
import de.rcm.ballistic.client.effect.FalloutClient;
import de.rcm.ballistic.client.effect.StormFront;
import de.rcm.ballistic.client.effect.Wind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * The weather satellite's picture, live: the land under you (as far as it is known - loaded), the clouds over it
 * drifting with the wind, the rain under them in radar colours - green, yellow, red in the core of a storm - the
 * lightning flickering in it, the fallout plumes, and you and the others on it. Scroll to zoom.
 */
public final class WeatherMapScreen extends Screen {
	private static final int SIZE = 160;
	private static final Identifier TEX_ID = BallisticMissiles.id("textures/gui/weather_map_live");
	private static final int[] SCALES = {256, 512, 1024, 2048, 4096};
	private static DynamicTexture texture;
	private int scale = 2;
	private int age;
	/** The land, kept between redraws (it barely changes): ARGB per pixel, 0 where unknown. */
	private final int[] land = new int[SIZE * SIZE];
	private int landScale = -1;
	private int landX;
	private int landZ;

	public WeatherMapScreen() {
		super(Component.translatable("gui.ballisticmissiles.weather_map.title"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		if (texture == null) {
			texture = new DynamicTexture(() -> "Weather satellite picture", SIZE, SIZE, false);
			Minecraft.getInstance().getTextureManager().register(TEX_ID, texture);
		}
		this.redraw();
	}

	@Override
	public void tick() {
		if (++this.age % 5 == 0) {
			this.redraw();
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		int s = Mth.clamp(this.scale + (scrollY < 0 ? 1 : -1), 0, SCALES.length - 1);
		if (s != this.scale) {
			this.scale = s;
			this.redraw();
		}
		return true;
	}

	private boolean signal() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.level.dimension() == Level.OVERWORLD;
	}

	/** Paints the picture into the texture. */
	private void redraw() {
		Minecraft mc = Minecraft.getInstance();
		NativeImage img = texture.getPixels();
		if (mc.level == null || mc.player == null || img == null) {
			return;
		}
		Player me = mc.player;
		double across = SCALES[this.scale];
		double step = across / SIZE;
		int cx = Mth.floor(me.getX());
		int cz = Mth.floor(me.getZ());
		boolean overworld = this.signal();
		// the land: only redone when you have moved a good way or zoomed
		if (this.landScale != this.scale || Math.abs(cx - this.landX) > step * 4 || Math.abs(cz - this.landZ) > step * 4 || this.age % 100 == 0) {
			this.landScale = this.scale;
			this.landX = cx;
			this.landZ = cz;
			for (int py = 0; py < SIZE; py++) {
				for (int px = 0; px < SIZE; px++) {
					int x = Mth.floor(cx + (px - SIZE / 2 + 0.5) * step);
					int z = Mth.floor(cz + (py - SIZE / 2 + 0.5) * step);
					this.land[py * SIZE + px] = landAt(mc, x, z);
				}
			}
		}
		float rain = mc.level.getRainLevel(1.0F);
		float thunder = mc.level.getThunderLevel(1.0F);
		double[] cell = StormFront.where();
		float flash = StormFront.flashNow();
		long time = mc.level.getGameTime();
		for (int py = 0; py < SIZE; py++) {
			for (int px = 0; px < SIZE; px++) {
				double x = this.landX + (px - SIZE / 2 + 0.5) * step;
				double z = this.landZ + (py - SIZE / 2 + 0.5) * step;
				int c = this.land[py * SIZE + px];
				if (c == 0) {
					// not known: a dim grid
					boolean grid = Math.floorMod(Mth.floor(x), 256) < step || Math.floorMod(Mth.floor(z), 256) < step;
					c = grid ? 0xFF262A30 : 0xFF181B20;
				}
				float r = (c >> 16 & 255) / 255.0F;
				float g = (c >> 8 & 255) / 255.0F;
				float b = (c & 255) / 255.0F;
				if (overworld) {
					// the clouds from above: white, the thick parts brighter
					float cloud = CloudDensity.cover(x, z);
					float storm = 0.0F;
					if (cell != null) {
						double d = Math.hypot(x - cell[0], z - cell[1]) / StormFront.RADIUS;
						storm = (float) Mth.clamp(1.0 - d, 0.0, 1.0);
						cloud = Math.max(cloud, (float) Mth.clamp(1.6 - d * 0.9, 0.0, 1.0)); // the anvil spreads wide
					}
					if (cloud > 0.0F) {
						float a = Mth.clamp(cloud * 1.3F, 0.0F, 0.9F);
						r = Mth.lerp(a, r, 0.93F);
						g = Mth.lerp(a, g, 0.94F);
						b = Mth.lerp(a, b, 0.96F);
					}
					// rain under the clouds, as the radar shows it
					float precip = Math.max(Mth.clamp((cloud - 0.35F) * 2.0F, 0.0F, 1.0F) * rain * (0.5F + 0.5F * thunder), storm * storm * 1.2F);
					if (precip > 0.05F) {
						int radar = precip > 0.8F ? 0xE03020 : precip > 0.55F ? 0xF0C020 : precip > 0.3F ? 0x40C040 : 0x2E8C9C;
						float a = Mth.clamp(precip * 0.75F, 0.0F, 0.7F);
						r = Mth.lerp(a, r, (radar >> 16 & 255) / 255.0F);
						g = Mth.lerp(a, g, (radar >> 8 & 255) / 255.0F);
						b = Mth.lerp(a, b, (radar & 255) / 255.0F);
					}
					// lightning: the core of the storm flashes white
					if (flash > 0.3F && storm > 0.5F && ((px * 7 + py * 13 + time) % 5) == 0) {
						r = g = b = 1.0F;
					}
					// fallout: a sickly yellow-green plume
					double fall = FalloutClient.density(x, z);
					if (fall > 0.02) {
						float a = (float) Mth.clamp(fall * 0.8, 0.0, 0.6);
						r = Mth.lerp(a, r, 0.78F);
						g = Mth.lerp(a, g, 0.86F);
						b = Mth.lerp(a, b, 0.2F);
					}
				} else {
					// no satellite over the Nether or the End: static
					float n = (float) (((px * 73856093 ^ py * 19349663 ^ (int) time * 83492791) & 255) / 255.0);
					r = g = b = 0.1F + 0.25F * n;
					if (AshStorm.amount() > 0.05F) {
						r += 0.25F;
						g += 0.08F;
					}
				}
				img.setPixel(px, py, 0xFF000000 | to8(r) << 16 | to8(g) << 8 | to8(b)); // ARGB
			}
		}
		texture.upload();
	}

	private static int to8(float v) {
		return Mth.clamp(Math.round(v * 255.0F), 0, 255);
	}

	/** The colour of the land at (x, z) as a map would show it, shaded by height; 0 if not loaded. */
	private static int landAt(Minecraft mc, int x, int z) {
		if (mc.level.getChunkSource().getChunk(x >> 4, z >> 4, false) == null) {
			return 0;
		}
		int h = mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		BlockPos at = new BlockPos(x, h - 1, z);
		BlockState s = mc.level.getBlockState(at);
		MapColor color = s.getMapColor(mc.level, at);
		if (color == MapColor.NONE) {
			return 0xFF101010;
		}
		int rgb = color.col;
		float shade = Mth.clamp(0.75F + (h - mc.level.getSeaLevel()) / 120.0F, 0.55F, 1.15F);
		int r = Mth.clamp(Math.round((rgb >> 16 & 255) * shade), 0, 255);
		int g = Mth.clamp(Math.round((rgb >> 8 & 255) * shade), 0, 255);
		int b = Mth.clamp(Math.round((rgb & 255) * shade), 0, 255);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		Minecraft mc = Minecraft.getInstance();
		int view = Math.min(this.height - 60, 320);
		int x0 = (this.width - view) / 2;
		int y0 = (this.height - view) / 2 - 4;
		// the case round the screen
		g.fill(x0 - 6, y0 - 18, x0 + view + 6, y0 + view + 30, 0xF0262A30);
		g.fill(x0 - 1, y0 - 1, x0 + view + 1, y0 + view + 1, 0xFF0A0C10);
		g.blit(RenderPipelines.GUI_TEXTURED, TEX_ID, x0, y0, 0.0F, 0.0F, view, view, SIZE, SIZE, SIZE, SIZE);
		g.drawCenteredString(this.font, this.title, this.width / 2, y0 - 13, 0xFFB8D8FF);
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (!this.signal()) {
			g.drawCenteredString(this.font, Component.translatable("gui.ballisticmissiles.weather_map.no_signal"), this.width / 2, y0 + view / 2 - 4, 0xFFFF8060);
			if (AshStorm.amount() > 0.05F) {
				g.drawCenteredString(this.font, Component.translatable("gui.ballisticmissiles.weather_map.ash"), this.width / 2, y0 + view / 2 + 8, 0xFFFFA040);
			}
			return;
		}
		double across = SCALES[this.scale];
		float k = view / (float) across;
		// the others on it, then you (an arrow the way you face)
		for (Player p : mc.level.players()) {
			if (p == mc.player) {
				continue;
			}
			int px = Math.round(x0 + view / 2.0F + (float) (p.getX() - this.landX) * k);
			int py = Math.round(y0 + view / 2.0F + (float) (p.getZ() - this.landZ) * k);
			if (px > x0 && px < x0 + view && py > y0 && py < y0 + view) {
				g.fill(px - 1, py - 1, px + 2, py + 2, 0xFF40A0FF);
			}
		}
		int mx = Math.round(x0 + view / 2.0F + (float) (mc.player.getX() - this.landX) * k);
		int my = Math.round(y0 + view / 2.0F + (float) (mc.player.getZ() - this.landZ) * k);
		float yaw = mc.player.getYRot() * Mth.DEG_TO_RAD;
		for (int i = 0; i < 6; i++) {
			int ax = Math.round(mx - Mth.sin(yaw) * i);
			int ay = Math.round(my + Mth.cos(yaw) * i);
			g.fill(ax - (i == 0 ? 2 : 1), ay - (i == 0 ? 2 : 1), ax + (i == 0 ? 2 : 1), ay + (i == 0 ? 2 : 1), 0xFFFFFFFF);
		}
		// the wind: an arrow in the corner
		float seconds = (mc.level.getGameTime() % 72000L) / 20.0F;
		float dir = Wind.direction(seconds);
		float strength = Wind.strength(seconds, partialTick);
		int wx = x0 + view - 22;
		int wy = y0 + 22;
		for (int i = -8; i <= 8; i++) {
			int ax = Math.round(wx + Mth.cos(dir) * i);
			int ay = Math.round(wy + Mth.sin(dir) * i);
			g.fill(ax, ay, ax + 1, ay + 1, i > 5 ? 0xFFFFFFFF : 0xC0FFFFFF);
		}
		g.fill(Math.round(wx + Mth.cos(dir) * 8) - 1, Math.round(wy + Mth.sin(dir) * 8) - 1, Math.round(wx + Mth.cos(dir) * 8) + 2,
			Math.round(wy + Mth.sin(dir) * 8) + 2, 0xFFFFFFFF);
		String beaufort = strength < 0.35F ? "•" : strength < 0.6F ? "••" : strength < 0.9F ? "•••" : "••••";
		g.drawString(this.font, Component.translatable("gui.ballisticmissiles.weather_map.wind", beaufort), x0 + view - 60, y0 + 36, 0xFFE0E0E0, true);
		// the news: a storm on its way, or here
		int ty = y0 + view + 4;
		long eta = StormFront.arrivesIn();
		if (mc.level.getThunderLevel(partialTick) > 0.5F) {
			g.drawString(this.font, Component.translatable("gui.ballisticmissiles.weather_map.storm_here"), x0, ty, 0xFFFF6040, true);
		} else if (eta >= 0L) {
			g.drawString(this.font, Component.translatable("gui.ballisticmissiles.weather_map.storm_in", Math.max(1L, (eta + 600L) / 1200L)), x0, ty, 0xFFFFC040,
				true);
		}
		g.drawString(this.font, Component.translatable("gui.ballisticmissiles.weather_map.scale", (int) across), x0, ty + 12, 0xFF909AA8, true);
	}
}
