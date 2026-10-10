package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.ModConfig;
import de.rcm.ballistic.client.render.GoreMesh;
import de.rcm.ballistic.client.render.GoreStump;
import de.rcm.ballistic.network.ModNetworking.GibPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A body torn apart by a blast: the pieces thrown up and out - for a player their own head, arms, legs and
 * trunk (in their skin, the torn ends raw), for any creature lumps of meat, splintered bones, coils of gut,
 * liver, heart and lung - spinning, trailing blood, bouncing where they land and splashing it, lying about
 * for a minute and a half.
 */
public final class GibClient {
	private static final RandomSource RANDOM = RandomSource.create();
	private static final int LIFE = 1800;
	private static final int MAX = 450;
	private static final GoreMesh[] MEAT = new GoreMesh[6];
	private static final GoreMesh[] GUT = new GoreMesh[3];
	private static final GoreMesh[] BONE = new GoreMesh[3];
	private static final GoreMesh LIVER;
	private static final GoreMesh HEART;
	private static final GoreMesh LUNG;
	/** The raw ends of a player's torn-off parts: a limb's (4 x 4 px) and the trunk's (8 x 4). */
	private static final GoreMesh LIMB_END = GoreStump.cap(2.15F, 2.15F, 77L);
	private static final GoreMesh TRUNK_END = GoreStump.cap(4.1F, 2.1F, 78L);

	static {
		for (int i = 0; i < MEAT.length; i++) {
			GoreMesh.Builder b = new GoreMesh.Builder();
			b.blob(new Vector3f(), 1.4F + i * 0.25F, 1.0F + (i % 3) * 0.3F, 1.2F, 0.3F, 300L + i, i % 2 == 0 ? GoreMesh.T_MEAT : GoreMesh.T_SECTION);
			if (i % 2 == 1) {
				b.shard(new Vector3f(0, 0.6F, 0), new Vector3f(0.3F, 1, 0).normalize(), new Vector3f(1, 0, 0), 1.4F, 0.9F, GoreMesh.T_FLAP, 0.2F);
			}
			MEAT[i] = b.build();
		}
		for (int i = 0; i < GUT.length; i++) {
			RandomSource r = RandomSource.create(400L + i);
			int n = 12 + i * 4;
			Vector3f[] pts = new Vector3f[n];
			float[] rad = new float[n];
			Vector3f p = new Vector3f();
			float a = 0.0F;
			for (int k = 0; k < n; k++) {
				a += 0.6F + r.nextFloat() * 0.5F;
				p = new Vector3f(p).add(Mth.cos(a) * 0.9F, (r.nextFloat() - 0.5F) * 0.5F, Mth.sin(a) * 0.9F);
				pts[k] = p;
				rad[k] = 0.42F + r.nextFloat() * 0.08F;
			}
			GUT[i] = new GoreMesh.Builder().path(pts, rad, GoreMesh.T_GUT).build();
		}
		for (int i = 0; i < BONE.length; i++) {
			GoreMesh.Builder b = new GoreMesh.Builder();
			float len = 3.0F + i * 1.5F;
			b.tube(new Vector3f(0, -len / 2, 0), new Vector3f(0, len / 2, 0), 0.5F, 0.42F, GoreMesh.T_BONE);
			b.blob(new Vector3f(0, -len / 2, 0), 0.75F, 0.55F, 0.7F, 0.15F, 500L + i, GoreMesh.T_BONE);
			b.shard(new Vector3f(0, len / 2, 0), new Vector3f(0.2F, 1, 0).normalize(), new Vector3f(1, 0, 0), 0.9F, 0.6F, GoreMesh.T_BONE, 0.1F);
			b.blob(new Vector3f(0.3F, -len / 4, 0), 0.6F, 1.0F, 0.6F, 0.3F, 510L + i, GoreMesh.T_MEAT);
			BONE[i] = b.build();
		}
		LIVER = new GoreMesh.Builder().blob(new Vector3f(), 2.4F, 1.0F, 1.6F, 0.15F, 600L, GoreMesh.T_LIVER).build();
		HEART = new GoreMesh.Builder().blob(new Vector3f(), 1.3F, 1.6F, 1.2F, 0.2F, 601L, GoreMesh.T_HEART).build();
		LUNG = new GoreMesh.Builder().blob(new Vector3f(), 1.6F, 2.4F, 1.1F, 0.18F, 602L, GoreMesh.T_LUNG).build();
	}

	private static final class Piece {
		GoreMesh mesh;
		ModelPart part;
		Identifier skin;
		/** For a body part: its middle in the model's pixels, and which end(s) were torn (bit 1 top, bit 2 bottom). */
		Vector3f centre;
		float top;
		float bottom;
		int torn;
		boolean trunk;
		double x;
		double y;
		double z;
		double px;
		double py;
		double pz;
		double vx;
		double vy;
		double vz;
		Quaternionf rot = new Quaternionf();
		Quaternionf prot = new Quaternionf();
		Vector3f spin = new Vector3f();
		float scale;
		float radius;
		int age;
		boolean resting;
		boolean landed;
	}

	private static final List<Piece> PIECES = new ArrayList<>();

	private GibClient() {
	}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(GibPayload.TYPE, (payload, context) -> receive(payload));
		ClientTickEvents.END_CLIENT_TICK.register(GibClient::tick);
		WorldRenderEvents.AFTER_ENTITIES.register(GibClient::render);
	}

	private static void receive(GibPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || !ModConfig.gore) {
			return;
		}
		Entity e = mc.level.getEntity(p.entity());
		Vec3 base = new Vec3(p.x(), p.y(), p.z());
		Vec3 dir = new Vec3(p.dx(), p.dy(), p.dz());
		float force = Mth.clamp(p.force(), 0.6F, 2.5F);
		float volume = p.width() * p.width() * p.height();
		float size = Mth.clamp((float) Math.cbrt(volume / 0.65F), 0.35F, 2.6F);
		if (e instanceof net.minecraft.client.entity.ClientAvatarEntity avatar && e instanceof net.minecraft.world.entity.LivingEntity person
			&& mc.getEntityRenderDispatcher().getRenderer(e) instanceof net.minecraft.client.renderer.entity.player.AvatarRenderer<?> renderer) {
			// a person (or the body one left): their own head, arms, legs and trunk, torn from each other
			PlayerModel model = renderer.getModel();
			Identifier skin = avatar.getSkin().body().texturePath();
			boolean slim = avatar.getSkin().model() == PlayerModelType.SLIM;
			float yaw = person.yBodyRot;
			part(model.head, skin, new Vector3f(0, -4, 0), -8, 0, 2, false, base, 1.55, 0, yaw, dir, force);
			part(model.body, skin, new Vector3f(0, 6, 0), 0, 12, 3, true, base, 1.1, 0, yaw, dir, force);
			part(model.rightArm, skin, new Vector3f(slim ? -0.5F : -1, 4, 0), -2, 10, 1, false, base, 1.2, -0.35, yaw, dir, force);
			part(model.leftArm, skin, new Vector3f(slim ? 0.5F : 1, 4, 0), -2, 10, 1, false, base, 1.2, 0.35, yaw, dir, force);
			part(model.rightLeg, skin, new Vector3f(0, 6, 0), 0, 12, 1, false, base, 0.4, -0.12, yaw, dir, force);
			part(model.leftLeg, skin, new Vector3f(0, 6, 0), 0, 12, 1, false, base, 0.4, 0.12, yaw, dir, force);
		}
		// what was inside: meat, bone, gut and the organs
		int meat = Math.round(Mth.clamp(5 + volume * 14, 4, 30));
		for (int i = 0; i < meat; i++) {
			loose(MEAT[RANDOM.nextInt(MEAT.length)], base, p.height(), dir, force, size * (0.6F + RANDOM.nextFloat() * 0.6F));
		}
		int bones = Math.round(Mth.clamp(2 + volume * 5, 2, 12));
		for (int i = 0; i < bones; i++) {
			loose(BONE[RANDOM.nextInt(BONE.length)], base, p.height(), dir, force, size * (0.6F + RANDOM.nextFloat() * 0.5F));
		}
		int guts = Math.round(Mth.clamp(1 + volume * 3, 1, 6));
		for (int i = 0; i < guts; i++) {
			loose(GUT[RANDOM.nextInt(GUT.length)], base, p.height(), dir, force * 0.8F, size * (0.7F + RANDOM.nextFloat() * 0.4F));
		}
		loose(LIVER, base, p.height(), dir, force * 0.9F, size);
		loose(HEART, base, p.height(), dir, force, size * 0.9F);
		loose(LUNG, base, p.height(), dir, force * 0.9F, size);
		if (volume > 0.4F) {
			loose(LUNG, base, p.height(), dir, force * 0.9F, size);
		}
		while (PIECES.size() > MAX) {
			PIECES.remove(0);
		}
	}

	/** Bits of meat torn out of a wound and thrown out along {@code dir} (a creature hit hard). */
	public static void spray(Vec3 at, Vec3 dir, int n, float size) {
		if (!ModConfig.gore) {
			return;
		}
		for (int i = 0; i < n; i++) {
			Piece g = new Piece();
			g.mesh = RANDOM.nextInt(4) == 0 ? BONE[RANDOM.nextInt(BONE.length)] : MEAT[RANDOM.nextInt(MEAT.length)];
			g.scale = size * (0.6F + RANDOM.nextFloat() * 0.6F);
			g.radius = 0.08F * g.scale;
			g.x = g.px = at.x;
			g.y = g.py = at.y;
			g.z = g.pz = at.z;
			double speed = 0.15 + RANDOM.nextDouble() * 0.25;
			g.vx = dir.x * speed + RANDOM.nextGaussian() * 0.06;
			g.vy = dir.y * speed + 0.1 + RANDOM.nextDouble() * 0.15;
			g.vz = dir.z * speed + RANDOM.nextGaussian() * 0.06;
			g.spin.set(RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F).normalize().mul(0.3F);
			g.rot.rotationXYZ(RANDOM.nextFloat() * 6.28F, RANDOM.nextFloat() * 6.28F, RANDOM.nextFloat() * 6.28F);
			g.prot.set(g.rot);
			PIECES.add(g);
		}
		while (PIECES.size() > MAX) {
			PIECES.remove(0);
		}
	}

	/** Thrown from the blast: mostly up, out along its push, every which way a little. */
	/** Bits of a body torn out by a blast (it is not torn apart): flesh, gut, splinters of bone, flung up and out. */
	public static void blown(Vec3 at, Vec3 dir, int n, float size, float force) {
		if (!ModConfig.gore) {
			return;
		}
		for (int i = 0; i < n; i++) {
			int k = RANDOM.nextInt(10);
			GoreMesh mesh = k < 6 ? MEAT[RANDOM.nextInt(MEAT.length)] : k < 8 ? BONE[RANDOM.nextInt(BONE.length)] : GUT[RANDOM.nextInt(GUT.length)];
			Piece g = new Piece();
			g.mesh = mesh;
			g.scale = size * (0.5F + RANDOM.nextFloat() * 0.6F);
			g.radius = 0.08F * g.scale;
			g.x = g.px = at.x + RANDOM.nextGaussian() * 0.12;
			g.y = g.py = at.y + RANDOM.nextGaussian() * 0.12;
			g.z = g.pz = at.z + RANDOM.nextGaussian() * 0.12;
			launch(g, dir, force);
			PIECES.add(g);
		}
		while (PIECES.size() > MAX) {
			PIECES.remove(0);
		}
	}

	private static void launch(Piece g, Vec3 dir, float force) {
		double up = (0.35 + RANDOM.nextDouble() * 0.5) * force;
		double out = (0.15 + RANDOM.nextDouble() * 0.35) * force;
		g.vx = dir.x * out + RANDOM.nextGaussian() * 0.17 * force;
		g.vy = Math.max(0.15, dir.y * out + up);
		g.vz = dir.z * out + RANDOM.nextGaussian() * 0.17 * force;
		double speed = Math.sqrt(g.vx * g.vx + g.vy * g.vy + g.vz * g.vz);
		if (speed > 1.7) {
			g.vx *= 1.7 / speed;
			g.vy *= 1.7 / speed;
			g.vz *= 1.7 / speed;
		}
		g.spin.set(RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F).normalize().mul(0.15F + RANDOM.nextFloat() * 0.45F);
		g.rot.rotationXYZ(RANDOM.nextFloat() * 6.28F, RANDOM.nextFloat() * 6.28F, RANDOM.nextFloat() * 6.28F);
		g.prot.set(g.rot);
	}

	private static void loose(GoreMesh mesh, Vec3 base, float height, Vec3 dir, float force, float scale) {
		Piece g = new Piece();
		g.mesh = mesh;
		g.scale = scale;
		g.radius = 0.08F * scale;
		g.x = g.px = base.x + RANDOM.nextGaussian() * 0.15;
		g.y = g.py = base.y + height * (0.3 + RANDOM.nextDouble() * 0.5);
		g.z = g.pz = base.z + RANDOM.nextGaussian() * 0.15;
		launch(g, dir, force);
		PIECES.add(g);
	}

	private static void part(ModelPart part, Identifier skin, Vector3f centre, float top, float bottom, int torn, boolean trunk, Vec3 base, double height,
		double side, float yaw, Vec3 dir, float force) {
		Piece g = new Piece();
		g.part = part;
		g.skin = skin;
		g.centre = centre;
		g.top = top;
		g.bottom = bottom;
		g.torn = torn;
		g.trunk = trunk;
		g.scale = 1.0F;
		g.radius = trunk ? 0.2F : 0.14F;
		double r = yaw * Mth.DEG_TO_RAD;
		g.x = g.px = base.x + Math.cos(r) * side;
		g.y = g.py = base.y + height;
		g.z = g.pz = base.z + Math.sin(r) * side;
		launch(g, dir, force * (trunk ? 0.7F : 1.0F));
		PIECES.add(g);
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			PIECES.clear();
			return;
		}
		PIECES.removeIf(g -> ++g.age > LIFE);
		for (Piece g : PIECES) {
			g.px = g.x;
			g.py = g.y;
			g.pz = g.z;
			g.prot.set(g.rot);
			if (g.resting) {
				continue;
			}
			g.vy -= 0.045;
			g.vx *= 0.985;
			g.vy *= 0.985;
			g.vz *= 0.985;
			double nx = g.x + g.vx;
			double ny = g.y + g.vy;
			double nz = g.z + g.vz;
			// walls: bounce back off them
			if (solidAt(mc, nx, g.y, g.z)) {
				g.vx = -g.vx * 0.3;
				nx = g.x;
			}
			if (solidAt(mc, g.x, g.y, nz)) {
				g.vz = -g.vz * 0.3;
				nz = g.z;
			}
			// the ground: land, splash, bounce a little and roll to a stop
			double floor = floor(mc, nx, ny, nz);
			if (ny - g.radius < floor && g.vy < 0) {
				ny = floor + g.radius;
				if (!g.landed || g.vy < -0.2) {
					BloodClient.splat(new Vec3(nx, ny, nz), Mth.clamp(0.25F + (float) -g.vy * 0.8F, 0.25F, 0.9F) * Math.min(1.5F, g.scale));
				}
				g.landed = true;
				g.vy = -g.vy * 0.25;
				g.vx *= 0.5;
				g.vz *= 0.5;
				g.spin.mul(0.5F);
				if (Math.abs(g.vy) < 0.06 && g.vx * g.vx + g.vz * g.vz < 0.002) {
					g.resting = true;
					g.vx = g.vy = g.vz = 0.0;
				}
			} else if (g.age % 2 == 0 && g.age < 80) {
				// a trail of blood behind it through the air
				BloodClient.drop(new Vec3(g.x, g.y, g.z), new Vec3(g.vx * 0.2 + RANDOM.nextGaussian() * 0.03, g.vy * 0.2, g.vz * 0.2 + RANDOM.nextGaussian() * 0.03),
					0.03F + RANDOM.nextFloat() * 0.04F);
			}
			g.x = nx;
			g.y = ny;
			g.z = nz;
			float angle = g.spin.length();
			if (angle > 1.0E-4F) {
				g.rot.premul(new Quaternionf().rotateAxis(angle, g.spin.x / angle, g.spin.y / angle, g.spin.z / angle));
			}
		}
	}

	private static boolean solidAt(Minecraft mc, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y, z);
		BlockState s = mc.level.getBlockState(pos);
		VoxelShape shape = s.getCollisionShape(mc.level, pos);
		return !shape.isEmpty() && y - pos.getY() < shape.max(net.minecraft.core.Direction.Axis.Y);
	}

	/** Top of whatever is under the point (or far below, in the air). */
	private static double floor(Minecraft mc, double x, double y, double z) {
		BlockPos pos = BlockPos.containing(x, y, z);
		for (int i = 0; i < 2; i++) {
			VoxelShape shape = mc.level.getBlockState(pos).getCollisionShape(mc.level, pos);
			if (!shape.isEmpty()) {
				return pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
			}
			pos = pos.below();
		}
		return -1.0E9;
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (PIECES.isEmpty() || mc.level == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		PoseStack poseStack = context.matrices();
		var collector = context.commandQueue();
		for (Piece g : PIECES) {
			double x = Mth.lerp(partial, g.px, g.x);
			double y = Mth.lerp(partial, g.py, g.y);
			double z = Mth.lerp(partial, g.pz, g.z);
			if ((x - cam.x) * (x - cam.x) + (y - cam.y) * (y - cam.y) + (z - cam.z) * (z - cam.z) > 72 * 72) {
				continue;
			}
			int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(x, y + 0.1, z));
			// lying there, the blood on it goes dull and dark
			GoreMesh.dryness((g.age - 200) / 2000.0F);
			poseStack.pushPose();
			poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
			poseStack.mulPose(new Quaternionf(g.prot).slerp(g.rot, partial));
			if (g.part != null) {
				// the model's own space: flipped as the entity renderer does, scaled to blocks, about the part's middle
				poseStack.scale(-1.0F, -1.0F, 1.0F);
				poseStack.translate(-g.centre.x * GoreMesh.P, -g.centre.y * GoreMesh.P, -g.centre.z * GoreMesh.P);
				ModelPart part = g.part;
				part.resetPose();
				part.x = 0.0F;
				part.y = 0.0F;
				part.z = 0.0F;
				part.xRot = 0.0F;
				part.yRot = 0.0F;
				part.zRot = 0.0F;
				part.visible = true;
				collector.submitModelPart(part, poseStack, RenderTypes.entityTranslucent(g.skin), light, OverlayTexture.NO_OVERLAY, null);
				GoreMesh end = g.trunk ? TRUNK_END : LIMB_END;
				if ((g.torn & 1) != 0) {
					// torn off at the top (shoulder, hip, neck): the raw end facing up
					poseStack.pushPose();
					poseStack.translate(g.centre.x * GoreMesh.P, g.top * GoreMesh.P, 0.0F);
					poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
					end.submit(poseStack, collector, light);
					poseStack.popPose();
				}
				if ((g.torn & 2) != 0) {
					poseStack.pushPose();
					poseStack.translate(g.centre.x * GoreMesh.P, g.bottom * GoreMesh.P, 0.0F);
					end.submit(poseStack, collector, light);
					poseStack.popPose();
				}
			} else {
				poseStack.scale(g.scale, g.scale, g.scale);
				g.mesh.submit(poseStack, collector, light);
			}
			poseStack.popPose();
		}
		GoreMesh.dryness(0.0F);
	}
}
