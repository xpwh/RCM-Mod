package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.gun.GunState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The player's own arms (their skin, sleeves and all) holding the rifle in first person: the right
 * fist round the pistol grip, the left hand under the handguard. They follow the hands of
 * {@link AkAnim} - to the magazine, down to the pouch and back, the right hand over to rack the
 * charging handle - while the shoulders stay put, so the arms bend and swing as the rifle recoils
 * and tips over for the reload.
 */
public final class AkArms {
	/** Shoulders in the hand space vanilla sets up for the main hand (before our recoil and reload motion). */
	private static final Vector3f RIGHT_SHOULDER = new Vector3f(0.02F, -0.4F, 0.58F);
	private static final Vector3f LEFT_SHOULDER = new Vector3f(-0.84F, -0.34F, 0.5F);
	/** Arms are slimmed to sit right on a rifle of this size. */
	private static final float THICK = 0.5F;
	private static final AkAnim ANIM = new AkAnim();

	private AkArms() {
	}

	/**
	 * @param base   the pose before {@link AkFirstPerson} moved the rifle
	 * @param poseStack the pose the item is about to be rendered with (display transform not yet applied)
	 */
	public static void render(AbstractClientPlayer player, ItemStack stack, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light,
		float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		float now = mc.level.getGameTime() + partialTick;
		GunState state = AkItem.state(stack);
		AkAnim anim = ANIM.compute(state, now, Math.max(state.lastShot(), AkClient.lastShotTick));

		PlayerModel model = mc.getEntityRenderDispatcher().getPlayerRenderer(player).getModel();
		Identifier skin = player.getSkin().body().texturePath();
		boolean slim = player.getSkin().model() == PlayerModelType.SLIM;

		poseStack.pushPose();
		// the item's first-person display transform (models/item/ak47_in_hand.json) and the renderer's re-centring
		poseStack.translate(-3.2F / 16.0F, 2.4F / 16.0F, 0.5F / 16.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(3.0F));
		Matrix4f toGun = new Matrix4f(poseStack.last().pose()).invert().mul(base);
		Vector3f rightShoulder = toGun.transformPosition(RIGHT_SHOULDER, new Vector3f());
		Vector3f leftShoulder = toGun.transformPosition(LEFT_SHOULDER, new Vector3f());

		arm(model.rightArm, model.rightSleeve, player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE), anim.rightHand, rightShoulder, slim ? -0.5F : -1.0F,
			poseStack, collector, light, skin);
		arm(model.leftArm, model.leftSleeve, player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE), anim.leftHand, leftShoulder, slim ? 0.5F : 1.0F,
			poseStack, collector, light, skin);
		poseStack.popPose();
	}

	/**
	 * Lays the arm from its shoulder to the fist: the model arm hangs along its +Y from the shoulder
	 * pivot, the fist about 8.5 px down; its outer face (away from the body) is turned outward.
	 */
	private static void arm(ModelPart arm, ModelPart sleeve, boolean sleeveShown, Vector3f hand, Vector3f shoulder, float fistX, PoseStack poseStack,
		SubmitNodeCollector collector, int light, Identifier skin) {
		Vector3f down = new Vector3f(hand).sub(shoulder);
		if (down.lengthSquared() < 1.0E-6F) {
			return;
		}
		down.normalize();
		// model +X points to the player's left: for both arms that is the gun's -X here
		Vector3f side = new Vector3f(-1.0F, 0.0F, 0.0F);
		side.sub(new Vector3f(down).mul(side.dot(down)));
		if (side.lengthSquared() < 1.0E-4F) {
			side.set(0.0F, 0.0F, 1.0F).sub(new Vector3f(down).mul(down.z));
		}
		side.normalize();
		Vector3f back = new Vector3f(side).cross(down);
		Quaternionf rot = new Quaternionf().setFromNormalized(new Matrix3f(side, down, back));

		poseStack.pushPose();
		poseStack.translate(hand.x, hand.y, hand.z);
		poseStack.mulPose(rot);
		poseStack.scale(THICK, 1.0F, THICK);
		poseStack.translate(-fistX / 16.0F, -8.5F / 16.0F, 0.0F);

		arm.resetPose();
		arm.x = 0.0F;
		arm.y = 0.0F;
		arm.z = 0.0F;
		arm.xRot = 0.0F;
		arm.yRot = 0.0F;
		arm.zRot = 0.0F;
		arm.visible = true;
		sleeve.visible = sleeveShown;
		collector.submitModelPart(arm, poseStack, RenderTypes.entityTranslucent(skin), light, OverlayTexture.NO_OVERLAY, null);
		poseStack.popPose();
	}
}
