package de.rcm.ballistic.gun;

import de.rcm.ballistic.BallisticMissiles;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Body armour against bullets and fragments. Where a round strikes decides which piece it meets - the helmet
 * for the head, the vest for the torso, the leg armour, the boots - and if that piece is worn, it takes most
 * of the round: what gets through is a bruising blow instead of a wound (no bleeding, no torn limbs, no fatal
 * head shot). Armour has gaps (arms, neck, the sides under the arms), the more of them the lighter it is, and
 * plates crack: a worn-out piece stops less, until it breaks.
 * <ul>
 *   <li>Light: a soft vest under the shirt - stops pistol rounds and fragments, rifle rounds only partly.</li>
 *   <li>Standard: helmet and plate carrier - the torso and head are covered, the rest is not.</li>
 *   <li>Medium: a full set, helmet, plate carrier with side plates, leg protection, boots.</li>
 *   <li>Heavy: heavy plates everywhere, a visored helmet; you feel the weight.</li>
 *   <li>Juggernaut: a bomb suit of plate over everything - shrugs off rifle fire, but slow to move in.</li>
 * </ul>
 */
public final class BallisticArmor {
	private static final net.minecraft.resources.Identifier WEIGHT = BallisticMissiles.id("armor_weight");

	public enum Tier {
		LIGHT("light", 0.55F, 0.72F, 0.0F, false),
		STANDARD("standard", 0.66F, 0.82F, 0.0F, true),
		MEDIUM("medium", 0.77F, 0.87F, -0.025F, true),
		HEAVY("heavy", 0.87F, 0.93F, -0.045F, true),
		JUGGERNAUT("juggernaut", 0.95F, 0.98F, -0.075F, true);

		public final String key;
		/** Share of a round's damage the piece takes when it is struck (fresh). */
		public final float stop;
		/** Chance a hit in its zone lands on the armour rather than a gap. */
		public final float cover;
		/** Walking speed per piece worn (a share, negative slows). */
		public final float weight;
		/** Hard plates (they ring and spark when struck); the light vest is soft. */
		public final boolean plates;

		Tier(String key, float stop, float cover, float weight, boolean plates) {
			this.key = key;
			this.stop = stop;
			this.cover = cover;
			this.weight = weight;
			this.plates = plates;
		}
	}

	/** What reached the body: the damage left, and whether armour took the hit (and was it a hard plate). */
	public record Result(float damage, boolean stopped, boolean plate) {
	}

	private BallisticArmor() {
	}

	public static @Nullable Tier tier(ItemStack stack) {
		return stack.getItem() instanceof BallisticArmorItem armor ? armor.tier() : null;
	}

	/** The piece of armour that covers the point {@code at} on {@code e}. */
	public static EquipmentSlot zone(LivingEntity e, Vec3 at, boolean head) {
		if (head) {
			return EquipmentSlot.HEAD;
		}
		double h = (at.y - e.getY()) / Math.max(0.1, e.getBbHeight());
		return h >= 0.42 ? EquipmentSlot.CHEST : h >= 0.12 ? EquipmentSlot.LEGS : EquipmentSlot.FEET;
	}

	/** A round striking {@code victim} at {@code at}. */
	public static Result bullet(LivingEntity victim, Vec3 at, boolean head, float damage, boolean pellet, RandomSource random) {
		return strike(victim, zone(victim, at, head), damage, pellet ? 0.12F : 0.0F, pellet ? 1 : 2 + (int) (damage / 5.0F), random);
	}

	/** A fragment striking {@code victim} somewhere: the torso most likely, then legs, head and feet. */
	public static float fragment(LivingEntity victim, float damage, RandomSource random) {
		float r = random.nextFloat();
		EquipmentSlot slot = r < 0.15F ? EquipmentSlot.HEAD : r < 0.62F ? EquipmentSlot.CHEST : r < 0.9F ? EquipmentSlot.LEGS : EquipmentSlot.FEET;
		return strike(victim, slot, damage, 0.15F, 1, random).damage();
	}

	private static Result strike(LivingEntity victim, EquipmentSlot slot, float damage, float bonus, int wear, RandomSource random) {
		ItemStack piece = victim.getItemBySlot(slot);
		Tier tier = tier(piece);
		if (tier == null || random.nextFloat() > tier.cover) {
			return new Result(damage, false, false); // unarmoured there, or through a gap
		}
		// a worn piece stops less: down to about half as much just before it breaks
		float left = piece.isDamageableItem() ? 1.0F - piece.getDamageValue() / (float) Math.max(1, piece.getMaxDamage()) : 1.0F;
		float stop = Mth.clamp((tier.stop + bonus) * (0.55F + 0.45F * left), 0.0F, 0.99F);
		piece.hurtAndBreak(wear, victim, slot);
		return new Result(damage * (1.0F - stop), true, tier.plates);
	}

	/** Twice a second: the weight of what each player wears slows them down. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			float weight = 0.0F;
			for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
				Tier tier = tier(player.getItemBySlot(slot));
				if (tier != null) {
					weight += tier.weight;
				}
			}
			AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
			if (speed == null) {
				continue;
			}
			if (weight < -1.0E-4F) {
				speed.addOrUpdateTransientModifier(new AttributeModifier(WEIGHT, weight, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			} else if (speed.getModifier(WEIGHT) != null) {
				speed.removeModifier(WEIGHT);
			}
		}
	}
}
