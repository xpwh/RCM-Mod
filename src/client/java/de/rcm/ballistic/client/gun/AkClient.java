package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.platform.InputConstants;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ClientHooks;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.effect.ClientEffects;
import de.rcm.ballistic.client.effect.SmokeField;
import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.gun.AkMagazineItem;
import de.rcm.ballistic.gun.BulletEntity;
import de.rcm.ballistic.gun.GunState;
import de.rcm.ballistic.network.ModNetworking.GunInputPayload;
import de.rcm.ballistic.network.ModNetworking.GunshotPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import de.rcm.ballistic.client.render.ShellCasings;
import de.rcm.ballistic.client.render.TracerFx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Client side of the AK: the keys (R reload, sneak + R other ammunition, V fire selector), what
 * firing feels like (the stock punching your shoulder, the muzzle climbing through a burst, brass
 * spinning out to the right, a haze of powder smoke), the ammo counter, and bullets cracking past
 * your head when someone shoots at you.
 */
public final class AkClient {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(BallisticMissiles.id("weapons"));
	private static KeyMapping reload;
	private static KeyMapping selector;
	private static KeyMapping check;
	private static KeyMapping inspect;
	/** Looking the rifle over: when it started (game tick). */
	static long inspectStart = -1000L;
	static final int INSPECT_TICKS = 64;

	// the trigger as the client sees it (left mouse button), with the shots it has predicted
	private static boolean triggerDown;
	private static long triggerTick;
	private static int pressShots;
	private static int pressRounds;
	// aiming over the sights
	private static float aim;
	private static float prevAim;
	/** Magazine check: when it started (game tick), and the rounds seen. */
	static long checkStart = -1000L;
	static final int CHECK_TICKS = 40;

	/** Local recoil state, for the first-person animation. */
	static long lastShotTick = -100;

	public static long lastShotTick() {
		return lastShotTick;
	}
	static float lastShotPartial;
	static int burst;

	private AkClient() {
	}

	public static void init() {
		reload = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ballisticmissiles.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
		selector = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ballisticmissiles.selector", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));
		check = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ballisticmissiles.mag_check", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));
		inspect = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.ballisticmissiles.inspect", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(AkClient::tick);
		ClientTickEvents.END_CLIENT_TICK.register(GunAudio::tick);
		ClientTickEvents.END_CLIENT_TICK.register(ShellCasings::tick);
		ClientTickEvents.END_CLIENT_TICK.register(Fatigue::tick);
		ClientTickEvents.END_CLIENT_TICK.register(TracerFx::tick);
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(TracerFx::render);
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.BEFORE_ENTITIES.register(ShellCasings::render);
		ClientHooks.bulletClientTick = AkClient::bulletTick;
		ClientPlayNetworking.registerGlobalReceiver(GunshotPayload.TYPE, (payload, context) -> remoteShot(payload));
		HudElementRegistry.addLast(BallisticMissiles.id("ammo"), (graphics, tickCounter) -> hud(graphics));
		// looking through the sights (AK or RPG) there is no crosshair: the sights are the aim
		HudElementRegistry.replaceElement(net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CROSSHAIR, crosshair -> (graphics, tickCounter) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && (AkItem.isAiming(mc.player) || de.rcm.ballistic.item.RocketLauncherItem.isAiming(mc.player))) {
				return;
			}
			crosshair.render(graphics, tickCounter);
		});
	}

	/** 0 at the hip, 1 with the eye behind the rear sight. */
	public static float aimProgress(float partialTick) {
		float a = Mth.lerp(partialTick, prevAim, aim);
		return a * a * (3.0F - 2.0F * a);
	}

	/** Ticks into the local player's magazine check, or -1. */
	public static float checkTime(float now) {
		float t = now - checkStart;
		return t >= 0.0F && t < CHECK_TICKS ? t : -1.0F;
	}

	/** Ticks into looking the rifle over, or -1. */
	public static float inspectTime(float now) {
		float t = now - inspectStart;
		return t >= 0.0F && t < INSPECT_TICKS ? t : -1.0F;
	}

	/** Sounds of looking the rifle over: the sling and the rifle shifting, the press check, the slap on the magazine. */
	private static void inspectTick(LocalPlayer player, GunState state, long now) {
		long t = now - inspectStart;
		Vec3 at = player.getEyePosition();
		if (t == 1 || t == 40) {
			GunAudio.play(net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_GENERIC.value(), at, 0.35F, 1.1F);
		} else if (t == 29) {
			GunAudio.play(ModRegistry.AK_SELECTOR, at, 0.45F, 0.75F);
		} else if (t == 35) {
			GunAudio.play(ModRegistry.AK_SELECTOR, at, 0.55F, 1.15F);
			if (state.rounds() > 0) {
				// brass in the chamber, seen: say so
				Minecraft.getInstance().gui.setOverlayMessage(net.minecraft.network.chat.Component.translatable("message.ballisticmissiles.ak_chamber_loaded"), false);
			} else {
				Minecraft.getInstance().gui.setOverlayMessage(net.minecraft.network.chat.Component.translatable("message.ballisticmissiles.ak_chamber_empty"), false);
			}
		} else if (t == 50 && state.hasMag()) {
			GunAudio.play(ModRegistry.AK_MAG_IN, at, 0.3F, 1.25F);
		}
	}

	static boolean checking(long now) {
		return now - checkStart < CHECK_TICKS;
	}

	/** The magazine check: sounds as it comes out and goes back, and what you make of what you see. */
	private static void magCheckTick(Minecraft mc, LocalPlayer player, GunState state, long now) {
		long t = now - checkStart;
		if (state == null) {
			checkStart = -1000L;
			return;
		}
		if (t == 11) {
			GunAudio.play(ModRegistry.AK_MAG_OUT, player.getEyePosition(), 0.5F, 1.0F);
		} else if (t == 17) {
			int r = state.rounds();
			String key = r <= 0 ? "empty" : r < 8 ? "low" : r < 15 ? "half" : r < 25 ? "most" : "full";
			mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.translatable("message.ballisticmissiles.mag_check_" + key), false);
		} else if (t == 27) {
			GunAudio.play(ModRegistry.AK_MAG_IN, player.getEyePosition(), 0.6F, 1.0F);
		}
	}

	private static boolean holdingAk(Player player) {
		return player != null && player.getMainHandItem().getItem() instanceof AkItem;
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		while (reload.consumeClick()) {
			if (holdingAk(player)) {
				ClientPlayNetworking.send(new GunInputPayload(player.isShiftKeyDown() ? GunInputPayload.RELOAD_SWITCH : GunInputPayload.RELOAD));
			}
		}
		while (selector.consumeClick()) {
			if (holdingAk(player)) {
				ClientPlayNetworking.send(new GunInputPayload(GunInputPayload.SELECTOR));
			}
		}
		if (mc.level != null && mc.level.getGameTime() - lastShotTick > 5) {
			burst = 0;
		}
		if (mc.level != null && player != null) {
			long now = mc.level.getGameTime();
			ItemStack stack = player.getMainHandItem();
			GunState state = holdingAk(player) ? AkItem.state(stack) : null;
			boolean checking = checking(now);
			while (check.consumeClick()) {
				if (player.getMainHandItem().getItem() instanceof de.rcm.ballistic.item.RocketLauncherItem) {
					de.rcm.ballistic.client.item.RpgClient.startCheck(player);
				}
				if (state != null && !state.reloading() && !checking && state.hasMag()) {
					checkStart = now;
					checking = true;
				}
			}
			magCheckTick(mc, player, state, now);
			while (inspect.consumeClick()) {
				if (state != null && !state.reloading() && !checking && aim <= 0.0F && inspectTime(now) < 0.0F && !triggerDown) {
					inspectStart = now;
				}
			}
			// anything else you do with the rifle breaks off looking it over
			if (inspectTime(now) >= 0.0F && (state == null || state.reloading() || checking || mc.options.keyAttack.isDown() || AkItem.isAiming(player)
				|| player.isSprinting())) {
				inspectStart = -1000L;
			}
			if (state != null && inspectTime(now) >= 0.0F) {
				inspectTick(player, state, now);
			}
			// the trigger: held down with the rifle in hand and nothing else on screen
			boolean want = state != null && mc.screen == null && mc.options.keyAttack.isDown() && !checking;
			if (want != triggerDown) {
				if (want && !state.reloading() && state.mode() != GunState.SAFE && state.rounds() <= 0) {
					AkFirstPerson.dryFire(now); // the hammer falls on nothing
				}
				triggerDown = want;
				triggerTick = now;
				pressShots = 0;
				pressRounds = state == null ? 0 : state.rounds();
				ClientPlayNetworking.send(new GunInputPayload(want ? GunInputPayload.TRIGGER_DOWN : GunInputPayload.TRIGGER_UP));
			}
			if (triggerDown && !state.reloading() && state.mode() != GunState.SAFE) {
				long held = now - triggerTick;
				boolean drop = state.mode() == GunState.AUTO ? held % AkItem.CYCLE == 0 : held == 0;
				if (drop && pressShots < pressRounds) {
					pressShots++;
					localShot(player, state);
				}
			}
			prevAim = aim;
			boolean aiming = state != null && AkItem.isAiming(player) && !state.reloading() && !checking;
			aim = aiming ? Math.min(1.0F, aim + 0.2F) : Math.max(0.0F, aim - 0.22F);
		}
		AkFirstPerson.tick(mc);
	}

	// ------------------------------------------------------------------ firing

	/** Where the muzzle of a rifle held by {@code player} is, and the direction to its right. */
	static Vec3 muzzle(Player player, float partial) {
		Vec3 look = player.getViewVector(partial);
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		return player.getEyePosition(partial).add(look.scale(0.95)).add(right.scale(0.14)).add(0, -0.12, 0);
	}

	static void localShot(Player player, GunState state) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		lastShotTick = mc.level.getGameTime();
		burst++;
		AkFirstPerson.kickSide = ClientEffects.rand() * 2.0F - 1.0F;
		AkFirstPerson.kickRoll = ClientEffects.rand() * 2.0F - 0.6F; // the AK's kick rolls it a little to the right
		AkFirstPerson.kick();
		// the muzzle climbs through a burst and wanders a little to the side
		float climb = (0.4F + Math.min(burst, 10) * 0.05F + ClientEffects.rand() * 0.15F) * (AkItem.isAiming(player) ? 0.7F : 1.0F);
		player.setXRot(Mth.clamp(player.getXRot() - climb, -90.0F, 90.0F));
		player.setYRot(player.getYRot() + (ClientEffects.rand() - 0.45F) * 0.35F);
		ClientEffects.addShake(0.12F);
		Vec3 muzzle = muzzle(player, 1.0F);
		GunAudio.shot(muzzle, true);
		// smoke and brass from the rifle we see in our hands
		Vec3 seen = mc.options.getCameraType().isFirstPerson() ? viewMuzzleInWorld(mc) : muzzle;
		// the hole where the crosshair is, at once (the server's own replaces it a moment later)
		Vec3 eye = player.getEyePosition();
		net.minecraft.world.phys.BlockHitResult sight = mc.level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(player.getLookAngle().scale(300.0)),
			net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		if (sight.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			var struck = mc.level.getBlockState(sight.getBlockPos());
			if (!(struck.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) && !BulletEntity.isGlass(struck)
				&& !(struck.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock)) {
				de.rcm.ballistic.client.render.BulletHoles.predict(sight.getLocation(), sight.getDirection(), BulletEntity.holeKind(struck), sight.getLocation().subtract(muzzle));
			}
		}
		effects(player, seen, player.getLookAngle(), true);
	}

	private static void remoteShot(GunshotPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Vec3 muzzle = new Vec3(p.x(), p.y(), p.z());
		Vec3 dir = new Vec3(p.dx(), p.dy(), p.dz());
		if ((p.flags() & GunshotPayload.TRACER) != 0) {
			Vec3 from = muzzle;
			Vec3 velocity = dir.scale(de.rcm.ballistic.gun.AkItem.MUZZLE_VELOCITY);
			if (mc.player != null && p.shooter() == mc.player.getId() && mc.options.getCameraType().isFirstPerson()) {
				// our own round: out of the muzzle we see, converging on the bullet's true line
				from = viewMuzzleInWorld(mc);
				Vec3 aimed = muzzle.add(dir.scale(120.0));
				velocity = aimed.subtract(from).normalize().scale(de.rcm.ballistic.gun.AkItem.MUZZLE_VELOCITY);
			}
			TracerFx.fire(from, velocity, p.shooter());
		}
		if (mc.player != null && p.shooter() == mc.player.getId()) {
			return; // our own shot: heard and seen already
		}
		GunAudio.shot(muzzle, false);
		Entity shooter = mc.level.getEntity(p.shooter());
		if (shooter instanceof Player other && muzzle.distanceTo(mc.gameRenderer.getMainCamera().position()) < 96.0) {
			effects(other, muzzle, new Vec3(p.dx(), p.dy(), p.dz()), false);
		}
	}

	/** The muzzle of the rifle as drawn in first person, in world space. */
	static Vec3 viewMuzzleInWorld(Minecraft mc) {
		var camera = mc.gameRenderer.getMainCamera();
		org.joml.Vector3f v = de.rcm.ballistic.client.render.AkItemRenderer.VIEW_MUZZLE;
		org.joml.Vector3f fwd = new org.joml.Vector3f(camera.forwardVector());
		org.joml.Vector3f up = new org.joml.Vector3f(camera.upVector());
		org.joml.Vector3f right = new org.joml.Vector3f(fwd).cross(up).normalize();
		Vec3 cam = camera.position();
		// the hand is drawn with the plain field of view, the world with zoom and sprint applied:
		// keep the muzzle where it is on screen
		float base = mc.options.fov().get().floatValue();
		float mod = mc.player == null ? 1.0F : mc.player.getFieldOfViewModifier(true, mc.options.fovEffectScale().get().floatValue());
		float k = (float) (Math.tan(Math.toRadians(base * 0.5)) / Math.tan(Math.toRadians(base * mod * 0.5)));
		v = new org.joml.Vector3f(v.x * k, v.y * k, v.z);
		return cam.add(right.x * v.x + up.x * v.y - fwd.x * v.z, right.y * v.x + up.y * v.y - fwd.y * v.z, right.z * v.x + up.z * v.y - fwd.z * v.z);
	}

	/** Brass and powder smoke. */
	private static void effects(Player player, Vec3 muzzle, Vec3 look, boolean own) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		// the spent case flicks out of the ejection port to the right, forward and up
		Vec3 port = muzzle.subtract(look.scale(0.55)).add(right.scale(0.05));
		Vec3 v = right.scale(0.22 + ClientEffects.rand() * 0.06).add(look.scale(0.05)).add(0, 0.14 + ClientEffects.rand() * 0.05, 0);
		ShellCasings.eject(port, v.add(player.getDeltaMovement()));
		// a puff of powder smoke at the muzzle that hangs about in still air
		Vec3 m = muzzle.add(look.scale(0.25));
		SmokeField.puff(m.x, m.y, m.z, look.x * 0.12, look.y * 0.12 + 0.01, look.z * 0.12, 160 + (int) (ClientEffects.rand() * 80), 0.08F, 0.75F,
			0xDAD6D0, 0.22F, 0.6F, 0.0008F);
	}

	// ------------------------------------------------------------------ bullets going past

	private static void bulletTick(BulletEntity bullet) {
		Minecraft mc = Minecraft.getInstance();
		if (bullet.crackPlayed || mc.player == null || bullet.shooterId() == mc.player.getId()) {
			return;
		}
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		Vec3 a = bullet.clientPrev;
		Vec3 v = bullet.getDeltaMovement();
		double len2 = v.lengthSqr();
		if (len2 < 1.0E-6) {
			return;
		}
		double t = Mth.clamp(ear.subtract(a).dot(v) / len2, 0.0, 1.0);
		Vec3 closest = a.add(v.scale(t));
		double d = closest.distanceTo(ear);
		if (d > 6.0) {
			return;
		}
		bullet.crackPlayed = true;
		float near = (float) (1.0 - d / 6.0);
		// under fire: the picture closes in and the hands start to shake
		de.rcm.ballistic.client.effect.BlastShader.suppress(0.18F + 0.45F * near * near);
		if (Math.sqrt(len2) > 17.5) {
			// supersonic: the sharp snap of its shock wave going past your ear
			GunAudio.play(ModRegistry.BULLET_CRACK, closest, 0.45F + 0.55F * near, 0.92F + ClientEffects.rand() * 0.16F);
			ClientEffects.addShake(0.25F * near);
		} else {
			GunAudio.play(ModRegistry.BULLET_WHIZ, closest, 0.4F + 0.6F * near, 0.9F + ClientEffects.rand() * 0.2F);
		}
	}

	// ------------------------------------------------------------------ ammo counter

	private static void hud(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (!holdingAk(player) || mc.options.hideGui) {
			return;
		}
		ItemStack gun = player.getMainHandItem();
		GunState s = AkItem.state(gun);
		int mags = 0;
		int tracers = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack st = player.getInventory().getItem(i);
			if (st.getItem() instanceof AkMagazineItem m && AkMagazineItem.rounds(st) > 0) {
				if (m.tracer()) {
					tracers += st.getCount();
				} else {
					mags += st.getCount();
				}
			}
		}
		String mode = switch (s.mode()) {
			case GunState.SEMI -> "SEMI";
			case GunState.SAFE -> "SAFE";
			default -> "AUTO";
		};
		String rounds = s.hasMag() ? String.valueOf(s.rounds()) : "--";
		String line1 = rounds + " / " + AkItem.MAG_CAPACITY;
		String line2 = mode + (s.ammo() == GunState.TRACER ? "  TRACER" : "  7.62") + "   " + mags + (tracers > 0 ? " + " + tracers + "T" : "");
		int w = g.guiWidth();
		int h = g.guiHeight();
		int color = s.rounds() == 0 ? 0xFFFF5040 : s.rounds() <= 5 ? 0xFFFFC040 : 0xFFF0F0F0;
		g.drawString(mc.font, line1, w - 8 - mc.font.width(line1), h - 34, color, true);
		g.drawString(mc.font, line2, w - 8 - mc.font.width(line2), h - 22, s.ammo() == GunState.TRACER ? 0xFF8CFF7A : 0xFFB8B8B8, true);
		if (s.reloading()) {
			String r = "Nachladen...";
			g.drawString(mc.font, r, w - 8 - mc.font.width(r), h - 46, 0xFFFFE080, true);
		}
	}
}
