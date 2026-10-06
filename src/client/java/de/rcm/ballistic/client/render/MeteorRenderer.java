package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.MOON_ROCK;
import static de.rcm.ballistic.client.render.StructureKit.v;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.entity.MeteorEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Tumbling, glowing chunk of rock with a fiery entry plume streaming behind it. */
public class MeteorRenderer extends EntityRenderer<MeteorEntity, MeteorRenderer.State> {
	private static final RenderType FLAME_TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/entity/exhaust_flame.png"));
	private static final BoxMesh ROCK = buildRock();

	public MeteorRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	public static class State extends EntityRenderState {
		public final Quaternionf heading = new Quaternionf();
		public float spin;
		public float size;
		public float time;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	protected AABB getBoundingBoxForCulling(MeteorEntity entity) {
		return entity.getBoundingBox().inflate(12.0);
	}

	@Override
	public void extractRenderState(MeteorEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		Vec3 v = entity.getDeltaMovement();
		MissileRenderer.orient(state.heading, v.lengthSqr() < 1.0E-6 ? new Vec3(0, -1, 0) : v.normalize());
		state.spin = (entity.spin + partialTick * 9.0F) * Mth.DEG_TO_RAD;
		state.size = entity.getSize();
		state.time = entity.tickCount + partialTick;
		state.lightCoords = LightTexture.FULL_BRIGHT;
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		float s = state.size;
		float t = state.time;
		poseStack.pushPose();
		poseStack.translate(0.0F, 0.6F, 0.0F);
		poseStack.pushPose();
		poseStack.mulPose(new Quaternionf().rotationXYZ(state.spin, state.spin * 0.7F, state.spin * 0.4F));
		poseStack.scale(s, s, s);
		collector.submitCustomGeometry(poseStack, StructureKit.TYPE, (pose, consumer) -> ROCK.emit(pose, consumer, LightTexture.FULL_BRIGHT));
		poseStack.popPose();
		// orient() puts +Y along the flight path; the flame mesh grows down -Y, i.e. behind the rock
		poseStack.mulPose(state.heading);
		collector.submitCustomGeometry(poseStack, FLAME_TYPE, (pose, consumer) -> {
			MissileRenderer.flame(pose, consumer, 0.9F * s, 9.0F * s, t, 1.0F);
			MissileRenderer.flame(pose, consumer, 1.4F * s, 4.0F * s, t + 3.0F, 0.7F);
		});
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}

	/** Lumpy rock: a few overlapping, skewed blocks. */
	private static BoxMesh buildRock() {
		BoxMesh.Builder b = new BoxMesh.Builder();
		b.hexa(MOON_ROCK,
			v(-0.6F, -0.5F, -0.55F), v(0.55F, -0.6F, -0.5F), v(0.6F, 0.5F, -0.6F), v(-0.5F, 0.55F, -0.5F),
			v(-0.55F, -0.55F, 0.6F), v(0.6F, -0.5F, 0.5F), v(0.5F, 0.6F, 0.55F), v(-0.6F, 0.5F, 0.6F));
		b.hexa(MOON_ROCK,
			v(-0.3F, -0.75F, -0.3F), v(0.35F, -0.7F, -0.25F), v(0.3F, 0.7F, -0.35F), v(-0.35F, 0.75F, -0.3F),
			v(-0.3F, -0.7F, 0.35F), v(0.3F, -0.75F, 0.3F), v(0.35F, 0.7F, 0.3F), v(-0.3F, 0.72F, 0.3F));
		b.hexa(MOON_ROCK,
			v(-0.75F, -0.3F, -0.3F), v(0.72F, -0.35F, -0.3F), v(0.7F, 0.3F, -0.35F), v(-0.7F, 0.35F, -0.3F),
			v(-0.72F, -0.3F, 0.3F), v(0.75F, -0.3F, 0.35F), v(0.7F, 0.35F, 0.3F), v(-0.75F, 0.3F, 0.3F));
		return b.build();
	}
}
