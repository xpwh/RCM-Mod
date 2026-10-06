package de.rcm.ballistic.item;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.entity.JetEntity;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Field radio for close air support. Right-click to call a strike fighter onto the block you are
 * looking at; with a target designator in the other hand it uses the designator's stored target
 * instead (so you can order strikes far beyond what you can see). The jet arrives from behind you
 * and carpet-bombs a strip centred on the target.
 */
public class AirstrikeRadioItem extends Item {
	public static final int COOLDOWN_TICKS = 45 * 20;
	private static final double LOOK_RANGE = 320.0;
	private static final double DANGER_CLOSE = 24.0;

	public AirstrikeRadioItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.SUCCESS;
		}
		if (EmpManager.isJammed(level, player.blockPosition())) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.jammed", EmpManager.jammedTicks(level, player.blockPosition()) / 20)
				.withStyle(ChatFormatting.DARK_PURPLE), true);
			return InteractionResult.FAIL;
		}
		Vec3 target = this.findTarget(server, player, hand);
		if (target == null) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.airstrike_no_target").withStyle(ChatFormatting.YELLOW), true);
			return InteractionResult.FAIL;
		}
		double distance = Math.hypot(target.x - player.getX(), target.z - player.getZ());
		if (distance < DANGER_CLOSE) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.airstrike_danger_close", (int) DANGER_CLOSE).withStyle(ChatFormatting.RED), true);
			return InteractionResult.FAIL;
		}
		JetEntity jet = JetEntity.callIn(server, serverPlayer, target);
		if (jet == null) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.airstrike_out_of_range", (int) JetEntity.MAX_RANGE).withStyle(ChatFormatting.RED), true);
			return InteractionResult.FAIL;
		}
		ItemStack stack = player.getItemInHand(hand);
		if (!player.getAbilities().instabuild) {
			player.getCooldowns().addCooldown(stack, COOLDOWN_TICKS);
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.PLAYERS, 0.8F, 0.6F);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.TARGET_LOCK, SoundSource.PLAYERS, 1.0F, 0.8F);
		player.displayClientMessage(Component.literal("✈ ").append(Component.translatable("message.ballisticmissiles.airstrike_called",
				(int) target.x, (int) target.y, (int) target.z, (int) distance, jet.etaSeconds()))
			.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
		return InteractionResult.SUCCESS;
	}

	private Vec3 findTarget(ServerLevel level, Player player, InteractionHand hand) {
		ItemStack other = player.getItemInHand(hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
		TargetData stored = other.is(ModRegistry.TARGET_DESIGNATOR) ? other.get(ModRegistry.TARGET) : null;
		if (stored != null) {
			Vec3 t = Vec3.atBottomCenterOf(stored.pos());
			if (stored.surface()) {
				// height unknown: aim for sea level, the jet's terrain following keeps it clear of hills
				t = new Vec3(t.x, Math.max(t.y, level.getSeaLevel()), t.z);
			}
			return t;
		}
		HitResult hit = player.pick(LOOK_RANGE, 1.0F, false);
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			return Vec3.atBottomCenterOf(block.getBlockPos().above());
		}
		return null;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.airstrike_1").withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.airstrike_2").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.airstrike_3", COOLDOWN_TICKS / 20).withStyle(ChatFormatting.DARK_GRAY));
	}
}
