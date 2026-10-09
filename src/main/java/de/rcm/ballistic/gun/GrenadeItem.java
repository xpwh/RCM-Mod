package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * RGD-5 hand grenade. Hold right-click: the left hand pulls the pin while the right holds the lever
 * down, and the arm comes back to throw. Let go and it is thrown - the harder the longer you wound up -
 * the lever flies off and the fuse burns: four seconds, then it bursts into some 350 fragments.
 * <p>
 * Cooking: left-click while holding it lets the lever fly in your hand, so the fuse is already
 * burning when you throw (and if you hold on too long, it goes off in your hand). Sneak as you let go
 * for an underhand lob: short, soft, round a corner or into a trench.
 */
public class GrenadeItem extends Item {
	/** Fuse from the moment the lever flies off, ticks. */
	public static final int FUSE = 80;
	/** Ticks of winding up for a full throw. */
	public static final int WIND_UP = 18;

	/** Server: players who have let the lever fly, and the game time they did. */
	private static final java.util.Map<java.util.UUID, Long> COOKING = new java.util.HashMap<>();

	public GrenadeItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!level.isClientSide()) {
			level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), ModRegistry.GRENADE_PIN, SoundSource.PLAYERS, 0.8F,
				0.95F + level.getRandom().nextFloat() * 0.1F);
		}
		if (!level.isClientSide()) {
			COOKING.remove(player.getUUID());
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	/** Left-click while the pin is out: let the lever go in the hand, the fuse starts burning. */
	public static void cook(net.minecraft.server.level.ServerPlayer player) {
		if (!(player.getUseItem().getItem() instanceof GrenadeItem) || COOKING.containsKey(player.getUUID())) {
			return;
		}
		ServerLevel level = player.level();
		COOKING.put(player.getUUID(), level.getGameTime());
		level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), ModRegistry.GRENADE_SPOON, SoundSource.PLAYERS, 0.7F,
			0.95F + level.getRandom().nextFloat() * 0.1F);
	}

	/** Ticks left on the fuse of {@code player}'s grenade, or the full fuse if it is not cooking. */
	private static int fuseLeft(Player player, Level level) {
		Long since = COOKING.get(player.getUUID());
		return since == null ? FUSE : FUSE - (int) (level.getGameTime() - since);
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, net.minecraft.world.entity.Entity entity, net.minecraft.world.entity.EquipmentSlot slot) {
		if (!(entity instanceof Player player) || !COOKING.containsKey(player.getUUID())) {
			return;
		}
		int left = fuseLeft(player, level);
		if (left <= 0) {
			// held on too long
			COOKING.remove(player.getUUID());
			if (player.getUseItem().getItem() instanceof GrenadeItem) {
				player.stopUsingItem();
			}
			if (!player.getAbilities().instabuild) {
				stack.shrink(1);
			}
			Vec3 at = player.position().add(0, player.getBbHeight() * 0.6, 0).add(player.getLookAngle().scale(0.4));
			de.rcm.ballistic.explosion.DetonationManager.detonateGrenade(level, at, player);
			return;
		}
		if (left % 5 == 0) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.grenade_cooking", String.format(java.util.Locale.ROOT, "%.1f", left / 20.0F))
				.withStyle(left < 30 ? ChatFormatting.RED : ChatFormatting.GOLD), true);
		}
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return 72000;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.NONE;
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
		if (!(entity instanceof Player player)) {
			return false;
		}
		int held = this.getUseDuration(stack, entity) - timeLeft;
		float power = Math.max(0.3F, Math.min(1.0F, held / (float) WIND_UP));
		if (level instanceof ServerLevel server) {
			boolean cooked = COOKING.containsKey(player.getUUID());
			int fuse = fuseLeft(player, level);
			COOKING.remove(player.getUUID());
			if (fuse <= 0) {
				return true; // already gone off
			}
			Vec3 look = player.getLookAngle();
			Vec3 right = look.cross(new Vec3(0, 1, 0));
			right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
			Vec3 from;
			Vec3 velocity;
			if (player.isShiftKeyDown()) {
				// underhand: bowled low and soft from the hip, a few metres
				Vec3 flat = new Vec3(look.x, 0, look.z);
				flat = flat.lengthSqr() < 1.0E-4 ? Vec3.directionFromRotation(0, player.getYRot()) : flat.normalize();
				from = player.position().add(0, player.getBbHeight() * 0.45, 0).add(flat.scale(0.45)).add(right.scale(0.2));
				velocity = flat.scale(0.5 * power + 0.15).add(0, 0.18 + Math.max(0.0, look.y) * 0.3, 0);
			} else {
				from = player.getEyePosition().add(look.scale(0.4)).add(right.scale(0.25)).add(0, -0.05, 0);
				velocity = look.scale(1.3 * power).add(0, 0.12 * power, 0);
			}
			velocity = velocity.add(player.getDeltaMovement().multiply(1.0, 0.0, 1.0));
			GrenadeEntity.throwFrom(server, player, from, velocity, fuse);
			if (!cooked) {
				server.playSound(null, from.x, from.y, from.z, ModRegistry.GRENADE_SPOON, SoundSource.PLAYERS, 0.7F, 0.95F + server.getRandom().nextFloat() * 0.1F);
			}
			if (!player.getAbilities().instabuild) {
				stack.shrink(1);
			}
		}
		player.swing(InteractionHand.MAIN_HAND);
		player.getCooldowns().addCooldown(stack, 12);
		return true;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.grenade.1").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.grenade.2").withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.grenade.3").withStyle(ChatFormatting.DARK_GRAY));
	}
}
