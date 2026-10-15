package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
import static de.rcm.ballistic.client.render.StructureKit.CABLE;
import static de.rcm.ballistic.client.render.StructureKit.GRATING;
import static de.rcm.ballistic.client.render.StructureKit.LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RED;
import static de.rcm.ballistic.client.render.StructureKit.SOOT;
import static de.rcm.ballistic.client.render.StructureKit.YELLOW;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE;
import static de.rcm.ballistic.client.render.StructureKit.CONCRETE_DARK;
import static de.rcm.ballistic.client.render.StructureKit.DOOR;
import static de.rcm.ballistic.client.render.StructureKit.HAZARD;
import static de.rcm.ballistic.client.render.StructureKit.OLIVE;
import static de.rcm.ballistic.client.render.StructureKit.PANEL;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RUST;
import static de.rcm.ballistic.client.render.StructureKit.SHAFT;
import static de.rcm.ballistic.client.render.StructureKit.STEEL;
import static de.rcm.ballistic.client.render.StructureKit.VENT;
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
 * Minuteman-style silo headworks on top of the hatch block: a raised concrete launcher closure
 * with sloped berms, a pair of armoured sliding doors on rails that roll aside before launch, the
 * launch tube below with the missile standing on its floor, a security fence, warning beacons that
 * flash during the countdown, an antenna mast, a ventilation stack and the personnel access hatch.
 * <p>
 * The tube ({@link MissileSiloBlock#digShaft dug into the ground} when the silo is placed) is fitted
 * out like a Minuteman launch tube: a steel liner with stiffening rings, cable runs, folded-back
 * work platforms, lamps every few metres, an umbilical to the missile and the shock-isolated launch
 * platform it stands on.
 * <p>
 * The {@link HatchSiloBlock ground-hatch variant} has no headworks: a low concrete collar flush with
 * the ground and two armoured leaves that swing up on heavy hinges, past vertical, on either side.
 */
public class SiloRenderer implements BlockEntityRenderer<MissileSiloBlockEntity, SiloRenderer.State> {
	/** Top of the hatch block = ground level. */
	private static final float GROUND = 1.0F;
	private static final float DECK = GROUND + 0.45F;
	private static final float OPENING = 1.5F;
	private static final float DOOR_TRAVEL = 1.9F;
	private static final float[][] BEACONS = {{-3.2F, -3.2F}, {3.2F, 3.2F}};

	private static final BoxMesh HEADWORKS = buildHeadworks();
	private static final BoxMesh[] DOORS = {buildDoor(-1.0F), buildDoor(1.0F)};
	private static final BoxMesh BEACON_GLOW = buildBeaconGlow();
	/** Floor of the tube in block space (the hatch block's bottom is 0). */
	private static final float FLOOR = -MissileSiloBlock.SHAFT_DEPTH;
	private static final float PLATFORM = FLOOR + 0.3F;
	private static final BoxMesh TUBE = buildTube();
	private static final BoxMesh TUBE_LAMPS = buildTubeLamps();

	/** Ground-hatch variant: how far the leaves swing (a little past vertical, radians). */
	private static final float LEAF_SWING = 1.92F;
	private static final float LEAF_THICK = 0.32F;
	private static final float[][] HATCH_BEACONS = {{-2.15F, -2.15F}, {2.15F, 2.15F}};
	private static final BoxMesh COLLAR = buildCollar();
	private static final BoxMesh[] LEAVES = {buildLeaf(-1.0F), buildLeaf(1.0F)};
	private static final BoxMesh HATCH_BEACON_GLOW = buildHatchBeaconGlow();

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
		BoxMesh top = state.hatch ? COLLAR : HEADWORKS;
		ModelPick.part(state, poseStack, camera, top);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> top.emit(pose, consumer, light));

		// the launch tube, lit by its own lamps
		int tubeLight = LightTexture.pack(Math.max(LightTexture.block(light), 11), LightTexture.sky(light));
		ModelPick.part(state, poseStack, camera, TUBE);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> TUBE.emit(pose, consumer, tubeLight));
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
			// the two leaves swing up about their hinges at the edges of the tube mouth
			for (int i = 0; i < 2; i++) {
				float side = i == 0 ? -1.0F : 1.0F;
				BoxMesh leaf = LEAVES[i];
				poseStack.pushPose();
				poseStack.translate(side * OPENING, GROUND + 0.05F, 0.0F);
				poseStack.mulPose(new Quaternionf().rotationZ(-side * state.open * LEAF_SWING));
				ModelPick.part(state, poseStack, camera, leaf);
				collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> leaf.emit(pose, consumer, light));
				poseStack.popPose();
			}
			if (state.counting && Mth.sin(state.time * 0.9F) > 0.0F) {
				collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> HATCH_BEACON_GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			}
			poseStack.popPose();
			return;
		}

		// the two door halves roll outwards along their rails
		for (int i = 0; i < 2; i++) {
			BoxMesh door = DOORS[i];
			poseStack.pushPose();
			poseStack.translate((i == 0 ? -1.0F : 1.0F) * state.open * DOOR_TRAVEL, 0.0F, 0.0F);
			ModelPick.part(state, poseStack, camera, door);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> door.emit(pose, consumer, light));
			poseStack.popPose();
		}

		if (state.counting && Mth.sin(state.time * 0.9F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BEACON_GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		poseStack.popPose();
	}

	/** Submarine variant: the hull around the block, the live hatch swinging open, the missile in its tube. */
	private void submitSubmarine(State state, PoseStack poseStack, SubmitNodeCollector collector, int light, CameraRenderState camera) {
		poseStack.mulPose(new Quaternionf().rotationY(state.facingYaw));
		ModelPick.part(state, poseStack, camera, SubmarineModel.HULL);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> SubmarineModel.HULL.emit(pose, consumer, light));
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
		poseStack.translate(0.0F, SubmarineModel.DECK_Y, -0.45F);
		poseStack.mulPose(new Quaternionf().rotationX(-state.open * 1.75F));
		ModelPick.part(state, poseStack, camera, SubmarineModel.HATCH);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> SubmarineModel.HATCH.emit(pose, consumer, light));
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 256;
	}

	private static BoxMesh buildHeadworks() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float o = OPENING;
		// raised launcher closure around the 3x3 opening
		b.box(-3.5F, GROUND, -3.5F, 3.5F, DECK, -o, CONCRETE);
		b.box(-3.5F, GROUND, o, 3.5F, DECK, 3.5F, CONCRETE);
		b.box(-3.5F, GROUND, -o, -o, DECK, o, CONCRETE);
		b.box(o, GROUND, -o, 3.5F, DECK, o, CONCRETE);
		// sloped berm on all four sides
		b.hexa(CONCRETE_DARK,
			v(-3.5F, GROUND, -3.5F), v(3.5F, GROUND, -3.5F), v(3.5F, DECK, -3.5F), v(-3.5F, DECK, -3.5F),
			v(-4.5F, GROUND, -4.5F), v(4.5F, GROUND, -4.5F), v(4.5F, GROUND + 0.02F, -4.5F), v(-4.5F, GROUND + 0.02F, -4.5F));
		b.hexa(CONCRETE_DARK,
			v(-3.5F, GROUND, 3.5F), v(3.5F, GROUND, 3.5F), v(3.5F, DECK, 3.5F), v(-3.5F, DECK, 3.5F),
			v(-4.5F, GROUND, 4.5F), v(4.5F, GROUND, 4.5F), v(4.5F, GROUND + 0.02F, 4.5F), v(-4.5F, GROUND + 0.02F, 4.5F));
		b.hexa(CONCRETE_DARK,
			v(-3.5F, GROUND, -3.5F), v(-3.5F, GROUND, 3.5F), v(-3.5F, DECK, 3.5F), v(-3.5F, DECK, -3.5F),
			v(-4.5F, GROUND, -4.5F), v(-4.5F, GROUND, 4.5F), v(-4.5F, GROUND + 0.02F, 4.5F), v(-4.5F, GROUND + 0.02F, -4.5F));
		b.hexa(CONCRETE_DARK,
			v(3.5F, GROUND, -3.5F), v(3.5F, GROUND, 3.5F), v(3.5F, DECK, 3.5F), v(3.5F, DECK, -3.5F),
			v(4.5F, GROUND, -4.5F), v(4.5F, GROUND, 4.5F), v(4.5F, GROUND + 0.02F, 4.5F), v(4.5F, GROUND + 0.02F, -4.5F));
		// the shaft: lined walls and a pitch-dark floor far below (the terrain hides the rest)
		b.box(-o, GROUND, -o, -o + 0.08F, DECK, o, SHAFT);
		b.box(o - 0.08F, GROUND, -o, o, DECK, o, SHAFT);
		b.box(-o, GROUND, -o, o, DECK, -o + 0.08F, SHAFT);
		b.box(-o, GROUND, o - 0.08F, o, DECK, o, SHAFT);
		// hazard rim around the opening
		b.box(-o - 0.2F, DECK, -o - 0.2F, o + 0.2F, DECK + 0.015F, -o, HAZARD);
		b.box(-o - 0.2F, DECK, o, o + 0.2F, DECK + 0.015F, o + 0.2F, HAZARD);
		// door rails running out to both sides
		for (float z : new float[] {-1.75F, 1.75F}) {
			b.box(-3.5F, DECK, z - 0.08F, 3.5F, DECK + 0.08F, z + 0.08F, RUST);
			b.box(-3.55F, DECK, z - 0.14F, -3.4F, DECK + 0.25F, z + 0.14F, STEEL); // end stops
			b.box(3.4F, DECK, z - 0.14F, 3.55F, DECK + 0.25F, z + 0.14F, STEEL);
		}
		// security fence on the berm edge
		float f = 4.8F;
		for (float p = -f; p <= f + 0.01F; p += 1.6F) {
			for (float[] post : new float[][] {{p, -f}, {p, f}, {-f, p}, {f, p}}) {
				b.box(post[0] - 0.05F, 0.0F, post[1] - 0.05F, post[0] + 0.05F, 2.2F, post[1] + 0.05F, STEEL);
			}
		}
		for (float h : new float[] {0.6F, 1.4F, 2.1F}) {
			b.box(-f, h, -f - 0.02F, f, h + 0.03F, -f + 0.02F, STEEL);
			b.box(-f, h, f - 0.02F, f, h + 0.03F, f + 0.02F, STEEL);
			b.box(-f - 0.02F, h, -f, -f + 0.02F, h + 0.03F, f, STEEL);
			b.box(f - 0.02F, h, -f, f + 0.02F, h + 0.03F, f, STEEL);
		}
		// warning beacons
		for (float[] p : BEACONS) {
			b.box(p[0] - 0.06F, DECK, p[1] - 0.06F, p[0] + 0.06F, DECK + 1.2F, p[1] + 0.06F, STEEL);
			b.box(p[0] - 0.14F, DECK + 1.2F, p[1] - 0.14F, p[0] + 0.14F, DECK + 1.45F, p[1] + 0.14F, RED_LAMP);
		}
		// UHF antenna mast with crossed dipoles
		b.box(-3.05F, DECK, 2.95F, -2.95F, DECK + 6.0F, 3.05F, STEEL);
		for (float y : new float[] {DECK + 4.2F, DECK + 5.0F, DECK + 5.8F}) {
			b.box(-3.6F, y, 2.97F, -2.4F, y + 0.05F, 3.03F, STEEL);
			b.box(-3.03F, y + 0.2F, 2.4F, -2.97F, y + 0.25F, 3.6F, STEEL);
		}
		// ventilation stack and the personnel access hatch with its equipment box
		b.box(2.6F, DECK, -3.3F, 3.3F, DECK + 1.3F, -2.6F, CONCRETE_DARK);
		b.box(2.55F, DECK + 0.7F, -3.2F, 2.6F, DECK + 1.2F, -2.7F, VENT);
		b.box(2.65F, DECK + 1.3F, -3.25F, 3.25F, DECK + 1.36F, -2.65F, VENT);
		b.box(-3.3F, DECK, -3.3F, -2.3F, DECK + 0.25F, -2.3F, CONCRETE_DARK);
		b.box(-3.15F, DECK + 0.25F, -3.15F, -2.45F, DECK + 0.3F, -2.45F, DOOR);
		b.box(-1.0F, DECK, -3.4F, 0.4F, DECK + 0.9F, -2.6F, OLIVE);
		b.box(-0.95F, DECK + 0.1F, -2.62F, -0.35F, DECK + 0.8F, -2.58F, PANEL);
		return b.build();
	}

	/** The fittings of the launch tube (inside a 3x3 hole, walls at +-1.5). */
	private static BoxMesh buildTube() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float w = 1.5F;
		float l = 0.06F;
		// steel liner on the four walls
		b.box(-w, FLOOR, -w, -w + l, GROUND, w, SHAFT);
		b.box(w - l, FLOOR, -w, w, GROUND, w, SHAFT);
		b.box(-w, FLOOR, -w, w, GROUND, -w + l, SHAFT);
		b.box(-w, FLOOR, w - l, w, GROUND, w, SHAFT);
		// stiffening rings every two metres
		for (float y = FLOOR + 1.5F; y < GROUND - 0.5F; y += 2.0F) {
			b.box(-w + l, y, -w + l, -w + 0.16F, y + 0.12F, w - l, STEEL);
			b.box(w - 0.16F, y, -w + l, w - l, y + 0.12F, w - l, STEEL);
			b.box(-w + l, y, -w + l, w - l, y + 0.12F, -w + 0.16F, STEEL);
			b.box(-w + l, y, w - 0.16F, w - l, y + 0.12F, w - l, STEEL);
		}
		// cable runs down two corners and a ladder down a third
		b.box(-w + l, FLOOR, -w + l, -w + 0.26F, GROUND, -w + 0.26F, CABLE);
		b.box(w - 0.26F, FLOOR, -w + l, w - l, GROUND, -w + 0.26F, CABLE);
		b.box(-w + 0.12F, FLOOR, w - 0.2F, -w + 0.18F, GROUND, w - 0.14F, STEEL);
		b.box(-w + 0.52F, FLOOR, w - 0.2F, -w + 0.58F, GROUND, w - 0.14F, STEEL);
		for (float y = FLOOR + 0.3F; y < GROUND; y += 0.3F) {
			b.box(-w + 0.12F, y, w - 0.19F, -w + 0.58F, y + 0.04F, w - 0.15F, STEEL);
		}
		// work platforms folded back against the walls
		for (float y : new float[] {FLOOR + 6.0F, FLOOR + 12.0F, FLOOR + 18.0F}) {
			b.box(-w + l, y, -w + l, -w + 0.3F, y + 0.06F, w - l, GRATING);
			b.box(w - 0.3F, y, -w + l, w - l, y + 0.06F, w - l, GRATING);
			b.box(-w + 0.3F, y - 0.4F, 0.6F, -w + 0.34F, y + 0.7F, 0.66F, YELLOW); // folded railing
			b.box(w - 0.34F, y - 0.4F, -0.66F, w - 0.3F, y + 0.7F, -0.6F, YELLOW);
		}
		// lamp housings
		for (float y = FLOOR + 2.5F; y < GROUND - 1.0F; y += 4.0F) {
			b.box(-w + l, y, -0.15F, -w + 0.14F, y + 0.3F, 0.15F, BLACK);
			b.box(w - 0.14F, y + 2.0F, -0.15F, w - l, y + 2.3F, 0.15F, BLACK);
		}
		// umbilical from the wall to the missile's equipment section
		b.beam(v(w - l, FLOOR + 7.5F, 0.4F), v(0.62F, FLOOR + 7.2F, 0.3F), 0.12F, 0.12F, CABLE);
		b.box(w - 0.12F, FLOOR + 6.8F, 0.25F, w - l, FLOOR + 8.0F, 0.55F, PANEL);
		// launch platform on shock isolators, over the gas generator's vent
		b.box(-1.2F, FLOOR, -1.2F, 1.2F, FLOOR + 0.02F, 1.2F, SOOT);
		b.box(-1.0F, FLOOR + 0.18F, -1.0F, 1.0F, PLATFORM, 1.0F, STEEL);
		b.box(-0.7F, PLATFORM, -0.7F, 0.7F, PLATFORM + 0.02F, 0.7F, HAZARD);
		for (float[] c : new float[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
			// suspension struts from the tube wall to the platform corners
			b.beam(v(c[0] * (w - l), FLOOR + 2.4F, c[1] * (w - l)), v(c[0] * 0.95F, PLATFORM - 0.05F, c[1] * 0.95F), 0.1F, 0.1F, RED);
			b.box(c[0] * 0.95F - 0.12F, FLOOR, c[1] * 0.95F - 0.12F, c[0] * 0.95F + 0.12F, FLOOR + 0.18F, c[1] * 0.95F + 0.12F, BLACK);
		}
		return b.build();
	}

	private static BoxMesh buildTubeLamps() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float w = 1.5F;
		for (float y = FLOOR + 2.5F; y < GROUND - 1.0F; y += 4.0F) {
			b.box(-w + 0.14F, y + 0.05F, -0.11F, -w + 0.17F, y + 0.25F, 0.11F, LAMP);
			b.box(w - 0.17F, y + 2.05F, -0.11F, w - 0.14F, y + 2.25F, 0.11F, LAMP);
		}
		return b.build();
	}

	/** One door half in its closed position: {@code side} +1 covers x >= 0, -1 covers x <= 0. */
	private static BoxMesh buildDoor(float side) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float o = OPENING + 0.1F;
		b.box(Math.min(0.0F, side * o), DECK, -1.85F, Math.max(0.0F, side * o), DECK + 0.4F, 1.85F, CONCRETE);
		b.box(Math.min(side * 0.02F, side * (o - 0.05F)), DECK + 0.4F, -1.8F, Math.max(side * 0.02F, side * (o - 0.05F)), DECK + 0.42F, 1.8F, DOOR);
		b.box(Math.min(0.0F, side * 0.15F), DECK + 0.42F, -1.85F, Math.max(0.0F, side * 0.15F), DECK + 0.44F, 1.85F, HAZARD); // meeting edge
		// wheel trucks riding on the rails
		for (float z : new float[] {-1.75F, 1.75F}) {
			b.box(Math.min(side * 0.2F, side * (o - 0.2F)), DECK + 0.08F, z - 0.12F, Math.max(side * 0.2F, side * (o - 0.2F)), DECK + 0.2F, z + 0.12F, BLACK);
		}
		return b.build();
	}

	/**
	 * The ground-hatch variant's collar on top of the 5x5 concrete ring: a raised lip around the tube
	 * mouth, the hinge blocks on two sides, a hazard band, drains, flush lamp beacons, a short sensor
	 * mast and the personnel access hatch.
	 */
	private static BoxMesh buildCollar() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float o = OPENING;
		float g = GROUND;
		float e = 2.5F;
		// concrete skin over the ring, with a lip around the mouth the leaves seat on
		b.box(-e, g - 0.02F, -e, e, g + 0.02F, -o, CONCRETE);
		b.box(-e, g - 0.02F, o, e, g + 0.02F, e, CONCRETE);
		b.box(-e, g - 0.02F, -o, -o, g + 0.02F, o, CONCRETE);
		b.box(o, g - 0.02F, -o, e, g + 0.02F, o, CONCRETE);
		b.box(-o - 0.12F, g, -o - 0.12F, o + 0.12F, g + 0.06F, -o, CONCRETE_DARK);
		b.box(-o - 0.12F, g, o, o + 0.12F, g + 0.06F, o + 0.12F, CONCRETE_DARK);
		// hazard band round the lip, broken at the hinges
		b.box(-o, g + 0.02F, -o - 0.4F, o, g + 0.03F, -o - 0.12F, HAZARD);
		b.box(-o, g + 0.02F, o + 0.12F, o, g + 0.03F, o + 0.4F, HAZARD);
		// hinge blocks: a steel bed and three knuckles on each side, pins through them
		for (float side : new float[] {-1.0F, 1.0F}) {
			float x0 = side * o;
			b.box(Math.min(x0, x0 + side * 0.45F), g, -o - 0.1F, Math.max(x0, x0 + side * 0.45F), g + 0.08F, o + 0.1F, STEEL);
			for (float z : new float[] {-1.2F, 0.0F, 1.2F}) {
				b.box(Math.min(x0 + side * 0.05F, x0 + side * 0.3F), g + 0.05F, z - 0.14F, Math.max(x0 + side * 0.05F, x0 + side * 0.3F), g + 0.24F, z + 0.14F, RUST);
				b.box(Math.min(x0 + side * 0.4F, x0 + side * 0.85F), g, z - 0.2F, Math.max(x0 + side * 0.4F, x0 + side * 0.85F), g + 0.18F, z + 0.2F, OLIVE); // actuator housing
			}
			b.box(Math.min(x0, x0 + side * 0.08F), g + 0.1F, -o, Math.max(x0, x0 + side * 0.08F), g + 0.16F, o, STEEL); // hinge pin
			// drain grate along the outer edge
			b.box(Math.min(side * 2.05F, side * 2.3F), g + 0.02F, -1.0F, Math.max(side * 2.05F, side * 2.3F), g + 0.03F, 1.0F, GRATING);
		}
		// flush warning beacons in the corners
		for (float[] p : HATCH_BEACONS) {
			b.box(p[0] - 0.16F, g, p[1] - 0.16F, p[0] + 0.16F, g + 0.1F, p[1] + 0.16F, STEEL);
			b.box(p[0] - 0.11F, g + 0.1F, p[1] - 0.11F, p[0] + 0.11F, g + 0.22F, p[1] + 0.11F, RED_LAMP);
		}
		// short sensor mast with a radome and a whip antenna
		b.box(-2.3F, g, 2.0F, -1.9F, g + 0.12F, 2.4F, CONCRETE_DARK);
		b.box(-2.13F, g + 0.12F, 2.17F, -2.07F, g + 1.6F, 2.23F, STEEL);
		b.box(-2.22F, g + 1.6F, 2.08F, -1.98F, g + 1.84F, 2.32F, PANEL);
		b.box(-2.115F, g + 1.84F, 2.185F, -2.085F, g + 2.9F, 2.215F, BLACK);
		// personnel access hatch and a vent mushroom on the far edge
		b.box(1.75F, g, -2.4F, 2.4F, g + 0.1F, -1.75F, CONCRETE_DARK);
		b.box(1.82F, g + 0.1F, -2.33F, 2.33F, g + 0.14F, -1.82F, DOOR);
		b.box(2.0F, g + 0.14F, -2.1F, 2.15F, g + 0.18F, -2.05F, STEEL); // handle
		b.box(-2.3F, g, -2.3F, -1.9F, g + 0.5F, -1.9F, STEEL);
		b.box(-2.4F, g + 0.5F, -2.4F, -1.8F, g + 0.62F, -1.8F, VENT);
		return b.build();
	}

	/**
	 * One hatch leaf, built about its hinge line (x = 0, at the leaf's top surface) and lying flat:
	 * {@code side} +1 is hinged at x = +1.5 and covers the half towards x = 0, -1 the other half.
	 */
	private static BoxMesh buildLeaf(float side) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float w = OPENING + 0.08F;
		float in = -side * OPENING; // the meeting edge, relative to the hinge
		float lo = Math.min(0.0F, in);
		float hi = Math.max(0.0F, in);
		float t = LEAF_THICK;
		// armoured body and its top plate
		b.box(lo, -t, -w, hi, -0.03F, w, CONCRETE);
		b.box(lo + 0.03F, -0.03F, -w + 0.03F, hi - 0.03F, 0.0F, w - 0.03F, DOOR);
		// stiffener ribs on the underside (seen once it stands open)
		for (float z : new float[] {-1.0F, 0.0F, 1.0F}) {
			b.box(lo + 0.1F, -t - 0.12F, z - 0.06F, hi - 0.1F, -t, z + 0.06F, STEEL);
		}
		b.box(Math.min(in * 0.5F - 0.06F, in * 0.5F + 0.06F), -t - 0.12F, -w + 0.1F, Math.max(in * 0.5F - 0.06F, in * 0.5F + 0.06F), -t, w - 0.1F, STEEL);
		// hazard stripe and seal along the meeting edge
		float m0 = in + side * 0.18F;
		b.box(Math.min(in, m0), -0.005F, -w, Math.max(in, m0), 0.012F, w, HAZARD);
		b.box(Math.min(in, in + side * 0.03F), -t, -w, Math.max(in, in + side * 0.03F), -0.03F, w, BLACK);
		// lifting eyes and the hinge knuckles on the leaf
		for (float z : new float[] {-0.6F, 0.6F}) {
			b.box(in * 0.5F - 0.08F, 0.0F, z - 0.05F, in * 0.5F + 0.08F, 0.08F, z + 0.05F, STEEL);
		}
		for (float z : new float[] {-0.6F, 0.6F}) {
			b.box(Math.min(0.0F, -side * 0.22F), -0.2F, z - 0.18F, Math.max(0.0F, -side * 0.22F), 0.06F, z + 0.18F, RUST);
		}
		return b.build();
	}

	private static BoxMesh buildHatchBeaconGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] p : HATCH_BEACONS) {
			b.box(p[0] - 0.12F, GROUND + 0.1F, p[1] - 0.12F, p[0] + 0.12F, GROUND + 0.23F, p[1] + 0.12F, RED_LAMP);
		}
		return b.build();
	}

	private static BoxMesh buildBeaconGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] p : BEACONS) {
			b.box(p[0] - 0.16F, DECK + 1.19F, p[1] - 0.16F, p[0] + 0.16F, DECK + 1.47F, p[1] + 0.16F, RED_LAMP);
		}
		return b.build();
	}
}
