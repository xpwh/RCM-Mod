package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.injury.Injuries;
import de.rcm.ballistic.injury.Wounds;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * What your wounds look like from the inside: a list of them at the left of the screen, the edges of the
 * view throbbing red with your pulse while you bleed (faster and darker the worse it is), and blood
 * spattered across the view for a few seconds when you are hit.
 */
public final class InjuryHud {
	private static final Identifier BLOOD = BallisticMissiles.id("textures/effect/blood.png");
	private static final RandomSource RANDOM = RandomSource.create();

	private record Splat(float x, float y, float size, int variant, long born) {
	}

	private static final List<Splat> SPLATS = new ArrayList<>();
	private static int lastHurt;

	private InjuryHud() {
	}

	public static void init() {
		HudElementRegistry.addFirst(BallisticMissiles.id("injuries"), (g, tick) -> render(g, tick.getGameTimeDeltaPartialTick(false)));
		ClientTickEvents.END_CLIENT_TICK.register(InjuryHud::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null) {
			SPLATS.clear();
			return;
		}
		int hurt = mc.player.hurtTime;
		if (hurt > lastHurt && ModConfig.blood && mc.level != null) {
			// struck: a spatter of blood across the view
			int n = 1 + RANDOM.nextInt(3);
			for (int i = 0; i < n; i++) {
				float x = RANDOM.nextFloat();
				float y = RANDOM.nextFloat();
				// mostly towards the edges
				x = x < 0.5F ? x * 0.6F : 0.4F + x * 0.6F;
				SPLATS.add(new Splat(x, y, 0.18F + RANDOM.nextFloat() * 0.22F, RANDOM.nextInt(4), mc.level.getGameTime()));
			}
			while (SPLATS.size() > 8) {
				SPLATS.remove(0);
			}
		}
		lastHurt = hurt;
		if (mc.level != null) {
			long now = mc.level.getGameTime();
			SPLATS.removeIf(s -> now - s.born > 80);
		}
	}

	private static void render(GuiGraphics g, float partial) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.options.hideGui) {
			return;
		}
		int w = g.guiWidth();
		int h = g.guiHeight();
		Wounds wounds = Injuries.get(mc.player);
		float now = mc.level.getGameTime() + partial;
		// the pulse in the edges of the view
		if (wounds.bleed() > 0) {
			float rate = wounds.bleed() == Wounds.ARTERIAL ? 0.33F : wounds.bleed() == 2 ? 0.24F : 0.18F;
			float beat = (float) Math.pow(Math.max(0.0F, Mth.sin(now * rate)), 6.0F);
			float strength = (wounds.bleed() == Wounds.ARTERIAL ? 0.55F : wounds.bleed() == 2 ? 0.35F : 0.2F) * (0.55F + 0.45F * beat);
			edges(g, w, h, strength);
		}
		float low = 1.0F - mc.player.getHealth() / mc.player.getMaxHealth();
		if (low > 0.6F) {
			edges(g, w, h, (low - 0.6F) * 0.8F);
		}
		// blood on the view, running down and fading
		if (!SPLATS.isEmpty() && ModConfig.blood) {
			for (Splat s : SPLATS) {
				float age = (mc.level.getGameTime() - s.born) + partial;
				float a = Mth.clamp(1.0F - (age - 40.0F) / 40.0F, 0.0F, 1.0F) * 0.75F;
				int size = (int) (s.size * h);
				int x = (int) (s.x * w) - size / 2;
				int y = (int) (s.y * h) - size / 2 + (int) (age * 0.15F);
				int u = (s.variant % 2) * 64;
				int v = (s.variant / 2) * 64;
				g.blit(RenderPipelines.GUI_TEXTURED, BLOOD, x, y, u, v, size, size, 64, 64, 128, 128, (int) (a * 255.0F) << 24 | 0xFFFFFF);
			}
		}
		// the list of wounds
		if (!wounds.any() || mc.player.isCreative()) {
			return;
		}
		Font font = mc.font;
		List<Component> lines = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		if (wounds.bleed() > 0) {
			lines.add(Component.translatable("hud.ballisticmissiles.bleed_" + wounds.bleed()));
			boolean blink = wounds.bleed() < Wounds.ARTERIAL || ((int) (now / 6.0F)) % 2 == 0;
			colors.add(blink ? 0xFFFF4040 : 0xFF902020);
		}
		if (wounds.leg() > 0) {
			lines.add(Component.translatable("hud.ballisticmissiles.leg_" + wounds.leg()));
			colors.add(wounds.leg() == 2 ? 0xFFFF8040 : 0xFFFFC060);
		}
		if (wounds.arm() > 0) {
			lines.add(Component.translatable("hud.ballisticmissiles.arm_" + wounds.arm()));
			colors.add(wounds.arm() == 2 ? 0xFFFF8040 : 0xFFFFC060);
		}
		int y = h - 62 - lines.size() * 11;
		for (int i = 0; i < lines.size(); i++) {
			int c = colors.get(i);
			// a small drop of blood as the bullet
			g.fill(8, y + 3, 11, y + 7, 0xFFB01818);
			g.fill(9, y + 2, 10, y + 3, 0xFFB01818);
			g.drawString(font, lines.get(i), 15, y, c, true);
			y += 11;
		}
	}

	/** A red glow in from the edges of the screen. */
	private static void edges(GuiGraphics g, int w, int h, float strength) {
		int a = Mth.clamp((int) (strength * 255.0F), 0, 255);
		if (a <= 2) {
			return;
		}
		int col = a << 24 | 0x6A0000;
		int bandY = h / 5;
		int bandX = w / 6;
		g.fillGradient(0, 0, w, bandY, col, 0x006A0000);
		g.fillGradient(0, h - bandY, w, h, 0x006A0000, col);
		// the sides: a few columns stepping down in strength
		for (int i = 0; i < 8; i++) {
			int ca = a * (8 - i) / 8 / 2;
			int x0 = bandX * i / 8;
			int x1 = bandX * (i + 1) / 8;
			g.fill(x0, 0, x1, h, ca << 24 | 0x6A0000);
			g.fill(w - x1, 0, w - x0, h, ca << 24 | 0x6A0000);
		}
	}
}
