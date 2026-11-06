package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Vector3f;

/**
 * Bullet wounds in the body, front and back, in three grades - one hole; two or three; a load of buckshot,
 * the shirt soaked through - each in three versions and mirrored or not. Entry holes in front, small and
 * neat with a bruised ring; where rounds came out the back, ragged and bigger; blood soaking the shirt
 * round them and running down. In the body's space, in pixels: x -4..4, y 0 (neck) .. 12, z -2 chest .. 2.
 */
public final class GoreTorso {
	private static final GoreMesh[] MESHES = new GoreMesh[9];

	static {
		for (int level = 1; level <= 3; level++) {
			for (int v = 0; v < 3; v++) {
				int i = (level - 1) * 3 + v;
				GoreMesh.Builder b = new GoreMesh.Builder();
				// the jacket layer sits a quarter pixel out: just beyond it
				GoreHead.decal(b, new Vector3f(-4, 0, -2.3F), new Vector3f(8, 0, 0), new Vector3f(0, 12, 0), GoreMesh.T_TORSO + i * 2, new Vector3f(0, 0, -1));
				GoreHead.decal(b, new Vector3f(-4, 0, 2.3F), new Vector3f(8, 0, 0), new Vector3f(0, 12, 0), GoreMesh.T_TORSO + i * 2 + 1, new Vector3f(0, 0, 1));
				MESHES[i] = b.build();
			}
		}
	}

	private GoreTorso() {
	}

	public static void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, int level, int seed, GoreMesh.Cut cut) {
		if (level <= 0) {
			return;
		}
		int v = Math.floorMod(seed >> 4, 3);
		poseStack.pushPose();
		if (((seed >> 6) & 1) != 0) {
			poseStack.scale(-1.0F, 1.0F, 1.0F);
		}
		boolean mirror = ((seed >> 6) & 1) != 0;
		MESHES[(Math.min(3, level) - 1) * 3 + v].submit(poseStack, collector, light, cut == null || !mirror ? cut : (x, y, z) -> cut.test(-x, y, z));
		poseStack.popPose();
	}
}
