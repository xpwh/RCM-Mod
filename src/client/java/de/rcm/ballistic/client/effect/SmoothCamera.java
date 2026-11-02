package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.client.ModConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * A cleaner first-person camera. The mouse turns the view through a very short filter - a few hundredths of a
 * second - so the steps between mouse reports and frames melt into an even turn without feeling slow. Walking up a
 * step or a slab the view rises with the body instead of snapping up a block, and landing from a jump or a fall it
 * sinks a little and springs back.
 */
public final class SmoothCamera {
	/** How quickly the view catches up with the mouse, per second (time constant 1/RATE). */
	private static final double LOOK_RATE = 42.0;
	/** How quickly the view catches up with a step, per second. */
	private static final double STEP_RATE = 13.0;
	/** The landing spring: stiffness, and damping just short of critical (one soft overshoot-free sink and return). */
	private static final double SPRING = 170.0;
	private static final double DAMP = 2.0 * Math.sqrt(SPRING) * 0.95;

	private static double pendingX;
	private static double pendingY;
	private static long lastTurn;

	private static double smoothY = Double.NaN;
	private static double dip;
	private static double dipVel;
	private static long lastFrame;
	private static boolean wasOnGround = true;
	private static double lastFall;

	private SmoothCamera() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(SmoothCamera::tick);
	}

	/** The mouse's turn this frame, handed on to the player a little at a time. */
	public static void turn(LocalPlayer player, double dx, double dy, java.util.function.BiConsumer<Double, Double> apply) {
		long now = System.nanoTime();
		double dt = Mth.clamp((now - lastTurn) / 1.0E9, 0.0, 0.1);
		lastTurn = now;
		if (!ModConfig.smoothLook || Minecraft.getInstance().options.smoothCamera) {
			if (pendingX != 0.0 || pendingY != 0.0) {
				dx += pendingX;
				dy += pendingY;
				pendingX = 0.0;
				pendingY = 0.0;
			}
			apply.accept(dx, dy);
			return;
		}
		pendingX += dx;
		pendingY += dy;
		double k = 1.0 - Math.exp(-dt * LOOK_RATE);
		double ox = pendingX * k;
		double oy = pendingY * k;
		pendingX -= ox;
		pendingY -= oy;
		if (Math.abs(pendingX) < 1.0E-4) {
			ox += pendingX;
			pendingX = 0.0;
		}
		if (Math.abs(pendingY) < 1.0E-4) {
			oy += pendingY;
			pendingY = 0.0;
		}
		if (ox != 0.0 || oy != 0.0) {
			apply.accept(ox, oy);
		}
	}

	private static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			smoothY = Double.NaN;
			return;
		}
		boolean ground = p.onGround();
		if (ground && !wasOnGround && lastFall < -0.28 && ModConfig.smoothMove) {
			// landing: the knees take it
			dipVel -= Mth.clamp((-lastFall - 0.2) * 2.6, 0.0, 3.4);
		}
		wasOnGround = ground;
		lastFall = p.getDeltaMovement().y;
	}

	/** How far to move the camera up or down this frame (blocks) for the entity it is on. */
	public static double offset(Entity entity, float partialTick) {
		long now = System.nanoTime();
		double dt = Mth.clamp((now - lastFrame) / 1.0E9, 0.0, 0.05);
		lastFrame = now;
		Minecraft mc = Minecraft.getInstance();
		if (!ModConfig.smoothMove || entity != mc.player || entity.isPassenger() || mc.player.isFallFlying() || mc.player.getAbilities().flying) {
			smoothY = Double.NaN;
			dip = 0.0;
			dipVel = 0.0;
			return 0.0;
		}
		double y = Mth.lerp(partialTick, entity.yo, entity.getY());
		if (Double.isNaN(smoothY) || Math.abs(y - smoothY) > 1.3 || y < smoothY || !mc.player.onGround()) {
			// only steps up are eased; dropping, jumping and falling the view stays with you
			smoothY = y;
		} else {
			smoothY += (y - smoothY) * (1.0 - Math.exp(-dt * STEP_RATE));
		}
		// the landing spring
		double acc = -SPRING * dip - DAMP * dipVel;
		dipVel += acc * dt;
		dip += dipVel * dt;
		if (Math.abs(dip) < 1.0E-4 && Math.abs(dipVel) < 1.0E-3) {
			dip = 0.0;
			dipVel = 0.0;
		}
		return (smoothY - y) + Mth.clamp(dip, -0.25, 0.1);
	}
}
