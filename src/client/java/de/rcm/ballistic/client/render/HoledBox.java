package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.client.mixin.ModelPartAccessor;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Vector3fc;

/**
 * A part of the player model drawn as the game draws it - its boxes in the skin - but with a piece cut
 * out: every face split into half-pixel cells, and the cells inside the cut left out. Where a round went
 * right through the body, you look through the hole; where the jaw was shot off, it is gone.
 */
public final class HoledBox {
	/** Cell size, pixels. */
	private static final float CELL = 0.5F;

	private HoledBox() {
	}

	/** The part's own boxes (not its children), the pose stack already at the part (translateAndRotate). */
	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, ModelPart part, RenderType type, int light, int overlay, int color,
		GoreMesh.Cut cut) {
		var cubes = ((ModelPartAccessor) (Object) part).ballisticmissiles$cubes();
		if (cubes.isEmpty()) {
			return;
		}
		collector.submitCustomGeometry(poseStack, type, (pose, consumer) -> {
			for (ModelPart.Cube cube : cubes) {
				for (ModelPart.Polygon polygon : cube.polygons) {
					face(pose, consumer, polygon, light, overlay, color, cut);
				}
			}
		});
	}

	private static void face(PoseStack.Pose pose, VertexConsumer consumer, ModelPart.Polygon polygon, int light, int overlay, int color, GoreMesh.Cut cut) {
		ModelPart.Vertex[] v = polygon.vertices();
		Vector3fc n = polygon.normal();
		float ea = (float) Math.sqrt(sq(v[1].x() - v[0].x()) + sq(v[1].y() - v[0].y()) + sq(v[1].z() - v[0].z()));
		float eb = (float) Math.sqrt(sq(v[3].x() - v[0].x()) + sq(v[3].y() - v[0].y()) + sq(v[3].z() - v[0].z()));
		int na = Math.max(1, (int) Math.ceil(ea / CELL));
		int nb = Math.max(1, (int) Math.ceil(eb / CELL));
		float[] c = new float[5];
		for (int i = 0; i < na; i++) {
			for (int j = 0; j < nb; j++) {
				float s0 = i / (float) na;
				float s1 = (i + 1) / (float) na;
				float t0 = j / (float) nb;
				float t1 = (j + 1) / (float) nb;
				lerp(v, (s0 + s1) * 0.5F, (t0 + t1) * 0.5F, c);
				if (cut != null && cut.test(c[0], c[1], c[2])) {
					continue;
				}
				corner(pose, consumer, v, s0, t0, n, light, overlay, color, c);
				corner(pose, consumer, v, s1, t0, n, light, overlay, color, c);
				corner(pose, consumer, v, s1, t1, n, light, overlay, color, c);
				corner(pose, consumer, v, s0, t1, n, light, overlay, color, c);
			}
		}
	}

	private static void corner(PoseStack.Pose pose, VertexConsumer consumer, ModelPart.Vertex[] v, float s, float t, Vector3fc n, int light, int overlay,
		int color, float[] c) {
		lerp(v, s, t, c);
		consumer.addVertex(pose, c[0] / 16.0F, c[1] / 16.0F, c[2] / 16.0F).setColor(color).setUv(c[3], c[4]).setOverlay(overlay).setLight(light)
			.setNormal(pose, n.x(), n.y(), n.z());
	}

	/** The point at (s, t) across the face (s from corner 0 to 1, t from 0 to 3): pixels, then u, v. */
	private static void lerp(ModelPart.Vertex[] v, float s, float t, float[] out) {
		float a = (1 - s) * (1 - t);
		float b = s * (1 - t);
		float cc = s * t;
		float d = (1 - s) * t;
		out[0] = v[0].x() * a + v[1].x() * b + v[2].x() * cc + v[3].x() * d;
		out[1] = v[0].y() * a + v[1].y() * b + v[2].y() * cc + v[3].y() * d;
		out[2] = v[0].z() * a + v[1].z() * b + v[2].z() * cc + v[3].z() * d;
		out[3] = v[0].u() * a + v[1].u() * b + v[2].u() * cc + v[3].u() * d;
		out[4] = v[0].v() * a + v[1].v() * b + v[2].v() * cc + v[3].v() * d;
	}

	private static float sq(float x) {
		return x * x;
	}
}
