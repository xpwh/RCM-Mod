package de.rcm.ballistic.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The space our own first-person hands are animated in. Poses are written as if in camera space (x right, y up,
 * -z ahead) and drawn through the renderer's hand stack as vanilla sets it up before an item - with the view
 * bobbing and the lag of the arm behind the turning camera - moved out to where vanilla puts the right hand.
 * So the arms sway and bob with you like any held item, instead of being fixed to the screen.
 */
public final class HandSpace {
	/** Where vanilla puts the right hand (applyItemArmTransform, nothing equipping). */
	private static final Vector3f ARM = new Vector3f(0.56F, -0.52F, -0.72F);

	private HandSpace() {
	}

	/** Pushes the hand pose; returns it (the base the arms are drawn from). Pop it when done. */
	public static Matrix4f enter(PoseStack poseStack) {
		poseStack.pushPose();
		poseStack.translate(ARM.x, ARM.y, ARM.z);
		return new Matrix4f(poseStack.last().pose());
	}

	/** A camera-space point in hand space. */
	public static Vector3f hand(Vector3f camera) {
		return new Vector3f(camera).sub(ARM);
	}
}
