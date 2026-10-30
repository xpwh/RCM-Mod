package de.rcm.ballistic.client.effect;

import de.rcm.ballistic.ModRegistry;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The sounds of the night round the player: crickets chirping in the grass all around, frogs calling from the water,
 * now and then an owl in the woods. Each is a sound at a real place - a cricket over there in the meadow, the
 * frogs down at the pond - so the night has depth.
 * <p>
 * And like the animals ({@link AmbientLife}) they go quiet when something is fired: everything round the shot
 * stops at once, and the crickets only start up again one by one, a good while after.
 */
public final class NightSounds {
	private static final RandomSource R = RandomSource.create();
	/** After a hush the night comes back slowly over this long, ticks. */
	private static final int RECOVER = 1600;

	private record Calling(SimpleSoundInstance sound, Vec3 at, boolean cricket) {
	}

	private record Hush(Vec3 at, double radius, long until) {
	}

	private static final List<Calling> CALLING = new ArrayList<>();
	private static final List<Hush> HUSHED = new ArrayList<>();
	private static long tick;

	private NightSounds() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(NightSounds::tick);
	}

	/** Something loud at {@code at}: every caller within {@code radius} falls silent, for a while. */
	public static void hush(Vec3 at, double radius, int quietTicks) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		HUSHED.add(new Hush(at, radius * 1.3, mc.level.getGameTime() + quietTicks));
		double r2 = radius * radius * 1.69;
		CALLING.removeIf(c -> {
			if (c.at().distanceToSqr(at) < r2) {
				mc.getSoundManager().stop(c.sound());
				return true;
			}
			return false;
		});
	}

	/** How much the night has come back at {@code p}: 0 just after a shot, 1 when it has long forgotten it. */
	private static float calm(Vec3 p, long now) {
		float calm = 1.0F;
		for (Hush h : HUSHED) {
			if (h.at().distanceToSqr(p) < h.radius() * h.radius()) {
				calm = Math.min(calm, Mth.clamp((now - h.until()) / (float) RECOVER, 0.0F, 1.0F));
			}
		}
		return calm;
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			CALLING.clear();
			HUSHED.clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		tick++;
		long now = level.getGameTime();
		HUSHED.removeIf(h -> now > h.until() + RECOVER);
		CALLING.removeIf(c -> !mc.getSoundManager().isActive(c.sound()));
		if (!level.dimensionType().hasSkyLight() || level.dimensionType().hasCeiling() || mc.player.isSpectator()) {
			return;
		}
		long t = level.getDayTime() % 24000L;
		// dusk to dawn; the crickets start a little before dark
		boolean night = t > 12600L && t < 23300L;
		boolean deep = t > 13400L && t < 22700L;
		if (!night) {
			return;
		}
		Vec3 p = mc.player.position();
		BlockPos at = mc.player.blockPosition();
		var holder = level.getBiome(at);
		Biome biome = holder.value();
		float warmth = biome.getBaseTemperature();
		boolean rain = level.isRaining();
		boolean wild = !holder.is(BiomeTags.IS_OCEAN) && warmth > 0.15F;
		if (!wild || p.y < level.getSeaLevel() - 20) {
			return; // deep underground, out at sea: nothing calls
		}

		// crickets: many, all round, in the grass - warm, dry nights
		int crickets = (int) CALLING.stream().filter(Calling::cricket).count();
		if (!rain && warmth >= 0.45F && warmth < 1.6F && crickets < 7 && tick % 6 == 0) {
			Vec3 spot = around(level, p, 5.0, 26.0);
			BlockState ground = level.getBlockState(BlockPos.containing(spot).below());
			BlockState on = level.getBlockState(BlockPos.containing(spot));
			boolean grassy = ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.MOSS_BLOCK) || on.is(Blocks.SHORT_GRASS) || on.is(Blocks.TALL_GRASS)
				|| on.is(BlockTags.FLOWERS) || on.is(Blocks.FERN);
			if (grassy && R.nextFloat() < calm(spot, now)) {
				play(mc, ModRegistry.NIGHT_CRICKET, spot, 0.3F + R.nextFloat() * 0.3F, 0.94F + R.nextFloat() * 0.12F);
			}
		}
		// frogs: at the water; a swamp is full of them, and they like the rain
		boolean swamp = holder.is(Biomes.SWAMP) || holder.is(Biomes.MANGROVE_SWAMP);
		if (warmth >= 0.4F && tick % 20 == 0 && R.nextFloat() < (swamp ? 0.7F : 0.35F)) {
			Vec3 spot = around(level, p, 8.0, 40.0);
			BlockPos s = BlockPos.containing(spot).below();
			if (level.getBlockState(s).is(Blocks.WATER) && near(level, s) && R.nextFloat() < calm(spot, now)) {
				boolean chorus = (swamp || rain) && R.nextFloat() < 0.35F;
				play(mc, chorus ? ModRegistry.NIGHT_FROG_CHORUS : ModRegistry.NIGHT_FROG, spot.add(0, 0.2, 0), chorus ? 0.8F : 0.6F,
					0.9F + R.nextFloat() * 0.2F);
			}
		}
		// an owl: in the trees, far off, now and then, deep in the night
		if (deep && !rain && tick % 200 == 0 && R.nextFloat() < 0.4F) {
			Vec3 spot = around(level, p, 28.0, 64.0);
			BlockPos s = BlockPos.containing(spot).below();
			if (level.getBlockState(s).is(BlockTags.LEAVES) && R.nextFloat() < calm(spot, now) && calm(p, now) > 0.6F) {
				boolean female = R.nextFloat() < 0.3F;
				play(mc, female ? ModRegistry.NIGHT_OWL_FEMALE : ModRegistry.NIGHT_OWL, spot.add(0, -1.5, 0), 1.2F, 0.95F + R.nextFloat() * 0.1F);
			}
		}
	}

	/** A spot on the surface somewhere round {@code p}, between {@code min} and {@code max} away. */
	private static Vec3 around(ClientLevel level, Vec3 p, double min, double max) {
		float a = R.nextFloat() * Mth.TWO_PI;
		double d = min + R.nextDouble() * (max - min);
		int x = Mth.floor(p.x + Mth.cos(a) * d);
		int z = Mth.floor(p.z + Mth.sin(a) * d);
		int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		return new Vec3(x + 0.5, h, z + 0.5);
	}

	/** Water by the bank (where frogs sit), not out in a lake: land within a few blocks. */
	private static boolean near(ClientLevel level, BlockPos water) {
		for (int i = 0; i < 6; i++) {
			BlockPos q = water.offset(R.nextInt(9) - 4, 0, R.nextInt(9) - 4);
			BlockState s = level.getBlockState(q);
			if (!s.is(Blocks.WATER) && !s.isAir()) {
				return true;
			}
		}
		return false;
	}

	private static void play(Minecraft mc, SoundEvent event, Vec3 at, float volume, float pitch) {
		SimpleSoundInstance sound = new SimpleSoundInstance(event, SoundSource.AMBIENT, volume, pitch, R, at.x, at.y, at.z);
		mc.getSoundManager().play(sound);
		CALLING.add(new Calling(sound, at, event == ModRegistry.NIGHT_CRICKET));
	}
}
