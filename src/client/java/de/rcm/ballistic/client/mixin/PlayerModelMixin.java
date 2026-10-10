package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.render.LostLeg;
import de.rcm.ballistic.injury.Wounds;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A leg shot away below the knee, an arm below the elbow: only the upper half is left (its trouser leg or sleeve goes with it). */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void ballisticmissiles$halfLeg(AvatarRenderState state, CallbackInfo ci) {
		LostLeg gore = (LostLeg) state;
		de.rcm.ballistic.client.render.CorpseAge body = (de.rcm.ballistic.client.render.CorpseAge) state;
		if (body.ballisticmissiles$corpseAge() >= 0.0F) {
			this.ballisticmissiles$limp((PlayerModel) (Object) this, gore.ballisticmissiles$seed(), body.ballisticmissiles$flail(), state.ageInTicks);
		}
		PlayerModel self = (PlayerModel) (Object) this;
		// the jaw shot away, a hole right through: those parts are drawn by StumpLayer, with the piece cut out
		int extra = de.rcm.ballistic.client.ModConfig.gore ? gore.ballisticmissiles$extra() : 0;
		boolean jaw = (extra & Wounds.JAW) != 0;
		boolean holed = Wounds.NONE.withExtra(extra).holed();
		self.head.skipDraw = jaw;
		self.hat.skipDraw = jaw;
		self.body.skipDraw = holed;
		self.jacket.skipDraw = holed;
		int lost = gore.ballisticmissiles$lostLeg();
		int arms = gore.ballisticmissiles$lostArm();
		if ((lost == 0 && arms == 0 && gore.ballisticmissiles$head() == 0) || !de.rcm.ballistic.client.ModConfig.gore) {
			return;
		}
		if ((lost & Wounds.LEFT) != 0) {
			self.leftLeg.yScale = 0.5F;
		}
		if ((lost & Wounds.RIGHT) != 0) {
			self.rightLeg.yScale = 0.5F;
		}
		// an arm gone below the elbow: the upper arm is left (the sleeve is part of it)
		if ((arms & Wounds.LEFT) != 0) {
			self.leftArm.yScale = 0.5F;
		}
		if ((arms & Wounds.RIGHT) != 0) {
			self.rightArm.yScale = 0.5F;
		}
		// the skull blown open: the hair/hat layer is torn away with it
		if (gore.ballisticmissiles$head() == Wounds.SHATTERED) {
			self.hat.visible = false;
		}
	}

	/**
	 * A dead body is limp: arms flung out a little, legs apart, the head lolling to one side, each body in
	 * its own way - and while it is thrown about, everything flops.
	 */
	@org.spongepowered.asm.mixin.Unique
	private void ballisticmissiles$limp(PlayerModel m, int seed, float flail, float t) {
		net.minecraft.util.RandomSource r = net.minecraft.util.RandomSource.create(seed == 0 ? 77L : seed);
		float[] k = new float[8];
		for (int i = 0; i < k.length; i++) {
			k[i] = r.nextFloat();
		}
		m.rightArm.zRot = 0.2F + 0.55F * k[0] + flail * net.minecraft.util.Mth.sin(t * 1.7F) * 0.9F;
		m.leftArm.zRot = -0.2F - 0.55F * k[1] - flail * net.minecraft.util.Mth.sin(t * 1.9F + 1.0F) * 0.9F;
		m.rightArm.xRot = (k[2] - 0.5F) * 0.9F + flail * net.minecraft.util.Mth.sin(t * 1.3F + 0.4F) * 1.3F;
		m.leftArm.xRot = (k[3] - 0.5F) * 0.9F + flail * net.minecraft.util.Mth.sin(t * 1.45F + 2.0F) * 1.3F;
		m.rightLeg.zRot = 0.06F + 0.16F * k[4] + flail * net.minecraft.util.Mth.sin(t * 1.6F + 0.7F) * 0.45F;
		m.leftLeg.zRot = -0.06F - 0.16F * k[5] - flail * net.minecraft.util.Mth.sin(t * 1.5F + 1.9F) * 0.45F;
		m.rightLeg.xRot = (k[6] - 0.5F) * 0.3F + flail * net.minecraft.util.Mth.sin(t * 1.2F) * 0.8F;
		m.leftLeg.xRot = (k[7] - 0.5F) * 0.3F - flail * net.minecraft.util.Mth.sin(t * 1.25F + 0.5F) * 0.8F;
		m.rightLeg.yRot = 0.0F;
		m.leftLeg.yRot = 0.0F;
		m.head.yRot = (k[0] - 0.5F) * 1.4F + flail * net.minecraft.util.Mth.sin(t * 2.1F) * 0.6F;
		m.head.zRot = (k[1] - 0.5F) * 0.5F;
		m.head.xRot = flail * net.minecraft.util.Mth.sin(t * 1.8F + 1.1F) * 0.5F;
	}
}
