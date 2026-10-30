package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.effect.SmokeField;
import de.rcm.ballistic.client.render.GlockItemRenderer;
import de.rcm.ballistic.client.render.ShellCasings;
import de.rcm.ballistic.gun.GlockItem;
import de.rcm.ballistic.gun.GlockMagazineItem;
import de.rcm.ballistic.gun.GunState;
import de.rcm.ballistic.injury.Injuries;
import de.rcm.ballistic.network.ModNetworking.GunInputPayload;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Client side of the Glock 19: the trigger (left click, one shot a pull), the snap of a 9 mm in two hands and
 * the view kicked up a little, the slide flicking back and the case spinning out, the speed reload, the press
 * check and look-over on the inspect key, and the rounds left on the HUD. The poses themselves are
 * {@link GlockMotion}'s.
 */
public final class GlockClient {
	private static float aim;
	private static float prevAim;
	private static float sprint;
	private static float prevSprint;
	private static boolean held;
	private static long equipTick = -100L;
	/** Our own shot and dry click (game ticks), as the client saw them. */
	private static long lastFire = -100L;
	private static long dryTick = -100L;
	private static long inspectStart = -1000L;
	private static long shotSeed;

	private static final GlockMotion MOTION = new GlockMotion();
	private static int pendingKicks;
	private static float kickRoll;
	private static long landTick = -100L;
	private static float landHard;
	private static boolean wasOnGround = true;
	private static double fallFrom;
	private static float walk;
	private static float prevWalk;
	private static float strafe;
	private static float prevStrafe;
	private static long busyTick;
	private static float lastFrame = -1.0F;
	private static float lastYaw;
	private static float lastPitch;
	/** Whether each reload (by its start tick) began with a magazine in the gun, for the drop animation. */
	private static final Map<Long, Boolean> HAD_MAG = new HashMap<>();

	private GlockClient() {
	}

	public static void init() {
		// aiming is "using" the pistol, and while an item is in use Minecraft swallows the left clicks: take them first
		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (mc.player != null && mc.screen == null && holding(mc.player) && mc.player.isUsingItem()) {
				while (mc.options.keyAttack.consumeClick()) {
					trigger(mc.player);
				}
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(GlockClient::tick);
		HudElementRegistry.addLast(BallisticMissiles.id("glock_ammo"), (graphics, tickCounter) -> hud(graphics));
	}

	public static boolean holding(Player player) {
		return player != null && player.getMainHandItem().getItem() instanceof GlockItem;
	}

	/** 0 at the ready, 1 on the sights. */
	public static float aimProgress(float partialTick) {
		float a = Mth.lerp(partialTick, prevAim, aim);
		return a * a * (3.0F - 2.0F * a);
	}

	private static float partial() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	private static float inspectTime(float now) {
		float t = now - inspectStart;
		return t >= 0.0F && t < GlockMotion.INSPECT ? t : -1.0F;
	}

	/** What the gun is doing now (the parts of it any player's gun shows). */
	private static GlockMotion.Frame frame(Minecraft mc, float partialTick, ItemStack stack, boolean local) {
		float now = mc.level.getGameTime() + partialTick;
		GunState s = GlockItem.state(stack);
		float reload = s.reloading() ? now - s.reloadStart() : -1.0F;
		boolean hadMag = true;
		if (s.reloading()) {
			Boolean seen = HAD_MAG.get(s.reloadStart());
			if (seen == null) {
				seen = s.hasMag() || reload >= GlockItem.T_IN;
				if (reload < GlockItem.T_DROP) {
					HAD_MAG.put(s.reloadStart(), seen);
				}
			}
			hadMag = seen;
		}
		long last = local ? Math.max(s.lastShot(), lastFire) : s.lastShot();
		int oldKind = s.ammo();
		LocalPlayer player = mc.player;
		boolean me = local && player != null;
		return new GlockMotion.Frame(now, me ? aimProgress(partialTick) : 0.0F, now - last, GlockItem.slideLocked(s) && !s.reloading(), reload,
			s.reloadKind() == GunState.EMPTY, hadMag, oldKind, s.reloadAmmo(), me ? now - dryTick : 100.0F, me ? inspectTime(now) : -1.0F,
			me ? now - busyTick : 0.0F, me ? now - equipTick : 100.0F, me ? GlockMotion.smooth(Mth.lerp(partialTick, prevSprint, sprint)) : 0.0F,
			me ? player.walkAnimation.position(partialTick) : 0.0F, me ? Mth.lerp(partialTick, prevWalk, walk) : 0.0F,
			me ? Mth.lerp(partialTick, prevStrafe, strafe) : 0.0F, me ? now - landTick : 100.0F, landHard, me ? Fatigue.level() : 0.0F,
			me ? Fatigue.breathPhase(partialTick) : 0.0F, me ? Injuries.get(player).arm() : 0);
	}

	/** Everything that moves on this gun right now, for its model. */
	public static GlockItemRenderer.Pose poseFor(ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return GlockItemRenderer.Pose.REST;
		}
		boolean local = mc.player != null && mc.player.getMainHandItem() == stack;
		GlockMotion.Frame f = frame(mc, partial(), stack, local);
		GunState s = GlockItem.state(stack);
		float r = f.reload();
		// reloading, both magazines go where the clip has them (the old one seated until the thumb drops it)
		boolean magIn = r < 0.0F && s.hasMag();
		int kind = r >= 0.0F ? s.reloadAmmo() : s.ammo();
		float shot = f.shot();
		float eject = shot - 0.3F;
		boolean chambered = s.rounds() > 0 && !(r >= 0.0F && f.empty() && r < GlockItem.T_RACK_FORWARD);
		return new GlockItemRenderer.Pose(GlockMotion.slide(f), magIn, kind, GlockMotion.newMag(f), f.oldKind(), GlockMotion.oldMag(f),
			eject >= 0.0F && eject < 6.0F ? eject : -1.0F, chambered, shot >= 0.0F && shot < 1.2F ? shot : -1.0F,
			local ? shotSeed : (long) s.lastShot());
	}

	// ------------------------------------------------------------------ input

	/** Left click with the pistol in hand. */
	public static void trigger(LocalPlayer player) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || Injuries.get(player).incapacitated()) {
			return;
		}
		long now = mc.level.getGameTime();
		GunState s = GlockItem.state(player.getMainHandItem());
		if (s.reloading() || now - lastFire < GlockItem.CYCLE || now - s.lastShot() < GlockItem.CYCLE) {
			return;
		}
		inspectStart = -1000L;
		ClientPlayNetworking.send(new GunInputPayload(GunInputPayload.GLOCK_FIRE));
		if (s.rounds() <= 0) {
			dryTick = now;
			return;
		}
		lastFire = now;
		shotSeed = ClientEffects.rand() > 0.5F ? now * 7L : now * 13L + 5L;
		Vec3 muzzle = muzzle(player, 1.0F);
		boolean quiet = de.rcm.ballistic.gun.GunAttachments.suppressed(player);
		if (quiet) {
			GunAudio.suppressed(muzzle, true, ModRegistry.SUPPRESSED_PISTOL);
		} else {
			GunAudio.pistol(muzzle, true);
		}
		kickRoll = ClientEffects.rand() * 2.0F - 0.8F;
		pendingKicks++;
		// the muzzle flips and the view a little with it
		boolean aimed = GlockItem.isAiming(player);
		float climb = (1.1F + ClientEffects.rand() * 0.6F) * (aimed ? 0.65F : 1.0F);
		player.setXRot(Mth.clamp(player.getXRot() - climb, -90.0F, 90.0F));
		player.setYRot(player.getYRot() + (ClientEffects.rand() - 0.45F) * 0.5F);
		ClientEffects.addShake(0.08F);
		effects(player, muzzle, player.getLookAngle(), s.rounds() == 1, quiet);
	}

	/** The muzzle, roughly where it is held (world space). */
	static Vec3 muzzle(Player player, float partial) {
		Vec3 look = player.getViewVector(partial);
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		return player.getEyePosition(partial).add(look.scale(0.62)).add(right.scale(0.06)).add(0, -0.1, 0);
	}

	/** Flash light, brass and smoke. */
	private static void effects(Player player, Vec3 muzzle, Vec3 look, boolean last, boolean quiet) {
		if (!quiet) {
			de.rcm.ballistic.client.effect.DynamicLights.flash(muzzle.add(look.scale(0.3)), 0xFFC070, 0.9F, 8.0F, 30.0F + ClientEffects.rand() * 15.0F);
		}
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 port = muzzle.subtract(look.scale(0.12)).add(right.scale(0.03)).add(0, 0.02, 0);
		Vec3 v = right.scale(0.16 + ClientEffects.rand() * 0.06).add(look.scale(-0.02)).add(0, 0.16 + ClientEffects.rand() * 0.05, 0);
		ShellCasings.ejectPistol(port, v.add(player.getDeltaMovement()));
		Vec3 m = muzzle.add(look.scale(0.15));
		SmokeField.puff(m.x, m.y, m.z, look.x * 0.08, look.y * 0.08 + 0.01, look.z * 0.08, 110 + (int) (ClientEffects.rand() * 60), 0.05F, 0.45F,
			0xDAD6D0, 0.16F, 0.5F, 0.0008F);
	}

	/** Someone's pistol went off (our own is heard and seen already). */
	static void remoteShot(de.rcm.ballistic.network.ModNetworking.GunshotPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || (mc.player != null && p.shooter() == mc.player.getId())) {
			return;
		}
		Vec3 muzzle = new Vec3(p.x(), p.y(), p.z());
		Vec3 look = new Vec3(p.dx(), p.dy(), p.dz());
		boolean quiet = (p.flags() & de.rcm.ballistic.network.ModNetworking.GunshotPayload.SUPPRESSED) != 0;
		if (quiet) {
			GunAudio.suppressed(muzzle, false, ModRegistry.SUPPRESSED_PISTOL);
		} else {
			GunAudio.pistol(muzzle, false);
		}
		var shooter = mc.level.getEntity(p.shooter());
		if (shooter instanceof Player other && muzzle.distanceTo(mc.gameRenderer.getMainCamera().position()) < 96.0) {
			effects(other, muzzle, look, (p.flags() & de.rcm.ballistic.network.ModNetworking.GunshotPayload.LAST) != 0, quiet);
		} else if (!quiet) {
			de.rcm.ballistic.client.effect.DynamicLights.flash(muzzle.add(look.scale(0.3)), 0xFFC070, 0.9F, 8.0F, 30.0F);
		}
	}

	/** R with the pistol in hand (sneaking: the other kind of magazine). */
	static void reload(LocalPlayer player) {
		inspectStart = -1000L;
		ClientPlayNetworking.send(new GunInputPayload(player.isShiftKeyDown() ? GunInputPayload.GLOCK_RELOAD_SWITCH : GunInputPayload.GLOCK_RELOAD));
	}

	/** The inspect key: look the pistol over, with a press check in the middle. */
	static void inspect(LocalPlayer player) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		long now = mc.level.getGameTime();
		GunState s = GlockItem.state(player.getMainHandItem());
		if (s.reloading() || aim > 0.0F || inspectTime(now) >= 0.0F || GlockItem.slideLocked(s)) {
			return;
		}
		inspectStart = now;
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		prevAim = aim;
		prevSprint = sprint;
		if (player == null || mc.level == null) {
			held = false;
			HAD_MAG.clear();
			return;
		}
		if (HAD_MAG.size() > 32) {
			HAD_MAG.clear();
		}
		long now = mc.level.getGameTime();
		boolean has = holding(player);
		if (has && !held) {
			equipTick = now;
			inspectStart = -1000L;
		}
		held = has;
		GunState s = has ? GlockItem.state(player.getMainHandItem()) : null;
		boolean aiming = s != null && GlockItem.isAiming(player) && !s.reloading();
		aim = aiming ? Math.min(1.0F, aim + 0.25F) : Math.max(0.0F, aim - 0.25F);
		// anything else done with the pistol breaks off looking it over
		if (inspectTime(now) >= 0.0F && (aiming || s == null || s.reloading() || player.isSprinting())) {
			inspectStart = -1000L;
		}
		boolean busy = s != null && (s.reloading() || now - s.lastShot() < 8);
		float target = has && player.isSprinting() && !busy && !aiming ? 1.0F : 0.0F;
		sprint += (target - sprint) * 0.25F;
		if (has) {
			if (!player.onGround()) {
				if (wasOnGround) {
					fallFrom = player.getY();
				}
				fallFrom = Math.max(fallFrom, player.getY());
			} else if (!wasOnGround) {
				landTick = now;
				landHard = (float) Mth.clamp((fallFrom - player.getY()) / 3.0, 0.3, 1.0);
			}
			wasOnGround = player.onGround();
			prevWalk = walk;
			prevStrafe = strafe;
			var vel = player.getDeltaMovement();
			float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
			double side = vel.x * Math.cos(yawRad) + vel.z * Math.sin(yawRad);
			strafe += ((float) Mth.clamp(side / 0.13, -1.0, 1.0) - strafe) * 0.18F;
			walk += ((player.onGround() ? (float) Mth.clamp(vel.horizontalDistance() / 0.12, 0.0, 1.0) : 0.0F) - walk) * 0.2F;
			if (busy || aiming || walk > 0.1F || mc.options.keyAttack.isDown() || now - equipTick < 20 || inspectTime(now) >= 0.0F) {
				busyTick = now;
			}
			// the inspect's own sounds: the press check
			float i = inspectTime(now);
			if (i == (int) GlockMotion.PRESS_BACK) {
				GunAudio.play(ModRegistry.GLOCK_SLIDE_BACK, player.getEyePosition(), 0.35F, 1.1F);
			} else if (i == (int) GlockMotion.PRESS_HOME) {
				GunAudio.play(ModRegistry.GLOCK_SLIDE_FORWARD, player.getEyePosition(), 0.4F, 1.1F);
			} else if (i == 2 || i == 40) {
				GunAudio.play(ModRegistry.GEAR_RUSTLE, player.getEyePosition(), 0.3F, 1.15F);
			}
			if (i == 30) {
				mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.translatable(s.rounds() > 0 ? "message.ballisticmissiles.ak_chamber_loaded"
					: "message.ballisticmissiles.ak_chamber_empty"), false);
			}
		}
		if (has && now == equipTick) {
			GunAudio.play(ModRegistry.GEAR_RUSTLE, player.getEyePosition(), 0.4F, 1.1F + ClientEffects.rand() * 0.1F);
		}
	}

	// ------------------------------------------------------------------ first person

	private static void physics(Minecraft mc, float now, float partialTick) {
		var player = mc.player;
		float yaw = player.getViewYRot(partialTick);
		float pitch = player.getViewXRot(partialTick);
		float dt = now - lastFrame;
		if (lastFrame < 0.0F || dt < 0.0F || dt > 2.0F) {
			dt = 0.0F;
			lastYaw = yaw;
			lastPitch = pitch;
		}
		lastFrame = now;
		float aiming = aimProgress(partialTick);
		while (pendingKicks > 0) {
			pendingKicks--;
			MOTION.kick(1.0F - 0.35F * aiming - (player.isCrouching() ? 0.1F : 0.0F), kickRoll, ClientEffects.rand());
		}
		float dy = Mth.wrapDegrees(yaw - lastYaw);
		float dp = pitch - lastPitch;
		lastYaw = yaw;
		lastPitch = pitch;
		MOTION.step(dt, dy, dp, aiming);
	}

	public static void transform(PoseStack poseStack, float partialTick, ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		physics(mc, mc.level.getGameTime() + partialTick, partialTick);
		MOTION.apply(poseStack, frame(mc, partialTick, stack, true));
	}

	/** The two hands on the pistol (and the support hand off it to fetch magazines and rack the slide). */
	public static void renderArms(AbstractClientPlayer player, ItemStack stack, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light,
		float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		GlockMotion.Hands hands = GlockMotion.hands(frame(mc, partialTick, stack, true));
		poseStack.pushPose();
		poseStack.translate(-3.2F / 16.0F, 2.4F / 16.0F, 0.5F / 16.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(3.0F));
		AkArms.draw(player, base, poseStack, collector, light, hands.right(), AttachAnim.supportHand(partialTick, stack, hands.left()), hands.rightShoulder(),
			hands.leftShoulder(), hands.rightPole(),
			hands.leftPole(), GlockMotion.ARM_THICK, GlockMotion.FOREARM);
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ ammo

	private static void hud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (!holding(player) || mc.options.hideGui) {
			return;
		}
		GunState s = GlockItem.state(player.getMainHandItem());
		int mags = 0;
		int ext = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack st = player.getInventory().getItem(i);
			if (st.getItem() instanceof GlockMagazineItem m && GlockMagazineItem.rounds(st) > 0) {
				if (m.kind() == GlockMagazineItem.EXTENDED) {
					ext += st.getCount();
				} else {
					mags += st.getCount();
				}
			}
		}
		int cap = GlockMagazineItem.capacity(s.ammo());
		String rounds = s.hasMag() ? String.valueOf(s.rounds()) : s.rounds() > 0 ? s.rounds() + " (K)" : "--";
		String line1 = rounds + " / " + cap + "+1";
		String line2 = "9×19  " + (s.ammo() == GlockMagazineItem.EXTENDED ? "EXT" : "STD") + "   " + mags + (ext > 0 ? " + " + ext + " EXT" : "");
		int w = g.guiWidth();
		int h = g.guiHeight();
		int color = s.rounds() == 0 ? 0xFFFF5040 : s.rounds() <= 3 ? 0xFFFFC040 : 0xFFF0F0F0;
		g.drawString(mc.font, line1, w - 8 - mc.font.width(line1), h - 34, color, true);
		g.drawString(mc.font, line2, w - 8 - mc.font.width(line2), h - 22, 0xFFB8B8B8, true);
		if (s.reloading()) {
			String r = "Nachladen...";
			g.drawString(mc.font, r, w - 8 - mc.font.width(r), h - 46, 0xFFFFE080, true);
		} else if (GlockItem.slideLocked(s)) {
			String r = "Verschluss offen – R";
			g.drawString(mc.font, r, w - 8 - mc.font.width(r), h - 46, 0xFFFF8060, true);
		}
	}
}
