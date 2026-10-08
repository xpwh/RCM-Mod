package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.gun.GunState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * The rifle in first person: a slow breathing sway, the stock punching back into the shoulder and
 * the muzzle jumping with every shot (more as a burst goes on), and the reload - the rifle rolled
 * over to the left and tipped down to bring the magazine well into view, the old magazine rocked out,
 * the new one rocked in and slapped home, then for an empty gun the rifle turned right and the
 * charging handle racked, and back up on target.
 */
public final class AkFirstPerson {
	private AkFirstPerson() {
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	public static void transform(PoseStack poseStack, float partialTick, ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		float now = mc.level.getGameTime() + partialTick;
		GunState state = AkItem.state(stack);

		// breathing sway
		poseStack.mulPose(Axis.XP.rotationDegrees(Mth.sin(now * 0.045F) * 0.35F));
		poseStack.mulPose(Axis.YP.rotationDegrees(Mth.cos(now * 0.031F) * 0.3F));

		// reload choreography
		if (state.reloading()) {
			float r = now - state.reloadStart();
			int duration = state.reloadKind() == GunState.EMPTY ? AkItem.RELOAD_EMPTY : AkItem.RELOAD_TACTICAL;
			float tilt = smooth(r / 9.0F) * (1.0F - smooth((r - (duration - 9)) / 9.0F));
			// the slap of the new magazine going home
			float slap = r > AkItem.T_MAG_IN - 1 && r < AkItem.T_MAG_IN + 4 ? Mth.sin((r - (AkItem.T_MAG_IN - 1)) / 5.0F * Mth.PI) : 0.0F;
			// racking the handle: the rifle is turned right to bring the handle under your hand
			float rack = 0.0F;
			if (state.reloadKind() == GunState.EMPTY) {
				rack = smooth((r - (AkItem.T_CHARGE - 12)) / 6.0F) * (1.0F - smooth((r - (AkItem.T_CHARGE + 2)) / 8.0F));
			}
			poseStack.translate(-0.06F * tilt, -0.12F * tilt + 0.02F * slap, 0.05F * tilt);
			poseStack.mulPose(Axis.ZP.rotationDegrees(28.0F * tilt - 45.0F * rack));
			poseStack.mulPose(Axis.XP.rotationDegrees(-14.0F * tilt + 4.0F * slap));
			poseStack.mulPose(Axis.YP.rotationDegrees(10.0F * tilt - 12.0F * rack));
		}

		// recoil: the local player's own shots (instant), everybody else's from the synced state
		float shot = AkClient.lastShotTick > 0 ? now - AkClient.lastShotTick : now - state.lastShot();
		if (shot >= 0.0F && shot < 8.0F) {
			float kick = shot < 0.6F ? shot / 0.6F : (float) Math.exp(-(shot - 0.6F) * 0.9F);
			float build = 1.0F + Math.min(AkClient.burst, 10) * 0.05F;
			poseStack.translate(0.0F, 0.012F * kick, 0.07F * kick * build);
			poseStack.mulPose(Axis.XP.rotationDegrees(3.2F * kick * build));
			poseStack.mulPose(Axis.ZP.rotationDegrees((AkClient.burst % 2 == 0 ? 1.0F : -1.0F) * 0.8F * kick));
		}
	}
}
