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
public record GunAttachments(boolean light, boolean laser, boolean lightOn, boolean laserOn) {
	public static final GunAttachments NONE = new GunAttachments(false, false, false, false);

	public static final Codec<GunAttachments> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.BOOL.optionalFieldOf("light", false).forGetter(GunAttachments::light),
		Codec.BOOL.optionalFieldOf("laser", false).forGetter(GunAttachments::laser),
		Codec.BOOL.optionalFieldOf("light_on", false).forGetter(GunAttachments::lightOn),
		Codec.BOOL.optionalFieldOf("laser_on", false).forGetter(GunAttachments::laserOn)
	).apply(i, GunAttachments::new));

	public static final StreamCodec<ByteBuf, GunAttachments> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, GunAttachments::light,
		ByteBufCodecs.BOOL, GunAttachments::laser,
		ByteBufCodecs.BOOL, GunAttachments::lightOn,
		ByteBufCodecs.BOOL, GunAttachments::laserOn,
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
				return new GunAttachments(this.light, this.laser, l, z);
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
			player.getInventory().placeItemBackInInventory(new ItemStack(ModRegistry.LASER_MODULE));
		}
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_MAG_OUT, SoundSource.PLAYERS, 0.5F, 1.4F);
		player.displayClientMessage(Component.translatable("message.ballisticmissiles.attachment_removed").withStyle(ChatFormatting.GRAY), true);
	}

	/** Mounts {@code light} or a laser on the gun held in {@code gunHand}; false if it already has one. */
	public static boolean mount(ServerPlayer player, InteractionHand gunHand, boolean light) {
		ItemStack gun = player.getItemInHand(gunHand);
		if (!isGun(gun)) {
			return false;
		}
		GunAttachments a = of(gun);
		if (light ? a.light : a.laser) {
			player.displayClientMessage(Component.translatable("message.ballisticmissiles.attachment_already").withStyle(ChatFormatting.YELLOW), true);
			return false;
		}
		gun.set(ModRegistry.GUN_ATTACHMENTS, light ? new GunAttachments(true, a.laser, false, a.laserOn) : new GunAttachments(a.light, true, a.lightOn, false));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), ModRegistry.AK_MAG_IN, SoundSource.PLAYERS, 0.5F, 1.5F);
		player.displayClientMessage(Component.translatable(light ? "message.ballisticmissiles.attachment_light_on" : "message.ballisticmissiles.attachment_laser_on")
			.withStyle(ChatFormatting.GREEN), true);
		return true;
	}
}
