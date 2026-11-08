package de.rcm.ballistic.item;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.defense.EmpManager;
import de.rcm.ballistic.entity.JetEntity;
import de.rcm.ballistic.entity.JetType;
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
import org.jspecify.annotations.Nullable;

/**
 * Field radio for close air support. Right-click to call a strike fighter onto the block you are
 * looking at; with a target designator in the other hand it uses the designator's stored target
 * instead (so you can order strikes far beyond what you can see). The jet arrives from behind you
 * and carpet-bombs a strip centred on the target.
 */
public class AirstrikeRadioItem extends Item {
	/** What the radio calls in. Sneak + right-click cycles through them. */
	public enum Mode {
		CARPET("carpet", JetType.STRIKE, 1, 45),
		WARTHOG("warthog", JetType.WARTHOG, 1, 40),
		MOAB("moab", JetType.SPIRIT, 1, 120),
		FORMATION("formation", JetType.STRIKE, 3, 90),
		GUNSHIP("gunship", JetType.GUNSHIP, 1, 150),
		REAPER("reaper", JetType.REAPER, 1, 100),
		APACHE("apache", JetType.APACHE, 1, 100),
		TOMAHAWK("tomahawk", null, 4, 120),
		ARTILLERY("artillery", null, 20, 60),
		/** B-2 with a B61-11 nuclear earth penetrator: needs the bomb in your inventory. */
		B61("b61", JetType.SPIRIT, 1, 300);

		public final String key;
		/** The aircraft called in, or null for a fire mission (Tomahawks, artillery). */
		public final @Nullable JetType jet;
		public final int aircraft;
		public final int cooldownSeconds;

		Mode(String key, @Nullable JetType jet, int aircraft, int cooldownSeconds) {
			this.key = key;
			this.jet = jet;
			this.aircraft = aircraft;
			this.cooldownSeconds = cooldownSeconds;
		}

		public static Mode of(ItemStack stack) {
			Integer i = stack.get(ModRegistry.AIRSTRIKE_MODE);
			Mode[] all = values();
			return i != null && i >= 0 && i < all.length ? all[i] : CARPET;
		}

		public Component displayName() {
			return Component.translatable("airstrike.ballisticmissiles.mode." + this.key);
		}
	}

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
		ItemStack radio = player.getItemInHand(hand);
		Mode mode = Mode.of(radio);
		if (player.isShiftKeyDown()) {
			mode = Mode.values()[(mode.ordinal() + 1) % Mode.values().length];
			radio.set(ModRegistry.AIRSTRIKE_MODE, mode.ordinal());
			level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.RADIO_CLICK, SoundSource.PLAYERS, 0.7F, 1.0F);
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.airstrike_mode", mode.displayName()).withStyle(ChatFormatting.AQUA), true);
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
		InteractionResult result = callMission(server, serverPlayer, mode, target);
		if (result == InteractionResult.SUCCESS && !player.getAbilities().instabuild) {
			player.getCooldowns().addCooldown(radio, mode.cooldownSeconds * 20);
		}
		return result;
	}

	/**
	 * Calls in {@code mode} on {@code target} for {@code player} (also used by the command center).
	 * Reports to the player; returns SUCCESS if the mission is on its way.
	 */
	public static InteractionResult callMission(ServerLevel server, ServerPlayer player, Mode mode, Vec3 target) {
		Level level = server;
		double distance = Math.hypot(target.x - player.getX(), target.z - player.getZ());
		if (distance < DANGER_CLOSE) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.airstrike_danger_close", (int) DANGER_CLOSE).withStyle(ChatFormatting.RED), true);
			return InteractionResult.FAIL;
		}
		if (mode.jet == null) {
			if (mode == Mode.TOMAHAWK) {
				FireSupport.tomahawkSalvo(server, player, target);
			} else {
				FireSupport.artilleryBarrage(server, player, target);
			}
			level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.TARGET_LOCK, SoundSource.PLAYERS, 1.0F, 0.8F);
			player.displayClientMessage(Component.literal("☄ ").append(mode.displayName()).append(" – ")
				.append(Component.translatable(mode == Mode.TOMAHAWK ? "message.ballisticmissiles.tomahawk_away" : "message.ballisticmissiles.artillery_shot",
					(int) target.x, (int) target.y, (int) target.z, (int) distance))
				.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
			return InteractionResult.SUCCESS;
		}
		net.minecraft.world.item.ItemStack bomb = net.minecraft.world.item.ItemStack.EMPTY;
		if (mode == Mode.B61 && !player.getAbilities().instabuild) {
			var inventory = player.getInventory();
			for (int i = 0; i < inventory.getContainerSize() && bomb.isEmpty(); i++) {
				if (inventory.getItem(i).is(ModRegistry.B61_BOMB)) {
					bomb = inventory.getItem(i);
				}
			}
			if (bomb.isEmpty()) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.b61_missing").withStyle(ChatFormatting.RED), true);
				return InteractionResult.FAIL;
			}
		}
		JetEntity jet = JetEntity.callIn(server, player, target, mode.jet, 0.0, 0.0);
		if (jet != null && mode == Mode.B61) {
			jet.setNuclear(true);
			bomb.shrink(1);
		}
		if (jet != null && mode.aircraft > 1) {
			// wingmen echelon out to both sides and a little behind, widening the carpet
			JetEntity.callIn(server, player, target, mode.jet, 14.0, 22.0);
			JetEntity.callIn(server, player, target, mode.jet, -14.0, 44.0);
		}
		if (jet == null) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.airstrike_out_of_range", (int) JetEntity.MAX_RANGE).withStyle(ChatFormatting.RED), true);
			return InteractionResult.FAIL;
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.RADIO_SQUELCH, SoundSource.PLAYERS, 0.8F, 1.0F);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.TARGET_LOCK, SoundSource.PLAYERS, 1.0F, 0.8F);
		player.displayClientMessage(Component.literal("✈ ").append(mode.displayName()).append(" – ")
			.append(Component.translatable("message.ballisticmissiles.airstrike_called", (int) target.x, (int) target.y, (int) target.z, (int) distance, jet.etaSeconds()))
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
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.airstrike_mode", Mode.of(stack).displayName()).withStyle(ChatFormatting.AQUA));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.airstrike_1").withStyle(ChatFormatting.GOLD));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.airstrike_2").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("tooltip.ballisticmissiles.airstrike_3", Mode.of(stack).cooldownSeconds).withStyle(ChatFormatting.DARK_GRAY));
	}
}
