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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * AKM assault rifle, 7.62x39 mm, 600 rounds a minute. Hold right-click to aim over the sights,
 * left-click is the trigger (automatic or single shots), R to change magazines (sneak + R loads the other kind of ammunition), V to work the
 * fire selector (safe / auto / single). The magazine you take out goes back into your inventory with
 * whatever it still holds; with the chamber empty the bolt has to be charged after the new magazine
 * is in.
 */
public class AkItem extends Item {
	public static final int MAG_CAPACITY = 30;
	/** Ticks between shots in automatic fire (600 rounds per minute). */
	public static final int CYCLE = 2;
	public static final int RELOAD_TACTICAL = 38;
	public static final int RELOAD_EMPTY = 72;
	/** Reload choreography (ticks after the start): magazine out, new magazine in, bolt charged. */
	public static final int T_MAG_OUT = 12;
	/** A speed reload lets the empty magazine go here (just out of the well). */
	public static final int T_MAG_DROP = 15;
	public static final int T_MAG_IN = 30;
	public static final int T_CHARGE = 52;
	/**
	 * A reload with rounds left goes magazine to magazine: the fresh one comes up from behind and knocks
	 * the old one out of the well and away forward at {@code T_TAC_OUT} (it lands on the ground, with
	 * the rounds still in it), and goes straight in, seated at {@code T_TAC_IN}.
	 */
	public static final int T_TAC_OUT = 14;
	public static final int T_TAC_IN = 21;
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

	/** Right-click (held): shoulder the rifle and look over the sights. */
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

	/** Whether the player has the rifle up at the eye. */
	public static boolean isAiming(Player player) {
		return player.isUsingItem() && player.getUseItem().getItem() instanceof AkItem;
	}

	// ------------------------------------------------------------------ the trigger (left mouse button)

	private static final java.util.Map<java.util.UUID, long[]> TRIGGERS = new java.util.HashMap<>();

	/** A player left or the server stopped: nobody's finger stays on the trigger. */
	public static void release(java.util.@org.jspecify.annotations.Nullable UUID player) {
		if (player == null) {
			TRIGGERS.clear();
		} else {
			TRIGGERS.remove(player);
		}
	}

	/** The trigger pressed or let go (from the client). */
	public static void trigger(ServerPlayer player, boolean down) {
		if (!down) {
			TRIGGERS.remove(player.getUUID());
			return;
		}
		if (TRIGGERS.containsKey(player.getUUID())) {
			return; // still held: a repeated "pressed" must not restart the burst (that would fire every tick)
		}
		ItemStack stack = player.getMainHandItem();
		if (!(stack.getItem() instanceof AkItem)) {
			return;
		}
		GunState state = state(stack);
		ServerLevel level = player.level();
		if (state.reloading()) {
			return;
		}
		if (state.mode() == GunState.SAFE) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_SELECTOR, SoundSource.PLAYERS, 0.4F, 1.3F);
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.ak_safe").withStyle(ChatFormatting.YELLOW), true);
			return;
		}
		if (state.rounds() <= 0) {
			level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_DRY, SoundSource.PLAYERS, 0.7F, 1.0F);
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.ak_empty").withStyle(ChatFormatting.RED), true);
			return;
		}
		TRIGGERS.put(player.getUUID(), new long[] {level.getGameTime()});
	}

	/** Server, every tick while in the main hand: the hammer falls as long as the trigger is held. */
	private void triggerTick(ServerLevel level, ServerPlayer player, ItemStack stack) {
		long[] down = TRIGGERS.get(player.getUUID());
		if (down == null) {
			return;
		}
		GunState state = state(stack);
		long held = level.getGameTime() - down[0];
		boolean drop = state.mode() == GunState.AUTO ? held % CYCLE == 0 : held == 0;
		if (state.reloading() || state.mode() == GunState.SAFE) {
			TRIGGERS.remove(player.getUUID());
			return;
		}
		if (!drop) {
			return;
		}
		if (level.getGameTime() - state.lastShot() < CYCLE) {
			return; // the bolt has not come back yet: no faster than the rifle can cycle, however the trigger is worked
		}
		if (state.rounds() <= 0) {
			// the hammer falls on an empty chamber
			level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_DRY, SoundSource.PLAYERS, 0.7F, 1.0F);
			TRIGGERS.remove(player.getUUID());
			return;
		}
		this.fire(level, player, stack, state, (int) (held / CYCLE));
	}

	private void fire(ServerLevel level, ServerPlayer player, ItemStack stack, GunState state, int burst) {
		int left = state.rounds() - 1;
		long now = level.getGameTime();
		setState(stack, state.fired(left, now));
		Vec3 look = player.getLookAngle();
		Vec3 right = look.cross(new Vec3(0, 1, 0));
		right = right.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : right.normalize();
		Vec3 muzzle = player.getEyePosition().add(look.scale(0.9)).add(right.scale(0.12)).add(0, -0.1, 0);
		// the round goes where the crosshair is: aimed from the muzzle at what the eye is looking at
		Vec3 eye = player.getEyePosition();
		Vec3 far = eye.add(look.scale(300.0));
		net.minecraft.world.phys.BlockHitResult sight = level.clip(new net.minecraft.world.level.ClipContext(eye, far,
			net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		Vec3 target = sight.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? far : sight.getLocation();
		net.minecraft.world.phys.EntityHitResult body = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(level, player, eye, target,
			new net.minecraft.world.phys.AABB(eye, target).inflate(1.0), e -> e.isPickable() && !e.isSpectator() && e != player, 0.0F);
		if (body != null) {
			target = body.getLocation();
		}
		if (target.distanceToSqr(eye) < 4.0) {
			target = eye.add(look.scale(2.0));
		}
		// the round flies from the eye along the line the sights are on (the muzzle only gives the flash
		// and the tracer): a target right in front of you - closer than the barrel is long - is still hit
		Vec3 aim = target.subtract(eye).normalize();
		// spread: the first round of a burst goes true, then the barrel climbs and wanders a little
		double spread = 0.0010 + Math.min(burst, 10) * 0.0006 + (player.isCrouching() ? -0.0004 : 0.0) + (player.onGround() ? 0.0 : 0.006);
		spread *= isAiming(player) ? 0.35 : 1.15;
		var random = level.getRandom();
		Vec3 dir = aim.add(random.nextGaussian() * spread, random.nextGaussian() * spread, random.nextGaussian() * spread).normalize();
		// a tracer magazine is loaded the way soldiers load them: every fourth round a tracer, and the
		// last three all tracers - so the stream shows where the fire goes, and the magazine running dry
		// shows in the shooter's own fire before the hammer falls on nothing
		boolean tracer = state.ammo() == GunState.TRACER && (state.rounds() % 4 == 0 || state.rounds() <= 3);
		shoot(level, player, eye, muzzle, dir, tracer, left == 0);
		if (left == 0) {
			TRIGGERS.remove(player.getUUID()); // the bolt stays forward on an empty chamber: the next pull just clicks
		}
	}

	/**
	 * One round leaving an AK's muzzle, whoever holds it (a player or a soldier): the bullet, the shot
	 * everybody near hears and sees (sound, flash, tracer), and the noise soldiers react to.
	 */
	public static void shoot(ServerLevel level, net.minecraft.world.entity.LivingEntity shooter, Vec3 origin, Vec3 muzzle, Vec3 dir, boolean tracer,
		boolean last) {
		BulletEntity.fire(level, shooter, origin, dir.scale(MUZZLE_VELOCITY), tracer);
		// everybody near hears it; the shooter gets it too, for the tracer on the bullet's true line
		GunshotPayload shot = new GunshotPayload(shooter.getId(), muzzle.x, muzzle.y, muzzle.z, (float) dir.x, (float) dir.y, (float) dir.z,
			(last ? GunshotPayload.LAST : 0) | (tracer ? GunshotPayload.TRACER : 0));
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(muzzle) < 900.0 * 900.0) {
				ServerPlayNetworking.send(p, shot);
			}
		}
		level.gameEvent(shooter, net.minecraft.world.level.gameevent.GameEvent.PROJECTILE_SHOOT, muzzle);
		de.rcm.ballistic.ai.SoldierEntity.flash(shooter);
		de.rcm.ballistic.ai.Senses.noise(level, muzzle, de.rcm.ballistic.ai.Senses.GUNSHOT_RANGE, de.rcm.ballistic.ai.Senses.GUNSHOT, shooter);
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
		if (slot == EquipmentSlot.MAINHAND && entity instanceof ServerPlayer shooter) {
			this.triggerTick(level, shooter, stack);
		}
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
		boolean tactical = state.reloadKind() == GunState.TACTICAL;
		if (t == (tactical ? T_TAC_OUT : T_MAG_OUT) && state.hasMag()) {
			this.sound(level, player, ModRegistry.AK_MAG_OUT, 1.0F);
		} else if (t == T_MAG_DROP && state.hasMag() && state.reloadKind() == GunState.EMPTY) {
			// a speed reload: the empty magazine is let go and falls where you stand - pick it up again later
			this.dropMagazine(level, player, state, 0, false);
			setState(stack, new GunState(0, state.ammo(), false, state.mode(), state.lastShot(), state.reloadStart(), state.reloadKind(), state.reloadAmmo()));
			state = state(stack);
		} else if (t == T_TAC_OUT + 1 && state.hasMag() && tactical) {
			// knocked out by the fresh one: away forward with what is left in it; the round in the chamber stays
			this.dropMagazine(level, player, state, Math.max(0, state.rounds() - 1), true);
			setState(stack, new GunState(Math.min(1, state.rounds()), state.ammo(), false, state.mode(), state.lastShot(), state.reloadStart(),
				state.reloadKind(), state.reloadAmmo()));
			state = state(stack);
		} else if (t == (tactical ? T_TAC_IN : T_MAG_IN)) {
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

	/**
	 * The old magazine leaving the rifle: let go, it drops at your feet with a little toss forward;
	 * knocked out by the fresh one, it flies off a couple of metres ahead.
	 */
	private void dropMagazine(ServerLevel level, Player player, GunState state, int rounds, boolean knocked) {
		if (player.getAbilities().instabuild) {
			return;
		}
		ItemStack old = new ItemStack(state.ammo() == GunState.TRACER ? ModRegistry.AK_MAG_TRACER : ModRegistry.AK_MAG);
		AkMagazineItem.setRounds(old, rounds);
		Vec3 look = Vec3.directionFromRotation(0.0F, player.getYRot());
		Vec3 right = new Vec3(-look.z, 0.0, look.x);
		boolean leftHanded = player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT;
		Vec3 at = player.position().add(0.0, player.getBbHeight() * 0.55, 0.0).add(look.scale(0.45)).add(right.scale(leftHanded ? -0.15 : 0.15));
		ItemEntity item = new ItemEntity(level, at.x, at.y, at.z, old);
		var random = level.getRandom();
		double toss = knocked ? 0.2 : 0.06;
		item.setDeltaMovement(look.x * toss + random.triangle(0.0, 0.03), knocked ? 0.1 : 0.02, look.z * toss + random.triangle(0.0, 0.03));
		item.setPickUpDelay(30);
		level.addFreshEntity(item);
		// and lands with a clatter that depends on the ground
		MagazineLanding.watch(item);
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
