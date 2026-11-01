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

	/** The air's colour on the thermometer: deep blue in frost, through cool and mild to orange and red heat. */
	private static int airColour(float t) {
		float[] at = {-20.0F, 0.0F, 10.0F, 20.0F, 30.0F, 42.0F};
		int[] col = {0x5A8CFF, 0x7FC4FF, 0xBFE6FF, 0xE8F0E0, 0xFFB050, 0xFF5A3C};
		if (t <= at[0]) {
			return col[0];
		}
		for (int i = 1; i < at.length; i++) {
			if (t <= at[i]) {
				return de.rcm.ballistic.client.gui.UiKit.lerpColor(col[i - 1], col[i], (t - at[i - 1]) / (at[i] - at[i - 1]));
			}
		}
		return col[col.length - 1];
	}

	/**
	 * The thermometer right of the hotbar, always there: a little glass with the air's temperature risen in it,
	 * the degrees beside it, and under them how the body copes - a bar from blue (freezing) over the middle
	 * (fine) to orange (overheating), which blinks when it gets dangerous.
	 */
	public static void hud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui || mc.player.isCreative() || mc.player.isSpectator() || !de.rcm.ballistic.client.ModConfig.temperatureHud) {
			return;
		}
		de.rcm.ballistic.client.gui.UiKit.init();
		var font = mc.font;
		String text = Math.round(air) + "°C";
		int w = 20 + Math.max(36, font.width(text) + 2);
		int h = 20;
		int x = g.guiWidth() / 2 + 98;
		int y = g.guiHeight() - 21;
		// the plate
		de.rcm.ballistic.client.gui.UiKit.round(g, de.rcm.ballistic.client.gui.UiKit.FILL, x, y, w, h, 5, 0xA0101318);
		de.rcm.ballistic.client.gui.UiKit.round(g, de.rcm.ballistic.client.gui.UiKit.EDGE, x, y, w, h, 5, 0x40FFFFFF);
		// the glass: a tube and its bulb, filled as high as it is warm (-20 .. 45 degrees)
		int airC = airColour(air);
		int tx = x + 6;
		int ty = y + 3;
		int th = 10;
		g.fill(tx, ty, tx + 4, ty + th + 1, 0xFF2A2F37);
		g.fill(tx + 1, ty + 1, tx + 3, ty + th + 1, 0xFF15181D);
		float level = Mth.clamp((air + 20.0F) / 65.0F, 0.0F, 1.0F);
		int fillTop = ty + 1 + Math.round((1.0F - level) * (th - 1));
		g.fill(tx + 1, fillTop, tx + 3, ty + th + 1, 0xFF000000 | airC);
		de.rcm.ballistic.client.gui.UiKit.disc(g, tx + 2.0F, ty + th + 3.0F, 3.6F, 0xFF2A2F37);
		de.rcm.ballistic.client.gui.UiKit.disc(g, tx + 2.0F, ty + th + 3.0F, 2.6F, 0xFF000000 | airC);
		// the degrees
		g.drawString(font, text, x + 16, y + 3, 0xFF000000 | airC, true);
		// the body: a bar with its middle marked, a knob where the body is now
		int bx = x + 16;
		int bw = w - 21;
		int by = y + 14;
		int mid = bx + bw / 2;
		g.fill(bx, by, bx + bw, by + 2, 0xFF2A2F37);
		g.fill(bx, by, mid, by + 2, 0x507FC4FF);
		g.fill(mid, by, bx + bw, by + 2, 0x50FFB050);
		g.fill(mid, by - 1, mid + 1, by + 3, 0xFF8A919B);
		int bodyC = shownBody < -0.05F ? 0x7FC4FF : shownBody > 0.05F ? 0xFF9A40 : 0xE8EEF2;
		float danger = Math.max(0.0F, Math.abs(shownBody) - 0.55F) / 0.45F;
		float blink = danger > 0.0F ? 0.55F + 0.45F * Mth.sin((System.currentTimeMillis() % 100000L) / 1000.0F * (4.0F + 6.0F * danger)) : 1.0F;
		int pos = Math.round(mid + Mth.clamp(shownBody, -1.0F, 1.0F) * (bw / 2.0F - 1.0F));
		int a = Math.round(255 * blink);
		g.fill(pos - 1, by - 1, pos + 2, by + 3, a << 24 | bodyC);
	}
}
