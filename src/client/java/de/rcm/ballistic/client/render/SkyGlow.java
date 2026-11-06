package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Light sources seen high in the sky: a camera-facing point of fire that keeps its apparent size
 * however far away it is, and a glowing streak trailing behind it.
 */
final class SkyGlow {
	private SkyGlow() {
	}

	/** Apparent radius for a point that should look about {@code angular} wide at any distance. */
	static float size(float distance, float min, float angular) {
		return Math.max(min, distance * angular);
	}

	/** A glowing point at the pose origin: hot core, bright halo, faint wide haze. */
	static void point(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, float r, int core, int halo, float alpha) {
		if (camera.orientation == null || alpha <= 0.01F) {
			return;
		}
		int a = (int) (Math.min(1.0F, alpha) * 255.0F);
		poseStack.pushPose();
		poseStack.mulPose(camera.orientation);
		collector.submitCustomGeometry(poseStack, MissileRenderer.GLOW_TYPE, (pose, consumer) -> {
			MissileRenderer.glowQuad(pose, consumer, r * 3.2F, (a / 4) << 24 | (halo & 0xFFFFFF));
			MissileRenderer.glowQuad(pose, consumer, r * 1.6F, (a * 2 / 3) << 24 | (halo & 0xFFFFFF));
			MissileRenderer.glowQuad(pose, consumer, r * 0.8F, a << 24 | (core & 0xFFFFFF));
		});
		poseStack.popPose();
	}

	/**
	 * A streak from the pose origin back along {@code back} (unit, pose space) for {@code length},
	 * turned to face the camera ({@code toCamera} from the origin), fading out along its length.
	 */
	static void streak(PoseStack poseStack, SubmitNodeCollector collector, Vec3 toCamera, Vec3 back, float length, float width, int color, float alpha) {
		if (alpha <= 0.01F || length <= 0.1F) {
			return;
		}
		Vector3f axis = new Vector3f((float) back.x, (float) back.y, (float) back.z).normalize();
		Vector3f view = new Vector3f((float) toCamera.x, (float) toCamera.y, (float) toCamera.z);
		Vector3f side = new Vector3f(axis).cross(view);
		if (side.lengthSquared() < 1.0E-6F) {
			return;
		}
		side.normalize();
		int a = (int) (Math.min(1.0F, alpha) * 255.0F);
		int rgb = color & 0xFFFFFF;
		int segments = 8;
		collector.submitCustomGeometry(poseStack, MissileRenderer.GLOW_TYPE, (pose, consumer) -> {
			for (int i = 0; i < segments; i++) {
				float f0 = (float) i / segments;
				float f1 = (float) (i + 1) / segments;
				float w0 = width * (1.0F - 0.75F * f0);
				float w1 = width * (1.0F - 0.75F * f1);
				int c0 = (int) (a * (1.0F - f0) * (1.0F - f0)) << 24 | rgb;
				int c1 = (int) (a * (1.0F - f1) * (1.0F - f1)) << 24 | rgb;
				Vector3f p0 = new Vector3f(axis).mul(length * f0);
				Vector3f p1 = new Vector3f(axis).mul(length * f1);
				Vector3f a0 = new Vector3f(p0).add(new Vector3f(side).mul(w0));
				Vector3f b0 = new Vector3f(p0).sub(new Vector3f(side).mul(w0));
				Vector3f b1 = new Vector3f(p1).sub(new Vector3f(side).mul(w1));
				Vector3f a1 = new Vector3f(p1).add(new Vector3f(side).mul(w1));
				// both windings, so it shows whichever way it is turned
				vertex(consumer, pose, a0, 0.0F, 0.5F, c0);
				vertex(consumer, pose, b0, 1.0F, 0.5F, c0);
				vertex(consumer, pose, b1, 1.0F, 0.5F, c1);
				vertex(consumer, pose, a1, 0.0F, 0.5F, c1);
				vertex(consumer, pose, a1, 0.0F, 0.5F, c1);
				vertex(consumer, pose, b1, 1.0F, 0.5F, c1);
				vertex(consumer, pose, b0, 1.0F, 0.5F, c0);
				vertex(consumer, pose, a0, 0.0F, 0.5F, c0);
			}
		});
	}

	private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer consumer, PoseStack.Pose pose, Vector3f p, float u, float v, int color) {
		consumer.addVertex(pose, p.x, p.y, p.z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
