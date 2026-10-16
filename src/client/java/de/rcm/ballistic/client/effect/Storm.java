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

	/** A lightning bolt drawn on the client: its jagged channel and branches, how old it is. */
	public static final class Bolt {
		public final Vec3 origin;
		/** Segments relative to the origin: x0 y0 z0 x1 y1 z1 width. */
		public final List<float[]> segments = new ArrayList<>();
		public int age;
		public final int life;
		private final long seed;

		Bolt(Vec3 top, Vec3 ground, long seed) {
			this.origin = ground;
			this.seed = seed;
			this.life = 7 + RANDOM.nextInt(6);
			RandomSource r = RandomSource.create(seed);
			channel(r, top.subtract(ground), Vec3.ZERO, 1.0F, 0);
		}

		/** The channel from {@code a} down to {@code b} (relative), jagged, with branches forking off. */
		private void channel(RandomSource r, Vec3 a, Vec3 b, float width, int depth) {
			List<Vec3> pts = new ArrayList<>();
			pts.add(a);
			pts.add(b);
			double spread = a.distanceTo(b) * 0.12;
			for (int pass = 0; pass < 6; pass++) {
				List<Vec3> next = new ArrayList<>();
				for (int i = 0; i + 1 < pts.size(); i++) {
					Vec3 p = pts.get(i);
					Vec3 q = pts.get(i + 1);
					next.add(p);
					next.add(p.add(q).scale(0.5).add((r.nextDouble() - 0.5) * spread, (r.nextDouble() - 0.5) * spread * 0.3, (r.nextDouble() - 0.5) * spread));
				}
				next.add(pts.get(pts.size() - 1));
				pts = next;
				spread *= 0.55;
			}
			for (int i = 0; i + 1 < pts.size(); i++) {
				Vec3 p = pts.get(i);
				Vec3 q = pts.get(i + 1);
				this.segments.add(new float[] {(float) p.x, (float) p.y, (float) p.z, (float) q.x, (float) q.y, (float) q.z, width});
				// branches: forking off downwards and dying out short of the ground
				if (depth < 2 && r.nextFloat() < (depth == 0 ? 0.07F : 0.04F) && i < pts.size() * 0.8) {
					double len = a.distanceTo(b) * (0.15 + r.nextDouble() * 0.25);
					Vec3 dir = new Vec3(r.nextDouble() - 0.5, -0.6 - r.nextDouble() * 0.6, r.nextDouble() - 0.5).normalize();
					this.channel(r, p, p.add(dir.scale(len)), width * 0.45F, depth + 1);
				}
			}
		}

		/** How bright it is now (0-1): it flickers as return strokes run up the channel again. */
		public float brightness(float partialTick) {
			float t = this.age + partialTick;
			if (t > this.life) {
				return 0.0F;
			}
			float pulse = (this.seed >> (int) (t * 0.7F) & 1) == 0 ? 1.0F : 0.35F;
			return pulse * (1.0F - t / this.life * 0.7F);
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
						mc.level.addParticle(ParticleTypes.RAIN, x + RANDOM.nextDouble(), y + 0.05, z + RANDOM.nextDouble(), 0.0, 0.0, 0.0);
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
		if (!real) {
			BOLTS.add(new Bolt(top, ground, RANDOM.nextLong()));
		}
		flash(top.x, top.z, 160.0F + RANDOM.nextFloat() * 120.0F, 3 + RANDOM.nextInt(4));
		double d = cam.distanceTo(ground);
		DynamicLights.flash(ground.add(0.0, 6.0, 0.0), 0xD8E4FF, 1.0F, (float) Mth.clamp(60.0 + d * 0.05, 60.0, 160.0), 260.0F);
		DynamicLights.flash(top.lerp(ground, 0.5), 0xC8D6FF, 0.8F, 140.0F, 200.0F);
		BlastShader.flash((float) Mth.clamp(0.55 - d / 1800.0, 0.06, 0.55));
		if (d < 300.0) {
			ClientEffects.setFlash((float) (0.35 * (1.0 - d / 300.0)), 0xDDE6FF);
		}
		thunder(mc, ground.add(0.0, 30.0, 0.0));
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
