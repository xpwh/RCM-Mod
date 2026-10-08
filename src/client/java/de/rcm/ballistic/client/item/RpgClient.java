package de.rcm.ballistic.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.gun.AkArms;
import de.rcm.ballistic.client.render.Rpg7ItemRenderer;
import de.rcm.ballistic.item.RocketLauncherItem;
import de.rcm.ballistic.network.ModNetworking.GunInputPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * First-person handling of the RPG-7. Hold right-click to shoulder it and put your eye behind the
 * rear sight (the view narrows a little); left-click fires. The kick when it fires (the launcher
 * jumps back and its muzzle climbs), then - if there is another round - the reload: the launcher
 * comes down off the shoulder, your left hand fetches a PG-7V from the pouch, pushes it into the
 * muzzle until it seats, and the launcher goes back up. Your own arms hold it throughout.
 */
public final class RpgClient {
	private static final int LOWER_START = 6;
	private static final int LOWERED = 14;
	private static final int RAISE_START = RocketLauncherItem.RELOAD_TICKS + 2;
	private static final int RAISED = RAISE_START + 8;

	// item-space spots (see Rpg7ItemRenderer: origin at the pistol grip, -Z forward)
	private static final Vector3f GRIP = new Vector3f(0.0F, -0.035F, 0.018F);
	private static final Vector3f TUBE = new Vector3f(-0.005F, 0.012F, -0.2F);
	private static final Vector3f POUCH = new Vector3f(-0.32F, -0.5F, 0.35F);
	/** Where the hand holds a seated warhead, and how far out of the muzzle it starts. */
	private static final Vector3f WARHEAD_HOLD = new Vector3f(0.0F, 0.03F, -0.6F);
	private static final float INSERT = 0.42F;
	/** Eye behind the rear sight, and the line of sight to the front post. */
	private static final Vector3f SIGHT_LINE = new Vector3f(0.0F, 0.005F, -0.28F).normalize();
	private static final Vector3f EYE = new Vector3f(0.0F, 0.2F, -0.026F).sub(new Vector3f(SIGHT_LINE).mul(0.2F));
	/** Camera position in the hand space vanilla sets up for the main hand. */
	private static final Vector3f CAMERA = new Vector3f(-0.56F, 0.52F, 0.72F);

	private static long firedAt = Long.MIN_VALUE / 2;
	private static boolean reloading;
	private static float aim;
	private static float prevAim;

	private RpgClient() {
	}

	public static void init() {
		RocketLauncherItem.clientFired = RpgClient::fired;
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			prevAim = aim;
			boolean aiming = mc.player != null && RocketLauncherItem.isAiming(mc.player) && !reloadingNow(mc);
			aim = aiming ? Math.min(1.0F, aim + 0.22F) : Math.max(0.0F, aim - 0.25F);
		});
	}

	private static boolean reloadingNow(Minecraft mc) {
		return reloading && mc.level != null && mc.level.getGameTime() - firedAt < RAISE_START;
	}

	/** 0 at the hip, 1 with the eye at the sights. */
	public static float aimProgress(float partialTick) {
		float a = Mth.lerp(partialTick, prevAim, aim);
		return a * a * (3.0F - 2.0F * a);
	}

	/** The attack key with the launcher in hand. */
	public static void trigger(LocalPlayer player) {
		if (RocketLauncherItem.clientTrigger(player)) {
			ClientPlayNetworking.send(new GunInputPayload(GunInputPayload.RPG_FIRE));
		}
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
		// up to the eye: the rear notch and the front post lined up on the middle of the view
		float a = aimProgress(partialTick);
		if (a > 0.0F) {
			Quaternionf display = Axis.YP.rotationDegrees(3.0F);
			Vector3f lineM0 = display.transform(new Vector3f(SIGHT_LINE));
			Quaternionf toView = new Quaternionf().rotationTo(lineM0, new Vector3f(0.0F, 0.0F, -1.0F));
			Vector3f eyeM0 = display.transform(new Vector3f(EYE)).add(-3.5F / 16.0F, 3.0F / 16.0F, 0.0F);
			Vector3f shift = new Vector3f(CAMERA).sub(toView.transform(new Vector3f(eyeM0)));
			poseStack.translate(shift.x * a, shift.y * a, shift.z * a);
			poseStack.mulPose(new Quaternionf().slerp(toView, a));
		}

		float t = (float) (mc.level.getGameTime() - firedAt) + partialTick;
		if (t < 0.0F || t > RAISED) {
			return;
		}
		// reload: off the shoulder and up in front of you, muzzle turned in so the round can go in, back up
		if (reloading) {
			float low = lowered(t);
			// the round being shoved home into the muzzle
			float push = t > RocketLauncherItem.RELOAD_TICKS - 4 && t < RocketLauncherItem.RELOAD_TICKS + 1
				? Mth.sin((t - (RocketLauncherItem.RELOAD_TICKS - 4)) / 5.0F * Mth.PI) : 0.0F;
			if (low > 0.0F) {
				poseStack.translate(-0.05F * low, 0.3F * low, -0.25F * low + 0.04F * push);
				poseStack.mulPose(Axis.XP.rotationDegrees(10.0F * low + 3.0F * push));
				poseStack.mulPose(Axis.ZP.rotationDegrees(15.0F * low));
				poseStack.mulPose(Axis.YP.rotationDegrees(40.0F * low));
			}
		}
		// recoil: a sharp kick back and up that settles
		if (t < 10.0F) {
			float kick = t < 1.0F ? t : (float) Math.exp(-(t - 1.0F) * 0.55F);
			poseStack.translate(0.0F, 0.03F * kick, 0.2F * kick);
			poseStack.mulPose(Axis.XP.rotationDegrees(9.0F * kick));
		}
	}

	private static float lowered(float t) {
		if (t < LOWER_START) {
			return 0.0F;
		} else if (t < LOWERED) {
			return smooth((t - LOWER_START) / (LOWERED - LOWER_START));
		} else if (t < RAISE_START) {
			return 1.0F;
		}
		return 1.0F - smooth((t - RAISE_START) / (RAISED - RAISE_START));
	}

	/**
	 * The arms on the launcher, and the fresh round in the left hand while reloading.
	 *
	 * @param base the pose before {@link #transform} moved the launcher
	 */
	public static void renderArms(AbstractClientPlayer player, ItemStack stack, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light,
		float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		float t = (float) (mc.level.getGameTime() - firedAt) + partialTick;
		Vector3f left = new Vector3f(TUBE);
		Vector3f round = null;
		boolean loaded = RocketLauncherItem.isLoaded(stack);
		if (reloading && t >= 0.0F && t < RAISED) {
			float insertEnd = RocketLauncherItem.RELOAD_TICKS - 1;
			Vector3f outside = new Vector3f(WARHEAD_HOLD).add(0.0F, 0.0F, -INSERT);
			if (t < 8.0F) {
				left.set(TUBE);
			} else if (t < 15.0F) {
				left.set(TUBE).lerp(POUCH, smooth((t - 8.0F) / 7.0F));
			} else if (t < 23.0F) {
				left.set(POUCH).lerp(outside, smooth((t - 15.0F) / 8.0F));
			} else if (t < insertEnd) {
				left.set(outside).lerp(WARHEAD_HOLD, smooth((t - 23.0F) / (insertEnd - 23.0F)));
			} else if (t < insertEnd + 3.0F) {
				left.set(WARHEAD_HOLD);
			} else {
				left.set(WARHEAD_HOLD).lerp(TUBE, smooth((t - insertEnd - 3.0F) / 7.0F));
			}
			if (t >= 15.0F && t < insertEnd + 3.0F && !loaded) {
				round = new Vector3f(left).sub(WARHEAD_HOLD);
			}
		}

		poseStack.pushPose();
		// the item's first-person display transform (models/item/rocket_launcher_in_hand.json)
		poseStack.translate(-3.5F / 16.0F, 3.0F / 16.0F, 0.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(3.0F));
		AkArms.draw(player, base, poseStack, collector, light, GRIP, left, AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER);
		if (round != null) {
			poseStack.pushPose();
			poseStack.translate(round.x, round.y, round.z);
			Rpg7ItemRenderer.submitWarhead(poseStack, collector, light);
			poseStack.popPose();
		}
		poseStack.popPose();
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}
}
