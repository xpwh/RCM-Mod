package de.rcm.ballistic.client.drone;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.gun.AkArms;
import de.rcm.ballistic.client.gun.Fatigue;
import de.rcm.ballistic.client.render.FpvDroneItemRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * An FPV drone held in first person: lying level on your flat right hand, held out in front of you
 * low and a little to the right, nose away - the palm under its warhead, the forearm coming in from
 * the side with the elbow out, as you would hold it before throwing it up. It sways gently with your
 * breathing; the left arm hangs out of view.
 */
public final class DroneHandClient {
	/** Where the drone sits in the hand space vanilla sets up for the main hand. */
	private static final Vector3f HELD = new Vector3f(0.08F, 0.18F, -0.12F);
	/**
	 * The hand under it, in the drone item's space: the top of the slimmed arm is about 0.0625 above
	 * its axis, the bottom of the warhead {@code 0.095 * scale} below the drone.
	 */
	private static final Vector3f PALM = new Vector3f(0.0F, -0.095F * FpvDroneItemRenderer.HAND_SCALE - 0.06F, 0.03F);
	/** Left hand: down by your side, out of view. */
	private static final Vector3f LEFT_REST = new Vector3f(-0.55F, -0.75F, 0.25F);
	/** Elbow out to the right and raised a little: the forearm comes in from low right, nearly level under the drone. */
	private static final Vector3f RIGHT_POLE = new Vector3f(0.6F, 1.0F, 0.2F);
	private static final Vector3f LEFT_POLE = new Vector3f(-0.65F, -1.0F, 0.2F);

	private DroneHandClient() {
	}

	/** Out in front, level, rising and falling a little with your breath. */
	public static void transform(PoseStack poseStack, AbstractClientPlayer player, float partialTick) {
		float t = player.tickCount + partialTick;
		float breath = Mth.sin(Fatigue.breathPhase(partialTick)) * 0.006F * (0.4F + Fatigue.level());
		poseStack.translate(HELD.x, HELD.y + breath + Mth.sin(t * 0.05F) * 0.003F, HELD.z);
		poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.sin(t * 0.037F) * 1.2F));
		poseStack.mulPose(Axis.XP.rotationDegrees(Mth.cos(t * 0.043F) * 0.8F));
	}

	public static void renderArms(AbstractClientPlayer player, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		AkArms.draw(player, base, poseStack, collector, light, PALM, LEFT_REST, AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER, RIGHT_POLE, LEFT_POLE);
	}
}
