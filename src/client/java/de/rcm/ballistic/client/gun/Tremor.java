package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;

/**
 * Shaking hands: under stress (shot at, hurt, the heart racing) or frozen stiff the hands holding the gun tremble -
 * a quick, uneven tremor of a few hertz, in bursts, that putting the gun to the eye only partly steadies.
 */
public final class Tremor {
	private Tremor() {
	}

	/** How badly the hands shake, 0-1. */
	public static float amount() {
		return Mth.clamp(Math.max(de.rcm.ballistic.client.effect.Stress.level() * 0.9F, de.rcm.ballistic.client.effect.BodyHeatClient.cold()), 0.0F, 1.0F);
	}

	/** Shakes the gun on the pose stack; {@code now} in ticks, {@code aim} 0-1 how far up at the eye. */
	public static void apply(PoseStack poseStack, float now, float aim) {
		float k = amount();
		if (k < 0.05F) {
			return;
		}
		k = (k - 0.05F) * (1.0F - 0.45F * aim);
		// in bursts: worse, easing, worse again
		float burst = 0.6F + 0.4F * Mth.sin(now * 0.11F) * Mth.sin(now * 0.047F + 0.8F);
		float pitch = Mth.sin(now * 2.9F) * 0.55F + Mth.sin(now * 4.7F + 1.1F) * 0.3F + Mth.sin(now * 1.3F + 2.0F) * 0.25F;
		float yaw = Mth.cos(now * 3.3F + 0.4F) * 0.45F + Mth.sin(now * 5.3F) * 0.25F;
		poseStack.mulPose(Axis.XP.rotationDegrees(pitch * 1.1F * k * burst));
		poseStack.mulPose(Axis.YP.rotationDegrees(yaw * 0.9F * k * burst));
		poseStack.translate(Mth.sin(now * 3.7F) * 0.0015F * k * burst, Mth.sin(now * 4.1F + 0.6F) * 0.0012F * k * burst, 0.0F);
	}
}
