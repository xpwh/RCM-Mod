package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.gun.C4DetonatorItem;
import de.rcm.ballistic.gun.C4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * C4 and its firing device in first person, in your own hands.
 * <ul>
 *   <li>The charge rests in the right hand, its receiver up. Holding right-click on a wall, the arm reaches out and
 *   turns it over so its back meets the wall, pushes it on, and the left hand comes across and smooths the tape
 *   down over it - then it stays there and the hand comes back empty.</li>
 *   <li>The firing device sits in the right hand, its face tipped towards you. FIRE: the thumb flicks the arming
 *   switch, flips the red cover up and presses the button (the hand jolts with it); the cover drops shut again a
 *   second later. The selector: a quick press with the thumb, the device tipped to read the display.</li>
 * </ul>
 */
public final class C4Client {
	/** Where the charge lies in the hand (item space, from the item's centre). */
	public static final Vector3f CHARGE_AT = new Vector3f(-0.01F, -0.035F, -0.03F);
	private static final Vector3f CHARGE_FIST = new Vector3f(0.02F, -0.05F, 0.07F);
	private static final Vector3f DEVICE_FIST = new Vector3f(0.004F, -0.03F, 0.03F);
	private static final Vector3f REST = new Vector3f(-0.42F, -0.42F, 0.12F);

	/** The firing device's moving parts: the cover's angle (degrees, up from shut), how far the button is in, the switch. */
	public record DevicePose(float cover, float press, boolean armed) {
		public static final DevicePose REST = new DevicePose(0.0F, 0.0F, false);
	}

	/** Client time (ticks + partial) the local player pressed FIRE / used the selector, or -1. */
	private static float firedAt = -1.0F;
	private static float selectedAt = -1.0F;

	private C4Client() {
	}

	public static void init() {
		de.rcm.ballistic.ClientHooks.detonatorAnimation = sneak -> {
			float now = now(0.0F);
			if (sneak) {
				selectedAt = now;
			} else {
				firedAt = now;
			}
		};
	}

	private static float now(float partial) {
		var level = Minecraft.getInstance().level;
		return level == null ? 0.0F : level.getGameTime() + partial;
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** 0 .. 1 .. 0 over [a, b]. */
	private static float bump(float t, float a, float b) {
		return t <= a || t >= b ? 0.0F : Mth.sin((t - a) / (b - a) * Mth.PI);
	}

	// ------------------------------------------------------------------ the firing device

	private static float sinceFire(float partial) {
		return firedAt < 0.0F ? -1.0F : now(partial) - firedAt;
	}

	/** How the device's parts stand for the local player's device right now. */
	public static DevicePose devicePose(ItemStack stack) {
		var player = Minecraft.getInstance().player;
		if (player == null || !(player.getMainHandItem() == stack || player.getOffhandItem() == stack)) {
			return DevicePose.REST;
		}
		float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float t = sinceFire(partial);
		if (t < 0.0F || t > 40.0F) {
			return DevicePose.REST;
		}
		// the switch to ARMED first, then the cover up, the button in; the cover drops shut after a while
		boolean armed = t > 1.0F && t < 30.0F;
		float cover = 110.0F * smooth((t - 1.5F) / 2.5F) * (1.0F - smooth((t - 26.0F) / 4.0F));
		float press = 0.0035F * bump(t, C4DetonatorItem.FIRE_TICKS - 2.5F, C4DetonatorItem.FIRE_TICKS + 2.0F);
		return new DevicePose(cover, press, armed);
	}

	public static void transformDevice(PoseStack poseStack, AbstractClientPlayer player, float partial) {
		// held up a little, its face tipped towards you
		poseStack.translate(-0.04F, 0.06F, 0.02F);
		poseStack.mulPose(Axis.XP.rotationDegrees(28.0F));
		poseStack.mulPose(Axis.YP.rotationDegrees(-8.0F));
		float t = sinceFire(partial);
		if (t >= 0.0F && t < 14.0F) {
			// the thumb working: a nudge as the switch goes, a dip as the button goes in
			float flick = bump(t, 0.5F, 2.5F);
			float push = bump(t, C4DetonatorItem.FIRE_TICKS - 2.5F, C4DetonatorItem.FIRE_TICKS + 2.0F);
			poseStack.translate(0.0F, -0.012F * push, 0.004F * flick);
			poseStack.mulPose(Axis.XP.rotationDegrees(-5.0F * push + 3.0F * flick));
			poseStack.mulPose(Axis.ZP.rotationDegrees(2.0F * flick));
		}
		float s = selectedAt < 0.0F ? -1.0F : now(partial) - selectedAt;
		if (s >= 0.0F && s < 10.0F) {
			// tipped up to read the selector on the display
			float k = bump(s, 0.0F, 10.0F);
			poseStack.mulPose(Axis.XP.rotationDegrees(12.0F * k));
			poseStack.translate(0.0F, 0.01F * k, 0.0F);
		}
	}

	public static void renderDeviceArms(AbstractClientPlayer player, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		poseStack.pushPose();
		poseStack.translate(0.0F, 2.0F / 16.0F, 0.0F);
		AkArms.draw(player, base, poseStack, collector, light, DEVICE_FIST, REST, AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER);
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ the charge

	private static float placing(AbstractClientPlayer player, float partial) {
		return player.isUsingItem() && player.getUseItem().getItem() instanceof C4Item ? player.getTicksUsingItem() + partial : -1.0F;
	}

	public static void transformCharge(PoseStack poseStack, AbstractClientPlayer player, float partial) {
		float t = placing(player, partial);
		if (t < 0.0F) {
			return;
		}
		float reach = smooth(t / 6.0F);
		float press = bump(t, 5.0F, 10.0F);
		// out towards the wall, turned over so its back meets it, pushed on
		poseStack.translate(-0.1F * reach, 0.07F * reach, -0.2F * reach - 0.05F * press);
		poseStack.mulPose(Axis.XP.rotationDegrees(80.0F * reach));
		poseStack.mulPose(Axis.ZP.rotationDegrees(-10.0F * reach));
	}

	public static void renderChargeArms(AbstractClientPlayer player, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light,
		float partial) {
		float t = placing(player, partial);
		Vector3f left = new Vector3f(REST);
		if (t >= 0.0F) {
			// the left hand comes in and smooths the tape down from one end to the other
			float in = smooth((t - 6.0F) / 3.0F) * (1.0F - smooth((t - 14.0F) / 2.0F));
			float along = smooth((t - 8.0F) / (C4Item.PLACE_TICKS - 9.0F));
			Vector3f on = new Vector3f(-0.03F, 0.02F, -0.17F + 0.2F * along);
			left.lerp(on, in);
		}
		poseStack.pushPose();
		poseStack.translate(0.0F, 2.0F / 16.0F, 0.0F);
		AkArms.draw(player, base, poseStack, collector, light, CHARGE_FIST, left, AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER);
		poseStack.popPose();
	}
}
