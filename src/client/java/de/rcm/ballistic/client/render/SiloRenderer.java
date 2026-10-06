package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BLACK;
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
 * dark shaft with the missile's nose cone inside, a security fence, warning beacons that flash during
 * the countdown, an antenna mast, a ventilation stack and the personnel access hatch.
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

	public SiloRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float open;
		public boolean counting;
		public @Nullable MissileType missile;
		public float time;
		public boolean submarine;
		public float facingYaw;
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
			this.submitSubmarine(state, poseStack, collector, light);
			poseStack.popPose();
			return;
		}
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> HEADWORKS.emit(pose, consumer, light));

		// the missile waiting in the shaft: only its nose cone shows above the shaft floor
		MissileType missile = state.missile;
		if (missile != null) {
			MissileMesh mesh = MissileMesh.of(missile);
			float s = missile.scale;
			poseStack.pushPose();
			poseStack.translate(0.0F, DECK - 0.15F - mesh.length() * s, 0.0F);
			poseStack.scale(s, s, s);
			collector.submitCustomGeometry(poseStack, MissileRenderer.bodyType(missile), (pose, consumer) -> mesh.emit(pose, consumer, light));
			poseStack.popPose();
		}

		// the two door halves roll outwards along their rails
		for (int i = 0; i < 2; i++) {
			BoxMesh door = DOORS[i];
			poseStack.pushPose();
			poseStack.translate((i == 0 ? -1.0F : 1.0F) * state.open * DOOR_TRAVEL, 0.0F, 0.0F);
			collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> door.emit(pose, consumer, light));
			poseStack.popPose();
		}

		if (state.counting && Mth.sin(state.time * 0.9F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BEACON_GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		}
		poseStack.popPose();
	}

	/** Submarine variant: the hull around the block, the live hatch swinging open, the missile in its tube. */
	private void submitSubmarine(State state, PoseStack poseStack, SubmitNodeCollector collector, int light) {
		poseStack.mulPose(new Quaternionf().rotationY(state.facingYaw));
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
		b.box(-o, GROUND, -o, o, GROUND + 0.02F, o, BLACK);
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

	private static BoxMesh buildBeaconGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] p : BEACONS) {
			b.box(p[0] - 0.16F, DECK + 1.19F, p[1] - 0.16F, p[0] + 0.16F, DECK + 1.47F, p[1] + 0.16F, RED_LAMP);
		}
		return b.build();
	}
}
