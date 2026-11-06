package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.gun.GunAttachments;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Vector3f;

/**
 * The weapon light and the laser module as mounted on the guns: a black tube with a knurled bezel and its
 * lens, a small boxy laser with its emitter - each clamped to the rail where it sits on that gun. The lens
 * and the emitter glow while switched on. In first person the renderer also notes where on screen they
 * are, so the beam and the laser start from the gun as drawn.
 */
public final class GunAttachmentModels {
	/** A gun renderer's argument together with what is mounted on the gun. */
	public record Armed<T>(T pose, GunAttachments att) {
	}

	/** Where a gun carries them: light and laser position (the front of the clamp) in the gun's model space. */
	/** Where the light (l) and laser (z) sit, and the muzzle (height, end) a suppressor threads onto, with its size. */
	public record Mount(float lx, float ly, float lz, float zx, float zy, float zz, float muzzleY, float muzzleZ, BoxMesh can, float canLength) {
	}

	public static final Mount AK = new Mount(0.047F, 0.071F, -0.30F, -0.046F, 0.079F, -0.29F, 0.085F, -0.712F, suppressor(0.021F, 0.19F), 0.19F);
	public static final Mount GLOCK = new Mount(0.004F, 0.011F, -0.079F, -0.0135F, 0.017F, -0.076F, 0.058F, -0.156F, suppressor(0.0165F, 0.15F), 0.15F);
	public static final Mount SHOTGUN = new Mount(0.031F, 0.133F, -0.52F, -0.031F, 0.135F, -0.50F, 0.135F, -0.70F, suppressor(0.025F, 0.22F), 0.22F);

	/**
	 * A suppressor along -Z from the muzzle: the threaded collar, the tube (a touch wider), the end cap with
	 * its bore; matt black, a band of bare steel at the collar.
	 */
	private static BoxMesh suppressor(float r, float length) {
		Vector3f origin = new Vector3f(0.0F, 0.0F, 0.0F);
		Vector3f axis = new Vector3f(0.0F, 0.0F, -1.0F);
		return new BoxMesh.Builder()
			.revolve(STEEL, origin, axis, new float[][] {{-0.012F, r * 0.62F}, {0.0F, r * 0.62F}, {0.004F, r * 0.92F}, {0.016F, r * 0.92F}}, 14)
			.revolve(StructureKit.GUNMETAL, origin, axis, new float[][] {{0.016F, r}, {length - 0.008F, r}, {length - 0.002F, r * 0.9F}}, 14)
			.revolve(BLACK, origin, axis, new float[][] {{length - 0.002F, r * 0.9F}, {length, r * 0.35F}, {length, 0.0F}}, 14)
			.build();
	}

	/** The muzzle end (in the gun's space): at the suppressor's front when one is fitted. */
	public static float muzzleEnd(GunAttachments att, Mount m) {
		return att.suppressor() ? m.muzzleZ() - m.canLength() : m.muzzleZ();
	}

	private static final float LIGHT_LEN = 0.075F;
	private static final float LIGHT_R = 0.0145F;
	private static final float LASER_LEN = 0.058F;

	private static final BoxMesh LIGHT = buildLight();
	private static final BoxMesh LIGHT_LENS = buildLightLens();
	private static final BoxMesh LASER = buildLaser();
	private static final BoxMesh LASER_LENS = buildLaserLens();
	private static final BoxMesh SMALL_LIGHT = scaled(LIGHT, 0.62F);
	private static final BoxMesh SMALL_LIGHT_LENS = scaled(LIGHT_LENS, 0.62F);
	private static final BoxMesh SMALL_LASER = scaled(LASER, 0.6F);
	private static final BoxMesh SMALL_LASER_LENS = scaled(LASER_LENS, 0.6F);

	/** First person, camera space: where the light's lens is and which way it shines; the same for the laser. */
	public static final Vector3f VIEW_LIGHT = new Vector3f();
	public static final Vector3f VIEW_LIGHT_DIR = new Vector3f(0.0F, 0.0F, -1.0F);
	public static final Vector3f VIEW_LASER = new Vector3f();
	public static final Vector3f VIEW_LASER_DIR = new Vector3f(0.0F, 0.0F, -1.0F);
	/** When the first-person gun was last drawn (System.nanoTime), so a stale position is not used. */
	public static long viewStamp;

	private GunAttachmentModels() {
	}

	/** Draws what is mounted on a gun (in the gun's model space, already on {@code poseStack}). */
	public static void submit(GunAttachments att, Mount m, boolean small, PoseStack poseStack, SubmitNodeCollector collector, int light, boolean firstPerson) {
		if (att.suppressor()) {
			poseStack.pushPose();
			poseStack.translate(0.0F, m.muzzleY(), m.muzzleZ());
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> m.can().emit(pose, consumer, light));
			poseStack.popPose();
		}
		if (!att.any()) {
			return;
		}
		if (att.light()) {
			BoxMesh body = small ? SMALL_LIGHT : LIGHT;
			BoxMesh lens = small ? SMALL_LIGHT_LENS : LIGHT_LENS;
			float front = (small ? 0.62F : 1.0F) * (LIGHT_LEN + 0.008F);
			poseStack.pushPose();
			poseStack.translate(m.lx(), m.ly(), m.lz());
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> body.emit(pose, consumer, light));
			if (att.lightOn()) {
				collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> lens.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			} else {
				collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> lens.emit(pose, consumer, light, 0xFF8A8A80));
			}
			if (firstPerson) {
				poseStack.last().pose().transformPosition(0.0F, 0.0F, -front, VIEW_LIGHT);
				poseStack.last().normal().transform(VIEW_LIGHT_DIR.set(0.0F, 0.0F, -1.0F)).normalize();
			}
			poseStack.popPose();
		}
		if (att.laser()) {
			BoxMesh body = small ? SMALL_LASER : LASER;
			BoxMesh lens = small ? SMALL_LASER_LENS : LASER_LENS;
			float front = (small ? 0.6F : 1.0F) * (LASER_LEN + 0.004F);
			poseStack.pushPose();
			poseStack.translate(m.zx(), m.zy(), m.zz());
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> body.emit(pose, consumer, light));
			if (att.laserOn() && (!att.ir() || de.rcm.ballistic.client.effect.Vision.seesInfrared())) {
				collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> lens.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			} else {
				collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> lens.emit(pose, consumer, light, 0xFF603030));
			}
			if (firstPerson) {
				poseStack.last().pose().transformPosition(0.0F, 0.0F, -front, VIEW_LASER);
				poseStack.last().normal().transform(VIEW_LASER_DIR.set(0.0F, 0.0F, -1.0F)).normalize();
			}
			poseStack.popPose();
		}
		if (firstPerson) {
			viewStamp = System.nanoTime();
		}
	}

	/** One device, unmounted, at {@code at} in the gun's space, turned {@code tilt} degrees off lying true. */
	public static void submitDevice(boolean light, boolean small, Vector3f at, float tilt, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords) {
		BoxMesh body = light ? (small ? SMALL_LIGHT : LIGHT) : (small ? SMALL_LASER : LASER);
		BoxMesh lens = light ? (small ? SMALL_LIGHT_LENS : LIGHT_LENS) : (small ? SMALL_LASER_LENS : LASER_LENS);
		poseStack.pushPose();
		poseStack.translate(at.x, at.y, at.z);
		poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(tilt));
		poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-tilt * 0.4F));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> body.emit(pose, consumer, lightCoords));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> lens.emit(pose, consumer, lightCoords, light ? 0xFF8A8A80 : 0xFF603030));
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ meshes (front of the device towards -Z)

	private static BoxMesh buildLight() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float r = LIGHT_R;
		// the tube, the tail cap with its pressure switch, the knurled bezel at the head
		b.revolve(BLACK, v(0, 0, 0), v(0, 0, -1), new float[][] {{0.0F, 0.0F}, {0.0F, r * 0.85F}, {0.006F, r}, {LIGHT_LEN - 0.02F, r},
			{LIGHT_LEN - 0.018F, r * 1.12F}, {LIGHT_LEN, r * 1.12F}, {LIGHT_LEN, 0.0F}}, 12);
		b.revolve(STEEL, v(0, 0, -(LIGHT_LEN - 0.016F)), v(0, 0, -1), new float[][] {{0.0F, r * 1.16F}, {0.006F, r * 1.16F}}, 12);
		b.box(-0.004F, -0.004F, 0.0F, 0.004F, 0.004F, 0.006F, STEEL); // tail switch
		// the rail clamp, reaching in towards the gun
		b.box(-r * 1.25F, -0.004F, -0.05F, 0.0F, 0.004F, -0.02F, BLACK);
		return b.build();
	}

	private static BoxMesh buildLightLens() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(LAMP, v(0, 0, -LIGHT_LEN), v(0, 0, -1), new float[][] {{0.0F, LIGHT_R * 0.95F}, {0.003F, LIGHT_R * 0.95F}, {0.003F, 0.0F}}, 12);
		return b.build();
	}

	private static BoxMesh buildLaser() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// a squat box: the module, its adjustment turrets on top and side, the clamp
		b.box(-0.011F, -0.012F, -LASER_LEN, 0.011F, 0.012F, 0.0F, BLACK);
		b.box(-0.004F, 0.012F, -0.036F, 0.004F, 0.017F, -0.026F, STEEL);
		b.box(0.011F, -0.004F, -0.036F, 0.015F, 0.004F, -0.026F, STEEL);
		b.box(0.011F, -0.004F, -0.045F, 0.016F, 0.004F, -0.012F, BLACK);
		return b.build();
	}

	private static BoxMesh buildLaserLens() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(RED_LAMP, v(0, 0.003F, -LASER_LEN), v(0, 0, -1), new float[][] {{0.0F, 0.0045F}, {0.004F, 0.0045F}, {0.004F, 0.0F}}, 8);
		return b.build();
	}

	private static BoxMesh scaled(BoxMesh mesh, float k) {
		return mesh.scaled(k);
	}
}
