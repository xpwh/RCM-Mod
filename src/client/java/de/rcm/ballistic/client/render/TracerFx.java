package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3f;

/**
 * Tracer rounds as you see them. A tracer carries a pyrotechnic charge in its base, lit by the
 * propellant: it shows dim for the first few metres, then burns bright green (Soviet 7.62x39 T-45)
 * along the bullet's real, dropping trajectory. The eye holds the light for a moment, so the moving
 * point is drawn out into a glowing dash that tapers off behind it - long at night, short and pale
 * by day. Tracers skip off hard ground and walls at shallow angles, often leaping steeply upward,
 * and one that buries itself keeps glowing where it lies until the charge is spent. After about
 * 900 m (some three seconds' flight here) the charge gutters, reddens and goes out.
 * <p>
 * A tracer that hits sprays burning compound; at night it lights up what it passes.
 * <p>
 * Purely visual and client-side, flown with the same ballistics as {@code BulletEntity} from the
 * moment of the shot, so it is there from the first frame whatever the network does.
 */
public final class TracerFx {
	private static final double GRAVITY = 0.0245;
	private static final double DRAG = 0.988;
	/** Ticks of burning flight (about 900 m). */
	private static final int BURN = 30;
	private static final int CAP = 256;
	private static final List<Tracer> TRACERS = new ArrayList<>();

	private TracerFx() {
	}

	private static final class Tracer {
		final int shooter;
		final float seed;
		Vec3 pos;
		Vec3 vel;
		int age;
		int ricochets;
		/** Path corners: time (ticks since the shot) and where it was then; newest last. */
		final List<double[]> path = new ArrayList<>();
		boolean stopped;
		int emberLife;

		Tracer(Vec3 muzzle, Vec3 vel, int shooter) {
			this.pos = muzzle;
			this.vel = vel;
			this.shooter = shooter;
			this.seed = (float) Math.random() * 100.0F;
			this.path.add(new double[] {0.0, muzzle.x, muzzle.y, muzzle.z});
		}
	}

	public static void fire(Vec3 muzzle, Vec3 velocity, int shooter) {
		if (TRACERS.size() >= CAP) {
			TRACERS.remove(0);
		}
		TRACERS.add(new Tracer(muzzle, velocity, shooter));
	}

	// ------------------------------------------------------------------ flight

	public static void tick(Minecraft mc) {
		Level level = mc.level;
		if (level == null) {
			TRACERS.clear();
			return;
		}
		for (int i = TRACERS.size() - 1; i >= 0; i--) {
			Tracer t = TRACERS.get(i);
			t.age++;
			// the burning pellet lights what it passes (the dynamic light only shows where it is dark)
			if (t.age > 1 && (t.stopped ? t.emberLife > 4 : t.age < BURN)) {
				de.rcm.ballistic.client.effect.DynamicLights.steady(t.pos, 0x8CFF6A, t.stopped ? 0.25F : 0.5F, t.stopped ? 3.0F : 6.0F);
			}
			if (t.stopped) {
				if (--t.emberLife <= 0) {
					TRACERS.remove(i);
				}
				continue;
			}
			if (t.age > BURN + 2) {
				TRACERS.remove(i);
				continue;
			}
			step(level, t);
			while (t.path.size() > 8) {
				t.path.remove(0);
			}
		}
	}

	private static void step(Level level, Tracer t) {
		double time = t.age - 1;
		double left = 1.0; // fraction of this tick still to fly
		for (int bounce = 0; bounce < 3 && left > 1.0E-3; bounce++) {
			Vec3 next = t.pos.add(t.vel.scale(left));
			BlockHitResult hit = level.clip(new ClipContext(t.pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, CollisionContext.empty()));
			Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
			// people and animals stop it
			Vec3 body = hitBody(level, t, t.pos, end);
			if (body != null) {
				corner(t, time + left * fraction(t.pos, body, next), body);
				t.pos = body;
				t.stopped = true;
				t.emberLife = 0;
				return;
			}
			if (hit.getType() == HitResult.Type.MISS) {
				break;
			}
			double f = fraction(t.pos, end, next);
			double at = time + (1.0 - left) + left * f;
			corner(t, at, end);
			BlockState state = level.getBlockState(hit.getBlockPos());
			if (!state.getFluidState().isEmpty()) {
				t.pos = end;
				t.stopped = true;
				t.emberLife = 2; // quenched
				return;
			}
			Direction face = hit.getDirection();
			Vec3 n = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
			Vec3 dir = t.vel.normalize();
			double incidence = -dir.dot(n);
			SoundType sound = state.getSoundType();
			boolean hard = sound == SoundType.STONE || sound == SoundType.METAL || sound == SoundType.DEEPSLATE || sound == SoundType.DEEPSLATE_BRICKS
				|| sound == SoundType.ANVIL || sound == SoundType.COPPER || state.getBlock().getExplosionResistance() >= 6.0F;
			double chance = hard ? (incidence < 0.4 ? 0.8 : incidence < 0.6 ? 0.25 : 0.0) : (incidence < 0.22 ? 0.55 : 0.0);
			if (t.ricochets < 3 && Math.random() < chance) {
				// skips off, losing most of its speed, very often kicked steeply upward
				t.ricochets++;
				sparks(level, end, n, 6);
				Vec3 out = dir.subtract(n.scale(2.0 * dir.dot(n)));
				out = out.add((Math.random() - 0.5) * 0.5, Math.random() * 0.6, (Math.random() - 0.5) * 0.5).normalize();
				t.vel = out.scale(t.vel.length() * (0.3 + Math.random() * 0.2));
				t.pos = end.add(n.scale(0.02));
				left *= 1.0 - f;
				continue;
			}
			// buried: keeps burning where it lies, after a spray of burning compound
			sparks(level, end, n, 10);
			t.pos = end.add(n.scale(0.03));
			t.stopped = true;
			t.emberLife = Math.max(6, Math.min(BURN - t.age, 30 + (int) (Math.random() * 30)));
			return;
		}
		t.pos = t.pos.add(t.vel.scale(left));
		t.vel = t.vel.scale(DRAG).add(0, -GRAVITY, 0);
		corner(t, t.age, t.pos);
	}

	/** Burning tracer compound knocked off on impact. */
	private static void sparks(Level level, Vec3 at, Vec3 normal, int count) {
		for (int i = 0; i < count; i++) {
			Vec3 v = normal.scale(0.08 + Math.random() * 0.12).add((Math.random() - 0.5) * 0.25, Math.random() * 0.15, (Math.random() - 0.5) * 0.25);
			level.addParticle(de.rcm.ballistic.ModRegistry.SPARK, at.x, at.y, at.z, v.x, v.y, v.z);
		}
	}

	private static double fraction(Vec3 from, Vec3 at, Vec3 to) {
		double full = to.distanceTo(from);
		return full < 1.0E-6 ? 0.0 : at.distanceTo(from) / full;
	}

	private static void corner(Tracer t, double time, Vec3 p) {
		t.path.add(new double[] {time, p.x, p.y, p.z});
	}

	private static Vec3 hitBody(Level level, Tracer t, Vec3 from, Vec3 to) {
		Vec3 best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : level.getEntities((Entity) null, new AABB(from, to).inflate(0.5),
			e -> e instanceof LivingEntity && e.isAlive() && (e.getId() != t.shooter || t.age > 3))) {
			var clip = e.getBoundingBox().inflate(0.05).clip(from, to);
			if (clip.isPresent()) {
				double d = clip.get().distanceToSqr(from);
				if (d < bestD) {
					bestD = d;
					best = clip.get();
				}
			}
		}
		return best;
	}

	/** Where the tracer was at {@code time} (ticks since the shot), along its recorded path. */
	private static Vector3f at(Tracer t, double time, Vec3 cam) {
		List<double[]> p = t.path;
		double[] a = p.get(0);
		if (time <= a[0]) {
			return new Vector3f((float) (a[1] - cam.x), (float) (a[2] - cam.y), (float) (a[3] - cam.z));
		}
		for (int i = 1; i < p.size(); i++) {
			double[] b = p.get(i);
			if (time <= b[0]) {
				double f = (time - a[0]) / Math.max(1.0E-6, b[0] - a[0]);
				return new Vector3f((float) (a[1] + (b[1] - a[1]) * f - cam.x), (float) (a[2] + (b[2] - a[2]) * f - cam.y), (float) (a[3] + (b[3] - a[3]) * f - cam.z));
			}
			a = b;
		}
		// past the last corner: carry on with the current velocity (the next tick is not flown yet)
		double dt = time - a[0];
		return new Vector3f((float) (a[1] + t.vel.x * dt - cam.x), (float) (a[2] + t.vel.y * dt - cam.y), (float) (a[3] + t.vel.z * dt - cam.z));
	}

	// ------------------------------------------------------------------ drawing

	private static float night(Minecraft mc) {
		long t = mc.level.getDayTime() % 24000L;
		float n;
		if (t < 12000L) {
			n = t < 500L ? 0.5F : 0.0F;
		} else if (t < 13500L) {
			n = (t - 12000L) / 1500.0F;
		} else if (t < 22500L) {
			n = 1.0F;
		} else {
			n = Math.max(0.0F, (24000L - t) / 1500.0F);
		}
		return Math.max(n, mc.level.getRainLevel(1.0F) * 0.4F);
	}

	public static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (TRACERS.isEmpty() || mc.level == null) {
			return;
		}
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cam = camera.position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vector3f up = new Vector3f(camera.upVector());
		Vector3f right = new Vector3f(camera.forwardVector()).cross(up).normalize();
		float night = night(mc);
		PoseStack poseStack = context.matrices();
		context.commandQueue().submitCustomGeometry(poseStack, MissileRenderer.GLOW_TYPE, (pose, consumer) -> {
			for (Tracer t : TRACERS) {
				draw(t, pose, consumer, cam, up, right, partial, night);
			}
		});
	}

	private static void draw(Tracer t, PoseStack.Pose pose, VertexConsumer consumer, Vec3 cam, Vector3f up, Vector3f right, float partial, float night) {
		double now = t.stopped ? t.path.get(t.path.size() - 1)[0] : t.age + partial;
		float life = (float) (t.age + partial);
		// lit by the propellant: dull for the first few metres
		float ignite = Mth.clamp((float) now / 0.25F, 0.0F, 1.0F);
		// the charge gutters, reddens and dies near the end of its burn
		float burnLeft = t.stopped ? Math.min(1.0F, (t.emberLife - partial) / 10.0F) : Mth.clamp((BURN - life) / 7.0F, 0.0F, 1.0F);
		if (burnLeft <= 0.0F) {
			return;
		}
		float flicker = 0.8F + 0.2F * Mth.sin(life * 11.0F + t.seed) * Mth.sin(life * 27.0F + t.seed * 2.0F);
		if (burnLeft < 1.0F) {
			flicker *= 0.55F + 0.45F * Mth.sin(life * 41.0F + t.seed);
		}
		// bright enough to follow by day (a vivid moving point), dazzling at night
		float strength = ignite * burnLeft * flicker * (0.72F + 0.28F * night);
		if (strength <= 0.02F) {
			return;
		}
		// green while it burns hard, going yellow then red as it dies
		int glow = burnLeft > 0.6F ? 0x62F040 : burnLeft > 0.3F ? 0xC8E040 : 0xFF6A30;
		int core = burnLeft > 0.5F ? 0xF2FFE8 : 0xFFE0B0;
		Vector3f head = at(t, now, cam);
		float dist = head.length();
		// never smaller on screen than a burning point you can still pick out far downrange
		float size = Math.max(0.045F, dist * 0.0032F);

		// the dash the eye smears it into: back along the real path, tapering and fading
		if (!t.stopped) {
			double persist = 0.45 + 0.45 * night;
			int segments = 10;
			Vector3f prev = head;
			for (int s = 1; s <= segments; s++) {
				double time = Math.max(0.0, now - persist * s / segments);
				Vector3f p = at(t, time, cam);
				float f0 = (s - 1) / (float) segments;
				float f1 = s / (float) segments;
				float w0 = size * 0.55F * (1.0F - 0.8F * f0);
				float w1 = size * 0.55F * (1.0F - 0.8F * f1);
				float a0 = strength * (1.0F - f0) * (1.0F - f0);
				float a1 = strength * (1.0F - f1) * (1.0F - f1);
				ribbon(pose, consumer, prev, p, w0, w1, glow, a0 * 0.55F, a1 * 0.55F);
				ribbon(pose, consumer, prev, p, w0 * 0.35F, w1 * 0.35F, core, a0 * 0.8F, a1 * 0.8F);
				prev = p;
				if (time <= 0.0) {
					break;
				}
			}
		}
		// the burning pellet itself
		float halo = t.stopped ? 0.7F : 1.0F;
		billboard(pose, consumer, head, up, right, size * 2.6F * halo, glow, strength * 0.22F);
		billboard(pose, consumer, head, up, right, size * 1.2F, glow, strength * 0.7F);
		billboard(pose, consumer, head, up, right, size * 0.55F, core, Math.min(1.0F, strength * 1.2F));
	}

	private static void billboard(PoseStack.Pose pose, VertexConsumer consumer, Vector3f c, Vector3f up, Vector3f right, float r, int rgb, float alpha) {
		int color = colour(rgb, alpha);
		vertex(consumer, pose, c.x + (-right.x - up.x) * r, c.y + (-right.y - up.y) * r, c.z + (-right.z - up.z) * r, 0.0F, 1.0F, color);
		vertex(consumer, pose, c.x + (right.x - up.x) * r, c.y + (right.y - up.y) * r, c.z + (right.z - up.z) * r, 1.0F, 1.0F, color);
		vertex(consumer, pose, c.x + (right.x + up.x) * r, c.y + (right.y + up.y) * r, c.z + (right.z + up.z) * r, 1.0F, 0.0F, color);
		vertex(consumer, pose, c.x + (-right.x + up.x) * r, c.y + (-right.y + up.y) * r, c.z + (-right.z + up.z) * r, 0.0F, 0.0F, color);
	}

	/** A camera-facing strip from {@code a} to {@code b}, both windings. */
	private static void ribbon(PoseStack.Pose pose, VertexConsumer consumer, Vector3f a, Vector3f b, float wa, float wb, int rgb, float alphaA, float alphaB) {
		Vector3f axis = new Vector3f(b).sub(a);
		if (axis.lengthSquared() < 1.0E-8F) {
			return;
		}
		Vector3f side = new Vector3f(axis).cross(new Vector3f(a).add(b).mul(0.5F));
		if (side.lengthSquared() < 1.0E-10F) {
			return;
		}
		side.normalize();
		int ca = colour(rgb, alphaA);
		int cb = colour(rgb, alphaB);
		float[][] q = {
			{a.x + side.x * wa, a.y + side.y * wa, a.z + side.z * wa, 0.0F},
			{a.x - side.x * wa, a.y - side.y * wa, a.z - side.z * wa, 1.0F},
			{b.x - side.x * wb, b.y - side.y * wb, b.z - side.z * wb, 1.0F},
			{b.x + side.x * wb, b.y + side.y * wb, b.z + side.z * wb, 0.0F}
		};
		int[] cols = {ca, ca, cb, cb};
		for (int i : new int[] {0, 1, 2, 3, 3, 2, 1, 0}) {
			vertex(consumer, pose, q[i][0], q[i][1], q[i][2], q[i][3], 0.5F, cols[i]);
		}
	}

	private static int colour(int rgb, float alpha) {
		return (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24 | (rgb & 0xFFFFFF);
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int color) {
		consumer.addVertex(pose, x, y, z).setColor(color).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
