package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Aiming at the mod's big structures by what you see of them. A silo, a launch pad, a radar is one block, but
 * its model reaches far beyond it: Minecraft only finds the block when the crosshair is on that one block.
 * So each renderer records the meshes it draws for its block, placed exactly as drawn (turned to its facing,
 * the radar's antenna where it has turned to, the silo doors as far as they are open), and when the crosshair
 * ray meets one of those surfaces - nearer than whatever vanilla found - the structure's block is what is
 * aimed at. The ray is tested against the real faces, not a box round them: the empty air between a launch
 * tower and its tanks stays free to build in.
 * <p>
 * The server allows the click from further off for these blocks ({@code LargeBlocks}).
 */
public final class ModelPick {
	private record Part(BoxMesh mesh, Matrix4f toBlock, AABB box) {
	}

	private static final class Drawn {
		final List<Part> parts = new ArrayList<>();
		long frame;
	}

	private static final Map<BlockPos, Drawn> DRAWN = new HashMap<>();
	private static long frame;

	private ModelPick() {
	}

	/** A new frame is being drawn. */
	public static void nextFrame() {
		frame++;
		if ((frame & 63) == 0) {
			// structures no longer drawn (out of sight, broken): forgotten
			DRAWN.values().removeIf(d -> frame - d.frame > 40);
		}
	}

	/** {@code mesh} is being drawn for the block of {@code state}, with the pose {@code poseStack} has now. */
	public static void part(BlockEntityRenderState state, PoseStack poseStack, CameraRenderState camera, BoxMesh mesh) {
		BlockPos pos = state.blockPos;
		Drawn d = DRAWN.computeIfAbsent(pos.immutable(), k -> new Drawn());
		if (d.frame != frame) {
			d.parts.clear();
			d.frame = frame;
		}
		// the pose is relative to the camera; made relative to the block (small numbers, no precision lost)
		Matrix4f m = new Matrix4f().translation((float) (camera.pos.x - pos.getX()), (float) (camera.pos.y - pos.getY()), (float) (camera.pos.z - pos.getZ()))
			.mul(poseStack.last().pose());
		float[] b = mesh.bounds();
		AABB box = null;
		for (int i = 0; i < 8; i++) {
			Vector3f c = m.transformPosition(new Vector3f(b[(i & 1) == 0 ? 0 : 3], b[(i & 2) == 0 ? 1 : 4], b[(i & 4) == 0 ? 2 : 5]));
			AABB one = new AABB(c.x, c.y, c.z, c.x, c.y, c.z);
			box = box == null ? one : box.minmax(one);
		}
		d.parts.add(new Part(mesh, m, box.inflate(0.05)));
	}

	/**
	 * After vanilla has found what the crosshair is on: if one of the structures' surfaces is nearer, that
	 * structure's block is aimed at instead.
	 */
	public static void pick(Minecraft mc, double reach) {
		if (mc.level == null || mc.getCameraEntity() == null || DRAWN.isEmpty()) {
			return;
		}
		Vec3 eye = mc.getCameraEntity().getEyePosition(1.0F);
		Vec3 look = mc.getCameraEntity().getViewVector(1.0F);
		HitResult current = mc.hitResult;
		double best = current == null || current.getType() == HitResult.Type.MISS ? reach : Math.min(reach, current.getLocation().distanceTo(eye));
		BlockPos bestPos = null;
		Vec3 end = eye.add(look.scale(best));
		Iterator<Map.Entry<BlockPos, Drawn>> it = DRAWN.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<BlockPos, Drawn> e = it.next();
			BlockPos pos = e.getKey();
			Drawn d = e.getValue();
			if (frame - d.frame > 2) {
				continue;
			}
			if (!(mc.level.getBlockEntity(pos) != null)) {
				continue;
			}
			Vec3 o = eye.subtract(pos.getX(), pos.getY(), pos.getZ());
			Vec3 oEnd = end.subtract(pos.getX(), pos.getY(), pos.getZ());
			for (Part p : d.parts) {
				if (!p.box().contains(o) && p.box().clip(o, oEnd).isEmpty()) {
					continue;
				}
				double t = hit(p, o, look, best);
				if (t < best) {
					best = t;
					bestPos = pos;
					oEnd = o.add(look.scale(best));
				}
			}
		}
		if (bestPos != null) {
			// the hit point given to the server must lie within the block itself: the nearest point of it
			Vec3 at = eye.add(look.scale(best));
			Vec3 inside = new Vec3(clamp(at.x, bestPos.getX()), clamp(at.y, bestPos.getY()), clamp(at.z, bestPos.getZ()));
			mc.hitResult = new BlockHitResult(inside, Direction.getApproximateNearest(-look.x, -look.y, -look.z), bestPos, false);
		}
	}

	private static double clamp(double v, int block) {
		return Math.max(block + 0.001, Math.min(block + 0.999, v));
	}

	/** Distance along the ray (block-relative origin {@code o}, unit direction {@code dir}) to the mesh, or {@code max}. */
	private static double hit(Part p, Vec3 o, Vec3 dir, double max) {
		float[] v = p.mesh().vertices();
		Matrix4f m = p.toBlock();
		Vector3f[] q = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
		double best = max;
		for (int i = 0; i + 3 * BoxMesh.STRIDE < v.length + 1; i += 4 * BoxMesh.STRIDE) {
			for (int k = 0; k < 4; k++) {
				int j = i + k * BoxMesh.STRIDE;
				m.transformPosition(v[j], v[j + 1], v[j + 2], q[k]);
			}
			best = Math.min(best, triangle(o, dir, q[0], q[1], q[2], best));
			best = Math.min(best, triangle(o, dir, q[0], q[2], q[3], best));
		}
		return best;
	}

	/** Möller-Trumbore, both sides. */
	private static double triangle(Vec3 o, Vec3 d, Vector3f a, Vector3f b, Vector3f c, double max) {
		double e1x = b.x - a.x, e1y = b.y - a.y, e1z = b.z - a.z;
		double e2x = c.x - a.x, e2y = c.y - a.y, e2z = c.z - a.z;
		double px = d.y * e2z - d.z * e2y, py = d.z * e2x - d.x * e2z, pz = d.x * e2y - d.y * e2x;
		double det = e1x * px + e1y * py + e1z * pz;
		if (Math.abs(det) < 1.0E-9) {
			return max;
		}
		double inv = 1.0 / det;
		double tx = o.x - a.x, ty = o.y - a.y, tz = o.z - a.z;
		double u = (tx * px + ty * py + tz * pz) * inv;
		if (u < 0.0 || u > 1.0) {
			return max;
		}
		double qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
		double w = (d.x * qx + d.y * qy + d.z * qz) * inv;
		if (w < 0.0 || u + w > 1.0) {
			return max;
		}
		double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
		return t > 0.0 && t < max ? t : max;
	}
}
