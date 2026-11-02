package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Infrared strobes: every friendly player carries one on the back of the helmet, blinking once a second or so -
 * invisible to the naked eye, a hard white wink through night vision, so in the dark you know your own people
 * from a long way off. Friendly: on your team, or anyone when there are no teams.
 */
public final class IrStrobes {
	private static final Identifier TEX = BallisticMissiles.id("textures/effect/wildlife.png");
	private static final RenderType TYPE = RenderTypes.entityTranslucentEmissive(TEX);
	private static final float TILE = 1.0F / 8.0F;

	private IrStrobes() {
	}

	public static void init() {
		WorldRenderEvents.AFTER_ENTITIES.register(IrStrobes::render);
	}

	private static boolean friendly(Player me, Player other) {
		return me.getTeam() == null && other.getTeam() == null || me.isAlliedTo(other);
	}

	/** How bright the strobe is now (0-1): a short double wink, each player on their own beat. */
	static float blink(int id, float seconds) {
		float phase = (seconds * 0.9F + (id * 0.618F) % 1.0F) % 1.0F;
		float a = Math.max(0.0F, 1.0F - Math.abs(phase - 0.05F) / 0.035F);
		float b = Math.max(0.0F, 1.0F - Math.abs(phase - 0.17F) / 0.035F);
		return Math.max(a, b * 0.8F);
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !Vision.seesInfrared()) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float seconds = (mc.level.getGameTime() % 72000L + partial) / 20.0F;
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cam = camera.position();
		Vector3f up = new Vector3f(camera.upVector());
		Vector3f left = new Vector3f(camera.leftVector());
		context.commandQueue().submitCustomGeometry(context.matrices(), TYPE, (pose, c) -> {
			Matrix4f m = pose.pose();
			for (Player p : mc.level.players()) {
				if (p == mc.player || p.isSpectator() || !p.isAlive() || !friendly(mc.player, p)) {
					continue;
				}
				float k = blink(p.getId(), seconds);
				if (k <= 0.01F) {
					continue;
				}
				// the back of the helmet: above and behind the eyes
				Vec3 eye = p.getEyePosition(partial);
				float yaw = p.getViewYRot(partial) * Mth.DEG_TO_RAD;
				Vec3 at = eye.add(Mth.sin(yaw) * 0.2, 0.28, -Mth.cos(yaw) * 0.2);
				double dist = at.distanceTo(cam);
				if (dist > 256.0) {
					continue;
				}
				// a point of light that stays a point: bigger the further off, so it still shows
				float size = (0.05F + (float) dist * 0.0045F) * (0.6F + 0.4F * k);
				float x = (float) (at.x - cam.x);
				float y = (float) (at.y - cam.y);
				float z = (float) (at.z - cam.z);
				int color = (int) (k * 255.0F) << 24 | 0xFFFFFF;
				float u0 = 5 * TILE;
				float u1 = u0 + TILE;
				vertex(c, m, x - left.x * size - up.x * size, y - left.y * size - up.y * size, z - left.z * size - up.z * size, u0, 1.0F, color);
				vertex(c, m, x + left.x * size - up.x * size, y + left.y * size - up.y * size, z + left.z * size - up.z * size, u1, 1.0F, color);
				vertex(c, m, x + left.x * size + up.x * size, y + left.y * size + up.y * size, z + left.z * size + up.z * size, u1, 0.0F, color);
				vertex(c, m, x - left.x * size + up.x * size, y - left.y * size + up.y * size, z - left.z * size + up.z * size, u0, 0.0F, color);
			}
		});
	}

	private static void vertex(VertexConsumer c, Matrix4f m, float x, float y, float z, float u, float v, int color) {
		c.addVertex(m, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xF000F0).setNormal(0.0F, 1.0F, 0.0F);
	}
}
