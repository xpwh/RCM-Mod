package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.render.GrenadeRenderers;
import de.rcm.ballistic.gun.GrenadeItem;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The grenade in first person, in your own hands: held low in the right; on right-click the left
 * hand comes across, hooks a finger through the ring and pulls the pin out, flicking it away, while
 * the right arm draws back over the shoulder, ready to throw.
 */
public final class GrenadeClient {
	private static final Vector3f FIST = new Vector3f(0.012F, -0.03F, 0.045F);
	/** Left hand resting low and out of the way. */
	private static final Vector3f REST = new Vector3f(-0.42F, -0.42F, 0.12F);
	private static final Vector3f PULLED = new Vector3f(-0.22F, 0.0F, 0.06F);

	/** Client game time the lever was let go in the hand (cooking), or -1. */
	private static long cookedAt = -1L;

	private GrenadeClient() {
	}

	/** The lever was let go during the current wind-up (not some earlier one). */
	public static boolean isCooking() {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		return cookedAt >= 0L && player != null && player.isUsingItem() && player.getUseItem().getItem() instanceof GrenadeItem
			&& player.level().getGameTime() - cookedAt <= player.getTicksUsingItem();
	}

	/** Left-click with the pin out: the thumb lets the lever fly. */
	public static void cook() {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		if (level != null && !isCooking()) {
			cookedAt = level.getGameTime();
		}
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static float used(AbstractClientPlayer player, float partialTick) {
		return player.isUsingItem() && player.getUseItem().getItem() instanceof GrenadeItem ? player.getTicksUsingItem() + partialTick : -1.0F;
	}

	/** The arm drawing back to throw. */
	public static void transform(PoseStack poseStack, AbstractClientPlayer player, float partialTick) {
		float t = used(player, partialTick);
		if (t < 0.0F) {
			cookedAt = -1L;
			return;
		}
		float lift = smooth((t - 3.0F) / (GrenadeItem.WIND_UP - 3.0F));
		if (player.isShiftKeyDown()) {
			// underhand: the arm swings down and back past the hip
			poseStack.translate(0.06F * lift, -0.16F * lift, 0.12F * lift);
			poseStack.mulPose(Axis.XP.rotationDegrees(30.0F * lift));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-6.0F * lift));
		} else {
			poseStack.translate(0.04F * lift, 0.17F * lift, 0.2F * lift);
			poseStack.mulPose(Axis.XP.rotationDegrees(-25.0F * lift));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-12.0F * lift));
		}
		if (isCooking()) {
			// the lever flying off: a little jolt of the hand, then it trembles slightly while the fuse burns
			float c = player.level().getGameTime() - cookedAt + partialTick;
			float jolt = c < 5.0F ? Mth.sin(c / 5.0F * Mth.PI) : 0.0F;
			float tremble = c > 5.0F ? 0.004F * Mth.sin(c * 2.3F) : 0.0F;
			poseStack.translate(tremble, 0.02F * jolt + tremble * 0.6F, 0.0F);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-8.0F * jolt));
		}
	}

	public static void renderArms(AbstractClientPlayer player, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light, float partialTick) {
		float t = used(player, partialTick);
		Vector3f left = new Vector3f(REST);
		boolean ringInHand = false;
		if (t >= 0.0F) {
			if (t < 3.0F) {
				left.set(REST).lerp(GrenadeRenderers.RING_AT, smooth(t / 3.0F));
			} else if (t < 6.0F) {
				left.set(GrenadeRenderers.RING_AT).lerp(PULLED, smooth((t - 3.0F) / 3.0F));
				ringInHand = true;
			} else {
				left.set(PULLED).lerp(REST, smooth((t - 6.0F) / 6.0F));
				ringInHand = t < 8.0F;
			}
		}
		poseStack.pushPose();
		// the item's first-person display transform (models/item/grenade_in_hand.json)
		poseStack.translate(0.0F, 2.0F / 16.0F, 0.0F);
		AkArms.draw(player, base, poseStack, collector, light, FIST, left, AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER);
		if (ringInHand) {
			Vector3f off = new Vector3f(left).sub(GrenadeRenderers.RING_AT);
			poseStack.translate(off.x, off.y, off.z);
			GrenadeRenderers.submitRing(poseStack, collector, light);
		}
		poseStack.popPose();
	}
}
