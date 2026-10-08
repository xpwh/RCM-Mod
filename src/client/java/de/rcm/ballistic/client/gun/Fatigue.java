package de.rcm.ballistic.client.gun;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.item.RocketLauncherItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Out of breath (and under fire). Sprinting (and jumping) winds you; standing still you get your breath back over a
 * few seconds. While winded you hear yourself panting - real breaths, quicker and louder the worse it
 * is - and a rifle or launcher in your hands heaves with every breath and wanders off the point you
 * are aiming at, less so when you have it up at the eye.
 */
public final class Fatigue {
	private static float level;
	private static float phase;
	private static float prevPhase;
	private static float swayYaw;
	private static float swayPitch;
	private static boolean wasOnGround = true;
	private static int age;

	private Fatigue() {
	}

	/** 0 rested .. 1 completely winded. */
	public static float level() {
		return level;
	}

	/** Breathing cycle, radians (one breath per 2 pi). */
	public static float breathPhase(float partialTick) {
		return Mth.lerp(partialTick, prevPhase, phase);
	}

	static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.isPaused()) {
			return;
		}
		age++;
		// winded by running and jumping, recovering at rest
		if (player.isSprinting()) {
			level += 0.0075F;
		} else if (player.getDeltaMovement().horizontalDistanceSqr() > 0.002) {
			level -= 0.0035F;
		} else {
			level -= player.isCrouching() ? 0.008F : 0.006F;
		}
		if (wasOnGround && !player.onGround() && player.getDeltaMovement().y > 0.2) {
			level += 0.025F;
		}
		wasOnGround = player.onGround();
		if (player.isCreative() || player.isSpectator()) {
			level -= 0.05F;
		}
		level = Mth.clamp(level, 0.0F, 1.0F);

		// breathing: about one breath in four seconds at rest, nearly one a second when winded
		prevPhase = phase;
		float rate = 0.25F + 0.75F * level;
		phase += Mth.TWO_PI * rate / 20.0F;
		if (phase > Mth.TWO_PI * 64.0F) {
			phase -= Mth.TWO_PI * 64.0F;
			prevPhase -= Mth.TWO_PI * 64.0F;
		}
		if (level > 0.22F && (int) (phase / Mth.TWO_PI) != (int) (prevPhase / Mth.TWO_PI)) {
			float volume = 0.12F + 0.55F * level;
			GunAudio.play(ModRegistry.PLAYER_BREATH, player.getEyePosition(), volume, 0.94F + (float) Math.random() * 0.12F);
		}

		// the weapon wandering with the breath and the pounding heart: it moves your aim
		boolean armed = player.getMainHandItem().getItem() instanceof AkItem || player.getMainHandItem().getItem() instanceof RocketLauncherItem;
		float amp = armed ? level * level * (AkItem.isAiming(player) || RocketLauncherItem.isAiming(player) ? 0.7F : 1.2F) : 0.0F;
		float t = age / 20.0F;
		float yaw = amp * (Mth.sin(t * 1.1F) * 0.7F + Mth.sin(t * 2.9F + 1.3F) * 0.3F);
		float pitch = amp * (Mth.sin(phase) * 0.8F + Mth.sin(t * 1.7F + 0.4F) * 0.25F);
		// under fire the hands shake: quick and jittery, it fades as the shooting stops
		float fear = de.rcm.ballistic.client.effect.BlastShader.suppression() * (armed ? 1.6F : 0.8F);
		yaw += fear * (Mth.sin(t * 7.3F) * 0.6F + Mth.sin(t * 13.1F + 0.7F) * 0.4F);
		pitch += fear * (Mth.sin(t * 9.1F + 2.1F) * 0.55F + Mth.sin(t * 15.7F) * 0.35F);
		player.setYRot(player.getYRot() + (yaw - swayYaw));
		player.setXRot(Mth.clamp(player.getXRot() + (pitch - swayPitch), -90.0F, 90.0F));
		swayYaw = yaw;
		swayPitch = pitch;
	}
}
