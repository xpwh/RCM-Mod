package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.client.effect.Storm;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Lightning bolts, drawn as light: each segment of the channel a soft ribbon turned to the eye, added onto what is
 * behind it - a white-hot core and a wide blue-violet glow round it, the leader faint and violet, the branches
 * thinner and fading sooner. Thin as the channel is, it never shrinks below a pixel or two, so a strike miles off
 * still cuts across the sky.
 */
public final class LightningRenderer {
	/** The narrowest a channel is drawn, as a fraction of its distance (about two pixels at a normal field of view). */
	private static final float MIN_ANGLE = 0.0016F;

	private LightningRenderer() {
	}

	public static void draw(WorldRenderContext context, List<Storm.Bolt> bolts) {
		if (!(context.consumers() instanceof MultiBufferSource.BufferSource buffers)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		VertexConsumer out = buffers.getBuffer(RenderTypes.lightning());
		Matrix4f m = context.matrices().last().pose();
		Vector3f a = new Vector3f();
		Vector3f b = new Vector3f();
		Vector3f side = new Vector3f();
		Vector3f eye = new Vector3f();
		for (Storm.Bolt bolt : bolts) {
			float t = bolt.age + partial;
			if (t > bolt.life) {
				continue;
			}
			float ox = (float) (bolt.origin.x - cam.x);
			float oy = (float) (bolt.origin.y - cam.y);
			float oz = (float) (bolt.origin.z - cam.z);
			for (float[] s : bolt.segments) {
				float[] l = bolt.light((int) s[7], s[8], t);
				float k = l[0];
				if (k < 0.01F) {
					continue;
				}
				a.set(s[0] + ox, s[1] + oy, s[2] + oz);
				b.set(s[3] + ox, s[4] + oy, s[5] + oz);
				// across the segment, square to the line of sight
				eye.set(a).add(b).mul(0.5F);
				float dist = Math.max(eye.length(), 1.0F);
				b.sub(a, side);
				side.cross(eye);
				if (side.lengthSquared() < 1.0E-8F) {
					continue;
				}
				side.normalize();
				float w = s[6];
				float minW = dist * MIN_ANGLE;
				float violet = l[1];
				// the glow: wide, blue-violet, soft
				float glowW = Math.max(1.6F * w, minW * 6.0F);
				int glowRgb = violet > 0.5F ? 0x8A6CFF : 0xA8B4FF;
				ribbon(out, m, a, b, side, glowW, glowRgb, k * 0.35F);
				// the channel: white-hot, a little blue at the edges
				float coreW = Math.max(0.22F * w, minW);
				int coreRgb = violet > 0.5F ? 0xC8B8FF : 0xF4F6FF;
				ribbon(out, m, a, b, side, coreW, coreRgb, Mth.clamp(k * 1.6F, 0.0F, 1.0F));
			}
		}
		buffers.endBatch(RenderTypes.lightning());
	}

	/** A strip from a to b, {@code w} wide: bright along its middle, nothing at its edges. */
	private static void ribbon(VertexConsumer out, Matrix4f m, Vector3f a, Vector3f b, Vector3f side, float w, int rgb, float alpha) {
		int on = Math.round(Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24 | rgb;
		int off = rgb;
		float sx = side.x * w;
		float sy = side.y * w;
		float sz = side.z * w;
		// two quads: edge - middle - edge
		quad(out, m, a.x - sx, a.y - sy, a.z - sz, off, a.x, a.y, a.z, on, b.x, b.y, b.z, on, b.x - sx, b.y - sy, b.z - sz, off);
		quad(out, m, a.x, a.y, a.z, on, a.x + sx, a.y + sy, a.z + sz, off, b.x + sx, b.y + sy, b.z + sz, off, b.x, b.y, b.z, on);
	}

	private static void quad(VertexConsumer out, Matrix4f m, float x0, float y0, float z0, int c0, float x1, float y1, float z1, int c1, float x2, float y2,
		float z2, int c2, float x3, float y3, float z3, int c3) {
		out.addVertex(m, x0, y0, z0).setColor(c0);
		out.addVertex(m, x1, y1, z1).setColor(c1);
		out.addVertex(m, x2, y2, z2).setColor(c2);
		out.addVertex(m, x3, y3, z3).setColor(c3);
	}
}
