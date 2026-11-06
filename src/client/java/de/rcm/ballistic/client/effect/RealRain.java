package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.WeatherRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Rain as it looks: not Minecraft's blue sheets but single drops, each a thin streak smeared by its own fall,
 * pale and half see-through, the near ones finer and sharper, the far ones fading into the grey; the wind drives
 * them aslant. Where they land they splash: a few beads thrown up off the ground, a ring on water and on the
 * puddles, a fine spray over everything when it pours.
 * <p>
 * Minecraft still works out where it rains (the columns round the camera, from where to where, how they are
 * lit); only how it is drawn and what the drops do on landing change.
 */
public final class RealRain {
	private static final Identifier STREAK = BallisticMissiles.id("textures/environment/rain_streak.png");
	/** How fast the drops fall (blocks a second): 6-9 m/s for real rain. */
	private static final float SPEED = 8.5F;
	/** Every drop comes round again this many blocks further up (anchored to the world, not to the camera). */
	private static final float LOOP = 9.0F;

	private RealRain() {
	}

	public static boolean active() {
		return ModConfig.realRain;
	}

	private static float frac(long h, int shift) {
		return ((h >>> shift) & 0xFFFF) / 65536.0F;
	}

	private static long hash(int x, int z, int k) {
		long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ k * 0x165667B19E3779F9L;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return h;
	}

	/** The rain round the camera, drop by drop, in the columns Minecraft found it falling in. */
	public static void render(MultiBufferSource buffers, Vec3 cam, WeatherRenderState state) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || state.rainColumns.isEmpty()) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		long gameTime = level.getGameTime();
		double time = ((gameTime % 2400000L) + partial) / 20.0;
		float seconds = ((gameTime % 72000L) + partial) / 20.0F;
		float dir = Wind.direction(seconds);
		float wind = Wind.strength(seconds, partial);
		// the wind drives the drops along: how far aside they go for every block they fall
		float slantX = Mth.cos(dir) * wind * 0.32F;
		float slantZ = Mth.sin(dir) * wind * 0.32F;
		float dl = Mth.sqrt(slantX * slantX + 1.0F + slantZ * slantZ);
		float ax = slantX / dl;
		float ay = -1.0F / dl;
		float az = slantZ / dl;
		float intensity = state.intensity;
		float radius = Math.max(3, state.radius);
		// more drops the harder it rains, each column a handful per loop
		int perLoop = Math.max(1, Math.round(1.0F + 4.0F * intensity + 2.0F * level.getThunderLevel(partial)));
		VertexConsumer vc = buffers.getBuffer(RenderTypes.weather(STREAK, Minecraft.useShaderTransparency()));
		for (WeatherEffectRenderer.ColumnInstance col : state.rainColumns) {
			int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, col.x(), col.z());
			float bottom = Math.max(col.bottomY(), ground);
			float top = col.topY();
			if (top <= bottom) {
				continue;
			}
			double hx = col.x() + 0.5 - cam.x;
			double hz = col.z() + 0.5 - cam.z;
			float horiz = (float) Math.sqrt(hx * hx + hz * hz);
			float edge = Mth.clamp(horiz / (radius + 0.5F), 0.0F, 1.0F);
			float columnAlpha = (1.0F - edge * edge) * (0.55F + 0.45F * Mth.sqrt(intensity));
			if (columnAlpha <= 0.01F) {
				continue;
			}
			for (int k = 0; k < perLoop; k++) {
				long h = hash(col.x(), col.z(), k);
				float fx = frac(h, 0);
				float fz = frac(h, 16);
				float phase = frac(h, 32) * LOOP;
				float speed = SPEED * (0.85F + 0.3F * frac(h, 48));
				float size = frac(h, 8);
				// where this drop's head is now: the same drop each loop, falling through the world's heights
				double fall = time * speed + phase;
				double first = Math.ceil((bottom + fall) / LOOP);
				for (double m = first; m * LOOP - fall <= top + 1.0; m++) {
					float y = (float) (m * LOOP - fall);
					// the length it is smeared over (the faster, the longer), clipped at the ground and at the top
					float len = (0.38F + 0.4F * size) * speed / SPEED;
					if (y < bottom || y - ay * len > top + 1.0F) {
						continue;
					}
					// it lands where its column's ground is: still upwind of it by as much as it has left to fall
					float drift = y - ground;
					double px = col.x() + fx - slantX * drift - cam.x;
					double py = y - cam.y;
					double pz = col.z() + fz - slantZ * drift - cam.z;
					float dist = (float) Math.sqrt(px * px + py * py + pz * pz);
					if (dist < 0.6F) {
						continue;
					}
					// far drops would be thinner than a pixel and flicker: kept a pixel wide, fainter instead
					float width = 0.008F + 0.006F * size;
					float minWidth = dist * 0.0021F;
					float alpha = columnAlpha * Mth.clamp((dist - 0.6F) / 1.4F, 0.0F, 1.0F) * (0.3F + 0.18F * size);
					if (width < minWidth) {
						alpha *= width / minWidth;
						width = minWidth;
					}
					if (alpha < 0.012F) {
						continue;
					}
					// the head (lower end) and the tail up the line it came down
					float x1 = (float) px;
					float y1 = (float) py;
					float z1 = (float) pz;
					float x0 = x1 - ax * len;
					float y0 = y1 - ay * len;
					float z0 = z1 - az * len;
					// turned about its own line to face the eye
					float sx = ay * z1 - az * y1;
					float sy = az * x1 - ax * z1;
					float sz = ax * y1 - ay * x1;
					float sl = Mth.sqrt(sx * sx + sy * sy + sz * sz);
					if (sl < 1.0E-4F) {
						continue;
					}
					float half = width * 0.5F / sl;
					sx *= half;
					sy *= half;
					sz *= half;
					int a = Mth.clamp((int) (alpha * 255.0F), 0, 255);
					int colour = a << 24 | 0xD4DCE4;
					int light = col.lightCoords();
					vc.addVertex(x0 - sx, y0 - sy, z0 - sz).setUv(0.0F, 0.0F).setColor(colour).setLight(light);
					vc.addVertex(x0 + sx, y0 + sy, z0 + sz).setUv(1.0F, 0.0F).setColor(colour).setLight(light);
					vc.addVertex(x1 + sx, y1 + sy, z1 + sz).setUv(1.0F, 1.0F).setColor(colour).setLight(light);
					vc.addVertex(x1 - sx, y1 - sy, z1 - sz).setUv(0.0F, 1.0F).setColor(colour).setLight(light);
				}
			}
		}
	}

	/**
	 * A raindrop landing (where Minecraft would put its blue splash): beads bouncing off the ground, a ring on
	 * water or on a puddle, now and then a breath of spray. Anything else (the hiss on lava) as it was.
	 */
	public static void splash(ClientLevel level, ParticleOptions options, double x, double y, double z, double dx, double dy, double dz) {
		if (!active() || options != ParticleTypes.RAIN) {
			level.addParticle(options, x, y, z, dx, dy, dz);
			return;
		}
		RandomSource r = level.getRandom();
		BlockPos at = BlockPos.containing(x, y - 0.05, z);
		boolean water = level.getFluidState(at).is(FluidTags.WATER) || level.getFluidState(at.above()).is(FluidTags.WATER);
		if (water) {
			// a small ring spreading on the water, sometimes a bead jumping up out of it
			level.addParticle(ModRegistry.WATER_RIPPLE, x, y + 0.012, z, 0.07 + r.nextFloat() * 0.14, 0.0, 0.0);
			if (r.nextFloat() < 0.3F) {
				level.addParticle(ModRegistry.WATER_DROP, x, y + 0.03, z, (r.nextFloat() - 0.5F) * 0.02, 0.07 + r.nextFloat() * 0.06,
					(r.nextFloat() - 0.5F) * 0.02);
			}
			return;
		}
		// on the ground: a crown of a few beads, low and short
		int beads = 1 + r.nextInt(3);
		for (int i = 0; i < beads; i++) {
			float a = r.nextFloat() * Mth.TWO_PI;
			float out = 0.012F + r.nextFloat() * 0.022F;
			level.addParticle(ModRegistry.WATER_DROP, x, y + 0.02, z, Mth.cos(a) * out, 0.045 + r.nextFloat() * 0.06, Mth.sin(a) * out);
		}
		// on a flat top in a downpour the puddles stand: rings on them too
		boolean flat = y - Math.floor(y) < 0.02 || y - Math.floor(y) > 0.98;
		float puddles = Storm.puddles();
		if (flat && puddles > 0.2F && r.nextFloat() < puddles * 0.7F) {
			level.addParticle(ModRegistry.WATER_RIPPLE, x, y + 0.012, z, 0.05 + r.nextFloat() * 0.09, 0.0, 0.0);
		}
		// a fine spray hanging low over the ground when it really pours
		float pour = level.getRainLevel(1.0F) * (0.4F + 0.6F * level.getThunderLevel(1.0F));
		if (r.nextFloat() < 0.05F * pour) {
			level.addParticle(ModRegistry.WATER_MIST, x, y + 0.08, z, (r.nextFloat() - 0.5F) * 0.02, 0.006, (r.nextFloat() - 0.5F) * 0.02);
		}
	}
}
