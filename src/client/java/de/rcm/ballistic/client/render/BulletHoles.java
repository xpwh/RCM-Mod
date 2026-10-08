package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.network.ModNetworking.BulletHolePayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Bullet holes left where rounds struck: a dark hole in crushed chips on stone, torn pale splinters
 * on wood (along the grain), a bright scraped rim on metal, a crumbly crater in earth. They fade out
 * after about a minute, and go with the block if it is broken.
 */
public final class BulletHoles {
	private static final RenderType TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/effect/bullet_hole.png"));
	private static final int LIFE = 1200;
	private static final int FADE = 200;
	private static final int CAP = 800;
	private static final float SIZE = 0.075F;
	private static final List<Hole> HOLES = new ArrayList<>();

	private BulletHoles() {
	}

	private record Hole(Vec3 at, Direction face, int kind, float angle, float stretch, float size, int variant, int tint, BlockPos block,
		BlockState state, long born, int index, boolean predicted) {
	}

	private static int counter;

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(BulletHolePayload.TYPE, (payload, context) -> add(payload));
		ClientTickEvents.END_CLIENT_TICK.register(BulletHoles::tick);
		WorldRenderEvents.BEFORE_ENTITIES.register(BulletHoles::render);
	}

	private static void add(BulletHolePayload p) {
		Direction face = Direction.from3DDataValue(p.face());
		Vec3 at = new Vec3(p.x(), p.y(), p.z());
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			// the server's hole replaces the one we drew the instant we fired
			long now = mc.level.getGameTime();
			HOLES.removeIf(h -> h.predicted && now - h.born < 30 && h.face == face && h.at.distanceToSqr(at) < 0.5 * 0.5);
		}
		place(at, face, p.kind(), new Vec3(p.dx(), p.dy(), p.dz()), false);
	}

	/** Our own shot: the hole appears at once where the crosshair is; the server's own follows it. */
	public static void predict(Vec3 at, Direction face, int kind, Vec3 dir) {
		place(at, face, kind, dir, true);
	}

	private static void place(Vec3 at, Direction face, int kind, Vec3 dir, boolean predicted) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		BlockPos block = BlockPos.containing(at.subtract(face.getStepX() * 0.05, face.getStepY() * 0.05, face.getStepZ() * 0.05));
		BlockState state = mc.level.getBlockState(block);
		if (state.isAir()) {
			return;
		}
		if (HOLES.size() >= CAP) {
			HOLES.remove(0);
		}
		// turned along the round's path across the face, and drawn out the flatter it came in
		Vector3f n = new Vector3f(face.getStepX(), face.getStepY(), face.getStepZ());
		Vector3f[] axes = axes(n);
		Vector3f d = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
		float angle = (float) (Math.random() * Mth.TWO_PI);
		float stretch = 1.0F;
		if (d.lengthSquared() > 1.0E-6F) {
			d.normalize();
			float head = Math.abs(d.dot(n));
			float tu = d.dot(axes[0]);
			float tv = d.dot(axes[1]);
			if (tu * tu + tv * tv > 0.01F) {
				angle = (float) Mth.atan2(tv, tu) + (float) (Math.random() - 0.5) * 0.2F;
				stretch = Mth.clamp(1.0F / Math.max(head, 0.05F), 1.0F, 1.0F + 1.1F * (1.0F - head) * 2.0F);
				stretch = Math.min(stretch, 2.3F);
			}
		}
		float size = 0.8F + (float) Math.random() * 0.4F;
		HOLES.add(new Hole(at, face, kind, angle, stretch, size, (int) (Math.random() * 2), tint(mc, block, state, kind), block, state,
			mc.level.getGameTime(), counter++ % 9, predicted));
	}

	/**
	 * The colour of the broken surface: the block's own, freshly exposed - lighter for broken stone
	 * and splintered wood, bare grey steel for metal, damp and darker for earth.
	 */
	private static int tint(Minecraft mc, BlockPos block, BlockState state, int kind) {
		int c = state.getMapColor(mc.level, block).col;
		if (c == 0) {
			c = 0x8C8C8C;
		}
		float r = (c >> 16 & 255) / 255.0F;
		float g = (c >> 8 & 255) / 255.0F;
		float b = (c & 255) / 255.0F;
		float[] mix = switch (kind) {
			case 1 -> new float[] {1.0F, 0.95F, 0.85F, 0.45F};
			case 2 -> new float[] {0.8F, 0.8F, 0.82F, 0.65F};
			case 3 -> new float[] {0.0F, 0.0F, 0.0F, 0.25F};
			default -> new float[] {1.0F, 1.0F, 1.0F, 0.3F};
		};
		r = Mth.lerp(mix[3], r, mix[0]);
		g = Mth.lerp(mix[3], g, mix[1]);
		b = Mth.lerp(mix[3], b, mix[2]);
		return (int) (r * 255.0F) << 16 | (int) (g * 255.0F) << 8 | (int) (b * 255.0F);
	}

	/** Two directions across a face. */
	private static Vector3f[] axes(Vector3f n) {
		Vector3f u = Math.abs(n.y) > 0.5F ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0).cross(n).normalize();
		Vector3f v = new Vector3f(n).cross(u).normalize();
		return new Vector3f[] {u, v};
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			HOLES.clear();
			return;
		}
		long now = mc.level.getGameTime();
		// gone with time, or with the block (broken, or changed)
		// a hole drawn at once for our own shot that the server never confirmed (the round hit someone
		// on the way, or the spread put it elsewhere) goes again
		HOLES.removeIf(h -> now - h.born > LIFE || mc.level.getBlockState(h.block) != h.state || h.predicted && now - h.born > 40);
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (HOLES.isEmpty() || mc.level == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		long now = mc.level.getGameTime();
		PoseStack poseStack = context.matrices();
		context.commandQueue().submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> {
			for (Hole h : HOLES) {
				if (h.at.distanceToSqr(cam) > 64.0 * 64.0) {
					continue;
				}
				float alpha = Mth.clamp((LIFE - (now - h.born)) / (float) FADE, 0.0F, 1.0F);
				draw(pose, consumer, h, cam, alpha, mc);
			}
		});
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer consumer, Hole h, Vec3 cam, float alpha, Minecraft mc) {
		Vector3f n = new Vector3f(h.face.getStepX(), h.face.getStepY(), h.face.getStepZ());
		Vector3f[] axes = axes(n);
		Vector3f u = axes[0];
		Vector3f v = axes[1];
		float size = SIZE * h.size;
		Vector3f ru;
		Vector3f rv;
		if (h.kind == 1 && Math.abs(n.y) < 0.5F) {
			// the grain of wood runs up a wall: its splinters stay upright there, torn longer the way the round went
			float along = Math.abs(Mth.sin(h.angle));
			ru = new Vector3f(u).mul(size * (1.0F + (h.stretch - 1.0F) * (1.0F - along)));
			rv = new Vector3f(v).mul(size * (1.0F + (h.stretch - 1.0F) * along));
		} else {
			// the texture's +u (where the spall is thrown) along the round's path, stretched along it
			ru = new Vector3f(u).mul(Mth.cos(h.angle)).add(new Vector3f(v).mul(Mth.sin(h.angle))).mul(size * h.stretch);
			rv = new Vector3f(v).mul(Mth.cos(h.angle)).sub(new Vector3f(u).mul(Mth.sin(h.angle))).mul(size);
		}
		// held off the face by more the further away it is, so the depth buffer never swallows it
		float dist = (float) Math.sqrt(h.at.distanceToSqr(cam));
		float lift = 0.006F + dist * 0.0009F + h.index * 0.0005F;
		Vector3f c = new Vector3f((float) (h.at.x - cam.x), (float) (h.at.y - cam.y), (float) (h.at.z - cam.z)).add(new Vector3f(n).mul(lift));
		int light = LevelRenderer.getLightColor(mc.level, h.block.relative(h.face));
		int color = (int) (alpha * 255.0F) << 24 | h.tint;
		int cell = h.kind * 2 + h.variant;
		float u0 = (cell % 4) * 0.25F;
		float v0 = (cell / 4) * 0.5F;
		float[][] q = {
			{-1, -1, u0, v0 + 0.5F}, {1, -1, u0 + 0.25F, v0 + 0.5F}, {1, 1, u0 + 0.25F, v0}, {-1, 1, u0, v0}
		};
		for (float[] p : q) {
			float x = c.x + ru.x * p[0] + rv.x * p[1];
			float y = c.y + ru.y * p[0] + rv.y * p[1];
			float z = c.z + ru.z * p[0] + rv.z * p[1];
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(p[2], p[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, n.x, n.y, n.z);
		}
	}
}
