package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.GREEN_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.LAMP;
import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.block.LaunchPadBlock;
import de.rcm.ballistic.block.LaunchPadBlockEntity;
import de.rcm.ballistic.entity.MissileEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The launch complex around the launch table, modelled in Blender (tools/structures/launchpad.py) on a real
 * orbital/ICBM pad:
 * <ul>
 *   <li>a concrete apron with expansion joints, oil stains and soot blown out round the table, a flame trench
 *       running out to +X with refractory-lined walls, a curved steel deflector and a scorched spillway</li>
 *   <li>the fixed service tower on the -X side: I-beam columns and angle bracing in red and white bands, grated
 *       decks with railings, switch-back stairs, an elevator that rides up and down its shaft, a machine house
 *       with a hammerhead crane, the beacon and lightning rod on top, and four umbilical swing arms - only the
 *       arms the missile is tall enough to reach are connected, and they swing back to the tower in the last
 *       seconds of the countdown</li>
 *   <li>hold-down clamps on the table that keep the missile down while the engines come up to thrust and fling
 *       open at release</li>
 *   <li>the sound-suppression water system: an elevated water tower and its main, a deluge ring round the table,
 *       spray headers along the trench and two "rainbird" water cannons on the deck</li>
 *   <li>propellant farm: a white liquid-oxygen sphere with its boil-off vent and a fuel sphere in its dike, both
 *       piped to the tower</li>
 *   <li>two lightning masts with catenary and guy wires, floodlight masts, cameras, the bunker hatch, cable
 *       trenches, signs and bollards</li>
 * </ul>
 * Status lights on the tower show green while a missile is ready and flash red through the countdown and launch.
 */
public class LaunchPadRenderer implements BlockEntityRenderer<LaunchPadBlockEntity, LaunchPadRenderer.State> {
	static final float TOWER_X = -3.2F;
	static final float TOWER_TOP = 22.0F;
	static final float TABLE = (float) LaunchPadBlock.TOP_HEIGHT;
	static final float[] ARM_HEIGHTS = {4.6F, 8.6F, 12.6F, 16.6F};
	private static final float[] PLATFORMS = {4.4F, 8.4F, 12.4F, 16.4F, 20.4F, TOWER_TOP};
	static final float[][] FLOODLIGHTS = {{-4.2F, -4.2F}, {4.2F, -4.2F}, {-4.2F, 4.2F}, {4.2F, 4.2F}};
	/** Height of the floodlights' lamp bar, and how far the lamp heads are tilted down (launchpad.py FLOOD_H, TAU). */
	private static final float FLOOD_HEIGHT = 6.0F;
	private static final float FLOOD_TILT = 0.35F;
	/** Lightning masts north and south of the pad. */
	private static final float MAST_HEIGHT = 30.0F;
	private static final float[][] LIGHTNING_MASTS = {{0.5F, -10.0F}, {0.5F, 10.0F}};
	static final float[] LOX_SPHERE = {-9.5F, -8.5F};
	/** The tower's face towards the pad. */
	private static final float TOWER_FACE = TOWER_X + 0.55F;
	/** Heights of the status signal heads on the tower face, red lamp over green. */
	private static final float[] STATUS_Y = {2.2F, 6.4F, 10.4F, 14.4F};

	/** Arms swing about a hinge on the tower face; built around that hinge, reaching along +X to the missile. */
	static final float ARM_HINGE_X = TOWER_X + 0.65F;
	/** Swing of a fully retracted arm, radians. */
	static final float ARM_SWING = 1.35F;
	/** Hold-down clamp pivots on the table corners. */
	static final float CLAMP_PIVOT = 0.42F;

	/** The pad and its tower, and everything round it, as built in Blender (two models, one texture each). */
	static final StructureModel MODEL = StructureModel.load("launchpad");
	static final StructureModel SITE_MODEL = StructureModel.load("launchpad_site");
	private static final String[] ARM_PARTS = {"arm0", "arm1", "arm2", "arm3"};
	static final BoxMesh COMPLEX = MODEL.part("pad");
	static final BoxMesh SITE = SITE_MODEL.part("site");
	static final BoxMesh[] ARMS = {MODEL.part(ARM_PARTS[0]), MODEL.part(ARM_PARTS[1]), MODEL.part(ARM_PARTS[2]), MODEL.part(ARM_PARTS[3])};
	/** The hold-down clamp of the (+x, +z) corner, about its pivot; turned by quarter turns for the others. */
	static final BoxMesh CLAMP = MODEL.part("clamp");
	/** The elevator car, floor at y = 0, about the shaft's axis. */
	static final BoxMesh ELEVATOR = MODEL.part("elevator");
	static final BoxMesh GLOW = buildGlow();
	static final BoxMesh BEACON = new BoxMesh.Builder()
		.box(TOWER_X - 0.12F, TOWER_TOP + 3.0F, -0.12F, TOWER_X + 0.12F, TOWER_TOP + 3.25F, 0.12F, RED_LAMP)
		.box(LIGHTNING_MASTS[0][0] - 0.15F, MAST_HEIGHT, LIGHTNING_MASTS[0][1] - 0.15F, LIGHTNING_MASTS[0][0] + 0.15F, MAST_HEIGHT + 0.3F, LIGHTNING_MASTS[0][1] + 0.15F, RED_LAMP)
		.box(LIGHTNING_MASTS[1][0] - 0.15F, MAST_HEIGHT, LIGHTNING_MASTS[1][1] - 0.15F, LIGHTNING_MASTS[1][0] + 0.15F, MAST_HEIGHT + 0.3F, LIGHTNING_MASTS[1][1] + 0.15F, RED_LAMP)
		.build();
	static final BoxMesh READY_LIGHT = statusLight(0.03F, GREEN_LAMP);
	static final BoxMesh COUNT_LIGHT = statusLight(0.17F, RED_LAMP);

	public LaunchPadRenderer(BlockEntityRendererProvider.Context context) {
	}

	public static class State extends BlockEntityRenderState {
		public float time;
		/** Per arm: 0 = on the missile, 1 = swung back against the tower. */
		public final float[] retract = {1.0F, 1.0F, 1.0F, 1.0F};
		/** 0 = clamps closed on the missile, 1 = flung open. */
		public float clamps = 0.85F;
		/** 0 no missile, 1 missile ready, 2 counting down or launching. */
		public int status;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(LaunchPadBlockEntity pad, State state, float partialTick, Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay overlay) {
		BlockEntityRenderer.super.extractRenderState(pad, state, partialTick, cameraPos, overlay);
		state.time = pad.getLevel() == null ? 0.0F : pad.getLevel().getGameTime() + partialTick;
		java.util.Arrays.fill(state.retract, 1.0F);
		state.clamps = 0.85F;
		state.status = 0;
		if (pad.getLevel() == null) {
			return;
		}
		for (MissileEntity missile : pad.getLevel().getEntitiesOfClass(MissileEntity.class, new AABB(pad.getBlockPos()).inflate(0.5, 3.0, 0.5))) {
			int s = missile.getState();
			float age = missile.clientStateAge + partialTick;
			float swing;
			if (s == MissileEntity.IDLE) {
				swing = 0.0F;
				state.clamps = 0.0F;
				state.status = 1;
			} else if (s == MissileEntity.COUNTDOWN) {
				// the arms let go and swing back between 5 and 2 seconds before ignition
				float remaining = missile.getMissileType().countdownTicks - age;
				float t = Mth.clamp((100.0F - remaining) / 60.0F, 0.0F, 1.0F);
				swing = t * t * (3.0F - 2.0F * t);
				state.clamps = 0.0F;
				state.status = 2;
			} else if (s == MissileEntity.IGNITION) {
				// engines run up to full thrust against the hold-downs, then they let go at once
				swing = 1.0F;
				state.clamps = Mth.clamp((age - (missile.getMissileType().ignitionTicks - 5)) / 3.0F, 0.0F, 1.0F);
				state.status = 2;
			} else {
				swing = 1.0F;
				state.clamps = 1.0F;
				state.status = 2;
			}
			float top = TABLE + missile.getMissileType().length;
			for (int i = 0; i < ARM_HEIGHTS.length; i++) {
				// an arm above the missile's nose has nothing to connect to and stays parked
				state.retract[i] = ARM_HEIGHTS[i] < top - 0.6F ? swing : 1.0F;
			}
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		ModelPick.part(state, poseStack, camera, COMPLEX);
		MODEL.submit("pad", poseStack, collector, light);
		ModelPick.part(state, poseStack, camera, SITE);
		SITE_MODEL.submit("site", poseStack, collector, light);
		collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> GLOW.emit(pose, consumer, LightTexture.FULL_BRIGHT));

		// umbilical swing arms
		for (int i = 0; i < ARMS.length; i++) {
			poseStack.pushPose();
			poseStack.translate(ARM_HINGE_X, 0.0F, 0.0F);
			poseStack.mulPose(new Quaternionf().rotationY(state.retract[i] * ARM_SWING));
			ModelPick.part(state, poseStack, camera, ARMS[i]);
			MODEL.submit(ARM_PARTS[i], poseStack, collector, light);
			poseStack.popPose();
		}

		// hold-down clamps on the four table corners, flipping up and outwards at release
		for (int c = 0; c < 4; c++) {
			poseStack.pushPose();
			poseStack.mulPose(new Quaternionf().rotationY(c * Mth.HALF_PI));
			poseStack.translate(CLAMP_PIVOT, TABLE + 0.32F, CLAMP_PIVOT);
			poseStack.mulPose(new Quaternionf().rotationAxis(-state.clamps * 1.9F, new Vector3f(-1.0F, 0.0F, 1.0F).normalize()));
			ModelPick.part(state, poseStack, camera, CLAMP);
			MODEL.submit("clamp", poseStack, collector, light);
			poseStack.popPose();
		}

		// the tower elevator, riding up and down
		float lift = 0.3F + (TOWER_TOP - 2.0F) * (0.5F - 0.5F * Mth.cos(state.time * 0.006F));
		poseStack.pushPose();
		poseStack.translate(TOWER_X, lift, 0.0F);
		ModelPick.part(state, poseStack, camera, ELEVATOR);
		MODEL.submit("elevator", poseStack, collector, light);
		poseStack.popPose();

		// the floodlights really light the pad (dynamic light: these lamps shine on what is round them)
		for (int i = 0; i < FLOODLIGHTS.length; i++) {
			de.rcm.ballistic.client.effect.DynamicLights.blockLamp(state.blockPos, i, FLOODLIGHTS[i][0] * 0.92F, 5.8F, FLOODLIGHTS[i][1] * 0.92F,
				0xFFF0D8, 0.55F, 20.0F);
		}
		// status lights: steady green when ready, fast red flashing through countdown and launch
		if (state.status == 1) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> READY_LIGHT.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			de.rcm.ballistic.client.effect.DynamicLights.blockLamp(state.blockPos, 8, ARM_HINGE_X + 0.3F, 6.5F, 0.65F, 0x40FF60, 0.4F, 7.0F);
		} else if (state.status == 2 && Mth.sin(state.time * 0.8F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> COUNT_LIGHT.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			de.rcm.ballistic.client.effect.DynamicLights.blockLamp(state.blockPos, 8, ARM_HINGE_X + 0.3F, 6.5F, 0.65F, 0xFF2010, 0.9F, 12.0F);
			de.rcm.ballistic.client.effect.DynamicLights.blockLamp(state.blockPos, 9, ARM_HINGE_X + 0.3F, 14.5F, 0.65F, 0xFF2010, 0.9F, 12.0F);
		}
		// aviation obstruction lights on the tower and the lightning masts: slow red blink
		if (Mth.sin(state.time * 0.2F) > 0.0F) {
			collector.submitCustomGeometry(poseStack, StructureKit.GLOW_TYPE, (pose, consumer) -> BEACON.emit(pose, consumer, LightTexture.FULL_BRIGHT));
			de.rcm.ballistic.client.effect.DynamicLights.blockLamp(state.blockPos, 10, TOWER_X, TOWER_TOP + 3.1F, 0.0F, 0xFF3020, 0.5F, 9.0F);
		}
		poseStack.popPose();
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 320;
	}

	// ------------------------------------------------------------------ lamps (the glowing faces over the model's lenses)

	/** One lamp of each signal head on the tower face: {@code dy} above the head's foot (green 0.03, red 0.17). */
	private static BoxMesh statusLight(float dy, int patch) {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float y : STATUS_Y) {
			b.box(TOWER_FACE + 0.118F, y + dy - 0.003F, 0.947F, TOWER_FACE + 0.135F, y + dy + 0.103F, 1.053F, patch);
		}
		return b.build();
	}

	/** The floodlights' lenses and the work lights under every tower deck. */
	private static BoxMesh buildGlow() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		for (float[] m : FLOODLIGHTS) {
			floodGlow(b, m[0], m[1]);
		}
		for (float y : PLATFORMS) {
			b.box(TOWER_X - 0.133F, y - 0.076F, 0.842F, TOWER_X + 0.133F, y - 0.058F, 0.918F, LAMP);
			b.box(TOWER_X - 0.133F, y - 0.076F, -0.918F, TOWER_X + 0.133F, y - 0.058F, -0.842F, LAMP);
		}
		return b.build();
	}

	/** The four lamp heads on a floodlight mast's bar, aimed at the pad and tilted down (as launchpad.py places them). */
	private static void floodGlow(BoxMesh.Builder b, float x, float z) {
		float len = (float) Math.sqrt(x * x + z * z);
		Vector3f d = new Vector3f(-x / len, 0.0F, -z / len);
		Vector3f p = new Vector3f(-d.z, 0.0F, d.x);
		float cos = Mth.cos(FLOOD_TILT);
		float sin = Mth.sin(FLOOD_TILT);
		Vector3f f = new Vector3f(d.x * cos, -sin, d.z * cos);
		Vector3f h = new Vector3f(d.x * sin, cos, d.z * sin);
		for (float t : new float[] {-0.35F, -0.12F, 0.12F, 0.35F}) {
			Vector3f c = new Vector3f(x, FLOOD_HEIGHT - 0.06F, z).add(new Vector3f(p).mul(t)).add(new Vector3f(d).mul(0.16F)).add(new Vector3f(f).mul(0.0625F));
			b.hexa(LAMP, corners(c, p, h, f, 0.083F, 0.068F, 0.0125F));
		}
	}

	/** The eight corners of a box about {@code c} with half sizes along three axes, in hexa's order. */
	private static Vector3f[] corners(Vector3f c, Vector3f ax, Vector3f ay, Vector3f az, float hx, float hy, float hz) {
		float[][] ring = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
		Vector3f[] out = new Vector3f[8];
		for (int i = 0; i < 8; i++) {
			float sy = i < 4 ? -1.0F : 1.0F;
			float[] r = ring[i & 3];
			out[i] = new Vector3f(c).add(new Vector3f(ax).mul(r[0] * hx)).add(new Vector3f(ay).mul(sy * hy)).add(new Vector3f(az).mul(r[1] * hz));
		}
		return out;
	}
}
