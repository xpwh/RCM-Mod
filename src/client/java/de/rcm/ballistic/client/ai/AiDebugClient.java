package de.rcm.ballistic.client.ai;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.ai.Senses;
import de.rcm.ballistic.ai.SoldierDebug;
import de.rcm.ballistic.ai.SoldierEntity;
import de.rcm.ballistic.client.render.MissileRenderer;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Draws the soldiers' minds into the world (from {@link SoldierDebug}):
 * <ul>
 *   <li>the view cone - bright lines for sharp sight (60 degrees each side), faint ones for the edge
 *       of vision (100 degrees), coloured by what he is doing: green patrol, yellow investigating a
 *       noise, red fighting, orange searching, blue taking cover</li>
 *   <li>a line to his target - solid red while he sees it, dim when he only remembers it</li>
 *   <li>everything he heard: rings where it was (red shot, purple explosion, white footsteps,
 *       yellow bullet past), fading with time, with a thin line back to him</li>
 *   <li>where he last saw his enemy (orange cross), the noise he is checking (yellow post), his cover
 *       spot (blue post), and the path he is walking (white)</li>
 *   <li>above his head: state, why, awareness, target and distance, ammunition, suppression</li>
 * </ul>
 */
public final class AiDebugClient {
	private static final Map<Integer, SoldierDebug.Entry> ENTRIES = new HashMap<>();
	private static long received;

	private AiDebugClient() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(SoldierDebug.Payload.TYPE, (payload, context) -> {
			ENTRIES.clear();
			for (SoldierDebug.Entry e : payload.entries()) {
				ENTRIES.put(e.id(), e);
			}
			received = System.currentTimeMillis();
		});
		WorldRenderEvents.AFTER_ENTITIES.register(AiDebugClient::render);
	}

	private static boolean live() {
		if (!ENTRIES.isEmpty() && System.currentTimeMillis() - received > 1500L) {
			ENTRIES.clear(); // debug switched off, or out of range
		}
		return !ENTRIES.isEmpty();
	}

	private static final String[] STATE = {"PATROUILLE", "UNTERSUCHT", "KAMPF", "SUCHE", "DECKUNG", "UNTERDRÜCKUNGSFEUER"};
	private static final String[] STATE_COLOUR = {"§a", "§e", "§c", "§6", "§9", "§d"};
	private static final int[] STATE_RGB = {0x55FF55, 0xFFE040, 0xFF4040, 0xFF9A30, 0x5090FF, 0xE060FF};

	/** The line above his head, or null when the debug view is off. */
	static @Nullable Component text(int id) {
		if (!live()) {
			return null;
		}
		SoldierDebug.Entry e = ENTRIES.get(id);
		if (e == null) {
			return null;
		}
		int s = Mth.clamp(e.state(), 0, 5);
		StringBuilder b = new StringBuilder();
		b.append(e.team() == SoldierEntity.TEAM_HOSTILE ? "§4■ " : "§2■ ").append(STATE_COLOUR[s]).append(STATE[s]).append(" §7(").append(e.reason()).append(")");
		b.append(" §8| §b").append(e.role());
		b.append(" §8| §7Erkennung §f").append(Math.round(e.awareness() * 100)).append('%');
		if (!e.targetName().isEmpty()) {
			b.append(" §8| §7Ziel §f").append(e.targetName()).append(' ').append(Math.round(e.targetDistance())).append("m ")
				.append(e.targetVisible() ? "§asichtbar" : "§everdeckt");
		}
		b.append(" §8| §7Muni §f").append(e.rounds()).append("/30").append(e.reloading() ? " §elädt" : "").append(" §8| §7Gran. §f").append(e.grenades());
		if (e.suppression() > 0.05F) {
			b.append(" §8| §7Unterdrückt §c").append(Math.round(e.suppression() * 100)).append('%');
		}
		return Component.literal(b.toString());
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !live()) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		PoseStack poseStack = context.matrices();
		context.commandQueue().submitCustomGeometry(poseStack, MissileRenderer.GLOW_TYPE, (pose, consumer) -> {
			for (SoldierDebug.Entry e : ENTRIES.values()) {
				Entity soldier = mc.level.getEntity(e.id());
				if (soldier == null) {
					continue;
				}
				draw(mc, e, soldier, pose, consumer, cam, partial);
			}
		});
	}

	private static void draw(Minecraft mc, SoldierDebug.Entry e, Entity soldier, PoseStack.Pose pose, VertexConsumer c, Vec3 cam, float partial) {
		Vec3 eye = soldier.getEyePosition(partial);
		int rgb = STATE_RGB[Mth.clamp(e.state(), 0, 5)];
		// the view cone
		float yaw = e.headYaw();
		Vec3 prevSharp = null;
		for (int i = 0; i <= 12; i++) {
			float a = yaw - 60.0F + i * 10.0F;
			Vec3 p = eye.add(Vec3.directionFromRotation(0.0F, a).scale(16.0));
			if (i == 0 || i == 12) {
				line(pose, c, cam, eye, p, rgb, 0.8F);
			}
			if (prevSharp != null) {
				line(pose, c, cam, prevSharp, p, rgb, 0.6F);
			}
			prevSharp = p;
		}
		for (float side : new float[] {-100.0F, 100.0F}) {
			line(pose, c, cam, eye, eye.add(Vec3.directionFromRotation(0.0F, yaw + side).scale(7.0)), rgb, 0.25F);
		}
		// the target
		if (e.targetId() >= 0) {
			Entity t = mc.level.getEntity(e.targetId());
			if (t != null) {
				line(pose, c, cam, eye, t.getEyePosition(partial).add(0, -0.3, 0), 0xFF3030, e.targetVisible() ? 0.95F : 0.3F);
			}
		}
		if (e.lastKnown() != null && !e.targetVisible()) {
			cross(pose, c, cam, e.lastKnown().add(0, 0.1, 0), 0.8, 0xFF9A30, 0.9F);
			post(pose, c, cam, e.lastKnown(), 2.2, 0xFF9A30, 0.6F);
			line(pose, c, cam, eye, e.lastKnown().add(0, 1.0, 0), 0xFF9A30, 0.25F);
		}
		if (e.investigate() != null) {
			post(pose, c, cam, e.investigate(), 2.5, 0xFFE040, 0.8F);
			cross(pose, c, cam, e.investigate().add(0, 0.1, 0), 0.6, 0xFFE040, 0.8F);
		}
		if (e.cover() != null) {
			post(pose, c, cam, e.cover(), 1.6, 0x5090FF, 0.9F);
			ring(pose, c, cam, e.cover().add(0, 0.05, 0), 0.5, 0x5090FF, 0.9F);
		}
		// where he is working round to, and where his grenade is going
		if (e.flank() != null) {
			post(pose, c, cam, e.flank(), 2.0, 0xC060FF, 0.8F);
			line(pose, c, cam, soldier.position().add(0, 0.2, 0), e.flank().add(0, 0.2, 0), 0xC060FF, 0.35F);
		}
		if (e.grenade() != null) {
			ring(pose, c, cam, e.grenade().add(0, 0.1, 0), 5.0, 0xFF6020, 0.7F); // the burst radius
			cross(pose, c, cam, e.grenade().add(0, 0.1, 0), 0.6, 0xFF6020, 0.9F);
		}
		// radio reports he received: a line from whoever reported
		for (double[] r : e.radio()) {
			Entity from = mc.level.getEntity((int) r[0]);
			if (from != null) {
				float fade = Mth.clamp(1.0F - (float) r[1] / 60.0F, 0.0F, 1.0F);
				line(pose, c, cam, from.getEyePosition(partial).add(0, 0.3, 0), eye.add(0, 0.3, 0), 0x40E0FF, 0.8F * fade);
			}
		}
		// what he heard
		for (double[] n : e.noises()) {
			Vec3 at = new Vec3(n[0], n[1], n[2]);
			int kind = (int) n[3];
			float age = (float) n[4];
			float fade = Mth.clamp(1.0F - age / 200.0F, 0.0F, 1.0F);
			int col = kind == Senses.GUNSHOT ? 0xFF4040 : kind == Senses.EXPLOSION ? 0xD050FF : kind == Senses.BULLET_PASS ? 0xFFE040 : 0xF0F0F0;
			double r = 0.4 + n[5] * 1.6 + age * 0.01;
			ring(pose, c, cam, at.add(0, 0.15, 0), r, col, 0.9F * fade);
			line(pose, c, cam, eye, at.add(0, 0.15, 0), col, 0.2F * fade);
		}
		// the path he is walking
		Vec3 prev = soldier.position().add(0, 0.1, 0);
		for (double[] p : e.path()) {
			Vec3 q = new Vec3(p[0], p[1], p[2]);
			line(pose, c, cam, prev, q, 0xFFFFFF, 0.55F);
			prev = q;
		}
	}

	// ------------------------------------------------------------------ primitives

	private static void post(PoseStack.Pose pose, VertexConsumer c, Vec3 cam, Vec3 at, double h, int rgb, float a) {
		line(pose, c, cam, at, at.add(0, h, 0), rgb, a);
	}

	private static void cross(PoseStack.Pose pose, VertexConsumer c, Vec3 cam, Vec3 at, double s, int rgb, float a) {
		line(pose, c, cam, at.add(-s, 0, -s), at.add(s, 0, s), rgb, a);
		line(pose, c, cam, at.add(-s, 0, s), at.add(s, 0, -s), rgb, a);
	}

	private static void ring(PoseStack.Pose pose, VertexConsumer c, Vec3 cam, Vec3 at, double r, int rgb, float a) {
		Vec3 prev = null;
		for (int i = 0; i <= 16; i++) {
			double ang = i * Math.PI * 2.0 / 16.0;
			Vec3 p = at.add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
			if (prev != null) {
				line(pose, c, cam, prev, p, rgb, a);
			}
			prev = p;
		}
	}

	/** A thin camera-facing strip from {@code a} to {@code b}, a constant few pixels wide. */
	private static void line(PoseStack.Pose pose, VertexConsumer consumer, Vec3 cam, Vec3 from, Vec3 to, int rgb, float alpha) {
		if (alpha <= 0.01F) {
			return;
		}
		Vector3f a = new Vector3f((float) (from.x - cam.x), (float) (from.y - cam.y), (float) (from.z - cam.z));
		Vector3f b = new Vector3f((float) (to.x - cam.x), (float) (to.y - cam.y), (float) (to.z - cam.z));
		Vector3f axis = new Vector3f(b).sub(a);
		if (axis.lengthSquared() < 1.0E-8F) {
			return;
		}
		Vector3f side = new Vector3f(axis).cross(new Vector3f(a).add(b).mul(0.5F));
		if (side.lengthSquared() < 1.0E-10F) {
			return;
		}
		side.normalize();
		float wa = Math.max(0.012F, a.length() * 0.0025F);
		float wb = Math.max(0.012F, b.length() * 0.0025F);
		int col = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24 | (rgb & 0xFFFFFF);
		float[][] q = {
			{a.x + side.x * wa, a.y + side.y * wa, a.z + side.z * wa, 0.0F},
			{a.x - side.x * wa, a.y - side.y * wa, a.z - side.z * wa, 1.0F},
			{b.x - side.x * wb, b.y - side.y * wb, b.z - side.z * wb, 1.0F},
			{b.x + side.x * wb, b.y + side.y * wb, b.z + side.z * wb, 0.0F}
		};
		for (int i : new int[] {0, 1, 2, 3, 3, 2, 1, 0}) {
			consumer.addVertex(pose, q[i][0], q[i][1], q[i][2]).setColor(col).setUv(q[i][3], 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
		}
	}
}
