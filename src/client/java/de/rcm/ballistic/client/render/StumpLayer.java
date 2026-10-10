package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.injury.Wounds;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * The gore on a player's body (and on the body they leave behind), all of it moving with the part it is on,
 * and each wound in the version the player's wound seed picks:
 * <ul>
 *   <li>a leg shot off below the knee or an arm below the elbow ({@link GoreStump}), at the end of the limb
 *       {@code PlayerModelMixin} shortened, with drops of blood falling off it;</li>
 *   <li>bullet holes in the body, front and back ({@link GoreTorso});</li>
 *   <li>the head: a graze, or the skull blown open ({@link GoreHead}), drops falling off the jaw.</li>
 * </ul>
 */
public final class StumpLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static final float P = GoreMesh.P;
	private boolean dripping = true;

	public StumpLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		LostLeg gore = (LostLeg) state;
		int legs = gore.ballisticmissiles$lostLeg();
		int arms = gore.ballisticmissiles$lostArm();
		int head = gore.ballisticmissiles$head();
		int torso = gore.ballisticmissiles$torso();
		if ((legs | arms | head | torso) == 0 || !ModConfig.gore || state.isInvisible) {
			return;
		}
		int seed = gore.ballisticmissiles$seed();
		// a body left lying: the wounds stop running and the blood on them dries dark and dull
		float dead = ((CorpseAge) state).ballisticmissiles$corpseAge();
		this.dripping = dead < 300.0F;
		GoreMesh.dryness(dead < 0.0F ? 0.0F : (dead - 300.0F) / 2400.0F);
		PlayerModel model = this.getParentModel();
		float age = state.ageInTicks;
		boolean slim = state.skin != null && state.skin.model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
		for (int side : new int[] {Wounds.LEFT, Wounds.RIGHT}) {
			int shift = side == Wounds.LEFT ? 8 : 10;
			if ((legs & side) != 0) {
				ModelPart leg = side == Wounds.LEFT ? model.leftLeg : model.rightLeg;
				// the end of the shortened leg: 12 px down in the leg's own, squashed, space
				this.stumpAt(poseStack, collector, light, leg, 0.0F, 12.0F, 1.0F, GoreStump.leg(seed >> shift), age, side);
			}
			if ((arms & side) != 0) {
				ModelPart arm = side == Wounds.LEFT ? model.leftArm : model.rightArm;
				// the arm hangs from the shoulder 2 px above its top; its middle is 1 px out (half a pixel, slim)
				float cx = (side == Wounds.LEFT ? 1.0F : -1.0F) * (slim ? 0.5F : 1.0F);
				this.stumpAt(poseStack, collector, light, arm, cx, 10.0F, slim ? 0.75F : 0.95F, GoreStump.arm(seed >> (shift + 4)), age, side + 2);
			}
		}
		if (torso > 0) {
			poseStack.pushPose();
			model.body.translateAndRotate(poseStack);
			GoreTorso.submit(poseStack, collector, light, torso, seed);
			poseStack.popPose();
		}
		if (head != 0) {
			poseStack.pushPose();
			model.head.translateAndRotate(poseStack);
			GoreHead.submit(poseStack, collector, light, head, seed);
			// drops running off the chin (and, the skull open, out of it)
			float[][] drips = head == Wounds.SHATTERED
				? new float[][] {{1.2F, 0.4F, -3.4F}, {-1.5F, 0.4F, -3.0F}, {-3.0F, 0.2F, 2.2F}}
				: new float[][] {{3.6F, 0.4F, -2.2F}};
			this.drops(poseStack, collector, light, drips, age, 3);
			poseStack.popPose();
		}
		GoreMesh.dryness(0.0F);
	}

	private void drops(PoseStack poseStack, SubmitNodeCollector collector, int light, float[][] at, float age, int seed) {
		if (!this.dripping) {
			return;
		}
		for (int i = 0; i < at.length; i++) {
			float phase = ((age + seed * 7.3F + i * 6.1F) % 17.0F) / 17.0F;
			if (phase > 0.85F) {
				continue;
			}
			poseStack.pushPose();
			poseStack.translate(at[i][0] * P, (at[i][1] + phase * phase * 16.0F) * P, at[i][2] * P);
			poseStack.scale(1.0F, 1.0F + phase * 1.5F, 1.0F);
			GoreStump.DROP.submit(poseStack, collector, light);
			poseStack.popPose();
		}
	}

	/**
	 * A stump at the end of a limb shortened by {@code PlayerModelMixin}: {@code end} px down the part's own
	 * (squashed) space, {@code cx} px across to its middle; drawn at true scale, {@code width} times as wide.
	 */
	private void stumpAt(PoseStack poseStack, SubmitNodeCollector collector, int light, ModelPart part, float cx, float end, float width, GoreMesh mesh, float age,
		int seed) {
		poseStack.pushPose();
		part.translateAndRotate(poseStack);
		poseStack.translate(cx * P, end * P, 0.0F);
		if (part.yScale > 0.01F) {
			poseStack.scale(1.0F, 1.0F / part.yScale, 1.0F);
		}
		poseStack.scale(width, width, width);
		mesh.submit(poseStack, collector, light);
		this.drops(poseStack, collector, light, new float[][] {{0.6F, 1.2F, -0.4F}, {-0.9F, 1.2F, 0.8F}}, age, seed);
		poseStack.popPose();
	}
}
