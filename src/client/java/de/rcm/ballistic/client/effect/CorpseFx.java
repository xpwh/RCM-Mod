package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.injury.Blood;
import de.rcm.ballistic.injury.Corpses;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * What happens to a body left lying. The skin goes pale and then grey-blue as the blood sinks out of it
 * (a tint on the model, {@code LivingEntityRendererMixin}); the blood on the wounds and on the ground stops
 * shining and dries dark (GoreMesh, BloodClient). After a while the first flies find it - a few, then more,
 * buzzing round it in jerky loops, settling on it, crawling, taking off again - and the air over it begins
 * to stink: pale, greenish wisps rising off it, drifting and spreading as they go.
 */
public final class CorpseFx {
	private static final RenderType FLY = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/effect/fly.png"));
	private static final RenderType STENCH = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/effect/stench.png"));
	/** When the first fly comes (ticks dead), and how many come after that. */
	private static final int FLIES_FROM = 300;
	private static final int MAX_FLIES = 10;
	/** When it starts to stink. */
	private static final int STENCH_FROM = 700;
	private static final int MAX_WISPS = 320;
	private static final RandomSource RANDOM = RandomSource.create();

	private static final class Fly {
		double x;
		double y;
		double z;
		double px;
		double py;
		double pz;
		double vx;
		double vy;
		double vz;
		/** Where it means to settle (null: just flying about), and how long it stays once there. */
		Vec3 spot;
		int sitting;
	}

	private static final class Wisp {
		double x;
		double y;
		double z;
		double px;
		double py;
		double pz;
		double vx;
		double vy;
		double vz;
		int age;
		int life;
		float size;
		float spin;
	}

	/** How a body lies, a tick ago and now (its turn, synced from the server), and how its limbs flop. */
	private static final class Lying {
		final org.joml.Quaternionf prev = new org.joml.Quaternionf();
		final org.joml.Quaternionf now = new org.joml.Quaternionf();
		float flail;
		float pflail;
		double lx = Double.NaN;
		double ly;
		double lz;
		int seen;
	}

	private static final Int2ObjectOpenHashMap<Lying> LYING = new Int2ObjectOpenHashMap<>();
	private static final Int2ObjectOpenHashMap<List<Fly>> FLIES = new Int2ObjectOpenHashMap<>();
	private static final List<Wisp> WISPS = new ArrayList<>();

	private CorpseFx() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(CorpseFx::tick);
		WorldRenderEvents.AFTER_ENTITIES.register(CorpseFx::render);
	}

	/** How long {@code e} has lain dead (ticks), or -1 if it is no body left lying. */
	public static float age(LivingEntity e, float partial) {
		if (e instanceof Mannequin) {
			Long until = e.getAttached(Corpses.UNTIL);
			return until == null ? -1.0F : Corpses.LIFETIME - (until - e.level().getGameTime()) + partial;
		}
		if (e instanceof Player || e.deathTime <= 0 || !Blood.bleeds(e)) {
			return -1.0F;
		}
		return e.deathTime + partial;
	}

	/** How far the skin has paled (0..1) after lying dead {@code age} ticks: it starts within the minute. */
	public static float pallor(float age) {
		if (age < 0.0F) {
			return 0.0F;
		}
		return Mth.clamp((age - 60.0F) / 1500.0F, 0.0F, 1.0F);
	}

	/** {@code color} drained of its blood: the warm reds gone, a cold grey-blue cast. */
	public static int tint(int color, float pale) {
		float r = 1.0F - 0.3F * pale;
		float g = 1.0F - 0.2F * pale;
		float b = 1.0F - 0.1F * pale;
		int a = color >>> 24;
		int cr = (int) (((color >> 16) & 0xFF) * r);
		int cg = (int) (((color >> 8) & 0xFF) * g);
		int cb = (int) ((color & 0xFF) * b);
		return a << 24 | cr << 16 | cg << 8 | cb;
	}

	/** Follows how the body lies (the server's turn), and how hard it moves - for its limbs to flop. */
	private static void lying(LivingEntity e, long now) {
		Lying l = LYING.computeIfAbsent(e.getId(), k -> {
			Lying n = new Lying();
			n.now.set(de.rcm.ballistic.injury.CorpsePose.turn(e));
			n.prev.set(n.now);
			return n;
		});
		l.seen = (int) now;
		l.prev.set(l.now);
		l.now.set(de.rcm.ballistic.injury.CorpsePose.turn(e));
		l.pflail = l.flail;
		double speed = 0.0;
		if (!Double.isNaN(l.lx)) {
			speed = Math.sqrt(Mth.square(e.getX() - l.lx) + Mth.square(e.getY() - l.ly) + Mth.square(e.getZ() - l.lz));
			if (speed > 3.0) {
				speed = 0.0; // moved by a teleport, not thrown
			}
		}
		l.lx = e.getX();
		l.ly = e.getY();
		l.lz = e.getZ();
		float turning = new org.joml.Quaternionf(l.prev).conjugate().mul(l.now).angle();
		float want = Mth.clamp((float) speed * 5.0F + Math.abs(turning) * 4.0F, 0.0F, 1.0F);
		l.flail += (want - l.flail) * (want > l.flail ? 0.5F : 0.15F);
	}

	/**
	 * How the body {@code e} lies just now: {turn x, y, z, w, middle x, y, z, lift, flop} (see {@code CorpsePose}),
	 * or null if it is no body left lying.
	 */
	public static float[] pose(LivingEntity e, float partial) {
		Lying l = LYING.get(e.getId());
		org.joml.Quaternionf q = l == null ? de.rcm.ballistic.injury.CorpsePose.turn(e) : new org.joml.Quaternionf(l.prev).slerp(l.now, partial);
		org.joml.Vector3f c = de.rcm.ballistic.injury.CorpsePose.centre(e);
		float flail = l == null ? 0.0F : Mth.lerp(partial, l.pflail, l.flail);
		return new float[] {q.x, q.y, q.z, q.w, c.x, c.y, c.z, de.rcm.ballistic.injury.CorpsePose.lift(e), flail};
	}

	/** Puts {@code pose} on a pose stack set at the body's feet in world directions ({@code scale}: the model's own). */
	public static void apply(PoseStack poseStack, float[] pose, float scale) {
		float cx = pose[4] / scale;
		float cy = pose[5] / scale;
		float cz = pose[6] / scale;
		poseStack.translate(cx, cy, cz);
		poseStack.mulPose(new org.joml.Quaternionf(pose[0], pose[1], pose[2], pose[3]));
		poseStack.translate(-cx, -cy, -cz);
		poseStack.translate(0.0F, pose[7] / scale, 0.0F);
	}

	/** The middle of a body lying on the ground, and how far it reaches. */
	private static Vec3 centre(LivingEntity e) {
		return e.position().add(0, Math.min(0.35, e.getBbHeight() * 0.3), 0);
	}

	private static float reach(LivingEntity e) {
		return Mth.clamp(Math.max(e.getBbWidth(), e.getBbHeight()) * 0.55F, 0.35F, 1.2F);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			FLIES.clear();
			WISPS.clear();
			LYING.clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		boolean on = ModConfig.gore;
		java.util.Set<Integer> bodies = new java.util.HashSet<>();
		long now = mc.level.getGameTime();
		for (Entity en : mc.level.entitiesForRendering()) {
			if (!(en instanceof LivingEntity e) || e.distanceToSqr(cam) > 64.0 * 64.0) {
				continue;
			}
			float age = age(e, 0.0F);
			if (age < 0.0F) {
				continue;
			}
			lying(e, now);
			if (on && age >= FLIES_FROM && e.distanceToSqr(cam) < 48.0 * 48.0) {
				bodies.add(e.getId());
				body(mc, e, age);
			}
		}
		LYING.int2ObjectEntrySet().removeIf(en -> en.getValue().seen != (int) now);
		FLIES.int2ObjectEntrySet().removeIf(en -> !bodies.contains(en.getIntKey()));
		for (int i = WISPS.size() - 1; i >= 0; i--) {
			Wisp w = WISPS.get(i);
			w.px = w.x;
			w.py = w.y;
			w.pz = w.z;
			w.x += w.vx;
			w.y += w.vy;
			w.z += w.vz;
			// eddies: it wanders as it rises, and slows
			w.vx = w.vx * 0.97 + (RANDOM.nextDouble() - 0.5) * 0.0025;
			w.vz = w.vz * 0.97 + (RANDOM.nextDouble() - 0.5) * 0.0025;
			w.vy *= 0.985;
			if (++w.age > w.life) {
				WISPS.remove(i);
			}
		}
	}

	private static void body(Minecraft mc, LivingEntity e, float age) {
		Vec3 c = centre(e);
		float reach = reach(e);
		// flies: one, then more and more; a big body draws more
		List<Fly> flies = FLIES.computeIfAbsent(e.getId(), k -> new ArrayList<>());
		int want = Math.min(MAX_FLIES, 1 + (int) ((age - FLIES_FROM) / 160.0F * Math.max(0.6F, reach)));
		if (flies.size() < want && RANDOM.nextInt(40) == 0) {
			// it comes in from somewhere off to the side
			Fly f = new Fly();
			double a = RANDOM.nextDouble() * Math.PI * 2.0;
			f.x = f.px = c.x + Math.cos(a) * 2.5;
			f.y = f.py = c.y + 0.6 + RANDOM.nextDouble();
			f.z = f.pz = c.z + Math.sin(a) * 2.5;
			flies.add(f);
		}
		for (Fly f : flies) {
			fly(f, c, reach);
		}
		// the stink: more of it, the longer it lies
		if (age > STENCH_FROM && WISPS.size() < MAX_WISPS) {
			int every = Math.max(5, 34 - (int) ((age - STENCH_FROM) / 70.0F));
			if (RANDOM.nextInt(every) == 0) {
				Wisp w = new Wisp();
				w.x = w.px = c.x + (RANDOM.nextDouble() - 0.5) * reach * 1.4;
				w.y = w.py = c.y + 0.05 + RANDOM.nextDouble() * 0.2;
				w.z = w.pz = c.z + (RANDOM.nextDouble() - 0.5) * reach * 1.4;
				w.vx = (RANDOM.nextDouble() - 0.5) * 0.006;
				w.vy = 0.006 + RANDOM.nextDouble() * 0.006;
				w.vz = (RANDOM.nextDouble() - 0.5) * 0.006;
				w.life = 90 + RANDOM.nextInt(80);
				w.size = (0.35F + RANDOM.nextFloat() * 0.3F) * Math.max(0.7F, reach);
				w.spin = RANDOM.nextFloat() * Mth.TWO_PI;
				WISPS.add(w);
			}
		}
	}

	/** A fly's jerky flight round the body; now and then it lands on it, sits, crawls a little, and is off again. */
	private static void fly(Fly f, Vec3 c, float reach) {
		f.px = f.x;
		f.py = f.y;
		f.pz = f.z;
		if (f.sitting > 0) {
			if (--f.sitting % 9 == 0) {
				// a few steps across the body
				f.x += (RANDOM.nextDouble() - 0.5) * 0.03;
				f.z += (RANDOM.nextDouble() - 0.5) * 0.03;
			}
			if (f.sitting == 0) {
				f.spot = null;
				f.vy = 0.08;
				f.vx = (RANDOM.nextDouble() - 0.5) * 0.15;
				f.vz = (RANDOM.nextDouble() - 0.5) * 0.15;
			}
			return;
		}
		double tx;
		double ty;
		double tz;
		if (f.spot != null) {
			tx = f.spot.x;
			ty = f.spot.y;
			tz = f.spot.z;
		} else {
			// loops round the body, a little above it
			tx = c.x;
			ty = c.y + 0.35;
			tz = c.z;
			if (RANDOM.nextInt(70) == 0) {
				f.spot = new Vec3(c.x + (RANDOM.nextDouble() - 0.5) * reach * 1.2, c.y + reach * 0.35 + 0.02, c.z + (RANDOM.nextDouble() - 0.5) * reach * 1.2);
			}
		}
		double dx = tx - f.x;
		double dy = ty - f.y;
		double dz = tz - f.z;
		double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (f.spot != null && d < 0.04) {
			f.x = tx;
			f.y = ty;
			f.z = tz;
			f.vx = f.vy = f.vz = 0.0;
			f.sitting = 30 + RANDOM.nextInt(120);
			return;
		}
		double pull = f.spot != null ? 0.035 : 0.012;
		double jitter = f.spot != null ? 0.02 : 0.06;
		f.vx = f.vx * 0.86 + dx / Math.max(d, 0.1) * pull + (RANDOM.nextDouble() - 0.5) * jitter;
		f.vy = f.vy * 0.86 + dy / Math.max(d, 0.1) * pull + (RANDOM.nextDouble() - 0.5) * jitter * 0.7;
		f.vz = f.vz * 0.86 + dz / Math.max(d, 0.1) * pull + (RANDOM.nextDouble() - 0.5) * jitter;
		double speed = Math.sqrt(f.vx * f.vx + f.vy * f.vy + f.vz * f.vz);
		if (speed > 0.16) {
			f.vx *= 0.16 / speed;
			f.vy *= 0.16 / speed;
			f.vz *= 0.16 / speed;
		}
		f.x += f.vx;
		f.y = Math.max(c.y - 0.25, f.y + f.vy);
		f.z += f.vz;
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || FLIES.isEmpty() && WISPS.isEmpty()) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		Vector3f up = new Vector3f(mc.gameRenderer.getMainCamera().upVector());
		Vector3f right = new Vector3f(mc.gameRenderer.getMainCamera().leftVector()).negate();
		PoseStack poseStack = context.matrices();
		if (!FLIES.isEmpty()) {
			context.commandQueue().submitCustomGeometry(poseStack, FLY, (pose, consumer) -> {
				for (List<Fly> flies : FLIES.values()) {
					for (Fly f : flies) {
						float x = (float) (Mth.lerp(partial, f.px, f.x) - cam.x);
						float y = (float) (Mth.lerp(partial, f.py, f.y) - cam.y);
						float z = (float) (Mth.lerp(partial, f.pz, f.z) - cam.z);
						if (x * x + y * y + z * z > 24.0F * 24.0F) {
							continue;
						}
						int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(f.x, f.y, f.z));
						// sitting: wings folded; in the air: a blur of wings
						float u0 = f.sitting > 0 ? 0.0F : 0.5F;
						quad(pose, consumer, x, y, z, right, up, 0.03F, 0.0F, u0, 0.0F, u0 + 0.5F, 1.0F, 0xFFFFFFFF, light);
					}
				}
			});
		}
		if (!WISPS.isEmpty()) {
			// back to front: they are see-through
			List<Wisp> sorted = new ArrayList<>(WISPS);
			sorted.sort((a, b) -> Double.compare(dist(b, cam), dist(a, cam)));
			context.commandQueue().submitCustomGeometry(poseStack, STENCH, (pose, consumer) -> {
				for (Wisp w : sorted) {
					float x = (float) (Mth.lerp(partial, w.px, w.x) - cam.x);
					float y = (float) (Mth.lerp(partial, w.py, w.y) - cam.y);
					float z = (float) (Mth.lerp(partial, w.pz, w.z) - cam.z);
					float t = (w.age + partial) / w.life;
					// faint: there to see if you look, more a shimmer of foul air than smoke
					float alpha = 0.16F * Mth.clamp(t * 5.0F, 0.0F, 1.0F) * Mth.clamp((1.0F - t) * 2.5F, 0.0F, 1.0F);
					int a = (int) (alpha * 255.0F);
					if (a <= 0) {
						continue;
					}
					float size = w.size * (0.6F + 0.9F * t);
					int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(w.x, w.y, w.z));
					int color = a << 24 | 0xB4BE82;
					quad(pose, consumer, x, y, z, right, up, size, w.spin + t * 0.8F, 0.0F, 0.0F, 1.0F, 1.0F, color, light);
				}
			});
		}
	}

	private static double dist(Wisp w, Vec3 cam) {
		return (w.x - cam.x) * (w.x - cam.x) + (w.y - cam.y) * (w.y - cam.y) + (w.z - cam.z) * (w.z - cam.z);
	}

	/** A quad facing the camera, {@code size} across, turned by {@code spin}. */
	private static void quad(PoseStack.Pose pose, VertexConsumer consumer, float x, float y, float z, Vector3f right, Vector3f up, float size, float spin,
		float u0, float v0, float u1, float v1, int color, int light) {
		float cs = Mth.cos(spin) * size * 0.5F;
		float sn = Mth.sin(spin) * size * 0.5F;
		// the quad's own axes, turned in the camera's plane
		float ax = right.x * cs + up.x * sn;
		float ay = right.y * cs + up.y * sn;
		float az = right.z * cs + up.z * sn;
		float bx = up.x * cs - right.x * sn;
		float by = up.y * cs - right.y * sn;
		float bz = up.z * cs - right.z * sn;
		float[][] q = {{-1, -1, u0, v1}, {1, -1, u1, v1}, {1, 1, u1, v0}, {-1, 1, u0, v0}};
		for (float[] p : q) {
			consumer.addVertex(pose, x + ax * p[0] + bx * p[1], y + ay * p[0] + by * p[1], z + az * p[0] + bz * p[1]).setColor(color).setUv(p[2], p[3])
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0F, 1.0F, 0.0F);
		}
	}
}
