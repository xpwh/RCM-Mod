package de.rcm.ballistic.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import de.rcm.ballistic.BallisticMissiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The soft shapes the mod's cards are drawn with - rounded rectangles (filled or as a hairline edge), circles and a
 * radial glow for shadows - from small textures drawn once at start-up and scaled with smooth filtering, so the
 * edges stay clean at any GUI scale; and the easing and colour helpers that go with them.
 */
public final class UiKit {
	public static final Identifier ORB = BallisticMissiles.id("dynamic/ui_orb");
	public static final Identifier FILL = BallisticMissiles.id("dynamic/ui_round_fill");
	public static final Identifier EDGE = BallisticMissiles.id("dynamic/ui_round_edge");
	public static final Identifier DISC = BallisticMissiles.id("dynamic/ui_disc");
	/** A warning triangle with softly rounded corners, point up, filling its square texture's width. */
	public static final Identifier TRIANGLE = BallisticMissiles.id("dynamic/ui_triangle");
	/** Rounded-rectangle textures: 64 px with a 24 px corner radius (nine-sliced), the edge 2 px wide. */
	private static final int TEX = 64;
	private static final int CORNER = 24;
	private static boolean texturesMade;

	private UiKit() {
	}


	/** A soft light (radial falloff) stretched over a rectangle: the card's shadow. */
	public static void orb(GuiGraphics g, float x, float y, float w, float h, int rgb, float alpha) {
		if (alpha <= 0.003F) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / 128.0F, h / 128.0F);
		g.blit(RenderPipelines.GUI_TEXTURED, ORB, 0, 0, 0.0F, 0.0F, 128, 128, 128, 128, 128, 128, argb(alpha, rgb));
		g.pose().popMatrix();
	}

	/** A filled circle with a clean edge. */
	public static void disc(GuiGraphics g, float x, float y, float radius, int argb) {
		if ((argb >>> 24) == 0) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x - radius, y - radius);
		g.pose().scale(radius * 2.0F / 128.0F, radius * 2.0F / 128.0F);
		g.blit(RenderPipelines.GUI_TEXTURED, DISC, 0, 0, 0.0F, 0.0F, 128, 128, 128, 128, 128, 128, argb);
		g.pose().popMatrix();
	}

	/** A rounded rectangle (filled, or just its edge), nine-sliced from a texture so the corners stay smooth. */
	public static void round(GuiGraphics g, Identifier tex, int x, int y, int w, int h, int r, int argb) {
		if ((argb >>> 24) == 0) {
			return;
		}
		r = Math.min(r, Math.min(w, h) / 2);
		int c = CORNER;
		int m = TEX - 2 * c;
		int far = TEX - c;
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, r, r, c, c, TEX, TEX, argb);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x + w - r, y, far, 0, r, r, c, c, TEX, TEX, argb);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y + h - r, 0, far, r, r, c, c, TEX, TEX, argb);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x + w - r, y + h - r, far, far, r, r, c, c, TEX, TEX, argb);
		if (w > 2 * r) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x + r, y, c, 0, w - 2 * r, r, m, c, TEX, TEX, argb);
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x + r, y + h - r, c, far, w - 2 * r, r, m, c, TEX, TEX, argb);
		}
		if (h > 2 * r) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y + r, 0, c, r, h - 2 * r, c, m, TEX, TEX, argb);
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x + w - r, y + r, far, c, r, h - 2 * r, c, m, TEX, TEX, argb);
		}
		if (w > 2 * r && h > 2 * r) {
			g.blit(RenderPipelines.GUI_TEXTURED, tex, x + r, y + r, c, c, w - 2 * r, h - 2 * r, m, m, TEX, TEX, argb);
		}
	}

	/** Draws the soft shapes once: a radial glow, a disc, and a rounded square filled and as an outline. */
	public static void init() {
		if (texturesMade) {
			return;
		}
		texturesMade = true;
		var manager = Minecraft.getInstance().getTextureManager();
		manager.register(ORB, smooth("ui orb", 128, (px, py) -> {
			double d = Math.hypot(px - 64.0, py - 64.0) / 64.0;
			double f = Math.max(0.0, 1.0 - d);
			return f * f * (3.0 - 2.0 * f) * f;
		}));
		manager.register(DISC, smooth("ui disc", 128, (px, py) -> coverage(62.0 - Math.hypot(px - 64.0, py - 64.0))));
		manager.register(TRIANGLE, smooth("ui triangle", 128, (px, py) -> coverage(-(triangle(px, py) - 9.0))));
		manager.register(FILL, smooth("ui round fill", TEX, (px, py) -> coverage(-roundedBox(px, py))));
		manager.register(EDGE, smooth("ui round edge", TEX, (px, py) -> {
			double d = roundedBox(px, py);
			return coverage(-d) - coverage(-(d + 2.2));
		}));
	}

	/** Signed distance (texture pixels) to a triangle, point up, inset so its rounded version fills the texture. */
	private static double triangle(double px, double py) {
		double[][] v = {{64.0, 12.0}, {119.0, 112.0}, {9.0, 112.0}};
		double best = Double.MAX_VALUE;
		boolean inside = true;
		for (int i = 0; i < 3; i++) {
			double[] a = v[i];
			double[] b = v[(i + 1) % 3];
			double ex = b[0] - a[0];
			double ey = b[1] - a[1];
			double wx = px - a[0];
			double wy = py - a[1];
			double h = Mth.clamp((wx * ex + wy * ey) / (ex * ex + ey * ey), 0.0, 1.0);
			best = Math.min(best, Math.hypot(wx - ex * h, wy - ey * h));
			if (ex * wy - ey * wx < 0.0) {
				inside = false;
			}
		}
		return inside ? -best : best;
	}

	/** The warning triangle, stretched over a rectangle. */
	public static void triangle(GuiGraphics g, float x, float y, float w, float h, int argb) {
		if ((argb >>> 24) == 0) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / 128.0F, h / 128.0F);
		g.blit(RenderPipelines.GUI_TEXTURED, TRIANGLE, 0, 0, 0.0F, 0.0F, 128, 128, 128, 128, 128, 128, argb);
		g.pose().popMatrix();
	}

	/** Signed distance (texture pixels) to the rounded square filling the texture, negative inside. */
	private static double roundedBox(double px, double py) {
		double half = TEX / 2.0;
		double qx = Math.abs(px - half) - (half - CORNER);
		double qy = Math.abs(py - half) - (half - CORNER);
		double outside = Math.hypot(Math.max(qx, 0.0), Math.max(qy, 0.0));
		return outside + Math.min(Math.max(qx, qy), 0.0) - CORNER;
	}

	private static double coverage(double inside) {
		return Mth.clamp(inside + 0.5, 0.0, 1.0);
	}

	private interface Shape {
		double at(double x, double y);
	}

	/** A white texture whose alpha is the shape, supersampled 4x4, with smooth (linear) filtering. */
	private static DynamicTexture smooth(String name, int size, Shape shape) {
		NativeImage image = new NativeImage(size, size, false);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				double sum = 0.0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						sum += shape.at(x + (sx + 0.5) / 4.0, y + (sy + 0.5) / 4.0);
					}
				}
				int a = (int) Math.round(Mth.clamp(sum / 16.0, 0.0, 1.0) * 255.0);
				image.setPixel(x, y, a << 24 | 0xFFFFFF);
			}
		}
		return new SmoothTexture(name, image);
	}

	private static final class SmoothTexture extends DynamicTexture {
		SmoothTexture(String name, NativeImage image) {
			super(() -> name, image);
			this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
		}
	}


	public static float approach(float value, float target, float rate) {
		return value + (target - value) * Math.min(1.0F, rate);
	}

	public static float ease(float x) {
		return x * x * (3.0F - 2.0F * x);
	}

	public static float easeOutExpo(float x) {
		return x >= 1.0F ? 1.0F : 1.0F - (float) Math.pow(2.0, -10.0 * x);
	}

	public static float easeOutBack(float x) {
		float c1 = 1.9F;
		float k = x - 1.0F;
		return 1.0F + (c1 + 1.0F) * k * k * k + c1 * k * k;
	}

	public static int argb(float alpha, int rgb) {
		return Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24 | rgb & 0xFFFFFF;
	}

	public static int lerpColor(int a, int b, float f) {
		f = Mth.clamp(f, 0.0F, 1.0F);
		int r = Math.round(Mth.lerp(f, a >> 16 & 255, b >> 16 & 255));
		int gr = Math.round(Mth.lerp(f, a >> 8 & 255, b >> 8 & 255));
		int bl = Math.round(Mth.lerp(f, a & 255, b & 255));
		return r << 16 | gr << 8 | bl;
	}

	public static int lerpArgb(int a, int b, float f) {
		f = Mth.clamp(f, 0.0F, 1.0F);
		int al = Math.round(Mth.lerp(f, a >>> 24, b >>> 24));
		return al << 24 | lerpColor(a, b, f);
	}
}
