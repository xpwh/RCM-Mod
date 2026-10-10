package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.DISH;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.GRATING;
import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.GUNMETAL;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.LAMP;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.SHELTER;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
import static de.rcm.ballistic.client.render.StructureKit.WHITE;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.OrbitalUplinkBlock;
import de.rcm.ballistic.block.OrbitalUplinkBlockEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Satellite earth station of the orbital strike system, modelled on a real Cassegrain uplink:
 * <ul>
 *   <li>a fenced concrete pad with floodlights, a diesel generator and its fuel tank;</li>
 *   <li>the white equipment shelter with door and steps, air conditioners, a small telemetry dish and
 *       GPS antennas on the roof, and an aviation warning mast;</li>
 *   <li>the 4 m main antenna on a steel kingpost: azimuth bearing and cable wrap, the alidade with
 *       its yoke, an elevation jackscrew that extends as the dish tips up, a deep paraboloid with panel
 *       seams, ring truss and counterweight, and the feed cone in the hub looking up at a
 *       sub-reflector held on four struts.</li>
 * </ul>
 * The dish tracks the weapons platform: it creeps across the sky when idle and slews round onto
 * the target bearing to uplink a strike while the beacon on the shelter flashes red.
 */
public class OrbitalUplinkRenderer implements BlockEntityRenderer<OrbitalUplinkBlockEntity, OrbitalUplinkRenderer.State> {
	/** Height of the elevation axis above the block's bottom. */
	private static final float PIVOT_Y = 3.05F;
	private static final float DISH_R = 2.05F;
	/** Focal length of the main reflector: a deep dish (f/D 0.2). */
	private static final float FOCUS = 0.85F;
	/** Offset of the reflector's vertex in front of the elevation axis. */
	private static final float VERTEX_Z = 0.3F;
	/** Jackscrew anchor on the alidade and its attachment lug on the dish's back structure. */
	private static final Vector3f ACTUATOR_BASE = new Vector3f(0.0F, 2.6F, -0.76F);
	private static final Vector3f ACTUATOR_LUG = new Vector3f(0.0F, 0.62F, -0.5F);

	static final BoxMesh SITE = buildSite();
	static final BoxMesh TURNTABLE = buildTurntable();
	static final BoxMesh DISH_MESH = buildDish();
	private static final BoxMesh BEACON = new BoxMesh.Builder().box(2.0F, 2.42F, 0.9F, 2.2F, 2.62F, 1.1F, RED_LAMP).build();
	private static final BoxMesh AVIATION = new BoxMesh.Builder().box(2.66F, 5.6F, -1.34F, 2.84F, 5.78F, -1.16F, RED_LAMP).build();
	private static final BoxMesh READY = new BoxMesh.Builder().box(2.92F, 2.05F, -0.62F, 2.94F, 2.15F, -0.52F, GREEN_LAMP).build();

	public OrbitalUplinkRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float facing;
		public float azimuth;
		public float elevation;
		public int phase;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(OrbitalUplinkBlockEntity uplink, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(uplink, state, partialTick, cameraPos, overlay);
		Direction facing = state.blockState.hasProperty(OrbitalUplinkBlock.FACING) ? state.blockState.getValue(OrbitalUplinkBlock.FACING) : Direction.NORTH;
		state.facing = (float) Mth.atan2(facing.getStepX(), facing.getStepZ());
		state.azimuth = Mth.lerp(partialTick, uplink.azimuthO, uplink.azimuth);
		state.elevation = Mth.lerp(partialTick, uplink.elevationO, uplink.elevation);
		state.phase = uplink.phase();
		state.time = uplink.clientAge + partialTick;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.pushPose();
		poseStack.mulPose(new Quaternionf().rotationY(state.facing));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> SITE.emit(pose, consumer, light));
		boolean busy = state.phase != OrbitalUplinkBlockEntity.IDLE;
		if (busy && Mth.sin(state.time * 0.9F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BEACON.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		if (!busy) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> READY.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		if ((state.time % 40.0F) < 20.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> AVIATION.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		poseStack.popPose();
		// azimuth: the whole head turns on the kingpost (world azimuth, 0 = +Z)
		poseStack.mulPose(new Quaternionf().rotationY(state.azimuth));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> TURNTABLE.emit(pose, consumer, light));
		// the elevation jackscrew, from its anchor on the alidade to the lug on the tilted dish
		Vector3f lug = new Vector3f(ACTUATOR_LUG).rotateX(-state.elevation).add(0.0F, PIVOT_Y, 0.0F);
		Vector3f base = new Vector3f(ACTUATOR_BASE);
		Vector3f mid = new Vector3f(base).lerp(lug, 0.55F);
		BoxMesh jack = new BoxMesh.Builder()
			.beam(base, mid, 0.17F, 0.17F, WHITE)
			.beam(new Vector3f(base).lerp(lug, 0.45F), lug, 0.08F, 0.08F, STEEL)
			.build();
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> jack.emit(pose, consumer, light));
		// elevation: the dish tips up about the yoke's axis
		poseStack.translate(0.0F, PIVOT_Y, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationX(-state.elevation));
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> DISH_MESH.emit(pose, consumer, light));
		poseStack.popPose();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 256;
	}

	private static Vector3f v(float x, float y, float z) {
		return new Vector3f(x, y, z);
	}

	/** The fixed parts of the site; the shelter stands to the +X side with its door facing +X. */
	private static BoxMesh buildSite() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float top = 1.02F;
		// ---- pad, with expansion joints, and the antenna's octagonal foundation
		b.box(-2.3F, 0.0F, -2.3F, 3.3F, top, 2.3F, CONCRETE);
		b.box(-2.3F, top, -0.02F, 3.3F, top + 0.005F, 0.02F, CONCRETE_DARK);
		b.box(0.98F, top, -2.3F, 1.02F, top + 0.005F, 2.3F, CONCRETE_DARK);
		b.revolve(CONCRETE, v(0, top, 0), v(0, 1, 0), new float[][] {{0.0F, 0.95F}, {0.32F, 0.9F}}, 8);
		b.box(-0.95F, top + 0.32F, -0.95F, 0.95F, top + 0.34F, 0.95F, HAZARD);
		// ---- steel kingpost with its base flange, bolts and access hatch
		float post = top + 0.34F;
		b.revolve(STEEL, v(0, post, 0), v(0, 1, 0), new float[][] {{0.0F, 0.6F}, {0.08F, 0.6F}, {0.1F, 0.44F}, {0.75F, 0.42F}}, 16);
		for (int i = 0; i < 12; i++) {
			float a = i * Mth.TWO_PI / 12;
			b.box(Mth.cos(a) * 0.53F - 0.025F, post + 0.08F, Mth.sin(a) * 0.53F - 0.025F, Mth.cos(a) * 0.53F + 0.025F, post + 0.13F, Mth.sin(a) * 0.53F + 0.025F, BLACK);
		}
		b.box(0.38F, post + 0.2F, -0.15F, 0.44F, post + 0.6F, 0.15F, DOOR);
		// ---- equipment shelter: corrugated walls, roof cap, door and steps, AC units, cable entry
		float sx0 = 1.25F;
		float sx1 = 2.9F;
		b.box(sx0, top, -1.45F, sx1, 2.32F, 1.45F, SHELTER);
		b.box(sx0 - 0.03F, 2.32F, -1.48F, sx1 + 0.03F, 2.4F, 1.48F, STEEL);
		b.box(sx1, top + 0.05F, -0.45F, sx1 + 0.02F, 2.1F, 0.45F, STEEL); // door frame
		b.box(sx1 + 0.01F, top + 0.08F, -0.4F, sx1 + 0.03F, 2.05F, 0.4F, DOOR);
		b.box(sx1 + 0.02F, top + 1.0F, 0.28F, sx1 + 0.06F, top + 1.06F, 0.35F, BLACK); // handle
		b.box(sx1 + 0.03F, top, -0.55F, sx1 + 0.38F, top + 0.12F, 0.55F, CONCRETE_DARK); // step
		for (float z : new float[] {-1.25F, -0.75F}) {
			b.box(sx0 + 0.2F, top + 0.5F, z - 0.2F, sx0 + 0.75F, top + 1.05F, z + 0.2F, VENT); // AC units on the -X wall side
		}
		b.box(sx0 - 0.3F, top + 0.55F, -1.45F, sx0, top + 1.1F, -0.55F, VENT);
		b.box(sx0 - 0.3F, top + 0.4F, 0.6F, sx0 + 0.02F, top + 0.75F, 1.0F, GUNMETAL); // cable entry panel
		// roof: small telemetry dish, GPS pucks, the aviation warning mast
		b.revolve(DISH, v(2.3F, 2.55F, 0.2F), v(0.0F, 0.75F, 0.66F), new float[][] {{0.0F, 0.02F}, {0.06F, 0.22F}, {0.12F, 0.34F}}, 14);
		b.beam(v(2.3F, 2.4F, 0.2F), v(2.3F, 2.55F, 0.2F), 0.06F, 0.06F, STEEL);
		b.beam(v(2.3F, 2.6F, 0.25F), v(2.3F, 2.86F, 0.45F), 0.02F, 0.02F, STEEL);
		for (float z : new float[] {-0.4F, -0.2F}) {
			b.revolve(WHITE, v(1.6F, 2.4F, z), v(0, 1, 0), new float[][] {{0.0F, 0.07F}, {0.05F, 0.05F}, {0.07F, 0.0F}}, 8);
		}
		for (float x : new float[] {2.62F, 2.88F}) {
			for (float z : new float[] {-1.38F, -1.12F}) {
				b.beam(v(x, 2.4F, z), v(2.75F, 5.6F, -1.25F), 0.03F, 0.03F, x < 2.7F ? RED : WHITE);
			}
		}
		for (float y = 2.9F; y < 5.5F; y += 0.6F) {
			float w = 0.13F * (5.6F - y) / 3.2F + 0.02F;
			b.box(2.75F - w, y, -1.25F - w, 2.75F + w, y + 0.03F, -1.25F + w, y % 1.2F < 0.6F ? RED : WHITE);
		}
		// ---- cable trench from the shelter to the kingpost
		b.box(0.6F, top, -0.12F, sx0, top + 0.04F, 0.12F, GRATING);
		// ---- diesel generator and its fuel tank in a corner
		b.box(-2.05F, top, 1.15F, -0.95F, top + 0.75F, 2.05F, OLIVE_DARK);
		b.box(-2.0F, top + 0.75F, 1.2F, -1.0F, top + 0.78F, 2.0F, VENT);
		b.beam(v(-1.15F, top + 0.75F, 1.3F), v(-1.15F, top + 1.35F, 1.3F), 0.07F, 0.07F, BLACK);
		b.revolve(WHITE, v(-2.1F, top + 0.3F, -1.6F), v(1, 0, 0), new float[][] {{0.0F, 0.0F}, {0.0F, 0.32F}, {1.0F, 0.32F}, {1.0F, 0.0F}}, 12);
		b.box(-1.95F, top, -1.75F, -1.9F, top + 0.3F, -1.45F, STEEL);
		b.box(-1.25F, top, -1.75F, -1.2F, top + 0.3F, -1.45F, STEEL);
		// ---- floodlight poles
		for (float z : new float[] {-2.15F, 2.15F}) {
			b.beam(v(-2.15F, top, z), v(-2.15F, top + 2.8F, z), 0.07F, 0.07F, GUNMETAL);
			b.box(-2.05F, top + 2.65F, z - 0.15F, -1.9F, top + 2.85F, z + 0.15F, LAMP);
		}
		// ---- chain-link fence around the pad, with a gate gap in front of the shelter door
		float fx0 = -2.25F;
		float fx1 = 3.25F;
		float fz = 2.25F;
		float h = 1.45F;
		for (float x = fx0; x <= fx1 + 0.01F; x += 0.55F) {
			for (float z : new float[] {-fz, fz}) {
				b.box(x - 0.025F, top, z - 0.025F, x + 0.025F, top + h, z + 0.025F, STEEL);
			}
		}
		for (float z = -fz; z <= fz + 0.01F; z += 0.75F) {
			b.box(fx0 - 0.025F, top, z - 0.025F, fx0 + 0.025F, top + h, z + 0.025F, STEEL);
			if (Math.abs(z) > 0.8F) {
				b.box(fx1 - 0.025F, top, z - 0.025F, fx1 + 0.025F, top + h, z + 0.025F, STEEL);
			}
		}
		// wire strands between the posts and a run of barbed wire on top (open, so the site shows through)
		for (float y : new float[] {0.15F, 0.5F, 0.85F, 1.2F, h}) {
			float t = y == h ? 0.025F : 0.012F;
			int tex = y == h ? STEEL : GUNMETAL;
			for (float z : new float[] {-fz, fz}) {
				b.box(fx0, top + y - t, z - t, fx1, top + y + t, z + t, tex);
			}
			b.box(fx0 - t, top + y - t, -fz, fx0 + t, top + y + t, fz, tex);
			for (float[] seg : new float[][] {{-fz, -0.8F}, {0.8F, fz}}) {
				b.box(fx1 - t, top + y - t, seg[0], fx1 + t, top + y + t, seg[1], tex);
			}
		}
		for (float z : new float[] {-fz, fz}) {
			b.box(fx0, top + h + 0.12F, z - 0.015F, fx1, top + h + 0.15F, z + 0.015F, GUNMETAL); // barbed strand
			for (float x = fx0; x <= fx1 + 0.01F; x += 0.55F) {
				b.beam(v(x, top + h, z), v(x, top + h + 0.18F, z + Math.signum(z) * 0.12F), 0.025F, 0.025F, STEEL); // outrigger
			}
		}
		return b.build();
	}

	/** Azimuth head: bearing housing, cable wrap, the alidade platform and the yoke arms. */
	private static BoxMesh buildTurntable() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.revolve(WHITE, v(0, 2.11F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.62F}, {0.22F, 0.62F}, {0.26F, 0.5F}}, 20);
		b.revolve(BLACK, v(0, 2.13F, 0), v(0, 1, 0), new float[][] {{0.0F, 0.64F}, {0.04F, 0.64F}}, 20); // bearing seal
		// alidade platform with a railing
		b.box(-0.75F, 2.37F, -0.85F, 0.75F, 2.47F, 0.65F, WHITE);
		b.box(-0.75F, 2.47F, -0.85F, 0.75F, 2.49F, 0.65F, GRATING);
		for (float x : new float[] {-0.72F, 0.72F}) {
			b.box(x - 0.02F, 2.49F, -0.82F, x + 0.02F, 2.95F, -0.78F, STEEL);
			b.box(x - 0.02F, 2.93F, -0.82F, x + 0.02F, 2.95F, -0.3F, STEEL);
		}
		// the yoke: two tapering arms carrying the elevation bearings
		for (float x : new float[] {-0.62F, 0.5F}) {
			b.hexa(WHITE,
				v(x, 2.47F, -0.42F), v(x + 0.12F, 2.47F, -0.42F), v(x + 0.12F, PIVOT_Y + 0.2F, -0.16F), v(x, PIVOT_Y + 0.2F, -0.16F),
				v(x, 2.47F, 0.42F), v(x + 0.12F, 2.47F, 0.42F), v(x + 0.12F, PIVOT_Y + 0.2F, 0.16F), v(x, PIVOT_Y + 0.2F, 0.16F));
		}
		// elevation drive gearbox and motor
		b.box(-0.82F, PIVOT_Y - 0.2F, -0.18F, -0.62F, PIVOT_Y + 0.18F, 0.18F, GUNMETAL);
		b.revolve(BLACK, v(-0.82F, PIVOT_Y - 0.05F, 0.0F), v(-1, 0, 0), new float[][] {{0.0F, 0.1F}, {0.25F, 0.1F}, {0.25F, 0.0F}}, 10);
		// jackscrew anchor bracket
		b.box(-0.1F, 2.49F, -0.85F, 0.1F, 2.7F, -0.65F, STEEL);
		return b.build();
	}

	/** The dish looking along +Z at elevation 0; elevation axis at the origin. */
	private static BoxMesh buildDish() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// elevation axle, hub box and the jackscrew lug
		b.beam(v(-0.56F, 0.0F, 0.0F), v(0.56F, 0.0F, 0.0F), 0.14F, 0.14F, STEEL);
		b.box(-0.42F, -0.42F, -0.3F, 0.42F, 0.42F, VERTEX_Z, WHITE);
		b.box(-0.07F, 0.35F, ACTUATOR_LUG.z() - 0.08F, 0.07F, ACTUATOR_LUG.y() + 0.07F, -0.28F, STEEL);
		// main reflector: a paraboloid, depth = r^2 / (4 f), white panels with seams between the rings
		int rings = 10;
		float[][] profile = new float[rings + 1][2];
		for (int i = 0; i <= rings; i++) {
			float r = Math.max(0.001F, DISH_R * i / rings);
			profile[i][0] = r * r / (4.0F * FOCUS);
			profile[i][1] = r;
		}
		Vector3f vertex = v(0, 0, VERTEX_Z);
		Vector3f axis = v(0, 0, 1);
		b.revolve(DISH, vertex, axis, profile, 32);
		for (float r : new float[] {0.75F, 1.4F}) {
			float d = r * r / (4.0F * FOCUS);
			b.revolve(BLACK, vertex, axis, new float[][] {{d - 0.004F, r + 0.004F}, {d + 0.012F, r + 0.004F}}, 32);
		}
		float depth = DISH_R * DISH_R / (4.0F * FOCUS);
		b.revolve(STEEL, vertex, axis, new float[][] {{depth - 0.04F, DISH_R + 0.05F}, {depth + 0.02F, DISH_R + 0.05F}}, 32); // rim
		// back structure: radial ribs and a ring truss, the counterweight below
		for (int i = 0; i < 8; i++) {
			float a = i * Mth.TWO_PI / 8 + 0.2F;
			float x = Mth.cos(a);
			float y = Mth.sin(a);
			float rr = DISH_R * 0.92F;
			b.beam(v(x * 0.38F, y * 0.38F, VERTEX_Z - 0.05F), v(x * rr, y * rr, VERTEX_Z + rr * rr / (4.0F * FOCUS) - 0.08F), 0.05F, 0.07F, STEEL);
		}
		float ringR = DISH_R * 0.62F;
		float ringZ = VERTEX_Z + ringR * ringR / (4.0F * FOCUS) - 0.12F;
		b.revolve(STEEL, v(0, 0, ringZ - 0.02F), axis, new float[][] {{0.0F, ringR}, {0.05F, ringR}}, 16);
		b.beam(v(0, -0.35F, -0.25F), v(0, -1.05F, -0.55F), 0.1F, 0.1F, STEEL);
		b.box(-0.3F, -1.35F, -0.8F, 0.3F, -0.95F, -0.4F, GUNMETAL); // counterweight
		// Cassegrain optics: feed cone in the hub, sub-reflector at the focus on four struts
		b.revolve(WHITE, v(0, 0, VERTEX_Z), axis, new float[][] {{0.0F, 0.28F}, {0.55F, 0.13F}, {0.6F, 0.13F}}, 16);
		b.revolve(BLACK, v(0, 0, VERTEX_Z + 0.6F), axis, new float[][] {{0.0F, 0.12F}, {0.01F, 0.0F}}, 12);
		float subZ = VERTEX_Z + FOCUS * 1.1F;
		b.revolve(WHITE, v(0, 0, subZ), axis, new float[][] {{-0.08F, 0.0F}, {-0.06F, 0.2F}, {0.0F, 0.34F}, {0.04F, 0.34F}, {0.06F, 0.0F}}, 16);
		for (int i = 0; i < 4; i++) {
			float a = i * Mth.HALF_PI + Mth.PI / 4;
			float r = DISH_R * 0.7F;
			Vector3f foot = v(Mth.cos(a) * r, Mth.sin(a) * r, VERTEX_Z + r * r / (4.0F * FOCUS));
			Vector3f head = v(Mth.cos(a) * 0.3F, Mth.sin(a) * 0.3F, subZ + 0.02F);
			b.beam(foot, head, 0.04F, 0.04F, WHITE);
		}
		// waveguide run from the hub down the back to the alidade
		b.beam(v(0.3F, -0.42F, -0.2F), v(0.3F, -0.42F, 0.25F), 0.05F, 0.05F, CABLE);
		return b.build();
	}
}
