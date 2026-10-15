package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.render.GunAttachmentModels;
import de.rcm.ballistic.gun.GunAttachments;
import de.rcm.ballistic.network.ModNetworking.GunInputPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

/**
 * Mounting a light or a laser on the gun in your hands (key P), in first person: the gun is turned in so the
 * rail faces you, the support hand leaves it and goes down to the pouch, comes back up with the light (or
 * laser), offers it up to the rail ahead of its place, slides it back along the rail until it clicks home,
 * the thumb tightens the clamp screw, and the hand goes back to the gun as it comes back on target. The
 * server mounts it at the click ({@link #T_CLICK}).
 */
public final class AttachAnim {
	public static final int TICKS = 46;
	/** Hand at the pouch; the device in hand; offered up to the rail; slid home with a click; screw tightened. */
	private static final float T_POUCH = 8.0F;
	private static final float T_GRAB = 12.0F;
	private static final float T_OFFER = 22.0F;
	private static final float T_ON_RAIL = 26.0F;
	public static final int T_CLICK = 31;
	private static final float T_LOCK = 36.0F;
	/** How far ahead of its place the device meets the rail, and how far out from the gun it is offered. */
	private static final float SLIDE = 0.075F;
	private static final float OUT = 0.05F;

	private static float start = -1000.0F;
	private static boolean light;
	private static Item gun;
	private static int lastSound = -1;

	private AttachAnim() {
	}

	/** Key P: the first thing the gun is missing that is in the inventory goes on. */
	static void request(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		ItemStack stack = mc.player.getMainHandItem();
		if (!GunAttachments.isGun(stack) || active(mc.level.getGameTime())) {
			return;
		}
		GunAttachments a = GunAttachments.of(stack);
		boolean haveLight = mc.player.getInventory().contains(new ItemStack(ModRegistry.WEAPON_LIGHT));
		boolean haveLaser = mc.player.getInventory().contains(new ItemStack(ModRegistry.LASER_MODULE));
		Boolean which = !a.light() && haveLight ? Boolean.TRUE : !a.laser() && haveLaser ? Boolean.FALSE : null;
		if (which == null) {
			String key = a.light() && a.laser() ? "message.ballisticmissiles.attachment_full" : "message.ballisticmissiles.attachment_none_in_inventory";
			mc.gui.setOverlayMessage(Component.translatable(key), false);
			return;
		}
		light = which;
		gun = stack.getItem();
		start = mc.level.getGameTime();
		lastSound = -1;
		ClientPlayNetworking.send(new GunInputPayload(light ? GunInputPayload.ATTACH_LIGHT : GunInputPayload.ATTACH_LASER));
	}

	private static boolean active(float now) {
		return now - start >= 0.0F && now - start < TICKS;
	}

	/** Ticks into mounting a device on this gun (in first person), or -1. */
	public static float time(float now, ItemStack stack) {
		float t = now - start;
		return t >= 0.0F && t < TICKS && stack.getItem() == gun ? t : -1.0F;
	}

	public static boolean light() {
		return light;
	}

	/** The local player's sounds of it, tick by tick. */
	static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return;
		}
		int t = (int) (mc.level.getGameTime() - start);
		if (t < 0 || t >= TICKS || t == lastSound) {
			return;
		}
		lastSound = t;
		var at = mc.player.getEyePosition();
		if (t == 2 || t == (int) T_POUCH) {
			GunAudio.play(ModRegistry.GEAR_RUSTLE, at, 0.45F, t == 2 ? 1.0F : 1.2F); // the pouch flap
		} else if (t == (int) T_ON_RAIL) {
			GunAudio.play(ModRegistry.AK_MAG_IN, at, 0.25F, 1.9F); // the clamp onto the rail
		} else if (t == (int) T_ON_RAIL + 3) {
			GunAudio.play(ModRegistry.GEAR_RUSTLE, at, 0.25F, 1.8F); // sliding back along it
		} else if (t == (int) T_LOCK - 2 || t == (int) T_LOCK) {
			GunAudio.play(ModRegistry.AK_SELECTOR, at, 0.35F, t == (int) T_LOCK ? 1.7F : 1.5F); // the clamp screw, two turns
		}
	}

	// ------------------------------------------------------------------ the motion

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	/** How far the gun is turned in for it (0 on target .. 1 rail towards you). */
	private static float presented(float t) {
		return smooth(t / 7.0F) * (1.0F - smooth((t - T_LOCK) / (TICKS - T_LOCK - 1.0F)));
	}

	/**
	 * The gun turned in to show the rail the device goes on: its right side (lifted and turned across), its
	 * left side (rolled over), or its underside (a pistol rolled onto its side). {@code k} scales the
	 * movement (a pistol moves less). Applied after the gun's own first-person motion.
	 */
	public static void gun(PoseStack poseStack, float t, ItemStack stack) {
		GunAttachmentModels.Mount m = mount(stack);
		if (m != null) {
			gun(poseStack, t, m, stack.is(ModRegistry.GLOCK));
		}
	}

	/** The same for a gun with these mounts ({@code pistol}: small, the device goes underneath). */
	public static void gun(PoseStack poseStack, float t, GunAttachmentModels.Mount m, boolean pistol) {
		float c = presented(t);
		if (c <= 0.0F) {
			return;
		}
		float k = pistol ? 0.6F : 1.0F;
		float side = side(m, pistol);
		// the knock of the device clicking home, and the hand's push on the screw
		float knock = t > T_CLICK && t < T_CLICK + 4 ? Mth.sin((t - T_CLICK) / 4.0F * Mth.PI) : 0.0F;
		float screw = t > T_LOCK - 3 && t < T_LOCK + 1 ? Mth.sin((t - (T_LOCK - 3)) / 4.0F * Mth.PI) : 0.0F;
		if (side > 0.0F) {
			// rolled over to the left, so its right side comes up into view
			poseStack.translate(-0.16F * c * k, (0.2F * c + 0.006F * knock) * k, 0.08F * c * k);
			poseStack.mulPose(Axis.ZP.rotationDegrees(45.0F * c + 2.0F * screw));
			poseStack.mulPose(Axis.XP.rotationDegrees(10.0F * c - 1.5F * knock));
			poseStack.mulPose(Axis.YP.rotationDegrees(25.0F * c));
		} else if (side < 0.0F) {
			// turned in towards you, so its left side faces you (as for a magazine check)
			poseStack.translate(-0.14F * c * k, (0.17F * c + 0.006F * knock) * k, 0.06F * c * k);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-15.0F * c - 2.0F * screw));
			poseStack.mulPose(Axis.XP.rotationDegrees(8.0F * c - 1.5F * knock));
			poseStack.mulPose(Axis.YP.rotationDegrees(38.0F * c));
		} else {
			// muzzle tipped up and turned in, so the rail under the dust cover faces you
			poseStack.translate(-0.06F * c * k, (0.02F * c + 0.006F * knock) * k, 0.02F * c * k);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-18.0F * c - 2.0F * screw));
			poseStack.mulPose(Axis.XP.rotationDegrees(34.0F * c - 1.5F * knock));
			poseStack.mulPose(Axis.YP.rotationDegrees(22.0F * c));
		}
	}

	/** The gun's part of it, if the local player is mounting something on it now. */
	public static void applyGun(PoseStack poseStack, float partialTick, ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		float t = time(mc.level.getGameTime() + partialTick, stack);
		if (t >= 0.0F) {
			gun(poseStack, t, stack);
		}
	}

	/** The support hand during it, or {@code rest} when not mounting anything. */
	public static Vector3f supportHand(float partialTick, ItemStack stack, Vector3f rest) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return rest;
		}
		float t = time(mc.level.getGameTime() + partialTick, stack);
		GunAttachmentModels.Mount m = mount(stack);
		return t >= 0.0F && m != null ? hand(t, m, stack.is(ModRegistry.GLOCK), rest, pouch(stack)) : rest;
	}

	/** Which side of the gun the device goes on: +1 right, -1 left, 0 underneath. */
	private static float side(GunAttachmentModels.Mount m, boolean pistol) {
		if (pistol) {
			return 0.0F;
		}
		return Math.signum(light ? m.lx() : m.zx());
	}

	public static GunAttachmentModels.Mount mount(ItemStack stack) {
		return stack.is(ModRegistry.AK47) ? GunAttachmentModels.AK : stack.is(ModRegistry.GLOCK) ? GunAttachmentModels.GLOCK
			: stack.is(ModRegistry.SHOTGUN) ? GunAttachmentModels.SHOTGUN : null;
	}

	private static Vector3f place(GunAttachmentModels.Mount m) {
		return light ? new Vector3f(m.lx(), m.ly(), m.lz()) : new Vector3f(m.zx(), m.zy(), m.zz());
	}

	/** Which way is "out" from the rail the device goes on. */
	private static Vector3f outward(GunAttachmentModels.Mount m, boolean pistol) {
		float side = side(m, pistol);
		return side == 0.0F ? new Vector3f(0.0F, -1.0F, 0.0F) : new Vector3f(side, 0.0F, 0.0F);
	}

	/**
	 * Where the device is in the gun's space while it is carried (null: still in the pouch): up from the
	 * pouch, offered up out from the rail and ahead of its place, onto the rail, slid back home.
	 * {@code tilt} gets how far it is still turned from lying true on the rail (degrees).
	 */
	public static Vector3f carried(float t, GunAttachmentModels.Mount m, boolean pistol, Vector3f pouch, float[] tilt) {
		if (t < T_GRAB) {
			return null;
		}
		Vector3f home = place(m);
		Vector3f out = outward(m, pistol);
		float k = pistol ? 0.55F : 1.0F;
		Vector3f onRail = new Vector3f(home).add(0.0F, 0.0F, -SLIDE * k);
		Vector3f offered = new Vector3f(onRail).add(new Vector3f(out).mul(OUT * k));
		if (t < T_OFFER) {
			float f = smooth((t - T_GRAB) / (T_OFFER - T_GRAB));
			tilt[0] = 55.0F * (1.0F - f);
			return swing(pouch, offered, f);
		}
		tilt[0] = 0.0F;
		if (t < T_ON_RAIL) {
			return lerp(offered, onRail, smooth((t - T_OFFER) / (T_ON_RAIL - T_OFFER)));
		}
		if (t < T_CLICK) {
			return lerp(onRail, home, smooth((t - T_ON_RAIL) / (T_CLICK - T_ON_RAIL)));
		}
		return home;
	}

	/** The support hand: to the pouch, up with the device, on it as it goes on, the thumb on the screw, back. */
	public static Vector3f hand(float t, GunAttachmentModels.Mount m, boolean pistol, Vector3f rest, Vector3f pouch) {
		Vector3f out = outward(m, pistol);
		float k = pistol ? 0.55F : 1.0F;
		// the hand holds the device from below and behind, so it stays in view
		Vector3f grip = new Vector3f(out).mul(0.012F * k).add(0.0F, -0.03F * k, 0.035F * k);
		if (t < T_POUCH) {
			return swing(rest, pouch, smooth(t / T_POUCH));
		}
		if (t < T_GRAB) {
			return new Vector3f(pouch).add(0.0F, 0.012F * Mth.sin((t - T_POUCH) / (T_GRAB - T_POUCH) * Mth.PI), 0.0F);
		}
		float[] tilt = new float[1];
		Vector3f dev = carried(t, m, pistol, pouch, tilt);
		if (t < T_CLICK) {
			return new Vector3f(dev).add(grip);
		}
		Vector3f home = place(m).add(grip);
		if (t < T_LOCK) {
			// the thumb on the clamp screw, turning it
			float turn = Mth.sin((t - T_CLICK) / (T_LOCK - T_CLICK) * Mth.PI * 2.0F);
			return home.add(0.0F, 0.004F * turn * k, 0.0F);
		}
		return swing(home, rest, smooth((t - T_LOCK) / (TICKS - T_LOCK - 2.0F)));
	}

	/** Where the support hand finds the device: the pouch it carries magazines in for that gun. */
	public static Vector3f pouch(ItemStack stack) {
		if (stack.is(ModRegistry.GLOCK)) {
			return new Vector3f(GlockMotion.POUCH);
		}
		if (stack.is(ModRegistry.SHOTGUN)) {
			return new Vector3f(-0.3F, -0.42F, 0.3F);
		}
		return new Vector3f(AkAnim.POUCH);
	}

	/**
	 * Draws the device in the support hand (first person, in the gun's model space on {@code poseStack}),
	 * while the local player is mounting one on {@code stack}.
	 */
	public static void submitCarried(PoseStack poseStack, net.minecraft.client.renderer.SubmitNodeCollector collector, int lightCoords) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		ItemStack stack = mc.player.getMainHandItem();
		float now = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		float t = time(now, stack);
		if (t < 0.0F) {
			return;
		}
		GunAttachmentModels.Mount m = mount(stack);
		if (m == null) {
			return;
		}
		boolean pistol = stack.is(ModRegistry.GLOCK);
		float[] tilt = new float[1];
		Vector3f at = carried(t, m, pistol, pouch(stack), tilt);
		if (at == null) {
			return;
		}
		GunAttachmentModels.submitDevice(light, pistol, at, tilt[0] * (side(m, pistol) < 0.0F ? -1.0F : 1.0F), poseStack, collector, lightCoords);
	}

	private static Vector3f lerp(Vector3f a, Vector3f b, float f) {
		return new Vector3f(a).lerp(b, f);
	}

	/** From a to b in an arc that dips on the way (a hand swinging, not sliding). */
	private static Vector3f swing(Vector3f a, Vector3f b, float f) {
		return new Vector3f(a).lerp(b, f).add(0.0F, -0.05F * Mth.sin(f * Mth.PI), 0.02F * Mth.sin(f * Mth.PI));
	}
}
