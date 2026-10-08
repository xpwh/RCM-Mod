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
 */
public class GrenadeItem extends Item {
	/** Fuse from the moment the lever flies off, ticks. */
	public static final int FUSE = 80;
	/** Ticks of winding up for a full throw. */
	public static final int WIND_UP = 18;

	public GrenadeItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!level.isClientSide()) {
			level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), ModRegistry.GRENADE_PIN, SoundSource.PLAYERS, 0.8F,
				0.95F + level.getRandom().nextFloat() * 0.1F);
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
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
			Vec3 look = player.getLookAngle();
			Vec3 right = look.cross(new Vec3(0, 1, 0));
			right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
			Vec3 from = player.getEyePosition().add(look.scale(0.4)).add(right.scale(0.25)).add(0, -0.05, 0);
			Vec3 velocity = look.scale(1.3 * power).add(0, 0.12 * power, 0).add(player.getDeltaMovement().multiply(1.0, 0.0, 1.0));
			GrenadeEntity.throwFrom(server, player, from, velocity);
			server.playSound(null, from.x, from.y, from.z, ModRegistry.GRENADE_SPOON, SoundSource.PLAYERS, 0.7F, 0.95F + server.getRandom().nextFloat() * 0.1F);
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
	}
}
