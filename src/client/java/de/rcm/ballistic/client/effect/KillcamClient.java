package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.explosion.Killcam;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The killcam on this side: while the world runs slowly (the server slowed it, {@link Killcam}) the camera leaves
 * you and shows the explosion from three angles - low across the ground, a slow arc high round it, close in
 * from the far side looking up at the cloud rising - in black bars, with a replay mark, the slow-motion factor
 * and a timecode. The jump key gives you your view back early.
 */
public final class KillcamClient {
	private static Vec3 pos;
	private static float size;
	private static long start;
	private static int millis;
	private static float bearing;
	private static boolean playing;

	private KillcamClient() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(Killcam.Payload.TYPE, (payload, context) -> start(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> playing = false);
		ClientTickEvents.END_CLIENT_TICK.register(KillcamClient::tick);
		HudElementRegistry.addLast(BallisticMissiles.id("killcam"), (g, counter) -> hud(g));
		for (Identifier id : new Identifier[] {VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.ARMOR_BAR,
			VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR,
			VanillaHudElements.CROSSHAIR, VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.STATUS_EFFECTS, VanillaHudElements.MOUNT_HEALTH}) {
			HudElementRegistry.replaceElement(id, element -> (g, counter) -> {
				if (!playing()) {
					element.render(g, counter);
				}
			});
		}
	}

	private static void start(Killcam.Payload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || de.rcm.ballistic.client.sun.SunCutscene.playing()) {
			return;
		}
		pos = p.pos();
		size = Math.max(20.0F, p.size());
		millis = p.millis();
		start = Util.getMillis();
		// the first shot from about where you are, so it starts out on the side you know
		Vec3 me = mc.player.position();
		bearing = (float) Math.atan2(me.z - pos.z, me.x - pos.x) + 0.5F;
		playing = true;
	}

	/** Whether the replay is on screen. */
	public static boolean playing() {
		if (!playing) {
			return false;
		}
		if (Util.getMillis() - start > millis) {
			playing = false;
		}
		return playing;
	}

	private static float progress() {
		return Mth.clamp((Util.getMillis() - start) / (float) millis, 0.0F, 1.0F);
	}

	private static void tick(Minecraft mc) {
		if (playing() && mc.options.keyJump.consumeClick() && Util.getMillis() - start > 600L) {
			playing = false;
		}
	}

	/** Where the camera is and looks, {x, y, z, yaw, pitch}, or null. */
	public static double[] camera(float partialTick) {
		if (!playing()) {
			return null;
		}
		float t = progress();
		int shot = Math.min(2, (int) (t * 3.0F));
		float u = t * 3.0F - shot;
		double r = size;
		Vec3 eye;
		Vec3 look;
		switch (shot) {
			case 0 -> {
				// low across the ground, drifting slowly sideways
				double a = bearing + u * 0.12;
				eye = pos.add(Math.cos(a) * r * 2.3, 0.0, Math.sin(a) * r * 2.3);
				eye = new Vec3(eye.x, ground(eye) + 2.5 + r * 0.06, eye.z);
				look = pos.add(0.0, r * 0.35, 0.0);
			}
			case 1 -> {
				// a slow arc high round it
				double a = bearing + 1.4 + u * 0.8;
				eye = pos.add(Math.cos(a) * r * 2.8, r * 1.1, Math.sin(a) * r * 2.8);
				look = pos.add(0.0, r * 0.45, 0.0);
			}
			default -> {
				// close in from the far side, looking up at the cloud as it rises
				double a = bearing + Math.PI;
				double d = r * (1.6 - 0.25 * u);
				eye = pos.add(Math.cos(a) * d, 0.0, Math.sin(a) * d);
				eye = new Vec3(eye.x, ground(eye) + 3.0, eye.z);
				look = pos.add(0.0, r * (0.6 + 0.4 * u), 0.0);
			}
		}
		eye = new Vec3(eye.x, Math.max(eye.y, ground(eye) + 2.0), eye.z);
		Vec3 d = look.subtract(eye);
		double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
		double pitch = Math.toDegrees(-Math.atan2(d.y, Math.hypot(d.x, d.z)));
		// a handheld drift
		float s = (Util.getMillis() - start) / 1000.0F;
		return new double[] {eye.x, eye.y, eye.z, yaw + Mth.sin(s * 0.7F) * 0.5F, pitch + Mth.sin(s * 0.9F + 1.0F) * 0.35F};
	}

	private static double ground(Vec3 at) {
		Minecraft mc = Minecraft.getInstance();
		int x = Mth.floor(at.x);
		int z = Mth.floor(at.z);
		if (mc.level == null || !mc.level.hasChunk(x >> 4, z >> 4)) {
			return pos.y;
		}
		return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
	}

	private static void hud(GuiGraphics g) {
		if (!playing()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		float t = progress();
		int w = g.guiWidth();
		int h = g.guiHeight();
		float in = Mth.clamp(t * millis / 400.0F, 0.0F, 1.0F);
		float out = Mth.clamp((1.0F - t) * millis / 400.0F, 0.0F, 1.0F);
		int bar = Math.round(h * 0.1F * Math.min(in, out));
		g.fill(0, 0, w, bar, 0xFF000000);
		g.fill(0, h - bar, w, h, 0xFF000000);
		// a cut: a quick dip to black between the angles
		float cut = t * 3.0F % 1.0F;
		float dip = Math.max(Math.max(0.0F, 1.0F - cut / 0.05F), Math.max(0.0F, (cut - 0.95F) / 0.05F));
		if (t * 3.0F >= 1.0F && dip > 0.0F) {
			g.fill(0, 0, w, h, Math.round(Mth.clamp(dip, 0.0F, 1.0F) * 220.0F) << 24);
		}
		float a = Math.min(in, out);
		int alpha = Math.round(a * 230.0F) << 24;
		long ms = Util.getMillis() - start;
		boolean blink = ms / 500 % 2 == 0;
		int x = 14;
		int y = bar + 10;
		if (blink) {
			g.fill(x, y + 1, x + 6, y + 7, alpha | 0xE5484D);
		}
		g.drawString(mc.font, Component.translatable("killcam.ballisticmissiles.replay"), x + 10, y, alpha | 0xFFFFFF, false);
		g.drawString(mc.font, Component.translatable("killcam.ballisticmissiles.slowmo", String.format("%.1f", Killcam.SLOW)), x, y + 12, alpha | 0xFFC060, false);
		Component cam = Component.translatable("killcam.ballisticmissiles.camera", Math.min(3, (int) (t * 3.0F) + 1));
		g.drawString(mc.font, cam, w - 14 - mc.font.width(cam), y, alpha | 0xD8D8DC, false);
		String code = String.format("%02d:%02d:%02d", ms / 60000, ms / 1000 % 60, ms % 1000 / 40);
		g.drawString(mc.font, code, w - 14 - mc.font.width(code), y + 12, alpha | 0x9A9AA2, false);
		Component skip = Component.translatable("killcam.ballisticmissiles.skip", mc.options.keyJump.getTranslatedKeyMessage());
		g.drawString(mc.font, skip, w - 12 - mc.font.width(skip), h - bar - 14, Math.round(a * 140.0F) << 24 | 0xFFFFFF, false);
	}
}
