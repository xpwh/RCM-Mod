package de.rcm.ballistic.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.effect.BlastShader;
import de.rcm.ballistic.client.effect.ClientEffects;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera shake from blasts, launches and shockwaves; per-frame blast shader parameters. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/**
	 * Far plane of at least 4 km, so far-away missiles, contrails and mushroom clouds are not clipped
	 * when the vanilla render distance is low (as with Distant Horizons drawing the far terrain).
	 */
	@Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
	private void ballisticmissiles$farPlane(CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(Math.max(cir.getReturnValueF(), 4096.0F));
	}

	@Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
	private void ballisticmissiles$blastShader(DeltaTracker deltaTracker, boolean advance, CallbackInfo ci) {
		BlastShader.frame(Minecraft.getInstance(), deltaTracker.getGameTimeDeltaPartialTick(false));
	}

	/** Aiming down the sights the head and the weapon hardly bob as you walk: you steady them. */
	@com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "bobView", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/entity/ClientAvatarState;getInterpolatedBob(F)F"))
	private float ballisticmissiles$steadyAim(float bob) {
		float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float aim = Math.max(de.rcm.ballistic.client.gun.AkClient.aimProgress(partialTick), de.rcm.ballistic.client.item.RpgClient.aimProgress(partialTick));
		return bob * (1.0F - 0.88F * aim);
	}

	@Inject(method = "bobHurt", at = @At("HEAD"))
	private void ballisticmissiles$shake(PoseStack poseStack, float partialTick, CallbackInfo ci) {
		if (de.rcm.ballistic.client.drone.DroneClient.isFlying()) {
			// the drone banks into its turns, and the picture with it
			poseStack.mulPose(Axis.ZP.rotationDegrees(de.rcm.ballistic.client.drone.DroneClient.cameraRoll(partialTick)));
		}
		if (de.rcm.ballistic.client.fighter.FighterClient.isFlying()) {
			// the horizon tilts with the jet's bank
			poseStack.mulPose(Axis.ZP.rotationDegrees(de.rcm.ballistic.client.fighter.FighterClient.cameraRoll(partialTick)));
		}
		float shake = ClientEffects.shake(partialTick);
		if (shake <= 0.001F) {
			return;
		}
		float t = (System.nanoTime() % 1_000_000_000_000L) / 1.0E9F;
		float pitch = (Mth.sin(t * 41.0F) + Mth.sin(t * 67.0F) * 0.6F) * shake * 0.9F;
		float roll = (Mth.sin(t * 53.0F + 1.3F) + Mth.sin(t * 29.0F) * 0.5F) * shake * 0.7F;
		float yaw = Mth.sin(t * 37.0F + 2.1F) * shake * 0.5F;
		poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
		poseStack.mulPose(Axis.ZP.rotationDegrees(roll));
		poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
	}
}
