package de.rcm.ballistic.client.gun;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.client.item.RpgClient;
import de.rcm.ballistic.gun.AkItem;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * What you see when you aim.
 * <ul>
 * <li><b>AK-47</b>: the iron sights come up to the eye; a small dot marks the exact point of aim in the
 * middle of the front sight post - where the bullet goes (it can be switched off in the settings).</li>
 * <li><b>RPG-7</b>: the eye goes to the PGO-7 optical sight, 2.7x: the dark tube round the picture and its
 * reticle - the aiming chevron, the chevrons below it for 100 to 400 m (each sits as far below the middle
 * as the rocket drops at that range, so you put the right one on the target), the lead scale either side
 * for moving targets, and in the lower left the rangefinder: a tank-sized target (2.7 m tall) that fills
 * the space between the base line and the curve is at the range written there.</li>
 * </ul>
 */
public final class AimOverlay {
	/** Magnification of the PGO-7. */
	public static final float SCOPE_ZOOM = 2.7F;
	/** How far below the middle the rocket strikes at 100, 200, 300 and 400 m (degrees). */
	private static final float[] DROP = {3.31F, 4.29F, 7.03F, 11.06F};
	private static final int INK = 0xF0101010;
	private static final int HALO = 0x50E8E0C8;

	private AimOverlay() {
	}

	public static void init() {
		HudElementRegistry.addFirst(BallisticMissiles.id("aim_overlay"), (graphics, tick) -> render(graphics, tick.getGameTimeDeltaPartialTick(false)));
	}

	/** How far the eye is into the RPG's optical sight (0-1): the last part of bringing it up. */
	public static float scope(float partialTick) {
		float a = RpgClient.aimProgress(partialTick);
		float s = Mth.clamp((a - 0.7F) / 0.3F, 0.0F, 1.0F);
		return s * s * (3.0F - 2.0F * s);
	}

	private static void render(GuiGraphics g, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !mc.options.getCameraType().isFirstPerson() || mc.options.hideGui) {
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		float scope = scope(partialTick);
		if (scope > 0.0F && de.rcm.ballistic.item.RocketLauncherItem.isAiming(mc.player)) {
			scope(g, mc, w, h, scope);
			return;
		}
		float ak = AkClient.aimProgress(partialTick);
		if (ak > 0.6F && ModConfig.aimDot && AkItem.isAiming(mc.player)) {
			// the point of aim: a small glowing dot with a dark rim, on the tip of the front sight post
			int a = (int) (Mth.clamp((ak - 0.6F) / 0.4F, 0.0F, 1.0F) * 230.0F);
			int cx = w / 2;
			int cy = h / 2;
			g.fill(cx - 1, cy - 1, cx + 2, cy + 2, (a * 3 / 4) << 24);
			g.fill(cx, cy, cx + 1, cy + 1, a << 24 | 0xFF3A20);
		}
	}

	/** Screen offset (GUI pixels) of a direction {@code deg} degrees off the middle of the view. */
	private static float offset(Minecraft mc, int h, float deg) {
		float base = mc.options.fov().get().floatValue();
		float mod = mc.player.getFieldOfViewModifier(true, mc.options.fovEffectScale().get().floatValue());
		double half = Math.toRadians(base * mod * 0.5);
		return (float) (h * 0.5 * Math.tan(Math.toRadians(deg)) / Math.tan(half));
	}

	private static void scope(GuiGraphics g, Minecraft mc, int w, int h, float amount) {
		int cx = w / 2;
		int cy = h / 2;
		float r = h * 0.46F;
		int alpha = (int) (amount * 255.0F);
		// the tube: black round the field of view, its rim softening into the picture
		for (int y = 0; y < h; y++) {
			float dy = y + 0.5F - cy;
			float span = r * r - dy * dy;
			if (span <= 0.0F) {
				g.fill(0, y, w, y + 1, alpha << 24);
				continue;
			}
			int half = (int) Math.sqrt(span);
			g.fill(0, y, cx - half, y + 1, alpha << 24);
			g.fill(cx + half, y, w, y + 1, alpha << 24);
			for (int ring = 1; ring <= 6; ring++) {
				float ri = r - ring * 2.0F;
				float s2 = ri * ri - dy * dy;
				int inner = s2 > 0.0F ? (int) Math.sqrt(s2) : 0;
				int a = (int) (alpha * 0.16F);
				g.fill(cx - half, y, cx - inner, y + 1, a << 24);
				g.fill(cx + inner, y, cx + half, y + 1, a << 24);
				half = inner;
				if (inner == 0) {
					break;
				}
			}
		}
		if (amount < 0.6F) {
			return;
		}
		int ink = (int) ((amount - 0.6F) / 0.4F * (INK >>> 24)) << 24 | (INK & 0xFFFFFF);
		int halo = (int) ((amount - 0.6F) / 0.4F * (HALO >>> 24)) << 24 | (HALO & 0xFFFFFF);
		Font font = mc.font;
		// the aiming chevron and the vertical line down through the range chevrons
		chevron(g, cx, cy, 5, ink, halo);
		int bottom = cy + (int) offset(mc, h, DROP[DROP.length - 1]) + 10;
		line(g, cx, cy + 6, cx, bottom, ink, halo);
		for (int i = 0; i < DROP.length; i++) {
			int y = cy + Math.round(offset(mc, h, DROP[i]));
			chevron(g, cx, y, 4, ink, halo);
			g.drawString(font, Integer.toString(i + 1), cx + 7, y - 3, ink, false);
		}
		// lead scale: a tick every degree out to 5, longer at 5
		int lead = Math.round(offset(mc, h, 1.0F));
		line(g, cx - lead * 5 - 2, cy, cx - 7, cy, ink, halo);
		line(g, cx + 7, cy, cx + lead * 5 + 2, cy, ink, halo);
		for (int i = 1; i <= 5; i++) {
			int len = i == 5 ? 5 : 3;
			line(g, cx - lead * i, cy - len, cx - lead * i, cy, ink, halo);
			line(g, cx + lead * i, cy - len, cx + lead * i, cy, ink, halo);
		}
		// rangefinder, lower left: base line, and a target 2.7 m tall at each range
		int baseY = cy + (int) (r * 0.55F);
		int x0 = cx - (int) (r * 0.62F);
		int x1 = cx - (int) (r * 0.12F);
		line(g, x0, baseY, x1, baseY, ink, halo);
		int prevX = -1;
		int prevY = -1;
		for (int d = 100; d <= 600; d += 25) {
			float f = (d - 100) / 500.0F;
			int x = Math.round(Mth.lerp(f, x0, x1));
			int y = baseY - Math.round(offset(mc, h, (float) Math.toDegrees(Math.atan(2.7 / d))));
			if (prevX >= 0) {
				line(g, prevX, prevY, x, y, ink, halo);
			}
			if (d % 100 == 0) {
				line(g, x, y, x, y + 2, ink, halo);
				g.drawString(font, Integer.toString(d / 100), x - 2, y - 9, ink, false);
			}
			prevX = x;
			prevY = y;
		}
	}

	/** A "^" chevron with its tip at (x, y). */
	private static void chevron(GuiGraphics g, int x, int y, int size, int ink, int halo) {
		line(g, x - size, y + size, x, y, ink, halo);
		line(g, x, y, x + size, y + size, ink, halo);
	}

	/** A one-pixel line (Bresenham), over a faint light halo so it reads against dark ground too. */
	private static void line(GuiGraphics g, int x0, int y0, int x1, int y1, int ink, int halo) {
		plot(g, x0, y0, x1, y1, halo, 1);
		plot(g, x0, y0, x1, y1, ink, 0);
	}

	private static void plot(GuiGraphics g, int x0, int y0, int x1, int y1, int color, int grow) {
		int dx = Math.abs(x1 - x0);
		int dy = -Math.abs(y1 - y0);
		int sx = x0 < x1 ? 1 : -1;
		int sy = y0 < y1 ? 1 : -1;
		int err = dx + dy;
		int x = x0;
		int y = y0;
		for (int n = 0; n < 4000; n++) {
			g.fill(x - grow, y - grow, x + 1 + grow, y + 1 + grow, color);
			if (x == x1 && y == y1) {
				break;
			}
			int e2 = 2 * err;
			if (e2 >= dy) {
				err += dy;
				x += sx;
			}
			if (e2 <= dx) {
				err += dx;
				y += sy;
			}
		}
	}
}
