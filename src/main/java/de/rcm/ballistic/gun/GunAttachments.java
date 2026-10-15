package de.rcm.ballistic.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import de.rcm.ballistic.ModRegistry;
import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * What is mounted on a gun's rails - a weapon light, a laser module - and whether each is switched on.
 * Kept on the gun's stack, so every player who sees the gun sees them (and their light).
 */
public record GunAttachments(boolean light, boolean laser, boolean lightOn, boolean laserOn, boolean ir) {
	public static final GunAttachments NONE = new GunAttachments(false, false, false, false, false);
	/** What goes on: a weapon light, a (visible red) laser, an infrared laser - only seen through night vision. */
	public static final int LIGHT = 0;
	public static final int LASER = 1;
	public static final int IR_LASER = 2;

	public static final Codec<GunAttachments> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.BOOL.optionalFieldOf("light", false).forGetter(GunAttachments::light),
		Codec.BOOL.optionalFieldOf("laser", false).forGetter(GunAttachments::laser),
		Codec.BOOL.optionalFieldOf("light_on", false).forGetter(GunAttachments::lightOn),
		Codec.BOOL.optionalFieldOf("laser_on", false).forGetter(GunAttachments::laserOn),
		Codec.BOOL.optionalFieldOf("ir", false).forGetter(GunAttachments::ir)
	).apply(i, GunAttachments::new));

	public static final StreamCodec<ByteBuf, GunAttachments> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, GunAttachments::light,
		ByteBufCodecs.BOOL, GunAttachments::laser,
		ByteBufCodecs.BOOL, GunAttachments::lightOn,
		ByteBufCodecs.BOOL, GunAttachments::laserOn,
		ByteBufCodecs.BOOL, GunAttachments::ir,
		GunAttachments::new
	);

	/** The guns that take attachments. */
	public static boolean isGun(ItemStack stack) {
		return stack.is(ModRegistry.AK47) || stack.is(ModRegistry.GLOCK) || stack.is(ModRegistry.SHOTGUN);
	}

	public static GunAttachments of(ItemStack stack) {
		return stack.getOrDefault(ModRegistry.GUN_ATTACHMENTS, NONE);
	}

	public boolean any() {
		return this.light || this.laser;
	}

	public boolean lightShining() {
		return this.light && this.lightOn;
	}

	public boolean laserShining() {
		return this.laser && this.laserOn;
	}

	/**
	 * The next switch setting: off, light, laser, both - skipping what is not mounted (one press of the
	 * pressure switch at a time, as on a real light/laser combination).
	 */
	public GunAttachments cycled() {
		int now = (this.lightOn ? 1 : 0) | (this.laserOn ? 2 : 0);
		for (int k = 1; k <= 4; k++) {
			int next = (now + k) % 4;
			boolean l = (next & 1) != 0;
			boolean z = (next & 2) != 0;
			if ((!l || this.light) && (!z || this.laser)) {
				return new GunAttachments(this.light, this.laser, l, z, this.ir);
			}
		}
		return this;
	}

	/** The gun in the main hand: its light and laser switch to the next setting. */
	public static void toggle(ServerPlayer player) {
		ItemStack gun = player.getMainHandItem();
		if (!isGun(gun)) {
			return;
		}
		GunAttachments a = of(gun);
		if (!a.any()) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.attachment_none").withStyle(ChatFormatting.GRAY), true);
			return;
		}
		GunAttachments next = a.cycled();
		gun.set(ModRegistry.GUN_ATTACHMENTS, next);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_SELECTOR, SoundSource.PLAYERS, 0.35F, 1.9F);
		player.displayClientMessage(Component.translatable("message.ballisticmissiles.attachment_mode." + ((next.lightOn ? 1 : 0) | (next.laserOn ? 2 : 0)))
			.withStyle(ChatFormatting.AQUA), true);
	}

	/** Takes everything off the gun in the main hand, back into the inventory. */
	public static void remove(ServerPlayer player) {
		ItemStack gun = player.getMainHandItem();
		if (!isGun(gun)) {
			return;
		}
		GunAttachments a = of(gun);
		if (!a.any()) {
			return;
		}
		gun.remove(ModRegistry.GUN_ATTACHMENTS);
		if (a.light) {
			player.getInventory().placeItemBackInInventory(new ItemStack(ModRegistry.WEAPON_LIGHT));
		}
		if (a.laser) {
			player.getInventory().placeItemBackInInventory(new ItemStack(a.ir ? ModRegistry.IR_LASER_MODULE : ModRegistry.LASER_MODULE));
		}
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_MAG_OUT, SoundSource.PLAYERS, 0.5F, 1.4F);
		player.displayClientMessage(Component.translatable("message.ballisticmissiles.attachment_removed").withStyle(ChatFormatting.GRAY), true);
	}

	/** Ticks from pressing the mount key until the device clicks home on the rail (the first-person animation's click). */
	public static final int MOUNT_TICKS = 31;
	private static final java.util.Map<java.util.UUID, long[]> PENDING = new java.util.HashMap<>();

	/**
	 * The player started mounting a light, a laser or an IR laser ({@code kind}) from their inventory on the
	 * gun in their hand: it goes on when the animation clicks it home, if they still hold the gun and have
	 * the device then.
	 */
	public static void startMount(ServerPlayer player, int kind) {
		ItemStack gun = player.getMainHandItem();
		if (!isGun(gun) || (kind == LIGHT ? of(gun).light : of(gun).laser)) {
			return;
		}
		PENDING.put(player.getUUID(), new long[] {player.level().getGameTime() + MOUNT_TICKS, kind});
	}

	public static net.minecraft.world.item.Item device(int kind) {
		return kind == LIGHT ? ModRegistry.WEAPON_LIGHT : kind == IR_LASER ? ModRegistry.IR_LASER_MODULE : ModRegistry.LASER_MODULE;
	}

	/** Called every server tick: mounts what is due. */
	public static void tickPending(net.minecraft.server.MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}
		PENDING.entrySet().removeIf(e -> {
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player == null) {
				return true;
			}
			if (player.level().getGameTime() < e.getValue()[0]) {
				return false;
			}
			int kind = (int) e.getValue()[1];
			net.minecraft.world.item.Item device = device(kind);
			var inv = player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (s.is(device)) {
					if (mount(player, InteractionHand.MAIN_HAND, kind) && !player.getAbilities().instabuild) {
						s.shrink(1);
					}
					break;
				}
			}
			return true;
		});
	}

	/** Mounts a light, laser or IR laser ({@code kind}) on the gun held in {@code gunHand}; false if it already has one. */
	public static boolean mount(ServerPlayer player, InteractionHand gunHand, int kind) {
		boolean light = kind == LIGHT;
		ItemStack gun = player.getItemInHand(gunHand);
		if (!isGun(gun)) {
			return false;
		}
		GunAttachments a = of(gun);
		if (light ? a.light : a.laser) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.attachment_already").withStyle(ChatFormatting.YELLOW), true);
			return false;
		}
		gun.set(ModRegistry.GUN_ATTACHMENTS, light ? new GunAttachments(true, a.laser, false, a.laserOn, a.ir)
			: new GunAttachments(a.light, true, a.lightOn, false, kind == IR_LASER));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_MAG_IN, SoundSource.PLAYERS, 0.5F, 1.5F);
		player.displayClientMessage(Component.translatable(light ? "message.ballisticmissiles.attachment_light_on"
			: kind == IR_LASER ? "message.ballisticmissiles.attachment_ir_on" : "message.ballisticmissiles.attachment_laser_on")
			.withStyle(ChatFormatting.GREEN), true);
		return true;
	}
}
