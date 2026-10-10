package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
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
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A 12-gauge pump-action shotgun (Remington 870 pattern): six rounds of 00 buckshot - four in the
 * magazine tube, one in the chamber, one... well, six. Each shell throws nine lead balls of 8.4 mm at
 * about 400 m/s in a cone that opens some 3.5 cm per metre: devastating up close, losing its punch fast
 * with distance. Left click fires, and the slide is racked back and forward for the next shell; right
 * click (held) brings the bead to the eye; R thumbs shells into the tube one at a time (any shot stops
 * the loading), racking the slide at the end if the chamber was empty.
 * <p>
 * The state rides on the stack in {@link GunState}: {@code rounds}, {@code lastShot}; while loading
 * {@code reloadStart} is the tick it began and {@code reloadKind} 1 (shells going in) or 2 (racking
 * the first one into the chamber).
 */
public class ShotgunItem extends Item {
	public static final int CAPACITY = 6;
	public static final int PELLETS = 9;
	/** Ticks from the shot to the slide back (and its sound), to it forward again, to ready to fire. */
	public static final int PUMP_BACK = 4;
	public static final int PUMP_FORWARD = 8;
	public static final int READY = 11;
	/** One shell into the tube: hand to the pouch and back, thumbed in at {@link #SHELL_IN}. */
	public static final int SHELL_CYCLE = 13;
	public static final int SHELL_IN = 10;
	/** Racking the first shell into the chamber after loading an empty gun: back, forward, done. */
	public static final int RACK_BACK = 3;
	public static final int RACK_FORWARD = 8;
	public static final int RACK = 12;
	public static final double MUZZLE_VELOCITY = 20.0;
	/** Half-angle of the shot pattern (radians, one standard deviation). */
	private static final double SPREAD = 0.032;
	/** Each ball's punch against a rifle bullet's: nine of them up close outdo any rifle. */
	public static final float PELLET_DAMAGE = 0.62F;

	public ShotgunItem(Properties properties) {
		super(properties);
	}

	public static GunState state(ItemStack stack) {
		return stack.getOrDefault(ModRegistry.GUN_STATE, GunState.DEFAULT);
	}

	private static void setState(ItemStack stack, GunState state) {
		stack.set(ModRegistry.GUN_STATE, state);
	}

	public static boolean isAiming(Player player) {
		return player.isUsingItem() && player.getUseItem().getItem() instanceof ShotgunItem;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND || state(player.getItemInHand(hand)).reloading()) {
			return InteractionResult.FAIL;
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

	private static void sound(ServerLevel level, Entity at, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, at.getX(), at.getEyeY() - 0.2, at.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
	}

	// ------------------------------------------------------------------ firing

	/** Left click (from the client). */
	public static void fire(ServerPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof ShotgunItem)) {
			return;
		}
		ServerLevel level = player.level();
		long now = level.getGameTime();
		GunState state = state(stack);
		if (now - state.lastShot() < READY) {
			return; // still working the slide
		}
		if (state.reloading()) {
			if (state.reloadKind() == 2) {
				return; // racking the first shell in
			}
			// a shot stops the loading: the shells already in are there to fire
			state = state.cancelReload();
			setState(stack, state);
		}
		if (state.rounds() <= 0) {
			sound(level, player, ModRegistry.SHOTGUN_DRY, 0.8F, 1.0F);
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.shotgun_empty").withStyle(ChatFormatting.RED), true);
			setState(stack, state.fired(0, now - 30)); // no shot, no pump
			return;
		}
		setState(stack, state.fired(state.rounds() - 1, now));
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 muzzle = eye.add(look.scale(1.0)).add(right.scale(0.12)).add(0, -0.1, 0);
		double spread = SPREAD * (isAiming(player) ? 0.85 : 1.1) * (1.0 + 0.6 * de.rcm.ballistic.injury.Injuries.get(player).arm());
		var random = level.getRandom();
		for (int i = 0; i < PELLETS; i++) {
			Vec3 dir = look.add(random.nextGaussian() * spread, random.nextGaussian() * spread, random.nextGaussian() * spread).normalize();
			BulletEntity.firePellet(level, player, eye, dir.scale(MUZZLE_VELOCITY * (0.95 + random.nextDouble() * 0.1)), PELLET_DAMAGE);
		}
		// the blast: each client works out how it sounds where they are (close, far, echoes, rooms)
		var shot = new de.rcm.ballistic.network.ModNetworking.GunshotPayload(player.getId(), muzzle.x, muzzle.y, muzzle.z, (float) look.x, (float) look.y,
			(float) look.z, de.rcm.ballistic.network.ModNetworking.GunshotPayload.SHOTGUN);
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(muzzle) < 900.0 * 900.0) {
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, shot);
			}
		}
		// flash and smoke at the muzzle
		level.sendParticles(ModRegistry.SPARK, true, false, muzzle.x, muzzle.y, muzzle.z, 6, 0.05, 0.05, 0.05, 0.3);
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, muzzle.x + look.x * 0.3, muzzle.y + look.y * 0.3, muzzle.z + look.z * 0.3,
			8, 0.08, 0.08, 0.08, 0.02);
		level.gameEvent(player, net.minecraft.world.level.gameevent.GameEvent.PROJECTILE_SHOOT, muzzle);
		de.rcm.ballistic.ai.SoldierEntity.flash(player);
		de.rcm.ballistic.ai.Senses.noise(level, muzzle, de.rcm.ballistic.ai.Senses.GUNSHOT_RANGE, de.rcm.ballistic.ai.Senses.GUNSHOT, player);
	}

	// ------------------------------------------------------------------ loading

	private static boolean hasShells(Player player) {
		return player.getAbilities().instabuild || player.getInventory().countItem(ModRegistry.SHOTGUN_SHELL) > 0;
	}

	/** R pressed (from the client): start thumbing shells in. */
	public static void load(ServerPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof ShotgunItem)) {
			return;
		}
		GunState state = state(stack);
		long now = player.level().getGameTime();
		if (state.reloading() || state.rounds() >= CAPACITY || now - state.lastShot() < READY) {
			return;
		}
		if (!hasShells(player)) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.shotgun_no_shells").withStyle(ChatFormatting.RED), true);
			return;
		}
		player.stopUsingItem();
		// reloadAmmo: 1 if the chamber was empty, so the first shell has to be racked in afterwards
		setState(stack, new GunState(state.rounds(), state.ammo(), true, state.mode(), state.lastShot(), now, 1, state.rounds() == 0 ? 1 : 0));
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
		if (!(entity instanceof ServerPlayer player)) {
			return;
		}
		GunState state = state(stack);
		long now = level.getGameTime();
		boolean inHand = player.getMainHandItem() == stack;
		// the slide racked after a shot
		if (inHand && now - state.lastShot() == PUMP_BACK) {
			sound(level, player, ModRegistry.SHOTGUN_PUMP_BACK, 1.0F, 0.97F + level.getRandom().nextFloat() * 0.06F);
		} else if (inHand && now - state.lastShot() == PUMP_FORWARD) {
			sound(level, player, ModRegistry.SHOTGUN_PUMP_FORWARD, 1.0F, 0.97F + level.getRandom().nextFloat() * 0.06F);
		} else if (inHand && now - state.lastShot() == PUMP_BACK + 6) {
			// the empty hull lands at your feet
			sound(level, player, ModRegistry.SHOTGUN_HULL, 0.5F, 0.9F + level.getRandom().nextFloat() * 0.2F);
		}
		if (!state.reloading()) {
			return;
		}
		if (!inHand) {
			setState(stack, state.cancelReload()); // put away: loading stops
			return;
		}
		long t = now - state.reloadStart();
		if (state.reloadKind() == 2) {
			if (t == RACK_BACK) {
				sound(level, player, ModRegistry.SHOTGUN_PUMP_BACK, 1.0F, 1.0F);
			} else if (t == RACK_FORWARD) {
				sound(level, player, ModRegistry.SHOTGUN_PUMP_FORWARD, 1.0F, 1.0F);
			} else if (t >= RACK) {
				setState(stack, state.cancelReload());
			}
			return;
		}
		if (t % SHELL_CYCLE == SHELL_IN) {
			if (!hasShells(player) || state.rounds() >= CAPACITY) {
				this.finishLoading(stack, state, now, player);
				return;
			}
			if (!player.getAbilities().instabuild) {
				consumeShell(player);
			}
			state = state.withRounds(state.rounds() + 1);
			setState(stack, state);
			sound(level, player, ModRegistry.SHOTGUN_SHELL_IN, 0.9F, 0.95F + level.getRandom().nextFloat() * 0.1F);
		} else if (t % SHELL_CYCLE == 0 && t > 0 && (state.rounds() >= CAPACITY || !hasShells(player))) {
			this.finishLoading(stack, state, now, player);
		}
	}

	private void finishLoading(ItemStack stack, GunState state, long now, ServerPlayer player) {
		if (state.reloadAmmo() == 1 && state.rounds() > 0) {
			// it was empty: rack the first shell into the chamber
			setState(stack, new GunState(state.rounds(), state.ammo(), true, state.mode(), state.lastShot(), now, 2, 0));
		} else {
			setState(stack, state.cancelReload());
		}
	}

	private static void consumeShell(Player player) {
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(ModRegistry.SHOTGUN_SHELL)) {
				s.shrink(1);
				return;
			}
		}
	}

	@Override
	public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
		return false;
	}

	@Override
	public boolean allowContinuingBlockBreaking(Player player, ItemStack oldStack, ItemStack newStack) {
		return true;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		GunState s = state(stack);
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.shotgun_rounds", s.rounds(), CAPACITY).withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.shotgun").withStyle(ChatFormatting.DARK_GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.shotgun_controls").withStyle(ChatFormatting.DARK_GRAY));
	}
}
