package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.gun.C4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * C4 and its firing device in first person: the game's side of {@link C4Motion} - the times it needs (placing,
 * FIRE, the selector, taking it in hand) and drawing the arms.
 */
public final class C4Client {
	/** The firing device's moving parts: the cover's angle (degrees, up from shut), how far the button is in, the switch. */
	public record DevicePose(float cover, float press, boolean armed) {
		public static final DevicePose REST = new DevicePose(0.0F, 0.0F, false);
	}

	/** Client time (ticks) the local player pressed FIRE / used the selector, or -1. */
	private static float firedAt = -1.0F;
	private static float selectedAt = -1.0F;
	/** What was in the hand, and since when (for the draw). */
	private static ItemStack held = ItemStack.EMPTY;
	private static int heldCount;
	private static float heldSince;

	private C4Client() {
	}

	public static void init() {
		de.rcm.ballistic.ClientHooks.detonatorAnimation = sneak -> {
			if (sneak) {
				selectedAt = now(0.0F);
			} else {
				firedAt = now(0.0F);
			}
		};
	}

	private static float now(float partial) {
		var level = Minecraft.getInstance().level;
		return level == null ? 0.0F : level.getGameTime() + partial;
	}

	private static float partial() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
	}

	/** Ticks since this stack (or, for C4, this many of it) came into the hand. */
	private static float drawn(ItemStack stack, float partial) {
		if (stack != held || stack.getCount() != heldCount) {
			// a new item, or one charge fewer after placing one: the next is taken up
			boolean fresh = stack != held || stack.getCount() < heldCount;
			held = stack;
			heldCount = stack.getCount();
			if (fresh) {
				heldSince = now(0.0F);
			}
		}
		return now(partial) - heldSince;
	}

	private static float since(float at, float partial) {
		return at < 0.0F ? -1.0F : now(partial) - at;
	}

	// ------------------------------------------------------------------ the firing device

	/** How the device's parts stand for the local player's device right now. */
	public static DevicePose devicePose(ItemStack stack) {
		var player = Minecraft.getInstance().player;
		if (player == null || !(player.getMainHandItem() == stack || player.getOffhandItem() == stack)) {
			return DevicePose.REST;
		}
		return C4Motion.devicePose(since(firedAt, partial()));
	}

	public static void transformDevice(PoseStack poseStack, AbstractClientPlayer player, ItemStack stack, float partial) {
		float time = player.tickCount + partial;
		float sel = since(selectedAt, partial);
		poseStack.mulPose(C4Motion.deviceHold(time, drawn(stack, partial), since(firedAt, partial), sel > 12.0F ? -1.0F : sel));
	}

	public static void renderDeviceArms(AbstractClientPlayer player, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light,
		float partial) {
		C4Motion.Hands h = C4Motion.deviceHands(since(firedAt, partial));
		poseStack.pushPose();
		poseStack.translate(0.0F, 2.0F / 16.0F, 0.0F);
		AkArms.draw(player, base, poseStack, collector, light, h.right(), h.left(), AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER);
		poseStack.popPose();
	}

	// ------------------------------------------------------------------ the charge

	/** Ticks the local player has been pressing a charge on, or -1. */
	public static float placing(AbstractClientPlayer player, float partial) {
		return player.isUsingItem() && player.getUseItem().getItem() instanceof C4Item ? player.getTicksUsingItem() + partial : -1.0F;
	}

	/** For the item renderer: the local player's placing time if {@code stack} is the charge in their hand. */
	public static float placing(ItemStack stack) {
		var player = Minecraft.getInstance().player;
		return player != null && player.getUseItem() == stack ? placing(player, partial()) : -1.0F;
	}

	public static void transformCharge(PoseStack poseStack, AbstractClientPlayer player, ItemStack stack, float partial) {
		float time = player.tickCount + partial;
		poseStack.mulPose(C4Motion.chargeHold(time, drawn(stack, partial), placing(player, partial)));
	}

	public static void renderChargeArms(AbstractClientPlayer player, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light,
		float partial) {
		C4Motion.Hands h = C4Motion.chargeHands(placing(player, partial));
		poseStack.pushPose();
		poseStack.translate(0.0F, 2.0F / 16.0F, 0.0F);
		AkArms.draw(player, base, poseStack, collector, light, h.right(), h.left(), AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER);
		poseStack.popPose();
	}

	static boolean isC4(ItemStack stack) {
		return stack.is(ModRegistry.C4) || stack.is(ModRegistry.C4_DETONATOR);
	}
}
