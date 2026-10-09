package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.injury.Injuries;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The last seconds: lying there, the world closing in from the edges to a tunnel, colour draining to a
 * dark red, the heartbeat loud in your ears and slowing - then black. No death screen: the black holds
 * for a moment and you open your eyes again at your spawn.
 */
public final class DyingOverlay {
	/** How black the screen is (0..1), last tick and this one. */
	private static float black;
	private static float prevBlack;
	private static float tunnel;
	private static float prevTunnel;
	/** Ticks of black held after death before the eyes open again (counts down). */
	private static int hold;
	private static boolean waking;
	private static int nextBeat;

	private DyingOverlay() {
	}

	public static void init() {
		// on top of everything, the hotbar included
		HudElementRegistry.addLast(BallisticMissiles.id("dying"), (g, tick) -> render(g, tick.getGameTimeDeltaPartialTick(false)));
		ClientTickEvents.END_CLIENT_TICK.register(DyingOverlay::tick);
	}

	/** The player died: stay black through the respawn, then wake slowly. */
	public static void died() {
		black = 1.0F;
		prevBlack = 1.0F;
		tunnel = 1.0F;
		prevTunnel = 1.0F;
		hold = 30;
		waking = true;
	}

	/** Lying there, the view rolls over onto its side a little. */
	public static float cameraRoll(float partialTick) {
		return 18.0F * Mth.lerp(partialTick, prevTunnel, tunnel) * (waking ? 0.0F : 1.0F);
	}

	private static void tick(Minecraft mc) {
		prevBlack = black;
		prevTunnel = tunnel;
		var player = mc.player;
		if (player == null) {
			black = 0.0F;
			tunnel = 0.0F;
			waking = false;
			return;
		}
		if (waking) {
			if (hold > 0) {
				hold--;
				return;
			}
			// eyes opening: slowly, with a blink or two on the way
			black = Math.max(0.0F, black - 0.022F);
			tunnel = Math.max(0.0F, tunnel - 0.02F);
			if (black <= 0.0F && tunnel <= 0.0F) {
				waking = false;
			}
			return;
		}
		int dying = Injuries.get(player).dying();
		if (dying <= 0 && !player.isDeadOrDying()) {
			black = Math.max(0.0F, black - 0.05F);
			tunnel = Math.max(0.0F, tunnel - 0.05F);
			nextBeat = 0;
			return;
		}
		if (player.isDeadOrDying()) {
			black = 1.0F;
			tunnel = 1.0F;
			return;
		}
		float p = 1.0F - dying / (float) Injuries.DYING;
		tunnel = Math.max(tunnel, Mth.clamp(p * 1.15F, 0.0F, 1.0F));
		// the last two seconds: the light goes
		black = Mth.clamp((40.0F - dying) / 32.0F, 0.0F, 1.0F);
		// the heart, slowing and fading
		if (--nextBeat <= 0) {
			float volume = 0.9F * (1.0F - 0.75F * p);
			mc.getSoundManager().play(SimpleSoundInstance.forUI(ModRegistry.HEARTBEAT, 1.0F - 0.15F * p, volume));
			nextBeat = (int) Mth.lerp(p, 15.0F, 38.0F);
		}
	}

	private static void render(GuiGraphics g, float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		float t = Mth.lerp(partial, prevTunnel, tunnel);
		float b = Mth.lerp(partial, prevBlack, black);
		if (t <= 0.0F && b <= 0.0F) {
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		if (t > 0.0F) {
			// colour going out of the world: a dark red wash
			int wash = (int) (Mth.clamp(t * 0.45F, 0.0F, 0.45F) * 255.0F);
			g.fill(0, 0, w, h, wash << 24 | 0x1A0000);
			// the tunnel: dark rings closing in on the middle of the view
			int rings = 14;
			float radius = Mth.lerp(t, 1.15F, 0.22F);
			for (int i = 0; i < rings; i++) {
				float f = i / (float) rings;
				float r = radius + f * 0.6F;
				int ix = (int) (w * 0.5F * r);
				int iy = (int) (h * 0.5F * r);
				int a = (int) (Mth.clamp(t * (0.25F + 0.75F * f), 0.0F, 1.0F) * 70.0F);
				int cx = w / 2;
				int cy = h / 2;
				// a frame: everything outside the rectangle of this ring
				int x0 = Math.max(0, cx - ix);
				int x1 = Math.min(w, cx + ix);
				int y0 = Math.max(0, cy - iy);
				int y1 = Math.min(h, cy + iy);
				int col = a << 24;
				g.fill(0, 0, w, y0, col);
				g.fill(0, y1, w, h, col);
				g.fill(0, y0, x0, y1, col);
				g.fill(x1, y0, w, y1, col);
			}
		}
		if (b > 0.0F) {
			g.fill(0, 0, w, h, (int) (Mth.clamp(b, 0.0F, 1.0F) * 255.0F) << 24);
		}
		if (!waking && t > 0.2F && b < 0.6F) {
			Component text = Component.translatable("hud.ballisticmissiles.dying");
			int a = (int) (Mth.clamp((t - 0.2F) * 2.0F, 0.0F, 1.0F) * (1.0F - b) * 200.0F);
			if (a > 8) {
				g.drawCenteredString(mc.font, text, w / 2, h / 2 + 30, a << 24 | 0xC04040);
			}
		}
	}
}
