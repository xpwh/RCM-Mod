package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.explosion.Wind;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Long-lived, volumetric-looking smoke: rocket exhaust trails, contrails, launch clouds and
 * back-blast. Drawn by its own renderer rather than the particle engine, so thousands of puffs can
 * hang in the sky for minutes without crowding out the explosions.
 * <ul>
 *   <li>Each puff is a raymarched cloud sprite (lit from above, shadowed below) turned so its lit side
 *       faces the sun, tinted by the time of day, sorted back to front.</li>
 *   <li>Puffs swell, slow down, rise or sink, drift with the height-dependent wind and twist slowly
 *       with turbulence that grows with age, so trails kink and spread like real ones.</li>
 *   <li>Fresh exhaust glows orange from the flame; old smoke dissolves into wisps.</li>
 *   <li>A trail strings puffs along its path at even spacing; when too many are alive they are spaced
 *       wider and made larger instead.</li>
 *   <li>Standing inside dense smoke you can hardly see: {@link #fog()} feeds the screen shader.</li>
 * </ul>
 */
public final class SmokeField {
	private static final RenderType TYPE = RenderTypes.entityTranslucentEmissive(BallisticMissiles.id("textures/effect/smoke_volume.png"));
	private static final int CAP = 16000;
	private static final int BUDGET = 11000;
	private static final double MAX_DISTANCE = 1400.0;

	// struct of arrays: position, velocity, birth, life, sizes, colour, opacity, glow, buoyancy, seed
	private static final double[] X = new double[CAP];
	private static final double[] Y = new double[CAP];
	private static final double[] Z = new double[CAP];
	private static final float[] VX = new float[CAP];
	private static final float[] VY = new float[CAP];
	private static final float[] VZ = new float[CAP];
	private static final long[] BORN = new long[CAP];
	private static final int[] LIFE = new int[CAP];
	private static final float[] R0 = new float[CAP];
	private static final float[] R1 = new float[CAP];
	private static final int[] COLOR = new int[CAP];
	private static final float[] ALPHA = new float[CAP];
	private static final float[] GLOW = new float[CAP];
	private static final float[] LIFT = new float[CAP];
	private static final float[] SEED = new float[CAP];
	/** Way out from under a ceiling (unit x, z), and when it was last looked for. */
	private static final float[] ESC_X = new float[CAP];
	private static final float[] ESC_Z = new float[CAP];
	private static final int[] ESC_AGE = new int[CAP];
	/** {@link SmokeCollision#shelter} of each puff, looked up every few ticks. */
	private static final byte[] SHELTER = new byte[CAP];
	private static final double[] MOVE = new double[3];
	/** Smoke further than this from the camera is not collided (it is out of sight anyway). */
	private static final double COLLIDE_RANGE = 160.0;
	private static final double[] WAY = new double[2];
	private static int count;
	private static long clock;
	private static float fog;
	private static int fogColor = 0xB0B0B0;
	private static long[] order = new long[CAP];
	private static double[] WIND_X = new double[0];
	private static double[] WIND_Z = new double[0];

	/** How a trail looks. */
	public record Style(float width, float spread, int life, int color, float alpha, float glow, float lift, float spacing) {
		/** Solid-rocket exhaust low down: thick, white-grey, billowing, glowing at the nozzle. */
		public static Style exhaust(float scale) {
			return new Style(1.6F * scale, 7.0F * scale, 3600, 0xEEEBE6, 0.8F, 0.9F, 0.0012F, 1.0F);
		}

		/** Small rocket or interceptor. */
		public static Style smallRocket(float width) {
			return new Style(width, width * 4.5F, 1800, 0xF0EEEA, 0.72F, 0.8F, 0.001F, 0.8F);
		}

		/** Condensation trail high up: thin, bright, spreading into a wide soft band. */
		public static Style contrail(float width, float strength) {
			return new Style(width * 0.45F, width * (2.5F + 6.0F * strength), (int) (500 + 3100 * strength * strength), 0xF8F9FA,
				0.45F + 0.3F * strength, 0.0F, 0.0F, 1.2F);
		}
	}

	private static final class Trail {
		Vec3 last;
		double carry;
		long used;
	}

	private static final Map<Integer, Trail> TRAILS = new HashMap<>();

	private SmokeField() {
	}

	// ------------------------------------------------------------------ emitting

	/** One puff. Velocity in blocks per tick; life in ticks; radius grows from {@code r0} to {@code r1}. */
	public static void puff(double x, double y, double z, double vx, double vy, double vz, int life, float r0, float r1, int color, float alpha,
		float glow, float lift) {
		if (count >= CAP) {
			return; // full (trails already space their puffs out long before this)
		}
		int i = count++;
		X[i] = x;
		Y[i] = y;
		Z[i] = z;
		VX[i] = (float) vx;
		VY[i] = (float) vy;
		VZ[i] = (float) vz;
		BORN[i] = clock;
		LIFE[i] = Math.max(4, life);
		R0[i] = r0;
		R1[i] = r1;
		COLOR[i] = color;
		ALPHA[i] = alpha;
		GLOW[i] = glow;
		LIFT[i] = lift;
		SEED[i] = ClientEffects.rand() * 1000.0F;
		ESC_AGE[i] = 0;
		SHELTER[i] = 0;
	}

	/** A cloud of {@code n} puffs around {@code c} spreading at {@code speed} (launch clouds, back-blast). */
	public static void burst(Vec3 c, int n, double spread, Vec3 drift, double speed, int life, float r0, float r1, int color, float alpha, float lift) {
		for (int i = 0; i < n; i++) {
			Vec3 d = new Vec3(ClientEffects.gauss(), ClientEffects.gauss() * 0.4, ClientEffects.gauss()).normalize();
			double s = speed * (0.4 + ClientEffects.rand() * 0.8);
			puff(c.x + d.x * spread * ClientEffects.rand(), c.y + Math.abs(d.y) * spread * 0.5, c.z + d.z * spread * ClientEffects.rand(),
				drift.x + d.x * s, drift.y + Math.abs(d.y) * s * 0.4, drift.z + d.z * s, life + (int) (ClientEffects.rand() * life * 0.3),
				r0 * (0.7F + ClientEffects.rand() * 0.6F), r1 * (0.7F + ClientEffects.rand() * 0.6F), color, alpha, 0.0F, lift);
		}
	}

	/** Continues trail {@code key} to {@code now} in {@code style}; strength 0 just moves the trail head. */
	public static void trail(int key, Vec3 now, Style style, float strength) {
		Trail trail = TRAILS.computeIfAbsent(key, k -> new Trail());
		Vec3 from = trail.last;
		trail.last = now;
		trail.used = clock;
		if (from == null || strength <= 0.02F || from.distanceToSqr(now) > 300.0 * 300.0) {
			trail.carry = 0.0;
			return;
		}
		double crowd = Math.max(1.0, (double) count / BUDGET);
		double spacing = Math.max(0.7, style.width() * style.spacing()) * crowd;
		double length = from.distanceTo(now);
		double pos = trail.carry;
		float grow = (float) Math.sqrt(crowd);
		while (pos <= length) {
			Vec3 p = from.lerp(now, length < 1.0E-6 ? 1.0 : pos / length);
			double j = style.width() * 0.12;
			int life = (int) (style.life() * (0.85 + 0.3 * ClientEffects.rand()) * (0.4 + 0.6 * strength));
			puff(p.x + ClientEffects.gauss() * j, p.y + ClientEffects.gauss() * j, p.z + ClientEffects.gauss() * j,
				ClientEffects.gauss() * 0.01, 0.0, ClientEffects.gauss() * 0.01, life,
				style.width() * grow, style.spread() * grow * (0.8F + ClientEffects.rand() * 0.4F), style.color(), style.alpha() * Math.min(1.0F, strength + 0.3F),
				style.glow(), style.lift());
			pos += spacing;
		}
		trail.carry = pos - length;
	}

	public static void cut(int key) {
		TRAILS.remove(key);
	}

	/** 0-1: how thick the smoke is where the camera is. */
	public static float fog() {
		return fog;
	}

	public static int fogColor() {
		return fogColor;
	}

	public static void clear() {
		count = 0;
		TRAILS.clear();
		fog = 0.0F;
	}

	private static void remove(int i) {
		int last = --count;
		if (i != last) {
			X[i] = X[last];
			Y[i] = Y[last];
			Z[i] = Z[last];
			VX[i] = VX[last];
			VY[i] = VY[last];
			VZ[i] = VZ[last];
			BORN[i] = BORN[last];
			LIFE[i] = LIFE[last];
			R0[i] = R0[last];
			R1[i] = R1[last];
			COLOR[i] = COLOR[last];
			ALPHA[i] = ALPHA[last];
			GLOW[i] = GLOW[last];
			LIFT[i] = LIFT[last];
			SEED[i] = SEED[last];
			ESC_X[i] = ESC_X[last];
			ESC_Z[i] = ESC_Z[last];
			ESC_AGE[i] = ESC_AGE[last];
			SHELTER[i] = SHELTER[last];
		}
	}

	// ------------------------------------------------------------------ simulation

	private static float radius(int i, float t) {
		// swells fast at first, then keeps spreading slowly
		float g = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
		return Mth.lerp(g, R0[i], R1[i]);
	}

	private static float opacity(int i, float t, float age) {
		float in = Math.min(1.0F, age / 4.0F);
		float out = t > 0.45F ? 1.0F - (t - 0.45F) / 0.55F : 1.0F;
		return ALPHA[i] * in * out * out;
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		clock++;
		long gameTime = mc.level.getGameTime();
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		// wind sampled once per 8-block height band this tick
		int bandMin = mc.level.getMinY() - 64;
		int bands = (mc.level.getMaxY() + 1200 - bandMin) / 8 + 1;
		if (WIND_X.length < bands) {
			WIND_X = new double[bands];
			WIND_Z = new double[bands];
		}
		for (int k = 0; k < bands; k++) {
			double[] w = Wind.at(gameTime, bandMin + k * 8 + 4);
			WIND_X[k] = w[0];
			WIND_Z[k] = w[1];
		}
		float density = 0.0F;
		double r = 0;
		double g = 0;
		double b = 0;
		for (int i = count - 1; i >= 0; i--) {
			long age = clock - BORN[i];
			if (age > LIFE[i]) {
				remove(i);
				continue;
			}
			// sheltered smoke - in a crater, under a roof - hangs about far longer, out of the wind
			double sx0 = X[i] - cam.x;
			double sz0 = Z[i] - cam.z;
			if ((clock + i) % 10 == 0) {
				SHELTER[i] = sx0 * sx0 + sz0 * sz0 < COLLIDE_RANGE * COLLIDE_RANGE ? (byte) SmokeCollision.shelter(mc.level, X[i], Y[i], Z[i]) : 0;
			}
			boolean sheltered = SHELTER[i] != SmokeCollision.OPEN;
			if (sheltered && ClientEffects.rand() < 0.7F) {
				BORN[i]++; // ages at a third of the pace
				age = clock - BORN[i];
			}
			float t = (float) age / LIFE[i];
			// the push from the exhaust dies away; hot gas rises a little, then drifts with the air
			VX[i] *= 0.9F;
			VZ[i] *= 0.9F;
			VY[i] = VY[i] * 0.92F + LIFT[i] * Math.max(0.0F, 1.0F - t * 2.0F);
			int band = Mth.clamp((int) ((Y[i] - bandMin) / 8), 0, bands - 1);
			float ramp = Math.min(1.0F, age / 60.0F);
			// slow twisting that grows with age: trails kink and curl in the shear
			float s = SEED[i];
			float twist = 0.012F + 0.03F * Math.min(1.0F, age / 400.0F);
			double tx = Mth.sin(age * 0.011F + s) * twist + Mth.sin((float) (Y[i] * 0.05) + s * 0.3F) * twist * 0.6;
			double tz = Mth.cos(age * 0.009F + s * 1.7F) * twist + Mth.cos((float) (Y[i] * 0.043) + s) * twist * 0.6;
			double ty = Mth.sin(age * 0.007F + s * 2.3F) * twist * 0.3;
			double windHere = sheltered ? 0.12 : 1.0;
			if (SHELTER[i] == SmokeCollision.PIT && t > 0.2F) {
				VY[i] *= 0.8F; // cooled, it no longer climbs out of the hole
			}
			// the cloud base is an inversion: rising smoke stops under it and spreads out along it, into the layer
			float cloudBase = VolumetricClouds.base();
			if (!Float.isNaN(cloudBase) && Y[i] > cloudBase - 14.0 && Y[i] < cloudBase + VolumetricClouds.THICKNESS) {
				if (VY[i] > 0.0F) {
					float spread = VY[i] * 0.45F;
					VY[i] *= Y[i] > cloudBase ? 0.5F : 0.75F;
					VX[i] += Mth.sin(s * 2.7F) * spread;
					VZ[i] += Mth.cos(s * 2.7F) * spread;
				}
				if ((age + (int) (s * 7.0F)) % 10 == 0) {
					VolumetricClouds.smoke(X[i], Z[i], Math.max(6.0F, radius(i, t) * 1.5F), ALPHA[i] * 0.12F);
				}
			}
			double mx = VX[i] + WIND_X[band] * ramp * windHere + tx;
			double my = VY[i] + ty;
			double mz = VZ[i] + WIND_Z[band] * ramp * windHere + tz;
			float rad = radius(i, t);
			// the smoke meets the world: under a roof it pools and spreads to the edge and out
			double cx = X[i] - cam.x;
			double cz = Z[i] - cam.z;
			if (cx * cx + cz * cz < COLLIDE_RANGE * COLLIDE_RANGE) {
				int hit = SmokeCollision.move(mc.level, X[i], Y[i], Z[i], mx, my, mz, rad, MOVE);
				mx = MOVE[0];
				my = MOVE[1];
				mz = MOVE[2];
				if ((hit & SmokeCollision.CEILING) != 0) {
					VY[i] = Math.min(VY[i], 0.0F);
					if (ESC_AGE[i] <= 0) {
						SmokeCollision.escape(mc.level, X[i], Y[i], Z[i], SEED[i], rad, WAY);
						ESC_X[i] = (float) WAY[0];
						ESC_Z[i] = (float) WAY[1];
						ESC_AGE[i] = 10;
					}
					// the rising push turns sideways along the ceiling
					float push = 0.03F + LIFT[i] * 0.6F;
					VX[i] += ESC_X[i] * push;
					VZ[i] += ESC_Z[i] * push;
				}
				if ((hit & SmokeCollision.WALL_X) != 0) {
					VX[i] *= -0.25F;
				}
				if ((hit & SmokeCollision.WALL_Z) != 0) {
					VZ[i] *= -0.25F;
				}
				if ((hit & SmokeCollision.FLOOR) != 0) {
					VY[i] = 0.0F;
				}
				if (ESC_AGE[i] > 0) {
					ESC_AGE[i]--;
				}
			}
			X[i] += mx;
			Y[i] += my;
			Z[i] += mz;
			// thickness of the smoke at the camera
			double dx = X[i] - cam.x;
			double dy = Y[i] - cam.y;
			double dz = Z[i] - cam.z;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 < rad * rad) {
				float k = (float) (1.0 - Math.sqrt(d2) / rad);
				float a = opacity(i, t, age) * k * k;
				density += a;
				int c = COLOR[i];
				r += (c >> 16 & 255) * a;
				g += (c >> 8 & 255) * a;
				b += (c & 255) * a;
			}
		}
		float target = Mth.clamp(density * 0.9F, 0.0F, 0.92F);
		fog += (target - fog) * 0.25F;
		if (density > 1.0E-3F) {
			float day = daylight(mc);
			fogColor = (int) Mth.clamp(r / density * day, 0, 255) << 16 | (int) Mth.clamp(g / density * day, 0, 255) << 8
				| (int) Mth.clamp(b / density * day, 0, 255);
		}
		Iterator<Trail> it = TRAILS.values().iterator();
		while (it.hasNext()) {
			if (clock - it.next().used > 40) {
				it.remove();
			}
		}
	}

	/** 0.2 at night to 1 at noon, darker under rain. */
	private static float daylight(Minecraft mc) {
		double phase = (mc.level.getDayTime() % 24000L) / 24000.0 * Math.PI * 2.0;
		float sun = (float) Mth.clamp(Math.sin(phase) * 2.2 + 0.25, 0.0, 1.0);
		float rain = mc.level.getRainLevel(1.0F);
		return (0.2F + 0.8F * sun) * (1.0F - 0.35F * rain);
	}

	/** Direction the light comes from: the sun by day, the moon at night. */
	private static Vector3f lightDir(Minecraft mc) {
		double phase = (mc.level.getDayTime() % 24000L) / 24000.0 * Math.PI * 2.0;
		double sx = Math.cos(phase);
		double sy = Math.sin(phase);
		if (sy < -0.05) {
			sx = -sx;
			sy = -sy;
		}
		return new Vector3f((float) sx * 0.8F, (float) sy + 0.55F, 0.25F).normalize();
	}

	// ------------------------------------------------------------------ rendering

	public static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (count == 0 || mc.level == null) {
			return;
		}
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cam = camera.position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vector3f fwd = new Vector3f(camera.forwardVector());
		Vector3f up = new Vector3f(camera.upVector());
		Vector3f right = new Vector3f(fwd).cross(up).normalize();
		// turn every sprite so its lit side faces the sun's direction on screen
		Vector3f sun = lightDir(mc);
		float su = sun.dot(up);
		float sr = sun.dot(right);
		float theta = (float) Math.atan2(sr, su);
		float day = daylight(mc);

		// back to front
		int n = 0;
		if (order.length < count) {
			order = new long[CAP];
		}
		for (int i = 0; i < count; i++) {
			double dx = X[i] - cam.x;
			double dy = Y[i] - cam.y;
			double dz = Z[i] - cam.z;
			double along = dx * fwd.x + dy * fwd.y + dz * fwd.z;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (along < -R1[i] || d2 > MAX_DISTANCE * MAX_DISTANCE) {
				continue;
			}
			order[n++] = (long) Float.floatToIntBits((float) d2) << 32 | i;
		}
		if (n == 0) {
			return;
		}
		Arrays.sort(order, 0, n);
		final int total = n;
		PoseStack poseStack = context.matrices();
		context.commandQueue().submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> {
			for (int k = total - 1; k >= 0; k--) {
				int i = (int) order[k];
				emit(pose, consumer, i, cam, right, up, theta, day, partial);
			}
		});
	}

	private static void emit(PoseStack.Pose pose, VertexConsumer consumer, int i, Vec3 cam, Vector3f right, Vector3f up, float theta, float day,
		float partial) {
		float age = clock - BORN[i] + partial;
		float t = Math.min(1.0F, age / LIFE[i]);
		float rad = radius(i, t);
		float a = opacity(i, t, age);
		double dx = X[i] - cam.x;
		double dy = Y[i] - cam.y;
		double dz = Z[i] - cam.z;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		// inside a puff the sprite would fill the screen: the shader's fog takes over instead
		if (dist < rad * 1.1) {
			a *= (float) Mth.clamp((dist - rad * 0.4) / (rad * 0.7), 0.0, 1.0);
		}
		if (a < 0.01F) {
			return;
		}
		// colour: the smoke's own colour in today's light; fresh exhaust lit orange by the flame
		int c = COLOR[i];
		float cr = (c >> 16 & 255) / 255.0F * day;
		float cg = (c >> 8 & 255) / 255.0F * day;
		float cb = (c & 255) / 255.0F * day;
		float hot = GLOW[i] * Mth.clamp(1.0F - age / 6.0F, 0.0F, 1.0F);
		if (hot > 0.0F) {
			cr = Mth.lerp(hot, cr, 1.0F);
			cg = Mth.lerp(hot, cg, 0.62F);
			cb = Mth.lerp(hot, cb, 0.28F);
		}
		int argb = (int) (Mth.clamp(a, 0.0F, 1.0F) * 255.0F) << 24 | (int) (Mth.clamp(cr, 0.0F, 1.0F) * 255.0F) << 16
			| (int) (Mth.clamp(cg, 0.0F, 1.0F) * 255.0F) << 8 | (int) (Mth.clamp(cb, 0.0F, 1.0F) * 255.0F);
		// sprite: one of four shapes, dissolving through four stages as it ages
		int shape = (int) SEED[i] & 3;
		int stage = Mth.clamp((int) (t * t * 4.6F), 0, 3);
		float u0 = shape * 0.25F;
		float v0 = stage * 0.25F;
		float jitter = (SEED[i] % 1.0F - 0.5F) * 0.5F;
		float ang = theta + jitter;
		float cos = Mth.cos(ang);
		float sin = Mth.sin(ang);
		// sprite axes on screen: "up" towards the sun
		float ux = (up.x * cos + right.x * sin) * rad;
		float uy = (up.y * cos + right.y * sin) * rad;
		float uz = (up.z * cos + right.z * sin) * rad;
		float rx = (right.x * cos - up.x * sin) * rad;
		float ry = (right.y * cos - up.y * sin) * rad;
		float rz = (right.z * cos - up.z * sin) * rad;
		float px = (float) dx;
		float py = (float) dy;
		float pz = (float) dz;
		vertex(consumer, pose, px - rx - ux, py - ry - uy, pz - rz - uz, u0, v0 + 0.25F, argb);
		vertex(consumer, pose, px + rx - ux, py + ry - uy, pz + rz - uz, u0 + 0.25F, v0 + 0.25F, argb);
		vertex(consumer, pose, px + rx + ux, py + ry + uy, pz + rz + uz, u0 + 0.25F, v0, argb);
		vertex(consumer, pose, px - rx + ux, py - ry + uy, pz - rz + uz, u0, v0, argb);
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int argb) {
		consumer.addVertex(pose, x, y, z).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
