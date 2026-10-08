package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.network.ModNetworking.GunshotPayload;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * AKM assault rifle, 7.62x39 mm, 600 rounds a minute. Hold right-click to fire (automatic or
 * single shots), R to change magazines (sneak + R loads the other kind of ammunition), V to work the
 * fire selector (safe / auto / single). The magazine you take out goes back into your inventory with
 * whatever it still holds; with the chamber empty the bolt has to be charged after the new magazine
 * is in.
 */
public class AkItem extends Item {
	public static final int MAG_CAPACITY = 30;
	/** Ticks between shots in automatic fire (600 rounds per minute). */
	public static final int CYCLE = 2;
	public static final int RELOAD_TACTICAL = 52;
	public static final int RELOAD_EMPTY = 72;
	/** Reload choreography (ticks after the start): magazine out, new magazine in, bolt charged. */
	public static final int T_MAG_OUT = 12;
	public static final int T_MAG_IN = 30;
	public static final int T_CHARGE = 52;
	/** Muzzle velocity, blocks per tick (715 m/s). */
	public static final double MUZZLE_VELOCITY = 35.75;

	/** Client: the local player just fired (recoil, flash, shells, sound). */
	public static ShotHook clientShot = (player, state) -> {
	};

	public interface ShotHook {
		void shot(Player player, GunState state);
	}

	public AkItem(Properties properties) {
		super(properties);
	}

	public static GunState state(ItemStack stack) {
		return stack.getOrDefault(ModRegistry.GUN_STATE, GunState.DEFAULT);
	}

	public static void setState(ItemStack stack, GunState state) {
		stack.set(ModRegistry.GUN_STATE, state);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		GunState state = state(stack);
		if (hand != InteractionHand.MAIN_HAND || state.reloading()) {
			return InteractionResult.FAIL;
		}
		if (state.mode() == GunState.SAFE) {
			if (!level.isClientSide()) {
				level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_SELECTOR, SoundSource.PLAYERS, 0.4F, 1.3F);
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.ak_safe").withStyle(ChatFormatting.YELLOW), true);
			}
			return InteractionResult.FAIL;
		}
		if (state.rounds() <= 0) {
			if (!level.isClientSide()) {
				level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_DRY, SoundSource.PLAYERS, 0.7F, 1.0F);
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.ak_empty").withStyle(ChatFormatting.RED), true);
			}
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

	@Override
	public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
		if (!(entity instanceof Player player)) {
			return;
		}
		int held = this.getUseDuration(stack, entity) - remaining;
		GunState state = state(stack);
		boolean trigger = state.mode() == GunState.AUTO ? held % CYCLE == 0 : held == 0;
		if (!trigger || state.reloading() || state.mode() == GunState.SAFE) {
			return;
		}
		if (state.rounds() <= 0) {
			if (held > 0 && !level.isClientSide()) {
				// the hammer falls on an empty chamber
				level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_DRY, SoundSource.PLAYERS, 0.7F, 1.0F);
			}
			player.releaseUsingItem();
			return;
		}
		if (level.isClientSide()) {
			clientShot.shot(player, state);
			return;
		}
		ServerLevel server = (ServerLevel) level;
		this.fire(server, player, stack, state, held / CYCLE);
	}

	private void fire(ServerLevel level, Player player, ItemStack stack, GunState state, int burst) {
		int left = state.rounds() - 1;
		long now = level.getGameTime();
		setState(stack, state.fired(left, now));
		Vec3 look = player.getLookAngle();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 muzzle = player.getEyePosition().add(look.scale(0.9)).add(right.scale(0.12)).add(0, -0.1, 0);
		// a long burst walks: the barrel climbs and wanders, the spread opens up
		double spread = 0.0035 + Math.min(burst, 12) * 0.0018 + (player.isCrouching() ? -0.0015 : 0.0) + (player.onGround() ? 0.0 : 0.012);
		var random = level.getRandom();
		Vec3 dir = look.add(random.nextGaussian() * spread, random.nextGaussian() * spread, random.nextGaussian() * spread).normalize();
		boolean tracer = state.ammo() == GunState.TRACER;
		BulletEntity.fire(level, player, muzzle, dir.scale(MUZZLE_VELOCITY), tracer);
		GunshotPayload shot = new GunshotPayload(player.getId(), muzzle.x, muzzle.y, muzzle.z, (float) look.x, (float) look.y, (float) look.z, left == 0);
		for (ServerPlayer p : level.players()) {
			if (p != player && p.position().distanceToSqr(muzzle) < 900.0 * 900.0) {
				ServerPlayNetworking.send(p, shot);
			}
		}
		level.gameEvent(player, net.minecraft.world.level.gameevent.GameEvent.PROJECTILE_SHOOT, muzzle);
		if (left == 0) {
			player.releaseUsingItem(); // the bolt stays forward on an empty chamber: the next pull just clicks
		}
	}

	// ------------------------------------------------------------------ reload and selector

	/** R pressed (sneak: change ammunition type). Called on the server. */
	public static void requestReload(ServerPlayer player, boolean switchType) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof AkItem)) {
			return;
		}
		GunState state = state(stack);
		if (state.reloading()) {
			return;
		}
		int want = switchType ? 1 - state.ammo() : state.ammo();
		if (!state.hasMag()) {
			want = switchType ? GunState.TRACER : GunState.BALL;
		}
		ItemStack mag = findMagazine(player, want);
		if (mag.isEmpty()) {
			want = 1 - want;
			mag = findMagazine(player, want);
		}
		if (mag.isEmpty() && !player.getAbilities().instabuild) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.ak_no_mags").withStyle(ChatFormatting.RED), true);
			return;
		}
		if (state.hasMag() && want == state.ammo() && state.rounds() >= MAG_CAPACITY + 1) {
			return; // full already
		}
		player.stopUsingItem();
		int kind = state.rounds() > 0 ? GunState.TACTICAL : GunState.EMPTY;
		setState(stack, state.startReload(player.level().getGameTime(), kind, want));
	}

	/** V pressed: safe -> auto -> single -> safe. */
	public static void cycleMode(ServerPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof AkItem)) {
			return;
		}
		GunState state = state(stack);
		int next = switch (state.mode()) {
			case GunState.SAFE -> GunState.AUTO;
			case GunState.AUTO -> GunState.SEMI;
			default -> GunState.SAFE;
		};
		setState(stack, state.withMode(next));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_SELECTOR, SoundSource.PLAYERS, 0.7F, 1.0F);
		player.displayClientMessage(Component.translatable("message.ballisticmissiles.ak_mode_" + next).withStyle(ChatFormatting.AQUA), true);
	}

	/** The fullest magazine of {@code type} in the inventory (not counting empties). */
	private static ItemStack findMagazine(Player player, int type) {
		Inventory inv = player.getInventory();
		ItemStack best = ItemStack.EMPTY;
		int bestRounds = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof AkMagazineItem mag && mag.tracer() == (type == GunState.TRACER)) {
				int r = AkMagazineItem.rounds(s);
				if (r > bestRounds) {
					best = s;
					bestRounds = r;
				}
			}
		}
		return best;
	}

	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
		GunState state = state(stack);
		if (!state.reloading() || !(entity instanceof Player player)) {
			return;
		}
		if (slot != EquipmentSlot.MAINHAND) {
			setState(stack, state.cancelReload()); // put away mid-reload: start again next time
			return;
		}
		long t = level.getGameTime() - state.reloadStart();
		int duration = state.reloadKind() == GunState.EMPTY ? RELOAD_EMPTY : RELOAD_TACTICAL;
		if (t == T_MAG_OUT && state.hasMag()) {
			this.sound(level, player, ModRegistry.AK_MAG_OUT, 1.0F);
		} else if (t == T_MAG_IN) {
			this.sound(level, player, ModRegistry.AK_MAG_IN, 1.0F);
		} else if (t == T_CHARGE && state.reloadKind() == GunState.EMPTY) {
			this.sound(level, player, ModRegistry.AK_CHARGE, 1.0F);
		}
		if (t < duration) {
			return;
		}
		// swap the magazines now
		int newRounds;
		if (player.getAbilities().instabuild) {
			newRounds = MAG_CAPACITY;
		} else {
			ItemStack mag = findMagazine(player, state.reloadAmmo());
			if (mag.isEmpty()) {
				setState(stack, state.cancelReload());
				return;
			}
			newRounds = AkMagazineItem.rounds(mag);
			mag.shrink(1);
		}
		if (state.hasMag()) {
			int inOld = state.rounds() > 0 ? state.rounds() - 1 : 0;
			ItemStack old = new ItemStack(state.ammo() == GunState.TRACER ? ModRegistry.AK_MAG_TRACER : ModRegistry.AK_MAG);
			AkMagazineItem.setRounds(old, inOld);
			if (!player.getAbilities().instabuild && !player.getInventory().add(old)) {
				player.drop(old, false);
			}
		}
		int chambered = state.reloadKind() == GunState.TACTICAL ? 1 : 0;
		setState(stack, state.loaded(newRounds + chambered, state.reloadAmmo()));
	}

	private void sound(ServerLevel level, Player player, SoundEvent sound, float volume) {
		level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), sound, SoundSource.PLAYERS, volume, 0.96F + level.getRandom().nextFloat() * 0.08F);
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
	public boolean isBarVisible(ItemStack stack) {
		return state(stack).hasMag();
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		return Math.round(13.0F * Mth.clamp(state(stack).rounds() / (float) (MAG_CAPACITY + 1), 0.0F, 1.0F));
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return state(stack).ammo() == GunState.TRACER ? 0x66FF55 : 0xE0C060;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		GunState s = state(stack);
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.ak.rounds", s.rounds(), MAG_CAPACITY,
			Component.translatable(s.ammo() == GunState.TRACER ? "tooltip.ballisticmissiles.ak.tracer" : "tooltip.ballisticmissiles.ak.ball"),
			Component.translatable("message.ballisticmissiles.ak_mode_" + s.mode())).withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.ak.1").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.ak.2").withStyle(ChatFormatting.GRAY));
	}
}
