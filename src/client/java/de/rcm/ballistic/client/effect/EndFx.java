package de.rcm.ballistic.client.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.hurtingprojectile.DragonFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/**
 * The End, alive: the crystals on the obsidian pillars really shine, pulsing pink-white over the island, and the
 * beam that heals the dragon lights the air along it; the dragon's acid breath hangs in heavy, glowing violet fog
 * and its fireballs trail it, lighting the ground they fall on; its roar rolls out across the void and comes back,
 * fainter, again and again. And in the End cities the end rods glow and pulse in the purpur towers.
 */
public final class EndFx {
	private static final RandomSource R = RandomSource.create();
	/** The roar's echoes we play ourselves (so they do not echo again). */
	private static final Set<SoundInstance> ECHOES = Collections.newSetFromMap(new WeakHashMap<>());
	private static final List<long[]> PENDING = new ArrayList<>();
	private static final List<BlockPos> RODS = new ArrayList<>();
	private static int scan;

	private EndFx() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(EndFx::tick);
	}

	/**
	 * A sound about to play (SoundEngineMixin): the dragon's roar gets its echoes off the void - three, each later,
	 * fainter and a little lower, as if from ever further away.
	 */
	public static void played(SoundInstance sound) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || ECHOES.contains(sound) || !sound.getIdentifier().equals(SoundEvents.ENDER_DRAGON_GROWL.location())) {
			return;
		}
		long now = mc.level.getGameTime();
		for (int i = 1; i <= 3; i++) {
			PENDING.add(new long[] {now + 9L * i + R.nextInt(4), Math.round(sound.getX() * 16.0), Math.round(sound.getY() * 16.0), Math.round(sound.getZ() * 16.0), i});
		}
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			PENDING.clear();
			RODS.clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		long now = level.getGameTime();
		for (int i = PENDING.size() - 1; i >= 0; i--) {
			long[] e = PENDING.get(i);
			if (now >= e[0]) {
				PENDING.remove(i);
				float k = (float) Math.pow(0.45, e[4]);
				SoundInstance echo = new SimpleSoundInstance(SoundEvents.ENDER_DRAGON_GROWL, net.minecraft.sounds.SoundSource.HOSTILE, 4.0F * k,
					0.94F - 0.03F * e[4], R, e[1] / 16.0, e[2] / 16.0, e[3] / 16.0);
				ECHOES.add(echo);
				mc.getSoundManager().play(echo);
			}
		}
		if (level.dimension() != Level.END || !de.rcm.ballistic.client.ModConfig.endEffects) {
			RODS.clear();
			return;
		}
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		float t = now + 0.0F;
		for (Entity e : level.entitiesForRendering()) {
			if (e.distanceToSqr(cam) > 300.0 * 300.0) {
				continue;
			}
			if (e instanceof EndCrystal crystal) {
				// the crystal: a pink-white light pulsing with its spin, lighting the pillar top and round it
				float pulse = 0.8F + 0.25F * Mth.sin((t + crystal.time) * 0.15F);
				DynamicLights.lamp(e.getId() * 7L + 1L, e.position().add(0.0, 1.2, 0.0), 0xFF9AE6, 1.1F * pulse, 22.0F);
			} else if (e instanceof EnderDragon dragon && dragon.nearestCrystal != null) {
				// the healing beam: light all along it
				Vec3 a = dragon.nearestCrystal.position().add(0.0, 1.2, 0.0);
				Vec3 b = dragon.position().add(0.0, 2.0, 0.0);
				for (int k = 1; k <= 3; k++) {
					DynamicLights.lamp(e.getId() * 7L + 10L + k, a.lerp(b, k / 4.0), 0xF0B8FF, 0.7F, 12.0F);
				}
			} else if (e instanceof AreaEffectCloud cloud && cloud.getParticle().getType() == ParticleTypes.DRAGON_BREATH) {
				breath(cloud);
			} else if (e instanceof DragonFireball ball) {
				DynamicLights.lamp(e.getId() * 7L + 3L, ball.position(), 0xC060FF, 1.0F, 14.0F);
				if (R.nextFloat() < 0.8F) {
					SmokeField.puff(ball.getX(), ball.getY(), ball.getZ(), (R.nextFloat() - 0.5F) * 0.04, 0.01, (R.nextFloat() - 0.5F) * 0.04, 50,
						0.5F, 2.2F, 0x8A3AC8, 0.45F, 0.6F, 0.01F);
				}
			}
		}
		cityLights(mc, level, cam, now);
	}

	/** The acid breath: a heavy violet fog hugging the ground where it lies, glowing faintly, lighting round it. */
	private static void breath(AreaEffectCloud cloud) {
		float r = cloud.getRadius();
		if (r <= 0.1F) {
			return;
		}
		DynamicLights.lamp(cloud.getId() * 7L + 2L, cloud.position().add(0.0, 0.8, 0.0), 0xB050FF, 0.9F, r + 6.0F);
		int n = Math.max(1, Math.round(r * 0.6F));
		for (int i = 0; i < n; i++) {
			if (R.nextFloat() > 0.5F) {
				continue;
			}
			double a = R.nextDouble() * Math.PI * 2.0;
			double d = Math.sqrt(R.nextDouble()) * r;
			SmokeField.puff(cloud.getX() + Math.cos(a) * d, cloud.getY() + 0.3, cloud.getZ() + Math.sin(a) * d, (R.nextFloat() - 0.5F) * 0.02, 0.004,
				(R.nextFloat() - 0.5F) * 0.02, 70 + R.nextInt(40), 1.0F, 2.8F, 0x7A2AB8, 0.55F, 0.45F, -0.002F);
		}
	}

	/** End cities: the end rods round you glow and slowly pulse, each to its own beat. */
	private static void cityLights(Minecraft mc, ClientLevel level, Vec3 cam, long now) {
		if (++scan % 40 == 0) {
			RODS.clear();
			BlockPos at = BlockPos.containing(cam);
			int r = 3;
			for (int cx = (at.getX() >> 4) - r; cx <= (at.getX() >> 4) + r; cx++) {
				for (int cz = (at.getZ() >> 4) - r; cz <= (at.getZ() >> 4) + r; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) {
						continue;
					}
					LevelChunkSection[] sections = chunk.getSections();
					for (int i = 0; i < sections.length; i++) {
						LevelChunkSection s = sections[i];
						if (s.hasOnlyAir() || !s.maybeHas(b -> b.is(Blocks.END_ROD))) {
							continue;
						}
						int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
						for (int y = 0; y < 16; y++) {
							for (int z = 0; z < 16; z++) {
								for (int x = 0; x < 16; x++) {
									if (s.getBlockState(x, y, z).is(Blocks.END_ROD)) {
										RODS.add(new BlockPos(SectionPos.sectionToBlockCoord(cx) + x, baseY + y, SectionPos.sectionToBlockCoord(cz) + z));
									}
								}
							}
						}
					}
				}
			}
			RODS.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(cam)));
			if (RODS.size() > 48) {
				RODS.subList(48, RODS.size()).clear();
			}
		}
		for (BlockPos p : RODS) {
			float phase = (p.hashCode() & 1023) / 1023.0F * Mth.TWO_PI;
			float pulse = 0.65F + 0.35F * Mth.sin(now * 0.06F + phase);
			DynamicLights.blockLamp(p, 0, 0.0F, 0.5F, 0.0F, 0xEBDCFF, 0.75F * pulse, 9.0F);
		}
	}
}
