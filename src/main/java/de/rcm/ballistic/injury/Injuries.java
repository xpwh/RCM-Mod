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
		b -> b.persistent(Wounds.CODEC).syncWith(Wounds.STREAM_CODEC, AttachmentSyncPredicate.all()));
	public static final ResourceKey<DamageType> BLEEDING = ResourceKey.create(Registries.DAMAGE_TYPE, BallisticMissiles.id("bleeding"));
	private static final Identifier LIMP = BallisticMissiles.id("wounded_leg");
	private static final Identifier DOWN = BallisticMissiles.id("dying");
	/** How long you lie there before it goes black (ticks). */
	public static final int DYING = 180;
	/** Shot through the head: ticks swaying on your feet, falling and lying still before it is over. */
	public static final int COLLAPSE = 50;
	/** Buckshot balls in one leg in one shot, from close enough, that take it off. */
	private static final int AMPUTATION_PELLETS = 3;
	private static final double AMPUTATION_RANGE = 9.0;
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
		/** Ticks since the last spurt from a cut artery. */
		int pulse;
		/** Buckshot in a leg this tick: the tick, and the balls in the left and the right leg. */
		long pelletTick;
		int pelletsLeft;
		int pelletsRight;
		int pelletsArmLeft;
		int pelletsArmRight;
		int pelletsHead;
		/** What brought them down (dealt again when the dying is over), and whether that death is now let through. */
		DamageSource cause;
		boolean finishing;
	}

	private static final Map<UUID, Clock> CLOCKS = new HashMap<>();

	private Injuries() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(Injuries::tick);
		ServerTickEvents.END_SERVER_TICK.register(Injuries::tickCreatures);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(Injuries::afterDamage);
		ServerLivingEntityEvents.ALLOW_DEATH.register(Injuries::allowDeath);
		Corpses.init();
		// no hand on that arm: nothing to hit with, nothing to dig with
		net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
			handGone(player) ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
		net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
			handGone(player) ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			Float blast = TORN_APART.remove(entity.getUUID());
			if (blast != null) {
				// blown to pieces: nothing left lying there but what the blast threw about
				Blood.gib(entity, source.getSourcePosition(), blast);
				if (entity instanceof ServerPlayer p) {
					CLOCKS.remove(p.getUUID());
				} else {
					entity.discard();
				}
				return;
			}
			if (entity.level() instanceof ServerLevel level && Blood.bleeds(entity)) {
				// a pool spreading under the body
				Blood.send(level, entity.position().add(0, 0.2, 0), Vec3.ZERO, Math.round(entity.getBbWidth() * entity.getBbHeight() * 10.0F), Blood.POOL);
			}
			if (entity instanceof ServerPlayer p) {
				// the body stays behind a while, with its wounds (the respawned player starts whole)
				Corpses.leave(p);
				CLOCKS.remove(p.getUUID());
			}
		});
	}

	/**
	 * One spurt from a cut artery: from where the wound is - the stump of a leg or an arm, a leg, an arm, the
	 * body - out and away from it, as a jet that arcs to the ground. Lying down, from where the limbs lie.
	 */
	private static void spurt(ServerPlayer p, Wounds w, float strength) {
		ServerLevel level = p.level();
		double yaw = p.yBodyRot * Mth.DEG_TO_RAD;
		Vec3 facing = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		boolean lying = w.down() || p.getPose() == net.minecraft.world.entity.Pose.SWIMMING;
		int seedSide = (w.seed() & 1) == 0 ? 1 : -1;
		Vec3 at;
		Vec3 dir;
		if (w.lost() > 0 || (w.armsLost() == 0 && w.leg() >= w.arm() && w.leg() > 0)) {
			// a leg: the stump pours down and out, a wounded thigh squirts sideways
			double s = w.lost() > 0 ? ((w.lost() & Wounds.LEFT) != 0 ? 1 : -1) : seedSide;
			boolean stump = w.lost() > 0;
			if (lying) {
				at = p.position().add(facing.scale(-0.65)).add(left.scale(0.12 * s)).add(0, 0.15, 0);
				dir = stump ? facing.scale(-0.7).add(0, 0.45, 0) : left.scale(0.6 * s).add(0, 0.7, 0);
			} else {
				at = p.position().add(left.scale(0.12 * s)).add(0, stump ? 0.38 : 0.5, 0);
				dir = stump ? facing.scale(0.35).add(left.scale(0.25 * s)).add(0, -0.25, 0) : left.scale(0.65 * s).add(facing.scale(0.35)).add(0, 0.3, 0);
			}
		} else if (w.armsLost() > 0 || w.arm() > 0) {
			double s = w.armsLost() > 0 ? ((w.armsLost() & Wounds.LEFT) != 0 ? 1 : -1) : seedSide;
			boolean stump = w.armsLost() > 0;
			if (lying) {
				at = p.position().add(facing.scale(0.35)).add(left.scale(0.38 * s)).add(0, 0.15, 0);
				dir = left.scale(0.5 * s).add(0, 0.75, 0);
			} else {
				at = p.position().add(left.scale(0.38 * s)).add(0, stump ? 0.95 : 1.1, 0);
				dir = stump ? left.scale(0.35 * s).add(facing.scale(0.3)).add(0, -0.3, 0) : left.scale(0.6 * s).add(facing.scale(0.3)).add(0, 0.35, 0);
			}
		} else {
			at = lying ? p.position().add(0, 0.25, 0) : p.position().add(facing.scale(0.2)).add(0, 1.2, 0);
			dir = lying ? new Vec3(0, 0.8, 0).add(facing.scale(0.3)) : facing.scale(0.7).add(0, 0.25, 0);
		}
		Blood.send(level, at, dir.normalize().scale(strength), Math.round(10 + 8 * strength), Blood.JET);
	}

	/** Animals and monsters bleeding from their wounds, and for how many ticks more. */
	private static final Map<LivingEntity, Integer> BLEEDING_CREATURES = new java.util.WeakHashMap<>();

	/** A creature hit: it leaves a trail of blood for {@code ticks}. */
	public static void bleedCreature(LivingEntity e, int ticks) {
		BLEEDING_CREATURES.merge(e, ticks, Math::max);
	}

	private static void tickCreatures(MinecraftServer server) {
		if (BLEEDING_CREATURES.isEmpty() || server.getTickCount() % 6 != 0) {
			return;
		}
		var it = BLEEDING_CREATURES.entrySet().iterator();
		while (it.hasNext()) {
			var en = it.next();
			LivingEntity e = en.getKey();
			int left = en.getValue() - 6;
			if (left <= 0 || e.isRemoved() || !(e.level() instanceof ServerLevel level)) {
				it.remove();
				continue;
			}
			en.setValue(left);
			// drops falling from it as it runs; still, a pool spreading under the body
			if (e.isAlive()) {
				Blood.send(level, e.position().add(0, e.getBbHeight() * 0.45, 0), new Vec3(0, -0.2, 0), 2, Blood.DRIP);
				if (left > 260 && left % 12 < 6) {
					// a big wound: an artery pumping
					double a = level.getRandom().nextDouble() * Math.PI * 2;
					Blood.send(level, e.position().add(0, e.getBbHeight() * 0.55, 0), new Vec3(Math.cos(a) * 0.6, 0.45, Math.sin(a) * 0.6).scale(0.9), 12,
						Blood.JET);
				}
			} else if (left % 60 < 6) {
				Blood.send(level, e.position().add(0, 0.3, 0), Vec3.ZERO, 6 + left / 60, Blood.POOL);
			}
		}
	}

	public static void clear() {
		BLEEDING_CREATURES.clear();
		CLOCKS.clear();
		TORN_APART.clear();
	}

	/** Back in the game with the wounds they left with: the limp again. */
	public static void rejoin(ServerPlayer p) {
		limp(p, get(p));
	}

	/** The arm of the player's main hand is gone below the elbow. */
	public static boolean handGone(net.minecraft.world.entity.player.Player p) {
		return !p.isCreative() && get(p).lostArm(p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? Wounds.RIGHT : Wounds.LEFT);
	}

	public static Wounds get(LivingEntity e) {
		Wounds w = e.getAttached(WOUNDS);
		return w == null ? Wounds.NONE : w;
	}

	public static void set(ServerPlayer p, Wounds w) {
		Wounds old = get(p);
		if (w.any() && w.seed() == 0) {
			// which version of each wound this player gets: their own, kept until they are whole again
			w = w.withSeed(old.seed() != 0 ? old.seed() : 1 + p.getRandom().nextInt(Integer.MAX_VALUE - 1));
		}
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
		if (w.down() != old.down()) {
			p.refreshDimensions();
		}
	}

	// ------------------------------------------------------------------ losing a leg

	private static void tally(Clock c, long now) {
		if (c.pelletTick != now) {
			c.pelletTick = now;
			c.pelletsLeft = 0;
			c.pelletsRight = 0;
			c.pelletsArmLeft = 0;
			c.pelletsArmRight = 0;
			c.pelletsHead = 0;
		}
	}

	/** Which side of the body {@code at} is on: positive to the player's left. */
	private static double side(ServerPlayer p, Vec3 at) {
		float yaw = p.yBodyRot * Mth.DEG_TO_RAD;
		return (at.x - p.getX()) * Mth.cos(yaw) + (at.z - p.getZ()) * Mth.sin(yaw);
	}

	/**
	 * A ball of buckshot struck a player {@code range} blocks from the muzzle. Enough of one shot's balls
	 * in the same leg or arm, close enough, and the limb is gone (a leg below the knee, an arm below the elbow).
	 */
	public static void pellet(ServerPlayer p, Vec3 at, double range) {
		double h = (at.y - p.getY()) / Math.max(0.1, p.getBbHeight());
		if (range > AMPUTATION_RANGE || h >= 0.86) {
			return;
		}
		Clock c = clock(p);
		tally(c, p.level().getGameTime());
		double side = side(p, at);
		int limb = side > 0.0 ? Wounds.LEFT : Wounds.RIGHT;
		// the closer, the fewer it takes
		int needed = range < 4.0 ? AMPUTATION_PELLETS - 1 : AMPUTATION_PELLETS;
		if (h < 0.42) {
			if (get(p).lostLeg(limb)) {
				return; // nothing left there to take
			}
			int n = side > 0.0 ? ++c.pelletsLeft : ++c.pelletsRight;
			if (n >= needed) {
				amputate(p, limb);
			}
		} else if (Math.abs(side) > 0.18 && !get(p).lostArm(limb)) {
			int n = side > 0.0 ? ++c.pelletsArmLeft : ++c.pelletsArmRight;
			if (n >= needed) {
				amputateArm(p, limb);
			}
		}
	}

	/**
	 * A round about to strike a player's head: if it kills - a full-power round, or buckshot in the face
	 * from close up - the skull is blown open and there is no lying there dying.
	 */
	public static void headHit(ServerPlayer p, Vec3 at, Vec3 line, float damage, boolean pellet, double range) {
		if (p.isCreative() || p.isSpectator() || get(p).head() == Wounds.SHATTERED) {
			return;
		}
		boolean lethal = damage >= p.getHealth() + p.getAbsorptionAmount() || dying(p);
		if (pellet) {
			Clock c = clock(p);
			tally(c, p.level().getGameTime());
			if (++c.pelletsHead >= 2 && range < 7.0) {
				lethal = true;
			}
		}
		if (!lethal) {
			return;
		}
		Wounds w = get(p).withHead(Wounds.SHATTERED);
		if (!w.incapacitated()) {
			// still on your feet a moment - swaying, then down: away from the shot, mostly
			Vec3 facing = Vec3.directionFromRotation(0.0F, p.getYRot());
			boolean fromBehind = line.x * facing.x + line.z * facing.z > 0.0;
			if (p.getRandom().nextFloat() < 0.2F) {
				fromBehind = !fromBehind;
			}
			w = w.withCollapse(COLLAPSE, fromBehind ? Wounds.FORWARD : Wounds.BACKWARD);
			p.stopUsingItem();
		}
		set(p, w);
		ServerLevel level = p.level();
		Vec3 head = p.getEyePosition();
		// blown out the far side, in a spray of blood and bone
		Blood.send(level, head, line, 90, Blood.BURST);
		Blood.send(level, head.add(line.scale(0.3)), line, 50, Blood.SPRAY);
		level.playSound(null, head.x, head.y, head.z, ModRegistry.BULLET_IMPACT_FLESH, SoundSource.PLAYERS, 2.2F, 0.5F);
		level.playSound(null, head.x, head.y, head.z, ModRegistry.BULLET_IMPACT_FLESH, SoundSource.PLAYERS, 1.8F, 0.75F);
	}

	/** The operators' test: a graze across the scalp, or the skull blown open from in front. */
	public static void headTest(ServerPlayer p, boolean lethal) {
		if (!lethal) {
			set(p, get(p).withHead(Wounds.GRAZED));
			message(p, "message.ballisticmissiles.wound_head");
			Blood.send(p.level(), p.getEyePosition(), p.getLookAngle().scale(-1.0), 20, Blood.SPRAY);
			return;
		}
		Vec3 line = p.getLookAngle().scale(-1.0);
		headHit(p, p.getEyePosition(), line, 1000.0F, false, 1.0);
		afterHeadHit(p, p.level().damageSources().genericKill());
	}

	/** After the hit: a blown-open skull is death, there and then, whatever the round's damage. */
	public static void afterHeadHit(ServerPlayer p, DamageSource source) {
		if (get(p).collapse() > 0) {
			Clock c = clock(p);
			if (c.cause == null) {
				c.cause = source; // what it will have been, when the collapse is over
			}
			return;
		}
		if (p.isAlive() && get(p).head() == Wounds.SHATTERED && !p.isCreative()) {
			p.invulnerableTime = 0;
			p.hurtServer(p.level(), source, 1000.0F);
		}
	}

	/** The arm is shot away below the elbow: whatever that hand held falls, the artery pumps. */
	public static void amputateArm(ServerPlayer p, int side) {
		ServerLevel level = p.level();
		Wounds before = get(p);
		if ((before.armsLost() | side) == before.armsLost()) {
			return;
		}
		set(p, before.withArmLost(side));
		float yaw = p.yBodyRot * Mth.DEG_TO_RAD;
		double s = (side == Wounds.LEFT ? 1.0 : -1.0) * 0.4;
		Vec3 elbow = p.position().add(Mth.cos(yaw) * s, p.getBbHeight() * 0.55, Mth.sin(yaw) * s);
		Blood.send(level, elbow, new Vec3(Mth.cos(yaw) * s, 0.3, Mth.sin(yaw) * s), 60, Blood.BURST);
		Blood.send(level, elbow, new Vec3(0, -0.3, 0), 40, Blood.SPRAY);
		level.playSound(null, elbow.x, elbow.y, elbow.z, ModRegistry.BULLET_IMPACT_FLESH, SoundSource.PLAYERS, 2.0F, 0.6F);
		level.playSound(null, elbow.x, elbow.y, elbow.z, ModRegistry.BULLET_IMPACT_FLESH, SoundSource.PLAYERS, 1.6F, 0.85F);
		message(p, get(p).armsLost() == Wounds.BOTH ? "message.ballisticmissiles.arms_lost" : "message.ballisticmissiles.arm_lost");
		handsGone(p, get(p));
	}

	/** What hands that are gone (or one hand, for a two-handed gun) cannot hold falls to the ground. */
	private static void handsGone(ServerPlayer p, Wounds w) {
		if (w.armsLost() == 0) {
			return;
		}
		var main = p.getMainHandItem();
		boolean twoHanded = main.is(ModRegistry.AK47) || main.is(ModRegistry.SHOTGUN) || main.is(ModRegistry.ROCKET_LAUNCHER);
		boolean rightGone = w.lostArm(p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? Wounds.RIGHT : Wounds.LEFT);
		boolean leftGone = w.lostArm(p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? Wounds.LEFT : Wounds.RIGHT);
		if (!main.isEmpty() && (rightGone || twoHanded)) {
			p.drop(main.copy(), true, false);
			p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
			if (twoHanded && !rightGone) {
				message(p, "message.ballisticmissiles.one_hand");
			}
		}
		var off = p.getOffhandItem();
		if (!off.isEmpty() && leftGone) {
			p.drop(off.copy(), true, false);
			p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, net.minecraft.world.item.ItemStack.EMPTY);
		}
	}

	/** The leg is shot away below the knee: you go down, it bleeds from the artery until a tourniquet goes on. */
	public static void amputate(ServerPlayer p, int side) {
		ServerLevel level = p.level();
		Wounds before = get(p);
		if ((before.lost() | side) == before.lost()) {
			return;
		}
		set(p, before.withLost(side));
		Vec3 knee = p.position().add(0, 0.35, 0);
		Blood.send(level, knee, new Vec3(0, 0.5, 0), 60, Blood.BURST);
		Blood.send(level, knee, new Vec3(0, -0.2, 0), 40, Blood.SPRAY);
		level.playSound(null, knee.x, knee.y, knee.z, ModRegistry.BULLET_IMPACT_FLESH, SoundSource.PLAYERS, 2.0F, 0.55F);
		level.playSound(null, knee.x, knee.y, knee.z, ModRegistry.BULLET_IMPACT_FLESH, SoundSource.PLAYERS, 1.6F, 0.8F);
		message(p, get(p).lost() == Wounds.BOTH ? "message.ballisticmissiles.legs_lost" : "message.ballisticmissiles.leg_lost");
	}

	/** Brings a player down to bleed out (the operators' test): as if they had just taken a mortal hit. */
	public static void startDying(ServerPlayer p) {
		if (dying(p)) {
			return;
		}
		Clock c = clock(p);
		c.cause = null;
		p.setHealth(1.0F);
		p.stopUsingItem();
		set(p, get(p).withBleed(Wounds.ARTERIAL).withDying(DYING));
	}

	/** Every wound gone (the operators' heal). */
	public static void heal(ServerPlayer p) {
		set(p, Wounds.NONE);
		CLOCKS.remove(p.getUUID());
		p.setHealth(p.getMaxHealth());
	}

	// ------------------------------------------------------------------ dying

	/**
	 * Instead of dropping dead on the spot, a player who would die goes down: lying there, unable to
	 * move, the world fading for some seconds - then black. Only what kills outright (a blast that tears
	 * you apart, the void, /kill) skips the dying.
	 */
	/** Bodies a blast is tearing apart (on their way to dying), and how hard it hit. */
	private static final Map<UUID, Float> TORN_APART = new HashMap<>();

	/** A blast right by them or under them: not a body that dies, but one torn to pieces. */
	private static boolean tornApart(LivingEntity entity, DamageSource source, float amount) {
		if (!source.is(DamageTypeTags.IS_EXPLOSION) || !Blood.bleeds(entity)) {
			return false;
		}
		if (entity instanceof net.minecraft.world.entity.player.Player p && (p.isCreative() || p.isSpectator())) {
			return false;
		}
		float need = entity instanceof net.minecraft.world.entity.player.Player ? 14.0F : Math.max(8.0F, entity.getMaxHealth() * 0.5F);
		return amount >= need;
	}

	private static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
		if (tornApart(entity, source, amount)) {
			TORN_APART.put(entity.getUUID(), Math.min(2.5F, amount / 20.0F));
			return true;
		}
		if (!(entity instanceof ServerPlayer p) || p.isCreative() || p.isSpectator()) {
			return true;
		}
		Clock c = clock(p);
		if (c.finishing) {
			c.finishing = false;
			return true;
		}
		Wounds w = get(p);
		if (w.collapse() > 0 && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			// already as good as dead, still on the way down
			if (c.cause == null) {
				c.cause = source;
			}
			p.setHealth(1.0F);
			return false;
		}
		if (w.dying() > 0 || w.head() == Wounds.SHATTERED || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || amount >= 40.0F) {
			return true; // hit again while down, or killed outright
		}
		c.cause = source;
		p.setHealth(1.0F);
		p.stopUsingItem();
		set(p, w.withDying(DYING));
		return false;
	}

	public static boolean dying(LivingEntity e) {
		return get(e).dying() > 0;
	}

	private static void die(ServerPlayer p) {
		Clock c = clock(p);
		DamageSource cause = c.cause != null ? c.cause : new DamageSource(p.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(BLEEDING));
		c.cause = null;
		c.finishing = true;
		p.invulnerableTime = 0;
		p.setHealth(0.0F);
		p.die(cause);
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
		speed.removeModifier(DOWN);
		if (w.leg() > 0) {
			speed.addTransientModifier(new AttributeModifier(LIMP, w.lost() == Wounds.BOTH ? -0.6 : w.lost() > 0 ? -0.3 : w.leg() == 1 ? -0.18 : -0.4, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
		if (w.collapse() > 0) {
			speed.addTransientModifier(new AttributeModifier(DOWN, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		} else if (w.dying() > 0) {
			// down, but still dragging yourself along a little
			speed.addTransientModifier(new AttributeModifier(DOWN, -0.55, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
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
			// torn open to the skull, bleeding as scalp wounds do
			if (w.head() < Wounds.GRAZED) {
				message(p, "message.ballisticmissiles.wound_head");
			}
			w = w.withHead(Wounds.GRAZED);
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
				// in the body: another hole in the shirt
				w = w.withBleed(Math.max(w.bleed(), 2)).withTorso(w.torso() + 1);
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
			handsGone(p, w);
			if (w.leg() >= 2 && p.isSprinting()) {
				p.setSprinting(false);
			}
			if (w.collapse() > 0) {
				// shot through the head: swaying, falling, still - then it is over
				p.setHealth(Math.max(1.0F, p.getHealth()));
				p.setSprinting(false);
				p.stopUsingItem();
				int left = w.collapse() - 1;
				if (left == COLLAPSE - 40) {
					level.playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.PLAYER_BIG_FALL, SoundSource.PLAYERS, 1.0F, 0.8F);
					Blood.send(level, p.position().add(0, 0.2, 0), Vec3.ZERO, 12, Blood.POOL);
				}
				if (left <= 0) {
					die(p);
				} else {
					set(p, w.withCollapse(left, w.fall()));
				}
				continue;
			}
			if (w.dying() > 0) {
				// lying there; the end comes when it comes
				p.setHealth(1.0F);
				p.setSprinting(false);
				if (w.dying() % 30 == 0 && w.bleed() > 0) {
					Blood.send(level, p.position().add(0, 0.2, 0), Vec3.ZERO, 2, Blood.DRIP);
				}
				if (w.bleed() == Wounds.ARTERIAL) {
					// the heart failing: the spurts weaker and further apart
					float left = w.dying() / (float) DYING;
					if (++c.pulse >= 12 + Math.round((1.0F - left) * 14)) {
						c.pulse = 0;
						spurt(p, w, 0.35F + 0.5F * left);
					}
				}
				if (w.dying() <= 1) {
					die(p);
				} else {
					set(p, w.withDying(w.dying() - 1));
				}
				continue;
			}
			if (w.bleed() > 0) {
				if (++c.bleed >= BLEED_EVERY[w.bleed()]) {
					c.bleed = 0;
					p.hurtServer(level, new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(BLEEDING)), 1.0F);
				}
				if (w.bleed() == Wounds.ARTERIAL) {
					// a cut artery pumps it out with every beat: faster the more blood is gone
					float health = p.getHealth() / Math.max(1.0F, p.getMaxHealth());
					if (++c.pulse >= 9 + Math.round(health * 7)) {
						c.pulse = 0;
						spurt(p, w, 0.8F + 0.25F * health);
					}
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
		if (!w.any() && p.getHealth() >= p.getMaxHealth() || w.incapacitated()) {
			return false;
		}
		if (w.bleed() == Wounds.ARTERIAL) {
			p.displayClientMessage(Component.translatable("message.ballisticmissiles.need_tourniquet").withStyle(ChatFormatting.RED), true);
			p.heal(1.0F);
			return true;
		}
		set(p, w.withBleed(0).withLeg(w.leg() - 1).withArm(w.arm() - 1).headDressed());
		p.heal(3.0F);
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), ModRegistry.GEAR_RUSTLE, SoundSource.PLAYERS, 1.0F, 1.2F);
		p.displayClientMessage(Component.translatable("message.ballisticmissiles.dressed").withStyle(ChatFormatting.GREEN), true);
		return true;
	}

	/** A tourniquet: the bleeding stops at once, artery or not. */
	public static boolean tourniquet(ServerPlayer p) {
		Wounds w = get(p);
		if (w.bleed() == 0 || w.incapacitated()) {
			return false;
		}
		set(p, w.withBleed(0));
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), ModRegistry.GEAR_RUSTLE, SoundSource.PLAYERS, 1.0F, 0.8F);
		p.displayClientMessage(Component.translatable("message.ballisticmissiles.tourniquet_on").withStyle(ChatFormatting.GREEN), true);
		return true;
	}
}
