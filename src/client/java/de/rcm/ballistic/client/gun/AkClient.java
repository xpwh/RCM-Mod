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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
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
		ClientTickEvents.END_CLIENT_TICK.register(AkClient::tick);
		ClientTickEvents.END_CLIENT_TICK.register(GunAudio::tick);
		AkItem.clientShot = AkClient::localShot;
		ClientHooks.bulletClientTick = AkClient::bulletTick;
		ClientPlayNetworking.registerGlobalReceiver(GunshotPayload.TYPE, (payload, context) -> remoteShot(payload));
		HudElementRegistry.addLast(BallisticMissiles.id("ammo"), (graphics, tickCounter) -> hud(graphics));
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
	}

	// ------------------------------------------------------------------ firing

	/** Where the muzzle of a rifle held by {@code player} is, and the direction to its right. */
	static Vec3 muzzle(Player player, float partial) {
		Vec3 look = player.getViewVector(partial);
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		return player.getEyePosition(partial).add(look.scale(0.95)).add(right.scale(0.14)).add(0, -0.12, 0);
	}

	private static void localShot(Player player, GunState state) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		lastShotTick = mc.level.getGameTime();
		burst++;
		// the muzzle climbs through a burst and wanders a little to the side
		float climb = 0.75F + Math.min(burst, 10) * 0.09F + ClientEffects.rand() * 0.3F;
		player.setXRot(Mth.clamp(player.getXRot() - climb, -90.0F, 90.0F));
		player.setYRot(player.getYRot() + (ClientEffects.rand() - 0.45F) * 0.7F);
		ClientEffects.addShake(0.12F);
		Vec3 muzzle = muzzle(player, 1.0F);
		GunAudio.shot(muzzle, true);
		effects(player, muzzle, player.getLookAngle(), true);
	}

	private static void remoteShot(GunshotPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Vec3 muzzle = new Vec3(p.x(), p.y(), p.z());
		GunAudio.shot(muzzle, false);
		Entity shooter = mc.level.getEntity(p.shooter());
		if (shooter instanceof Player other && muzzle.distanceTo(mc.gameRenderer.getMainCamera().position()) < 96.0) {
			effects(other, muzzle, new Vec3(p.dx(), p.dy(), p.dz()), false);
		}
	}

	/** Brass and powder smoke. */
	private static void effects(Player player, Vec3 muzzle, Vec3 look, boolean own) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		// the spent case flicks out of the ejection port to the right, forward and up
		Vec3 port = muzzle.subtract(look.scale(0.55)).add(right.scale(0.05));
		Vec3 v = right.scale(0.22 + ClientEffects.rand() * 0.06).add(look.scale(0.05)).add(0, 0.14 + ClientEffects.rand() * 0.05, 0);
		mc.particleEngine.createParticle(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GOLD_BLOCK.defaultBlockState()), port.x, port.y, port.z, v.x, v.y, v.z);
		Vec3 ground = port.add(right.scale(1.6));
		GunAudio.later(ModRegistry.SHELL_DROP, ground.add(0, -1.4, 0), own ? 0.35F : 0.2F, 0.9F + ClientEffects.rand() * 0.3F, 10 + (int) (ClientEffects.rand() * 5));
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
