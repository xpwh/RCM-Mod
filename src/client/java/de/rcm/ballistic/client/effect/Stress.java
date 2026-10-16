package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.item.SmokingClient;
import de.rcm.ballistic.injury.Injuries;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Nerves. Rounds cracking past, blasts close by, being hit, bleeding, your health running low - each winds
 * you up, and it only ebbs slowly once things have been quiet for a while. Stressed you are jittery: the
 * hands tremble (worse with a weapon up), the head twitches round at nothing, you breathe quicker and hear
 * your heart. A cigarette takes the edge off - every drag a little more.
 */
public final class Stress {
	private static float level;
	private static int lastHurt;
	private static int calm;
	/** A sudden glance round: where to and how much of it is left. */
	private static float twitchYaw;
	private static float twitchPitch;
	private static float twitchLeft;
	private static int nextTwitch = 80;

	private Stress() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(Stress::tick);
		HudElementRegistry.addLast(BallisticMissiles.id("stress"), (g, tick) -> hud(g, tick.getGameTimeDeltaPartialTick(false)));
	}

	/** 0 calm .. 1 at the end of your tether. */
	public static float level() {
		return level;
	}

	/** A drag on a cigarette. */
	public static void relief(float amount) {
		level = Math.max(0.0F, level - amount);
	}

	/** The jerk of the head this tick (yaw, pitch, degrees), on top of the steady tremor. */
	public static float twitchYaw() {
		return twitchYaw * twitchLeft;
	}

	public static float twitchPitch() {
		return twitchPitch * twitchLeft;
	}

	private static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.isPaused()) {
			return;
		}
		if (p.isCreative() || p.isSpectator() || !p.isAlive()) {
			level = Math.max(0.0F, level - 0.02F);
			twitchLeft = 0.0F;
			return;
		}
		float add = BlastShader.suppression() * 0.012F + BlastShader.shock() * 0.02F;
		if (p.hurtTime > lastHurt) {
			add += 0.14F;
		}
		lastHurt = p.hurtTime;
		float health = p.getHealth() / Math.max(1.0F, p.getMaxHealth());
		add += Mth.clamp((0.5F - health) / 0.5F, 0.0F, 1.0F) * 0.0015F;
		if (Injuries.get(p).bleed() > 0) {
			add += 0.001F;
		}
		level += add * (1.0F - level * 0.5F);
		// it only settles once it has been quiet for five seconds - and then slowly, a couple of minutes from the worst
		calm = add > 0.0006F ? 0 : calm + 1;
		if (calm > 100) {
			level -= 0.0004F;
		}
		if (SmokingClient.isSmoking(p)) {
			level -= 0.0012F;
		}
		level = Mth.clamp(level, 0.0F, 1.0F);

		// nervous glances round at nothing
		twitchLeft = Math.max(0.0F, twitchLeft - 0.18F);
		if (level > 0.5F && --nextTwitch <= 0) {
			float k = (level - 0.5F) * 2.0F;
			twitchYaw = (p.getRandom().nextBoolean() ? 1 : -1) * (1.5F + 3.0F * k) * (0.6F + p.getRandom().nextFloat() * 0.4F);
			twitchPitch = (p.getRandom().nextFloat() - 0.5F) * 2.0F * k;
			twitchLeft = 1.0F;
			nextTwitch = 50 + p.getRandom().nextInt(Math.round(160 - 100 * k));
		}
	}

	/** A small pulse readout above the hotbar's left end, there while your nerves are up. */
	private static void hud(GuiGraphics g, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || level < 0.2F || mc.player.isCreative() || mc.player.isSpectator()) {
			return;
		}
		float show = Mth.clamp((level - 0.2F) / 0.1F, 0.0F, 1.0F);
		int bpm = Math.round(72.0F + 100.0F * level);
		float beat = Heartbeat.phase();
		float pulse = beat < 0.0F ? 0.0F : Math.max(0.0F, 1.0F - beat * 5.0F);
		// left of the hotbar (and of the off-hand slot), where nothing else is
		int x = g.guiWidth() / 2 - 91 - 29 - 62;
		int y = g.guiHeight() - 14;
		int alpha = Math.round(show * 255.0F) << 24;
		int red = level > 0.7F ? 0xFF4030 : level > 0.45F ? 0xFFA040 : 0xE8E0C0;
		// a little ECG trace, the spike in step with the heart you hear
		int w = 34;
		for (int i = 0; i < w; i++) {
			float u = (i / (float) w + (mc.level.getGameTime() + partialTick) * 0.02F * (bpm / 72.0F)) % 1.0F;
			float h = u > 0.42F && u < 0.46F ? -5.0F : u > 0.46F && u < 0.5F ? 3.0F : u > 0.6F && u < 0.68F ? -1.0F : 0.0F;
			g.fill(x + i, y + 4 + Math.round(h), x + i + 1, y + 5 + Math.round(h), alpha | (red & 0xFFFFFF));
		}
		String text = "♥ " + bpm;
		int c = alpha | (pulse > 0.2F ? 0xFFFFFF : red & 0xFFFFFF);
		g.drawString(mc.font, text, x + w + 3, y, c, true);
	}
}
