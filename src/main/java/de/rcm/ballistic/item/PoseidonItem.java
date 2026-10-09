package de.rcm.ballistic.item;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.block.SubmarineBlock;
import de.rcm.ballistic.entity.PoseidonEntity;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Poseidon nuclear torpedo. Needs a target in the target designator held in the other hand. Used on
 * a ballistic missile submarine it is fired from the boat's bow torpedo tube; used on open water it is
 * put over the side there (from a carrier submarine out of sight).
 */
public class PoseidonItem extends Item {
	private static final double MIN_RANGE = 120.0;

	public PoseidonItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
		if (hit.getType() != HitResult.Type.BLOCK || level.getFluidState(hit.getBlockPos()).isEmpty()) {
			if (!level.isClientSide()) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.poseidon_needs_water").withStyle(ChatFormatting.YELLOW), true);
			}
			return InteractionResult.FAIL;
		}
		if (level instanceof ServerLevel server && player instanceof ServerPlayer sp) {
			BlockPos surface = hit.getBlockPos();
			int depth = 0;
			while (depth < 12 && !server.getFluidState(surface.below(depth + 1)).isEmpty()) {
				depth++;
			}
			if (depth < 5) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.poseidon_too_shallow").withStyle(ChatFormatting.YELLOW), true);
				return InteractionResult.FAIL;
			}
			Vec3 from = Vec3.atCenterOf(surface).add(0, -Math.min(depth, 8) + 1.0, 0);
			launch(server, sp, player.getItemInHand(hand), from, player.getLookAngle());
		}
		return InteractionResult.SUCCESS;
	}

	/** From the submarine's bow tube (called by the submarine block). */
	public static void launchFromSubmarine(ServerLevel level, ServerPlayer player, ItemStack stack, BlockPos sub, BlockState state) {
		Direction facing = state.hasProperty(SubmarineBlock.FACING) ? state.getValue(SubmarineBlock.FACING) : Direction.NORTH;
		Vec3 dir = Vec3.atLowerCornerOf(facing.getUnitVec3i());
		Vec3 bow = Vec3.atBottomCenterOf(sub).add(dir.scale(19.0)).add(0, 2.2, 0);
		if (level.getFluidState(BlockPos.containing(bow)).isEmpty()) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.poseidon_no_water").withStyle(ChatFormatting.YELLOW), true);
			return;
		}
		launch(level, player, stack, bow, dir);
	}

	private static @Nullable TargetData target(Player player) {
		for (InteractionHand h : InteractionHand.values()) {
			ItemStack s = player.getItemInHand(h);
			if (s.is(ModRegistry.TARGET_DESIGNATOR) && s.get(ModRegistry.TARGET) != null) {
				return s.get(ModRegistry.TARGET);
			}
		}
		return null;
	}

	private static boolean launch(ServerLevel level, ServerPlayer player, ItemStack stack, Vec3 from, Vec3 dir) {
		TargetData target = target(player);
		if (target == null) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.poseidon_no_target").withStyle(ChatFormatting.YELLOW), true);
			return false;
		}
		Vec3 aim = Vec3.atBottomCenterOf(target.pos());
		double range = Math.hypot(aim.x - from.x, aim.z - from.z);
		if (range < MIN_RANGE) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.poseidon_too_close", (int) MIN_RANGE).withStyle(ChatFormatting.RED), true);
			return false;
		}
		if (PoseidonEntity.launch(level, from, dir, aim, player) == null) {
			return false;
		}
		if (!player.getAbilities().instabuild) {
			stack.shrink(1);
		}
		level.playSound(null, from.x, from.y, from.z, ModRegistry.AIR_LAUNCH, SoundSource.BLOCKS, 5.0F, 0.9F);
		level.playSound(null, from.x, from.y, from.z, ModRegistry.WATER_SPLASH, SoundSource.BLOCKS, 3.0F, 0.8F);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.TARGET_LOCK, SoundSource.PLAYERS, 1.0F, 0.6F);
		player.displayClientMessage(Component.literal("☢ ").append(Component.translatable("message.ballisticmissiles.poseidon_away", (int) aim.x, (int) aim.z, (int) range))
			.withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD), false);
		return true;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.poseidon.1").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.poseidon.2").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.poseidon.3").withStyle(ChatFormatting.GOLD));
	}
}
