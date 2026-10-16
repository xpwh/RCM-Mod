package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.BEAM;
import static de.rcm.ballistic.client.render.StructureKit.BEAM_HAZE;
import static de.rcm.ballistic.client.render.StructureKit.FLASH;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.client.effect.Storm;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Lightning bolts: the white-hot channel, a blue-violet glow round it, the branches thinner and fainter, all
 * flickering as the return strokes run up it again.
 */
public final class LightningRenderer {
	private static final Map<Storm.Bolt, BoxMesh[]> MESHES = new WeakHashMap<>();

	private LightningRenderer() {
	}

	public static void draw(WorldRenderContext context, List<Storm.Bolt> bolts) {
		if (!(context.consumers() instanceof MultiBufferSource.BufferSource buffers)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		for (Storm.Bolt bolt : bolts) {
			float k = bolt.brightness(partial);
			if (k <= 0.01F) {
				continue;
			}
			BoxMesh[] mesh = MESHES.computeIfAbsent(bolt, LightningRenderer::build);
			PoseStack ps = new PoseStack();
			ps.last().pose().set(context.matrices().last().pose());
			ps.last().normal().set(context.matrices().last().normal());
			ps.translate((float) (bolt.origin.x - cam.x), (float) (bolt.origin.y - cam.y), (float) (bolt.origin.z - cam.z));
			int haze = Math.round(Mth.clamp(k * 0.55F, 0.0F, 1.0F) * 255.0F) << 24 | 0xB4BEFF;
			int core = Math.round(Mth.clamp(k, 0.0F, 1.0F) * 255.0F) << 24 | 0xFFFFFF;
			mesh[0].emit(ps.last(), buffers.getBuffer(StructureKit.GLOW_TYPE), LightTexture.FULL_BRIGHT, haze);
			mesh[1].emit(ps.last(), buffers.getBuffer(StructureKit.GLOW_TYPE), LightTexture.FULL_BRIGHT, core);
		}
		buffers.endBatch(StructureKit.GLOW_TYPE);
	}

	/** The glow round the channel, and the channel itself. */
	private static BoxMesh[] build(Storm.Bolt bolt) {
		BoxMesh.Builder glow = new BoxMesh.Builder();
		BoxMesh.Builder core = new BoxMesh.Builder();
		for (float[] s : bolt.segments) {
			Vector3f a = new Vector3f(s[0], s[1], s[2]);
			Vector3f b = new Vector3f(s[3], s[4], s[5]);
			float w = s[6];
			glow.beam(a, b, 2.4F * w, 2.4F * w, BEAM_HAZE);
			glow.beam(a, b, 0.9F * w, 0.9F * w, BEAM);
			core.beam(a, b, 0.32F * w + 0.05F, 0.32F * w + 0.05F, FLASH);
		}
		return new BoxMesh[] {glow.build(), core.build()};
	}
}
