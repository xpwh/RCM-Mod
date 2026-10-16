package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.GRATING;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RUST;
import static de.rcm.ballistic.client.render.StructureKit.SHAFT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;
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
 * The round-lid silo (after the Soviet/Russian silo launchers): a domed armoured lid on a concrete
 * collar flush with the ground, locked by eight radial bolts, hinged on one side and hauled open by two
 * hydraulic rams. The opening is phased like the real thing ({@link HatchSiloBlock#bolts},
 * {@link HatchSiloBlock#lidAngle}): bolts back, the lid cracks off its seal, then swings up past vertical.
 * Block space with the block centred on x = z = 0, ground level at y = 1.
 */
final class RoundHatchModel {
	private static final float GROUND = 1.0F;
	/** Top of the collar, where the lid seats. */
	private static final float SEAT = GROUND + 0.1F;
	private static final float TUBE_R = 1.5F;
	private static final float LID_R = 1.74F;
	/** The hinge line, parallel to Z. */
	private static final float HINGE_X = 2.06F;
	private static final float HINGE_Y = SEAT + 0.3F;
	/** The rams: fixed trunnion on the collar, and the eye on the lid's lug (closed position), at z = +-RAM_Z. */
	private static final float RAM_Z = 1.34F;
	private static final Vector3f RAM_BASE = new Vector3f(2.34F, SEAT + 0.12F, 0.0F);
	private static final Vector3f RAM_EYE = new Vector3f(1.42F, SEAT + 0.56F, 0.0F);
	private static final float CYL_LENGTH = 0.62F;
	private static final float ROD_LENGTH = 0.6F;
	private static final int BOLTS = 8;
	private static final float BOLT_TRAVEL = 0.3F;
	private static final float[][] BEACONS = {{-1.7F, 1.62F}, {-1.7F, -1.62F}};

	static final BoxMesh COLLAR = buildCollar();
	private static final BoxMesh LID = buildLid();
	private static final BoxMesh BOLT = buildBolt();
	private static final BoxMesh CYLINDER = buildCylinder();
	private static final BoxMesh ROD = buildRod();
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
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> BOLT.emit(pose, consumer, light));
			poseStack.popPose();
		}

		// the lid about its hinge
		poseStack.pushPose();
		poseStack.translate(HINGE_X, HINGE_Y, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationZ(-angle));
		poseStack.translate(-HINGE_X, -HINGE_Y, 0.0F);
		ModelPick.part(state, poseStack, camera, LID);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> LID.emit(pose, consumer, light));
		poseStack.popPose();

		// the rams follow the lid: cylinder from the trunnion, rod from the lid's eye, each pointing at the other
		Vector3f eye = swing(RAM_EYE, angle);
		Vector3f dir = new Vector3f(eye).sub(RAM_BASE).normalize();
		for (float side : new float[] {-1.0F, 1.0F}) {
			float z = side * RAM_Z;
			poseStack.pushPose();
			poseStack.translate(RAM_BASE.x, RAM_BASE.y, z);
			poseStack.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, dir.x, dir.y, dir.z));
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> CYLINDER.emit(pose, consumer, light));
			poseStack.popPose();
			poseStack.pushPose();
			poseStack.translate(eye.x, eye.y, z);
			poseStack.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, -dir.x, -dir.y, -dir.z));
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> ROD.emit(pose, consumer, light));
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

	/** Bolts spaced round the rim, leaving the hinge side clear. */
	private static float boltAngle(int i) {
		return Mth.TWO_PI * (i + 0.5F) / BOLTS;
	}

	// ------------------------------------------------------------------ meshes

	/** A ring (or with rIn 0, a disc) between two heights, its radii free to change from bottom to top. */
	private static void ring(BoxMesh.Builder b, int patch, float rIn0, float rOut0, float y0, float rIn1, float rOut1, float y1, int segments) {
		ring(b, patch, rIn0, rOut0, y0, rIn1, rOut1, y1, segments, 0.0F, 0.0F);
	}

	private static void ring(BoxMesh.Builder b, int patch, float rIn0, float rOut0, float y0, float rIn1, float rOut1, float y1, int segments, float cx, float cz) {
		rIn0 = Math.max(rIn0, 0.002F);
		rIn1 = Math.max(rIn1, 0.002F);
		for (int s = 0; s < segments; s++) {
			float a0 = Mth.TWO_PI * s / segments;
			float a1 = Mth.TWO_PI * (s + 1) / segments;
			Vector3f c = v(cx, 0.0F, cz);
			b.hexa(patch,
				polar(rIn0, a0, y0).add(c), polar(rOut0, a0, y0).add(c), polar(rOut0, a1, y0).add(c), polar(rIn0, a1, y0).add(c),
				polar(rIn1, a0, y1).add(c), polar(rOut1, a0, y1).add(c), polar(rOut1, a1, y1).add(c), polar(rIn1, a1, y1).add(c));
		}
	}

	private static Vector3f polar(float r, float a, float y) {
		return v(Mth.cos(a) * r, y, Mth.sin(a) * r);
	}

	/** A box turned {@code angle} about the vertical axis through the block centre. */
	private static void turnedBox(BoxMesh.Builder b, int patch, float angle, float x0, float y0, float z0, float x1, float y1, float z1) {
		Quaternionf q = new Quaternionf().rotationY(angle);
		Vector3f[] c = {
			v(x0, y0, z0), v(x1, y0, z0), v(x1, y0, z1), v(x0, y0, z1),
			v(x0, y1, z0), v(x1, y1, z0), v(x1, y1, z1), v(x0, y1, z1)
		};
		for (Vector3f p : c) {
			q.transform(p);
		}
		b.hexa(patch, c);
	}

	private static BoxMesh buildCollar() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// the collar: a flat concrete ring round the tube mouth, chamfered down to the pad at its edge
		ring(b, CONCRETE, TUBE_R, 2.3F, GROUND - 0.04F, TUBE_R, 2.3F, SEAT, 40);
		ring(b, CONCRETE_DARK, 2.3F, 2.3F, SEAT, 2.3F, 2.5F, GROUND - 0.02F, 40);
		// steel seat ring the lid closes on, and the round throat lining the top of the tube
		ring(b, STEEL, TUBE_R - 0.02F, 1.8F, SEAT, TUBE_R - 0.02F, 1.8F, SEAT + 0.02F, 40);
		ring(b, SHAFT, 1.4F, TUBE_R, GROUND - 2.6F, 1.4F, TUBE_R, GROUND - 0.04F, 32);
		ring(b, STEEL, 1.36F, 1.4F, GROUND - 0.3F, 1.36F, 1.4F, GROUND - 0.15F, 32); // stiffening ring in the throat
		// painted hazard band round the lid's sweep
		ring(b, HAZARD, 2.0F, 2.22F, SEAT, 2.0F, 2.22F, SEAT + 0.006F, 40);
		// bolt housings: two cheeks per bolt, the bolt slides between them
		for (int i = 0; i < BOLTS; i++) {
			float a = boltAngle(i);
			turnedBox(b, CONCRETE_DARK, a, 1.82F, SEAT, -0.16F, 2.3F, SEAT + 0.16F, -0.09F);
			turnedBox(b, CONCRETE_DARK, a, 1.82F, SEAT, 0.09F, 2.3F, SEAT + 0.16F, 0.16F);
			turnedBox(b, STEEL, a, 2.2F, SEAT, -0.09F, 2.3F, SEAT + 0.16F, 0.09F); // end plate with the actuator
		}
		// hinge brackets on the collar either side of each lid knuckle, and their pins
		for (float z : new float[] {-0.95F, 0.95F}) {
			for (float side : new float[] {-1.0F, 1.0F}) {
				float z0 = z + side * 0.17F;
				b.box(1.92F, SEAT, Math.min(z0, z0 + side * 0.1F), 2.26F, HINGE_Y + 0.16F, Math.max(z0, z0 + side * 0.1F), STEEL);
			}
			b.revolve(RUST, v(HINGE_X, HINGE_Y, z - 0.3F), v(0, 0, 1), new float[][] {{0.0F, 0.06F}, {0.6F, 0.06F}}, 8);
		}
		// ram trunnions
		for (float side : new float[] {-1.0F, 1.0F}) {
			float z = side * RAM_Z;
			b.box(RAM_BASE.x - 0.14F, SEAT, z - 0.16F, RAM_BASE.x + 0.14F, RAM_BASE.y + 0.04F, z + 0.16F, STEEL);
			b.revolve(RUST, v(RAM_BASE.x, RAM_BASE.y, z - 0.2F), v(0, 0, 1), new float[][] {{0.0F, 0.05F}, {0.4F, 0.05F}}, 8);
		}
		// warning beacons on short posts, on the side away from the swing
		for (float[] p : BEACONS) {
			b.box(p[0] - 0.05F, SEAT, p[1] - 0.05F, p[0] + 0.05F, SEAT + 0.75F, p[1] + 0.05F, STEEL);
			b.box(p[0] - 0.1F, SEAT + 0.75F, p[1] - 0.1F, p[0] + 0.1F, SEAT + 0.95F, p[1] + 0.1F, RED_LAMP);
		}
		// seismic/intrusion sensor and the whip antenna of the launch control link
		b.box(-2.32F, SEAT, -0.12F, -2.08F, SEAT + 0.35F, 0.12F, PANEL);
		b.box(-2.22F, SEAT + 0.35F, -0.02F, -2.18F, SEAT + 2.6F, 0.02F, BLACK);
		b.box(-2.26F, SEAT + 0.35F, -0.06F, -2.14F, SEAT + 0.5F, 0.06F, STEEL);
		// small round personnel hatch with its handwheel, between two bolts
		ring(b, CONCRETE_DARK, 0.0F, 0.27F, SEAT, 0.0F, 0.25F, SEAT + 0.07F, 16, 0.0F, -2.1F);
		ring(b, DOOR, 0.0F, 0.2F, SEAT + 0.07F, 0.0F, 0.2F, SEAT + 0.09F, 16, 0.0F, -2.1F);
		ring(b, YELLOW, 0.1F, 0.13F, SEAT + 0.12F, 0.1F, 0.13F, SEAT + 0.14F, 12, 0.0F, -2.1F);
		b.box(-0.015F, SEAT + 0.09F, -2.115F, 0.015F, SEAT + 0.12F, -2.085F, STEEL);
		return b.build();
	}

	private static BoxMesh buildLid() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float y = SEAT + 0.02F;
		// the armoured body: a thick slab with a steel rim band, domed on top
		ring(b, STEEL, 0.0F, LID_R, y, 0.0F, LID_R, y + 0.2F, 40);
		ring(b, OLIVE, 0.0F, LID_R - 0.08F, y + 0.2F, 0.0F, 1.32F, y + 0.36F, 40);
		ring(b, OLIVE, 0.0F, 1.32F, y + 0.36F, 0.0F, 0.78F, y + 0.47F, 32);
		ring(b, OLIVE, 0.0F, 0.78F, y + 0.47F, 0.0F, 0.0F, y + 0.51F, 32);
		ring(b, DOOR, 0.0F, 1.5F, y - 0.06F, 0.0F, 1.5F, y, 32); // the plug that sits in the throat
		// radial stiffening ribs over the dome
		for (int i = 0; i < 8; i++) {
			float a = Mth.TWO_PI * i / 8.0F;
			b.beam(polar(0.55F, a, y + 0.5F), polar(1.6F, a, y + 0.25F), 0.07F, 0.07F, STEEL);
		}
		// lifting lugs and a centre plate
		ring(b, STEEL, 0.0F, 0.32F, y + 0.5F, 0.0F, 0.3F, y + 0.56F, 16);
		for (int i = 0; i < 3; i++) {
			float a = Mth.TWO_PI * i / 3.0F + 0.5F;
			Vector3f p = polar(1.05F, a, y + 0.42F);
			b.box(p.x - 0.07F, p.y, p.z - 0.03F, p.x + 0.07F, p.y + 0.14F, p.z + 0.03F, YELLOW);
		}
		// hinge arms from the dome out to the knuckles on the hinge line
		for (float z : new float[] {-0.95F, 0.95F}) {
			b.beam(v(0.75F, y + 0.46F, z), v(HINGE_X, HINGE_Y, z), 0.14F, 0.2F, OLIVE);
			b.revolve(STEEL, v(HINGE_X, HINGE_Y, z - 0.16F), v(0, 0, 1), new float[][] {{0.0F, 0.13F}, {0.32F, 0.13F}}, 10);
		}
		// lugs for the ram eyes, bridging out from the arms
		for (float side : new float[] {-1.0F, 1.0F}) {
			float z = side * RAM_Z;
			float zArm = side * 0.95F;
			b.box(RAM_EYE.x - 0.1F, RAM_EYE.y - 0.12F, Math.min(zArm, z + side * 0.06F), RAM_EYE.x + 0.1F, RAM_EYE.y + 0.08F, Math.max(zArm, z + side * 0.06F), OLIVE);
			b.revolve(RUST, v(RAM_EYE.x, RAM_EYE.y, z - 0.12F), v(0, 0, 1), new float[][] {{0.0F, 0.05F}, {0.24F, 0.05F}}, 8);
		}
		// the underside seal, seen once it stands open
		ring(b, BLACK, 1.5F, 1.62F, y - 0.01F, 1.5F, 1.62F, y + 0.005F, 40);
		ring(b, GRATING, 0.0F, 1.2F, y - 0.065F, 0.0F, 1.2F, y - 0.06F, 24);
		return b.build();
	}

	/** One locking bolt along +X, engaged (reaching into the lid's rim). */
	private static BoxMesh buildBolt() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.box(1.6F, SEAT + 0.04F, -0.07F, 2.15F, SEAT + 0.13F, 0.07F, STEEL);
		b.box(2.1F, SEAT + 0.13F, -0.05F, 2.16F, SEAT + 0.15F, 0.05F, YELLOW); // position flag
		return b.build();
	}

	/** Ram cylinder along +Y from its trunnion. */
	private static BoxMesh buildCylinder() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(OLIVE, v(0, 0.06F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 0.09F}, {CYL_LENGTH, 0.09F}, {CYL_LENGTH, 0.0F}}, 10);
		b.revolve(STEEL, v(0, CYL_LENGTH - 0.02F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.105F}, {0.08F, 0.105F}}, 10); // gland
		b.box(-0.05F, -0.06F, -0.05F, 0.05F, 0.08F, 0.05F, STEEL); // clevis
		b.beam(v(0.0F, 0.15F, 0.09F), v(0.0F, CYL_LENGTH - 0.05F, 0.09F), 0.025F, 0.025F, BLACK); // hose
		return b.build();
	}

	/** Chromed piston rod along +Y from the lid's eye. */
	private static BoxMesh buildRod() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(DOOR, v(0, 0.05F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 0.04F}, {ROD_LENGTH, 0.04F}, {ROD_LENGTH, 0.0F}}, 8);
		b.box(-0.05F, -0.06F, -0.05F, 0.05F, 0.07F, 0.05F, STEEL); // rod eye
		return b.build();
	}

	private static BoxMesh buildGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] p : BEACONS) {
			b.box(p[0] - 0.12F, SEAT + 0.74F, p[1] - 0.12F, p[0] + 0.12F, SEAT + 0.97F, p[1] + 0.12F, RED_LAMP);
		}
		return b.build();
	}
}
