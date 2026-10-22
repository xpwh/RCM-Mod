package de.rcm.ballistic.client.gui;

import de.rcm.ballistic.client.ModConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;

/**
 * The hotbar gets out of the way: left alone for a few seconds (no other slot picked, nothing in it changing) it
 * glides down off the bottom of the screen with the experience bar, and the hearts, food and armour settle down
 * into the space it leaves. Picking a slot, scrolling, picking something up or opening a screen brings it straight
 * back up.
 */
public final class HotbarFade {
	/** How far the hearts and food drop when the hotbar and the experience bar below them are gone. */
	public static final float DROP = 29.0F;
	/** The experience level number sits above the bar: it goes a little further to be out of sight. */
	public static final float LEVEL_EXTRA = 12.0F;
	private static long lastActive = Long.MAX_VALUE;
	private static int lastSlot = -1;
	private static int lastHash;
	private static float shown = 1.0F;
	private static long lastFrame;

	private HotbarFade() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(HotbarFade::tick);
	}

	/** The hotbar is in use again: up it comes. */
	public static void wake() {
		lastActive = Util.getMillis();
	}

	private static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) {
			lastSlot = -1;
			return;
		}
		int slot = player.getInventory().getSelectedSlot();
		int hash = 1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			hash = hash * 31 + (stack.isEmpty() ? 0 : stack.getItem().hashCode() * 7 + stack.getCount());
		}
		hash = hash * 31 + player.getOffhandItem().getCount() + (player.getOffhandItem().isEmpty() ? 0 : player.getOffhandItem().getItem().hashCode());
		if (slot != lastSlot || hash != lastHash || mc.screen != null || lastActive == Long.MAX_VALUE) {
			wake();
		}
		lastSlot = slot;
		lastHash = hash;
	}

	/** How far it has gone, 0 (all there) to 1 (out of sight), eased; worked out once per frame. */
	public static float hidden() {
		long now = Util.getMillis();
		float dt = Mth.clamp((now - lastFrame) / 1000.0F, 0.0F, 0.1F);
		if (now != lastFrame) {
			lastFrame = now;
			boolean away = ModConfig.hotbarAutoHide && lastActive != Long.MAX_VALUE
				&& now - lastActive > Mth.clamp(ModConfig.hotbarHideSeconds, 1, 60) * 1000L;
			// down slowly, up quickly
			float target = away ? 0.0F : 1.0F;
			float rate = away ? 2.6F : 7.0F;
			shown = shown < target ? Math.min(target, shown + dt * rate) : Math.max(target, shown - dt * rate);
		}
		float h = 1.0F - shown;
		return h * h * (3.0F - 2.0F * h);
	}
}
