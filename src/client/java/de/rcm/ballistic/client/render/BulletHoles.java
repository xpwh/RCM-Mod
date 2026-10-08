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
	private static final float SIZE = 0.07F;
	private static final List<Hole> HOLES = new ArrayList<>();

	private BulletHoles() {
	}

	private record Hole(Vec3 at, Direction face, int kind, float angle, BlockPos block, BlockState state, long born, int index) {
	}

	private static int counter;

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(BulletHolePayload.TYPE, (payload, context) -> add(payload));
		ClientTickEvents.END_CLIENT_TICK.register(BulletHoles::tick);
		WorldRenderEvents.BEFORE_ENTITIES.register(BulletHoles::render);
	}

	private static void add(BulletHolePayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Direction face = Direction.from3DDataValue(p.face());
		Vec3 at = new Vec3(p.x(), p.y(), p.z());
		BlockPos block = BlockPos.containing(at.subtract(face.getStepX() * 0.05, face.getStepY() * 0.05, face.getStepZ() * 0.05));
		BlockState state = mc.level.getBlockState(block);
		if (state.isAir()) {
			return;
		}
		if (HOLES.size() >= CAP) {
			HOLES.remove(0);
		}
		HOLES.add(new Hole(at, face, p.kind(), (float) (Math.random() * Mth.TWO_PI), block, state, mc.level.getGameTime(), counter++ % 9));
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			HOLES.clear();
			return;
		}
		long now = mc.level.getGameTime();
		// gone with time, or with the block (broken, or changed)
		HOLES.removeIf(h -> now - h.born > LIFE || mc.level.getBlockState(h.block) != h.state);
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
		Vector3f u = Math.abs(n.y) > 0.5F ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0).cross(n).normalize();
		Vector3f v = new Vector3f(n).cross(u).normalize();
		// the grain of wood runs up a wall: its splinters stay upright there
		float angle = h.kind == 1 && Math.abs(n.y) < 0.5F ? 0.0F : h.angle;
		Vector3f ru = new Vector3f(u).mul(Mth.cos(angle)).add(new Vector3f(v).mul(Mth.sin(angle))).mul(SIZE);
		Vector3f rv = new Vector3f(v).mul(Mth.cos(angle)).sub(new Vector3f(u).mul(Mth.sin(angle))).mul(SIZE);
		float lift = 0.003F + h.index * 0.0004F;
		Vector3f c = new Vector3f((float) (h.at.x - cam.x), (float) (h.at.y - cam.y), (float) (h.at.z - cam.z)).add(new Vector3f(n).mul(lift));
		int light = LevelRenderer.getLightColor(mc.level, h.block.relative(h.face));
		int color = (int) (alpha * 255.0F) << 24 | 0xFFFFFF;
		float u0 = (h.kind % 2) * 0.5F;
		float v0 = (h.kind / 2) * 0.5F;
		float[][] q = {
			{-1, -1, u0, v0 + 0.5F}, {1, -1, u0 + 0.5F, v0 + 0.5F}, {1, 1, u0 + 0.5F, v0}, {-1, 1, u0, v0}
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
