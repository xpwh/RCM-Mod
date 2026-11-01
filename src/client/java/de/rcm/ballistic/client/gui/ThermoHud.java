package de.rcm.ballistic.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * The temperature right of the hotbar: a round badge with nothing on it but the degrees, its rim in the air's
 * colour. It lives with the weather:
 * <ul>
 *   <li>heat: a crown of fire licks up round the badge - tongues white-yellow at the root, orange, red, dark at
 *   the tips - sparks fly off it trailing light, the badge glows from inside and the figures shimmer in the hot
 *   air;</li>
 *   <li>cold: frost grows out from the rim as a crown of ice spikes with side twigs, ice ferns creep in over the
 *   glass, the rim catches glints that twinkle, and six-armed snowflakes turn as they drift down past it;</li>
 *   <li>wet: water stands in the glass as high as you are soaked, its surface rocking, and drops gather under the
 *   badge, swell and fall.</li>
 * </ul>
 * Everything fades in and out with the weather.
 */
public final class ThermoHud {
	private static final float RADIUS = 17.0F;
	private static final RandomSource R = RandomSource.create();
	private static final List<Bit> BITS = new ArrayList<>();
	private static long last = -1L;
	private static float heatShown;
	private static float coldShown;
	private static float wetShown;
	private static float spawnDrop;
	private static float spawnFlame;
	private static float spawnSpark;
	private static float spawnSnow;

	/** A flame tongue (0), a spark with its trail (1), a snowflake (2) or a drop of water (3), relative to the badge's centre. */
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
		final float[] trail = new float[10];
		int trailN;

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
		int[] col = {0x4F86FF, 0x7FC4FF, 0xB8E4FF, 0xE6EEE8, 0xFFB050, 0xFF5A3C};
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

	/**
	 * @param air the air's temperature as felt (deg C); @param body the body, -1 freezing .. 0 fine .. 1 heat stroke;
	 * @param wet how wet the clothes are, 0-1
	 */
	public static void render(GuiGraphics g, float air, float body, float wet) {
		Minecraft mc = Minecraft.getInstance();
		UiKit.init();
		long now = net.minecraft.util.Util.getMillis();
		float dt = last < 0L ? 0.0F : Mth.clamp((now - last) / 1000.0F, 0.0F, 0.1F);
		last = now;
		float t = (now % 1_000_000L) / 1000.0F;
		float heat = Mth.clamp(Math.max((air - 27.0F) / 13.0F, (body - 0.1F) / 0.6F), 0.0F, 1.0F);
		float cold = Mth.clamp(Math.max((6.0F - air) / 20.0F, (-body - 0.1F) / 0.6F), 0.0F, 1.0F);
		heatShown = UiKit.approach(heatShown, heat, dt * 0.8F);
		coldShown = UiKit.approach(coldShown, cold, dt * 0.5F);
		wetShown = UiKit.approach(wetShown, wet, dt * 0.6F);
		float cx = g.guiWidth() / 2.0F + 91.0F + 9.0F + RADIUS;
		float cy = g.guiHeight() - RADIUS - 2.0F;
		int airC = airColour(air);
		float hot = Math.min(1.0F, heatShown * 1.4F);
		float icy = Math.min(1.0F, coldShown * 1.4F);

		spawn(dt);
		step(dt, t);

		// ---- under the badge: its shadow, the glow of the heat or the cold, the fire behind it
		UiKit.orb(g, cx - RADIUS * 1.6F, cy - RADIUS * 1.5F, RADIUS * 3.2F, RADIUS * 3.3F, 0x000000, 0.6F);
		if (hot > 0.01F) {
			float flicker = 0.8F + 0.2F * Mth.sin(t * 13.0F) * Mth.sin(t * 5.3F + 2.0F);
			UiKit.orb(g, cx - RADIUS * 2.4F, cy - RADIUS * 2.9F, RADIUS * 4.8F, RADIUS * 4.6F, 0xFF5A14, 0.5F * hot * flicker);
			UiKit.orb(g, cx - RADIUS * 1.6F, cy - RADIUS * 1.9F, RADIUS * 3.2F, RADIUS * 3.2F, 0xFFB040, 0.35F * hot * flicker);
		}
		if (icy > 0.01F) {
			float breathe = 0.85F + 0.15F * Mth.sin(t * 1.4F);
			UiKit.orb(g, cx - RADIUS * 2.1F, cy - RADIUS * 2.1F, RADIUS * 4.2F, RADIUS * 4.2F, 0x8FD8FF, 0.38F * icy * breathe);
		}
		drawFlames(g, cx, cy);
		if (icy > 0.02F) {
			crown(g, cx, cy, coldShown, t);
		}

		// ---- the badge: a rim in the air's colour (turning fire or ice), the glass inside, its sheen
		int rim = UiKit.lerpColor(UiKit.lerpColor(UiKit.lerpColor(airC, 0x2A3038, 0.35F), 0xFF7A2A, hot), 0xD8F2FF, icy);
		UiKit.disc(g, cx, cy, RADIUS, UiKit.argb(1.0F, rim));
		// a light running round the rim: fast and fiery in the heat, slow and cold in the frost
		float spin = t * (1.2F + 3.0F * hot) * (icy > hot ? 0.5F : 1.0F);
		for (int i = 0; i < 40; i++) {
			float a = i / 40.0F * Mth.TWO_PI;
			float lit = Mth.cos(a - spin);
			lit = lit * lit * lit * lit * Math.max(0.0F, Math.signum(lit));
			if (lit < 0.05F) {
				continue;
			}
			int c = hot > icy ? 0xFFE8A0 : icy > 0.05F ? 0xFFFFFF : UiKit.lerpColor(airC, 0xFFFFFF, 0.6F);
			UiKit.disc(g, cx + Mth.cos(a) * (RADIUS - 0.9F), cy + Mth.sin(a) * (RADIUS - 0.9F), 1.1F, UiKit.argb(lit * (0.35F + 0.5F * Math.max(hot, icy)), c));
		}
		int glass = UiKit.lerpColor(UiKit.lerpColor(0x13171D, 0x2C1206, hot), 0x112434, icy);
		UiKit.disc(g, cx, cy, RADIUS - 1.8F, UiKit.argb(1.0F, glass));
		// inside: lit from below by the fire, misted by the frost; a sheen across the top as on glass
		if (hot > 0.01F) {
			float pulse = 0.75F + 0.25F * Mth.sin(t * 6.0F);
			UiKit.orb(g, cx - RADIUS * 0.9F, cy - RADIUS * 0.2F, RADIUS * 1.8F, RADIUS * 1.3F, 0xFF7A1E, 0.55F * hot * pulse);
		}
		if (icy > 0.01F) {
			UiKit.orb(g, cx - RADIUS * 0.95F, cy - RADIUS * 0.95F, RADIUS * 1.9F, RADIUS * 1.9F, 0xCFEFFF, 0.3F * icy);
			ferns(g, cx, cy, coldShown);
		}
		if (wetShown > 0.02F) {
			water(g, cx, cy, wetShown, t);
		}
		UiKit.orb(g, cx - RADIUS * 0.75F, cy - RADIUS * 0.95F, RADIUS * 1.5F, RADIUS * 0.9F, 0xFFFFFF, 0.1F);

		// ---- the degrees: shimmering in the heat, frosted in the cold
		figures(g, mc.font, Math.round(air) + "°C", cx, cy, airC, t);

		// ---- over it all: sparks with their trails, glints on the frozen rim, the snow
		drawSparks(g, cx, cy);
		if (icy > 0.05F) {
			glints(g, cx, cy, icy, t);
		}
		drawSnow(g, cx, cy, t);
		drawDrops(g, cx, cy);
	}

	/** Water in the glass: a level as high as you are wet, its surface rocking in two slow waves, a bright rim. */
	private static void water(GuiGraphics g, float cx, float cy, float wet, float t) {
		float inner = RADIUS - 1.8F;
		float level = cy + inner - wet * inner * 1.6F;
		int x0 = Mth.floor(cx - inner);
		int x1 = Mth.ceil(cx + inner);
		for (int x = x0; x < x1; x++) {
			float dx = x + 0.5F - cx;
			float half = inner * inner - dx * dx;
			if (half <= 0.0F) {
				continue;
			}
			float chord = Mth.sqrt(half);
			float bottom = cy + chord;
			float top = Math.max(cy - chord, level + 0.9F * Mth.sin(t * 2.6F + x * 0.45F) + 0.5F * Mth.sin(t * 4.1F - x * 0.8F));
			if (top >= bottom) {
				continue;
			}
			int yt = Math.round(top);
			int yb = Math.round(bottom);
			g.fillGradient(x, yt, x + 1, yb, UiKit.argb(0.5F * Math.min(1.0F, wet * 2.0F), 0x4AA0F0), UiKit.argb(0.7F * Math.min(1.0F, wet * 2.0F), 0x1A4C8C));
			g.fill(x, yt, x + 1, yt + 1, UiKit.argb(0.75F * Math.min(1.0F, wet * 2.0F), 0xBFE8FF));
		}
	}

	private static void drawDrops(GuiGraphics g, float cx, float cy) {
		for (Bit b : BITS) {
			if (b.kind != 3) {
				continue;
			}
			float p = b.age / b.life;
			// hanging and swelling first, then falling, drawn out a little as it goes
			float swell = Math.min(1.0F, b.age / 0.6F);
			float a = Math.min(1.0F, (1.0F - p) * 4.0F) * Math.min(1.0F, wetShown * 2.0F);
			float px = cx + b.x;
			float py = cy + b.y;
			float r = b.size * (0.5F + 0.5F * swell);
			float stretch = b.vy > 1.0F ? Math.min(2.2F, 1.0F + b.vy / 40.0F) : 1.0F;
			UiKit.orb(g, px - r * 1.6F, py - r * 1.6F * stretch, r * 3.2F, r * 3.2F * stretch, 0x6AB8FF, a * 0.35F);
			UiKit.disc(g, px, py, r, UiKit.argb(a * 0.85F, 0x5AAEF5));
			UiKit.disc(g, px - r * 0.35F, py - r * 0.35F, r * 0.35F, UiKit.argb(a, 0xE8F6FF));
		}
	}

	private static void figures(GuiGraphics g, Font font, String text, float cx, float cy, int airC, float t) {
		float hot = Math.min(1.0F, heatShown * 1.4F);
		float icy = Math.min(1.0F, coldShown * 1.4F);
		int ink = UiKit.lerpColor(UiKit.lerpColor(UiKit.lerpColor(airC, 0xFFFFFF, 0.55F), 0xFFF2C8, hot), 0xF2FBFF, icy);
		if (hot > 0.05F) {
			UiKit.orb(g, cx - 14, cy - 8, 28, 16, 0xFFC060, 0.35F * hot);
		}
		float x = cx - font.width(text) / 2.0F;
		for (int i = 0; i < text.length(); i++) {
			String ch = String.valueOf(text.charAt(i));
			// the hot air bends the light: each figure wavers on its own
			float wave = hot * 0.7F * Mth.sin(t * 9.0F + i * 1.7F);
			g.pose().pushMatrix();
			g.pose().translate(x, cy - 3.5F + wave);
			g.drawString(font, ch, 0, 0, 0xFF000000 | ink, true);
			g.pose().popMatrix();
			x += font.width(ch);
		}
	}

	private static void spawn(float dt) {
		if (heatShown > 0.02F) {
			spawnFlame += dt * (30.0F + 80.0F * heatShown) * Math.min(1.0F, heatShown * 1.5F);
			while (spawnFlame >= 1.0F) {
				spawnFlame -= 1.0F;
				// from the rim, mostly its upper half, licking up and a little outward
				float a = -Mth.HALF_PI + (float) R.nextGaussian() * 1.05F;
				float ox = Mth.cos(a);
				float oy = Mth.sin(a);
				float sp = 0.6F + 0.6F * heatShown;
				BITS.add(new Bit(0, ox * (RADIUS - 2.0F), oy * (RADIUS - 2.0F), ox * 7.0F * sp, (oy * 6.0F - 14.0F - R.nextFloat() * 12.0F) * sp,
					0.4F + R.nextFloat() * 0.45F, (2.6F + R.nextFloat() * 3.2F) * (0.7F + 0.6F * heatShown)));
			}
			spawnSpark += dt * 10.0F * heatShown;
			while (spawnSpark >= 1.0F) {
				spawnSpark -= 1.0F;
				float a = -Mth.HALF_PI + (float) R.nextGaussian() * 0.8F;
				BITS.add(new Bit(1, Mth.cos(a) * RADIUS, Mth.sin(a) * RADIUS, Mth.cos(a) * 14.0F + (R.nextFloat() - 0.5F) * 10.0F,
					-(26.0F + R.nextFloat() * 26.0F), 0.9F + R.nextFloat() * 0.9F, 1.0F));
			}
		}
		if (coldShown > 0.02F) {
			spawnSnow += dt * 6.0F * coldShown;
			while (spawnSnow >= 1.0F) {
				spawnSnow -= 1.0F;
				BITS.add(new Bit(2, (R.nextFloat() - 0.5F) * RADIUS * 4.0F, -RADIUS * 2.2F, 0.0F, 5.0F + R.nextFloat() * 6.0F,
					4.0F + R.nextFloat() * 2.0F, 1.6F + R.nextFloat() * 1.8F));
			}
		}
		if (wetShown > 0.05F) {
			spawnDrop += dt * 1.6F * wetShown;
			while (spawnDrop >= 1.0F) {
				spawnDrop -= 1.0F;
				// a drop gathers under the badge's lower rim
				float a = Mth.HALF_PI + (R.nextFloat() - 0.5F) * 1.4F;
				BITS.add(new Bit(3, Mth.cos(a) * RADIUS, Mth.sin(a) * RADIUS, 0.0F, 0.0F, 1.6F + R.nextFloat() * 0.6F, 1.1F + R.nextFloat() * 0.7F));
			}
		}
		while (BITS.size() > 320) {
			BITS.removeFirst();
		}
	}

	private static void step(float dt, float t) {
		BITS.removeIf(b -> (b.age += dt) >= b.life);
		for (Bit b : BITS) {
			if (b.kind == 0) {
				// a tongue flickers sideways and speeds up as it rises
				b.vx += Mth.sin(t * 12.0F + b.seed) * 22.0F * dt;
				b.vy -= 14.0F * dt;
			} else if (b.kind == 1) {
				for (int i = b.trail.length - 1; i >= 2; i--) {
					b.trail[i] = b.trail[i - 2];
				}
				b.trail[0] = b.x;
				b.trail[1] = b.y;
				b.trailN = Math.min(b.trail.length / 2, b.trailN + 1);
				b.vx += Mth.sin(t * 7.0F + b.seed) * 40.0F * dt;
				b.vx *= 1.0F - dt * 0.8F;
				b.vy += 6.0F * dt;
			} else if (b.kind == 2) {
				b.vx = Mth.sin(t * 1.1F + b.seed) * 5.0F;
			} else if (b.age > 0.6F) {
				// it lets go and falls
				b.vy += 160.0F * dt;
			}
			b.x += b.vx * dt;
			b.y += b.vy * dt;
		}
	}

	/** The fire round the badge: each tongue a soft flame, hot white at its root, burning out red into smoke. */
	private static void drawFlames(GuiGraphics g, float cx, float cy) {
		float hot = Math.min(1.0F, heatShown * 1.6F);
		for (Bit b : BITS) {
			if (b.kind != 0) {
				continue;
			}
			float p = b.age / b.life;
			int c = p < 0.2F ? UiKit.lerpColor(0xFFF6D0, 0xFFC040, p / 0.2F)
				: p < 0.55F ? UiKit.lerpColor(0xFFC040, 0xFF5418, (p - 0.2F) / 0.35F) : UiKit.lerpColor(0xFF5418, 0x40180E, (p - 0.55F) / 0.45F);
			float a = (float) Math.pow(1.0F - p, 1.2) * hot;
			float s = b.size * (1.0F - 0.5F * p);
			float px = cx + b.x;
			float py = cy + b.y;
			UiKit.orb(g, px - s, py - s * 1.7F, s * 2.0F, s * 3.4F, c, a);
			if (p < 0.35F) {
				UiKit.orb(g, px - s * 0.45F, py - s * 0.9F, s * 0.9F, s * 1.8F, 0xFFFDF0, a * 0.8F);
			}
		}
	}

	private static void drawSparks(GuiGraphics g, float cx, float cy) {
		float hot = Math.min(1.0F, heatShown * 1.5F);
		for (Bit b : BITS) {
			if (b.kind != 1) {
				continue;
			}
			float p = b.age / b.life;
			float a = (1.0F - p) * hot;
			int c = UiKit.lerpColor(0xFFF0B0, 0xFF5A20, p);
			// the trail it leaves, fading behind it
			for (int i = 0; i < b.trailN; i++) {
				float f = 1.0F - (i + 1.0F) / (b.trailN + 1.0F);
				UiKit.disc(g, cx + b.trail[i * 2], cy + b.trail[i * 2 + 1], 0.6F * f + 0.25F, UiKit.argb(a * f * 0.7F, c));
			}
			UiKit.orb(g, cx + b.x - 3, cy + b.y - 3, 6, 6, c, a * 0.6F);
			UiKit.disc(g, cx + b.x, cy + b.y, 0.8F, UiKit.argb(a, 0xFFFBE8));
		}
	}

	/** Snowflakes: six arms with twigs, turning slowly as they drift down. */
	private static void drawSnow(GuiGraphics g, float cx, float cy, float t) {
		float icy = Math.min(1.0F, coldShown * 1.6F);
		for (Bit b : BITS) {
			if (b.kind != 2) {
				continue;
			}
			float p = b.age / b.life;
			float a = Math.min(1.0F, p * 5.0F) * Math.min(1.0F, (1.0F - p) * 3.0F) * icy;
			if (a < 0.02F) {
				continue;
			}
			float px = cx + b.x;
			float py = cy + b.y;
			float rot = t * 0.8F + b.seed;
			for (int k = 0; k < 6; k++) {
				float ang = rot + k * Mth.PI / 3.0F;
				line(g, px, py, ang, b.size, UiKit.argb(a * 0.9F, 0xF4FBFF));
				float mx = px + Mth.cos(ang) * b.size * 0.55F;
				float my = py + Mth.sin(ang) * b.size * 0.55F;
				line(g, mx, my, ang + 0.9F, b.size * 0.35F, UiKit.argb(a * 0.7F, 0xF4FBFF));
				line(g, mx, my, ang - 0.9F, b.size * 0.35F, UiKit.argb(a * 0.7F, 0xF4FBFF));
			}
			UiKit.orb(g, px - b.size, py - b.size, b.size * 2.0F, b.size * 2.0F, 0xFFFFFF, a * 0.25F);
		}
	}

	/** A thin line of soft dots (half a pixel apart, so it reads smooth at any angle). */
	private static void line(GuiGraphics g, float x, float y, float angle, float length, int argb) {
		float ca = Mth.cos(angle);
		float sa = Mth.sin(angle);
		for (float i = 0; i <= length; i += 0.5F) {
			UiKit.disc(g, x + ca * i, y + sa * i, 0.45F, argb);
		}
	}

	/** The frost crown: ice spikes grown out from the rim, each with side twigs, as far as the cold has got. */
	private static void crown(GuiGraphics g, float cx, float cy, float cold, float t) {
		float grown = Mth.clamp(cold * 1.3F, 0.0F, 1.0F);
		RandomSource r = RandomSource.create(4242L);
		int spikes = 22;
		for (int i = 0; i < spikes; i++) {
			float a = i / (float) spikes * Mth.TWO_PI + (r.nextFloat() - 0.5F) * 0.2F;
			float len = 3.0F + r.nextFloat() * 6.5F;
			float sx = cx + Mth.cos(a) * (RADIUS - 0.5F);
			float sy = cy + Mth.sin(a) * (RADIUS - 0.5F);
			float shimmer = 0.75F + 0.25F * Mth.sin(t * 2.0F + i * 1.3F);
			ice(g, sx, sy, a, len, grown, 0.85F * shimmer * Math.min(1.0F, cold * 1.6F), r, 1);
		}
	}

	/** Ice ferns creeping in over the glass from the rim (faint, the figures stay readable). */
	private static void ferns(GuiGraphics g, float cx, float cy, float cold) {
		float grown = Mth.clamp((cold - 0.15F) * 1.4F, 0.0F, 1.0F);
		if (grown <= 0.0F) {
			return;
		}
		RandomSource r = RandomSource.create(9191L);
		for (int i = 0; i < 9; i++) {
			float a = i / 9.0F * Mth.TWO_PI + r.nextFloat() * 0.5F;
			float len = 4.0F + r.nextFloat() * 5.0F;
			float sx = cx + Mth.cos(a) * (RADIUS - 2.2F);
			float sy = cy + Mth.sin(a) * (RADIUS - 2.2F);
			ice(g, sx, sy, a + Mth.PI + (r.nextFloat() - 0.5F) * 0.6F, len, grown, 0.45F * grown, r, 1);
		}
	}

	private static void ice(GuiGraphics g, float sx, float sy, float a, float len, float grown, float alpha, RandomSource r, int depth) {
		float ca = Mth.cos(a);
		float sa = Mth.sin(a);
		float shown = len * grown;
		for (float i = 0; i < shown; i += 0.5F) {
			float f = 1.0F - 0.65F * i / len;
			UiKit.disc(g, sx + ca * i, sy + sa * i, 0.5F * f + 0.15F, UiKit.argb(alpha * f, 0xE6F7FF));
		}
		if (depth <= 0) {
			return;
		}
		// twigs off both sides, the shape fixed, only how far it has grown follows the cold
		for (float i = 1.5F; i < len - 1.0F; i += 1.8F + r.nextFloat() * 1.2F) {
			float tl = (len - i) * (0.35F + r.nextFloat() * 0.25F);
			float at = i * grown;
			ice(g, sx + ca * at, sy + sa * at, a + 1.05F, tl, grown, alpha * 0.75F, r, depth - 1);
			ice(g, sx + ca * at, sy + sa * at, a - 1.05F, tl, grown, alpha * 0.75F, r, depth - 1);
		}
	}

	/** Light catching the frozen rim: four-pointed stars twinkling here and there. */
	private static void glints(GuiGraphics g, float cx, float cy, float icy, float t) {
		for (int i = 0; i < 4; i++) {
			float phase = t * 0.7F + i * 1.618F;
			float k = phase - Mth.floor(phase);
			float tw = Mth.sin(k * Mth.PI);
			tw = tw * tw * tw;
			if (tw < 0.05F) {
				continue;
			}
			int cycle = Mth.floor(phase);
			float a = (cycle * 2.399F + i * 1.7F) % Mth.TWO_PI;
			float px = cx + Mth.cos(a) * (RADIUS + 0.5F);
			float py = cy + Mth.sin(a) * (RADIUS + 0.5F);
			float s = 2.0F + 3.0F * tw;
			int c = UiKit.argb(tw * icy, 0xFFFFFF);
			line(g, px - s, py, 0.0F, s * 2.0F, c);
			line(g, px, py - s, Mth.HALF_PI, s * 2.0F, c);
			UiKit.orb(g, px - s, py - s, s * 2.0F, s * 2.0F, 0xFFFFFF, 0.5F * tw * icy);
		}
	}
}
