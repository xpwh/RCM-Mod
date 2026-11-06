package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;

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
 * Satellite earth station of the orbital strike system, modelled in Blender (tools/structures/uplink.py) on a real
 * Cassegrain uplink:
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

	/** The station as built in Blender (tools/structures/uplink.py): site, head, dish, the jackscrew's barrel and rod. */
	static final StructureModel MODEL = StructureModel.load("uplink");
	static final BoxMesh SITE = MODEL.part("site");
	static final BoxMesh TURNTABLE = MODEL.part("head");
	static final BoxMesh DISH_MESH = MODEL.part("dish");
	private static final BoxMesh JACK = MODEL.part("jack");
	private static final BoxMesh ROD = MODEL.part("rod");
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
		ModelPick.part(state, poseStack, camera, SITE);
		MODEL.submit("site", poseStack, collector, light);
		boolean busy = state.phase != OrbitalUplinkBlockEntity.IDLE;
		if (busy && Mth.sin(state.time * 0.9F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BEACON.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			StructureKit.light(state, camera, poseStack, 0, BEACON, 0xFF2010, 0.7F, 10.0F);
		}
		if (!busy) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> READY.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			StructureKit.light(state, camera, poseStack, 1, READY, 0x40FF60, 0.25F, 4.0F);
		}
		if ((state.time % 40.0F) < 20.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> AVIATION.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			StructureKit.light(state, camera, poseStack, 2, AVIATION, 0xFF2A18, 0.45F, 8.0F);
		}
		poseStack.popPose();
		// azimuth: the whole head turns on the kingpost (world azimuth, 0 = +Z)
		poseStack.mulPose(new Quaternionf().rotationY(state.azimuth));
		ModelPick.part(state, poseStack, camera, TURNTABLE);
		MODEL.submit("head", poseStack, collector, light);
		// the elevation jackscrew, from its anchor on the alidade to the lug on the tilted dish: the barrel from the
		// anchor, the screw running out of it to the lug (both modelled a unit long, stretched to fit)
		Vector3f lug = new Vector3f(ACTUATOR_LUG).rotateX(-state.elevation).add(0.0F, PIVOT_Y, 0.0F);
		Vector3f base = new Vector3f(ACTUATOR_BASE);
		Vector3f dir = new Vector3f(lug).sub(base);
		float len = dir.length();
		Quaternionf along = new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, dir.x, dir.y, dir.z);
		poseStack.pushPose();
		poseStack.translate(base.x, base.y, base.z);
		poseStack.mulPose(along);
		poseStack.pushPose();
		poseStack.scale(1.0F, len * 0.55F, 1.0F);
		ModelPick.part(state, poseStack, camera, JACK);
		MODEL.submit("jack", poseStack, collector, light);
		poseStack.popPose();
		poseStack.translate(0.0F, len * 0.45F, 0.0F);
		poseStack.scale(1.0F, len * 0.55F, 1.0F);
		MODEL.submit("rod", poseStack, collector, light);
		poseStack.popPose();
		// elevation: the dish tips up about the yoke's axis
		poseStack.translate(0.0F, PIVOT_Y, 0.0F);
		poseStack.mulPose(new Quaternionf().rotationX(-state.elevation));
		ModelPick.part(state, poseStack, camera, DISH_MESH);
		MODEL.submit("dish", poseStack, collector, light);
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
}
