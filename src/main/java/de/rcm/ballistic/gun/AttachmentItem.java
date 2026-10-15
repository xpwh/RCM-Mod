package de.rcm.ballistic.gun;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * A rail attachment - the weapon light or the laser module. Held in one hand with the gun (AK, Glock,
 * shotgun) in the other, a right-click mounts it on the gun.
 */
public class AttachmentItem extends Item {
	private final int kind;

	/** @param kind {@link GunAttachments#LIGHT}, {@link GunAttachments#LASER} or {@link GunAttachments#IR_LASER} */
	public AttachmentItem(Properties properties, int kind) {
		super(properties);
		this.kind = kind;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		InteractionHand other = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		if (!GunAttachments.isGun(player.getItemInHand(other))) {
			if (!level.isClientSide()) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.attachment_need_gun").withStyle(ChatFormatting.YELLOW), true);
			}
			return InteractionResult.FAIL;
		}
		if (player instanceof ServerPlayer server && GunAttachments.mount(server, other, this.kind) && !player.getAbilities().instabuild) {
			player.getItemInHand(hand).shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		String key = this.kind == GunAttachments.LIGHT ? "weapon_light" : this.kind == GunAttachments.IR_LASER ? "ir_laser_module" : "laser_module";
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles." + key).withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.attachment_usage").withStyle(ChatFormatting.GOLD));
	}
}
