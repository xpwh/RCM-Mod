package de.rcm.ballistic.client.effect;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A thunderstorm you see coming. Minutes before it breaks (the server tells us when: {@link #receive}), a storm
 * cell stands on the horizon upwind - a dark tower with its anvil spreading out on top and a grey curtain of rain
 * hanging under it - and drifts in with the wind; sheet lightning flickers inside it, silent at first, then with a
 * low rumble long after the flash, the wind picking up as it comes. When it is overhead the storm breaks; when it
 * is over, the cell moves on and you watch it go, flickering, the other way.
 * <p>
 * Drawn by the clouds' and the sky's shaders from the {@code StormCell} / {@code StormInfo} entries of the wind
 * uniform block (see {@link Wind}).
 */
public final class StormFront {
	/** How long before it breaks it is on its way (ticks): the server only tells us that far ahead. */
	public static final int LEAD = 9600;
	/** How far off it is when it sets out (blocks). */
	private static final double FAR = 3200.0;
	/** The storm cell's radius (blocks). */
	public static final float RADIUS = 650.0F;
	private static final RandomSource R = RandomSource.create();

	/** Game time the storm breaks here (-1: none on its way). */
	private static long arrival = -1L;
	/** When we first heard of it. */
	private static long heard;
	/** Where it is heading (where the player was), and the way it moves (with the wind). */
	private static double targetX;
	private static double targetZ;
	private static float dirX = 1.0F;
	private static float dirZ;
	/** Moving off after the storm: since when (-1: not). */
	private static long leaving = -1L;
	private static float lastThunder;
	private static float fade;
	private static float flash;
	private static float prevFlash;
	private static int flickers;
	private static float flashX;
	private static float flashZ;

	private StormFront() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(StormFront::tick);
	}

	/** From the server: the next thunderstorm breaks in {@code ticks} (-1: none in sight). */
	public static void receive(int ticks) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		long now = mc.level.getGameTime();
		if (ticks < 0) {
			// "none in sight" as it breaks means it is here: the cell stays overhead and gives way to the storm
			if (arrival >= 0L && now < arrival - 600L) {
				arrival = -1L;
			}
			return;
		}
		long at = now + ticks;
		if (arrival >= 0L && Math.abs(at - arrival) < 200L) {
			return; // the same storm
		}
		boolean fresh = arrival < 0L;
		arrival = at;
		if (fresh) {
			heard = now;
			leaving = -1L;
			targetX = mc.player.getX();
			targetZ = mc.player.getZ();
			float dir = Wind.direction((now % 72000L) / 20.0F);
			dirX = Mth.cos(dir);
			dirZ = Mth.sin(dir);
		}
	}

	public static void clear() {
		arrival = -1L;
		leaving = -1L;
		fade = 0.0F;
		flash = prevFlash = 0.0F;
		lastThunder = 0.0F;
	}

	/** Whether there is a storm cell to draw. */
	public static boolean visible() {
		return fade > 0.003F;
	}

	/** Where the cell's centre is now (x, z), or null. */
	private static double[] center(long now) {
		if (leaving >= 0L) {
			double d = (now - leaving) * (FAR / LEAD);
			return new double[] {targetX + dirX * d, targetZ + dirZ * d};
		}
		if (arrival >= 0L) {
			double d = Math.max(0.0, (arrival - now) * (FAR / LEAD));
			return new double[] {targetX - dirX * d, targetZ - dirZ * d};
		}
		return null;
	}

	/** How close it has come, 0 (out of sight) to 1 (here): the wind picks up as it does. */
	public static float closeness() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || !visible()) {
			return 0.0F;
		}
		double[] c = center(mc.level.getGameTime());
		if (c == null) {
			return 0.0F;
		}
		double d = Math.hypot(c[0] - mc.player.getX(), c[1] - mc.player.getZ());
		return (float) Mth.clamp(1.0 - d / FAR, 0.0, 1.0) * fade;
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		long now = mc.level.getGameTime();
		boolean overworld = mc.level.dimension() == Level.OVERWORLD;
		float thunder = mc.level.getThunderLevel(1.0F);
		// the storm over: it moves on, off downwind
		if (overworld && lastThunder > 0.6F && thunder < 0.3F && leaving < 0L) {
			leaving = now;
			arrival = -1L;
			targetX = mc.player.getX();
			targetZ = mc.player.getZ();
			float dir = Wind.direction((now % 72000L) / 20.0F);
			dirX = Mth.cos(dir);
			dirZ = Mth.sin(dir);
		}
		lastThunder = thunder;
		// it never came (the weather changed its mind): it dissolves
		if (arrival >= 0L && now > arrival + 2400L && thunder < 0.2F) {
			arrival = -1L;
		}
		boolean on = overworld && (arrival >= 0L || leaving >= 0L && now - leaving < LEAD * 1.3);
		if (leaving >= 0L && now - leaving >= LEAD * 1.3) {
			leaving = -1L;
		}
		float want = on ? 1.0F : 0.0F;
		if (on && arrival >= 0L) {
			want = Mth.clamp((now - heard) / 600.0F, 0.0F, 1.0F); // gathers on the horizon
		}
		if (on && leaving >= 0L) {
			want = 1.0F - Mth.clamp((now - leaving - LEAD) / (LEAD * 0.3F), 0.0F, 1.0F);
		}
		fade += (want - fade) * 0.02F;
		if (Math.abs(want - fade) < 0.002F) {
			fade = want;
		}

		// sheet lightning in it: more often the closer it comes
		prevFlash = flash;
		flash *= 0.5F;
		if (flickers > 0 && R.nextFloat() < 0.5F) {
			flickers--;
			flash = Math.max(flash, 0.5F + R.nextFloat() * 0.5F);
		}
		double[] c = center(now);
		if (c != null && visible()) {
			double dx = c[0] - mc.player.getX();
			double dz = c[1] - mc.player.getZ();
			double d = Math.hypot(dx, dz);
			float near = (float) Mth.clamp(1.0 - d / FAR, 0.0, 1.0);
			// while the storm is right here, it has its own lightning (Storm)
			float overhead = Mth.clamp(thunder * 1.5F, 0.0F, 1.0F);
			if (R.nextFloat() < (0.006F + 0.03F * near) * fade * (1.0F - overhead)) {
				flash = 1.0F;
				flickers = 1 + R.nextInt(3);
				float a = R.nextFloat() * Mth.TWO_PI;
				float r = RADIUS * 0.6F * Mth.sqrt(R.nextFloat());
				flashX = Mth.cos(a) * r;
				flashZ = Mth.sin(a) * r;
				float base = Float.isNaN(VolumetricClouds.base()) ? (float) mc.player.getY() + 130.0F : VolumetricClouds.base();
				// the rumble, long after the flash - from far off nothing at all
				Storm.distantThunder(new Vec3(c[0] + flashX, base + 120.0, c[1] + flashZ));
			}
		}
	}

	/** The cell for the uniform block: centre relative to the camera (x, z), radius, strength. */
	static float[] cell(Vec3 cam, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !visible()) {
			return null;
		}
		double[] c = center(mc.level.getGameTime());
		if (c == null) {
			return null;
		}
		// with a storm right here it is all over the sky anyway: the cell gives way to it
		float strength = fade * (1.0F - 0.85F * mc.level.getThunderLevel(partialTick));
		return new float[] {(float) (c[0] - cam.x), (float) (c[1] - cam.z), RADIUS, strength};
	}

	/** The flash for the uniform block: brightness, where in the cell (x, z). */
	static float[] flash(float partialTick) {
		return new float[] {Mth.lerp(partialTick, prevFlash, flash), flashX, flashZ};
	}
}
