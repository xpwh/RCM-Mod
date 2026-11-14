package de.rcm.ballistic.client.render;

import de.rcm.ballistic.client.ModConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * Your own body in first person: look down and there are your legs, your chest, your boots walking - not empty air
 * with a pair of floating arms. The world draws your body like anyone else's (LevelRendererMixin lets it), minus
 * what would sit in your face or doubles what the first-person view already shows: the head and hat, the helmet,
 * the arms (the first-person ones are drawn on their own) and what is in your hands. The body is drawn a little
 * behind the eyes, more the further you look down, so you look down over your chest rather than out of it.
 */
public final class FirstPersonBody {
	/** The render state of your body extracted for this frame's world (not a GUI preview of you). */
	private static Object marked;

	private FirstPersonBody() {
	}

	/** Should the world draw your body this frame (the camera is in your head)? */
	public static boolean wanted(Camera camera) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		return ModConfig.firstPersonBody && p != null && camera.entity() == p && !camera.isDetached() && p.isAlive() && !p.isSpectator()
			&& !p.isSleeping() && !p.isFallFlying() && !p.isVisuallySwimming() && !p.isInvisible() && !p.isPassenger() && !p.isScoping()
			&& !p.isAutoSpinAttack();
	}

	/** The world just extracted {@code state} for {@code entity}: if it is your body drawn from inside, strip it down. */
	public static void extracted(Object entity, EntityRenderState state) {
		Minecraft mc = Minecraft.getInstance();
		if (entity != mc.player || !(state instanceof AvatarRenderState s) || !wanted(mc.gameRenderer.getMainCamera())) {
			return;
		}
		marked = s;
		s.headEquipment = ItemStack.EMPTY;
		s.headItem.clear();
		s.wornHeadType = null;
		s.heldOnHead.clear();
		s.rightHandItemState.clear();
		s.leftHandItemState.clear();
		s.rightHandItemStack = ItemStack.EMPTY;
		s.leftHandItemStack = ItemStack.EMPTY;
		s.showHat = false;
		s.showLeftSleeve = false;
		s.showRightSleeve = false;
		s.parrotOnLeftShoulder = null;
		s.parrotOnRightShoulder = null;
		s.showExtraEars = false;
		s.nameTag = null;
		s.scoreText = null;
	}

	/** After the model's pose, for the body drawn from inside: no head, no arms, the rest set back behind the eyes. */
	public static boolean pose(net.minecraft.client.model.HumanoidModel<?> m, net.minecraft.client.renderer.entity.state.HumanoidRenderState state) {
		if (state != marked) {
			return false;
		}
		for (var part : new net.minecraft.client.model.geom.ModelPart[] {m.head, m.rightArm, m.leftArm}) {
			part.xScale = 0.0F;
			part.yScale = 0.0F;
			part.zScale = 0.0F;
		}
		// back from the eyes (model +z is behind): a little looking ahead, more looking down
		float down = Mth.clamp(state.xRot / 80.0F, 0.0F, 1.0F);
		float back = 2.0F + 2.5F * down;
		m.body.z += back;
		m.rightLeg.z += back;
		m.leftLeg.z += back;
		return true;
	}
}
