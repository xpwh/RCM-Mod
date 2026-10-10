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
	private static final RenderType TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/effect/blood_ground.png"));
	/** The wet shine on fresh blood (see {@link de.rcm.ballistic.client.render.WetShine}). */
	private static final RenderType GLOSS = RenderTypes.eyes(BallisticMissiles.id("textures/effect/blood_ground_gloss.png"));
	/** Rows of textures/effect/blood_ground.png (tools/gen_blood_ground.py), four versions each. */
	private static final int SPLAT = 0;
	private static final int SPATTER = 1;
	private static final int POOL_STAIN = 2;
	private static final int RUN = 3;
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
		BlockState state, int index, int kind) {
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
			case Blood.JET -> {
				// a spurt: drops along one arc, the first fastest, the jet breaking up at its end
				double strength = dir.length();
				Vec3 d = strength < 1.0E-4 ? new Vec3(0, 1, 0) : dir.scale(1.0 / strength);
				for (int i = 0; i < n; i++) {
					double f = i / (double) Math.max(1, n - 1);
					double speed = (0.12 + 0.3 * (1.0 - f * 0.75)) * strength;
					Vec3 v = d.scale(speed).add(gauss(0.012 + 0.03 * f));
					spawn(at.add(d.scale(0.02 * i)), v, 0.03F + RANDOM.nextFloat() * 0.035F);
				}
			}
			case Blood.EXIT_SPATTER -> {
				// out of the exit wound: a fast cone of blood and bits, the bulk of it straight on along the round's line
				Vec3 d = dir.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : dir.normalize();
				for (int i = 0; i < n; i++) {
					double speed = 0.45 + RANDOM.nextDouble() * 0.5;
					spawn(at, d.scale(speed).add(gauss(0.07 + 0.05 * RANDOM.nextDouble())), 0.025F + RANDOM.nextFloat() * 0.05F);
				}
				// what hits the wall behind hits it at once, as one big spatter with runs below it
				BlockHitResult hit = mc.level.clip(new ClipContext(at, at.add(d.scale(5.0)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
					CollisionContext.empty()));
				if (hit.getType() == HitResult.Type.BLOCK) {
					double dist = hit.getLocation().distanceTo(at);
					float size = (float) Mth.clamp(0.55 + dist * 0.22, 0.6, 1.6) * Mth.clamp(n / 26.0F, 0.6F, 1.4F);
					Vec3 vel = d.scale(0.8);
					stainDirect(mc, hit.getLocation(), hit.getDirection(), size, vel, SPLAT);
					for (int i = 0; i < 3; i++) {
						Vec3 off = gauss(0.18 + dist * 0.06);
						Vec3 nrm = Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i());
						off = off.subtract(nrm.scale(off.dot(nrm)));
						stainDirect(mc, hit.getLocation().add(off), hit.getDirection(), size * (0.3F + RANDOM.nextFloat() * 0.3F), vel, SPLAT);
					}
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
					stain(mc, hit.getLocation(), hit.getDirection(), Mth.clamp(0.5F + n * 0.06F, 0.6F, 2.0F), Vec3.ZERO, 160, POOL_STAIN);
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
			stain(mc, hit.getLocation(), hit.getDirection(), size, Vec3.ZERO, 30, size > 0.5F ? POOL_STAIN : SPLAT);
		}
	}

	/** A stain on the face of the block behind {@code at} (pulled back onto it), if it is a solid one. */
	private static void stainDirect(Minecraft mc, Vec3 at, Direction face, float size, Vec3 vel, int kind) {
		Vec3 onFace = at.subtract(face.getStepX() * 0.01, face.getStepY() * 0.01, face.getStepZ() * 0.01);
		if (mc.level.getBlockState(BlockPos.containing(onFace)).isAir()) {
			return;
		}
		stain(mc, at, face, size, vel, 0, kind);
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

	private static void stain(Minecraft mc, Vec3 at, Direction face, float size, Vec3 vel, int grow, int kind) {
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
		if (kind == SPLAT && face.getAxis().isHorizontal()) {
			// on a wall it runs down
			kind = RUN;
			grow = 50 + RANDOM.nextInt(60);
		} else if (kind == SPLAT && stretch > 1.35F) {
			kind = SPATTER;
		}
		STAINS.add(new Stain(at, face, size, angle, stretch, RANDOM.nextInt(4), mc.level.getGameTime(), grow, block, state, counter++ % 11, kind));
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
					stain(mc, hit.getLocation(), hit.getDirection(), d.size * (2.2F + speed * 3.5F), new Vec3(d.vx, d.vy, d.vz), 0, SPLAT);
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
					stainQuad(pose, consumer, s, cam, alpha, (float) Math.sqrt(grow), mc, false);
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
				float u0 = 0.11F;
				float u1 = 0.14F;
				float v0 = 0.11F;
				float v1 = 0.14F;
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
		if (!STAINS.isEmpty()) {
			// after everything else, over the stains it shines on
			context.commandQueue().order(1).submitCustomGeometry(poseStack, GLOSS, (pose, consumer) -> {
				for (Stain s : STAINS) {
					if (s.at.distanceToSqr(cam) < 48.0 * 48.0 && wetness(s, now) > 0.01F) {
						float alpha = Mth.clamp((LIFE - (now - s.born)) / (float) FADE, 0.0F, 1.0F);
						float grow = s.grow <= 0 ? 1.0F : Mth.clamp((now - s.born + partial) / s.grow, 0.05F, 1.0F);
						stainQuad(pose, consumer, s, cam, alpha, (float) Math.sqrt(grow), mc, true);
					}
				}
			});
		}
	}

	/** How wet a stain still is: a thick pool stays wet longest, a fine spatter dries in a minute. */
	private static float wetness(Stain s, long now) {
		float dries = s.kind == POOL_STAIN ? 3200.0F : s.kind == RUN ? 1200.0F : 1000.0F + 1200.0F * Math.min(1.0F, s.size);
		float t = Mth.clamp((now - s.born) / dries, 0.0F, 1.0F);
		return (1.0F - t) * (1.0F - t);
	}

	private static void stainQuad(PoseStack.Pose pose, VertexConsumer consumer, Stain s, Vec3 cam, float alpha, float grow, Minecraft mc, boolean gloss) {
		Vector3f n = new Vector3f(s.face.getStepX(), s.face.getStepY(), s.face.getStepZ());
		float size = s.size * 0.5F;
		Vector3f ru;
		Vector3f rv;
		// texture rectangle of this stain's tile, and how far down it reaches (a run on a wall grows downwards)
		float tu = s.variant * 0.25F;
		float tv = s.kind * 0.25F;
		float top = 1.0F;
		float bottom = 1.0F;
		if (s.kind == RUN) {
			Vector3f up = new Vector3f(0, 1, 0);
			ru = new Vector3f(up).cross(n).normalize().mul(size);
			rv = new Vector3f(up).mul(size);
			// the splat near the top of the tile (v 0.175); the runs reveal as they flow down
			top = 0.35F;
			bottom = 0.35F + 1.3F * grow;
		} else {
			Vector3f[] axes = axes(n);
			float sz = size * (s.kind == POOL_STAIN ? grow : (float) Math.sqrt(grow)) * (s.kind == SPATTER ? 1.4F : 1.0F);
			float stretch = s.kind == SPATTER ? 1.0F + (s.stretch - 1.0F) * 0.35F : 1.0F;
			ru = new Vector3f(axes[0]).mul(Mth.cos(s.angle)).add(new Vector3f(axes[1]).mul(Mth.sin(s.angle))).mul(sz * stretch);
			rv = new Vector3f(axes[1]).mul(Mth.cos(s.angle)).sub(new Vector3f(axes[0]).mul(Mth.sin(s.angle))).mul(sz);
		}
		float dist = (float) Math.sqrt(s.at.distanceToSqr(cam));
		float lift = 0.005F + dist * 0.0009F + s.index * 0.0004F;
		Vector3f c = new Vector3f((float) (s.at.x - cam.x), (float) (s.at.y - cam.y), (float) (s.at.z - cam.z)).add(new Vector3f(n).mul(lift));
		int light = LevelRenderer.getLightColor(mc.level, s.block.relative(s.face));
		// drying: fresh blood bright and wet, over a minute or two going dark, clotting, then brown-black (a pool takes longer)
		float age = Mth.clamp((mc.level.getGameTime() - s.born) / (s.kind == POOL_STAIN ? 2600.0F : 1800.0F), 0.0F, 1.0F);
		age = age * age * (3.0F - 2.0F * age);
		int r = (int) (255 * (1.0F - 0.55F * age));
		int g = (int) (255 * (1.0F - 0.45F * age));
		int b = (int) (255 * (1.0F - 0.52F * age));
		int color = (int) (alpha * 255.0F) << 24 | r << 16 | g << 8 | b;
		float wet = gloss ? wetness(s, mc.level.getGameTime()) * alpha : 0.0F;
		Vector3f corner = new Vector3f();
		// corners: (across, along v) - for a run, along v goes from +top (up) to -bottom (down)
		float[][] q = s.kind == RUN
			? new float[][] {{-1, top, tu, tv}, {1, top, tu + 0.25F, tv}, {1, -bottom, tu + 0.25F, tv + 0.25F * (top + bottom) / 2.0F},
				{-1, -bottom, tu, tv + 0.25F * (top + bottom) / 2.0F}}
			: new float[][] {{-1, -1, tu, tv + 0.25F}, {1, -1, tu + 0.25F, tv + 0.25F}, {1, 1, tu + 0.25F, tv}, {-1, 1, tu, tv}};
		for (float[] p : q) {
			float x = c.x + ru.x * p[0] + rv.x * p[1];
			float y = c.y + ru.y * p[0] + rv.y * p[1];
			float z = c.z + ru.z * p[0] + rv.z * p[1];
			if (gloss) {
				color = (int) (255.0F * wet * de.rcm.ballistic.client.render.WetShine.at(corner.set(x, y, z), n, light)) << 24 | 0xFFFFFF;
			}
			consumer.addVertex(pose, x, y, z).setColor(color).setUv(p[2], p[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, n.x, n.y, n.z);
		}
	}
}
