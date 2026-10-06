package de.rcm.ballistic.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.effect.ClientEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Camera shake from blasts, launches and shockwaves. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "bobHurt", at = @At("HEAD"))
	private void ballisticmissiles$shake(PoseStack poseStack, float partialTick, CallbackInfo ci) {
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
