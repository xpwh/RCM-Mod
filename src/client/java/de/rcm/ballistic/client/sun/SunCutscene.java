package de.rcm.ballistic.client.sun;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.sun.SunNetwork;
import de.rcm.ballistic.sun.SunTimeline;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The cutscene for whoever gave the order: the last seconds of the countdown circling the Helios tower as the
 * coils light up, the shot seen from under it, the round chased up through the sky and over, and the strike from
 * afar - the core collapsing, the star born, burning, going nova, the shock rolling towards the camera. Black bars,
 * no HUD, titles and a telemetry readout; the jump key skips it.
 */
public final class SunCutscene {
	private static SunClient.Strike strike;
	private static boolean skipped;
	private static int age;

	private SunCutscene() {
	}

	static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(SunCutscene::tick);
		HudElementRegistry.addLast(BallisticMissiles.id("sun_cutscene"), (graphics, tickCounter) -> hud(graphics, tickCounter.getGameTimeDeltaPartialTick(false)));
		for (Identifier id : new Identifier[] {VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.ARMOR_BAR,
			VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR,
			VanillaHudElements.CROSSHAIR, VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.STATUS_EFFECTS, VanillaHudElements.MOUNT_HEALTH}) {
			HudElementRegistry.replaceElement(id, element -> (graphics, tickCounter) -> {
				if (!playing()) {
					element.render(graphics, tickCounter);
				}
			});
		}
	}

	static void start(SunClient.Strike s) {
		strike = s;
		skipped = false;
		age = 0;
	}

	static void aborted(int id) {
		if (strike != null && strike.id == id) {
			strike = null;
		}
	}

	static void clear() {
		strike = null;
	}

	private static float time(float partialTick) {
		return strike == null ? -1.0F : strike.time(partialTick);
	}

	/** Whether the cutscene is on screen now. */
	public static boolean playing() {
		if (strike == null || skipped) {
			return false;
		}
		float t = time(0.0F);
		return t >= SunTimeline.cutsceneFrom() && t <= SunTimeline.cutsceneTo(strike.flight);
	}

	/** Where the camera is and looks ({x, y, z, yaw, pitch}), or null when no cutscene plays. */
	public static double[] camera(float partialTick) {
		if (!playing()) {
			return null;
		}
		float t = time(partialTick);
		double[] view = SunTimeline.camera(strike.launcher, strike.target, strike.flight, t);
		// a camera operator's hands: a slow drift, and a shudder when something big goes off
		float drift = t * 0.05F;
		view[3] += Mth.sin(drift * 1.3F) * 0.6F + Mth.sin(drift * 2.9F) * 0.25F;
		view[4] += Mth.sin(drift * 1.7F + 1.0F) * 0.4F;
		return view;
	}

	private static void tick(Minecraft mc) {
		if (strike == null) {
			return;
		}
		if (mc.level == null || mc.player == null) {
			strike = null;
			return;
		}
		float t = time(0.0F);
		if (t > SunTimeline.cutsceneTo(strike.flight) + 5) {
			strike = null;
			return;
		}
		if (playing()) {
			age++;
			if (mc.options.keyJump.consumeClick() && age > 20) {
				skipped = true;
				ClientPlayNetworking.send(new SunNetwork.SkipPayload(strike.id));
			}
		}
	}

	private static float ease(float t, float a, float b) {
		return SunTimeline.smooth((t - a) / (b - a));
	}

	private static void hud(GuiGraphics g, float partialTick) {
		if (!playing()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		float t = time(partialTick);
		float from = SunTimeline.cutsceneFrom();
		float to = SunTimeline.cutsceneTo(strike.flight);
		float fire = SunTimeline.FIRE;
		float impact = fire + strike.flight;
		int w = g.guiWidth();
		int h = g.guiHeight();
		// the bars close in, and open at the end
		float bars = ease(t, from, from + 20.0F) - ease(t, to - 20.0F, to);
		int bar = Math.round(h * 0.12F * bars);
		if (bar > 0) {
			g.fill(0, 0, w, bar, 0xFF000000);
			g.fill(0, h - bar, w, h, 0xFF000000);
		}
		// a fade from black at the start, and a cut through white on the nova
		float black = 1.0F - ease(t, from, from + 14.0F);
		if (black > 0.01F) {
			g.fill(0, 0, w, h, Math.round(black * 255.0F) << 24);
		}
		int cx = w / 2;
		int cy = Math.round(h * 0.42F);
		// ---- titles
		if (t < fire - 70.0F) {
			float a = ease(t, from + 10.0F, from + 30.0F) - ease(t, fire - 95.0F, fire - 75.0F);
			title(g, mc, Component.translatable("cutscene.ballisticmissiles.sun.complex").getString(), cx, cy, 2.2F, a, 0xE6E8EC);
			sub(g, mc, Component.translatable("cutscene.ballisticmissiles.sun.charging"), cx, cy + 22, a);
		}
		// the last seconds: three, two, one
		if (t >= fire - 60.0F && t < fire + 10.0F) {
			int n = 3 - (int) ((t - (fire - 60.0F)) / 20.0F);
			float in = ((t - (fire - 60.0F)) % 20.0F) / 20.0F;
			String s = n > 0 ? String.valueOf(n) : Component.translatable("cutscene.ballisticmissiles.sun.fire").getString();
			float a = n > 0 ? 1.0F - in * in : 1.0F - ease(t, fire + 2.0F, fire + 10.0F);
			title(g, mc, s, cx, cy, n > 0 ? 4.0F - in * 0.8F : 3.0F, a, n > 0 ? 0xFFFFFF : 0xFFD27A);
		}
		// the chase: the round's telemetry
		if (t > fire + 30.0F && t < impact) {
			float f = (t - fire) / strike.flight;
			Vec3 p = SunTimeline.flight(strike.launcher, strike.target, f);
			Vec3 q = SunTimeline.flight(strike.launcher, strike.target, Math.min(1.0F, f + 1.0F / strike.flight));
			double speed = p.distanceTo(q) * 20.0;
			double range = Math.hypot(strike.target.x - p.x, strike.target.z - p.z);
			float a = ease(t, fire + 30.0F, fire + 45.0F) * (1.0F - ease(t, impact - 20.0F, impact - 5.0F));
			int col = Math.round(a * 220.0F) << 24 | 0xBFE3FF;
			int x = 14;
			int y = h - bar - 52;
			g.drawString(mc.font, Component.translatable("cutscene.ballisticmissiles.sun.telemetry"), x, y, col, false);
			g.drawString(mc.font, String.format("MACH %5.1f", speed / 343.0), x, y + 12, col, false);
			g.drawString(mc.font, String.format("ALT  %6d m", Math.round(p.y)), x, y + 22, col, false);
			g.drawString(mc.font, String.format("RNG  %6d m", Math.round(range)), x, y + 32, col, false);
			// the time to impact, top right
			String eta = String.format("T-%04.1f", (impact - t) / 20.0F);
			g.drawString(mc.font, eta, w - 14 - mc.font.width(eta), bar + 10, col, false);
		}
		// the star
		float d = t - impact;
		if (d > SunTimeline.IMPLODE + 20 && d < SunTimeline.BURN_END) {
			float a = ease(d, SunTimeline.IMPLODE + 20.0F, SunTimeline.IMPLODE + 60.0F) - ease(d, SunTimeline.IMPLODE + 160.0F, SunTimeline.IMPLODE + 200.0F);
			title(g, mc, "SUN DESTROYER", cx, Math.round(h * 0.2F) + bar, 2.6F, a, 0xFFE2B0);
			sub(g, mc, Component.translatable("cutscene.ballisticmissiles.sun.born"), cx, Math.round(h * 0.2F) + bar + 24, a);
		}
		if (d >= SunTimeline.NOVA && d < SunTimeline.NOVA + 12) {
			float a = 1.0F - (d - SunTimeline.NOVA) / 12.0F;
			g.fill(0, 0, w, h, Math.round(a * a * 255.0F) << 24 | 0xFFFFFF);
		}
		// how to skip it
		float hint = ease(t, from + 20.0F, from + 40.0F) * (1.0F - ease(t, to - 30.0F, to - 10.0F));
		if (hint > 0.01F) {
			Component skip = Component.translatable("cutscene.ballisticmissiles.sun.skip", mc.options.keyJump.getTranslatedKeyMessage());
			g.drawString(mc.font, skip, w - 12 - mc.font.width(skip), h - bar - 14, Math.round(hint * 150.0F) << 24 | 0xFFFFFF, false);
		}
	}

	private static void sub(GuiGraphics g, Minecraft mc, Component text, int cx, int y, float alpha) {
		if (alpha > 0.01F) {
			g.drawCenteredString(mc.font, text, cx, y, Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 220.0F) << 24 | 0xAEB4C2);
		}
	}

	/** A title, letter by letter, from wide apart into place. */
	private static void title(GuiGraphics g, Minecraft mc, String text, float cx, float cy, float scale, float reveal, int rgb) {
		reveal = Mth.clamp(reveal, 0.0F, 1.0F);
		if (reveal <= 0.01F) {
			return;
		}
		int n = text.length();
		float gap = 1.5F + 4.0F * (1.0F - reveal);
		float width = 0.0F;
		for (int i = 0; i < n; i++) {
			width += mc.font.width(String.valueOf(text.charAt(i))) + gap;
		}
		width -= gap;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(scale, scale);
		float x = -width / 2.0F;
		for (int i = 0; i < n; i++) {
			String c = String.valueOf(text.charAt(i));
			float own = Mth.clamp(reveal * (n + 3) - i, 0.0F, 1.0F);
			if (own > 0.01F) {
				g.drawString(mc.font, c, Math.round(x), -4, Math.round(own * 255.0F) << 24 | rgb, true);
			}
			x += mc.font.width(c) + gap;
		}
		g.pose().popMatrix();
	}
}
