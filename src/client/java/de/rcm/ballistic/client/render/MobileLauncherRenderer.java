package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.MissileType;
import de.rcm.ballistic.entity.MobileLauncherEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Eight-wheeled transporter-erector-launcher with a raisable erector and the missile on it. */
public class MobileLauncherRenderer extends EntityRenderer<MobileLauncherEntity, MobileLauncherRenderer.State> {
	private static final RenderType TYPE = RenderTypes.entityCutout(BallisticMissiles.id("textures/entity/mobile_launcher.png"));

	private static final int OLIVE = 0;
	private static final int DARK_OLIVE = 1;
	private static final int TIRE = 2;
	private static final int RIM = 3;
	private static final int GLASS = 4;
	private static final int STEEL = 5;
	private static final int BLACK = 6;
	private static final int LAMP = 7;
	private static final int HAZARD = 8;
	private static final int TREAD = 9;
	private static final int GRILLE = 10;
	private static final int CAMO = 11;
	private static final int RED = 12;
	private static final int ORANGE = 13;
	private static final int RAIL = 14;

	private static final float WHEEL_RADIUS = 0.62F;
	private static final float[] AXLES = {4.3F, 2.7F, -2.2F, -3.8F};
	/** Gap between the missile axis and the erector rails. */
	private static final float RAIL_OFFSET = 0.6F;

	private static final BoxMesh BODY = buildBody();
	private static final BoxMesh WHEEL = new BoxMesh.Builder().cylinderX(0, 0, 0, WHEEL_RADIUS, 0.5F, 14, TREAD, RIM).build();
	private static final BoxMesh ERECTOR = buildErector();
	private static final BoxMesh JACK = new BoxMesh.Builder().box(-0.13F, -1.0F, -0.13F, 0.13F, 0.0F, 0.13F, STEEL).box(-0.3F, -1.08F, -0.3F, 0.3F, -1.0F, 0.3F, BLACK).build();

	public MobileLauncherRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 2.2F;
	}

	public static class State extends EntityRenderState {
		public float yaw;
		public float erect;
		public float wheelAngle;
		public @Nullable MissileType missile;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(MobileLauncherEntity entity) {
		return entity.getBoundingBox().inflate(6.0, 8.0, 6.0);
	}

	@Override
	public void extractRenderState(MobileLauncherEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.yaw = entity.getYRot(partialTick);
		state.erect = entity.getErect(partialTick);
		state.wheelAngle = Mth.lerp(partialTick, entity.wheelAngleO, entity.wheelAngle);
		state.missile = entity.getMissile();
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		int light = state.lightCoords;
		poseStack.pushPose();
		poseStack.mulPose(new Quaternionf().rotationY(-state.yaw * Mth.DEG_TO_RAD));

		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> BODY.emit(pose, consumer, light));

		for (float z : AXLES) {
			for (float x : new float[] {-1.15F, 1.15F}) {
				poseStack.pushPose();
				poseStack.translate(x, WHEEL_RADIUS, z);
				poseStack.mulPose(new Quaternionf().rotationX(state.wheelAngle));
				collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> WHEEL.emit(pose, consumer, light));
				poseStack.popPose();
			}
		}

		// stabiliser jacks go down before the erector rises
		float jack = Mth.clamp(state.erect * 4.0F, 0.0F, 1.0F);
		if (jack > 0.0F) {
			for (float[] p : new float[][] {{-1.2F, -4.6F}, {1.2F, -4.6F}, {-1.2F, 1.9F}, {1.2F, 1.9F}}) {
				poseStack.pushPose();
				poseStack.translate(p[0], 1.1F, p[1]);
				poseStack.scale(1.0F, 0.15F + 0.9F * jack, 1.0F);
				collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> JACK.emit(pose, consumer, light));
				poseStack.popPose();
			}
		}

		// erector: lies flat over the cab when stowed, swings up around the rear hinge
		float angle = (1.0F - state.erect) * Mth.HALF_PI;
		Quaternionf tilt = new Quaternionf().rotationX(angle);
		Vector3f pivot = new Vector3f(0, MobileLauncherEntity.PIVOT_Y, MobileLauncherEntity.PIVOT_Z);
		Vector3f ramTop = new Vector3f(0, 3.2F, RAIL_OFFSET + 0.3F).rotate(tilt).add(pivot);
		BoxMesh ram = new BoxMesh.Builder()
			.beam(new Vector3f(0, 1.5F, -0.6F), ramTop, 0.34F, 0.34F, STEEL)
			.beam(new Vector3f(0, 1.5F, -0.6F), new Vector3f(0, 1.5F, -0.6F).lerp(ramTop, 0.55F), 0.46F, 0.46F, RAIL)
			.build();
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> ram.emit(pose, consumer, light));

		poseStack.pushPose();
		poseStack.translate(pivot.x, pivot.y, pivot.z);
		poseStack.mulPose(tilt);
		collector.submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> ERECTOR.emit(pose, consumer, light));
		MissileType missile = state.missile;
		if (missile != null) {
			MissileMesh mesh = MissileMesh.of(missile);
			RenderType bodyType = MissileRenderer.bodyType(missile);
			float s = missile.scale;
			poseStack.pushPose();
			poseStack.scale(s, s, s);
			collector.submitCustomGeometry(poseStack, bodyType, (pose, consumer) -> mesh.emit(pose, consumer, light));
			poseStack.popPose();
		}
		poseStack.popPose();

		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	/** Chassis, cab and bed in truck space: x across, y up, z forward; origin on the ground. */
	private static BoxMesh buildBody() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		// frame and bed
		b.box(-0.9F, 0.75F, -5.0F, 0.9F, 1.1F, 5.3F, BLACK);
		b.box(-1.0F, 1.1F, -5.0F, 1.0F, 1.5F, 3.5F, DARK_OLIVE);
		b.box(-1.45F, 1.1F, -5.15F, 1.45F, 1.5F, -4.95F, HAZARD); // rear bumper
		b.box(-1.4F, 1.15F, -5.18F, -1.1F, 1.3F, -5.14F, RED);
		b.box(1.1F, 1.15F, -5.18F, 1.4F, 1.3F, -5.14F, RED);
		// cab
		b.box(-1.4F, 1.1F, 3.6F, 1.4F, 2.65F, 5.6F, OLIVE);
		b.box(-1.3F, 2.65F, 3.7F, 1.3F, 2.8F, 5.4F, DARK_OLIVE);
		b.box(-1.25F, 1.85F, 5.6F, 1.25F, 2.5F, 5.63F, GLASS);
		b.box(1.4F, 1.9F, 4.5F, 1.43F, 2.45F, 5.4F, GLASS);
		b.box(-1.43F, 1.9F, 4.5F, -1.4F, 2.45F, 5.4F, GLASS);
		b.box(-1.0F, 1.15F, 5.6F, 1.0F, 1.75F, 5.65F, GRILLE);
		b.box(-1.45F, 0.75F, 5.55F, 1.45F, 1.1F, 5.85F, BLACK);
		b.box(-1.3F, 1.25F, 5.6F, -1.0F, 1.5F, 5.67F, LAMP);
		b.box(1.0F, 1.25F, 5.6F, 1.3F, 1.5F, 5.67F, LAMP);
		b.box(-1.2F, 2.8F, 5.0F, -0.9F, 2.9F, 5.2F, ORANGE); // beacon lights
		b.box(0.9F, 2.8F, 5.0F, 1.2F, 2.9F, 5.2F, ORANGE);
		b.box(1.15F, 2.0F, 3.15F, 1.3F, 3.4F, 3.3F, BLACK); // exhaust stack
		// fenders over every axle, equipment lockers in between
		for (float z : AXLES) {
			if (z < 3.5F) {
				b.box(0.9F, 1.3F, z - 0.8F, 1.45F, 1.42F, z + 0.8F, OLIVE);
				b.box(-1.45F, 1.3F, z - 0.8F, -0.9F, 1.42F, z + 0.8F, OLIVE);
			}
		}
		b.box(1.0F, 1.1F, -1.3F, 1.4F, 1.75F, 1.6F, CAMO);
		b.box(-1.4F, 1.1F, -1.3F, -1.0F, 1.75F, 1.6F, CAMO);
		b.box(-1.0F, 1.5F, 0.4F, 1.0F, 2.1F, 1.8F, CAMO); // generator / fire-control module
		b.box(-0.9F, 2.1F, 0.5F, 0.9F, 2.16F, 1.7F, DARK_OLIVE); // module roof with vents
		for (float z = 0.65F; z < 1.6F; z += 0.25F) {
			b.box(-0.7F, 2.16F, z, 0.7F, 2.2F, z + 0.1F, GRILLE);
		}

		// --- cab details: door seams, steps, grab handles, mirrors, sun visor, roof hatch
		for (float side : new float[] {-1.0F, 1.0F}) {
			float x0 = side < 0 ? -1.43F : 1.4F;
			float x1 = side < 0 ? -1.4F : 1.43F;
			b.box(x0, 1.25F, 4.42F, x1, 2.5F, 4.46F, BLACK); // door seam
			b.box(x0, 1.25F, 5.4F, x1, 1.85F, 5.43F, BLACK);
			b.box(side * 1.4F - 0.15F, 0.95F, 4.45F, side * 1.4F + 0.15F, 1.0F, 5.3F, STEEL); // step
			b.box(side < 0 ? -1.5F : 1.43F, 1.5F, 4.35F, side < 0 ? -1.43F : 1.5F, 2.4F, 4.4F, STEEL); // grab handle
			// mirror arm and mirror head
			b.box(side < 0 ? -1.85F : 1.4F, 2.35F, 5.25F, side < 0 ? -1.4F : 1.85F, 2.4F, 5.3F, BLACK);
			b.box(side < 0 ? -1.95F : 1.8F, 1.95F, 5.2F, side < 0 ? -1.8F : 1.95F, 2.5F, 5.35F, BLACK);
			b.box(side < 0 ? -1.97F : 1.95F, 2.0F, 5.22F, side < 0 ? -1.95F : 1.97F, 2.45F, 5.33F, GLASS);
			// tool box below the cab
			b.box(side < 0 ? -1.45F : 1.0F, 0.85F, 3.65F, side < 0 ? -1.0F : 1.45F, 1.1F, 4.3F, DARK_OLIVE);
		}
		b.box(-1.35F, 2.55F, 5.6F, 1.35F, 2.62F, 5.85F, DARK_OLIVE); // sun visor
		b.box(-0.35F, 2.8F, 4.2F, 0.35F, 2.88F, 4.8F, BLACK); // roof hatch
		b.box(-0.05F, 2.8F, 3.85F, 0.05F, 4.3F, 3.95F, BLACK); // whip antenna
		b.box(0.55F, 2.8F, 3.85F, 0.62F, 3.6F, 3.92F, BLACK);
		b.box(-1.1F, 1.7F, 5.63F, -0.95F, 1.82F, 5.75F, STEEL); // tow hooks
		b.box(0.95F, 1.7F, 5.63F, 1.1F, 1.82F, 5.75F, STEEL);
		b.box(-1.35F, 1.2F, 5.67F, -0.95F, 1.55F, 5.72F, BLACK); // headlight guards
		b.box(0.95F, 1.2F, 5.67F, 1.35F, 1.55F, 5.72F, BLACK);
		b.box(-0.6F, 0.6F, 5.0F, 0.6F, 0.75F, 5.9F, BLACK); // front skid plate
		b.box(-1.0F, 2.0F, 3.2F, -0.85F, 3.4F, 3.35F, BLACK); // second exhaust stack
		b.box(-1.05F, 3.35F, 3.15F, -0.8F, 3.45F, 3.4F, STEEL);
		b.box(1.1F, 3.35F, 3.1F, 1.35F, 3.45F, 3.35F, STEEL);
		// spare wheels behind the cab and fuel tanks along the frame
		b.box(-0.95F, 1.5F, 2.95F, -0.35F, 2.7F, 3.45F, TIRE);
		b.box(-0.8F, 1.7F, 2.94F, -0.5F, 2.5F, 3.46F, RIM);
		b.box(1.0F, 0.8F, -1.25F, 1.38F, 1.1F, 0.6F, STEEL);
		b.box(-1.38F, 0.8F, -1.25F, -1.0F, 1.1F, 0.6F, STEEL);
		// rolled camouflage net on the lockers, jerrycans and a fire extinguisher
		b.box(1.05F, 1.75F, -1.25F, 1.4F, 2.0F, 1.55F, CAMO);
		b.box(-1.4F, 1.75F, -1.25F, -1.05F, 2.0F, 1.55F, CAMO);
		for (float z : new float[] {-2.9F, -3.2F}) {
			b.box(1.42F, 1.15F, z, 1.52F, 1.5F, z + 0.24F, DARK_OLIVE);
		}
		b.box(-1.52F, 1.15F, -3.1F, -1.42F, 1.55F, -2.95F, RED);
		// hydraulic lines from the module to the erector cylinder
		b.box(-0.2F, 1.5F, -0.6F, -0.1F, 1.58F, 0.4F, BLACK);
		b.box(0.1F, 1.5F, -0.6F, 0.2F, 1.58F, 0.4F, BLACK);
		// mudflaps behind every wheel and rear lights / reflectors
		for (float z : AXLES) {
			b.box(1.0F, 0.35F, z - 0.82F, 1.4F, 1.3F, z - 0.78F, BLACK);
			b.box(-1.4F, 0.35F, z - 0.82F, -1.0F, 1.3F, z - 0.78F, BLACK);
		}
		b.box(-1.0F, 1.15F, -5.18F, -0.85F, 1.3F, -5.14F, ORANGE);
		b.box(0.85F, 1.15F, -5.18F, 1.0F, 1.3F, -5.14F, ORANGE);
		// erector hinge tower at the rear and the front rest the missile lies on
		b.box(-0.6F, 1.5F, -4.75F, 0.6F, MobileLauncherEntity.PIVOT_Y - 0.1F, -3.85F, RAIL);
		b.box(-0.75F, MobileLauncherEntity.PIVOT_Y - 0.25F, -4.5F, 0.75F, MobileLauncherEntity.PIVOT_Y + 0.1F, -4.1F, STEEL);
		b.box(-0.5F, 1.5F, 2.9F, 0.5F, MobileLauncherEntity.PIVOT_Y - RAIL_OFFSET - 0.3F, 3.3F, RAIL);
		return b.build();
	}

	/** Erector in its own frame: y along the missile from the hinge, rails on the +z (truck) side. */
	private static BoxMesh buildErector() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		float r = RAIL_OFFSET;
		float length = 8.0F;
		b.box(-0.55F, 0.0F, r, -0.35F, length, r + 0.28F, RAIL);
		b.box(0.35F, 0.0F, r, 0.55F, length, r + 0.28F, RAIL);
		for (float y = 0.6F; y < length; y += 1.5F) {
			b.box(-0.35F, y, r + 0.08F, 0.35F, y + 0.15F, r + 0.22F, STEEL);
		}
		// base plate the missile stands on, and two cradle clamps
		b.box(-0.7F, -0.18F, -0.7F, 0.7F, 0.0F, r + 0.3F, RAIL);
		for (float y : new float[] {2.0F, 5.6F}) {
			b.box(-0.68F, y, -0.25F, -0.56F, y + 0.35F, r + 0.1F, STEEL);
			b.box(0.56F, y, -0.25F, 0.68F, y + 0.35F, r + 0.1F, STEEL);
		}
		return b.build();
	}
}
