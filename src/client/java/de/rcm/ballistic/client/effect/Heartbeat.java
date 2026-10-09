package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.gun.Fatigue;
import de.rcm.ballistic.injury.Injuries;
import de.rcm.ballistic.injury.Wounds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.util.Mth;

/**
 * Your own heart, loud in your ears when it counts: after being shot it races with the shock, it pounds
 * harder and faster the more blood you lose and the lower your health, and after a long sprint you hear it
 * too - calming down again as you do. (The slowing, fading beat of bleeding out is the dying screen's.)
 */
public final class Heartbeat {
	/** The rush after being hit, 0..1, ebbing away over some seconds. */
	private static float shock;
	private static int lastHurt;
	private static float sinceBeat;
	/** How strongly the heart is beating (0..1): smoothed so the rate rises and falls like a pulse does. */
	private static float level;

	private Heartbeat() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(Heartbeat::tick);
	}

	/** How strongly the heart beats now (0..1), for anything that wants to pulse with it. */
	public static float level() {
		return level;
	}

	private static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.isPaused()) {
			return;
		}
		if (p.isCreative() || p.isSpectator() || !p.isAlive()) {
			level = 0.0F;
			shock = 0.0F;
			return;
		}
		Wounds w = Injuries.get(p);
		if (p.hurtTime > lastHurt) {
			shock = Math.min(1.0F, shock + 0.55F);
		}
		lastHurt = p.hurtTime;
		shock *= 0.994F;
		float health = p.getHealth() / Math.max(1.0F, p.getMaxHealth());
		float low = Mth.clamp((0.65F - health) / 0.55F, 0.0F, 1.0F);
		float bleeding = w.bleed() / 3.0F;
		float target = Math.max(Math.max(low, bleeding * 0.9F), Math.max(shock * 0.85F, Fatigue.level() * 0.55F));
		if (w.lost() > 0 || w.armsLost() > 0) {
			target = Math.max(target, 0.5F);
		}
		level += (target - level) * (target > level ? 0.08F : 0.01F);
		if (w.incapacitated() || level < 0.18F) {
			// silent - or the dying screen's own, slowing heart
			sinceBeat = 0.0F;
			return;
		}
		// 70 beats a minute at rest, up to 175 at the worst
		float bpm = 70.0F + 105.0F * level;
		sinceBeat += 1.0F;
		if (sinceBeat >= 1200.0F / bpm) {
			sinceBeat = 0.0F;
			float volume = 0.12F + 0.6F * level;
			float pitch = 0.95F + 0.35F * level;
			mc.getSoundManager().play(SimpleSoundInstance.forUI(ModRegistry.HEARTBEAT, pitch, volume));
		}
	}
}
