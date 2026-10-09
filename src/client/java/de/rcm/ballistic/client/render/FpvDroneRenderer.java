package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.AK_BRIGHT;
import static de.rcm.ballistic.client.render.StructureKit.AK_MAG;
import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.BRASS;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.FLASH;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.HAZE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.LENS;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.SUB_TILES;
import static de.rcm.ballistic.client.render.StructureKit.TUNGSTEN;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.FpvDroneEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * The FPV kamikaze drones, built like the real things.
 * <p>
 * Standard: a 7-inch true-X carbon frame (separate tapered arms bolted between a bottom and a top
 * plate on aluminium standoffs), 2807 motors (black base, copper windings showing, silver bell, lock
 * nut), tri-blade props, the ESC and flight-controller stack with its big capacitor, a 6S pack held
 * on with two red straps, the camera tilted up in its side plates, a lollipop video antenna and the
 * receiver's two whips at the back - and under it all a PG-7 shaped-charge warhead in a printed
 * sleeve, zip-tied and taped to the frame, its fuse wires run up to the stack.
 * <p>
 * Racer: a light 5-inch stretched-X with a red printed canopy, hot bright props, the camera tilted
 * steeply up for speed, a 4S pack and a VOG-17 grenade in a printed drop holder under the nose.
 * <p>
 * Drone space: +Z forward (the nose), +Y up, origin in the middle of the frame. When the motors
 * spin, the props blur into translucent discs.
 */
public class FpvDroneRenderer extends EntityRenderer<FpvDroneEntity, FpvDroneRenderer.State> {
	private static final RenderType BLUR_TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/entity/structures.png"));
	/** Drawn bigger than life so it reads at a distance. */
	private static final float SCALE = 1.45F;
	/** Motor positions: (+-ARM, +-ARM). */
	private static final float ARM = 0.16F;
	private static final float RACER_ARM = 0.12F;
	private static final float PROP_RADIUS = 0.135F;
	private static final float RACER_PROP_RADIUS = 0.1F;
	/** Height of the prop plane above the frame. */
	private static final float PROP_Y = 0.046F;
	private static final float RACER_PROP_Y = 0.04F;

	/** Where the fibre leaves the spool's guide (drone space). Declared before the meshes, which use it. */
	public static final Vector3f FIBER_EXIT = new Vector3f(0.0F, 0.035F, -0.175F);
	public static final BoxMesh BODY = body(false);
	public static final BoxMesh FIBER = body(true);
	public static final BoxMesh RACER = racer();
	public static final BoxMesh PROP = prop(PROP_RADIUS, 0.024F, BLACK);
	public static final BoxMesh RACER_PROP = prop(RACER_PROP_RADIUS, 0.02F, FLASH);
	private static final BoxMesh BLUR = blur(PROP_RADIUS);
	private static final BoxMesh RACER_BLUR = blur(RACER_PROP_RADIUS);

	public FpvDroneRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.3F;
	}

	public static class State extends EntityRenderState {
		public float yaw;
		public float pitch;
		public float roll;
		public float time;
		public float throttle;
		public int kind;
		public boolean landed;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(FpvDroneEntity drone, State state, float partialTick) {
		super.extractRenderState(drone, state, partialTick);
		state.yaw = drone.getViewYRot(partialTick);
		state.pitch = drone.getViewXRot(partialTick);
		state.roll = drone.getRoll();
		state.time = drone.tickCount + partialTick;
		state.throttle = drone.getThrottle();
		state.kind = drone.getKind();
		state.landed = drone.isLanded();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.mulPose(Axis.YP.rotationDegrees(-state.yaw));
		if (state.landed) {
			// resting on its warhead (the racer on its grenade holder)
			poseStack.translate(0.0F, (state.kind == FpvDroneEntity.KIND_RACER ? 0.048F : 0.098F) * SCALE, 0.0F);
		} else {
			poseStack.translate(0.0F, 0.12F, 0.0F);
			// it flies nose down to go forward: the camera is tilted up to make up for it
			poseStack.mulPose(Axis.XP.rotationDegrees(Mth.clamp(state.pitch, -60.0F, 60.0F) * 0.6F + 18.0F * state.throttle));
			poseStack.mulPose(Axis.ZP.rotationDegrees(state.roll));
		}
		poseStack.scale(SCALE, SCALE, SCALE);
		submitDrone(poseStack, collector, light, state.kind, state.time, state.landed ? 0.0F : state.throttle);
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	/** The drone in its own space (also used for the one in your hand). */
	public static void submitDrone(PoseStack poseStack, SubmitNodeCollector collector, int light, int kind, float time, float throttle) {
		boolean racer = kind == FpvDroneEntity.KIND_RACER;
		BoxMesh body = racer ? RACER : kind == FpvDroneEntity.KIND_FIBER ? FIBER : BODY;
		BoxMesh prop = racer ? RACER_PROP : PROP;
		BoxMesh blur = racer ? RACER_BLUR : BLUR;
		float arm = racer ? RACER_ARM : ARM;
		float propY = racer ? RACER_PROP_Y : PROP_Y;
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> body.emit(pose, consumer, light));
		boolean spinning = throttle > 0.0F;
		for (int i = 0; i < 4; i++) {
			float sx = (i % 2 == 0 ? -1.0F : 1.0F) * arm;
			float sz = (i < 2 ? -1.0F : 1.0F) * arm;
			// props in: front left and back right turn one way, the other two the other
			float dir = i == 0 || i == 3 ? 1.0F : -1.0F;
			poseStack.pushPose();
			poseStack.translate(sx, propY, sz);
			// spinning, the eye only catches the blades now and then: a slow strobe of them over the blur
			poseStack.mulPose(Axis.YP.rotationDegrees(spinning ? dir * time * 23.0F + i * 40.0F : i * 40.0F + 15.0F));
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> prop.emit(pose, consumer, light));
			if (spinning) {
				int alpha = (int) (60 + 70 * throttle);
				collector.submitCustomGeometry(poseStack, BLUR_TYPE, (pose, consumer) -> blur.emit(pose, consumer, light, alpha << 24 | 0xFFFFFF));
			}
			poseStack.popPose();
		}
	}

	// ------------------------------------------------------------------ building blocks

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** A box rotated about X by {@code pitch} degrees (nose up positive) round its centre. */
	private static void tiltedBox(BoxMesh.Builder b, float cx, float cy, float cz, float hx, float hy, float hz, float pitch, int patch) {
		float c = Mth.cos(-pitch * Mth.DEG_TO_RAD);
		float s = Mth.sin(-pitch * Mth.DEG_TO_RAD);
		float[][] k = {{-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1}, {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}};
		Vector3f[] out = new Vector3f[8];
		for (int i = 0; i < 8; i++) {
			float x = k[i][0] * hx;
			float y = k[i][1] * hy;
			float z = k[i][2] * hz;
			out[i] = v(cx + x, cy + y * c - z * s, cz + y * s + z * c);
		}
		b.hexa(patch, out);
	}

	/** A tapered carbon arm from the centre out to a motor at (mx, mz), {@code root} to {@code tip} wide. */
	private static void arm(BoxMesh.Builder b, float mx, float mz, float from, float root, float tip, float y0, float y1, int patch) {
		float len = Mth.sqrt(mx * mx + mz * mz);
		Vector3f along = v(mx / len, 0.0F, mz / len);
		Vector3f across = v(along.z, 0.0F, -along.x);
		float[][] k = {{-1, 0}, {1, 0}, {1, 0}, {-1, 0}, {-1, 1}, {1, 1}, {1, 1}, {-1, 1}};
		float[] ys = {y0, y0, y1, y1, y0, y0, y1, y1};
		Vector3f[] out = new Vector3f[8];
		for (int i = 0; i < 8; i++) {
			boolean atTip = k[i][1] > 0;
			float r = atTip ? len + 0.01F : from;
			float w = (atTip ? tip : root) * 0.5F * k[i][0];
			out[i] = new Vector3f(along).mul(r).add(new Vector3f(across).mul(w)).add(0.0F, ys[i], 0.0F);
		}
		b.hexa(patch, out);
	}

	/** A 2807-class motor on its arm end: base, copper windings, silver bell, shaft and lock nut. */
	private static void motor(BoxMesh.Builder b, float x, float z, float r, float h, int capPatch) {
		Vector3f up = v(0, 1, 0);
		b.revolve(SUB_TILES, v(x, -0.003F, z), up, new float[][] {{0.0F, 0.0F}, {0.0F, r * 1.15F}, {0.006F, r * 1.15F}, {0.006F, 0.0F}}, 14); // motor mount pad
		b.revolve(BLACK, v(x, 0.003F, z), up, new float[][] {{0.0F, 0.0F}, {0.0F, r}, {h * 0.25F, r}}, 14);
		b.revolve(BRASS, v(x, 0.003F + h * 0.25F, z), up, new float[][] {{0.0F, r * 0.96F}, {h * 0.18F, r * 0.96F}}, 14);
		b.revolve(AK_BRIGHT, v(x, 0.003F + h * 0.43F, z), up, new float[][] {{0.0F, r}, {h * 0.5F, r}, {h * 0.57F, r * 0.82F}}, 14);
		b.revolve(capPatch, v(x, 0.003F + h, z), up, new float[][] {{0.0F, r * 0.82F}, {0.002F, r * 0.55F}, {0.002F, 0.0F}}, 14);
		b.revolve(STEEL, v(x, 0.003F + h, z), up, new float[][] {{0.0F, 0.0028F}, {0.012F, 0.0028F}}, 6);
	}

	/**
	 * A tri-blade prop in its own space (hub at the origin, in the XZ plane): each blade widest at a
	 * third of its length, twisted (steep at the root, flat at the tip) and slightly swept.
	 */
	private static BoxMesh prop(float radius, float chord, int patch) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(BLACK, v(0, -0.004F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 0.009F}, {0.009F, 0.009F}, {0.012F, 0.004F}, {0.012F, 0.0F}}, 10);
		b.revolve(GUNMETAL, v(0, 0.006F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.0055F}, {0.008F, 0.0045F}, {0.008F, 0.0F}}, 6); // lock nut
		float[] rs = {0.007F, radius * 0.35F, radius * 0.8F, radius};
		float[] cs = {chord * 0.6F, chord, chord * 0.75F, chord * 0.35F};
		float[] twist = {0.006F, 0.004F, 0.0018F, 0.0008F};
		float[] sweep = {0.0F, 0.002F, 0.008F, 0.016F};
		float t = 0.0012F;
		for (int blade = 0; blade < 3; blade++) {
			float a = blade * Mth.TWO_PI / 3.0F;
			Matrix3f rot = new Matrix3f().rotateY(a);
			for (int s = 0; s + 1 < rs.length; s++) {
				// box corner order: x = along the blade (root, tip), y = thickness, z = across (trailing, leading)
				Vector3f[] c = new Vector3f[8];
				int i = 0;
				for (int zSide = 0; zSide < 2; zSide++) {
					for (int corner = 0; corner < 4; corner++) {
						boolean tipEnd = corner == 1 || corner == 2;
						boolean top = corner >= 2;
						int k = tipEnd ? s + 1 : s;
						float lead = zSide == 1 ? 1.0F : -1.0F;
						float z = lead * cs[k] * 0.5F - sweep[k];
						float y = lead * twist[k] + (top ? t : -t);
						c[i++] = rot.transform(v(rs[k], y, z));
					}
				}
				b.hexa(patch, c);
			}
		}
		return b.build();
	}

	/** The blur of a spinning prop: a thin disc, drawn translucent. */
	private static BoxMesh blur(float radius) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(HAZE_DARK, v(0, -0.0015F, 0), v(0, 1, 0), new float[][] {{0.0F, radius * 0.12F}, {0.0F, radius}, {0.003F, radius}, {0.003F, radius * 0.12F}}, 24);
		return b.build();
	}

	// ------------------------------------------------------------------ the 7-inch with the PG-7

	/**
	 * @param fiber the fibre-optic version: no radio antennas, instead a spool of glass fibre on the
	 *              back of the frame, the fibre leaving it through a guide at the tail
	 */
	private static BoxMesh body(boolean fiber) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float a = ARM;
		// four arms, 6 mm carbon, tapering from the body out to the motors
		for (int i = 0; i < 4; i++) {
			float mx = (i % 2 == 0 ? -1.0F : 1.0F) * a;
			float mz = (i < 2 ? -1.0F : 1.0F) * a;
			arm(b, mx, mz, 0.025F, 0.038F, 0.027F, -0.003F, 0.003F, SUB_TILES);
			// motor wires running in along the arm, and a zip tie holding them
			Vector3f tip = v(mx, 0.0F, mz).mul(0.86F);
			b.beam(v(mx * 0.2F, 0.0045F, mz * 0.2F), tip.add(0.0F, 0.0045F, 0.0F), 0.006F, 0.0025F, CABLE);
			Vector3f tie = v(mx, 0.0F, mz).mul(0.55F);
			b.box(tie.x - 0.004F, -0.0045F, tie.z - 0.004F, tie.x + 0.004F, 0.0065F, tie.z + 0.004F, WHITE);
			motor(b, mx, mz, 0.02F, 0.038F, GUNMETAL);
		}
		// bottom and top plates, standoffs
		b.box(-0.036F, -0.004F, -0.08F, 0.036F, 0.003F, 0.08F, SUB_TILES);
		b.box(-0.032F, 0.04F, -0.065F, 0.032F, 0.044F, 0.062F, SUB_TILES);
		for (float[] p : new float[][] {{-0.027F, -0.055F}, {0.027F, -0.055F}, {-0.027F, 0.052F}, {0.027F, 0.052F}}) {
			b.revolve(RED, v(p[0], 0.003F, p[1]), v(0, 1, 0), new float[][] {{0.0F, 0.0035F}, {0.037F, 0.0035F}}, 8);
		}
		// the stack: ESC with its heat sink, flight controller, the big capacitor and the XT60 lead
		b.box(-0.016F, 0.005F, -0.017F, 0.016F, 0.009F, 0.017F, AK_MAG);
		b.box(-0.014F, 0.009F, -0.015F, 0.014F, 0.015F, 0.015F, TUNGSTEN);
		for (int f = 0; f < 5; f++) {
			b.box(-0.014F, 0.015F, -0.014F + f * 0.007F, 0.014F, 0.019F, -0.012F + f * 0.007F, TUNGSTEN);
		}
		b.box(-0.016F, 0.026F, -0.017F, 0.016F, 0.029F, 0.017F, AK_MAG);
		b.box(-0.004F, 0.029F, 0.006F, -0.001F, 0.031F, 0.009F, GREEN_LAMP);
		b.revolve(BLACK, v(0.0F, 0.012F, -0.025F), v(0, 0, -1), new float[][] {{0.0F, 0.0F}, {0.0F, 0.007F}, {0.03F, 0.007F}, {0.031F, 0.0F}}, 10);
		b.beam(v(-0.004F, 0.02F, -0.06F), v(-0.004F, 0.055F, -0.05F), 0.004F, 0.004F, RED);
		b.beam(v(0.004F, 0.02F, -0.06F), v(0.004F, 0.055F, -0.05F), 0.004F, 0.004F, BLACK);
		b.box(-0.008F, 0.018F, -0.075F, 0.008F, 0.028F, -0.06F, YELLOW); // XT60
		// battery: 6S pack on the top plate, black wrap, a label round the middle, two red straps
		b.box(-0.021F, 0.044F, -0.052F, 0.021F, 0.084F, 0.042F, CONCRETE_DARK);
		b.box(-0.0215F, 0.05F, -0.015F, 0.0215F, 0.078F, 0.012F, YELLOW);
		for (float z : new float[] {-0.035F, 0.025F}) {
			b.box(-0.023F, 0.038F, z - 0.006F, 0.023F, 0.086F, z + 0.006F, RED);
		}
		b.beam(v(-0.006F, 0.06F, -0.052F), v(-0.006F, 0.03F, -0.07F), 0.004F, 0.004F, RED); // balance lead
		// camera in its side plates at the nose, tilted 25 degrees up
		b.box(-0.02F, -0.002F, 0.058F, -0.017F, 0.042F, 0.09F, SUB_TILES);
		b.box(0.017F, -0.002F, 0.058F, 0.02F, 0.042F, 0.09F, SUB_TILES);
		tiltedBox(b, 0.0F, 0.022F, 0.074F, 0.0165F, 0.014F, 0.012F, 25.0F, BLACK);
		Vector3f lensAxis = v(0.0F, Mth.sin(25.0F * Mth.DEG_TO_RAD), Mth.cos(25.0F * Mth.DEG_TO_RAD));
		b.revolve(GUNMETAL, v(0.0F, 0.022F, 0.074F).add(new Vector3f(lensAxis).mul(0.011F)), lensAxis,
			new float[][] {{0.0F, 0.0085F}, {0.011F, 0.0085F}, {0.012F, 0.006F}}, 10);
		b.revolve(LENS, v(0.0F, 0.022F, 0.074F).add(new Vector3f(lensAxis).mul(0.022F)), lensAxis, new float[][] {{0.0F, 0.006F}, {0.0012F, 0.0F}}, 10);
		if (fiber) {
			spool(b);
		} else {
			// video transmitter's lollipop antenna and the receiver's two whips at the back
			b.beam(v(0.0F, 0.044F, -0.062F), v(0.0F, 0.075F, -0.1F), 0.004F, 0.004F, BLACK);
			Vector3f lolly = v(0.0F, 0.075F, -0.1F);
			b.revolve(RED, lolly, v(0.0F, 0.77F, -0.64F), new float[][] {{-0.005F, 0.0F}, {-0.005F, 0.011F}, {0.004F, 0.011F}, {0.006F, 0.0F}}, 12);
			b.beam(v(-0.008F, 0.004F, -0.078F), v(-0.035F, 0.03F, -0.115F), 0.0025F, 0.0025F, WHITE);
			b.beam(v(0.008F, 0.004F, -0.078F), v(0.035F, 0.03F, -0.115F), 0.0025F, 0.0025F, WHITE);
		}
		b.box(-0.004F, -0.003F, -0.084F, 0.004F, 0.002F, -0.08F, RED_LAMP); // tail LED
		warhead(b);
		return b.build();
	}

	/**
	 * The fibre spool: a drum lying across the back of the frame on a printed bracket, wound full of
	 * pale fibre between two black flanges, and the guide eye at the tail it pays out through.
	 */
	private static void spool(BoxMesh.Builder b) {
		Vector3f c = v(0.0F, 0.05F, -0.125F);
		Vector3f across = v(1, 0, 0);
		b.box(-0.03F, 0.003F, -0.105F, 0.03F, 0.02F, -0.09F, CONCRETE_DARK); // bracket on the bottom plate
		b.box(-0.006F, 0.003F, -0.15F, 0.006F, 0.02F, -0.1F, CONCRETE_DARK);
		b.revolve(WHITE, new Vector3f(c).add(-0.028F, 0.0F, 0.0F), across, new float[][] {{0.0F, 0.03F}, {0.056F, 0.03F}}, 18); // wound fibre
		b.revolve(BLACK, new Vector3f(c).add(-0.032F, 0.0F, 0.0F), across, new float[][] {{0.0F, 0.0F}, {0.0F, 0.04F}, {0.004F, 0.04F}, {0.004F, 0.0F}}, 18);
		b.revolve(BLACK, new Vector3f(c).add(0.028F, 0.0F, 0.0F), across, new float[][] {{0.0F, 0.0F}, {0.0F, 0.04F}, {0.004F, 0.04F}, {0.004F, 0.0F}}, 18);
		b.revolve(GUNMETAL, new Vector3f(c).add(-0.036F, 0.0F, 0.0F), across, new float[][] {{0.0F, 0.006F}, {0.072F, 0.006F}}, 8); // axle
		// the guide arm and eye at the tail
		b.beam(v(0.0F, 0.02F, -0.15F), new Vector3f(FIBER_EXIT), 0.004F, 0.004F, CONCRETE_DARK);
		b.revolve(STEEL, new Vector3f(FIBER_EXIT).add(0.0F, 0.0F, -0.002F), v(0, 0, 1), new float[][] {{0.0F, 0.005F}, {0.004F, 0.005F}}, 8);
		b.box(-0.004F, 0.042F, -0.07F, 0.004F, 0.047F, -0.062F, GREEN_LAMP); // media converter LED
	}

	/**
	 * The PG-7 shaped-charge warhead under the frame, nose forward: the olive case, two bands of black
	 * tape, the ogive and the piezo fuse, a grey printed sleeve over its back end, white zip ties round
	 * it and up to the frame, and the firing leads run up into the stack.
	 */
	private static void warhead(BoxMesh.Builder b) {
		Vector3f o = v(0.0F, -0.05F, -0.12F);
		Vector3f fwd = v(0, 0, 1);
		b.revolve(CONCRETE_DARK, o, fwd, new float[][] {{-0.04F, 0.0F}, {-0.04F, 0.022F}, {0.0F, 0.04F}, {0.03F, 0.046F}}, 14); // printed sleeve
		b.revolve(OLIVE, o, fwd, new float[][] {{0.03F, 0.045F}, {0.16F, 0.045F}, {0.2F, 0.04F}, {0.24F, 0.027F}, {0.27F, 0.016F}}, 14);
		b.revolve(OLIVE_DARK, o, fwd, new float[][] {{0.16F, 0.0455F}, {0.17F, 0.0455F}}, 14);
		b.revolve(BLACK, o, fwd, new float[][] {{0.06F, 0.0462F}, {0.075F, 0.0462F}}, 14);
		b.revolve(BLACK, o, fwd, new float[][] {{0.13F, 0.0462F}, {0.142F, 0.0462F}}, 14);
		b.revolve(STEEL, o, fwd, new float[][] {{0.27F, 0.016F}, {0.29F, 0.013F}, {0.3F, 0.008F}, {0.33F, 0.004F}, {0.335F, 0.0F}}, 10); // piezo fuse
		for (float z : new float[] {0.05F, 0.155F}) {
			Vector3f c = new Vector3f(o).add(0.0F, 0.0F, z);
			b.revolve(WHITE, c, fwd, new float[][] {{0.0F, 0.0472F}, {0.005F, 0.0472F}}, 14);
			b.box(-0.022F, c.y + 0.04F, c.z - 0.0025F, -0.019F, 0.0F, c.z + 0.0025F, WHITE);
			b.box(0.019F, c.y + 0.04F, c.z - 0.0025F, 0.022F, 0.0F, c.z + 0.0025F, WHITE);
		}
		b.beam(v(-0.005F, -0.04F, -0.16F), v(-0.005F, 0.01F, -0.02F), 0.003F, 0.003F, RED);
		b.beam(v(0.005F, -0.04F, -0.16F), v(0.005F, 0.01F, -0.02F), 0.003F, 0.003F, BLACK);
	}

	// ------------------------------------------------------------------ the 5-inch racer with the VOG

	private static BoxMesh racer() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float a = RACER_ARM;
		for (int i = 0; i < 4; i++) {
			// stretched X: the arms reach a little further front and back than out to the side
			float mx = (i % 2 == 0 ? -1.0F : 1.0F) * a;
			float mz = (i < 2 ? -1.0F : 1.0F) * a;
			arm(b, mx, mz, 0.018F, 0.03F, 0.022F, -0.0025F, 0.0025F, SUB_TILES);
			Vector3f tip = v(mx, 0.0F, mz).mul(0.85F);
			b.beam(v(mx * 0.2F, 0.004F, mz * 0.2F), tip.add(0.0F, 0.004F, 0.0F), 0.005F, 0.0022F, CABLE);
			motor(b, mx, mz, 0.016F, 0.032F, RED);
		}
		b.box(-0.025F, -0.003F, -0.065F, 0.025F, 0.0025F, 0.07F, SUB_TILES);
		b.box(-0.012F, 0.004F, -0.013F, 0.012F, 0.016F, 0.013F, AK_MAG); // stack
		// the red printed canopy over the stack, sloping down to the camera
		b.hexa(RED, v(-0.022F, 0.0025F, -0.045F), v(0.022F, 0.0025F, -0.045F), v(0.016F, 0.03F, -0.035F), v(-0.016F, 0.03F, -0.035F),
			v(-0.022F, 0.0025F, 0.05F), v(0.022F, 0.0025F, 0.05F), v(0.016F, 0.022F, 0.042F), v(-0.016F, 0.022F, 0.042F));
		b.box(-0.0175F, 0.008F, 0.044F, -0.0155F, 0.03F, 0.066F, BLACK);
		b.box(0.0155F, 0.008F, 0.044F, 0.0175F, 0.03F, 0.066F, BLACK);
		// camera, tilted 40 degrees up for flying fast
		tiltedBox(b, 0.0F, 0.019F, 0.056F, 0.014F, 0.012F, 0.01F, 40.0F, BLACK);
		Vector3f lensAxis = v(0.0F, Mth.sin(40.0F * Mth.DEG_TO_RAD), Mth.cos(40.0F * Mth.DEG_TO_RAD));
		b.revolve(GUNMETAL, v(0.0F, 0.019F, 0.056F).add(new Vector3f(lensAxis).mul(0.01F)), lensAxis,
			new float[][] {{0.0F, 0.007F}, {0.009F, 0.007F}, {0.01F, 0.005F}}, 10);
		b.revolve(LENS, v(0.0F, 0.019F, 0.056F).add(new Vector3f(lensAxis).mul(0.019F)), lensAxis, new float[][] {{0.0F, 0.005F}, {0.001F, 0.0F}}, 10);
		// 4S pack on top with one strap, the lead down to the canopy
		b.box(-0.016F, 0.03F, -0.05F, 0.016F, 0.055F, 0.02F, YELLOW);
		b.box(-0.0165F, 0.034F, -0.03F, 0.0165F, 0.05F, -0.005F, CONCRETE_DARK);
		b.box(-0.018F, 0.026F, -0.022F, 0.018F, 0.057F, -0.012F, BLACK);
		b.beam(v(0.006F, 0.04F, -0.05F), v(0.006F, 0.02F, -0.06F), 0.0035F, 0.0035F, RED);
		// VTX whip with its red tip, receiver tubes
		b.beam(v(0.0F, 0.02F, -0.05F), v(0.0F, 0.07F, -0.085F), 0.003F, 0.003F, BLACK);
		b.beam(v(0.0F, 0.065F, -0.0815F), v(0.0F, 0.075F, -0.0885F), 0.0045F, 0.0045F, RED);
		b.beam(v(-0.006F, 0.002F, -0.064F), v(-0.028F, 0.022F, -0.095F), 0.002F, 0.002F, WHITE);
		b.beam(v(0.006F, 0.002F, -0.064F), v(0.028F, 0.022F, -0.095F), 0.002F, 0.002F, WHITE);
		// VOG-17 in its printed drop holder under the nose
		b.box(-0.02F, -0.018F, -0.035F, 0.02F, -0.003F, 0.035F, CONCRETE_DARK);
		Vector3f o = v(0.0F, -0.032F, -0.03F);
		Vector3f fwd = v(0, 0, 1);
		b.revolve(OLIVE, o, fwd, new float[][] {{0.0F, 0.0F}, {0.0F, 0.015F}, {0.055F, 0.015F}, {0.06F, 0.013F}}, 12);
		b.revolve(GUNMETAL, o, fwd, new float[][] {{0.06F, 0.012F}, {0.075F, 0.01F}, {0.085F, 0.006F}, {0.088F, 0.0F}}, 10); // fuse
		b.revolve(BRASS, o, fwd, new float[][] {{-0.012F, 0.0F}, {-0.012F, 0.012F}, {0.0F, 0.012F}}, 10); // cartridge case base
		b.revolve(WHITE, new Vector3f(o).add(0.0F, 0.0F, 0.025F), fwd, new float[][] {{0.0F, 0.0162F}, {0.005F, 0.0162F}}, 12);
		b.box(-0.003F, -0.002F, -0.069F, 0.003F, 0.002F, -0.065F, RED_LAMP);
		return b.build();
	}
}
