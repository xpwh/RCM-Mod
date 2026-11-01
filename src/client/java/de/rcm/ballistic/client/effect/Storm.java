package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.ModConfig;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Thunderstorms. Lightning flickers inside the clouds, lighting them up round where it is; now and then a bolt
 * forks down to the ground, the land round it lit for an instant; the thunder arrives when sound would - a
 * tearing crack and a boom when it is close, a long low rumble from afar (Minecraft's own thunder, heard at once
 * everywhere, is silenced). The rain comes down harder, bouncing off the ground, the air thickens, and
 * everything the rain reaches gets wet - the ground darker and glossy, puddles standing on the flat, more of them
 * the longer it rains, drying out again slowly afterwards (drawn by {@link WorldFx}).
 */
public final class Storm {
	private static final double SOUND = 17.15;
	private static final RandomSource RANDOM = RandomSource.create();
	private static final List<Bolt> BOLTS = new ArrayList<>();
	private static final List<long[]> THUNDER = new ArrayList<>();
	private static float wetness;
	private static float puddles;
	/** A flash in progress: a few more flickers to come at (x, z). */
	private static int flickers;
	private static double flickerX;
	private static double flickerZ;
	private static float flickerRadius;

	private Storm() {
	}

	/**
	 * A lightning bolt drawn on the client, as a real one goes: the stepped leader creeps down from the cloud in a
	 * faint, branching, flickering tree; when it touches the ground the return stroke races back up it - the main
	 * channel blinding white, the branches lit for an instant - and dies; then, a few times, a dart leader brings the
	 * current down the same channel again (only the main channel, no branches), each stroke a little weaker, the
	 * last sometimes glowing on for a moment (continuing current). Times in ticks.
	 */
	public static final class Bolt {
		/** How long the stepped leader takes to come down (slowed a little, so it is seen). */
		public static final float LEADER = 3.0F;
		public final Vec3 origin;
		/** Where in the cloud it comes out of. */
		public final Vec3 top;
		/** Segments relative to the origin: x0 y0 z0 x1 y1 z1, width, depth (0 the main channel), arrival (0-1, the leader). */
		public final List<float[]> segments = new ArrayList<>();
		public int age;
		public final int life;
		/** When the strokes come (ticks after it starts), how strong, how long each glows. */
		private final float[] strokes;
		private final float[] power;
		private final float[] decay;
		private float length;

		Bolt(Vec3 top, Vec3 ground, long seed) {
			this.origin = ground;
			this.top = top;
			RandomSource r = RandomSource.create(seed);
			int n = 2 + r.nextInt(4);
			this.strokes = new float[n];
			this.power = new float[n];
			this.decay = new float[n];
			float t = LEADER;
			for (int i = 0; i < n; i++) {
				this.strokes[i] = t;
				this.power[i] = i == 0 ? 1.0F : 0.55F + r.nextFloat() * 0.4F;
				// the last stroke now and then carries on glowing (continuing current)
				this.decay[i] = i == n - 1 && r.nextFloat() < 0.4F ? 0.35F : 1.4F + r.nextFloat() * 0.6F;
				t += 1.5F + r.nextFloat() * 2.5F;
			}
			this.life = (int) Math.ceil(t + 8.0F);
			Vec3 start = top.subtract(ground);
			List<Vec3> main = walk(r, start, Vec3.ZERO, start.length() / 70.0, 0.48, true);
			this.add(main, 1.0F, 0, 0.0F, 1.0F);
			this.length = (float) start.length();
			this.branches(r, main, 0, 1.0F);
		}

		/**
		 * A tortuous channel from {@code a} towards {@code b}: steps of {@code step} blocks, each turning off at
		 * random (by about {@code turn} radians) but drawn on towards the end, then every step broken again into
		 * a few smaller kinks.
		 */
		private static List<Vec3> walk(RandomSource r, Vec3 a, Vec3 b, double step, double turn, boolean reach) {
			List<Vec3> pts = new ArrayList<>();
			pts.add(a);
			Vec3 p = a;
			Vec3 dir = b.subtract(a).normalize();
			double total = a.distanceTo(b);
			for (int i = 0; i < 400; i++) {
				Vec3 to = b.subtract(p);
				double left = to.length();
				if (left < step * 1.2) {
					if (reach) {
						pts.add(b);
					}
					break;
				}
				// pulled towards the end harder the nearer it gets, so it gets there
				double pull = 0.25 + 0.6 * (1.0 - left / total);
				Vec3 wander = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.5, r.nextGaussian()).scale(turn);
				dir = dir.add(wander).normalize().lerp(to.normalize(), pull).normalize();
				double len = step * (0.5 + r.nextDouble());
				p = p.add(dir.scale(len));
				pts.add(p);
				if (!reach && pts.size() * step > total) {
					break;
				}
			}
			// the fine kinks: each step bent once or twice more, a little
			List<Vec3> fine = new ArrayList<>();
			for (int i = 0; i + 1 < pts.size(); i++) {
				Vec3 p0 = pts.get(i);
				Vec3 p1 = pts.get(i + 1);
				double d = p0.distanceTo(p1);
				fine.add(p0);
				int k = 1 + r.nextInt(2);
				for (int j = 1; j <= k; j++) {
					Vec3 m = p0.lerp(p1, j / (k + 1.0));
					fine.add(m.add(r.nextGaussian() * d * 0.12, r.nextGaussian() * d * 0.06, r.nextGaussian() * d * 0.12));
				}
			}
			fine.add(pts.get(pts.size() - 1));
			return fine;
		}

		private void add(List<Vec3> pts, float width, int depth, float from, float to) {
			for (int i = 0; i + 1 < pts.size(); i++) {
				Vec3 p = pts.get(i);
				Vec3 q = pts.get(i + 1);
				float arrival = from + (to - from) * (i + 1) / (float) (pts.size() - 1);
				this.segments.add(new float[] {(float) p.x, (float) p.y, (float) p.z, (float) q.x, (float) q.y, (float) q.z, width, depth, arrival});
			}
		}

		/** Branches off a channel: most from its upper part, angled down and out, shorter and fainter each level. */
		private void branches(RandomSource r, List<Vec3> trunk, int depth, float width) {
			if (depth >= 3) {
				return;
			}
			int n = trunk.size();
			int count = depth == 0 ? 5 + r.nextInt(6) : depth == 1 ? r.nextInt(4) : r.nextInt(2);
			for (int i = 0; i < count; i++) {
				// biased to the top: the leader branches most as it leaves the cloud
				double u = Math.pow(r.nextDouble(), 1.6) * 0.85;
				int at = Math.min(n - 2, (int) (u * n));
				Vec3 p = trunk.get(at);
				double drop = p.y; // how far above the ground (the origin) it is
				if (drop < 8.0) {
					continue;
				}
				double len = Math.max(6.0, drop * (0.2 + r.nextDouble() * 0.35) * (depth == 0 ? 1.0 : 0.6));
				double a = r.nextDouble() * Math.PI * 2.0;
				Vec3 dir = new Vec3(Math.cos(a), -(0.5 + r.nextDouble() * 0.9), Math.sin(a)).normalize();
				Vec3 end = p.add(dir.scale(len));
				List<Vec3> pts = walk(r, p, end, Math.max(1.5, len / 14.0), 0.5, false);
				float arrival = (float) at / n;
				float reach = Math.min(1.0F, arrival + (float) (len / Math.max(this.length, 1.0)) * 1.3F);
				this.add(pts, width * 0.45F, depth + 1, arrival, reach);
				this.branches(r, pts, depth + 1, width * 0.45F);
			}
		}

		/**
		 * How bright a segment of the given depth is at time {@code t}, and with what tint: leader (faint violet,
		 * only as far as it has come), or a stroke (white). Returns {brightness, 0 for white .. 1 for violet}.
		 */
		public float[] light(int depth, float arrival, float t) {
			if (t < LEADER) {
				// the stepped leader: it has come this far; its tip brightest, the steps flickering
				float front = t / LEADER;
				if (arrival > front) {
					return new float[] {0.0F, 1.0F};
				}
				float tip = (float) Math.exp(-(front - arrival) * 9.0);
				float flicker = 0.7F + 0.3F * Mth.sin(t * 37.0F + arrival * 61.0F);
				return new float[] {(0.12F + 0.35F * tip) * flicker / (1.0F + depth * 0.6F), 1.0F};
			}
			float b = 0.0F;
			for (int i = 0; i < this.strokes.length; i++) {
				float dt = t - this.strokes[i];
				if (dt < 0.0F) {
					continue;
				}
				if (depth > 0 && i > 0) {
					continue; // the later strokes only follow the main channel
				}
				float k = depth == 0 ? this.decay[i] : 2.6F + depth;
				b = Math.max(b, this.power[i] * (float) Math.exp(-dt * k) / (1.0F + depth * 0.9F));
			}
			return new float[] {b, 0.0F};
		}

		/** The strongest stroke that comes in (t0, t1], and whether it is the first (0: none). */
		float strokeIn(float t0, float t1) {
			float p = 0.0F;
			for (int i = 0; i < this.strokes.length; i++) {
				if (this.strokes[i] > t0 && this.strokes[i] <= t1) {
					p = Math.max(p, i == 0 ? 2.0F : this.power[i]);
				}
			}
			return p;
		}

		/** The overall brightness now (the strongest the main channel is). */
		public float brightness(float partialTick) {
			float t = this.age + partialTick;
			return t > this.life ? 0.0F : this.light(0, 1.0F, t)[0];
		}
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(Storm::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> clear());
		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof LightningBolt && enabled()) {
				strike(entity.position(), true);
			}
		});
		LatePass.register(Storm::render); // before the clouds, which lie over the bolts' tops
	}

	public static boolean enabled() {
		return ModConfig.storms;
	}

	public static List<Bolt> bolts() {
		return BOLTS;
	}

	/** How wet what the rain reaches is (0-1). */
	public static float wetness() {
		return enabled() ? wetness : 0.0F;
	}

	/** How much water stands in puddles (0-1). */
	public static float puddles() {
		return enabled() ? puddles : 0.0F;
	}

	/** How hard it is raining where the camera is (0 where it snows or never rains). */
	public static float rainHere(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return 0.0F;
		}
		BlockPos at = mc.gameRenderer.getMainCamera().blockPosition();
		Biome.Precipitation p = mc.level.getBiome(at).value().getPrecipitationAt(at, mc.level.getSeaLevel());
		return p == Biome.Precipitation.RAIN ? mc.level.getRainLevel(partialTick) : 0.0F;
	}

	public static void clear() {
		BOLTS.clear();
		THUNDER.clear();
		wetness = 0.0F;
		puddles = 0.0F;
		flickers = 0;
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.isPaused()) {
			return;
		}
		long now = mc.level.getGameTime();
		// thunder arriving
		for (Iterator<long[]> it = THUNDER.iterator(); it.hasNext();) {
			long[] th = it.next();
			if (now >= th[0]) {
				it.remove();
				Vec3 at = new Vec3(th[1], th[2], th[3]);
				double d = mc.gameRenderer.getMainCamera().position().distanceTo(at);
				boolean near = d < 320.0;
				float volume = (float) Mth.clamp(1.15 - d / 2600.0, 0.15, 1.0);
				ClientEffects.playDistant(mc, near ? ModRegistry.THUNDER_NEAR : ModRegistry.THUNDER_FAR, at, volume, 0.85F + RANDOM.nextFloat() * 0.3F);
				if (d < 200.0) {
					ClientEffects.addShake((float) (1.0 - d / 200.0) * 1.6F);
				}
			}
		}
		for (Iterator<Bolt> it = BOLTS.iterator(); it.hasNext();) {
			Bolt b = it.next();
			// a stroke this tick: the land and the clouds lit up (2: the first, the return stroke)
			float stroke = b.strokeIn(b.age, b.age + 1.0F);
			if (stroke > 0.0F) {
				strokeLight(mc, b, Math.min(stroke, 1.0F), stroke > 1.5F);
			}
			if (++b.age > b.life) {
				it.remove();
			}
		}
		if (!enabled() || mc.level.dimension() != Level.OVERWORLD) {
			wetness = Math.max(0.0F, wetness - 0.002F);
			puddles = Math.max(0.0F, puddles - 0.002F);
			return;
		}
		float rain = rainHere(1.0F);
		float thunder = mc.level.getThunderLevel(1.0F);
		// soaking in over half a minute or so, drying out over a few minutes
		float wantWet = Mth.clamp(rain * 0.85F + thunder * 0.15F, 0.0F, 1.0F);
		wetness += wantWet > wetness ? (wantWet - wetness) * 0.004F + 0.0005F : -0.0004F;
		wetness = Mth.clamp(wetness, 0.0F, Math.max(wantWet, wetness));
		float wantPools = Mth.clamp(rain * (0.45F + 0.55F * thunder), 0.0F, 1.0F) * Mth.clamp(wetness * 1.2F, 0.0F, 1.0F);
		puddles += wantPools > puddles ? 0.0006F : -0.00025F;
		puddles = Mth.clamp(puddles, 0.0F, 1.0F);

		// ---- the storm: lightning in the clouds, now and then a bolt to the ground
		if (flickers > 0 && RANDOM.nextFloat() < 0.6F) {
			flickers--;
			VolumetricClouds.lightning(flickerX, flickerZ, flickerRadius, 0.5F + RANDOM.nextFloat() * 0.5F);
		}
		if (thunder > 0.15F) {
			Vec3 cam = mc.gameRenderer.getMainCamera().position();
			if (RANDOM.nextFloat() < 0.012F * thunder) {
				// sheet lightning: a flash within the clouds somewhere round
				double a = RANDOM.nextDouble() * Mth.TWO_PI;
				double r = 150.0 + RANDOM.nextDouble() * 900.0;
				double x = cam.x + Math.cos(a) * r;
				double z = cam.z + Math.sin(a) * r;
				flash(x, z, 90.0F + RANDOM.nextFloat() * 160.0F, 2 + RANDOM.nextInt(4));
				float base = Float.isNaN(VolumetricClouds.base()) ? (float) cam.y + 140.0F : VolumetricClouds.base();
				Vec3 at = new Vec3(x, base + 40.0, z);
				thunder(mc, at);
				BlastShader.flash((float) Mth.clamp(0.12 - r / 12000.0, 0.02, 0.12));
			}
			if (RANDOM.nextFloat() < 0.0045F * thunder) {
				// a bolt to the ground, near or far
				double a = RANDOM.nextDouble() * Mth.TWO_PI;
				double r = RANDOM.nextFloat() < 0.15F ? 60.0 + RANDOM.nextDouble() * 140.0 : 200.0 + RANDOM.nextDouble() * 900.0;
				int x = Mth.floor(cam.x + Math.cos(a) * r);
				int z = Mth.floor(cam.z + Math.sin(a) * r);
				int y = mc.level.hasChunk(x >> 4, z >> 4) ? mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : mc.level.getSeaLevel();
				// it goes for what stands highest round there: a tree, a tower, a peak
				for (int i = 0; i < 6; i++) {
					int qx = x + RANDOM.nextInt(41) - 20;
					int qz = z + RANDOM.nextInt(41) - 20;
					if (mc.level.hasChunk(qx >> 4, qz >> 4)) {
						int qy = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, qx, qz);
						if (qy > y + 2) {
							x = qx;
							z = qz;
							y = qy;
						}
					}
				}
				strike(new Vec3(x + 0.5, y, z + 0.5), false);
			}
			// heavy rain: drops bouncing off the ground all round
			if (rain > 0.3F && mc.options.particles().get() != net.minecraft.server.level.ParticleStatus.MINIMAL) {
				int n = Math.round(28 * rain * (0.5F + thunder));
				for (int i = 0; i < n; i++) {
					int x = Mth.floor(cam.x + (RANDOM.nextDouble() - 0.5) * 28.0);
					int z = Mth.floor(cam.z + (RANDOM.nextDouble() - 0.5) * 28.0);
					if (!mc.level.hasChunk(x >> 4, z >> 4)) {
						continue;
					}
					int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
					if (Math.abs(y - cam.y) < 24.0) {
						RealRain.splash(mc.level, ParticleTypes.RAIN, x + RANDOM.nextDouble(), y + 0.02, z + RANDOM.nextDouble(), 0.0, 0.0, 0.0);
					}
				}
			}
		}
	}

	/** Lightning lighting the clouds from inside at (x, z), flickering a few times. */
	private static void flash(double x, double z, float radius, int count) {
		VolumetricClouds.lightning(x, z, radius, 1.0F);
		flickerX = x;
		flickerZ = z;
		flickerRadius = radius;
		flickers = count;
	}

	/** A bolt strikes the ground at {@code ground}: the channel, the light, the clouds lit over it, the thunder. */
	private static void strike(Vec3 ground, boolean real) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		float base = Float.isNaN(VolumetricClouds.base()) ? (float) Math.max(ground.y + 120.0, cam.y + 100.0) : VolumetricClouds.base() + 10.0F;
		Vec3 top = new Vec3(ground.x + (RANDOM.nextDouble() - 0.5) * 60.0, base, ground.z + (RANDOM.nextDouble() - 0.5) * 60.0);
		// a real bolt (Minecraft's, which sets fires) is drawn by us too: its own renderer is switched off
		BOLTS.add(new Bolt(top, ground, RANDOM.nextLong()));
		// the leader coming down already lights the cloud a little
		VolumetricClouds.lightning(top.x, top.z, 90.0F, 0.3F);
	}

	/** A stroke of a bolt: the flash over the land, the cloud lit from inside; the first brings the thunder. */
	private static void strokeLight(Minecraft mc, Bolt bolt, float power, boolean first) {
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		Vec3 ground = bolt.origin;
		Vec3 top = bolt.top;
		double d = cam.distanceTo(ground);
		if (first) {
			flash(top.x, top.z, 160.0F + RANDOM.nextFloat() * 120.0F, 2 + RANDOM.nextInt(3));
			thunder(mc, ground.add(0.0, 30.0, 0.0));
		} else {
			VolumetricClouds.lightning(top.x, top.z, 150.0F, power * 0.8F);
		}
		DynamicLights.flash(ground.add(0.0, 6.0, 0.0), 0xD8E4FF, power, (float) Mth.clamp(60.0 + d * 0.05, 60.0, 160.0), first ? 260.0F : 120.0F);
		DynamicLights.flash(top.lerp(ground, 0.5), 0xC8D6FF, 0.8F * power, 140.0F, first ? 200.0F : 100.0F);
		BlastShader.flash((float) Mth.clamp(0.55 - d / 1800.0, 0.06, 0.55) * power);
		if (d < 300.0) {
			ClientEffects.setFlash((float) (0.35 * (1.0 - d / 300.0)) * power, 0xDDE6FF);
		}
		// where it touched down: a spray of sparks
		if (first && d < 96.0) {
			for (int i = 0; i < 24; i++) {
				mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, ground.x + RANDOM.nextGaussian() * 0.6, ground.y + 0.2 + RANDOM.nextDouble(),
					ground.z + RANDOM.nextGaussian() * 0.6, RANDOM.nextGaussian() * 0.4, RANDOM.nextDouble() * 0.5, RANDOM.nextGaussian() * 0.4);
			}
			mc.level.addParticle(ParticleTypes.LARGE_SMOKE, ground.x, ground.y + 0.4, ground.z, 0.0, 0.05, 0.0);
		}
	}

	/** Thunder from a storm far off (StormFront): heard when sound would get here, if it carries that far. */
	public static void distantThunder(Vec3 at) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level != null) {
			thunder(mc, at);
		}
	}

	private static void thunder(Minecraft mc, Vec3 at) {
		double d = mc.gameRenderer.getMainCamera().position().distanceTo(at);
		if (d > 3200.0) {
			return;
		}
		THUNDER.add(new long[] {mc.level.getGameTime() + (long) (d / SOUND), Math.round(at.x), Math.round(at.y), Math.round(at.z)});
	}

	private static void render(WorldRenderContext context) {
		if (!BOLTS.isEmpty()) {
			de.rcm.ballistic.client.render.LightningRenderer.draw(context, BOLTS);
		}
	}
}
