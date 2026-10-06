package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.MissileEntity;
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
	private static final Identifier FLAME_TEXTURE = BallisticMissiles.id("textures/entity/exhaust_flame.png");
	private static final RenderType FLAME_TYPE = RenderTypes.entityTranslucentEmissive(FLAME_TEXTURE);
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
		public boolean ignition;
		public float engineTime;
		public float shakeX;
		public float shakeZ;
		/** 0..1 glow of the re-entry plasma sheath. */
		public float plasma;
	}

	/** Nozzle exits per model: {x, z, radius} in model space, all at y = 0. */
	private static float[][] nozzles(MissileType.Model model) {
		return switch (model) {
			case ICBM -> new float[][] {{0.212F, 0.212F, 0.21F}, {-0.212F, 0.212F, 0.21F}, {0.212F, -0.212F, 0.21F}, {-0.212F, -0.212F, 0.21F}};
			case HEAVY_ICBM -> new float[][] {{0.29F, 0.0F, 0.26F}, {-0.29F, 0.0F, 0.26F}};
			case CRUISE -> new float[][] {{0.0F, 0.0F, 0.16F}};
			case HYPERSONIC -> new float[][] {{0.0F, 0.0F, 0.38F}};
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
		Vec3 dir = entity.getNoseDirection(partialTick);
		orient(state.rotation, dir);
		state.engineOn = entity.isBoosterBurning();
		state.jet = entity.isJetRunning() && !state.engineOn;
		state.ignition = entity.getState() == MissileEntity.IGNITION;
		state.engineTime = entity.clientStateAge + partialTick;
		if (state.ignition) {
			// Hold-down shudder while the engine spools up.
			float t = state.engineTime;
			state.shakeX = Mth.sin(t * 2.7F) * 0.025F;
			state.shakeZ = Mth.cos(t * 3.3F) * 0.025F;
		} else {
			state.shakeX = 0;
			state.shakeZ = 0;
		}
		state.plasma = entity.getState() == MissileEntity.FLIGHT && !entity.getMissileType().isCruise() ? plasmaIntensity(entity, partialTick) : 0.0F;
		if (state.engineOn || state.jet) {
			// The exhaust lights up the airframe.
			state.lightCoords = LightTexture.pack(15, LightTexture.sky(state.lightCoords));
		}
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		MissileMesh mesh = MissileMesh.of(state.missileType);
		RenderType bodyType = BODY_TYPES.get(state.missileType);
		int light = state.lightCoords;
		float scale = state.missileType.scale;

		poseStack.pushPose();
		poseStack.translate(state.shakeX, 0.0F, state.shakeZ);
		poseStack.mulPose(state.rotation);
		poseStack.scale(scale, scale, scale);
		collector.submitCustomGeometry(poseStack, bodyType, (pose, consumer) -> mesh.emit(pose, consumer, light));
		boolean cruise = state.missileType.isCruise();
		if (cruise && state.engineOn) {
			collector.submitCustomGeometry(poseStack, bodyType, (pose, consumer) -> MissileMesh.CRUISE_BOOSTER.emit(pose, consumer, light));
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
			if (cruise && state.engineOn) {
				exits = new float[][] {{0.0F, 0.0F, 0.17F}};
				exitY = -MissileMesh.CRUISE_BOOSTER_LENGTH - 0.28F;
			}
			for (float[] exit : exits) {
				// turbofan: only a faint hot glow instead of a rocket plume
				float nozzle = state.jet ? exit[2] * 0.7F : exit[2];
				float flameLength = state.jet
					? 0.55F * flicker
					: (state.ignition ? Math.min(1.0F, t / 30.0F) * 3.5F : 6.5F) * flicker * Mth.sqrt(exit[2] / 0.34F) * (exits.length > 1 ? 1.1F : 1.0F);
				float phase = exit[0] * 13.0F + exit[1] * 7.0F;
				poseStack.pushPose();
				poseStack.translate(exit[0], exitY, exit[1]);
				collector.submitCustomGeometry(poseStack, FLAME_TYPE, (pose, consumer) -> {
					flame(pose, consumer, nozzle, flameLength, t + phase, 1.0F);
					flame(pose, consumer, nozzle * 0.55F, flameLength * 0.6F, t + phase + 7.0F, 1.0F);
				});
				poseStack.popPose();
			}
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
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

	/** Exhaust plume: a bulging, flickering cone of revolution pointing down the -Y axis. */
	static void flame(PoseStack.Pose pose, VertexConsumer consumer, float radius, float length, float time, float alpha) {
		final int segments = 16;
		final int rings = 7;
		int fullBright = LightTexture.FULL_BRIGHT;
		for (int ring = 0; ring < rings; ring++) {
			float f0 = (float) ring / rings;
			float f1 = (float) (ring + 1) / rings;
			float y0 = -f0 * length;
			float y1 = -f1 * length;
			float r0 = plumeRadius(radius, f0, time);
			float r1 = plumeRadius(radius, f1, time);
			for (int s = 0; s < segments; s++) {
				float a0 = Mth.TWO_PI * s / segments;
				float a1 = Mth.TWO_PI * (s + 1) / segments;
				float u0 = (float) s / segments;
				float u1 = (float) (s + 1) / segments;
				int c0 = color(f0, alpha);
				int c1 = color(f1, alpha);
				vertex(consumer, pose, Mth.cos(a0) * r0, y0, Mth.sin(a0) * r0, u0, f0, c0, fullBright, Mth.cos(a0), Mth.sin(a0));
				vertex(consumer, pose, Mth.cos(a1) * r0, y0, Mth.sin(a1) * r0, u1, f0, c0, fullBright, Mth.cos(a1), Mth.sin(a1));
				vertex(consumer, pose, Mth.cos(a1) * r1, y1, Mth.sin(a1) * r1, u1, f1, c1, fullBright, Mth.cos(a1), Mth.sin(a1));
				vertex(consumer, pose, Mth.cos(a0) * r1, y1, Mth.sin(a0) * r1, u0, f1, c1, fullBright, Mth.cos(a0), Mth.sin(a0));
			}
		}
	}

	private static float plumeRadius(float nozzle, float f, float time) {
		// Expands right after the nozzle (under-expanded exhaust), then tapers off.
		float bulge = Mth.sin(Mth.PI * Math.min(1.0F, f * 1.6F)) * 0.6F;
		float diamond = 0.08F * Mth.sin(f * 22.0F - time * 1.4F);
		return nozzle * (1.0F + bulge + diamond) * (1.0F - f * 0.7F);
	}

	private static int color(float f, float alpha) {
		int a = (int) (255 * alpha * (1.0F - f) * (1.0F - f * 0.3F));
		return Math.max(0, Math.min(255, a)) << 24 | 0xFFFFFF;
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int color, int light, float nx, float nz) {
		consumer.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, 0.0F, nz);
	}
}
