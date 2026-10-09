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
import net.minecraft.util.Mth;

/**
 * The player's own arms (their skin, sleeves and all) holding the rifle in first person: the right
 * fist round the pistol grip, the left hand under the handguard. They follow the hands of
 * {@link AkAnim} - to the magazine, down to the pouch and back, the right hand over to rack the
 * charging handle - while the shoulders stay put, so the arms bend and swing as the rifle recoils
 * and tips over for the reload.
 */
public final class AkArms {
	/** Shoulders in the hand space vanilla sets up for the main hand (before our recoil and reload motion). */
	public static final Vector3f RIGHT_SHOULDER = new Vector3f(0.34F, -0.48F, 0.52F);
	public static final Vector3f LEFT_SHOULDER = new Vector3f(-1.1F, -0.45F, 0.1F);
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
		AkAnim anim = ANIM.compute(state, now, Math.max(state.lastShot(), AkClient.lastShotTick), AkClient.checkTime(now));

		poseStack.pushPose();
		// the item's first-person display transform (models/item/ak47_in_hand.json) and the renderer's re-centring
		poseStack.translate(-3.2F / 16.0F, 2.4F / 16.0F, 0.5F / 16.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(3.0F));
		draw(player, base, poseStack, collector, light, anim.rightHand, anim.leftHand, RIGHT_SHOULDER, LEFT_SHOULDER);
		poseStack.popPose();
	}

	/** Upper arm and forearm (base space, blocks): the forearm is a whole Minecraft arm long, so the fist keeps its shape. */
	private static final float FOREARM = 10.5F / 16.0F;
	private static final float RIGHT_UPPER = 0.36F;
	private static final float LEFT_UPPER = 0.6F;
	/** Which way the elbows point (base space): down and out to the side, a little back. */
	private static final Vector3f RIGHT_POLE = new Vector3f(0.65F, -1.0F, 0.25F);
	private static final Vector3f LEFT_POLE = new Vector3f(-0.65F, -1.0F, 0.2F);

	/**
	 * Both arms, with {@code poseStack} already in the held item's own space and the fists at
	 * {@code rightHand} / {@code leftHand} there; the shoulders are given in {@code base} space. Each arm
	 * bends at the elbow: the forearm runs from the fist to an elbow found so that upper arm and forearm
	 * keep their lengths, the elbow dropping down and out to the side - the closer the hand comes to the
	 * shoulder (a magazine pulled in, a launcher on the shoulder) the more it bends.
	 */
	public static void draw(AbstractClientPlayer player, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light, Vector3f rightHand,
		Vector3f leftHand, Vector3f rightShoulderBase, Vector3f leftShoulderBase) {
		Minecraft mc = Minecraft.getInstance();
		PlayerModel model = mc.getEntityRenderDispatcher().getPlayerRenderer(player).getModel();
		Identifier skin = player.getSkin().body().texturePath();
		boolean slim = player.getSkin().model() == PlayerModelType.SLIM;
		Matrix4f toItem = new Matrix4f(poseStack.last().pose()).invert().mul(base);
		// how long a block of base space is in item space (the item may be drawn scaled)
		float scale = toItem.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F)).length();
		Vector3f rightShoulder = toItem.transformPosition(rightShoulderBase, new Vector3f());
		Vector3f leftShoulder = toItem.transformPosition(leftShoulderBase, new Vector3f());
		Vector3f rightElbow = elbow(rightShoulder, rightHand, RIGHT_UPPER * scale, FOREARM * scale, toItem.transformDirection(new Vector3f(RIGHT_POLE)));
		Vector3f leftElbow = elbow(leftShoulder, leftHand, LEFT_UPPER * scale, FOREARM * scale, toItem.transformDirection(new Vector3f(LEFT_POLE)));
		boolean rightSleeve = player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE);
		boolean leftSleeve = player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE);
		float rightFist = slim ? -0.5F : -1.0F;
		float leftFist = slim ? 0.5F : 1.0F;
		// forearms: fist at the hand, running back to the elbow
		arm(model.rightArm, model.rightSleeve, rightSleeve, rightHand, rightElbow, rightFist, 1.0F, false, poseStack, collector, light, skin);
		arm(model.leftArm, model.leftSleeve, leftSleeve, leftHand, leftElbow, leftFist, 1.0F, false, poseStack, collector, light, skin);
		// upper arms: from the elbow on up to the shoulder (mostly out of view), drawn the other way round so
		// the fist end of the model stays hidden at the shoulder
		arm(model.rightArm, model.rightSleeve, rightSleeve, rightElbow, rightShoulder, rightFist, rightElbow.distance(rightShoulder) / (12.0F / 16.0F), true,
			poseStack, collector, light, skin);
		arm(model.leftArm, model.leftSleeve, leftSleeve, leftElbow, leftShoulder, leftFist, leftElbow.distance(leftShoulder) / (12.0F / 16.0F), true,
			poseStack, collector, light, skin);
	}

	/**
	 * Where the elbow is (two-bone inverse kinematics): upper arm {@code upper} long from the shoulder,
	 * forearm {@code fore} long to the hand, bent towards {@code pole}. Out of reach, the arm is straight.
	 */
	private static Vector3f elbow(Vector3f shoulder, Vector3f hand, float upper, float fore, Vector3f pole) {
		Vector3f d = new Vector3f(hand).sub(shoulder);
		float dist = d.length();
		if (dist < 1.0E-4F) {
			return new Vector3f(hand);
		}
		d.div(dist);
		if (dist >= upper + fore - 1.0E-3F) {
			// at full stretch: the elbow on the straight line, the forearm its own length back from the hand
			return new Vector3f(hand).sub(new Vector3f(d).mul(fore));
		}
		// distance along shoulder -> hand to the elbow's foot, and how far out to the side it sits
		float a = (upper * upper - fore * fore + dist * dist) / (2.0F * dist);
		float h = Mth.sqrt(Math.max(upper * upper - a * a, 0.0F));
		Vector3f side = new Vector3f(pole).sub(new Vector3f(d).mul(pole.dot(d)));
		if (side.lengthSquared() < 1.0E-6F) {
			side.set(0.0F, -1.0F, 0.0F).sub(new Vector3f(d).mul(-d.y));
		}
		side.normalize();
		return new Vector3f(shoulder).add(new Vector3f(d).mul(a)).add(side.mul(h));
	}

	/**
	 * Lays one segment of an arm: the model arm hangs along its +Y from the shoulder pivot, the fist about
	 * 8.5 px down. A forearm puts the fist at {@code from} and runs back towards {@code to} (the elbow);
	 * {@code reversed} (an upper arm) starts the top of the model at {@code from} (the elbow) and runs on to
	 * {@code to} (the shoulder), {@code length} times as long as the model. The outer face (away from the
	 * body) is turned outward.
	 */
	private static void arm(ModelPart arm, ModelPart sleeve, boolean sleeveShown, Vector3f from, Vector3f to, float fistX, float length, boolean reversed,
		PoseStack poseStack, SubmitNodeCollector collector, int light, Identifier skin) {
		// +Y of the model: from the shoulder end towards the fist end
		Vector3f down = reversed ? new Vector3f(to).sub(from) : new Vector3f(from).sub(to);
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
		poseStack.translate(from.x, from.y, from.z);
		poseStack.mulPose(rot);
		poseStack.scale(THICK, Math.max(0.05F, length), THICK);
		if (reversed) {
			// the top of the model (2 px above its pivot) at the elbow, a pixel into the forearm to close the joint
			poseStack.translate(-fistX / 16.0F, 1.0F / 16.0F, 0.0F);
		} else {
			poseStack.translate(-fistX / 16.0F, -8.5F / 16.0F, 0.0F);
		}

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
