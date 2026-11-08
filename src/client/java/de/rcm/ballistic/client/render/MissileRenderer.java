package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.MissileStages;
import de.rcm.ballistic.entity.MissileType;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class MissileRenderer extends EntityRenderer<MissileEntity, MissileRenderer.State> {
	public static final RenderType GLOW_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/glow.png"));
	static final RenderType PLASMA_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/plasma.png"));
	private static final Map<MissileType, RenderType> BODY_TYPES = new EnumMap<>(MissileType.class);

	static {
		for (MissileType type : MissileType.values()) {
			BODY_TYPES.put(type, RenderTypes.entityCutoutNoCull(BallisticMissiles.id("textures/entity/" + type.id + ".png")));
		}
	}

	public static RenderType bodyType(MissileType type) {
		return BODY_TYPES.get(type);
	}

	/**
	 * Orientation with the model's +Y along the nose, +X to the right and +Z up, so wings and the
	 * belly intake of the cruise missile stay level in horizontal flight.
	 */
	public static void orient(Quaternionf out, Vec3 dir) {
		Vector3f forward = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z).normalize();
		Vector3f right = new Vector3f(forward).cross(0, 1, 0);
		if (right.lengthSquared() < 1.0E-4F) {
			right.set(1, 0, 0);
		} else {
			right.normalize();
		}
		Vector3f up = new Vector3f(right).cross(forward).normalize();
		out.setFromNormalized(new Matrix3f(right, forward, up));
	}

	public MissileRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.6F;
	}

	public static class State extends EntityRenderState {
		public MissileType missileType = MissileType.CONVENTIONAL;
		public final Quaternionf rotation = new Quaternionf();
		public boolean engineOn;
		public boolean jet;
		public boolean inCanister;
		public boolean ignition;
		public float engineTime;
		/** Height of the missile (the plume balloons in thin air). */
		public double altitude;
		public float shakeX;
		public float shakeZ;
		/** 0..1 glow of the re-entry plasma sheath. */
		public float plasma;
		/** Night-time light dot seen from afar: strength (0..1) and distance to the camera. */
		public float nightGlow;
		public float distance;
		/** Stages separated so far, and warheads left on the MIRV bus (-1 = whole missile). */
		public int dropped;
		public int bus = -1;
	}

	/** Nozzle exits per model: {x, z, radius} in model space, all at y = 0. */
	private static float[][] nozzles(MissileType.Model model) {
		return switch (model) {
			case ICBM -> new float[][] {{0.212F, 0.212F, 0.21F}, {-0.212F, 0.212F, 0.21F}, {0.212F, -0.212F, 0.21F}, {-0.212F, -0.212F, 0.21F}};
			case HEAVY_ICBM -> new float[][] {{0.29F, 0.0F, 0.26F}, {-0.29F, 0.0F, 0.26F}};
			case CRUISE -> new float[][] {{0.0F, 0.0F, 0.16F}};
			case HYPERSONIC -> new float[][] {{0.0F, 0.0F, 0.38F}};
			case DRONE -> new float[0][]; // piston engine and propeller: no exhaust plume
			case JET -> new float[][] {{0.0F, 0.0F, 0.1F}};
			case RAMJET -> new float[][] {{0.0F, 0.0F, 0.12F}};
			default -> new float[][] {{0.0F, 0.0F, 0.45F}};
		};
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(MissileEntity entity) {
		return entity.getBoundingBox().inflate(entity.getMissileType().length + 2.0);
	}

	@Override
	public void extractRenderState(MissileEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.missileType = entity.getMissileType();
		boolean ballisticFlight = entity.getState() == MissileEntity.FLIGHT && !state.missileType.isCruise() && entity.getFailure() == MissileEntity.FAIL_NONE;
		state.dropped = ballisticFlight ? MissileStages.dropped(state.missileType, entity.getTrajectory(), entity.clientStateAge + partialTick) : 0;
		state.bus = entity.getBusWarheads();
		Vec3 dir = entity.getNoseDirection(partialTick);
		orient(state.rotation, dir);
		state.engineOn = entity.isBoosterBurning();
		state.jet = entity.isJetRunning() && !state.engineOn;
		// a slim missile waiting in an inclined launcher's closed canister: out of sight until it fires
		state.inCanister = entity.getState() <= MissileEntity.IGNITION && entity.getNoseDirection(partialTick).y < 0.95;
		state.ignition = entity.getState() == MissileEntity.IGNITION;
		state.engineTime = entity.clientStateAge + partialTick;
		state.altitude = entity.getY();
		if (state.ignition) {
			// Hold-down shudder while the engine spools up.
			float t = state.engineTime;
			state.shakeX = Mth.sin(t * 2.7F) * 0.025F;
			state.shakeZ = Mth.cos(t * 3.3F) * 0.025F;
		} else {
			state.shakeX = 0;
			state.shakeZ = 0;
		}
		state.plasma = entity.getState() == MissileEntity.FLIGHT && !entity.getMissileType().isCruise() && entity.getFailure() == MissileEntity.FAIL_NONE
			? plasmaIntensity(entity, partialTick) : 0.0F;
		// at night a burning motor (or the re-entry glow) is seen from far away as an orange point
		state.distance = (float) Math.sqrt(state.distanceToCameraSq);
		float burning = state.engineOn ? 1.0F : state.plasma > 0.05F ? state.plasma * 0.9F : 0.0F;
		state.nightGlow = burning * night(entity.level().getDayTime()) * smoothstep(25.0F, 90.0F, state.distance);
		if (state.engineOn || state.jet) {
			// The exhaust lights up the airframe.
			state.lightCoords = LightTexture.pack(15, LightTexture.sky(state.lightCoords));
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.inCanister) {
			return;
		}
		MissileMesh mesh = MissileMesh.stack(state.missileType, state.dropped);
		float stageBottom = MissileStages.bottom(state.missileType, state.dropped);
		RenderType bodyType = BODY_TYPES.get(state.missileType);
		int light = state.lightCoords;
		float scale = state.missileType.scale;

		poseStack.pushPose();
		poseStack.translate(state.shakeX, 0.0F, state.shakeZ);
		poseStack.mulPose(state.rotation);
		poseStack.scale(scale, scale, scale);
		if (state.bus >= 0) {
			// MIRV bus: the shroud is gone; the post-boost vehicle with its remaining warheads
			MissileMesh busMesh = MissileMesh.bus(state.missileType);
			collector.submitCustomGeometry(poseStack, bodyType, (pose, consumer) -> busMesh.emit(pose, consumer, light));
			float top = MissileMesh.busTop(state.missileType);
			for (int i = 0; i < state.bus; i++) {
				float a = i * Mth.TWO_PI / 4.0F + 0.4F;
				float r = i == 4 ? 0.0F : 0.28F;
				poseStack.pushPose();
				poseStack.translate(Mth.cos(a) * r, top, Mth.sin(a) * r);
				poseStack.scale(0.4F, 0.4F, 0.4F);
				collector.submitCustomGeometry(poseStack, ProjectileRenderer.RV_TYPE, (pose, consumer) -> MissileMesh.REENTRY_VEHICLE.emit(pose, consumer, light));
				poseStack.popPose();
			}
			poseStack.popPose();
			super.submit(state, poseStack, collector, camera);
			return;
		}
		collector.submitCustomGeometry(poseStack, bodyType, (pose, consumer) -> mesh.emit(pose, consumer, light));
		boolean cruise = state.missileType.isCruise();
		boolean slim = state.missileType.slim();
		if (cruise && state.engineOn) {
			// the slim jet missile has a slimmer booster under its tail
			poseStack.pushPose();
			if (slim) {
				poseStack.scale(0.62F, 0.8F, 0.62F);
			}
			collector.submitCustomGeometry(poseStack, bodyType, (pose, consumer) -> MissileMesh.CRUISE_BOOSTER.emit(pose, consumer, light));
			poseStack.popPose();
		}
		if (state.missileType.model == MissileType.Model.DRONE) {
			// the propeller windmills during the boost and spins up to a blur once the engine runs
			float spin = state.jet || state.engineOn ? state.engineTime * 2.6F : 0.4F;
			poseStack.pushPose();
			poseStack.translate(0.0F, -0.12F, 0.0F);
			poseStack.mulPose(new Quaternionf().rotationY(spin));
			collector.submitCustomGeometry(poseStack, bodyType, (pose, consumer) -> MissileMesh.DRONE_PROP.emit(pose, consumer, light));
			poseStack.popPose();
		}
		if (state.plasma > 0.01F) {
			int a = (int) (Math.min(1.0F, state.plasma) * 255.0F);
			int color = a << 24 | 0xFFFFFF;
			poseStack.pushPose();
			poseStack.scale(1.06F, 1.012F, 1.06F);
			collector.submitCustomGeometry(poseStack, PLASMA_TYPE, (pose, consumer) -> mesh.emit(pose, consumer, LightTexture.FULL_BRIGHT, color));
			poseStack.popPose();
		}

		if (state.engineOn || state.jet) {
			float t = state.engineTime;
			float flicker = 0.85F + 0.15F * Mth.sin(t * 1.9F) * Mth.cos(t * 3.1F);
			float[][] exits = nozzles(state.missileType.model);
			float exitY = 0.0F;
			if (state.dropped > 0) {
				// an upper stage burning: one nozzle at the bottom of what is left
				float r = MissileStages.radii(state.missileType)[state.dropped - 1];
				exits = new float[][] {{0.0F, 0.0F, r * 0.32F}};
				exitY = stageBottom - 0.5F;
			}
			if (cruise && state.engineOn) {
				exits = new float[][] {{0.0F, 0.0F, slim ? 0.11F : 0.17F}};
				exitY = (-MissileMesh.CRUISE_BOOSTER_LENGTH - 0.28F) * (slim ? 0.8F : 1.0F);
			} else if (slim && state.jet) {
				exitY = -0.09F;
			}
			for (float[] exit : exits) {
				// turbofan: only a faint hot glow instead of a rocket plume
				float nozzle = state.jet ? exit[2] * (slim ? 0.9F : 0.7F) : exit[2];
				// the slim missile's turbojet runs hot: a short orange-blue jet flame you can see
				float flameLength = state.jet
					? (slim ? 1.25F * flicker : 0.55F * flicker)
					: (state.ignition ? Math.min(1.0F, t / 30.0F) * 3.5F : 6.5F) * flicker * Mth.sqrt(exit[2] / 0.34F) * (exits.length > 1 ? 1.1F : 1.0F);
				float phase = exit[0] * 13.0F + exit[1] * 7.0F;
				poseStack.pushPose();
				poseStack.translate(exit[0], exitY, exit[1]);
				// thin air high up: the plume balloons out
				float expand = 1.0F + Mth.clamp((float) (state.altitude - 120.0) / 300.0F, 0.0F, 1.2F);
				int style = state.jet ? ExhaustPlume.JET : ExhaustPlume.ROCKET;
				collector.submitCustomGeometry(poseStack, ExhaustPlume.TYPE,
					(pose, consumer) -> ExhaustPlume.plume(pose, consumer, nozzle, flameLength, t + phase, 1.0F, style, expand));
				poseStack.popPose();
			}
		}
		poseStack.popPose();
		if (state.nightGlow > 0.01F && camera.orientation != null) {
			// camera-facing glow at the nozzle, sized with distance so it stays a visible point
			float r = Math.max(1.5F, state.distance * 0.008F) * (0.9F + 0.1F * Mth.sin(state.engineTime * 1.7F));
			int alpha = (int) (Mth.clamp(state.nightGlow, 0.0F, 1.0F) * 255.0F);
			poseStack.pushPose();
			poseStack.mulPose(camera.orientation);
			collector.submitCustomGeometry(poseStack, GLOW_TYPE, (pose, consumer) -> {
				glowQuad(pose, consumer, r, alpha << 24 | 0xFFB060);
				glowQuad(pose, consumer, r * 2.6F, (alpha / 3) << 24 | 0xFF7A20); // haze around it
			});
			poseStack.popPose();
		}
		super.submit(state, poseStack, collector, camera);
	}

	static void glowQuad(PoseStack.Pose pose, VertexConsumer consumer, float r, int color) {
		float[][] c = {{-r, -r, 0, 1}, {r, -r, 1, 1}, {r, r, 1, 0}, {-r, r, 0, 0}};
		for (float[] v : c) {
			consumer.addVertex(pose, v[0], v[1], 0.0F).setColor(color).setUv(v[2], v[3]).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 0.0F, 1.0F);
		}
	}

	/** 0 by day, 1 at night, with dusk and dawn in between (day time in ticks). */
	private static float night(long dayTime) {
		float t = dayTime % 24000L;
		if (t < 12000.0F) {
			return 0.0F;
		}
		if (t < 13800.0F) {
			return (t - 12000.0F) / 1800.0F;
		}
		if (t < 22200.0F) {
			return 1.0F;
		}
		return Math.max(0.0F, (24000.0F - t) / 1800.0F);
	}

	private static float smoothstep(float edge0, float edge1, float x) {
		float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	/**
	 * Re-entry heating: every ballistic missile glows in its terminal dive; the hypersonic missile
	 * flies wrapped in plasma for most of its flight.
	 */
	private static float plasmaIntensity(MissileEntity entity, float partialTick) {
		MissileType type = entity.getMissileType();
		int duration = entity.getTrajectory().duration();
		float t = (entity.clientStateAge + partialTick) / Math.max(1, duration);
		float glow = type.warhead == MissileType.Warhead.HYPERSONIC
			? Mth.clamp((t - 0.18F) / 0.15F, 0.0F, 1.0F)
			: Mth.clamp((t - 0.72F) / 0.18F, 0.0F, 1.0F) * 0.8F;
		float flicker = 0.85F + 0.15F * Mth.sin((entity.clientStateAge + partialTick) * 2.3F);
		return glow * flicker;
	}

	/** Exhaust plume pointing down the -Y axis (see {@link ExhaustPlume}). */
	static void flame(PoseStack.Pose pose, VertexConsumer consumer, float radius, float length, float time, float alpha) {
		ExhaustPlume.plume(pose, consumer, radius, length, time, alpha);
	}

}
