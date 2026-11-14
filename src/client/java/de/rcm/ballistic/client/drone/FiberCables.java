package de.rcm.ballistic.client.drone;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.render.FpvDroneRenderer;
import de.rcm.ballistic.entity.FpvDroneEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The glass fibre of a fibre-optic drone, seen in the world: it pays out of the spool behind the
 * drone, hangs taut for a few metres, then sinks and comes to rest on the ground, on roofs and in
 * the treetops along the whole way back to the pilot - a thin pale line that catches the light. It
 * stays lying there for a minute after the drone is gone.
 */
public final class FiberCables {
	private static final RenderType TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/entity/glow.png"));
	/** The last few points behind the drone hang on, taut; the rest sink to the ground. */
	private static final int TAUT = 6;
	private static final int MAX_POINTS = 4000;
	private static final int LINGER = 1200;

	private static final class Point {
		double x;
		double y;
		double z;
		float fall;
		boolean resting;

		Point(Vec3 p) {
			this.x = p.x;
			this.y = p.y;
			this.z = p.z;
		}
	}

	private static final class Cable {
		final List<Point> points = new ArrayList<>();
		FpvDroneEntity drone;
		long lastSeen;
	}

	private static final Map<Integer, Cable> CABLES = new HashMap<>();
	private static ClientLevel cableLevel;

	private FiberCables() {
	}

	/** Where the fibre leaves the drone's spool, in the world. */
	static Vec3 exit(FpvDroneEntity drone, float partialTick) {
		Vector3f e = new Vector3f(FpvDroneRenderer.FIBER_EXIT).mul(1.45F);
		new Quaternionf().rotationY(-drone.getViewYRot(partialTick) * Mth.DEG_TO_RAD).transform(e);
		Vec3 p = drone.getPosition(partialTick);
		return new Vec3(p.x + e.x, p.y + 0.12 + e.y, p.z + e.z);
	}

	/** Every client tick, for every fibre drone in view: pay out more fibre behind it. */
	static void track(FpvDroneEntity drone) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || drone.isLanded() || drone.getCableUsed() <= 0.0F) {
			return;
		}
		Cable cable = CABLES.computeIfAbsent(drone.getId(), id -> new Cable());
		cable.drone = drone;
		cable.lastSeen = mc.level.getGameTime();
		Vec3 here = exit(drone, 1.0F);
		if (cable.points.isEmpty()) {
			cable.points.add(new Point(drone.getAnchor()));
		}
		Point last = cable.points.get(cable.points.size() - 1);
		double dx = here.x - last.x;
		double dy = here.y - last.y;
		double dz = here.z - last.z;
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		// lay points about a block apart (more of them where it flew fast, so the line stays smooth)
		int n = (int) Math.min(12, d / 1.0);
		for (int i = 1; i <= n; i++) {
			double f = (double) i / n;
			cable.points.add(new Point(new Vec3(last.x + dx * f, last.y + dy * f, last.z + dz * f)));
		}
		while (cable.points.size() > MAX_POINTS) {
			cable.points.remove(1);
		}
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != cableLevel) {
			CABLES.clear();
			cableLevel = level;
		}
		if (level == null || CABLES.isEmpty()) {
			return;
		}
		long now = level.getGameTime();
		for (Iterator<Cable> it = CABLES.values().iterator(); it.hasNext(); ) {
			Cable c = it.next();
			if (now - c.lastSeen > LINGER) {
				it.remove();
				continue;
			}
			boolean flying = c.drone != null && !c.drone.isRemoved() && now - c.lastSeen < 3;
			int taut = flying ? TAUT : 0;
			// the slack sinks: falling a little faster each tick until it lies on whatever is below
			for (int i = 1; i < c.points.size() - taut; i++) {
				Point p = c.points.get(i);
				if (p.resting) {
					continue;
				}
				int bx = Mth.floor(p.x);
				int bz = Mth.floor(p.z);
				if (!level.hasChunk(bx >> 4, bz >> 4)) {
					continue;
				}
				double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz) + 0.03;
				p.fall = Math.min(0.6F, p.fall + 0.04F);
				p.y = Math.max(ground, p.y - p.fall);
				if (p.y <= ground + 1.0E-3) {
					p.resting = true;
				}
			}
		}
	}

	public static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (CABLES.isEmpty() || mc.level == null) {
			return;
		}
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cam = camera.position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		ClientLevel level = mc.level;
		long now = level.getGameTime();
		PoseStack poseStack = context.matrices();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		context.commandQueue().submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> {
			for (Cable c : CABLES.values()) {
				float fade = Mth.clamp((LINGER - (now - c.lastSeen)) / 200.0F, 0.0F, 1.0F);
				int n = c.points.size();
				Vector3f prev = null;
				int prevLight = 0;
				for (int i = 0; i <= n; i++) {
					Vec3 w;
					if (i < n) {
						Point p = c.points.get(i);
						w = new Vec3(p.x, p.y, p.z);
					} else if (c.drone != null && !c.drone.isRemoved() && now - c.lastSeen < 3) {
						w = exit(c.drone, partial); // the last bit, right up to the spool
					} else {
						break;
					}
					Vector3f a = new Vector3f((float) (w.x - cam.x), (float) (w.y - cam.y), (float) (w.z - cam.z));
					m.set(w.x, w.y + 0.1, w.z);
					int light = LevelRenderer.getLightColor(level, m);
					if (prev != null && (a.lengthSquared() < 300.0F * 300.0F || prev.lengthSquared() < 300.0F * 300.0F)) {
						ribbon(pose, consumer, prev, a, prevLight, light, fade);
					}
					prev = a;
					prevLight = light;
				}
			}
		});
	}

	/** A thin camera-facing strip of fibre from {@code a} to {@code b} (camera space). */
	private static void ribbon(PoseStack.Pose pose, VertexConsumer consumer, Vector3f a, Vector3f b, int lightA, int lightB, float fade) {
		Vector3f axis = new Vector3f(b).sub(a);
		if (axis.lengthSquared() < 1.0E-8F) {
			return;
		}
		Vector3f side = new Vector3f(axis).cross(new Vector3f(a).add(b).mul(0.5F));
		if (side.lengthSquared() < 1.0E-10F) {
			return;
		}
		side.normalize();
		// a fraction of a millimetre in reality: drawn just wide enough to be seen, a little wider far off
		float wa = Math.max(0.006F, a.length() * 0.0011F);
		float wb = Math.max(0.006F, b.length() * 0.0011F);
		int color = (int) (215 * fade) << 24 | 0xE6E8EA;
		float[][] q = {
			{a.x + side.x * wa, a.y + side.y * wa, a.z + side.z * wa, 0.0F},
			{a.x - side.x * wa, a.y - side.y * wa, a.z - side.z * wa, 1.0F},
			{b.x - side.x * wb, b.y - side.y * wb, b.z - side.z * wb, 1.0F},
			{b.x + side.x * wb, b.y + side.y * wb, b.z + side.z * wb, 0.0F}
		};
		int[] lights = {lightA, lightA, lightB, lightB};
		Matrix4f mat = pose.pose();
		for (int i : new int[] {0, 1, 2, 3, 3, 2, 1, 0}) {
			consumer.addVertex(mat, q[i][0], q[i][1], q[i][2]).setColor(color).setUv(q[i][3], 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(lights[i]).setNormal(pose, 0.0F, 1.0F, 0.0F);
		}
	}
}
