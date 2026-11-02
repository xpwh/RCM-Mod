package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.HatchSiloBlock;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The round-lid silo (after the Soviet/Russian silo launchers), modelled in Blender (tools/structures/silo_hatch.py):
 * a domed armoured lid with radial gussets on a raised concrete collar in a square pad, locked by eight radial bolts
 * in their housings, hinged on a concrete abutment and hauled open by two hydraulic rams fed from a power unit.
 * The opening is phased like the real thing ({@link HatchSiloBlock#bolts}, {@link HatchSiloBlock#lidAngle}): bolts
 * back, the lid cracks off its seal, then swings up past vertical.
 * Block space with the block centred on x = z = 0, ground level at y = 1.
 */
final class RoundHatchModel {
	private static final float GROUND = 1.0F;
	/** Top of the collar, where the lid seats. */
	private static final float SEAT = GROUND + 0.1F;
	/** The hinge line, parallel to Z. */
	private static final float HINGE_X = 2.06F;
	private static final float HINGE_Y = SEAT + 0.3F;
	/** The rams: fixed trunnion on the collar, and the eye on the lid's lug (closed position), at z = +-RAM_Z. */
	private static final float RAM_Z = 1.34F;
	private static final Vector3f RAM_BASE = new Vector3f(2.34F, SEAT + 0.12F, 0.0F);
	private static final Vector3f RAM_EYE = new Vector3f(1.42F, SEAT + 0.56F, 0.0F);
	private static final int BOLTS = 8;
	private static final float BOLT_TRAVEL = 0.3F;
	private static final float[][] BEACONS = {{-1.7F, 1.62F}, {-1.7F, -1.62F}};

	/** The headworks as built in Blender: collar (fixed), lid (about the hinge), one bolt, a ram's cylinder and rod. */
	static final StructureModel MODEL = StructureModel.load("silo_hatch");
	static final BoxMesh COLLAR = MODEL.part("collar");
	private static final BoxMesh LID = MODEL.part("lid");
	private static final BoxMesh GLOW = buildGlow();

	private RoundHatchModel() {
	}

	static void submit(SiloRenderer.State state, PoseStack poseStack, SubmitNodeCollector collector, int light, CameraRenderState camera) {
		float t = state.travel;
		float bolts = HatchSiloBlock.bolts(t);
		float angle = HatchSiloBlock.lidAngle(t);

		// locking bolts slide out of the lid's rim into their housings
		for (int i = 0; i < BOLTS; i++) {
			poseStack.pushPose();
			poseStack.mulPose(new Quaternionf().rotationY(boltAngle(i)));
			poseStack.translate(bolts * BOLT_TRAVEL, 0.0F, 0.0F);
			MODEL.submit("bolt", poseStack, collector, light);
			poseStack.popPose();
		}

		// the lid about its hinge (the model's lid is about its hinge already)
		poseStack.pushPose();
		poseStack.translate(HINGE_X, HINGE_Y, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationZ(-angle));
		ModelPick.part(state, poseStack, camera, LID);
		MODEL.submit("lid", poseStack, collector, light);
		poseStack.popPose();

		// the rams follow the lid: cylinder from the trunnion, rod from the lid's eye, each pointing at the other
		Vector3f eye = swing(RAM_EYE, angle);
		Vector3f dir = new Vector3f(eye).sub(RAM_BASE).normalize();
		for (float side : new float[] {-1.0F, 1.0F}) {
			float z = side * RAM_Z;
			poseStack.pushPose();
			poseStack.translate(RAM_BASE.x, RAM_BASE.y, z);
			poseStack.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, dir.x, dir.y, dir.z));
			MODEL.submit("ram", poseStack, collector, light);
			poseStack.popPose();
			poseStack.pushPose();
			poseStack.translate(eye.x, eye.y, z);
			poseStack.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, -dir.x, -dir.y, -dir.z));
			MODEL.submit("rod", poseStack, collector, light);
			poseStack.popPose();
		}

		if (state.counting && Mth.sin(state.time * 0.9F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			for (int i = 0; i < BEACONS.length; i++) {
				de.rcm.ballistic.client.effect.DynamicLights.blockLamp(state.blockPos, i, BEACONS[i][0], SEAT + 0.85F, BEACONS[i][1], 0xFF2414, 0.85F, 10.0F);
			}
		}
	}

	/** A point fixed to the lid, after the lid has turned {@code angle} about its hinge. */
	private static Vector3f swing(Vector3f p, float angle) {
		float x = p.x - HINGE_X;
		float y = p.y - HINGE_Y;
		float c = Mth.cos(-angle);
		float s = Mth.sin(-angle);
		return new Vector3f(HINGE_X + x * c - y * s, HINGE_Y + x * s + y * c, p.z);
	}

	/** Bolts spaced round the rim over three quarters of a turn, leaving the hinge side clear. */
	private static float boltAngle(int i) {
		return Mth.PI * 0.25F + i * (Mth.PI * 1.5F) / (BOLTS - 1);
	}

	/** The beacons' lenses lit up: a dome just over each red lens of the model. */
	private static BoxMesh buildGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] p : BEACONS) {
			b.revolve(RED_LAMP, v(p[0], SEAT + 0.755F, p[1]), v(0, 1, 0), new float[][] {{0.0F, 0.1F}, {0.125F, 0.1F}, {0.185F, 0.068F}, {0.21F, 0.0F}}, 10);
		}
		return b.build();
	}
}
