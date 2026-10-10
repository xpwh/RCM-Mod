package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.client.render.GoreMesh;
import de.rcm.ballistic.network.ModNetworking.WoundPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Bullet wounds on animals and mobs, where the round went in: a hole with blood soaking round it and
 * running down, stuck to the creature's body - it turns with it, and falls over with it when it dies.
 * Positions are kept in the body's own frame (turned with its body, as the renderer turns the model).
 */
public final class MobWounds {
	private record Wound(Vector3f at, Vector3f normal, float size, int variant, int kind, float spin, long born) {
	}

	private static final Int2ObjectOpenHashMap<List<Wound>> WOUNDS = new Int2ObjectOpenHashMap<>();
	private static final int MAX_PER = 24;
	private static final GoreMesh[] DECALS = new GoreMesh[4];

	/** What hangs out of a torn-open wound (meat, strings of blood) and out of an opened belly (a loop of gut), in the decal's space. */
	private static final GoreMesh[] GAPING = new GoreMesh[3];
	private static final GoreMesh[] ENTRAILS = new GoreMesh[3];
	/** A dead body torn wide open along the flank that lies up: ribs broken out, the organs in it, the guts spilled to the ground. */
	private static final GoreMesh[] OPEN = new GoreMesh[3];

	static {
		for (int v = 0; v < 3; v++) {
			net.minecraft.util.RandomSource r = net.minecraft.util.RandomSource.create(900L + v);
			GoreMesh.Builder b = new GoreMesh.Builder();
			b.blob(new Vector3f((r.nextFloat() - 0.5F) * 2, (r.nextFloat() - 0.5F) * 2, -1.5F), 3.2F + r.nextFloat(), 2.6F + r.nextFloat(), 2.2F, 0.35F, 910L + v,
				GoreMesh.T_MEAT);
			b.blob(new Vector3f(2.5F, -2.0F, -1.0F), 1.6F, 2.2F, 1.4F, 0.3F, 920L + v, v == 1 ? GoreMesh.T_LIVER : GoreMesh.T_SECTION);
			for (int i = 0; i < 3; i++) {
				float x = (r.nextFloat() - 0.5F) * 6;
				b.shard(new Vector3f(x, -2.0F, -1.0F), new Vector3f((r.nextFloat() - 0.5F) * 0.3F, -1, -0.2F).normalize(), new Vector3f(1, 0, 0),
					4.0F + r.nextFloat() * 8.0F, 0.9F, i == 0 ? GoreMesh.T_FLAP : GoreMesh.T_STRAND, 0.1F);
			}
			GAPING[v] = b.build();
			// a loop of gut out of the hole, hanging down and swinging back up, another down to the ground
			GoreMesh.Builder e = new GoreMesh.Builder();
			int n = 16;
			Vector3f[] pts = new Vector3f[n];
			float[] rad = new float[n];
			for (int k = 0; k < n; k++) {
				float t = k / (float) (n - 1);
				float drop = (float) Math.sin(t * Math.PI) * (26.0F + v * 8.0F);
				pts[k] = new Vector3f((t - 0.5F) * 10.0F + Mth.sin(t * 9 + v) * 2.0F, -drop, -2.5F - Mth.sin(t * Mth.PI) * 4.0F);
				rad[k] = 2.2F + r.nextFloat() * 0.4F;
			}
			e.path(pts, rad, GoreMesh.T_GUT);
			Vector3f[] hang = new Vector3f[10];
			float[] hr = new float[10];
			for (int k = 0; k < 10; k++) {
				hang[k] = new Vector3f(3.0F + Mth.sin(k * 0.9F + v) * 2.0F, -k * (4.5F + v), -1.5F - k * 0.4F);
				hr[k] = 2.0F;
			}
			e.path(hang, hr, GoreMesh.T_GUT);
			e.blob(new Vector3f(0, 0, -1.2F), 3.6F, 3.0F, 2.0F, 0.3F, 930L + v, GoreMesh.T_MEAT);
			ENTRAILS[v] = e.build();
		}
		for (int v = 0; v < 3; v++) {
			OPEN[v] = openBody(v);
		}
		for (int v = 0; v < 4; v++) {
			// one unit square, centred, facing -Z, the tile's run going down (+v towards -Y)
			DECALS[v] = new GoreMesh.Builder().decal(new Vector3f(-8, 8, 0), new Vector3f(16, 0, 0), new Vector3f(0, -16, 0), GoreMesh.T_MOB + v).build();
		}
	}

	private MobWounds() {
	}

	/**
	 * In the decal's space, 16 units the body's width: x along the body, -y towards the belly, -z out of the
	 * flank (up, once it lies on its side), +z into it and on down to the ground, some 16 below.
	 */
	private static GoreMesh openBody(int v) {
		net.minecraft.util.RandomSource r = net.minecraft.util.RandomSource.create(1300L + v * 17L);
		GoreMesh.Builder b = new GoreMesh.Builder();
		float len = 9.0F + v;
		// the hide soaked round the tear, and the raw red inside of it
		b.decal(new Vector3f(-len - 3, 8.5F, -0.03F), new Vector3f(2 * len + 6, 0, 0), new Vector3f(0, -17, 0), GoreMesh.T_MOB + 2 + (v & 1));
		b.decal(new Vector3f(-len, 5.5F, -0.08F), new Vector3f(2 * len, 0, 0), new Vector3f(0, -11, 0), GoreMesh.T_SECTION);
		b.decal(new Vector3f(-len * 0.7F, 4.0F, -0.12F), new Vector3f(len * 1.4F, 0, 0), new Vector3f(0, -8, 0), GoreMesh.T_MEAT);
		// the torn edges of the hide, peeled back and hanging open
		for (int i = 0; i < 7; i++) {
			float x = -len + (i + 0.5F) * (2 * len / 7) + (r.nextFloat() - 0.5F) * 1.5F;
			boolean top = (i & 1) == 0;
			Vector3f base = new Vector3f(x, top ? 5.3F : -5.3F, -0.2F);
			Vector3f dir = new Vector3f((r.nextFloat() - 0.5F) * 0.4F, top ? 0.8F : -0.8F, -0.55F).normalize();
			b.shard(base, dir, new Vector3f(1, 0, 0), 2.5F + r.nextFloat() * 2.5F, 2.4F + r.nextFloat() * 1.4F, i % 3 == 0 ? GoreMesh.T_MEAT : GoreMesh.T_FLAP, 0.25F);
		}
		// the ribs: arching up out of the back, snapped, the broken ends sticking out of the hole
		int ribs = 5 + v;
		for (int i = 0; i < ribs; i++) {
			float x = -len * 0.75F + i * (1.5F * len / (ribs - 1)) + (r.nextFloat() - 0.5F) * 0.8F;
			float reach = 1.5F + r.nextFloat() * 4.0F;
			Vector3f[] pts = new Vector3f[5];
			float[] rad = new float[5];
			for (int k = 0; k < 5; k++) {
				float t = k / 4.0F;
				float y = 5.6F - t * reach;
				pts[k] = new Vector3f(x + t * 0.6F, y, -0.2F - Mth.sin(t * Mth.HALF_PI) * (1.8F + r.nextFloat() * 0.6F));
				rad[k] = 0.55F - t * 0.1F;
			}
			b.path(pts, rad, GoreMesh.T_BONE);
			// the splintered end
			Vector3f end = pts[4];
			b.shard(end, new Vector3f(0.2F, -1, -0.5F).normalize(), new Vector3f(1, 0, 0), 1.2F + r.nextFloat(), 0.8F, GoreMesh.T_BONE_THIN, 0.1F);
			if (r.nextFloat() < 0.6F) {
				// the other half, from the belly side
				Vector3f lo = new Vector3f(x + 0.4F, -5.6F, -0.2F);
				Vector3f tip = new Vector3f(x + 0.8F, -5.6F + 1.0F + r.nextFloat() * 2.0F, -1.6F - r.nextFloat());
				b.tube(lo, tip, 0.5F, 0.4F, GoreMesh.T_BONE);
				b.shard(tip, new Vector3f(0.1F, 1, -0.4F).normalize(), new Vector3f(1, 0, 0), 1.0F, 0.7F, GoreMesh.T_BONE_THIN, 0.1F);
			}
		}
		// the organs, heaped in the hole: the lung under the ribs, the heart, the liver, coils of gut over the rest
		float side = v == 1 ? -1.0F : 1.0F;
		b.blob(new Vector3f(side * len * 0.45F, 2.5F, -1.2F), 3.4F, 2.0F, 1.5F, 0.25F, 1310L + v, GoreMesh.T_LUNG);
		b.blob(new Vector3f(side * len * 0.35F, -0.2F, -2.0F), 1.7F, 2.1F, 1.6F, 0.2F, 1320L + v, GoreMesh.T_HEART);
		b.blob(new Vector3f(-side * len * 0.1F, 1.2F, -1.7F), 3.2F, 2.2F, 1.4F, 0.3F, 1330L + v, GoreMesh.T_LIVER);
		for (int c = 0; c < 3 + v; c++) {
			int n = 14;
			Vector3f[] pts = new Vector3f[n];
			float[] rad = new float[n];
			float cx = -side * len * (0.15F + 0.25F * (c % 2)) + (r.nextFloat() - 0.5F) * 3.0F;
			float cy = -1.5F - r.nextFloat() * 2.0F;
			float ph = r.nextFloat() * Mth.TWO_PI;
			for (int k = 0; k < n; k++) {
				float t = k / (float) (n - 1);
				float a = ph + t * 7.0F + Mth.sin(t * 11.0F + c) * 0.6F;
				float rr = 2.4F - t * 0.9F + Mth.sin(t * 13.0F + ph) * 0.6F;
				pts[k] = new Vector3f(cx + Mth.cos(a) * rr + t * 3.5F * (c % 2 == 0 ? 1 : -1), cy + Mth.sin(a) * rr * 0.8F, -1.6F - (k & 1) * 0.4F - Mth.sin(t * Mth.PI) * 1.4F);
				rad[k] = 0.9F + r.nextFloat() * 0.2F;
			}
			b.path(pts, rad, GoreMesh.T_GUT);
		}
		// the guts out over the belly and down to the ground, lying there in a heap
		for (int s = 0; s < 2; s++) {
			int n = 16;
			Vector3f[] pts = new Vector3f[n];
			float[] rad = new float[n];
			float x0 = (s == 0 ? -side : side) * len * 0.3F + (r.nextFloat() - 0.5F) * 2.0F;
			for (int k = 0; k < n; k++) {
				float t = k / (float) (n - 1);
				Vector3f p;
				if (t < 0.35F) {
					float q = t / 0.35F;
					// out of the hole and over the edge of the belly
					p = new Vector3f(x0 + q * 1.5F, -2.5F - q * 7.5F, -2.2F - Mth.sin(q * Mth.PI) * 1.5F + q * q * 2.0F);
				} else if (t < 0.6F) {
					float q = (t - 0.35F) / 0.25F;
					// down the side to the ground
					p = new Vector3f(x0 + 1.5F + Mth.sin(q * 3.0F) * 1.2F, -10.0F - q * 1.5F, -0.2F + q * 15.0F);
				} else {
					float q = (t - 0.6F) / 0.4F;
					// a slack heap on the ground
					float a = q * 7.0F + s * 2.0F;
					p = new Vector3f(x0 + 1.5F + Mth.cos(a) * (2.0F + q * 2.0F), -12.5F - Mth.sin(a) * 1.8F - q * 2.5F, 14.8F - (k & 1) * 0.3F);
				}
				pts[k] = p;
				rad[k] = 0.95F + (k & 1) * 0.1F;
			}
			b.path(pts, rad, GoreMesh.T_GUT);
		}
		// strings of blood and torn tissue across the hole
		for (int i = 0; i < 4; i++) {
			float x = (r.nextFloat() - 0.5F) * len * 1.6F;
			b.tube(new Vector3f(x, 5.2F, -0.4F), new Vector3f(x + (r.nextFloat() - 0.5F) * 3.0F, -1.0F - r.nextFloat() * 3.0F, -2.6F), 0.18F, 0.1F, GoreMesh.T_STRAND);
		}
		for (int i = 0; i < 3; i++) {
			b.blob(new Vector3f((r.nextFloat() - 0.5F) * len * 1.6F, (r.nextFloat() - 0.5F) * 7.0F, -1.0F), 1.4F, 1.1F, 0.9F, 0.4F, 1340L + v * 5 + i, GoreMesh.T_MEAT);
		}
		return b.build();
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(WoundPayload.TYPE, (payload, context) -> receive(payload));
		ClientTickEvents.END_CLIENT_TICK.register(MobWounds::tick);
		WorldRenderEvents.AFTER_ENTITIES.register(MobWounds::render);
	}

	private static void receive(WoundPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !(mc.level.getEntity(p.entity()) instanceof LivingEntity e)) {
			return;
		}
		// into the body's frame: undo the turn the renderer gives the model
		Quaternionf toBody = new Quaternionf().rotationY((180.0F - e.yBodyRot) * Mth.DEG_TO_RAD).conjugate();
		Vector3f at = toBody.transform(new Vector3f(p.rx(), p.ry(), p.rz()));
		Vector3f n = toBody.transform(new Vector3f(p.nx(), p.ny(), p.nz()));
		List<Wound> list = WOUNDS.computeIfAbsent(p.entity(), k -> new ArrayList<>());
		if (list.size() >= MAX_PER) {
			list.remove(0);
		}
		int kind = p.variant() >> 4;
		if (kind == de.rcm.ballistic.injury.Blood.OPEN) {
			// the flank that lies up once it has fallen, torn open along the body
			list.removeIf(w -> w.kind() == de.rcm.ballistic.injury.Blood.OPEN);
			float h = e.getBbHeight();
			list.add(new Wound(new Vector3f(e.getBbWidth() * 0.5F + 0.01F, h * 0.55F, 0), new Vector3f(1, 0, 0), p.size(), p.variant() & 3, kind, 0.0F, mc.level.getGameTime()));
			Vec3 world = e.position().add(0, h * 0.5, 0);
			GibClient.spray(world, new Vec3(0, 1, 0), 7 + (int) (Math.random() * 4), 0.35F + p.size());
			return;
		}
		list.add(new Wound(at, n, p.size(), p.variant() & 3, kind, (float) (Math.random() * 30.0 - 15.0), mc.level.getGameTime()));
		if (kind != de.rcm.ballistic.injury.Blood.HOLE) {
			// bits torn out of it, thrown out of the hole
			Vec3 world = e.position().add(p.rx(), p.ry(), p.rz());
			GibClient.spray(world, new Vec3(p.nx(), p.ny(), p.nz()), kind == de.rcm.ballistic.injury.Blood.EXIT ? 2 : 3 + (int) (Math.random() * 3),
				0.35F + p.size());
		}
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			WOUNDS.clear();
			return;
		}
		if (mc.level.getGameTime() % 40 == 0) {
			WOUNDS.int2ObjectEntrySet().removeIf(en -> {
				Entity e = mc.level.getEntity(en.getIntKey());
				return e == null || e.isRemoved();
			});
		}
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (WOUNDS.isEmpty() || mc.level == null || !ModConfig.blood) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.matrices();
		for (var en : WOUNDS.int2ObjectEntrySet()) {
			if (!(mc.level.getEntity(en.getIntKey()) instanceof LivingEntity e) || e.isInvisible()) {
				continue;
			}
			Vec3 pos = e.getPosition(partial);
			if (pos.distanceToSqr(cam) > 64 * 64) {
				continue;
			}
			int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(pos.x, pos.y + e.getBbHeight() * 0.5, pos.z));
			poseStack.pushPose();
			poseStack.translate(pos.x - cam.x, pos.y - cam.y, pos.z - cam.z);
			CorpseFx.applyTumble(poseStack, e.getId(), partial);
			poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - Mth.rotLerp(partial, e.yBodyRotO, e.yBodyRot)));
			if (e.deathTime > 0) {
				// keeling over as it dies, as the model does
				float f = Math.min(1.0F, Mth.sqrt((e.deathTime + partial - 1.0F) / 20.0F * 1.6F));
				poseStack.mulPose(Axis.ZP.rotationDegrees(f * 90.0F));
			}
			for (Wound w : en.getValue()) {
				boolean open = w.kind() == de.rcm.ballistic.injury.Blood.OPEN;
				if (open && (e.deathTime <= 0 || !ModConfig.gore)) {
					continue;
				}
				// a wound stays wet a while (longer on the living, still bleeding), then the blood on it dries dark
				GoreMesh.dryness((mc.level.getGameTime() - w.born() - (e.isAlive() ? 1200 : 400)) / 2400.0F);
				poseStack.pushPose();
				poseStack.translate(w.at().x + w.normal().x * 0.004F, w.at().y + w.normal().y * 0.004F, w.at().z + w.normal().z * 0.004F);
				// turn the decal (facing -Z, its run down -Y) to face out along the normal, the run down the creature's side
				Vector3f n = w.normal();
				Vector3f up = Math.abs(n.y) > 0.9F ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0).sub(new Vector3f(n).mul(n.y)).normalize();
				Vector3f back = new Vector3f(n).negate();
				Vector3f right = new Vector3f(up).cross(back).normalize();
				poseStack.mulPose(new Quaternionf().setFromNormalized(new org.joml.Matrix3f(right, up, back)));
				poseStack.mulPose(Axis.ZP.rotationDegrees(w.spin()));
				float s = w.size() / (16.0F * GoreMesh.P);
				poseStack.scale(s, s, s);
				if (open) {
					OPEN[w.variant() % 3].submit(poseStack, context.commandQueue(), light);
					poseStack.popPose();
					continue;
				}
				DECALS[w.variant() & 3].submit(poseStack, context.commandQueue(), light);
				if (ModConfig.gore) {
					if (w.kind() == de.rcm.ballistic.injury.Blood.GAPING) {
						GAPING[w.variant() % 3].submit(poseStack, context.commandQueue(), light);
					} else if (w.kind() == de.rcm.ballistic.injury.Blood.ENTRAILS) {
						ENTRAILS[w.variant() % 3].submit(poseStack, context.commandQueue(), light);
					}
				}
				poseStack.popPose();
			}
			poseStack.popPose();
		}
		GoreMesh.dryness(0.0F);
	}
}
