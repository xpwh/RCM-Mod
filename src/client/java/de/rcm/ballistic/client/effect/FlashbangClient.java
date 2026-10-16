package de.rcm.ballistic.client.effect;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A stun grenade from the player's side: the world goes white - held for a moment, longer the harder it hit
 * - then slowly comes back through a burnt-in afterimage; the ears ring and every other sound is muffled,
 * coming back only slowly. The flash also lights up the surroundings for an instant.
 */
public final class FlashbangClient {
	private static float white;
	private static float whitePrev;
	private static int hold;

	private FlashbangClient() {
	}

	public static void receive(Vec3 pos, float blind, float deaf) {
		Minecraft mc = Minecraft.getInstance();
		DynamicLights.flash(pos, 0xFFFFFF, 1.0F, 20.0F, 220.0F);
		if (blind > 0.02F) {
			white = Math.max(white, Mth.clamp(blind * 1.15F, 0.0F, 1.0F));
			hold = Math.max(hold, (int) (10 + 80 * blind));
			BlastShader.burnIn(Math.min(1.0F, blind * 1.2F));
			BlastShader.flash(blind);
		}
		if (deaf > 0.05F) {
			ClientEffects.deafen(mc, Math.min(1.0F, deaf * 1.1F), (int) (60 + 200 * deaf));
			ClientEffects.addShake(0.25F * deaf);
		}
	}

	public static void tick(Minecraft mc) {
		whitePrev = white;
		if (hold > 0) {
			hold--;
		} else {
			white = Math.max(0.0F, white * 0.965F - 0.002F);
		}
	}

	public static void clear() {
		white = whitePrev = 0.0F;
		hold = 0;
	}

	/** How white the screen is (0-1). */
	public static float white(float partialTick) {
		return Mth.lerp(partialTick, whitePrev, white);
	}
}
