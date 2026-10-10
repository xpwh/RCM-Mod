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
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * Bullet wounds on animals and mobs, where the round went in: a hole with blood soaking round it and
 * running down, stuck to the creature's body - it turns with it, and falls over with it when it dies.
 * Positions are kept in the body's own frame (turned with its body, as the renderer turns the model).
 */
public final class MobWounds {
	/** {@code dims}: for a body torn open, blocks per unit of the mesh along its length, height and depth (else null). */
	private record Wound(Vector3f at, Vector3f normal, float size, int variant, int kind, float spin, long born, Vector3f dims) {
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

	/** Where a body is torn open, which way the opening faces, how it is turned, and its size (see {@link Wound#dims}). */
	private record Body(Vector3f at, Vector3f normal, float spin, Vector3f dims) {
	}

	/** The trunk of each kind of creature, measured once off its model at rest: {min x, y, z, max x, y, z}, blocks, body frame. */
	private static final java.util.Map<String, float[]> TRUNKS = new java.util.HashMap<>();

	private static Body body(LivingEntity e) {
		float[] t = TRUNKS.computeIfAbsent(e.getType().toString() + (e.isBaby() ? "/baby" : ""), k -> trunk(e));
		float w = e.getBbWidth();
		float h = e.getBbHeight();
		if (t == null) {
			t = new float[] {-w * 0.38F, h * 0.3F, -w * 0.5F, w * 0.38F, h * 0.8F, w * 0.5F};
		}
		float dx = t[3] - t[0];
		float dy = t[4] - t[1];
		float dz = t[5] - t[2];
		// a sheep's fleece stands well proud of the skin
		float fleece = e instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep && !sheep.isSheared() ? 0.11F : 0.0F;
		if (dz >= dy) {
			// on four legs: along the flank, which lies uppermost once it has keeled over
			return new Body(new Vector3f(t[3] + fleece + 0.01F, (t[1] + t[4]) * 0.5F, (t[2] + t[5]) * 0.5F), new Vector3f(1, 0, 0), 0.0F,
				new Vector3f(dz * 0.85F / 20.0F, dy * 0.85F / 12.0F, (dx + fleece * 2.0F) / 16.0F));
		}
		// upright: down the front of the trunk
		return new Body(new Vector3f((t[0] + t[3]) * 0.5F, (t[1] + t[4]) * 0.5F, t[2] - fleece - 0.01F), new Vector3f(0, 0, -1), 90.0F,
			new Vector3f(dy * 0.85F / 20.0F, dx * 0.85F / 12.0F, dz / 16.0F));
	}

	/** The creature's trunk (its model's "body" part, else the whole model), at rest, in the body frame - or null. */
	private static float[] trunk(LivingEntity e) {
		try {
			if (!(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e) instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer<?, ?, ?> lr)) {
				return null;
			}
			net.minecraft.client.model.geom.ModelPart root = lr.getModel().root();
			root.getAllParts().forEach(net.minecraft.client.model.geom.ModelPart::resetPose);
			float[] trunk = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			float[] all = trunk.clone();
			PoseStack ps = new PoseStack();
			// as the renderer sets the model up
			ps.scale(-1.0F, -1.0F, 1.0F);
			ps.translate(0.0F, -1.501F, 0.0F);
			Vector3f q = new Vector3f();
			root.visit(ps, (pose, path, index, cube) -> {
				boolean isTrunk = path.equals("body") || path.endsWith("/body") || path.contains("body/");
				for (int c = 0; c < 8; c++) {
					float x = (c & 1) == 0 ? cube.minX : cube.maxX;
					float y = (c & 2) == 0 ? cube.minY : cube.maxY;
					float z = (c & 4) == 0 ? cube.minZ : cube.maxZ;
					pose.pose().transformPosition(x / 16.0F, y / 16.0F, z / 16.0F, q);
					grow(all, q);
					if (isTrunk) {
						grow(trunk, q);
					}
				}
			});
			float[] t = trunk[0] < trunk[3] ? trunk : all[0] < all[3] ? all : null;
			if (t != null && (t[3] - t[0] < 0.05F || t[4] - t[1] < 0.05F || t[5] - t[2] < 0.05F)) {
				return null;
			}
			return t;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static void grow(float[] b, Vector3f p) {
		b[0] = Math.min(b[0], p.x);
		b[1] = Math.min(b[1], p.y);
		b[2] = Math.min(b[2], p.z);
		b[3] = Math.max(b[3], p.x);
		b[4] = Math.max(b[4], p.y);
		b[5] = Math.max(b[5], p.z);
	}

	/**
	 * A body torn wide open, in the mesh's own units - scaled onto each creature by {@link Body#dims}: x -10..10
	 * along the trunk (+x towards the head), y -6..6 across it (-y towards the belly), z out of it (-z) and into
	 * it (+z) to the ground, 16 below. A ragged opening with a raised, torn lip - hide, fat and muscle in layers
	 * - and depth below it: the ribs arching over the front half, some snapped and bent out, the lungs and the
	 * heart under them, the liver, the stomach, the gut packed in coils behind, dark blood pooled among them,
	 * flaps of hide peeled back, and a loop of gut slid out over the belly onto the ground.
	 */
	private static GoreMesh openBody(int v) {
		RandomSource r = RandomSource.create(1300L + v * 17L);
		GoreMesh.Builder b = new GoreMesh.Builder();
		float a = 8.4F;
		float bh = 4.6F;
		float p1 = r.nextFloat() * Mth.TWO_PI;
		float p2 = r.nextFloat() * Mth.TWO_PI;
		int segs = 36;
		Vector3f[] edge = new Vector3f[segs + 1];
		for (int i = 0; i <= segs; i++) {
			float t = i / (float) segs * Mth.TWO_PI;
			float rag = 1.0F + 0.08F * Mth.sin(5.0F * t + p1) + 0.05F * Mth.sin(11.0F * t + p2);
			edge[i] = new Vector3f(Mth.cos(t) * a * rag, Mth.sin(t) * bh * rag, 0.0F);
		}
		// blood soaked into the hide all round the opening
		b.decalGrid(new Vector3f(-a - 3.5F, -bh - 4.0F, -0.02F), new Vector3f(2 * a + 7.0F, 0, 0), new Vector3f(0, 2 * bh + 6.0F, 0), GoreMesh.T_MOB + 2 + (v & 1),
			new Vector3f(0, 0, -1), 3.0F);
		Vector3f out = new Vector3f(0, 0, -1);
		// the floor of the hole: dark, deepest in the middle
		int rings = 6;
		for (int k = 0; k < rings; k++) {
			float r0 = k / (float) rings;
			float r1 = (k + 1) / (float) rings;
			for (int i = 0; i < segs; i++) {
				float t0 = i / (float) segs * Mth.TWO_PI;
				float t1 = (i + 1) / (float) segs * Mth.TWO_PI;
				Vector3f[] q = {new Vector3f(edge[i]).mul(r0).add(0, 0, -0.06F), new Vector3f(edge[i + 1]).mul(r0).add(0, 0, -0.06F),
					new Vector3f(edge[i + 1]).mul(r1).add(0, 0, -0.06F), new Vector3f(edge[i]).mul(r1).add(0, 0, -0.06F)};
				float[][] uv = {cavityUv(r0, t0), cavityUv(r0, t1), cavityUv(r1, t1), cavityUv(r1, t0)};
				b.quad(q, uv, new Vector3f[] {out, out, out, out});
			}
		}
		// the wall of the hole, from its floor up to the torn lip
		float lip = -1.5F;
		for (int i = 0; i < segs; i++) {
			Vector3f a0 = new Vector3f(edge[i]).add(0, 0, -0.06F);
			Vector3f a1 = new Vector3f(edge[i + 1]).add(0, 0, -0.06F);
			Vector3f b1 = new Vector3f(edge[i + 1]).mul(1.03F).add(0, 0, lip);
			Vector3f b0 = new Vector3f(edge[i]).mul(1.03F).add(0, 0, lip);
			Vector3f n0 = new Vector3f(edge[i]).negate().normalize().add(0, 0, -0.5F).normalize();
			Vector3f n1 = new Vector3f(edge[i + 1]).negate().normalize().add(0, 0, -0.5F).normalize();
			float u0 = i / (float) segs;
			float u1 = (i + 1) / (float) segs;
			b.quad(new Vector3f[] {a0, a1, b1, b0}, new float[][] {{GoreMesh.u(GoreMesh.T_MEAT, u0 * 4 % 1), GoreMesh.v(GoreMesh.T_MEAT, 1)},
				{GoreMesh.u(GoreMesh.T_MEAT, Math.min(1.0F, u0 * 4 % 1 + 0.11F)), GoreMesh.v(GoreMesh.T_MEAT, 1)},
				{GoreMesh.u(GoreMesh.T_MEAT, Math.min(1.0F, u0 * 4 % 1 + 0.11F)), GoreMesh.v(GoreMesh.T_MEAT, 0)}, {GoreMesh.u(GoreMesh.T_MEAT, u0 * 4 % 1), GoreMesh.v(GoreMesh.T_MEAT, 0)}},
				new Vector3f[] {n0, n1, n1, n0});
		}
		// the torn lip itself: hide, fat and muscle, ragged, all round
		Vector3f[] rim = new Vector3f[segs + 1];
		float[] rr = new float[segs + 1];
		for (int i = 0; i <= segs; i++) {
			rim[i] = new Vector3f(edge[i % segs]).mul(1.06F).add(0, 0, lip + 0.35F);
			rr[i] = 0.55F + 0.25F * Mth.sin(i * 1.7F + p1) * Mth.sin(i * 0.6F + p2);
		}
		b.path(rim, rr, GoreMesh.T_RIM);
		// flaps of hide peeled back and lying open on the body
		for (int i = 0; i < 4; i++) {
			int at = (i * segs / 4 + r.nextInt(4)) % segs;
			Vector3f base = new Vector3f(edge[at]).mul(1.08F).add(0, 0, lip + 0.4F);
			Vector3f dir = new Vector3f(edge[at]).normalize().add(0, 0, 0.35F).normalize();
			b.shard(base, dir, new Vector3f(0, 0, -1), 2.0F + r.nextFloat() * 1.5F, 2.2F + r.nextFloat(), GoreMesh.T_FLAP, 0.15F);
		}
		// blood pooled in the bottom of it, clotting
		b.decalGrid(new Vector3f(-a * 0.85F, -bh * 0.9F, -0.12F), new Vector3f(a * 1.7F, 0, 0), new Vector3f(0, bh * 1.1F, 0), GoreMesh.T_POOL, out, 2.0F);
		// the organs, lying in it below the lip: the lungs and the heart in front, the liver, the stomach, the gut behind
		b.blob(new Vector3f(4.0F, 1.7F, -0.75F), 3.0F, 1.8F, 0.65F, 0.22F, 1310L + v, GoreMesh.T_LUNG);
		b.blob(new Vector3f(4.3F, -1.5F, -0.7F), 2.7F, 1.6F, 0.6F, 0.22F, 1311L + v, GoreMesh.T_LUNG);
		b.blob(new Vector3f(5.8F, 0.0F, -1.0F), 1.0F, 1.25F, 0.85F, 0.12F, 1320L + v, GoreMesh.T_HEART);
		b.blob(new Vector3f(0.6F, 1.2F, -0.8F), 2.1F, 2.3F, 0.7F, 0.18F, 1330L + v, GoreMesh.T_LIVER);
		b.blob(new Vector3f(-0.4F, -1.7F, -0.7F), 1.6F, 1.4F, 0.6F, 0.18F, 1331L + v, GoreMesh.T_LIVER);
		b.blob(new Vector3f(-2.0F, 1.9F, -0.8F), 1.9F, 1.6F, 0.7F, 0.28F, 1340L + v, GoreMesh.T_STOMACH);
		// the gut, packed in tight coils in the back of it, one loop over another
		for (int c = 0; c < 3; c++) {
			int n = 34;
			Vector3f[] pts = new Vector3f[n];
			float[] rad = new float[n];
			Vector3f pos = new Vector3f(-3.0F - c * 1.8F, -2.6F + c * 1.6F, -0.6F);
			float heading = r.nextFloat() * Mth.TWO_PI;
			for (int k = 0; k < n; k++) {
				heading += (r.nextFloat() - 0.5F) * 1.6F + 0.35F;
				pos.add(Mth.cos(heading) * 0.62F, Mth.sin(heading) * 0.62F, 0);
				// kept inside the back half of the hole
				float ex = (pos.x + 5.0F) / 3.4F;
				float ey = pos.y / (bh * 0.82F);
				if (ex * ex + ey * ey > 1.0F) {
					pos.x -= (pos.x + 5.0F) * 0.25F;
					pos.y *= 0.75F;
					heading += Mth.PI * 0.6F;
				}
				pts[k] = new Vector3f(pos.x, pos.y, -0.55F - 0.45F * (0.5F + 0.5F * Mth.sin(k * 0.9F + c)));
				rad[k] = 0.5F + 0.06F * Mth.sin(k * 1.3F);
			}
			b.path(pts, rad, GoreMesh.T_GUT);
		}
		// the ribs: arching over the front half, a few snapped and their ends bent out
		int ribs = 8;
		int broken0 = 2 + v;
		for (int i = 0; i < ribs; i++) {
			float x = -0.2F + i * 1.05F;
			float hy = bh * (float) Math.sqrt(Math.max(0.05F, 1.0F - (x / a) * (x / a))) * 0.98F;
			boolean broken = i == broken0 || i == broken0 + 2;
			int n = 9;
			Vector3f[] pts = new Vector3f[n];
			float[] rad = new float[n];
			for (int k = 0; k < n; k++) {
				float t = k / (float) (n - 1);
				pts[k] = new Vector3f(x + 0.35F * t, Mth.lerp(t, hy, -hy), lip + 0.2F - 0.6F * Mth.sin(t * Mth.PI));
				rad[k] = 0.27F;
			}
			if (!broken) {
				b.path(pts, rad, GoreMesh.T_BONE);
				continue;
			}
			// snapped: the two halves bent outwards, splintered
			float cut = 0.4F + r.nextFloat() * 0.2F;
			int k1 = Math.round(cut * (n - 1));
			Vector3f[] top = java.util.Arrays.copyOf(pts, k1 + 1);
			Vector3f[] bottom = java.util.Arrays.copyOfRange(pts, k1 + 1, n);
			top[k1] = new Vector3f(top[k1]).add(0.2F, -0.3F, -0.9F);
			bottom[0] = new Vector3f(bottom[0]).add(-0.2F, 0.4F, -0.7F);
			b.path(top, java.util.Arrays.copyOf(rad, top.length), GoreMesh.T_BONE);
			b.path(bottom, java.util.Arrays.copyOf(rad, bottom.length), GoreMesh.T_BONE);
			b.shard(top[k1], new Vector3f(0.1F, -0.4F, -1.0F).normalize(), new Vector3f(1, 0, 0), 0.8F, 0.35F, GoreMesh.T_BONE_THIN, 0.1F);
			b.shard(bottom[0], new Vector3f(-0.1F, 0.5F, -1.0F).normalize(), new Vector3f(1, 0, 0), 0.7F, 0.35F, GoreMesh.T_BONE_THIN, 0.1F);
		}
		// strings of blood and membrane across the opening
		for (int i = 0; i < 4; i++) {
			float x = (r.nextFloat() - 0.5F) * a * 1.4F;
			b.tube(new Vector3f(x, bh * 0.8F, lip + 0.2F), new Vector3f(x + (r.nextFloat() - 0.5F) * 2.0F, -bh * 0.4F, -0.9F), 0.09F, 0.06F, GoreMesh.T_STRAND);
		}
		// a loop of gut slid out over the belly and down onto the ground
		int n = 18;
		Vector3f[] pts = new Vector3f[n];
		float[] rad = new float[n];
		float x0 = -2.5F - v * 0.8F;
		for (int k = 0; k < n; k++) {
			float t = k / (float) (n - 1);
			Vector3f p;
			if (t < 0.3F) {
				float q = t / 0.3F;
				p = new Vector3f(x0 + q * 1.2F, -2.5F - q * 3.0F, -0.9F + Mth.sin(q * Mth.PI) * -0.8F);
			} else if (t < 0.6F) {
				float q = (t - 0.3F) / 0.3F;
				p = new Vector3f(x0 + 1.2F + Mth.sin(q * 3.0F) * 0.8F, -5.8F - q * 0.6F, -0.4F + q * 15.5F);
			} else {
				float q = (t - 0.6F) / 0.4F;
				float ang = q * 6.5F + v;
				p = new Vector3f(x0 + 1.2F + Mth.cos(ang) * (1.2F + q * 1.6F), -7.2F - Mth.sin(ang) * 1.2F - q * 1.8F, 15.4F - (k & 1) * 0.25F);
			}
			pts[k] = p;
			rad[k] = 0.5F;
		}
		b.path(pts, rad, GoreMesh.T_GUT);
		return b.build();
	}

	/** Texture coordinates on the cavity tile: its dark middle at the middle of the hole. */
	private static float[] cavityUv(float r, float t) {
		return new float[] {GoreMesh.u(GoreMesh.T_CAVITY, 0.5F + 0.48F * r * Mth.cos(t)), GoreMesh.v(GoreMesh.T_CAVITY, 0.5F + 0.48F * r * Mth.sin(t))};
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
		if (e.deathTime >= 20 && de.rcm.ballistic.injury.CorpseHits.isCorpse(e)) {
			// a body lying (turned, lifted, fallen on its side): where the round struck it, as it lies
			Vector3f lying = de.rcm.ballistic.injury.CorpsePose.toBody(e, e.position().add(p.rx(), p.ry(), p.rz()));
			Quaternionf back = new Quaternionf(de.rcm.ballistic.injury.CorpsePose.turn(e)).conjugate();
			Vector3f ln = new Quaternionf(de.rcm.ballistic.injury.CorpsePose.facing(e)).conjugate().transform(back.transform(new Vector3f(p.nx(), p.ny(), p.nz())));
			// and undo its fall onto its side
			Quaternionf unfall = new Quaternionf().rotationZ(-90.0F * Mth.DEG_TO_RAD);
			at = unfall.transform(lying);
			n = unfall.transform(ln);
		}
		List<Wound> list = WOUNDS.computeIfAbsent(p.entity(), k -> new ArrayList<>());
		if (list.size() >= MAX_PER) {
			list.remove(0);
		}
		int kind = p.variant() >> 4;
		if (kind == de.rcm.ballistic.injury.Blood.OPEN) {
			// torn open where the body really is: along the flank that lies up once it has fallen (an animal), or
			// down the front of the trunk (something on two legs) - measured off its own model
			list.removeIf(w -> w.kind() == de.rcm.ballistic.injury.Blood.OPEN);
			Body b = body(e);
			list.add(new Wound(b.at, b.normal, p.size(), p.variant() & 3, kind, b.spin, mc.level.getGameTime(), b.dims));
			Vec3 world = e.position().add(0, e.getBbHeight() * 0.5, 0);
			GibClient.spray(world, new Vec3(0, 1, 0), 6 + (int) (Math.random() * 4), 0.25F + 0.3F * p.size());
			return;
		}
		list.add(new Wound(at, n, p.size(), p.variant() & 3, kind, (float) (Math.random() * 30.0 - 15.0), mc.level.getGameTime(), null));
		if (kind != de.rcm.ballistic.injury.Blood.HOLE) {
			// bits torn out of it, thrown out of the hole
			Vec3 world = e.position().add(p.rx(), p.ry(), p.rz());
			GibClient.spray(world, new Vec3(p.nx(), p.ny(), p.nz()), kind == de.rcm.ballistic.injury.Blood.EXIT ? 2 : 3 + (int) (Math.random() * 3),
				0.22F + p.size());
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
			float[] lying = CorpseFx.pose(e, partial);
			if (lying != null) {
				CorpseFx.apply(poseStack, lying, 1.0F);
			}
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
				if (open) {
					poseStack.scale(w.dims().x / GoreMesh.P, w.dims().y / GoreMesh.P, w.dims().z / GoreMesh.P);
					OPEN[w.variant() % 3].submit(poseStack, context.commandQueue(), light);
					poseStack.popPose();
					continue;
				}
				float s = w.size() / (16.0F * GoreMesh.P);
				poseStack.scale(s, s, s);
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
