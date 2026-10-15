package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.ModConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * The eye getting used to the light. It adapts to brightness in a moment but to the dark only slowly: step
 * from daylight or a lit room into a dark night and at first you see little, more and more as the eye
 * catches up; a flash at night (a muzzle flash, an explosion) dazzles for a moment and leaves you
 * night-blind for a while after. Drawn as a darkening (and a brief glare) over the picture.
 */
public final class EyeAdaptation {
	/** How bright the eye is set for (0 dark-adapted .. 1+ daylight or a flash). */
	private static float adapted;
	private static float seen;
	private static float glare;
	private static float glareO;
	private static float shadeO;
	private static float shade;
	private static final float[] LIT = new float[3];

	private EyeAdaptation() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(EyeAdaptation::tick);
		HudElementRegistry.addFirst(BallisticMissiles.id("eye_adaptation"), (g, tick) -> render(g, tick.getGameTimeDeltaPartialTick(false)));
	}

	private static void tick(Minecraft mc) {
		glareO = glare;
		shadeO = shade;
		if (mc.level == null || mc.player == null || !ModConfig.eyeAdaptation || mc.isPaused()) {
			if (mc.level == null || !ModConfig.eyeAdaptation) {
				glare = 0.0F;
				shade = 0.0F;
			}
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		BlockPos p = BlockPos.containing(eye);
		// the light round the eye: the sky's (by day) or the blocks', plus any flash or fire nearby
		float day = 1.0F - Mth.clamp(mc.level.getSkyDarken() / 11.0F, 0.0F, 1.0F);
		float sky = mc.level.getBrightness(LightLayer.SKY, p) / 15.0F * (0.06F + 0.94F * day);
		float block = mc.level.getBrightness(LightLayer.BLOCK, p) / 15.0F * 0.85F;
		LIT[0] = 0.0F;
		LIT[1] = 0.0F;
		LIT[2] = 0.0F;
		DynamicLights.lightAt(eye.x, eye.y, eye.z, LIT);
		float flash = Math.min(1.5F, (LIT[0] * 0.3F + LIT[1] * 0.55F + LIT[2] * 0.15F) * 1.4F);
		float steady = Math.max(sky, block);
		float now = steady + flash;
		// a sudden flash in the dark: dazzled for a moment (the first shot, not every one of a burst)
		if (now - seen > 0.3F && adapted < 0.4F) {
			glare = Math.min(0.4F, glare + (now - seen) * 0.3F);
		}
		seen = now;
		glare *= 0.86F;
		// quick to the light (a few tenths of a second), slow to the dark (about twenty seconds); a flash
		// only half counts - it is over before the eye has fully closed down
		float target = steady + flash * 0.5F;
		adapted += target > adapted ? (target - adapted) * 0.3F : (target - adapted) * 0.012F;
		// how much darker the world looks than the eye is set for
		shade = Mth.clamp((adapted - steady) * 0.85F, 0.0F, 0.7F);
	}

	private static void render(GuiGraphics g, float partialTick) {
		if (!ModConfig.eyeAdaptation) {
			return;
		}
		float s = Mth.lerp(partialTick, shadeO, shade);
		float gl = Mth.lerp(partialTick, glareO, glare);
		int w = g.guiWidth();
		int h = g.guiHeight();
		if (s > 0.01F) {
			int a = Math.round(s * 255.0F);
			g.fill(0, 0, w, h, a << 24 | 0x02030A);
		}
		if (gl > 0.01F) {
			// the after-image of the flash: a warm white veil
			g.fill(0, 0, w, h, Math.round(gl * 255.0F) << 24 | 0xFFF2DC);
		}
	}
}
