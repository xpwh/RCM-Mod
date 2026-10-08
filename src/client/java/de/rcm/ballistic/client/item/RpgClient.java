package de.rcm.ballistic.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.item.RocketLauncherItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * First-person animation of the RPG-7: the kick when it fires (the launcher jumps back and its
 * muzzle climbs), then - if there is another round - the reload: the launcher comes down off the
 * shoulder, muzzle tipped towards you, the new grenade is pushed in, and it goes back up.
 */
public final class RpgClient {
	private static final int LOWER_START = 6;
	private static final int LOWERED = 14;
	private static final int RAISE_START = RocketLauncherItem.RELOAD_TICKS + 2;
	private static final int RAISED = RAISE_START + 8;

	private static long firedAt = Long.MIN_VALUE / 2;
	private static boolean reloading;

	private RpgClient() {
	}

	public static void init() {
		RocketLauncherItem.clientFired = RpgClient::fired;
	}

	private static void fired() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		firedAt = mc.level.getGameTime();
		reloading = RocketLauncherItem.hasAmmo(player);
		// the recoil is mostly cancelled by the back-blast, but the launch still jolts you
		player.setXRot(Mth.clamp(player.getXRot() - 3.5F, -90.0F, 90.0F));
		player.setYRot(player.getYRot() + (ClientEffects.rand() - 0.5F) * 1.5F);
		ClientEffects.addShake(0.5F);
	}

	/** Applied in hand space just before the launcher is drawn in first person. */
	public static void transform(PoseStack poseStack, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		float t = (float) (mc.level.getGameTime() - firedAt) + partialTick;
		if (t < 0.0F || t > RAISED) {
			return;
		}
		// reload: off the shoulder, rolled and tipped, held while the round goes in, back up
		if (reloading) {
			float low;
			if (t < LOWER_START) {
				low = 0.0F;
			} else if (t < LOWERED) {
				low = smooth((t - LOWER_START) / (LOWERED - LOWER_START));
			} else if (t < RAISE_START) {
				low = 1.0F;
			} else {
				low = 1.0F - smooth((t - RAISE_START) / (RAISED - RAISE_START));
			}
			// the round being shoved home into the muzzle
			float push = t > RocketLauncherItem.RELOAD_TICKS - 4 && t < RocketLauncherItem.RELOAD_TICKS + 1
				? Mth.sin((t - (RocketLauncherItem.RELOAD_TICKS - 4)) / 5.0F * Mth.PI) : 0.0F;
			if (low > 0.0F) {
				poseStack.translate(-0.08F * low, -0.3F * low, 0.12F * low + 0.05F * push);
				poseStack.mulPose(Axis.XP.rotationDegrees(-28.0F * low + 3.0F * push));
				poseStack.mulPose(Axis.ZP.rotationDegrees(22.0F * low));
				poseStack.mulPose(Axis.YP.rotationDegrees(18.0F * low));
			}
		}
		// recoil: a sharp kick back and up that settles
		if (t < 10.0F) {
			float kick = t < 1.0F ? t : (float) Math.exp(-(t - 1.0F) * 0.55F);
			poseStack.translate(0.0F, 0.03F * kick, 0.2F * kick);
			poseStack.mulPose(Axis.XP.rotationDegrees(9.0F * kick));
		}
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}
}
