package de.rcm.ballistic.client.effect;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What heat and cold (the server's BodyHeat) do to you that you see and feel: in the cold you shiver - the view,
 * and so your aim, trembles, worse the colder you are - and every breath hangs white in the air (everyone's does,
 * out where it is cold); in the heat the sweat runs into your eyes. And a small thermometer by the hotbar when the
 * air turns hard.
 */
public final class BodyHeatClient {
	private static final RandomSource R = RandomSource.create();
	private static float body;
	private static float shownBody;
	private static float air = 15.0F;
	private static float lastYaw;
	private static float lastPitch;

	private BodyHeatClient() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(BodyHeatClient::tick);
		WorldRenderEvents.START_MAIN.register(context -> shiver(Minecraft.getInstance()));
	}

	/** How stiff with cold the body is, 0-1 (the hands shake with it). */
	public static float cold() {
		return Mth.clamp((-shownBody - 0.3F) / 0.5F, 0.0F, 1.0F);
	}

	public static void receive(float b, float a) {
		body = b;
		air = a;
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			body = shownBody = 0.0F;
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		shownBody += (body - shownBody) * 0.05F;
		long now = mc.level.getGameTime();
		// breath in the cold air: yours, and everyone's out there
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof Player || e instanceof Villager) || e.distanceToSqr(cam) > 24.0 * 24.0 || !((LivingEntity) e).isAlive()) {
				continue;
			}
			boolean me = e == mc.player;
			boolean cold = me ? air < 4.0F : coldAt(mc, e.blockPosition());
			int period = me && shownBody < -0.4F ? 40 : 60;
			if (cold && de.rcm.ballistic.client.ModConfig.breath && (now + e.getId() * 13L) % period == 0 && !e.isUnderWater()) {
				breathe(e, me && mc.options.getCameraType().isFirstPerson());
			}
		}
		// sweat running into the eyes
		if (shownBody > 0.5F && R.nextFloat() < 0.01F * (shownBody - 0.4F) * 10.0F) {
			BlastShader.drench(0.05F + 0.08F * shownBody);
		}
	}

	/** Cold enough out to see your breath. */
	private static boolean coldAt(Minecraft mc, BlockPos at) {
		if (mc.level.dimension() == Level.END) {
			return true;
		}
		if (mc.level.dimension() == Level.NETHER) {
			return false;
		}
		float t = mc.level.getBiome(at).value().getBaseTemperature() - Math.max(0, at.getY() - 80) * 0.00125F;
		long time = mc.level.getDayTime() % 24000L;
		boolean night = time > 13000L && time < 23000L;
		return t < 0.2F || night && t < 0.45F;
	}

	/** A breath out: a small white cloud from the mouth, drifting forward and fading. */
	private static void breathe(Entity e, boolean firstPerson) {
		Vec3 look = e.getLookAngle();
		Vec3 mouth = e.getEyePosition().add(0.0, -0.12, 0.0).add(look.scale(firstPerson ? 0.45 : 0.3));
		for (int i = 0; i < 3; i++) {
			SmokeField.puff(mouth.x, mouth.y, mouth.z, look.x * 0.035 + (R.nextFloat() - 0.5F) * 0.01, 0.004 + look.y * 0.02,
				look.z * 0.035 + (R.nextFloat() - 0.5F) * 0.01, 26 + R.nextInt(10), 0.05F, 0.32F, 0xEEF2F6, 0.28F, 0.0F, 0.001F);
		}
	}

	/** Shivering: the view (and the aim with it) trembles - small, quick, uneven. Applied every frame. */
	private static void shiver(Minecraft mc) {
		if (mc.player == null || mc.isPaused()) {
			lastYaw = lastPitch = 0.0F;
			return;
		}
		float amount = Mth.clamp((-shownBody - 0.3F) / 0.55F, 0.0F, 1.0F);
		float t = (System.nanoTime() % 1_000_000_000_000L) / 1.0E9F;
		float yaw = 0.0F;
		float pitch = 0.0F;
		if (amount > 0.0F) {
			// bursts of shivering, not a steady buzz
			float burst = 0.55F + 0.45F * Mth.sin(t * 0.9F) * Mth.sin(t * 0.37F + 1.0F);
			float k = amount * burst;
			yaw = k * (0.22F * Mth.sin(t * 41.0F) + 0.12F * Mth.sin(t * 67.0F + 1.3F));
			pitch = k * (0.18F * Mth.sin(t * 37.0F + 0.7F) + 0.1F * Mth.sin(t * 59.0F + 2.1F));
		}
		mc.player.setYRot(mc.player.getYRot() + yaw - lastYaw);
		mc.player.setXRot(Mth.clamp(mc.player.getXRot() + pitch - lastPitch, -90.0F, 90.0F));
		lastYaw = yaw;
		lastPitch = pitch;
	}

	/** The thermometer by the hotbar, when the air is hard or the body feels it. */
	public static void hud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || mc.player.isCreative() || mc.player.isSpectator() || !de.rcm.ballistic.client.ModConfig.temperatureHud) {
			return;
		}
		if (Math.abs(shownBody) < 0.12F && air > 0.0F && air < 34.0F) {
			return;
		}
		int x = g.guiWidth() / 2 + 96;
		int y = g.guiHeight() - 19;
		int c = shownBody < -0.05F ? 0xFF7FC4FF : shownBody > 0.05F ? 0xFFFF9A40 : 0xFFE0E0E0;
		String icon = air < 8.0F ? "❄" : air > 28.0F ? "☀" : "•";
		g.drawString(mc.font, icon + " " + Math.round(air) + "°C", x, y, c, true);
		// how the body is doing: a little bar, blue (freezing) .. white .. orange (overheating)
		int bx = x;
		int by = y + 10;
		g.fill(bx, by, bx + 40, by + 2, 0x80000000);
		int mid = bx + 20;
		int pos = Math.round(mid + shownBody * 20.0F);
		g.fill(Math.min(mid, pos), by, Math.max(mid, pos) + 1, by + 2, c);
	}
}
