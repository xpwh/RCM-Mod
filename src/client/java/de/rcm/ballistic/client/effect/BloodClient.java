package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.injury.Blood;
import de.rcm.ballistic.network.ModNetworking.BloodPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3f;

/**
 * Blood. A hit throws drops out along the round's path (and a little back out of the entry wound); they
 * fly, fall and splash where they land - on the ground, up a wall behind the one who was hit - and the
 * stains stay a couple of minutes before they fade. A bleeding wound leaves a trail of drops, a blast a
 * spray all round, a body a pool that spreads beneath it. Switchable in the settings.
 */
public final class BloodClient {
	private static final RenderType TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/effect/blood.png"));
	private static final int MAX_DROPS = 900;
	private static final int MAX_STAINS = 700;
	private static final int LIFE = 2400;
	private static final int FADE = 500;

	private static final class Drop {
		double x;
		double y;
		double z;
		double vx;
		double vy;
		double vz;
		double px;
		double py;
		double pz;
		float size;
		int age;
	}

	private record Stain(Vec3 at, Direction face, float size, float angle, float stretch, int variant, long born, int grow, BlockPos block,
		BlockState state, int index) {
	}

	private static final List<Drop> DROPS = new ArrayList<>();
	private static final List<Stain> STAINS = new ArrayList<>();
	private static final RandomSource RANDOM = RandomSource.create();
	private static int counter;

	private BloodClient() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(BloodPayload.TYPE, (payload, context) -> receive(payload));
		ClientTickEvents.END_CLIENT_TICK.register(BloodClient::tick);
		WorldRenderEvents.BEFORE_ENTITIES.register(BloodClient::render);
	}

	private static void receive(BloodPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !ModConfig.blood) {
			return;
		}
		Vec3 at = new Vec3(p.x(), p.y(), p.z());
		Vec3 dir = new Vec3(p.dx(), p.dy(), p.dz());
		int n = Math.min(60, p.amount());
		switch (p.kind()) {
			case Blood.SPRAY -> {
				// out through the exit wound, fast and fanned; a few back out of the entry
				for (int i = 0; i < n; i++) {
					boolean back = i % 5 == 0;
					Vec3 d = back ? dir.scale(-0.4) : dir;
					spawn(at, d.scale(0.25 + RANDOM.nextDouble() * 0.35).add(gauss(0.09)).add(0, 0.05, 0), 0.03F + RANDOM.nextFloat() * 0.05F);
				}
			}
			case Blood.BURST -> {
				for (int i = 0; i < n; i++) {
					Vec3 d = new Vec3(RANDOM.nextGaussian(), RANDOM.nextDouble() * 0.8 + 0.2, RANDOM.nextGaussian()).normalize();
					spawn(at, d.scale(0.2 + RANDOM.nextDouble() * 0.45).add(dir.scale(0.2)), 0.04F + RANDOM.nextFloat() * 0.07F);
				}
			}
			case Blood.DRIP -> {
				for (int i = 0; i < n; i++) {
					spawn(at.add(gauss(0.12)), dir.scale(0.3).add(gauss(0.01)), 0.03F + RANDOM.nextFloat() * 0.03F);
				}
			}
			case Blood.POOL -> {
				// straight down to the ground under the body, then it spreads
				BlockHitResult hit = mc.level.clip(new ClipContext(at, at.add(0, -3.0, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
					CollisionContext.empty()));
				if (hit.getType() == HitResult.Type.BLOCK) {
					stain(mc, hit.getLocation(), hit.getDirection(), Mth.clamp(0.35F + n * 0.04F, 0.4F, 1.4F), Vec3.ZERO, 80);
				}
			}
			default -> {
			}
		}
	}

	/** A drop of blood flying from {@code at} (for pieces of a body thrown through the air). */
	public static void drop(Vec3 at, Vec3 vel, float size) {
		if (ModConfig.blood) {
			spawn(at, vel, size);
		}
	}

	/** A splash of blood on whatever lies under {@code at} (a piece of a body landing). */
	public static void splat(Vec3 at, float size) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !ModConfig.blood) {
			return;
		}
		BlockHitResult hit = mc.level.clip(new ClipContext(at.add(0, 0.3, 0), at.add(0, -1.5, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
			CollisionContext.empty()));
		if (hit.getType() == HitResult.Type.BLOCK) {
			stain(mc, hit.getLocation(), hit.getDirection(), size, Vec3.ZERO, 30);
		}
	}

	private static Vec3 gauss(double s) {
		return new Vec3(RANDOM.nextGaussian() * s, RANDOM.nextGaussian() * s, RANDOM.nextGaussian() * s);
	}

	private static void spawn(Vec3 at, Vec3 vel, float size) {
		if (DROPS.size() >= MAX_DROPS) {
			DROPS.remove(0);
		}
		Drop d = new Drop();
		d.x = d.px = at.x;
		d.y = d.py = at.y;
		d.z = d.pz = at.z;
		d.vx = vel.x;
		d.vy = vel.y;
		d.vz = vel.z;
		d.size = size;
		DROPS.add(d);
	}

	private static void stain(Minecraft mc, Vec3 at, Direction face, float size, Vec3 vel, int grow) {
		BlockPos block = BlockPos.containing(at.subtract(face.getStepX() * 0.05, face.getStepY() * 0.05, face.getStepZ() * 0.05));
		BlockState state = mc.level.getBlockState(block);
		if (state.isAir()) {
			return;
		}
		if (STAINS.size() >= MAX_STAINS) {
			STAINS.remove(0);
		}
		// a drop that struck at a slant splashes into an elongated stain, pointing the way it flew
		Vector3f n = new Vector3f(face.getStepX(), face.getStepY(), face.getStepZ());
		Vector3f[] axes = axes(n);
		float angle = RANDOM.nextFloat() * Mth.TWO_PI;
		float stretch = 1.0F;
		Vector3f v = new Vector3f((float) vel.x, (float) vel.y, (float) vel.z);
		if (v.lengthSquared() > 1.0E-4F) {
			v.normalize();
			float tu = v.dot(axes[0]);
			float tv = v.dot(axes[1]);
			if (tu * tu + tv * tv > 0.05F) {
				angle = (float) Mth.atan2(tv, tu);
				stretch = Mth.clamp(1.0F / Math.max(Math.abs(v.dot(n)), 0.3F), 1.0F, 2.6F);
			}
		}
		STAINS.add(new Stain(at, face, size, angle, stretch, RANDOM.nextInt(4), mc.level.getGameTime(), grow, block, state, counter++ % 11));
	}

	private static Vector3f[] axes(Vector3f n) {
		Vector3f u = Math.abs(n.y) > 0.5F ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0).cross(n).normalize();
		Vector3f v = new Vector3f(n).cross(u).normalize();
		return new Vector3f[] {u, v};
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			DROPS.clear();
			STAINS.clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		long now = mc.level.getGameTime();
		STAINS.removeIf(s -> now - s.born > LIFE || mc.level.getBlockState(s.block) != s.state);
		for (int i = DROPS.size() - 1; i >= 0; i--) {
			Drop d = DROPS.get(i);
			d.px = d.x;
			d.py = d.y;
			d.pz = d.z;
			d.vy -= 0.045;
			d.vx *= 0.97;
			d.vy *= 0.98;
			d.vz *= 0.97;
			Vec3 from = new Vec3(d.x, d.y, d.z);
			Vec3 to = from.add(d.vx, d.vy, d.vz);
			BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
			if (hit.getType() == HitResult.Type.BLOCK) {
				if (mc.level.getFluidState(hit.getBlockPos()).isEmpty()) {
					// big drops make big splashes; the faster, the wider it spreads
					float speed = (float) Math.sqrt(d.vx * d.vx + d.vy * d.vy + d.vz * d.vz);
					stain(mc, hit.getLocation(), hit.getDirection(), d.size * (2.2F + speed * 3.5F), new Vec3(d.vx, d.vy, d.vz), 0);
				}
				DROPS.remove(i);
				continue;
			}
			d.x = to.x;
			d.y = to.y;
			d.z = to.z;
			if (++d.age > 120) {
				DROPS.remove(i);
			}
		}
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || DROPS.isEmpty() && STAINS.isEmpty()) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		long now = mc.level.getGameTime();
		Vector3f up = new Vector3f(mc.gameRenderer.getMainCamera().upVector());
		Vector3f left = new Vector3f(mc.gameRenderer.getMainCamera().leftVector()).negate(); // to the right: the quad faces the camera
		PoseStack poseStack = context.matrices();
		context.commandQueue().submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> {
			for (Stain s : STAINS) {
				if (s.at.distanceToSqr(cam) < 72.0 * 72.0) {
					float alpha = Mth.clamp((LIFE - (now - s.born)) / (float) FADE, 0.0F, 1.0F);
					float grow = s.grow <= 0 ? 1.0F : Mth.clamp((now - s.born + partial) / s.grow, 0.05F, 1.0F);
					stainQuad(pose, consumer, s, cam, alpha, (float) Math.sqrt(grow), mc);
				}
			}
			for (Drop d : DROPS) {
				double x = Mth.lerp(partial, d.px, d.x) - cam.x;
				double y = Mth.lerp(partial, d.py, d.y) - cam.y;
				double z = Mth.lerp(partial, d.pz, d.z) - cam.z;
				if (x * x + y * y + z * z > 48.0 * 48.0) {
					continue;
				}
				int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(d.x, d.y, d.z));
				float r = d.size;
				// the middle of a splat, for a round, dark-red drop
				float u0 = 0.2F;
				float u1 = 0.3F;
				float v0 = 0.2F;
				float v1 = 0.3F;
				float[][] q = {{-1, -1, u0, v1}, {1, -1, u1, v1}, {1, 1, u1, v0}, {-1, 1, u0, v0}};
				for (float[] p : q) {
					float px = (float) x + (left.x * p[0] + up.x * p[1]) * r;
					float py = (float) y + (left.y * p[0] + up.y * p[1]) * r;
					float pz = (float) z + (left.z * p[0] + up.z * p[1]) * r;
					consumer.addVertex(pose, px, py, pz).setColor(0xF0FFFFFF).setUv(p[2], p[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
						.setNormal(pose, 0.0F, 1.0F, 0.0F);
				}
			}
		});
	}

	private static void stainQuad(PoseStack.Pose pose, VertexConsumer consumer, Stain s, Vec3 cam, float alpha, float grow, Minecraft mc) {
		Vector3f n = new Vector3f(s.face.getStepX(), s.face.getStepY(), s.face.getStepZ());
		Vector3f[] axes = axes(n);
		Vector3f u = axes[0];
		Vector3f v = axes[1];
		float size = s.size * 0.5F * grow;
		Vector3f ru = new Vector3f(u).mul(Mth.cos(s.angle)).add(new Vector3f(v).mul(Mth.sin(s.angle))).mul(size * s.stretch);
		Vector3f rv = new Vector3f(v).mul(Mth.cos(s.angle)).sub(new Vector3f(u).mul(Mth.sin(s.angle))).mul(size);
		float dist = (float) Math.sqrt(s.at.distanceToSqr(cam));
		float lift = 0.005F + dist * 0.0009F + s.index * 0.0004F;
		Vector3f c = new Vector3f((float) (s.at.x - cam.x), (float) (s.at.y - cam.y), (float) (s.at.z - cam.z)).add(new Vector3f(n).mul(lift));
		int light = LevelRenderer.getLightColor(mc.level, s.block.relative(s.face));
		// drying: darker and browner as it ages
		float age = Mth.clamp((mc.level.getGameTime() - s.born) / (float) LIFE, 0.0F, 1.0F);
		int r = (int) (255 * (1.0F - 0.35F * age));
		int g = (int) (255 * (1.0F - 0.2F * age));
		int b = (int) (255 * (1.0F - 0.3F * age));
		int color = (int) (alpha * 255.0F) << 24 | r << 16 | g << 8 | b;
		float u0 = (s.variant % 2) * 0.5F;
		float v0 = (s.variant / 2) * 0.5F;
		float[][] q = {{-1, -1, u0, v0 + 0.5F}, {1, -1, u0 + 0.5F, v0 + 0.5F}, {1, 1, u0 + 0.5F, v0}, {-1, 1, u0, v0}};
		for (float[] p : q) {
			float x = c.x + ru.x * p[0] + rv.x * p[1];
			float y = c.y + ru.y * p[0] + rv.y * p[1];
			float z = c.z + ru.z * p[0] + rv.z * p[1];
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(p[2], p[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, n.x, n.y, n.z);
		}
	}
}
