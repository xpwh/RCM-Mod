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
	private record Wound(Vector3f at, Vector3f normal, float size, int variant, float spin) {
	}

	private static final Int2ObjectOpenHashMap<List<Wound>> WOUNDS = new Int2ObjectOpenHashMap<>();
	private static final int MAX_PER = 14;
	private static final GoreMesh[] DECALS = new GoreMesh[4];

	static {
		for (int v = 0; v < 4; v++) {
			// one unit square, centred, facing -Z, the tile's run going down (+v towards -Y)
			DECALS[v] = new GoreMesh.Builder().decal(new Vector3f(-8, 8, 0), new Vector3f(16, 0, 0), new Vector3f(0, -16, 0), GoreMesh.T_MOB + v).build();
		}
	}

	private MobWounds() {
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
		list.add(new Wound(at, n, p.size(), p.variant(), (float) (Math.random() * 30.0 - 15.0)));
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
			poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - Mth.rotLerp(partial, e.yBodyRotO, e.yBodyRot)));
			if (e.deathTime > 0) {
				// keeling over as it dies, as the model does
				float f = Math.min(1.0F, Mth.sqrt((e.deathTime + partial - 1.0F) / 20.0F * 1.6F));
				poseStack.mulPose(Axis.ZP.rotationDegrees(f * 90.0F));
			}
			for (Wound w : en.getValue()) {
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
				DECALS[w.variant() & 3].submit(poseStack, context.commandQueue(), light);
				poseStack.popPose();
			}
			poseStack.popPose();
		}
	}
}
