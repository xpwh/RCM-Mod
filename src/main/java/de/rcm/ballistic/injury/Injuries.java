package de.rcm.ballistic.injury;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Wounds. Where a round strikes matters: in a leg you limp (slower, and badly hit no running at all),
 * in an arm your aim shakes, and most hits bleed - lightly (it clots after a minute), heavily (it goes on
 * until it is dressed) or, from an artery in a limb, fast enough to kill within a minute unless a
 * tourniquet goes on. Blasts tear limbs and set them bleeding, a bad fall can break a leg. Dressed or
 * not, a wound heals by itself, slowly, once it has stopped bleeding.
 * <p>
 * The wounds live on the player (saved with them, and sent to their own client for the limp, the shaking
 * aim and the screen); bleeding to death counts as its own way to die.
 */
public final class Injuries {
	public static final AttachmentType<Wounds> WOUNDS = AttachmentRegistry.create(BallisticMissiles.id("wounds"),
		b -> b.persistent(Wounds.CODEC).syncWith(Wounds.STREAM_CODEC, AttachmentSyncPredicate.targetOnly()));
	public static final ResourceKey<DamageType> BLEEDING = ResourceKey.create(Registries.DAMAGE_TYPE, BallisticMissiles.id("bleeding"));
	private static final Identifier LIMP = BallisticMissiles.id("wounded_leg");
	/** Ticks between drops of health lost, by how badly it bleeds. */
	private static final int[] BLEED_EVERY = {0, 160, 70, 28};
	/** Light bleeding clots after this long. */
	private static final int CLOT = 1200;
	/** A wound that no longer bleeds heals a step in this time. */
	private static final int HEAL = 6000;

	private static final class Clock {
		int bleed;
		int clot;
		int heal;
		int drip;
	}

	private static final Map<UUID, Clock> CLOCKS = new HashMap<>();

	private Injuries() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(Injuries::tick);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(Injuries::afterDamage);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity.level() instanceof ServerLevel level && Blood.bleeds(entity)) {
				// a pool spreading under the body
				Blood.send(level, entity.position().add(0, 0.2, 0), Vec3.ZERO, Math.round(entity.getBbWidth() * entity.getBbHeight() * 10.0F), Blood.POOL);
			}
			if (entity instanceof ServerPlayer p) {
				set(p, Wounds.NONE);
				CLOCKS.remove(p.getUUID());
			}
		});
	}

	public static void clear() {
		CLOCKS.clear();
	}

	/** Back in the game with the wounds they left with: the limp again. */
	public static void rejoin(ServerPlayer p) {
		limp(p, get(p));
	}

	public static Wounds get(LivingEntity e) {
		Wounds w = e.getAttached(WOUNDS);
		return w == null ? Wounds.NONE : w;
	}

	public static void set(ServerPlayer p, Wounds w) {
		Wounds old = get(p);
		if (w.equals(old)) {
			return;
		}
		if (w.any()) {
			p.setAttached(WOUNDS, w);
		} else {
			p.removeAttached(WOUNDS);
		}
		limp(p, w);
		if (w.bleed() > old.bleed()) {
			Clock c = clock(p);
			c.clot = 0;
		}
		if (w.leg() > old.leg() || w.arm() > old.arm()) {
			clock(p).heal = 0;
		}
	}

	private static Clock clock(ServerPlayer p) {
		return CLOCKS.computeIfAbsent(p.getUUID(), u -> new Clock());
	}

	/** A wounded leg: slower, a badly wounded one much slower. */
	private static void limp(ServerPlayer p, Wounds w) {
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		speed.removeModifier(LIMP);
		if (w.leg() > 0) {
			speed.addTransientModifier(new AttributeModifier(LIMP, w.leg() == 1 ? -0.18 : -0.4, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	// ------------------------------------------------------------------ getting hurt

	/**
	 * A round struck a player at {@code at}, travelling along {@code dir}: which part of them it hit, and
	 * what that does. (The head is the bullet's business: double damage, and a graze bleeds a little.)
	 */
	public static void shot(ServerPlayer p, Vec3 at, Vec3 dir, float damage, boolean head) {
		Wounds w = get(p);
		var r = p.getRandom();
		double h = (at.y - p.getY()) / Math.max(0.1, p.getBbHeight());
		if (head) {
			w = w.withBleed(Math.max(w.bleed(), 1));
		} else if (h < 0.42) {
			w = w.withLeg(w.leg() + 1).withBleed(Math.max(w.bleed(), r.nextFloat() < 0.22F ? Wounds.ARTERIAL : damage > 5.0F ? 2 : 1));
			message(p, "message.ballisticmissiles.wound_leg");
		} else {
			// across the body: out towards either side is an arm
			float yaw = p.yBodyRot * Mth.DEG_TO_RAD;
			double side = (at.x - p.getX()) * Mth.cos(yaw) + (at.z - p.getZ()) * Mth.sin(yaw);
			if (Math.abs(side) > 0.2) {
				w = w.withArm(w.arm() + 1).withBleed(Math.max(w.bleed(), r.nextFloat() < 0.15F ? Wounds.ARTERIAL : damage > 5.0F ? 2 : 1));
				message(p, "message.ballisticmissiles.wound_arm");
			} else {
				w = w.withBleed(Math.max(w.bleed(), 2));
			}
		}
		set(p, w);
	}

	private static void afterDamage(LivingEntity entity, DamageSource source, float base, float taken, boolean blocked) {
		if (blocked || taken <= 0.0F || !(entity.level() instanceof ServerLevel level)) {
			return;
		}
		boolean bullet = source.getDirectEntity() instanceof de.rcm.ballistic.gun.BulletEntity;
		boolean bleeding = source.is(BLEEDING);
		// blood for everything that hurts flesh (the bullet sends its own, with the line it took)
		if (!bullet && !bleeding && Blood.bleeds(entity) && (source.is(DamageTypeTags.IS_EXPLOSION) || source.is(DamageTypeTags.IS_PROJECTILE)
			|| source.getEntity() != null && source.getDirectEntity() == source.getEntity())) {
			Vec3 from = source.getSourcePosition();
			Vec3 mid = entity.position().add(0, entity.getBbHeight() * 0.6, 0);
			Vec3 dir = from == null ? new Vec3(0, 0.4, 0) : mid.subtract(from).normalize();
			boolean blast = source.is(DamageTypeTags.IS_EXPLOSION);
			Blood.send(level, mid, dir, Math.min(60, Math.round(taken * (blast ? 4.0F : 2.0F))), blast ? Blood.BURST : Blood.SPRAY);
		}
		if (!(entity instanceof ServerPlayer p)) {
			return;
		}
		var r = p.getRandom();
		Wounds w = get(p);
		if (source.is(DamageTypeTags.IS_EXPLOSION) && taken >= 4.0F) {
			// shrapnel and blast: a limb torn, bleeding
			w = r.nextBoolean() ? w.withLeg(w.leg() + 1) : w.withArm(w.arm() + 1);
			w = w.withBleed(Math.max(w.bleed(), r.nextFloat() < 0.12F ? Wounds.ARTERIAL : 2));
			set(p, w);
		} else if (source.is(DamageTypeTags.IS_FALL) && taken >= 5.0F) {
			// landed badly: a broken leg
			set(p, w.withLeg(w.leg() + (taken >= 9.0F ? 2 : 1)));
			message(p, "message.ballisticmissiles.wound_leg");
		}
	}

	private static void message(ServerPlayer p, String key) {
		p.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
	}

	// ------------------------------------------------------------------ bleeding and healing

	private static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Wounds w = get(p);
			if (!w.any() || !p.isAlive() || p.isSpectator()) {
				continue;
			}
			ServerLevel level = p.level();
			Clock c = clock(p);
			if (p.isCreative()) {
				continue;
			}
			if (w.leg() >= 2 && p.isSprinting()) {
				p.setSprinting(false);
			}
			if (w.bleed() > 0) {
				if (++c.bleed >= BLEED_EVERY[w.bleed()]) {
					c.bleed = 0;
					p.hurtServer(level, new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(BLEEDING)), 1.0F);
				}
				// the drops it leaves behind
				int every = w.bleed() == Wounds.ARTERIAL ? 4 : w.bleed() == 2 ? 12 : 30;
				if (++c.drip >= every) {
					c.drip = 0;
					Vec3 at = p.position().add(0, p.getBbHeight() * (w.leg() > 0 ? 0.35 : 0.55), 0);
					Blood.send(level, at, p.getDeltaMovement(), w.bleed() == Wounds.ARTERIAL ? 3 : 1, Blood.DRIP);
				}
				if (w.bleed() == 1 && ++c.clot >= CLOT) {
					set(p, w.withBleed(0));
				}
			} else if (++c.heal >= HEAL) {
				c.heal = 0;
				set(p, w.withLeg(w.leg() - 1).withArm(w.arm() - 1));
			}
		}
	}

	// ------------------------------------------------------------------ treatment

	/** A first aid kit: dressings on the wounds - bleeding stops (all but an artery), a little health back, the wounds dressed. */
	public static boolean dress(ServerPlayer p) {
		Wounds w = get(p);
		if (!w.any() && p.getHealth() >= p.getMaxHealth()) {
			return false;
		}
		if (w.bleed() == Wounds.ARTERIAL) {
			p.displayClientMessage(Component.translatable("message.ballisticmissiles.need_tourniquet").withStyle(ChatFormatting.RED), true);
			p.heal(1.0F);
			return true;
		}
		set(p, new Wounds(Math.max(0, w.leg() - 1), Math.max(0, w.arm() - 1), 0));
		p.heal(3.0F);
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), ModRegistry.GEAR_RUSTLE, SoundSource.PLAYERS, 1.0F, 1.2F);
		p.displayClientMessage(Component.translatable("message.ballisticmissiles.dressed").withStyle(ChatFormatting.GREEN), true);
		return true;
	}

	/** A tourniquet: the bleeding stops at once, artery or not. */
	public static boolean tourniquet(ServerPlayer p) {
		Wounds w = get(p);
		if (w.bleed() == 0) {
			return false;
		}
		set(p, w.withBleed(0));
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), ModRegistry.GEAR_RUSTLE, SoundSource.PLAYERS, 1.0F, 0.8F);
		p.displayClientMessage(Component.translatable("message.ballisticmissiles.tourniquet_on").withStyle(ChatFormatting.GREEN), true);
		return true;
	}
}
