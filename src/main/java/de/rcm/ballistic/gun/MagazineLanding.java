package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Magazines dropped or knocked out during a reload: watched until they land, then they clatter the way
 * a steel magazine would on whatever they hit - a hard ring on stone, a hollow knock on wood, a dull
 * thud on grass, earth or sand, a splash in water. A light bounce gives a second, quieter knock.
 */
public final class MagazineLanding {
	private static final List<Falling> FALLING = new ArrayList<>();

	private static final Set<SoundType> METAL = Set.of(SoundType.METAL, SoundType.ANVIL, SoundType.CHAIN, SoundType.LANTERN, SoundType.COPPER,
		SoundType.COPPER_BULB, SoundType.COPPER_GRATE, SoundType.IRON, SoundType.NETHERITE_BLOCK, SoundType.HEAVY_CORE, SoundType.LODESTONE,
		SoundType.VAULT, SoundType.TRIAL_SPAWNER, SoundType.SPAWNER);
	private static final Set<SoundType> WOOD = Set.of(SoundType.WOOD, SoundType.NETHER_WOOD, SoundType.BAMBOO_WOOD, SoundType.CHERRY_WOOD,
		SoundType.LADDER, SoundType.SCAFFOLDING, SoundType.CHISELED_BOOKSHELF, SoundType.SHELF, SoundType.BAMBOO, SoundType.HANGING_SIGN,
		SoundType.NETHER_WOOD_HANGING_SIGN, SoundType.BAMBOO_WOOD_HANGING_SIGN, SoundType.CHERRY_WOOD_HANGING_SIGN, SoundType.STEM);
	/** Earth, plants, snow, cloth: the magazine sinks in a little, hardly any ring. */
	private static final Set<SoundType> SOFT = Set.of(SoundType.GRASS, SoundType.WET_GRASS, SoundType.MOSS, SoundType.MOSS_CARPET,
		SoundType.ROOTED_DIRT, SoundType.MUD, SoundType.SAND, SoundType.SUSPICIOUS_SAND, SoundType.SNOW, SoundType.POWDER_SNOW, SoundType.WOOL,
		SoundType.SOUL_SAND, SoundType.SOUL_SOIL, SoundType.NYLIUM, SoundType.AZALEA_LEAVES, SoundType.CHERRY_LEAVES, SoundType.LEAF_LITTER,
		SoundType.PINK_PETALS, SoundType.CROP, SoundType.HARD_CROP, SoundType.VINE, SoundType.SCULK, SoundType.SLIME_BLOCK,
		SoundType.HONEY_BLOCK, SoundType.SPONGE, SoundType.WET_SPONGE, SoundType.MUDDY_MANGROVE_ROOTS, SoundType.WART_BLOCK, SoundType.LILY_PAD);
	/** Loose stones: a crunch with a bit of steel in it. */
	private static final Set<SoundType> GRAVEL = Set.of(SoundType.GRAVEL, SoundType.SUSPICIOUS_GRAVEL);

	private MagazineLanding() {
	}

	public static void watch(ItemEntity magazine) {
		FALLING.add(new Falling(magazine));
	}

	public static void tick(ServerLevel level) {
		if (FALLING.isEmpty()) {
			return;
		}
		for (Iterator<Falling> it = FALLING.iterator(); it.hasNext();) {
			Falling f = it.next();
			ItemEntity e = f.entity;
			if (e.isRemoved() || e.getAge() > 200 || f.knocks >= 2) {
				it.remove();
				continue;
			}
			if (e.level() != level) {
				continue;
			}
			if (e.isInWater()) {
				if (!f.wasInWater) {
					float v = (float) Math.min(1.0, 0.25 + Math.abs(f.lastVy) * 1.5);
					play(level, e, ModRegistry.WATER_SPLASH_SMALL, 0.6F * v, 1.1F);
				}
				f.wasInWater = true;
				f.knocks = 2; // sinks quietly from here
				continue;
			}
			boolean ground = e.onGround();
			// hits the ground fast enough to be heard (after the first knock, a bounce has to be livelier)
			if (ground && !f.wasOnGround && f.lastVy < (f.knocks == 0 ? -0.05 : -0.1)) {
				knock(level, e, (float) Math.min(1.0, -f.lastVy * 2.2 + 0.35) * (f.knocks == 0 ? 1.0F : 0.45F));
				f.knocks++;
			}
			f.wasOnGround = ground;
			f.lastVy = e.getDeltaMovement().y;
		}
	}

	/**
	 * A steel object (magazine, grenade) striking the ground it lies on: the sound of the knock
	 * depends on the block. {@code loud} 0..1 is how hard it hit.
	 */
	public static void knock(ServerLevel level, Entity e, float loud) {
		BlockPos at = e.blockPosition();
		BlockState on = level.getBlockState(at);
		// carpet, snow layers, slabs: the block the magazine is in is the one it lies on
		if (on.getCollisionShape(level, at).isEmpty()) {
			at = e.getOnPos();
			on = level.getBlockState(at);
		}
		SoundType type = on.getSoundType();
		RandomSource r = level.getRandom();
		float jitter = 0.94F + r.nextFloat() * 0.12F;
		if (METAL.contains(type)) {
			// steel on steel: a bright clang
			play(level, e, ModRegistry.MAG_DROP, 1.0F * loud, 1.15F * jitter);
			play(level, e, ModRegistry.BULLET_IMPACT_METAL, 0.12F * loud, 1.3F * jitter);
		} else if (WOOD.contains(type)) {
			// a hollow knock, the steel only rattles a little
			play(level, e, type.getHitSound(), 0.9F * loud, 0.75F * jitter);
			play(level, e, type.getStepSound(), 0.6F * loud, 0.9F * jitter);
			play(level, e, ModRegistry.MAG_DROP, 0.6F * loud, 0.9F * jitter);
		} else if (SOFT.contains(type)) {
			// a dull thud into the earth
			play(level, e, type.getStepSound(), 0.85F * loud, 0.65F * jitter);
			play(level, e, type.getHitSound(), 0.5F * loud, 0.6F);
			play(level, e, ModRegistry.MAG_DROP, 0.2F * loud, 0.7F * jitter);
		} else if (GRAVEL.contains(type)) {
			play(level, e, type.getStepSound(), 0.9F * loud, 0.8F * jitter);
			play(level, e, ModRegistry.MAG_DROP, 0.5F * loud, 0.95F * jitter);
		} else if (type == SoundType.GLASS) {
			play(level, e, SoundEvents.GLASS_HIT, 0.9F * loud, 1.3F * jitter);
			play(level, e, ModRegistry.MAG_DROP, 0.7F * loud, 1.05F * jitter);
		} else {
			// stone, deepslate, bricks, concrete and the like: hard, with the steel ringing out
			play(level, e, ModRegistry.MAG_DROP, 1.0F * loud, 1.0F * jitter);
			play(level, e, type.getHitSound(), 0.7F * loud, 1.1F * jitter);
		}
	}

	private static void play(ServerLevel level, Entity e, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, e.getX(), e.getY(), e.getZ(), sound, SoundSource.PLAYERS, volume, pitch);
	}

	private static final class Falling {
		final ItemEntity entity;
		double lastVy;
		boolean wasOnGround;
		boolean wasInWater;
		int knocks;

		Falling(ItemEntity entity) {
			this.entity = entity;
		}
	}
}
