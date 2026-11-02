package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * C4: a bundle of four M112 blocks with a radio receiver. Hold right-click on a wall, a floor or a ceiling: the hand
 * presses it on and smooths the tape down ({@value #PLACE_TICKS} ticks), it sticks there, armed, its LED blinking.
 * Set it off with the firing device (C4DetonatorItem).
 */
public class C4Item extends Item {
	/** Ticks of pressing it on: brought to the wall, pushed on, taped, the code keyed in (C4Motion draws it). */
	public static final int PLACE_TICKS = 30;
	private static final int T_SLAP = 7;
	private static final int[] T_KEYS = {19, 22, 24};

	public C4Item(Properties properties) {
		super(properties);
	}

	/** The face the player is looking at within reach, or null. */
	public static @Nullable BlockHitResult target(Player player) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
		BlockHitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (target(player) == null) {
			return InteractionResult.PASS;
		}
		player.startUsingItem(hand);
		return InteractionResult.CONSUME;
	}

	@Override
	public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
		int t = this.getUseDuration(stack, entity) - remaining;
		if (level.isClientSide()) {
			return;
		}
		if (t == T_SLAP) {
			// the block meets the wall, the tape torn and pressed down
			level.playSound(null, entity.getX(), entity.getEyeY(), entity.getZ(), ModRegistry.C4_PLACE, SoundSource.PLAYERS, 0.8F,
				0.95F + level.getRandom().nextFloat() * 0.1F);
		}
		for (int k : T_KEYS) {
			if (t == k) {
				// a digit keyed in on the receiver
				level.playSound(null, entity.getX(), entity.getEyeY(), entity.getZ(), ModRegistry.C4_BEEP, SoundSource.PLAYERS, 0.45F, 1.5F);
			}
		}
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
		if (!(entity instanceof Player player) || !(level instanceof ServerLevel server)) {
			return stack;
		}
		BlockHitResult hit = target(player);
		if (hit == null) {
			return stack;
		}
		Direction face = hit.getDirection();
		// on a floor or a ceiling it lies across the way you look; on a wall, level
		float spin = face.getAxis() == Direction.Axis.Y ? player.getYRot() : 0.0F;
		C4ChargeEntity.place(server, hit.getLocation(), face, spin, player);
		// armed: the receiver's two beeps
		server.playSound(null, hit.getLocation().x, hit.getLocation().y, hit.getLocation().z, ModRegistry.C4_SWITCH, SoundSource.PLAYERS, 0.6F, 1.1F);
		de.rcm.ballistic.explosion.DetonationManager.later(server, 5, () -> server.playSound(null, hit.getLocation().x, hit.getLocation().y,
			hit.getLocation().z, ModRegistry.C4_BEEP, SoundSource.BLOCKS, 0.5F, 1.2F));
		de.rcm.ballistic.explosion.DetonationManager.later(server, 9, () -> server.playSound(null, hit.getLocation().x, hit.getLocation().y,
			hit.getLocation().z, ModRegistry.C4_BEEP, SoundSource.BLOCKS, 0.5F, 1.2F));
		if (!player.getAbilities().instabuild) {
			stack.shrink(1);
		}
		int n = C4ChargeEntity.charges((net.minecraft.server.level.ServerPlayer) player).size();
		player.displayClientMessage(Component.translatable("message.ballisticmissiles.c4_placed", n).withStyle(ChatFormatting.GOLD), true);
		return stack;
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return PLACE_TICKS;
	}

	@Override
	public ItemUseAnimation getUseAnimation(ItemStack stack) {
		return ItemUseAnimation.NONE;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("item.ballisticmissiles.c4.tooltip").withStyle(ChatFormatting.GRAY));
	}
}
