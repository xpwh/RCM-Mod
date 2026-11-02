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
import org.joml.Quaternionf;
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
	private static KeyMapping mountKey;
	/** Laser lines to draw this frame: from, to, infrared (1) or not. */
	private static final List<double[]> LASERS = new ArrayList<>();

	private GunLightClient() {
	}

	public static void init(KeyMapping.Category category) {
		key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ballisticmissiles.gun_light", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, category));
		mountKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ballisticmissiles.gun_mount", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, category));
		ClientTickEvents.END_CLIENT_TICK.register(GunLightClient::tick);
		ClientTickEvents.END_CLIENT_TICK.register(AttachAnim::tick);
		// P mounts a light or laser on the gun in hand; vanilla also has P (social interactions): with a gun
		// in hand ours wins, before vanilla handles its keys this tick
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			while (mountKey.consumeClick()) {
				if (mc.player != null && mc.screen == null && GunAttachments.isGun(mc.player.getMainHandItem())) {
					while (mc.options.keySocialInteractions.consumeClick()) {
						// swallowed
					}
					AttachAnim.request(mc);
				}
			}
		});
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
					from = viewToWorld(mc, camera, GunAttachmentModels.VIEW_LIGHT);
					dir = viewToWorld(mc, camera, new Vector3f(GunAttachmentModels.VIEW_LIGHT_DIR).mul(0.4F).add(GunAttachmentModels.VIEW_LIGHT)).subtract(from).normalize();
				} else {
					from = held(player, eye, look, partial, 0.0);
					dir = look;
				}
				// a little ahead of the lens, so the gun and the hand do not shadow their own beam; the cold white
				// of an LED, a tight hot spot in a wide dim spill, thrown far
				DynamicLights.spot(player.getId() * 8L + 1L, from.add(dir.scale(0.15)), dir, 26.0F, 0xEEF3FF, 1.0F, 56.0F);
				// light thrown back off whatever the beam lands on near by: a wall a few steps away lights the
				// room round you softly (the bounce is what lets you see beside the beam in a dark room)
				Vec3 far = from.add(dir.scale(24.0));
				HitResult wall = mc.level.clip(new ClipContext(from, far, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
				if (wall.getType() != HitResult.Type.MISS) {
					double d = wall.getLocation().distanceTo(from);
					float bounce = (float) (0.5 / (1.0 + d * d * 0.06));
					if (bounce > 0.03F) {
						DynamicLights.lamp(player.getId() * 8L + 3L, wall.getLocation().subtract(dir.scale(Math.min(0.8, d * 0.3))), 0xE4E8F0, bounce,
							(float) (3.0 + d * 0.5));
					}
				}
			}
			// an infrared laser is seen only through night vision (and lights nothing the eye can see)
			boolean irSeen = !a.ir() || de.rcm.ballistic.client.effect.Vision.seesInfrared();
			if (a.laserShining() && irSeen) {
				Vec3 from;
				Vec3 dir;
				if (ownView) {
					from = viewToWorld(mc, camera, GunAttachmentModels.VIEW_LASER);
					dir = viewToWorld(mc, camera, new Vector3f(GunAttachmentModels.VIEW_LASER_DIR).mul(0.4F).add(GunAttachmentModels.VIEW_LASER)).subtract(from).normalize();
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
				// the dot sits a hair off the surface, towards the gun, so it is not swallowed by it
				end = end.subtract(aim.normalize().scale(0.012));
				LASERS.add(new double[] {from.x, from.y, from.z, end.x, end.y, end.z, a.ir() ? 1.0 : 0.0, ownView ? 1.0 : 0.0});
				if (!a.ir()) {
					DynamicLights.lamp(player.getId() * 8L + 2L, end.subtract(aim.normalize().scale(0.08)), 0xFF2410, 0.3F, 1.6F);
				} else {
					// through the goggles the spot lights up what it lands on (only this viewer sees it at all)
					DynamicLights.lamp(player.getId() * 8L + 2L, end.subtract(aim.normalize().scale(0.1)), 0xE8FFE8, 0.7F, 2.6F);
				}
			}
		}
		if (LASERS.isEmpty()) {
			return;
		}
		// the line itself shows faintly in clear air, plainly in smoke, rain and the dark
		float day = 1.0F - Mth.clamp(mc.level.getSkyDarken() / 11.0F, 0.0F, 1.0F);
		float airBase = Mth.clamp(0.08F + SmokeField.fog() * 1.5F + mc.level.getRainLevel(partial) * 0.3F + (1.0F - day) * 0.2F, 0.0F, 0.8F);
		VertexConsumer consumer = context.consumers().getBuffer(RenderTypes.lightning());
		PoseStack.Pose pose = context.matrices().last();
		for (double[] l : LASERS) {
			Vec3 a = new Vec3(l[0], l[1], l[2]).subtract(cam);
			Vec3 b = new Vec3(l[3], l[4], l[5]).subtract(cam);
			double d = b.length();
			float shimmer = 0.82F + 0.18F * (float) Math.random();
			if (l[6] > 0.5) {
				infrared(consumer, pose, camera, a, b, shimmer, l[7] > 0.5);
				continue;
			}
			// a 650 nm beam, a few millimetres across: a thin bright thread where the air scatters it, a fainter
			// haze round it in smoke
			ribbon(consumer, pose, a, b, 0.0025F, 0.0025F, Math.round(airBase * 255.0F) << 24 | 0xFF2A18);
			ribbon(consumer, pose, a, b, 0.009F, 0.009F, Math.round(airBase * 0.3F * 255.0F) << 24 | 0xFF2010);
			// the dot where it strikes: tiny and blinding at its heart (the beam spreads about a millimetre per
			// metre), with the glare of it round it - bigger in the dark - and the shimmer of laser speckle
			float r = (float) (0.004 + d * 0.0006);
			float glare = 0.35F + 0.65F * (1.0F - day);
			dot(consumer, pose, camera, b, r, Math.round(255.0F * shimmer) << 24 | 0xFFE0D8);
			dot(consumer, pose, camera, b, r * 2.2F, Math.round(220.0F * shimmer) << 24 | 0xFF3020);
			dot(consumer, pose, camera, b, (float) (r * 6.0 + 0.01 * glare), Math.round(70.0F * glare * shimmer) << 24 | 0xFF1808);
		}
	}

	/**
	 * An infrared laser as night vision shows it - the way a PEQ beam looks through the tubes: not a faint
	 * thread but a solid bar of light from the gun to the target, brightest at the muzzle and fading with
	 * distance, with the tube's bloom round it; the emitter itself a glaring star, and where it lands a big
	 * blooming spot that lights up the patch it hits. It keeps the same width on screen however far it reaches,
	 * because the intensifier smears every bright point over a few pixels.
	 */
	private static void infrared(VertexConsumer consumer, PoseStack.Pose pose, Camera camera, Vec3 a, Vec3 b, float shimmer, boolean own) {
		double da = a.length();
		double db = b.length();
		// a few segments, so the beam can fade along its length
		int n = 12;
		for (int i = 0; i < n; i++) {
			double t0 = i / (double) n;
			double t1 = (i + 1) / (double) n;
			// the fade is by distance from the emitter along the beam: most light is scattered near the muzzle
			t0 = t0 * t0;
			t1 = t1 * t1;
			Vec3 p0 = a.lerp(b, t0);
			Vec3 p1 = a.lerp(b, t1);
			double len = b.subtract(a).length();
			float f0 = (float) (1.0 / (1.0 + len * t0 * 0.02));
			float f1 = (float) (1.0 / (1.0 + len * t1 * 0.02));
			float f = (f0 + f1) * 0.5F;
			float w0 = (float) p0.length();
			float w1 = (float) p1.length();
			// core: about two pixels across, near white; bloom: a soft halo several times wider
			ribbon(consumer, pose, p0, p1, 0.004F + w0 * 0.0016F, 0.004F + w1 * 0.0016F, Math.round(Math.min(1.0F, 0.95F * f) * 255.0F) << 24 | 0xF4FFF0);
			ribbon(consumer, pose, p0, p1, 0.012F + w0 * 0.0045F, 0.012F + w1 * 0.0045F, Math.round(0.45F * f * 255.0F) << 24 | 0xC8FFD0);
			ribbon(consumer, pose, p0, p1, 0.03F + w0 * 0.012F, 0.03F + w1 * 0.012F, Math.round(0.16F * f * 255.0F) << 24 | 0x90FFA0);
		}
		// the emitter: a glaring point at the gun (blinding to anyone it points at)
		float e = (float) (0.02 + da * 0.006);
		if (!own) {
			dot(consumer, pose, camera, a, e, Math.round(255.0F * shimmer) << 24 | 0xFFFFFF);
			dot(consumer, pose, camera, a, e * 3.0F, Math.round(110.0F * shimmer) << 24 | 0xD0FFD8);
		}
		// the spot: a hard bright centre and a wide bloom that washes over the surface
		float r = (float) (0.02 + db * 0.004);
		dot(consumer, pose, camera, b, r, Math.round(255.0F * shimmer) << 24 | 0xFFFFFF);
		dot(consumer, pose, camera, b, r * 2.5F, Math.round(200.0F * shimmer) << 24 | 0xE0FFE4);
		dot(consumer, pose, camera, b, r * 7.0F, Math.round(70.0F * shimmer) << 24 | 0xA0FFB0);
		dot(consumer, pose, camera, b, r * 16.0F, Math.round(22.0F * shimmer) << 24 | 0x80FF90);
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

	/**
	 * A point of the first-person gun, in the world. The hand is drawn with the camera's turn already taken
	 * out (so its positions are camera-relative but lined up with the world's axes) and with a fixed 70 degree
	 * field of view, the world with the player's own (zoom and sprint included): the point is moved out from
	 * the centre of view by the difference, so it lands where the gun is seen.
	 */
	private static Vec3 viewToWorld(Minecraft mc, Camera camera, Vector3f v) {
		Vector3f local = camera.rotation().conjugate(new Quaternionf()).transform(new Vector3f(v));
		float fov = mc.options.fov().get().floatValue();
		if (mc.player != null) {
			fov *= mc.player.getFieldOfViewModifier(true, mc.options.fovEffectScale().get().floatValue());
		}
		float k = (float) (Math.tan(Math.toRadians(fov * 0.5)) / Math.tan(Math.toRadians(35.0)));
		local.x *= k;
		local.y *= k;
		Vector3f w = camera.rotation().transform(local);
		return camera.position().add(w.x, w.y, w.z);
	}

	/** A thin band from a to b (camera-relative), turned to face the eye. */
	private static void ribbon(VertexConsumer consumer, PoseStack.Pose pose, Vec3 a, Vec3 b, float wa, float wb, int argb) {
		Vec3 along = b.subtract(a);
		Vec3 mid = a.add(b).scale(0.5);
		Vec3 side = along.cross(mid).normalize();
		if (!Double.isFinite(side.x)) {
			return;
		}
		vertex(consumer, pose, a.add(side.scale(wa)), argb);
		vertex(consumer, pose, a.subtract(side.scale(wa)), argb);
		vertex(consumer, pose, b.subtract(side.scale(wb)), argb);
		vertex(consumer, pose, b.add(side.scale(wb)), argb);
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
