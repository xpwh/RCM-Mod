package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.effect.SmokeField;
import de.rcm.ballistic.client.render.ShotgunItemRenderer;
import de.rcm.ballistic.gun.GunState;
import de.rcm.ballistic.gun.ShotgunItem;
import de.rcm.ballistic.injury.Injuries;
import de.rcm.ballistic.network.ModNetworking.GunInputPayload;
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
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Client side of the pump shotgun: the trigger (left click), the heavy kick of a 12-gauge into the
 * shoulder and the muzzle flipping up, the slide racked back and forward by the left hand after every
 * shot, bringing the bead to the eye, and the loading - the gun rolled over so its loading port faces
 * you, the left hand going down to the pouch for a shell and thumbing it up into the tube, one after
 * another, then for an empty gun the slide racked to chamber the first.
 */
public final class ShotgunClient {
	/** Line of sight (item space): the groove on top of the receiver to the brass bead at the muzzle. */
	private static final Vector3f NOTCH = new Vector3f(0.0F, ShotgunItemRenderer.SIGHT_REAR_Y, 0.05F);
	private static final Vector3f SIGHT_LINE = new Vector3f(0.0F, ShotgunItemRenderer.BEAD_Y - ShotgunItemRenderer.SIGHT_REAR_Y,
		ShotgunItemRenderer.MUZZLE_Z + 0.014F - 0.05F).normalize();
	/** Cheek down on the comb, the eye a hand's width behind the receiver. */
	private static final Vector3f EYE = new Vector3f(NOTCH).sub(new Vector3f(SIGHT_LINE).mul(0.2F));
	private static final Vector3f CAMERA = new Vector3f(-0.56F, 0.52F, 0.72F);

	/** The hands (item space): right fist round the wrist of the stock, left under the forend. */
	private static final Vector3f GRIP = new Vector3f(0.0F, 0.065F, 0.115F);
	private static final Vector3f FOREND = new Vector3f(-0.004F, 0.035F, -0.415F);
	/** Fingers under the loading port, a shell pushed up and in; the pouch on the belt (gun rolled over). */
	private static final Vector3f PORT = new Vector3f(-0.002F, 0.05F, -0.075F);
	private static final Vector3f APPROACH = new Vector3f(-0.01F, 0.0F, 0.0F);
	private static final Vector3f POUCH = new Vector3f(-0.16F, -0.3F, 0.16F);

	private static float aim;
	private static float prevAim;
	private static float load;
	private static float prevLoad;
	private static float sprint;
	private static float prevSprint;
	private static boolean held;
	private static long equipTick = -100L;
	/** Our own shot and dry click (game ticks), as the client saw them. */
	private static long lastFire = -100L;
	private static long dryTick = -100L;

	private static final class Spring {
		final float stiffness;
		final float damping;
		float x;
		float v;

		Spring(float frequency, float ratio) {
			this.stiffness = frequency * frequency;
			this.damping = 2.0F * ratio * frequency;
		}

		void step(float dt) {
			int n = Math.max(1, (int) Math.ceil(dt / 0.1F));
			float h = dt / n;
			for (int i = 0; i < n; i++) {
				this.v += (-this.stiffness * this.x - this.damping * this.v) * h;
				this.x += this.v * h;
			}
		}
	}

	// a 12-gauge kicks hard: back into the shoulder, the muzzle flipping high, a roll
	private static final Spring BACK = new Spring(1.0F, 0.55F);
	private static final Spring PITCH = new Spring(0.85F, 0.5F);
	private static final Spring ROLL = new Spring(0.9F, 0.45F);
	private static final Spring LAG_YAW = new Spring(0.7F, 0.55F);
	private static final Spring LAG_PITCH = new Spring(0.75F, 0.6F);
	private static int pendingKicks;
	private static float kickRoll;
	private static float lastFrame = -1.0F;
	private static float lastYaw;
	private static float lastPitch;

	private ShotgunClient() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(ShotgunClient::tick);
		HudElementRegistry.addLast(BallisticMissiles.id("shotgun_ammo"), (graphics, tickCounter) -> hud(graphics));
	}

	public static boolean holding(Player player) {
		return player != null && player.getMainHandItem().getItem() instanceof ShotgunItem;
	}

	/** 0 at the hip, 1 with the bead on target. */
	public static float aimProgress(float partialTick) {
		float a = Mth.lerp(partialTick, prevAim, aim);
		return a * a * (3.0F - 2.0F * a);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static float partial() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/** One stroke of the slide: snapped back to hit the stop at {@code back}, driven home at {@code forward} (when the clacks sound). */
	private static float stroke(float t, float back, float forward) {
		float s = 1.6F;
		if (t < back - s || t > forward) {
			return 0.0F;
		}
		if (t < back) {
			float f = (t - back + s) / s;
			return f * f * (2.0F - f) * 1.0F; // fast at the start, braking into the stop
		}
		if (t < forward - s) {
			// a little bounce off the rear stop
			return 1.0F - 0.04F * Mth.sin(Math.min(1.0F, (t - back) / 2.0F) * Mth.PI);
		}
		float f = (t - forward + s) / s;
		return 1.0F - f * f * (2.0F - f);
	}

	/** How far back the forend is on this gun right now (0 forward, 1 fully back). */
	public static float pumpFor(ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return 0.0F;
		}
		GunState s = ShotgunItem.state(stack);
		float now = mc.level.getGameTime() + partial();
		if (s.reloading() && s.reloadKind() == 2) {
			return stroke(now - s.reloadStart(), ShotgunItem.RACK_BACK, ShotgunItem.RACK_FORWARD);
		}
		return stroke(now - s.lastShot(), ShotgunItem.PUMP_BACK, ShotgunItem.PUMP_FORWARD);
	}

	/** Everything that moves on this gun right now, for its model. */
	public static ShotgunItemRenderer.Pose poseFor(ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return ShotgunItemRenderer.Pose.REST;
		}
		GunState s = ShotgunItem.state(stack);
		float since = mc.level.getGameTime() + partial() - s.lastShot();
		// the hull leaves the port as the bolt comes back off the shell
		float eject = since - (ShotgunItem.PUMP_BACK - 0.4F);
		return new ShotgunItemRenderer.Pose(pumpFor(stack), s.rounds() > 0, eject >= 0.0F && eject < 7.0F ? eject : -1.0F);
	}

	// ------------------------------------------------------------------ input

	/** Left click with the shotgun in hand. */
	public static void trigger(LocalPlayer player) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || Injuries.get(player).dying() > 0) {
			return;
		}
		long now = mc.level.getGameTime();
		GunState s = ShotgunItem.state(player.getMainHandItem());
		if (now - lastFire < ShotgunItem.READY || now - s.lastShot() < ShotgunItem.READY || (s.reloading() && s.reloadKind() == 2)) {
			return; // still working the slide
		}
		ClientPlayNetworking.send(new GunInputPayload(GunInputPayload.SHOTGUN_FIRE));
		if (s.rounds() <= 0) {
			dryTick = now;
			lastFire = now - ShotgunItem.READY + 5;
			return;
		}
		lastFire = now;
		GunAudio.shotgun(AkClient.muzzle(player, 1.0F), true);
		kickRoll = ClientEffects.rand() * 2.0F - 0.7F;
		pendingKicks++;
		// the muzzle flips up and the view with it
		float climb = (2.6F + ClientEffects.rand() * 0.8F) * (ShotgunItem.isAiming(player) ? 0.75F : 1.0F);
		player.setXRot(Mth.clamp(player.getXRot() - climb, -90.0F, 90.0F));
		player.setYRot(player.getYRot() + (ClientEffects.rand() - 0.4F) * 0.8F);
		ClientEffects.addShake(0.32F);
		Vec3 muzzle = AkClient.muzzle(player, 1.0F);
		Vec3 look = player.getLookAngle();
		de.rcm.ballistic.client.effect.DynamicLights.flash(muzzle.add(look.scale(0.4)), 0xFFC070, 1.2F, 12.0F, 60.0F + ClientEffects.rand() * 20.0F);
		Vec3 m = muzzle.add(look.scale(0.3));
		SmokeField.puff(m.x, m.y, m.z, look.x * 0.18, look.y * 0.18 + 0.01, look.z * 0.18, 200 + (int) (ClientEffects.rand() * 80), 0.12F, 1.1F,
			0xDAD6D0, 0.3F, 0.6F, 0.0008F);
	}

	/** Someone's shotgun went off (our own is heard and seen already). */
	static void remoteShot(de.rcm.ballistic.network.ModNetworking.GunshotPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || (mc.player != null && p.shooter() == mc.player.getId())) {
			return;
		}
		Vec3 muzzle = new Vec3(p.x(), p.y(), p.z());
		Vec3 look = new Vec3(p.dx(), p.dy(), p.dz());
		GunAudio.shotgun(muzzle, false);
		de.rcm.ballistic.client.effect.DynamicLights.flash(muzzle.add(look.scale(0.4)), 0xFFC070, 1.2F, 12.0F, 60.0F + ClientEffects.rand() * 20.0F);
		if (muzzle.distanceTo(mc.gameRenderer.getMainCamera().position()) < 96.0) {
			Vec3 m = muzzle.add(look.scale(0.3));
			SmokeField.puff(m.x, m.y, m.z, look.x * 0.18, look.y * 0.18 + 0.01, look.z * 0.18, 200 + (int) (ClientEffects.rand() * 80), 0.12F, 1.1F,
				0xDAD6D0, 0.3F, 0.6F, 0.0008F);
		}
	}

	/** R with the shotgun in hand. */
	static void reload(LocalPlayer player) {
		ClientPlayNetworking.send(new GunInputPayload(GunInputPayload.SHOTGUN_LOAD));
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		prevAim = aim;
		prevLoad = load;
		prevSprint = sprint;
		if (player == null || mc.level == null) {
			held = false;
			return;
		}
		long now = mc.level.getGameTime();
		boolean has = holding(player);
		if (has && !held) {
			equipTick = now;
		}
		held = has;
		GunState s = has ? ShotgunItem.state(player.getMainHandItem()) : null;
		boolean loading = s != null && s.reloading() && s.reloadKind() == 1;
		boolean aiming = s != null && ShotgunItem.isAiming(player) && !s.reloading();
		aim = aiming ? Math.min(1.0F, aim + 0.2F) : Math.max(0.0F, aim - 0.22F);
		load = loading ? Math.min(1.0F, load + 0.17F) : Math.max(0.0F, load - 0.14F);
		boolean busy = s != null && (s.reloading() || now - s.lastShot() < ShotgunItem.READY);
		float target = has && player.isSprinting() && !busy && !aiming ? 1.0F : 0.0F;
		sprint += (target - sprint) * 0.25F;
		// shouldering it: the sling and the gun against your jacket
		if (has && now == equipTick) {
			GunAudio.play(ModRegistry.SHOTGUN_HANDLE, player.getEyePosition(), 0.45F, 0.95F + ClientEffects.rand() * 0.1F);
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
			float braced = 1.0F - 0.3F * aiming - (player.isCrouching() ? 0.15F : 0.0F);
			BACK.v += 0.32F * braced;
			PITCH.v += 15.0F * braced * (0.9F + 0.2F * ClientEffects.rand());
			ROLL.v += 5.0F * kickRoll * braced;
		}
		float dy = Mth.wrapDegrees(yaw - lastYaw);
		float dp = pitch - lastPitch;
		lastYaw = yaw;
		lastPitch = pitch;
		float hold = 1.0F - 0.85F * aiming;
		LAG_YAW.v += Mth.clamp(dy, -25.0F, 25.0F) * 0.1F * hold;
		LAG_PITCH.v += Mth.clamp(dp, -25.0F, 25.0F) * 0.1F * hold;
		for (Spring sp : new Spring[] {BACK, PITCH, ROLL, LAG_YAW, LAG_PITCH}) {
			sp.step(dt);
		}
		LAG_YAW.x = Mth.clamp(LAG_YAW.x, -8.0F, 8.0F);
		LAG_PITCH.x = Mth.clamp(LAG_PITCH.x, -7.0F, 7.0F);
	}

	public static void transform(PoseStack poseStack, float partialTick, ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		float now = mc.level.getGameTime() + partialTick;
		GunState state = ShotgunItem.state(stack);
		physics(mc, now, partialTick);

		// the bead brought onto the middle of the view over the groove in the receiver
		float a = aimProgress(partialTick);
		if (a > 0.0F) {
			Quaternionf display = Axis.YP.rotationDegrees(3.0F);
			Vector3f line = display.transform(new Vector3f(SIGHT_LINE));
			Quaternionf toView = new Quaternionf().rotationTo(line, new Vector3f(0.0F, 0.0F, -1.0F));
			Vector3f eye = display.transform(new Vector3f(EYE)).add(-3.2F / 16.0F, 2.4F / 16.0F, 0.5F / 16.0F);
			Vector3f shift = new Vector3f(CAMERA).sub(toView.transform(new Vector3f(eye)));
			poseStack.translate(shift.x * a, shift.y * a, shift.z * a);
			poseStack.mulPose(new Quaternionf().slerp(toView, a));
			float arc = Mth.sin(a * Mth.PI);
			poseStack.translate(0.0F, -0.025F * arc, 0.02F * arc);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-7.0F * arc));
		}

		// its weight lagging behind the view
		poseStack.translate(-LAG_YAW.x * 0.0024F, LAG_PITCH.x * 0.002F, 0.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(LAG_YAW.x * 0.55F));
		poseStack.mulPose(Axis.XP.rotationDegrees(LAG_PITCH.x * 0.5F));

		// breathing, and a wounded arm's tremor
		float tired = Fatigue.level();
		float breath = Fatigue.breathPhase(partialTick);
		float steady = 1.0F - 0.8F * a;
		int arm = Injuries.get(mc.player).arm();
		if (arm > 0) {
			poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 1.7F) * 0.35F + Mth.sin(now * 0.43F) * 0.6F) * arm));
			poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 1.3F) * 0.3F + Mth.sin(now * 0.37F + 1.0F) * 0.55F) * arm));
		}
		poseStack.mulPose(Axis.XP.rotationDegrees((Mth.sin(now * 0.045F) * 0.4F + Mth.sin(breath) * 1.7F * tired) * steady));
		poseStack.mulPose(Axis.YP.rotationDegrees((Mth.cos(now * 0.031F) * 0.35F + Mth.cos(breath * 0.5F) * 1.0F * tired) * steady));

		// working the slide: the left arm snaps the forend back and drives it home. The pull drags the muzzle
		// down and rolls the gun a little towards the pulling hand, the bolt slamming into the rear stop jars
		// it, and driving it home throws the muzzle forward and back up onto the target
		float pump = pumpFor(stack);
		boolean racking = state.reloading() && state.reloadKind() == 2;
		float since = racking ? now - state.reloadStart() : now - state.lastShot();
		float backAt = racking ? ShotgunItem.RACK_BACK : ShotgunItem.PUMP_BACK;
		float homeAt = racking ? ShotgunItem.RACK_FORWARD : ShotgunItem.PUMP_FORWARD;
		float span = racking ? ShotgunItem.RACK : ShotgunItem.READY;
		if (since >= 0.0F && since < span + 4.0F) {
			float work = smooth((since - backAt + 3.0F) / 2.5F) * (1.0F - smooth((since - homeAt) / 4.0F));
			float stop = jolt(since - backAt, 3.5F);
			float home = jolt(since - homeAt, 4.0F);
			float hip = 1.0F - 0.65F * a;
			poseStack.translate(-0.008F * work * hip, -0.006F * work * hip + 0.004F * stop, 0.012F * pump * hip + 0.006F * stop - 0.012F * home);
			poseStack.mulPose(Axis.XP.rotationDegrees((-3.2F * pump - 1.5F * stop + 2.4F * home) * hip));
			poseStack.mulPose(Axis.ZP.rotationDegrees((4.5F * work + 2.0F * stop) * hip));
			poseStack.mulPose(Axis.YP.rotationDegrees(-1.5F * pump * hip));
		}

		// a click instead of a bang: a twitch, then a look at the gun
		float dry = now - dryTick;
		if (dry >= 0.0F && dry < 20.0F) {
			float twitch = dry < 4.0F ? Mth.sin(dry / 4.0F * Mth.PI) : 0.0F;
			float look = dry < 4.0F ? 0.0F : smooth((dry - 4.0F) / 5.0F) * (1.0F - smooth((dry - 12.0F) / 8.0F));
			poseStack.translate(-0.03F * look, 0.03F * look - 0.004F * twitch, -0.006F * twitch);
			poseStack.mulPose(Axis.XP.rotationDegrees(-1.0F * twitch + 4.0F * look));
			poseStack.mulPose(Axis.ZP.rotationDegrees(-14.0F * look));
		}

		// loading: rolled over to the left, raised and turned in so the loading port underneath faces you,
		// with a knock every time a shell is thumbed home
		float l = smooth(Mth.lerp(partialTick, prevLoad, load));
		if (l > 0.0F) {
			float c = state.reloading() ? (now - state.reloadStart()) % ShotgunItem.SHELL_CYCLE : 0.0F;
			float knock = state.reloading() ? jolt(c - ShotgunItem.SHELL_IN, 4.0F) : 0.0F;
			poseStack.translate(-0.07F * l, 0.14F * l + 0.006F * knock, -0.06F * l - 0.008F * knock);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-38.0F * l));
			poseStack.mulPose(Axis.XP.rotationDegrees(11.0F * l - 2.0F * knock));
			poseStack.mulPose(Axis.YP.rotationDegrees(24.0F * l));
		}

		// drawn: up from low and canted
		float draw = 1.0F - smooth((now - equipTick) / 13.0F);
		if (draw > 0.0F) {
			poseStack.translate(0.04F * draw, -0.27F * draw, 0.1F * draw);
			poseStack.mulPose(Axis.ZP.rotationDegrees(-35.0F * draw));
			poseStack.mulPose(Axis.XP.rotationDegrees(-30.0F * draw));
		}

		// sprinting: muzzle down and across the body
		float run = smooth(Mth.lerp(partialTick, prevSprint, sprint));
		if (run > 0.0F) {
			float stride = mc.player.walkAnimation.position(partialTick) * 0.3F;
			poseStack.translate(-0.06F * run, -0.07F * run + Math.abs(Mth.sin(stride * Mth.PI)) * 0.025F * run, 0.04F * run);
			poseStack.mulPose(Axis.YP.rotationDegrees(36.0F * run + Mth.sin(stride * Mth.PI) * 3.0F * run));
			poseStack.mulPose(Axis.XP.rotationDegrees(-22.0F * run));
			poseStack.mulPose(Axis.ZP.rotationDegrees(14.0F * run + Mth.cos(stride * Mth.PI) * 2.0F * run));
		}

		// recoil
		poseStack.translate(0.0F, 0.004F * Math.max(0.0F, PITCH.x) * 0.1F, Math.max(-0.03F, BACK.x));
		poseStack.mulPose(Axis.XP.rotationDegrees(PITCH.x));
		poseStack.mulPose(Axis.ZP.rotationDegrees(ROLL.x));
	}

	private static float jolt(float t, float length) {
		if (t < 0.0F || t > length) {
			return 0.0F;
		}
		float f = t / length;
		return Mth.sin(Math.min(1.0F, f * 3.0F) * Mth.PI * 0.5F) * (1.0F - f) * (1.0F - f);
	}

	private static Vector3f lerp(Vector3f a, Vector3f b, float t) {
		return new Vector3f(a).lerp(b, smooth(t));
	}

	/** An arc from {@code a} to {@code b}, dipping on the way (a hand going down to the belt and back). */
	private static Vector3f swing(Vector3f a, Vector3f b, float t) {
		Vector3f p = lerp(a, b, t);
		return p.add(0.0F, -0.04F * Mth.sin(Mth.clamp(t, 0.0F, 1.0F) * Mth.PI), 0.0F);
	}

	/** The arms holding the gun, and the shell in the left hand while loading. */
	public static void renderArms(AbstractClientPlayer player, ItemStack stack, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light,
		float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		float now = mc.level.getGameTime() + partialTick;
		GunState state = ShotgunItem.state(stack);
		Vector3f forend = new Vector3f(FOREND).add(0.0F, 0.0F, pumpFor(stack) * ShotgunItemRenderer.PUMP_TRAVEL);
		Vector3f left = forend;
		Vector3f shellBase = null;
		float shellTilt = 0.0F;
		float l = smooth(Mth.lerp(partialTick, prevLoad, load));
		if (l > 0.0F && state.reloading() && state.reloadKind() == 1) {
			float t = now - state.reloadStart();
			float c = t % ShotgunItem.SHELL_CYCLE;
			// down to the pouch (from the forend on the first one), up under the port, the shell pushed in
			Vector3f from = t < ShotgunItem.SHELL_CYCLE ? forend : PORT;
			Vector3f hand;
			if (c < 5.0F) {
				hand = swing(from, POUCH, c / 5.0F);
			} else if (c < 9.0F) {
				hand = swing(POUCH, APPROACH, (c - 5.0F) / 4.0F);
			} else if (c < ShotgunItem.SHELL_IN + 0.5F) {
				hand = lerp(APPROACH, PORT, (c - 9.0F) / 1.5F);
			} else {
				hand = new Vector3f(PORT);
			}
			left = new Vector3f(forend).lerp(hand, l);
			if (c > 3.0F && c < ShotgunItem.SHELL_IN + 0.5F) {
				shellTilt = c < 9.0F ? 22.0F : 22.0F * (1.0F - smooth((c - 9.0F) / 1.5F));
				shellBase = new Vector3f(left).add(0.002F, 0.03F, 0.03F);
				if (c >= 9.0F) {
					// the last of it: the shell going into the tube ahead of the fingers
					shellBase.add(0.0F, 0.012F * smooth((c - 9.0F) / 1.5F), -0.03F * smooth((c - 9.0F) / 1.5F));
				}
			}
		}

		poseStack.pushPose();
		poseStack.translate(-3.2F / 16.0F, 2.4F / 16.0F, 0.5F / 16.0F);
		poseStack.mulPose(Axis.YP.rotationDegrees(3.0F));
		if (shellBase != null) {
			poseStack.pushPose();
			poseStack.translate(shellBase.x, shellBase.y, shellBase.z);
			poseStack.mulPose(Axis.XP.rotationDegrees(shellTilt));
			ShotgunItemRenderer.submitShell(poseStack, collector, light);
			poseStack.popPose();
		}
		AkArms.draw(player, base, poseStack, collector, light, GRIP, left, AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER);
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ ammo

	private static void hud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (!holding(player) || mc.options.hideGui) {
			return;
		}
		GunState s = ShotgunItem.state(player.getMainHandItem());
		int shells = player.getInventory().countItem(ModRegistry.SHOTGUN_SHELL);
		String line1 = s.rounds() + " / " + ShotgunItem.CAPACITY;
		String line2 = "12/70 00-BUCK   " + shells;
		int w = g.guiWidth();
		int h = g.guiHeight();
		int color = s.rounds() == 0 ? 0xFFFF5040 : s.rounds() <= 2 ? 0xFFFFC040 : 0xFFF0F0F0;
		g.drawString(mc.font, line1, w - 8 - mc.font.width(line1), h - 34, color, true);
		g.drawString(mc.font, line2, w - 8 - mc.font.width(line2), h - 22, 0xFFB8B8B8, true);
		// the shells in the gun, drawn as little red hulls
		for (int i = 0; i < ShotgunItem.CAPACITY; i++) {
			int x = w - 8 - mc.font.width(line1) - 10 - i * 5;
			int c = i < s.rounds() ? 0xFFC02020 : 0x60404040;
			g.fill(x, h - 33, x + 3, h - 28, c);
			g.fill(x, h - 28, x + 3, h - 26, i < s.rounds() ? 0xFFD0A040 : 0x60404040);
		}
		if (s.reloading()) {
			String r = s.reloadKind() == 2 ? "Durchladen..." : "Laden...";
			g.drawString(mc.font, r, w - 8 - mc.font.width(r), h - 46, 0xFFFFE080, true);
		}
	}
}
