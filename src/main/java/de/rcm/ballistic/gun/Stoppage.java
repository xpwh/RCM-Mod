package de.rcm.ballistic.gun;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Stoppages: now and then a spent case does not get clear of the ejection port - the bolt closes on it and it sticks
 * up out of the port (a stovepipe). The trigger only clicks until the shooter clears it the drilled way: tap the
 * magazine up, rack the action hard, let it fly - the case spins out and a fresh round goes in (R, the CLEAR "reload").
 * Rare on a clean gun; more likely in long bursts, in the wet and with a suppressor's back pressure.
 */
public final class Stoppage {
	private Stoppage() {
	}

	/**
	 * Does this shot end in a stoppage?
	 *
	 * @param base  the gun's chance per round
	 * @param burst rounds fired in this burst so far (heat, fouling)
	 */
	public static boolean roll(ServerLevel level, LivingEntity shooter, double base, int burst) {
		double p = base * (1.0 + Math.min(burst, 30) * 0.06);
		if (shooter.isInWaterOrRain()) {
			p *= 2.0;
		}
		if (GunAttachments.suppressed(shooter)) {
			p *= 1.6;
		}
		return level.getRandom().nextDouble() < p;
	}

	public static void tell(Player player) {
		player.displayClientMessage(Component.translatable("message.ballisticmissiles.stoppage").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), true);
	}
}
