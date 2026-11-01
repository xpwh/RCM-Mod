package de.rcm.ballistic.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The thermometer right of the hotbar: a glass with the air's temperature risen in it, the degrees, and how the
 * body copes. It lives with the weather: in the heat flames lick up from under it, sparks fly and it glows; in
 * hard cold frost grows in from its corners as ice ferns, the glass mists over and snow drifts down past it.
 */
public final class ThermoHud {
	private static final int W = 76;
	private static final int H = 24;
	private static final RandomSource R = RandomSource.create();
	private static final List<Bit> BITS = new ArrayList<>();
	private static long last = -1L;
	private static float heatShown;
	private static float coldShown;
	private static float spawnHeat;
	private static float spawnEmber;
	private static float spawnSnow;

	/** A flame tongue (0), a spark (1) or a snowflake (2), placed relative to the plate's corner. */
	private static final class Bit {
		final int kind;
		float x;
		float y;
		float vx;
		float vy;
		float age;
		final float life;
		final float size;
		final float seed;

		Bit(int kind, float x, float y, float vx, float vy, float life, float size) {
			this.kind = kind;
			this.x = x;
			this.y = y;
			this.vx = vx;
			this.vy = vy;
			this.life = life;
			this.size = size;
			this.seed = R.nextFloat() * 100.0F;
		}
	}

	private ThermoHud() {
	}

	/** The air's colour: deep blue in frost, through cool and mild to orange and red heat. */
	static int airColour(float t) {
		float[] at = {-20.0F, 0.0F, 10.0F, 20.0F, 30.0F, 42.0F};
		int[] col = {0x5A8CFF, 0x7FC4FF, 0xBFE6FF, 0xE8F0E0, 0xFFB050, 0xFF5A3C};
		if (t <= at[0]) {
			return col[0];
		}
		for (int i = 1; i < at.length; i++) {
			if (t <= at[i]) {
				return UiKit.lerpColor(col[i - 1], col[i], (t - at[i - 1]) / (at[i] - at[i - 1]));
			}
		}
		return col[col.length - 1];
	}

	/** @param air the air's temperature (deg C); @param body the body, -1 freezing .. 0 fine .. 1 heat stroke */
	public static void render(GuiGraphics g, float air, float body) {
		Minecraft mc = Minecraft.getInstance();
		UiKit.init();
		long now = net.minecraft.util.Util.getMillis();
		float dt = last < 0L ? 0.0F : Mth.clamp((now - last) / 1000.0F, 0.0F, 0.1F);
		last = now;
		float t = (now % 1_000_000L) / 1000.0F;
		// how hot or cold it is to look at: the air's extremes, or the body feeling it
		float heat = Mth.clamp(Math.max((air - 27.0F) / 13.0F, (body - 0.1F) / 0.6F), 0.0F, 1.0F);
		float cold = Mth.clamp(Math.max((6.0F - air) / 20.0F, (-body - 0.1F) / 0.6F), 0.0F, 1.0F);
		heatShown = UiKit.approach(heatShown, heat, dt * 0.8F);
		coldShown = UiKit.approach(coldShown, cold, dt * 0.5F);
		int x0 = g.guiWidth() / 2 + 98;
		int y0 = g.guiHeight() - H - 1;

		// ---- what is round the plate: the glow of the heat or the cold
		if (heatShown > 0.01F) {
			float pulse = 0.85F + 0.15F * Mth.sin(t * 7.0F) * Mth.sin(t * 3.1F + 1.0F);
			UiKit.orb(g, x0 - 18, y0 - 22, W + 36, H + 34, 0xFF6A1E, 0.55F * heatShown * pulse);
		}
		if (coldShown > 0.01F) {
			UiKit.orb(g, x0 - 16, y0 - 14, W + 32, H + 28, 0x8FD8FF, 0.38F * coldShown);
		}
		// ---- the plate
		int tint = UiKit.lerpColor(UiKit.lerpColor(0x111419, 0x2A1309, heatShown), 0x0E1E2C, coldShown);
		UiKit.round(g, UiKit.FILL, x0, y0, W, H, 6, UiKit.argb(0.82F, tint));
		int edge = heatShown > coldShown ? UiKit.lerpColor(0x5A6270, 0xFF8A3A, heatShown) : UiKit.lerpColor(0x5A6270, 0xCDEFFF, coldShown);
		UiKit.round(g, UiKit.EDGE, x0, y0, W, H, 6, UiKit.argb(0.55F + 0.35F * Math.max(heatShown, coldShown), edge));

		// ---- the flames and sparks under and over the plate, the snow drifting past
		spawn(dt);
		step(dt, t);
		drawBits(g, x0, y0, 0);

		// ---- frost on the plate: a misted glaze, ice ferns grown in from the corners and along the edges
		if (coldShown > 0.02F) {
			g.fillGradient(x0 + 2, y0 + 1, x0 + W - 2, y0 + 6, UiKit.argb(0.35F * coldShown, 0xDDF4FF), UiKit.argb(0.0F, 0xDDF4FF));
			g.fillGradient(x0 + 2, y0 + H - 6, x0 + W - 2, y0 + H - 1, UiKit.argb(0.0F, 0xDDF4FF), UiKit.argb(0.3F * coldShown, 0xDDF4FF));
			frost(g, x0, y0, coldShown);
			// a glint running along the frozen rim
			float gx = x0 + ((t * 22.0F) % (W + 40)) - 20;
			UiKit.orb(g, gx - 6, y0 - 3, 12, 6, 0xFFFFFF, 0.5F * coldShown);
		}

		// ---- the glass: tube with marks, bulb, the column risen as warm as it is (-20 .. 45 degrees)
		int airC = airColour(air);
		int tx = x0 + 6;
		int ty = y0 + 3;
		int th = 12;
		UiKit.round(g, UiKit.FILL, tx - 1, ty - 1, 6, th + 3, 3, 0xFF2C323B);
		g.fill(tx + 1, ty, tx + 3, ty + th + 1, 0xFF0E1014);
		for (int i = 0; i < 4; i++) {
			g.fill(tx + 4, ty + 2 + i * 3, tx + 6, ty + 3 + i * 3, 0x90A0A8B4);
		}
		float level = Mth.clamp((air + 20.0F) / 65.0F, 0.0F, 1.0F);
		int top = ty + Math.round((1.0F - level) * th);
		int mercury = heatShown > 0.3F ? UiKit.lerpColor(airC, 0xFFF0C0, 0.35F * (0.5F + 0.5F * Mth.sin(t * 9.0F)) * heatShown) : airC;
		g.fill(tx + 1, top, tx + 3, ty + th + 1, 0xFF000000 | mercury);
		UiKit.disc(g, tx + 2.0F, ty + th + 3.5F, 4.2F, 0xFF2C323B);
		UiKit.disc(g, tx + 2.0F, ty + th + 3.5F, 3.0F, 0xFF000000 | mercury);
		UiKit.disc(g, tx + 1.2F, ty + th + 2.7F, 0.9F, 0xB0FFFFFF);
		if (coldShown > 0.3F) {
			// the glass misted over
			g.fill(tx + 1, ty, tx + 3, ty + th + 1, UiKit.argb(0.35F * coldShown, 0xE8F8FF));
		}

		// ---- the degrees, and under them the body
		String text = Math.round(air) + "°C";
		int textC = UiKit.lerpColor(airC, 0xFFFFFF, 0.15F);
		g.drawString(mc.font, text, x0 + 17, y0 + 4, 0xFF000000 | textC, true);
		String icon = heatShown > 0.35F ? "🔥" : coldShown > 0.35F ? "❄" : null;
		if (icon != null && "❄".equals(icon)) {
			g.drawString(mc.font, icon, x0 + W - 11, y0 + 4, UiKit.argb(0.9F, 0xDDF4FF), true);
		}
		int bx = x0 + 17;
		int bw = W - 23;
		int by = y0 + 16;
		int mid = bx + bw / 2;
		// a gradient from blue (freezing) over grey (fine) to orange (overheating)
		for (int i = 0; i < bw; i++) {
			float f = i / (float) (bw - 1) * 2.0F - 1.0F;
			int c = f < 0.0F ? UiKit.lerpColor(0x6A7480, 0x5AA8FF, -f) : UiKit.lerpColor(0x6A7480, 0xFF7A30, f);
			g.fill(bx + i, by, bx + i + 1, by + 2, UiKit.argb(0.55F, c));
		}
		g.fill(mid, by - 1, mid + 1, by + 3, 0xFFA8B0BA);
		float danger = Math.max(0.0F, Math.abs(body) - 0.55F) / 0.45F;
		float blink = danger > 0.0F ? 0.55F + 0.45F * Mth.sin(t * (4.0F + 7.0F * danger)) : 1.0F;
		int pos = Math.round(mid + Mth.clamp(body, -1.0F, 1.0F) * (bw / 2.0F - 1.0F));
		int knob = body < -0.05F ? 0x9AD4FF : body > 0.05F ? 0xFFAA50 : 0xF2F5F8;
		UiKit.disc(g, pos + 0.5F, by + 1.0F, 2.6F, UiKit.argb(blink, 0x0B0D10));
		UiKit.disc(g, pos + 0.5F, by + 1.0F, 1.9F, UiKit.argb(blink, knob));

		// ---- the tongues that lick over the top edge, drawn over the plate
		drawBits(g, x0, y0, 1);
	}

	private static void spawn(float dt) {
		if (heatShown > 0.02F) {
			spawnHeat += dt * (18.0F + 50.0F * heatShown) * heatShown;
			while (spawnHeat >= 1.0F) {
				spawnHeat -= 1.0F;
				float x = 3.0F + R.nextFloat() * (W - 6);
				BITS.add(new Bit(0, x, H - 1.0F, (R.nextFloat() - 0.5F) * 5.0F, -(12.0F + R.nextFloat() * 14.0F) * (0.6F + 0.6F * heatShown),
					0.45F + R.nextFloat() * 0.45F, (2.5F + R.nextFloat() * 3.0F) * (0.7F + 0.6F * heatShown)));
			}
			spawnEmber += dt * 9.0F * heatShown;
			while (spawnEmber >= 1.0F) {
				spawnEmber -= 1.0F;
				BITS.add(new Bit(1, 4.0F + R.nextFloat() * (W - 8), H * 0.6F, (R.nextFloat() - 0.5F) * 8.0F, -(22.0F + R.nextFloat() * 22.0F),
					0.8F + R.nextFloat() * 0.8F, 1.0F));
			}
		}
		if (coldShown > 0.02F) {
			spawnSnow += dt * 7.0F * coldShown;
			while (spawnSnow >= 1.0F) {
				spawnSnow -= 1.0F;
				BITS.add(new Bit(2, -6.0F + R.nextFloat() * (W + 12), -10.0F, 0.0F, 6.0F + R.nextFloat() * 6.0F, 3.0F + R.nextFloat() * 2.0F,
					0.7F + R.nextFloat() * 0.9F));
			}
		}
		while (BITS.size() > 260) {
			BITS.removeFirst();
		}
	}

	private static void step(float dt, float t) {
		BITS.removeIf(b -> (b.age += dt) >= b.life);
		for (Bit b : BITS) {
			if (b.kind == 1) {
				b.vx += Mth.sin(t * 6.0F + b.seed) * 30.0F * dt;
			} else if (b.kind == 2) {
				b.vx = Mth.sin(t * 1.3F + b.seed) * 4.0F;
			} else {
				// a flame flickers sideways and speeds up as it rises
				b.vx += Mth.sin(t * 11.0F + b.seed) * 18.0F * dt;
				b.vy -= 10.0F * dt;
			}
			b.x += b.vx * dt;
			b.y += b.vy * dt;
		}
	}

	/** Layer 0: what lies under the plate's content (flame bodies, snow); 1: the tongue tips over the top edge. */
	private static void drawBits(GuiGraphics g, int x0, int y0, int layer) {
		for (Bit b : BITS) {
			float p = b.age / b.life;
			float px = x0 + b.x;
			float py = y0 + b.y;
			if (b.kind == 0) {
				boolean over = b.y < 4.0F;
				if (over != (layer == 1)) {
					continue;
				}
				// white-yellow at the root, orange, red, then dark smoke as it dies away
				int c = p < 0.25F ? UiKit.lerpColor(0xFFF4C0, 0xFFB43C, p / 0.25F)
					: p < 0.6F ? UiKit.lerpColor(0xFFB43C, 0xFF4E1A, (p - 0.25F) / 0.35F) : UiKit.lerpColor(0xFF4E1A, 0x3A1A10, (p - 0.6F) / 0.4F);
				float a = (float) Math.pow(1.0F - p, 1.1) * 0.95F * Math.min(1.0F, heatShown * 1.6F);
				float s = b.size * (1.0F - 0.55F * p);
				UiKit.orb(g, px - s, py - s * 1.5F, s * 2.0F, s * 3.0F, c, a);
				if (p < 0.4F) {
					UiKit.orb(g, px - s * 0.5F, py - s * 0.8F, s, s * 1.6F, 0xFFFBE0, a * 0.7F);
				}
			} else if (b.kind == 1) {
				if (layer != 1) {
					continue;
				}
				float a = (1.0F - p) * Math.min(1.0F, heatShown * 1.5F);
				int c = UiKit.lerpColor(0xFFE6A0, 0xFF5A20, p);
				g.fill(Math.round(px), Math.round(py), Math.round(px) + 1, Math.round(py) + 1, UiKit.argb(a, c));
				UiKit.orb(g, px - 2, py - 2, 5, 5, c, a * 0.5F);
			} else {
				if (layer != 1) {
					continue;
				}
				float a = Math.min(1.0F, p * 6.0F) * (1.0F - p) * Math.min(1.0F, coldShown * 1.6F);
				UiKit.disc(g, px, py, b.size, UiKit.argb(a * 0.9F, 0xF4FBFF));
			}
		}
	}

	/** Ice ferns: rays grown in from the corners and the edges, with side twigs, as far as the cold has got. */
	private static void frost(GuiGraphics g, int x0, int y0, float cold) {
		float[][] roots = {
			{0, 0, 0.35F}, {W, 0, 2.8F}, {0, H, -0.4F}, {W, H, 3.6F},
			{W * 0.3F, 0, 1.45F}, {W * 0.62F, 0, 1.75F}, {W * 0.45F, H, -1.5F}, {W * 0.8F, H, -1.8F}
		};
		for (int n = 0; n < roots.length; n++) {
			float[] root = roots[n];
			// the same fern every frame: its shape fixed, only how far it has grown follows the cold
			RandomSource r = RandomSource.create(7331L + n * 101L);
			int rays = 2 + r.nextInt(2);
			for (int k = 0; k < rays; k++) {
				float a = root[2] + (r.nextFloat() - 0.5F) * 0.9F;
				float len = 5.0F + r.nextFloat() * 9.0F;
				ray(g, x0 + root[0], y0 + root[1], a, len, cold, 0.75F * Math.min(1.0F, cold * 1.5F), r, 2);
			}
		}
	}

	private static void ray(GuiGraphics g, float sx, float sy, float a, float len, float grown, float alpha, RandomSource r, int depth) {
		float ca = Mth.cos(a);
		float sa = Mth.sin(a);
		float shown = len * grown;
		for (float i = 0; i < shown; i += 1.0F) {
			int px = Math.round(sx + ca * i);
			int py = Math.round(sy + sa * i);
			g.fill(px, py, px + 1, py + 1, UiKit.argb(alpha * (1.0F - 0.6F * i / len), 0xE4F6FF));
		}
		if (depth <= 0) {
			return;
		}
		// twigs off both sides, shorter towards the tip
		for (float i = 2.0F; i < len - 1.0F; i += 2.0F + r.nextFloat() * 1.5F) {
			float tl = (len - i) * (0.35F + r.nextFloat() * 0.2F);
			float at = i * grown;
			ray(g, sx + ca * at, sy + sa * at, a + 1.0F, tl, grown, alpha * 0.8F, r, depth - 1);
			ray(g, sx + ca * at, sy + sa * at, a - 1.0F, tl, grown, alpha * 0.8F, r, depth - 1);
		}
	}
}
