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
import net.minecraft.world.entity.item.ItemEntity;
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
 * Glock 19: the compact 9x19 mm striker-fired pistol, 15 rounds in the flush magazine or 33 in the long
 * extended one, one more in the chamber. Left click fires one round per pull (as fast as you can work the
 * trigger, the slide cycling in a tenth of a second), right click (held) brings the sights up to the eye in a
 * two-handed grip, R changes magazines (sneak + R the other kind: standard or extended). The last round
 * locks the slide back; a fresh magazine then goes in and the slide is racked overhand to chamber a round.
 * Magazines come out the way they do in a hurry: the release pressed and the magazine dropped to the ground
 * (with what was left in it) - pick them up again afterwards.
 * <p>
 * The state rides on the stack in {@link GunState}: {@code rounds} (magazine plus chamber), {@code ammo} the
 * kind of magazine in the grip, {@code hasMag}, {@code lastShot}; while reloading {@code reloadStart},
 * {@code reloadKind} ({@link GunState#TACTICAL} with a round still chambered, {@link GunState#EMPTY} with the
 * slide locked back) and {@code reloadAmmo} (the kind of magazine going in).
 */
public class GlockItem extends Item {
	public static final int MAG_CAPACITY = 15;
	public static final int EXT_CAPACITY = 33;
	/** Fastest the slide cycles between shots (ticks). */
	public static final int CYCLE = 2;
	/** Reload choreography (ticks after the start): the release pressed, the old magazine falling, the new one in. */
	public static final int T_RELEASE = 4;
	public static final int T_DROP = 6;
	public static final int T_IN = 19;
	public static final int RELOAD_TACTICAL = 27;
	/** From empty: the slide racked overhand - back, and let go - after the magazine is in. */
	public static final int T_RACK_BACK = 25;
	public static final int T_RACK_FORWARD = 28;
	public static final int RELOAD_EMPTY = 35;
	/** Muzzle velocity, blocks per tick (375 m/s). */
	public static final double MUZZLE_VELOCITY = 18.75;

	public GlockItem(Properties properties) {
		super(properties);
	}

	public static GunState state(ItemStack stack) {
		return stack.getOrDefault(ModRegistry.GUN_STATE, GunState.DEFAULT);
	}

	public static void setState(ItemStack stack, GunState state) {
		stack.set(ModRegistry.GUN_STATE, state);
	}

	/** A pistol as it comes: a full standard magazine in it and a round chambered. */
	public static ItemStack loaded(Item item, int kind) {
		ItemStack stack = new ItemStack(item);
		setState(stack, GunState.DEFAULT.loaded(GlockMagazineItem.capacity(kind) + 1, kind));
		return stack;
	}

	/** The slide is locked back: the last round has gone (and stays so until a magazine is in and it is racked). */
	public static boolean slideLocked(GunState state) {
		return state.rounds() <= 0 && state.lastShot() > 0;
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

	public static boolean isAiming(Player player) {
		return player.isUsingItem() && player.getUseItem().getItem() instanceof GlockItem;
	}

	private static void sound(ServerLevel level, Entity at, SoundEvent sound, float volume) {
		level.playSound(null, at.getX(), at.getEyeY() - 0.3, at.getZ(), sound, SoundSource.PLAYERS, volume, 0.96F + level.getRandom().nextFloat() * 0.08F);
	}

	// ------------------------------------------------------------------ the trigger

	/** One pull of the trigger (from the client). */
	public static void fire(ServerPlayer player) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof GlockItem)) {
			return;
		}
		ServerLevel level = player.level();
		long now = level.getGameTime();
		GunState state = state(stack);
		if (state.reloading() || now - state.lastShot() < CYCLE) {
			return;
		}
		if (state.rounds() <= 0) {
			// the striker is not set: the trigger goes back dead
			sound(level, player, ModRegistry.GLOCK_DRY, 0.7F);
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.glock_empty").withStyle(ChatFormatting.RED), true);
			return;
		}
		int left = state.rounds() - 1;
		setState(stack, state.fired(left, now));
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 muzzle = eye.add(look.scale(0.6)).add(right.scale(0.1)).add(0, -0.12, 0);
		// the round goes where the sights are: from the eye at what it looks at
		Vec3 far = eye.add(look.scale(200.0));
		var sight = level.clip(new net.minecraft.world.level.ClipContext(eye, far, net.minecraft.world.level.ClipContext.Block.COLLIDER,
			net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		Vec3 target = sight.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? far : sight.getLocation();
		var body = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, player, eye, target,
			new net.minecraft.world.phys.AABB(eye, target).inflate(1.0), e -> e.isPickable() && !e.isSpectator() && e != player, 0.0F);
		if (body != null) {
			target = body.getLocation();
		}
		if (target.distanceToSqr(eye) < 4.0) {
			target = eye.add(look.scale(2.0));
		}
		Vec3 aim = target.subtract(eye).normalize();
		// a short barrel and a light gun: shots fired fast off target wander, aimed ones go true
		long since = now - state.lastShot();
		double spread = 0.0016 + (since < 6 ? 0.0028 * (6 - since) / 4.0 : 0.0) + (player.onGround() ? 0.0 : 0.008);
		spread *= isAiming(player) ? 0.4 : 1.25;
		spread *= 1.0 + 0.9 * de.rcm.ballistic.injury.Injuries.get(player).arm();
		var random = level.getRandom();
		Vec3 dir = aim.add(random.nextGaussian() * spread, random.nextGaussian() * spread, random.nextGaussian() * spread).normalize();
		BulletEntity.firePistol(level, player, eye, dir.scale(MUZZLE_VELOCITY));
		GunshotPayload shot = new GunshotPayload(player.getId(), muzzle.x, muzzle.y, muzzle.z, (float) dir.x, (float) dir.y, (float) dir.z,
			GunshotPayload.PISTOL | (left == 0 ? GunshotPayload.LAST : 0) | (GunAttachments.suppressed(player) ? GunshotPayload.SUPPRESSED : 0));
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(muzzle) < 900.0 * 900.0) {
				ServerPlayNetworking.send(p, shot);
			}
		}
		level.gameEvent(player, net.minecraft.world.level.gameevent.GameEvent.PROJECTILE_SHOOT, muzzle);
		if (!de.rcm.ballistic.gun.GunAttachments.suppressed(player)) {
			de.rcm.ballistic.ai.SoldierEntity.flash(player); // a suppressor hides the flash too
		}
		de.rcm.ballistic.ai.Senses.noise(level, muzzle, de.rcm.ballistic.ai.Senses.shotRange(player), de.rcm.ballistic.ai.Senses.GUNSHOT, player);
	}

	// ------------------------------------------------------------------ magazines

	/** R pressed (sneak: the other kind of magazine). */
	public static void requestReload(ServerPlayer player, boolean switchKind) {
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof GlockItem)) {
			return;
		}
		GunState state = state(stack);
		if (state.reloading()) {
			return;
		}
		int current = state.hasMag() ? state.ammo() : GlockMagazineItem.STANDARD;
		int want = switchKind ? 1 - current : current;
		ItemStack mag = findMagazine(player, want);
		if (mag.isEmpty() && !switchKind) {
			want = 1 - want; // none of this kind left: the other one
			mag = findMagazine(player, want);
		}
		if (mag.isEmpty() && !player.getAbilities().instabuild) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.glock_no_mags").withStyle(ChatFormatting.RED), true);
			return;
		}
		if (state.hasMag() && want == state.ammo() && state.rounds() >= GlockMagazineItem.capacity(want) + 1) {
			return; // full already
		}
		player.stopUsingItem();
		int kind = state.rounds() > 0 ? GunState.TACTICAL : GunState.EMPTY;
		setState(stack, state.startReload(player.level().getGameTime(), kind, want));
	}

	/** The fullest magazine of {@code kind} in the inventory (not counting empties). */
	private static ItemStack findMagazine(Player player, int kind) {
		Inventory inv = player.getInventory();
		ItemStack best = ItemStack.EMPTY;
		int bestRounds = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof GlockMagazineItem mag && mag.kind() == kind) {
				int r = GlockMagazineItem.rounds(s);
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
			setState(stack, state.cancelReload()); // holstered mid-reload: start again next time
			return;
		}
		long t = level.getGameTime() - state.reloadStart();
		boolean empty = state.reloadKind() == GunState.EMPTY;
		if (t == T_RELEASE && state.hasMag()) {
			sound(level, player, ModRegistry.GLOCK_MAG_OUT, 1.0F);
		} else if (t == T_DROP && state.hasMag()) {
			// let go: it drops out of the grip and falls where you stand, with what was left in it
			this.dropMagazine(level, player, state.ammo(), Math.max(0, state.rounds() - (empty ? 0 : 1)));
			setState(stack, new GunState(empty ? 0 : 1, state.ammo(), false, state.mode(), state.lastShot(), state.reloadStart(), state.reloadKind(),
				state.reloadAmmo()));
			state = state(stack);
		} else if (t == T_IN) {
			sound(level, player, ModRegistry.GLOCK_MAG_IN, 1.0F);
		} else if (empty && t == T_RACK_BACK) {
			sound(level, player, ModRegistry.GLOCK_SLIDE_BACK, 0.9F);
		} else if (empty && t == T_RACK_FORWARD) {
			sound(level, player, ModRegistry.GLOCK_SLIDE_FORWARD, 1.0F);
		}
		if (t == T_IN) {
			// the new magazine is in: take it out of the inventory now
			int rounds;
			if (player.getAbilities().instabuild) {
				rounds = GlockMagazineItem.capacity(state.reloadAmmo());
			} else {
				ItemStack mag = findMagazine(player, state.reloadAmmo());
				if (mag.isEmpty()) {
					setState(stack, state.cancelReload());
					return;
				}
				rounds = GlockMagazineItem.rounds(mag);
				mag.shrink(1);
			}
			// the round in the chamber stays; from empty it is only chambered by the rack (the slide's still back)
			int chambered = empty ? 0 : Math.min(1, state.rounds());
			setState(stack, new GunState(rounds + chambered, state.reloadAmmo(), true, state.mode(), state.lastShot(), state.reloadStart(),
				state.reloadKind(), state.reloadAmmo()));
			state = state(stack);
		}
		if (t >= (empty ? RELOAD_EMPTY : RELOAD_TACTICAL)) {
			// racked: the slide comes off its stop and the shooting can begin again
			setState(stack, new GunState(state.rounds(), state.ammo(), state.hasMag(), state.mode(), empty ? -100L : state.lastShot(), -1L, 0,
				state.reloadAmmo()));
		}
	}

	private void dropMagazine(ServerLevel level, Player player, int kind, int rounds) {
		if (player.getAbilities().instabuild) {
			return;
		}
		ItemStack old = new ItemStack(GlockMagazineItem.of(kind));
		GlockMagazineItem.setRounds(old, rounds);
		Vec3 look = Vec3.directionFromRotation(0.0F, player.getYRot());
		Vec3 right = new Vec3(-look.z, 0.0, look.x);
		Vec3 at = player.position().add(0.0, player.getBbHeight() * 0.6, 0.0).add(look.scale(0.4)).add(right.scale(0.1));
		ItemEntity item = new ItemEntity(level, at.x, at.y, at.z, old);
		var random = level.getRandom();
		item.setDeltaMovement(look.x * 0.04 + random.triangle(0.0, 0.02), -0.05, look.z * 0.04 + random.triangle(0.0, 0.02));
		item.setPickUpDelay(30);
		level.addFreshEntity(item);
		MagazineLanding.watch(item);
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
		GunState s = state(stack);
		return Math.round(13.0F * Mth.clamp(s.rounds() / (float) (GlockMagazineItem.capacity(s.ammo()) + 1), 0.0F, 1.0F));
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return 0xE0C060;
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		GunState s = state(stack);
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.glock.rounds", s.rounds(), GlockMagazineItem.capacity(s.ammo()),
			Component.translatable(s.ammo() == GlockMagazineItem.EXTENDED ? "tooltip.ballisticmissiles.glock.extended" : "tooltip.ballisticmissiles.glock.standard"))
			.withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.glock.1").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.glock.2").withStyle(ChatFormatting.GRAY));
	}
}
