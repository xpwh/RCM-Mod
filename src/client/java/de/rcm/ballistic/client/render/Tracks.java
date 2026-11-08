package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.ModConfig;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Footprints and tyre tracks pressed into soft ground - snow, sand, mud (and earth soaked by a storm):
 * a print of each step, left and right in turn, of everyone walking there (boot soles for people,
 * paws and hooves for animals), the tread of a vehicle's wheels in two unbroken lines behind it. Deep and
 * crisp at first, they soften and fade: soonest in sand, slowest in snow.
 */
public final class Tracks {
	private static final RenderType TYPE = RenderTypes.entityTranslucent(BallisticMissiles.id("textures/effect/tracks.png"));
	private static final int CAP = 2400;
	private static final int BOOT = 0;
	private static final int PAW = 1;
	private static final int TYRE = 2;
	private static final int COMBAT_BOOT = 3;

	private enum Ground {
		// tint of the hollow, how dark it is, how long it lasts (ticks)
		SNOW(0x8E9CB4, 0.62F, 4800),
		SAND(0x8C7250, 0.5F, 1600),
		MUD(0x2C2018, 0.62F, 3600);

		final int tint;
		final float depth;
		final int life;

		Ground(int tint, float depth, int life) {
			this.tint = tint;
			this.depth = depth;
			this.life = life;
		}
	}

	/** One print: where, which way, how big, what shape, in what ground and when. */
	private record Print(double x, double y, double z, float yaw, float w, float l, int shape, boolean mirror, Ground ground, BlockPos block, long born) {
	}

	private static final ArrayDeque<Print> PRINTS = new ArrayDeque<>();
	/** Per walker: where it was last measured, how far it has gone since its last print, which foot is next. */
	private static final Map<Integer, double[]> WALKERS = new HashMap<>();

	private Tracks() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(Tracks::tick);
		WorldRenderEvents.BEFORE_ENTITIES.register(Tracks::render);
	}

	/** What the ground under {@code pos} takes prints in (null: nothing to see). */
	private static Ground ground(Minecraft mc, BlockState state) {
		if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)) {
			return Ground.SNOW;
		}
		if (state.is(BlockTags.SAND)) {
			return Ground.SAND;
		}
		if (state.is(Blocks.MUD) || state.is(Blocks.SOUL_SAND) || state.is(Blocks.SOUL_SOIL) || state.is(Blocks.CLAY)) {
			return Ground.MUD;
		}
		// earth soaked by a downpour turns to mud
		if ((state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND) || state.is(Blocks.GRASS_BLOCK)
			|| state.is(Blocks.ROOTED_DIRT)) && de.rcm.ballistic.client.effect.Storm.wetness() > 0.55F) {
			return Ground.MUD;
		}
		return null;
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			PRINTS.clear();
			WALKERS.clear();
			return;
		}
		long now = mc.level.getGameTime();
		// while it snows, the prints in the snow fill in four times as fast
		if (now % 20 == 0 && mc.level.isRaining() && !PRINTS.isEmpty()) {
			ArrayDeque<Print> aged = new ArrayDeque<>(PRINTS.size());
			for (Print p : PRINTS) {
				boolean snowing = p.ground == Ground.SNOW && mc.level.getBiome(p.block).value().getPrecipitationAt(p.block, mc.level.getSeaLevel())
					== net.minecraft.world.level.biome.Biome.Precipitation.SNOW && mc.level.canSeeSky(p.block.above());
				aged.add(snowing ? new Print(p.x, p.y, p.z, p.yaw, p.w, p.l, p.shape, p.mirror, p.ground, p.block, p.born - 60L) : p);
			}
			PRINTS.clear();
			PRINTS.addAll(aged);
		}
		// gone with time, or with the ground (dug up, snow melted)
		Iterator<Print> it = PRINTS.iterator();
		while (it.hasNext()) {
			Print p = it.next();
			if (now - p.born > p.ground.life || now % 20 == 0 && ground(mc, mc.level.getBlockState(p.block)) != p.ground) {
				it.remove();
			}
		}
		if (!ModConfig.tracks || mc.player == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e.distanceToSqr(cam) > 64.0 * 64.0) {
				continue;
			}
			if (e instanceof de.rcm.ballistic.entity.MobileLauncherEntity) {
				seen.add(e.getId());
				wheels(mc, e, now, 1.15, 0.42, 0.4);
			} else if (e instanceof LivingEntity living && !living.isSpectator() && living.isAlive()) {
				seen.add(e.getId());
				steps(mc, living, now);
			}
		}
		WALKERS.keySet().retainAll(seen);
	}

	/** A print each stride, left and right in turn, when the walker's feet are on soft ground. */
	private static void steps(Minecraft mc, LivingEntity e, long now) {
		double[] w = WALKERS.computeIfAbsent(e.getId(), k -> new double[] {e.getX(), e.getZ(), 0.0, 0.0});
		double dx = e.getX() - w[0];
		double dz = e.getZ() - w[1];
		w[0] = e.getX();
		w[1] = e.getZ();
		if (!e.onGround() || e.isPassenger()) {
			return;
		}
		double moved = Math.sqrt(dx * dx + dz * dz);
		if (moved > 2.0) {
			return; // teleported
		}
		boolean animal = e instanceof Animal;
		float scale = Mth.clamp(e.getBbWidth() / 0.6F, 0.5F, 2.2F);
		double stride = (animal ? 0.45 : 0.72) * Math.sqrt(scale) * (e.isSprinting() ? 1.35 : 1.0);
		w[2] += moved;
		if (w[2] < stride) {
			return;
		}
		w[2] = 0.0;
		w[3] = w[3] == 0.0 ? 1.0 : 0.0;
		float yaw = (float) (Math.atan2(-dx, dz) * Mth.RAD_TO_DEG);
		if (moved < 1.0E-3) {
			yaw = e.getYRot();
		}
		float side = w[3] == 0.0 ? -1.0F : 1.0F;
		double sx = -Math.cos(yaw * Mth.DEG_TO_RAD) * 0.13 * scale * side;
		double sz = -Math.sin(yaw * Mth.DEG_TO_RAD) * 0.13 * scale * side;
		int shape = animal ? PAW : e instanceof Player || e.getType() == de.rcm.ballistic.ModRegistry.SOLDIER ? (e instanceof Player ? BOOT : COMBAT_BOOT) : BOOT;
		float wid = (animal ? 0.24F : 0.16F) * scale;
		float len = (animal ? 0.24F : 0.33F) * scale;
		print(mc, e.getX() + sx, e.getY(), e.getZ() + sz, yaw, wid, len, shape, side > 0, now);
	}

	/** Two lines of tyre tread behind a vehicle, {@code track} either side of its middle. */
	private static void wheels(Minecraft mc, Entity e, long now, double track, double width, double step) {
		double[] w = WALKERS.computeIfAbsent(e.getId(), k -> new double[] {e.getX(), e.getZ(), 0.0, 0.0});
		double dx = e.getX() - w[0];
		double dz = e.getZ() - w[1];
		w[0] = e.getX();
		w[1] = e.getZ();
		double moved = Math.sqrt(dx * dx + dz * dz);
		if (moved > 4.0 || !e.onGround()) {
			return;
		}
		w[2] += moved;
		if (w[2] < step) {
			return;
		}
		w[2] = 0.0;
		float yaw = moved < 1.0E-3 ? e.getYRot() : (float) (Math.atan2(-dx, dz) * Mth.RAD_TO_DEG);
		double cx = -Math.cos(yaw * Mth.DEG_TO_RAD);
		double cz = -Math.sin(yaw * Mth.DEG_TO_RAD);
		for (int s = -1; s <= 1; s += 2) {
			print(mc, e.getX() + cx * track * s, e.getY(), e.getZ() + cz * track * s, yaw, (float) width, (float) step + 0.04F, TYRE, false, now);
		}
	}

	private static void print(Minecraft mc, double x, double y, double z, float yaw, float w, float l, int shape, boolean mirror, long now) {
		// the surface the foot sinks into: a snow layer the feet stand in, or the block below
		BlockPos at = BlockPos.containing(x, y - 0.01, z);
		BlockState state = mc.level.getBlockState(at);
		if (state.isAir() || state.getCollisionShape(mc.level, at).isEmpty() && !state.is(Blocks.SNOW) && !state.is(Blocks.POWDER_SNOW)) {
			at = at.below();
			state = mc.level.getBlockState(at);
		}
		Ground g = ground(mc, state);
		if (g == null) {
			return;
		}
		double top;
		if (state.is(Blocks.SNOW)) {
			top = at.getY() + state.getValue(SnowLayerBlock.LAYERS) * 2.0 / 16.0;
		} else if (state.is(Blocks.POWDER_SNOW)) {
			top = at.getY() + 1.0;
		} else {
			var outline = state.getShape(mc.level, at);
			top = at.getY() + (outline.isEmpty() ? 1.0 : outline.max(Direction.Axis.Y));
		}
		if (Math.abs(top - y) > 0.6) {
			return;
		}
		if (PRINTS.size() >= CAP) {
			PRINTS.pollFirst();
		}
		PRINTS.addLast(new Print(x, top, z, yaw, w, l, shape, mirror, g, at.immutable(), now));
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (PRINTS.isEmpty() || mc.level == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		long now = mc.level.getGameTime();
		PoseStack poseStack = context.matrices();
		context.commandQueue().submitCustomGeometry(poseStack, TYPE, (pose, consumer) -> {
			int i = 0;
			for (Print p : PRINTS) {
				i++;
				double dx = p.x - cam.x;
				double dy = p.y - cam.y;
				double dz = p.z - cam.z;
				if (dx * dx + dy * dy + dz * dz > 56.0 * 56.0) {
					continue;
				}
				float age = (now - p.born) / (float) p.ground.life;
				// crisp for the first half of its life, then softening away
				float alpha = p.ground.depth * Mth.clamp((1.0F - age) / 0.5F, 0.0F, 1.0F);
				draw(pose, consumer, p, dx, dy, dz, alpha, i, mc);
			}
		});
	}

	private static void draw(PoseStack.Pose pose, VertexConsumer consumer, Print p, double dx, double dy, double dz, float alpha, int index, Minecraft mc) {
		float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float lift = 0.004F + dist * 0.0007F + (index % 8) * 0.0002F;
		float c = Mth.cos(p.yaw * Mth.DEG_TO_RAD);
		float s = Mth.sin(p.yaw * Mth.DEG_TO_RAD);
		// forward (toe) and right in the ground plane
		float fx = -s * p.l * 0.5F;
		float fz = c * p.l * 0.5F;
		float rx = -c * p.w * 0.5F;
		float rz = -s * p.w * 0.5F;
		float x = (float) dx;
		float y = (float) dy + lift;
		float z = (float) dz;
		int cell = p.shape;
		float u0 = (cell % 2) * 0.5F;
		float v0 = (cell / 2) * 0.5F;
		float u1 = u0 + 0.5F;
		float v1 = v0 + 0.5F;
		if (p.mirror) {
			float t = u0;
			u0 = u1;
			u1 = t;
		}
		int light = LevelRenderer.getLightColor(mc.level, p.block.above());
		int color = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24 | p.ground.tint;
		// toe at the texture's top (v0)
		consumer.addVertex(pose, x - rx - fx, y, z - rz - fz).setColor(color).setUv(u0, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
		consumer.addVertex(pose, x + rx - fx, y, z + rz - fz).setColor(color).setUv(u1, v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
		consumer.addVertex(pose, x + rx + fx, y, z + rz + fz).setColor(color).setUv(u1, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
		consumer.addVertex(pose, x - rx + fx, y, z - rz + fz).setColor(color).setUv(u0, v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
			.setNormal(pose, 0.0F, 1.0F, 0.0F);
	}
}
