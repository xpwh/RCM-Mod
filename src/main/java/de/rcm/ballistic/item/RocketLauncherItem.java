package de.rcm.ballistic.item;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.RpgRocketEntity;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * RPG-7 shoulder-fired rocket launcher. Hold right-click to shoulder it and aim through the sights,
 * left-click fires the PG-7V grenade sitting in the muzzle (from the hip it goes wide);
 * the open tube blows a cone of fire and smoke out of the back (stand clear!). Held in the hand, it
 * reloads itself from the PG-7V rounds in your inventory after a moment. Whether a round is loaded
 * is kept in the stack's custom model data, so the model shows the warhead in the muzzle or not.
 */
public class RocketLauncherItem extends Item {
	/** Ticks from firing until the next round is in: the client's reload animation is timed to it. */
	public static final int RELOAD_TICKS = 32;
	/** Set by the client: recoil animation, kick and shake when the local player fires. */
	public static Runnable clientFired = () -> {
	};

	public RocketLauncherItem(Properties properties) {
		super(properties);
	}

	public static boolean isLoaded(ItemStack stack) {
		CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		return data != null && Boolean.TRUE.equals(data.getBoolean(0));
	}

	public static void setLoaded(ItemStack stack, boolean loaded) {
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(loaded), List.of(), List.of()));
	}

	/** Whether the player has a round to load (or needs none, in creative). */
	public static boolean hasAmmo(Player player) {
		return player.getAbilities().instabuild || player.getInventory().contains(s -> s.is(ModRegistry.RPG_ROCKET));
	}

	/** Right-click (held): shoulder the launcher and aim through the sights. */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
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

	/** Whether the player is holding the launcher up to the eye. */
	public static boolean isAiming(Player player) {
		return player.isUsingItem() && player.getUseItem().getItem() instanceof RocketLauncherItem;
	}

	/** Left-click, on the client: fire at once (the server confirms) or click on an empty tube. */
	public static boolean clientTrigger(Player player) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof RocketLauncherItem) || player.getCooldowns().isOnCooldown(stack)) {
			return false;
		}
		if (!isLoaded(stack)) {
			return true; // the server answers with the click and the message
		}
		player.getCooldowns().addCooldown(stack, RELOAD_TICKS);
		setLoaded(stack, false); // the warhead leaves the muzzle at once
		clientFired.run();
		return true;
	}

	/** Left-click, on the server. */
	public static void serverTrigger(ServerPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof RocketLauncherItem launcher)) {
			return;
		}
		ServerLevel level = player.level();
		if (!isLoaded(stack)) {
			if (!player.getCooldowns().isOnCooldown(stack)) {
				level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6F, 1.6F);
				if (!hasAmmo(player)) {
					player.displayClientMessage(Component.translatable("message.ballisticmissiles.rpg_no_ammo").withStyle(ChatFormatting.YELLOW), true);
				}
			}
			return;
		}
		player.getCooldowns().addCooldown(stack, RELOAD_TICKS);
		setLoaded(stack, false);
		Vec3 look = player.getLookAngle();
		if (!isAiming(player)) {
			// fired from the hip: nowhere near where you were looking
			var random = player.getRandom();
			look = look.add(random.triangle(0.0, 0.07), random.triangle(0.0, 0.07), random.triangle(0.0, 0.07)).normalize();
		}
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		boolean leftHand = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT;
		Vec3 shoulder = player.getEyePosition().add(right.scale(leftHand ? -0.28 : 0.28)).add(0, -0.12, 0);
		RpgRocketEntity.fire(level, player, shoulder.add(look.scale(1.1)), look);
		launcher.muzzleAndBackblast(level, player, shoulder, look);
	}

	private void muzzleAndBackblast(ServerLevel level, Player player, Vec3 shoulder, Vec3 look) {
		Vec3 muzzle = shoulder.add(look.scale(1.0));
		level.sendParticles(net.minecraft.core.particles.ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFD890), muzzle.x, muzzle.y, muzzle.z, 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z, 12, 0.1, 0.1, 0.1, 0.03);
		// back-blast: the recoilless launch vents a jet of hot gas and burning powder backwards
		Vec3 back = look.scale(-1);
		for (int i = 0; i < 6; i++) {
			Vec3 p = shoulder.add(back.scale(0.7 + i * 0.55));
			double spread = 0.08 + i * 0.09;
			level.sendParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 6, spread, spread, spread, 0.04);
			level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 4, spread, spread, spread, 0.03);
			if (i < 3) {
				level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 5, spread * 0.6, spread * 0.6, spread * 0.6, 0.05);
			}
		}
		level.sendParticles(ParticleTypes.POOF, player.getX(), player.getY() + 0.1, player.getZ(), 20, 0.8, 0.05, 0.8, 0.06);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(shoulder, shoulder).inflate(5.0), e -> e != player && e.isAlive())) {
			Vec3 to = e.getBoundingBox().getCenter().subtract(shoulder);
			double dist = to.length();
			if (dist < 5.0 && to.normalize().dot(back) > 0.75) {
				float damage = (float) (10.0 * (1.0 - dist / 5.0)) + 2.0F;
				e.hurtServer(level, level.damageSources().explosion(player, player), damage);
				e.igniteForSeconds(3.0F);
				e.push(back.x * 0.8, 0.25, back.z * 0.8);
			}
		}
		level.playSound(null, shoulder.x, shoulder.y, shoulder.z, ModRegistry.SAM_LAUNCH, SoundSource.PLAYERS, 3.0F, 1.55F);
		level.playSound(null, shoulder.x, shoulder.y, shoulder.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.4F, 1.7F);
		level.playSound(null, shoulder.x, shoulder.y, shoulder.z, SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 2.0F, 0.5F);
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
		if (!(entity instanceof Player player) || slot == null || slot.getType() != EquipmentSlot.Type.HAND || isLoaded(stack) || player.getCooldowns().isOnCooldown(stack)) {
			return;
		}
		if (!player.getAbilities().instabuild) {
			ItemStack ammo = findAmmo(player);
			if (ammo.isEmpty()) {
				return;
			}
			ammo.shrink(1);
		}
		setLoaded(stack, true);
		// the grenade slid into the muzzle until the stop clicks, the hammer cocked
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.CROSSBOW_LOADING_END.value(), SoundSource.PLAYERS, 1.0F, 0.7F);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.8F, 0.8F);
	}

	private static ItemStack findAmmo(Player player) {
		var inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			if (s.is(ModRegistry.RPG_ROCKET)) {
				return s;
			}
		}
		return ItemStack.EMPTY;
	}

	@Override
	public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
		return false; // loading and firing have their own animation
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable(isLoaded(stack) ? "tooltip.ballisticmissiles.rocket_launcher.loaded" : "tooltip.ballisticmissiles.rocket_launcher.empty")
			.withStyle(isLoaded(stack) ? ChatFormatting.GREEN : ChatFormatting.RED));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.rocket_launcher.1").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.rocket_launcher.2").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.rocket_launcher.3").withStyle(ChatFormatting.GOLD));
	}
}
