package de.rcm.ballistic.client.gun;

import de.rcm.ballistic.gun.AkItem;
import de.rcm.ballistic.gun.GlockItem;
import de.rcm.ballistic.gun.GunState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;

/**
 * A reload seen from outside (another player, or yourself in third person): the same clip the first-person view
 * plays, read for the body - the support arm drops to the pouch on the belt and comes back up to the magazine
 * well, the shooting arm draws the gun in and cants it, the head bows to watch the hands and jerks with the slap.
 * The magazines themselves already go where the clip has them (the item renderers).
 */
public final class ThirdPersonReload {
	private static final float[] SAMPLE = new float[AkClips.STRIDE];

	private ThirdPersonReload() {
	}

	/** After the model's own pose (crossbow hold): bend it to the reload, if the player (or soldier) with this id is reloading. */
	public static void pose(net.minecraft.client.model.HumanoidModel<?> model, int id, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Entity e = mc.level.getEntity(id);
		if (!(e instanceof net.minecraft.world.entity.LivingEntity holder)) {
			return;
		}
		ItemStack stack = holder.getMainHandItem();
		float now = mc.level.getGameTime() + partialTick;
		float[] s;
		Vector3f rest;
		Vector3f grip;
		if (stack.getItem() instanceof AkItem) {
			GunState state = AkItem.state(stack);
			if (!state.reloading()) {
				return;
			}
			float r = now - state.reloadStart();
			s = AkClips.reload(state, r).at(r, SAMPLE);
			rest = AkAnim.HANDGUARD;
			grip = AkAnim.GRIP;
		} else if (stack.getItem() instanceof GlockItem) {
			GunState state = GlockItem.state(stack);
			if (!state.reloading()) {
				return;
			}
			float r = now - state.reloadStart();
			boolean empty = state.reloadKind() == GunState.EMPTY;
			AkClips.Clip clip = GlockClips.reload(empty, state.reloadAmmo(), empty ? 0 : state.reloadVariant(), state.reloadStart());
			if (clip == null) {
				return;
			}
			s = clip.at(r, SAMPLE);
			rest = GlockMotion.SUPPORT;
			grip = GlockMotion.GRIP;
		} else {
			return;
		}
		// how far the gun is in close and canted (0..1)
		float dist = Mth.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
		float in = smooth(Math.max(Math.abs(s[3]) / 25.0F, dist / 0.12F));
		// the support hand: away from its place on the gun, down towards the belt and back towards the body
		float lx = s[30] - rest.x();
		float ly = s[31] - rest.y();
		float lz = s[32] - rest.z();
		float away = smooth(Mth.sqrt(lx * lx + ly * ly + lz * lz) / 0.1F);
		float low = Mth.clamp(-ly / 0.4F, 0.0F, 1.0F);
		float back = Mth.clamp(lz / 0.5F, 0.0F, 1.0F);
		float pouch = Math.max(low, back) * away;
		model.leftArm.xRot = Mth.lerp(pouch, model.leftArm.xRot * (1.0F - 0.25F * away) + 0.25F * away, -0.3F);
		model.leftArm.yRot = Mth.lerp(pouch, model.leftArm.yRot, 0.15F);
		model.leftArm.zRot = Mth.lerp(pouch, model.leftArm.zRot, -0.12F);
		// the shooting arm: the gun drawn in to the chest and rolled over
		float rx = s[33] - grip.x();
		float ry = s[34] - grip.y();
		model.rightArm.xRot += 0.35F * in - ry * 2.0F;
		model.rightArm.yRot += 0.25F * in - rx * 1.5F;
		model.rightArm.zRot += s[3] * Mth.DEG_TO_RAD * 0.5F;
		// the head: down to the hands, with the clip's own head motion (the jolt of the slap, the look after a drop)
		float bow = 0.42F * in + s[AkClips.HEAD] * Mth.DEG_TO_RAD * 1.5F;
		model.head.xRot = Mth.clamp(model.head.xRot + bow, -1.5F, 1.3F);
		model.head.zRot += s[AkClips.HEAD + 2] * Mth.DEG_TO_RAD;
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}
}
