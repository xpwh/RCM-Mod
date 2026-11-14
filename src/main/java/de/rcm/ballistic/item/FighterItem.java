package de.rcm.ballistic.item;

import de.rcm.ballistic.entity.FighterEntity;
import de.rcm.ballistic.entity.FighterType;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Places an F-35 or F-22 on the ground, nose the way you face. Find yourself a long straight runway. */
public class FighterItem extends Item {
	private final FighterType type;

	public FighterItem(FighterType type, Properties properties) {
		super(properties);
		this.type = type;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level) || context.getPlayer() == null) {
			return InteractionResult.SUCCESS;
		}
		Player player = context.getPlayer();
		Vec3 at = Vec3.atBottomCenterOf(context.getClickedPos().relative(context.getClickedFace()));
		if (!level.noCollision(new AABB(at.x - 1.8, at.y, at.z - 1.8, at.x + 1.8, at.y + 3.2, at.z + 1.8))) {
			return InteractionResult.FAIL;
		}
		float yaw = player.getYRot();
		if (de.rcm.ballistic.runway.RunwayBuilder.isRunway(level.getBlockState(context.getClickedPos()))) {
			yaw = Math.round(yaw / 90.0F) * 90.0F; // on a runway: lined up on the centreline heading
		}
		FighterEntity.place(level, player, at, yaw, this.type);
		level.playSound(null, at.x, at.y, at.z, de.rcm.ballistic.ModRegistry.METAL_THUD, net.minecraft.sounds.SoundSource.NEUTRAL, 2.0F, 0.6F);
		if (!player.getAbilities().instabuild) {
			context.getItemInHand().shrink(1);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fighter_" + this.type.id).withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fighter_controls_1").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fighter_controls_2").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.fighter_controls_3").withStyle(ChatFormatting.DARK_GRAY));
	}
}
