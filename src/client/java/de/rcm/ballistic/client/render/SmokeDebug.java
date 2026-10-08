package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.effect.SmokeCollision.DBG_EX;
import static de.rcm.ballistic.client.effect.SmokeCollision.DBG_EZ;
import static de.rcm.ballistic.client.effect.SmokeCollision.DBG_HEAD;
import static de.rcm.ballistic.client.effect.SmokeCollision.DBG_TYPE;
import static de.rcm.ballistic.client.effect.SmokeCollision.DBG_X;
import static de.rcm.ballistic.client.effect.SmokeCollision.DBG_Y;
import static de.rcm.ballistic.client.effect.SmokeCollision.DBG_Z;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.effect.SmokeCollision;
import de.rcm.ballistic.client.effect.SmokeField;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * {@code /rauchdebug} (or {@code /smokedebug}): shows what the smoke's block collision is doing.
 * Every bit of smoke that is checked against the blocks this tick gets a line up to the height it
 * feels for a ceiling - yellow if the way is clear, red with a red mark where it struck a ceiling,
 * orange where a wall stopped it, purple where it was inside a block - and a green arrow along the
 * way out it found. Smoke high in the open sky is skipped (not drawn); the counts are in the top
 * left. {@code /rauchdebug test} lets a column of smoke rise where you are looking, for ten seconds.
 */
public final class SmokeDebug {
	private static final double RANGE = 96.0;
	private static Vec3 testAt;
	private static int testTicks;

	private SmokeDebug() {
	}

	public static void init() {
		ClientTickEvents.START_CLIENT_TICK.register(mc -> SmokeCollision.beginTick());
		ClientTickEvents.END_CLIENT_TICK.register(SmokeDebug::tick);
		WorldRenderEvents.AFTER_ENTITIES.register(SmokeDebug::render);
		HudElementRegistry.addLast(BallisticMissiles.id("smoke_debug"), (graphics, counter) -> hud(graphics));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			for (String name : new String[] {"rauchdebug", "smokedebug"}) {
				dispatcher.register(ClientCommandManager.literal(name)
					.executes(ctx -> toggle(ctx.getSource(), !SmokeCollision.debug))
					.then(ClientCommandManager.literal("an").executes(ctx -> toggle(ctx.getSource(), true)))
					.then(ClientCommandManager.literal("on").executes(ctx -> toggle(ctx.getSource(), true)))
					.then(ClientCommandManager.literal("aus").executes(ctx -> toggle(ctx.getSource(), false)))
					.then(ClientCommandManager.literal("off").executes(ctx -> toggle(ctx.getSource(), false)))
					.then(ClientCommandManager.literal("test").executes(ctx -> test(ctx.getSource()))));
			}
		});
	}

	private static int toggle(FabricClientCommandSource source, boolean on) {
		SmokeCollision.debug = on;
		source.sendFeedback(Component.translatable(on ? "message.ballisticmissiles.smoke_debug_on" : "message.ballisticmissiles.smoke_debug_off")
			.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		return 1;
	}

	private static int test(FabricClientCommandSource source) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0;
		}
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			testAt = Vec3.atCenterOf(block.getBlockPos().relative(block.getDirection())).add(0, -0.3, 0);
		} else {
			testAt = mc.player.getEyePosition().add(mc.player.getLookAngle().scale(6.0));
		}
		testTicks = 200;
		SmokeCollision.debug = true;
		source.sendFeedback(Component.translatable("message.ballisticmissiles.smoke_debug_test").withStyle(ChatFormatting.GREEN));
		return 1;
	}

	/** The test column: a steady stream of rising smoke for ten seconds. */
	private static void tick(Minecraft mc) {
		if (testTicks <= 0 || testAt == null || mc.level == null || mc.isPaused()) {
			return;
		}
		testTicks--;
		for (int i = 0; i < 3; i++) {
			double a = Math.random() * Mth.TWO_PI;
			double r = Math.random() * 0.25;
			SmokeField.puff(testAt.x + Math.cos(a) * r, testAt.y, testAt.z + Math.sin(a) * r, Math.cos(a) * 0.02, 0.08, Math.sin(a) * 0.02,
				360 + (int) (Math.random() * 120), 0.35F, 2.2F, 0xCFCAC2, 0.55F, 0.0F, 0.012F);
		}
	}

	private static void hud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (!SmokeCollision.debug || mc.options.hideGui) {
			return;
		}
		int[] s = SmokeCollision.lastStats;
		int x = 6;
		int y = 6;
		g.drawString(mc.font, Component.translatable("message.ballisticmissiles.smoke_debug_title"), x, y, 0xFFFFE070, true);
		g.drawString(mc.font, Component.translatable("message.ballisticmissiles.smoke_debug_stats", s[0], s[1]), x, y + 11, 0xFFFFFFFF, true);
		g.drawString(mc.font, Component.translatable("message.ballisticmissiles.smoke_debug_hits", s[2], s[3], s[4]), x, y + 22, 0xFFFFFFFF, true);
		g.drawString(mc.font, Component.translatable("message.ballisticmissiles.smoke_debug_legend"), x, y + 33, 0xFFB0B0B0, true);
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		int n = SmokeCollision.dbgCount;
		if (!SmokeCollision.debug || n == 0 || mc.level == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.matrices();
		context.commandQueue().submitCustomGeometry(poseStack, MissileRenderer.GLOW_TYPE, (pose, consumer) -> {
			int drawn = 0;
			for (int i = 0; i < n && drawn < 2500; i++) {
				float x = (float) (DBG_X[i] - cam.x);
				float y = (float) (DBG_Y[i] - cam.y);
				float z = (float) (DBG_Z[i] - cam.z);
				if (x * x + y * y + z * z > RANGE * RANGE) {
					continue;
				}
				drawn++;
				Vector3f p = new Vector3f(x, y, z);
				Vector3f top = new Vector3f(x, y + DBG_HEAD[i], z);
				int color = switch (DBG_TYPE[i]) {
					case SmokeCollision.HIT_CEILING -> 0xFF3030;
					case SmokeCollision.HIT_WALL -> 0xFF9020;
					case SmokeCollision.HIT_OTHER -> 0xC040FF;
					default -> 0xFFE040;
				};
				float alpha = DBG_TYPE[i] == SmokeCollision.FREE ? 0.45F : 0.95F;
				line(pose, consumer, p, top, 0.025F, color, alpha);
				if (DBG_TYPE[i] != SmokeCollision.FREE) {
					// a cross where it struck
					line(pose, consumer, new Vector3f(top).add(-0.18F, 0, 0), new Vector3f(top).add(0.18F, 0, 0), 0.04F, color, 1.0F);
					line(pose, consumer, new Vector3f(top).add(0, 0, -0.18F), new Vector3f(top).add(0, 0, 0.18F), 0.04F, color, 1.0F);
				}
				if (DBG_EX[i] != 0.0F || DBG_EZ[i] != 0.0F) {
					// the way out, with an arrowhead
					Vector3f tip = new Vector3f(x + DBG_EX[i], y, z + DBG_EZ[i]);
					line(pose, consumer, p, tip, 0.04F, 0x40FF60, 1.0F);
					Vector3f back = new Vector3f(p).sub(tip).normalize().mul(0.35F);
					Vector3f side = new Vector3f(-back.z, 0, back.x);
					line(pose, consumer, tip, new Vector3f(tip).add(back).add(side), 0.04F, 0x40FF60, 1.0F);
					line(pose, consumer, tip, new Vector3f(tip).add(back).sub(side), 0.04F, 0x40FF60, 1.0F);
				}
			}
		});
	}

	/** A thin camera-facing strip from a to b (camera at the origin), both windings. */
	private static void line(PoseStack.Pose pose, VertexConsumer consumer, Vector3f a, Vector3f b, float width, int rgb, float alpha) {
		Vector3f axis = new Vector3f(b).sub(a);
		Vector3f side = new Vector3f(axis).cross(new Vector3f(a).add(b).mul(0.5F));
		if (side.lengthSquared() < 1.0E-10F) {
			return;
		}
		side.normalize().mul(width);
		int c = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24 | (rgb & 0xFFFFFF);
		float[][] q = {
			{a.x + side.x, a.y + side.y, a.z + side.z}, {a.x - side.x, a.y - side.y, a.z - side.z},
			{b.x - side.x, b.y - side.y, b.z - side.z}, {b.x + side.x, b.y + side.y, b.z + side.z}
		};
		for (int i : new int[] {0, 1, 2, 3, 3, 2, 1, 0}) {
			consumer.addVertex(pose, q[i][0], q[i][1], q[i][2]).setColor(c).setUv(0.5F, 0.5F).setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
		}
	}
}
