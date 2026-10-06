package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Drives the {@code blast} screen post effect: when a shock wave or a fireball's flash hits the
 * player the picture refracts, splits into colour fringes, blooms and darkens at the edges, then
 * settles over a few seconds. Post effect uniforms are fixed per file, so strength and animation are
 * picked from pre-made variants ({@code post_effect/blast_<level>_<phase>.json}). A post effect that
 * something else switched on (spectating a creeper, other mods) is never replaced.
 */
public final class BlastShader {
	private static final int LEVELS = 5;
	private static final int PHASES = 4;
	private static final String PREFIX = "blast_";
	private static final Identifier[][] IDS = new Identifier[LEVELS][PHASES];

	static {
		for (int l = 0; l < LEVELS; l++) {
			for (int p = 0; p < PHASES; p++) {
				IDS[l][p] = BallisticMissiles.id(PREFIX + (l + 1) + "_" + p);
			}
		}
	}

	private static float intensity;
	private static int phase;

	private BlastShader() {
	}

	/** Raises the effect to at least {@code amount} (0-1); the faint rumble of far-off events is ignored. */
	public static void kick(float amount) {
		intensity = Math.max(intensity, Mth.clamp((amount - 0.1F) / 0.9F, 0.0F, 1.0F));
	}

	public static void tick(Minecraft mc) {
		intensity = Math.max(0.0F, intensity * 0.965F - 0.003F);
		GameRenderer renderer = mc.gameRenderer;
		Identifier current = renderer.currentPostEffect();
		boolean ours = current != null && current.getNamespace().equals(BallisticMissiles.MOD_ID) && current.getPath().startsWith(PREFIX);
		if (current != null && !ours) {
			return;
		}
		int level = Mth.ceil(intensity * LEVELS - 0.15F);
		if (level <= 0 || mc.level == null) {
			if (ours) {
				renderer.clearPostEffect();
			}
			return;
		}
		phase = (phase + 1) % PHASES;
		((GameRendererAccessor) renderer).ballisticmissiles$setPostEffect(IDS[Math.min(level, LEVELS) - 1][phase]);
	}
}
