package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import de.rcm.ballistic.client.effect.DynamicLights;
import de.rcm.ballistic.client.effect.SmokeField;
import de.rcm.ballistic.client.render.GunAttachmentModels;
import de.rcm.ballistic.gun.GunAttachments;
import de.rcm.ballistic.network.ModNetworking.GunInputPayload;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Weapon lights and lasers in the world: for every player holding a gun with them switched on, the light
 * throws its beam (a real spotlight, with shadows, through {@link DynamicLights}) and the laser draws its
 * thin line to where it strikes - a bright dot on the wall or on whoever it is aimed at. The key cycles the
 * switch (off, light, laser, both); with sneak it takes them off the gun.
 */
public final class GunLightClient {
	private static KeyMapping key;
	/** Laser lines to draw this frame: from, to, how visible the line itself is. */
	private static final List<double[]> LASERS = new ArrayList<>();

	private GunLightClient() {
	}

	public static void init(KeyMapping.Category category) {
		key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ballisticmissiles.gun_light", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, category));
		ClientTickEvents.END_CLIENT_TICK.register(GunLightClient::tick);
		// before the dynamic lights are drawn (they are registered after this), so the beams light this frame
		WorldRenderEvents.END_MAIN.register(GunLightClient::render);
	}

	private static void tick(Minecraft mc) {
		while (key.consumeClick()) {
			if (mc.player == null || !GunAttachments.isGun(mc.player.getMainHandItem())) {
				continue;
			}
			ClientPlayNetworking.send(new GunInputPayload(mc.player.isShiftKeyDown() ? GunInputPayload.ATTACHMENT_REMOVE : GunInputPayload.ATTACHMENT_TOGGLE));
		}
	}

	private static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		LASERS.clear();
		if (mc.level == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 cam = camera.position();
		for (Player player : mc.level.players()) {
			if (player.isSpectator() || player.distanceToSqr(cam) > 160.0 * 160.0) {
				continue;
			}
			GunAttachments a = GunAttachments.of(player.getMainHandItem());
			if (!GunAttachments.isGun(player.getMainHandItem()) || !(a.lightShining() || a.laserShining())) {
				continue;
			}
			boolean ownView = player == mc.player && mc.options.getCameraType().isFirstPerson()
				&& System.nanoTime() - GunAttachmentModels.viewStamp < 200_000_000L;
			Vec3 look = player.getViewVector(partial);
			Vec3 eye = player.getEyePosition(partial);
			if (a.lightShining()) {
				Vec3 from;
				Vec3 dir;
				if (ownView) {
					from = viewToWorld(mc, camera, GunAttachmentModels.VIEW_LIGHT, true);
					dir = viewToWorld(mc, camera, GunAttachmentModels.VIEW_LIGHT_DIR, false);
				} else {
					from = held(player, eye, look, partial, 0.0);
					dir = look;
				}
				// a little ahead of the lens, so the gun and the hand do not shadow their own beam
				DynamicLights.spot(player.getId() * 8L + 1L, from.add(dir.scale(0.15)), dir, 17.0F, 0xFFF6E8, 1.0F, 34.0F);
			}
			if (a.laserShining()) {
				Vec3 from;
				Vec3 dir;
				if (ownView) {
					from = viewToWorld(mc, camera, GunAttachmentModels.VIEW_LASER, true);
					dir = viewToWorld(mc, camera, GunAttachmentModels.VIEW_LASER_DIR, false);
				} else {
					from = held(player, eye, look, partial, 0.04);
					dir = look;
				}
				// zeroed to the sights: the beam starts at the gun and meets the line of sight
				Vec3 end = strike(mc, player, eye, look);
				Vec3 aim = end.subtract(from);
				if (aim.lengthSqr() < 1.0E-4 || aim.normalize().dot(dir) < 0.9) {
					aim = dir.scale(2.0);
					end = from.add(aim);
				}
				LASERS.add(new double[] {from.x, from.y, from.z, end.x, end.y, end.z});
				DynamicLights.lamp(player.getId() * 8L + 2L, end.subtract(aim.normalize().scale(0.08)), 0xFF2410, 0.3F, 1.6F);
			}
		}
		if (LASERS.isEmpty()) {
			return;
		}
		// the line itself shows faintly in clear air, plainly in smoke, rain and the dark
		float day = 1.0F - Mth.clamp(mc.level.getSkyDarken() / 11.0F, 0.0F, 1.0F);
		float air = Mth.clamp(0.08F + SmokeField.fog() * 1.5F + mc.level.getRainLevel(partial) * 0.3F + (1.0F - day) * 0.2F, 0.0F, 0.8F);
		VertexConsumer consumer = context.consumers().getBuffer(RenderTypes.lightning());
		PoseStack.Pose pose = context.matrices().last();
		for (double[] l : LASERS) {
			Vec3 a = new Vec3(l[0], l[1], l[2]).subtract(cam);
			Vec3 b = new Vec3(l[3], l[4], l[5]).subtract(cam);
			ribbon(consumer, pose, a, b, 0.006F, Math.round(air * 255.0F) << 24 | 0xFF2A18);
			ribbon(consumer, pose, a, b, 0.02F, Math.round(air * 0.35F * 255.0F) << 24 | 0xFF2A18);
			// the dot where it strikes: a hot point and a soft glow round it, sized for distance
			double d = b.length();
			float r = (float) (0.025 + d * 0.0012);
			dot(consumer, pose, camera, b, r, 0xFFFF6040);
			dot(consumer, pose, camera, b, r * 3.0F, 0x70FF2010);
		}
	}

	/** Where the line of sight meets the world, or the player it is aimed at (up to 150 blocks). */
	private static Vec3 strike(Minecraft mc, Player player, Vec3 eye, Vec3 look) {
		Vec3 far = eye.add(look.scale(150.0));
		HitResult block = mc.level.clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		Vec3 end = block.getType() == HitResult.Type.MISS ? far : block.getLocation();
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(mc.level, player, eye, end, player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0),
			e -> !e.isSpectator() && e.isPickable() && e != player, 0.0F);
		return hit != null ? hit.getLocation() : end;
	}

	/** Roughly where a gun held by someone else is: in front of the chest, to the right. */
	private static Vec3 held(Player player, Vec3 eye, Vec3 look, float partial, double side) {
		float yaw = player.getViewYRot(partial) * Mth.DEG_TO_RAD;
		Vec3 right = new Vec3(-Mth.cos(yaw), 0.0, -Mth.sin(yaw));
		return eye.add(look.scale(0.7)).add(right.scale(0.22 - side)).add(0.0, -0.22, 0.0);
	}

	/** A camera-space point (or direction) of the first-person gun, in the world. */
	private static Vec3 viewToWorld(Minecraft mc, Camera camera, Vector3f v, boolean point) {
		Vector3f fwd = new Vector3f(camera.forwardVector());
		Vector3f up = new Vector3f(camera.upVector());
		Vector3f right = new Vector3f(fwd).cross(up).normalize();
		float x = v.x;
		float y = v.y;
		if (point) {
			// the hand is drawn with the plain field of view, the world with zoom and sprint applied
			float base = mc.options.fov().get().floatValue();
			float mod = mc.player == null ? 1.0F : mc.player.getFieldOfViewModifier(true, mc.options.fovEffectScale().get().floatValue());
			float k = (float) (Math.tan(Math.toRadians(base * 0.5)) / Math.tan(Math.toRadians(base * mod * 0.5)));
			x *= k;
			y *= k;
		}
		Vec3 w = new Vec3(right.x * x + up.x * y - fwd.x * v.z, right.y * x + up.y * y - fwd.y * v.z, right.z * x + up.z * y - fwd.z * v.z);
		return point ? camera.position().add(w) : w.normalize();
	}

	/** A thin band from a to b (camera-relative), turned to face the eye. */
	private static void ribbon(VertexConsumer consumer, PoseStack.Pose pose, Vec3 a, Vec3 b, float width, int argb) {
		Vec3 along = b.subtract(a);
		Vec3 mid = a.add(b).scale(0.5);
		Vec3 side = along.cross(mid).normalize().scale(width);
		if (!Double.isFinite(side.x)) {
			return;
		}
		vertex(consumer, pose, a.add(side), argb);
		vertex(consumer, pose, a.subtract(side), argb);
		vertex(consumer, pose, b.subtract(side), argb);
		vertex(consumer, pose, b.add(side), argb);
	}

	private static void dot(VertexConsumer consumer, PoseStack.Pose pose, Camera camera, Vec3 at, float r, int argb) {
		Vector3f up = new Vector3f(camera.upVector()).mul(r);
		Vector3f left = new Vector3f(camera.leftVector()).mul(r);
		vertex(consumer, pose, at.add(left.x + up.x, left.y + up.y, left.z + up.z), argb);
		vertex(consumer, pose, at.add(-left.x + up.x, -left.y + up.y, -left.z + up.z), argb);
		vertex(consumer, pose, at.add(-left.x - up.x, -left.y - up.y, -left.z - up.z), argb);
		vertex(consumer, pose, at.add(left.x - up.x, left.y - up.y, left.z - up.z), argb);
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, Vec3 p, int argb) {
		consumer.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(argb);
	}
}
