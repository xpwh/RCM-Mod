package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.HatchSiloBlock;
import de.rcm.ballistic.block.MissileSiloBlock;
import de.rcm.ballistic.block.MissileSiloBlockEntity;
import de.rcm.ballistic.block.SubmarineBlock;
import de.rcm.ballistic.entity.MissileType;
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
import org.jspecify.annotations.Nullable;

/**
 * Minuteman-style silo headworks on top of the hatch block, modelled in Blender (tools/structures/silo.py) on a
 * Minuteman III launch facility: a hardened concrete apron round the tube's mouth with a gravel slope down to the
 * ground, the launcher closure split in two armoured halves that roll aside on crane rails into their buffer stops,
 * a ballistic-protected vent, the personnel access hatch, the electrical cabinet, a guyed UHF antenna, intrusion
 * sensors, warning beacons that flash during the countdown and a security fence with outriggers and a gate.
 * <p>
 * The tube ({@link MissileSiloBlock#digShaft dug into the ground} when the silo is placed) is fitted out like a
 * Minuteman launch tube (tools/structures/silo.py --tube): a round steel liner with stiffening rings, cable trays,
 * a ladder, folded-back work platforms, lamps every few metres, an umbilical to the missile and the
 * shock-isolated launch platform it stands on.
 * <p>
 * The {@link HatchSiloBlock ground-hatch variant} has no headworks: a round armoured lid in a concrete collar
 * (see {@link RoundHatchModel}); the {@link SubmarineBlock submarine} draws its boat round the block
 * ({@link SubmarineModel}).
 */
public class SiloRenderer implements BlockEntityRenderer<MissileSiloBlockEntity, SiloRenderer.State> {
	/** Top of the hatch block = ground level. */
	private static final float GROUND = 1.0F;
	/** Top of the apron. */
	private static final float DECK = GROUND + 0.45F;
	private static final float DOOR_TRAVEL = 1.9F;
	private static final float[][] BEACONS = {{-3.2F, -3.2F}, {3.2F, 3.2F}};

	/** The headworks as built in Blender: the fixed site and the two halves of the launcher closure, closed. */
	static final StructureModel MODEL = StructureModel.load("silo");
	private static final BoxMesh SITE = MODEL.part("site");
	private static final String[] DOOR_PARTS = {"door_l", "door_r"};
	private static final BoxMesh[] DOORS = {MODEL.part("door_l"), MODEL.part("door_r")};
	/** The launch tube below the block, shared by both silo variants. */
	static final StructureModel TUBE_MODEL = StructureModel.load("silo_tube");
	private static final BoxMesh TUBE = TUBE_MODEL.part("tube");
	private static final BoxMesh BEACON_GLOW = buildBeaconGlow();
	/** Floor of the tube in block space (the hatch block's bottom is 0). */
	private static final float FLOOR = -MissileSiloBlock.SHAFT_DEPTH;
	private static final float PLATFORM = FLOOR + 0.3F;
	private static final BoxMesh TUBE_LAMPS = buildTubeLamps();

	public SiloRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float open;
		public boolean counting;
		public @Nullable MissileType missile;
		public float time;
		public boolean submarine;
		public float facingYaw;
		public boolean hatch;
		/** Raw (linear) door travel, for the round hatch's phased opening. */
		public float travel;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(MissileSiloBlockEntity silo, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(silo, state, partialTick, cameraPos, overlay);
		float t = Mth.lerp(partialTick, silo.doorOpenO, silo.doorOpen);
		state.open = t * t * (3.0F - 2.0F * t); // heavy doors: slow start, slow stop
		state.counting = silo.isCounting();
		state.missile = silo.getMissile();
		state.time = silo.getLevel() == null ? 0.0F : silo.getLevel().getGameTime() + partialTick;
		state.submarine = state.blockState.getBlock() instanceof SubmarineBlock;
		state.hatch = state.blockState.getBlock() instanceof HatchSiloBlock;
		state.travel = t;
		if (state.submarine) {
			Direction facing = state.blockState.getValue(SubmarineBlock.FACING);
			state.facingYaw = (float) Mth.atan2(facing.getStepX(), facing.getStepZ());
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		if (state.submarine) {
			this.submitSubmarine(state, poseStack, collector, light, camera);
			poseStack.popPose();
			return;
		}
		if (state.hatch) {
			ModelPick.part(state, poseStack, camera, RoundHatchModel.COLLAR);
			RoundHatchModel.MODEL.submit("collar", poseStack, collector, light);
		} else {
			ModelPick.part(state, poseStack, camera, SITE);
			MODEL.submit("site", poseStack, collector, light);
		}

		// the launch tube, lit by its own lamps
		int tubeLight = LightTexture.pack(Math.max(LightTexture.block(light), 11), LightTexture.sky(light));
		ModelPick.part(state, poseStack, camera, TUBE);
		TUBE_MODEL.submit("tube", poseStack, collector, tubeLight);
		collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> TUBE_LAMPS.emit(pose, consumer, LightTexture.FULL_BRIGHT));

		// the missile standing on the launch platform at the bottom of the tube
		MissileType missile = state.missile;
		if (missile != null) {
			MissileMesh mesh = MissileMesh.of(missile);
			float s = missile.scale;
			poseStack.pushPose();
			poseStack.translate(0.0F, PLATFORM, 0.0F);
			poseStack.scale(s, s, s);
			collector.submitCustomGeometry(poseStack, MissileRenderer.bodyType(missile), (pose, consumer) -> mesh.emit(pose, consumer, tubeLight));
			poseStack.popPose();
		}

		if (state.hatch) {
			RoundHatchModel.submit(state, poseStack, collector, light, camera);
			poseStack.popPose();
			return;
		}

		// the two door halves roll outwards along their rails
		for (int i = 0; i < 2; i++) {
			poseStack.pushPose();
			poseStack.translate((i == 0 ? -1.0F : 1.0F) * state.open * DOOR_TRAVEL, 0.0F, 0.0F);
			ModelPick.part(state, poseStack, camera, DOORS[i]);
			MODEL.submit(DOOR_PARTS[i], poseStack, collector, light);
			poseStack.popPose();
		}

		if (state.counting && Mth.sin(state.time * 0.9F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BEACON_GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			// the beacons really light the headworks red
			for (int i = 0; i < BEACONS.length; i++) {
				de.rcm.ballistic.client.effect.DynamicLights.blockLamp(state.blockPos, i, BEACONS[i][0], DECK + 1.32F, BEACONS[i][1], 0xFF2414, 0.9F, 12.0F);
			}
		}
		poseStack.popPose();
	}

	/** Submarine variant: the hull around the block, the live hatch swinging open, the missile in its tube. */
	private void submitSubmarine(State state, PoseStack poseStack, SubmitNodeCollector collector, int light, CameraRenderState camera) {
		poseStack.mulPose(new Quaternionf().rotationY(state.facingYaw));
		ModelPick.part(state, poseStack, camera, SubmarineModel.HULL);
		SubmarineModel.MODEL.submit("hull", poseStack, collector, light);
		if (Mth.sin(state.time * 0.15F) > 0.6F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> SubmarineModel.MAST_LIGHT.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		MissileType missile = state.missile;
		if (missile != null) {
			MissileMesh mesh = MissileMesh.of(missile);
			float s = missile.scale;
			poseStack.pushPose();
			poseStack.translate(0.0F, SubmarineModel.DECK_Y - 0.2F - mesh.length() * s, 0.0F);
			poseStack.scale(s, s, s);
			collector.submitCustomGeometry(poseStack, MissileRenderer.bodyType(missile), (pose, consumer) -> mesh.emit(pose, consumer, light));
			poseStack.popPose();
		}
		// hatch hinged at its aft edge, swinging up to vertical
		poseStack.translate(0.0F, SubmarineModel.DECK_Y, SubmarineModel.HATCH_HINGE_Z);
		poseStack.mulPose(new Quaternionf().rotationX(-state.open * 1.75F));
		ModelPick.part(state, poseStack, camera, SubmarineModel.HATCH);
		SubmarineModel.MODEL.submit("hatch", poseStack, collector, light);
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 256;
	}

	/** The lamps of the tube (the housings are in the Blender model: -x wall at FLOOR + 2.5 + 4k, +x wall two metres higher). */
	private static BoxMesh buildTubeLamps() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float w = 1.5F;
		for (float y = FLOOR + 2.5F; y < GROUND - 1.0F; y += 4.0F) {
			b.box(-w + 0.14F, y + 0.05F, -0.11F, -w + 0.17F, y + 0.25F, 0.11F, LAMP);
			b.box(w - 0.17F, y + 2.05F, -0.11F, w - 0.14F, y + 2.25F, 0.11F, LAMP);
		}
		return b.build();
	}

	/** The beacons' lenses lit up: a dome just over each red lens of the model. */
	private static BoxMesh buildBeaconGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] p : BEACONS) {
			b.revolve(RED_LAMP, v(p[0], DECK + 1.215F, p[1]), v(0, 1, 0), new float[][] {{0.0F, 0.125F}, {0.135F, 0.125F}, {0.205F, 0.088F}, {0.235F, 0.0F}}, 10);
		}
		return b.build();
	}
}
