package de.rcm.ballistic.client.effect;

import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What a Tsar Bomba leaves on the living and on the machines, as you see it:
 * <ul>
 *   <li>the electromagnetic pulse: night vision and lamps flickering and dying, the picture tearing with static -
 *       for the better part of a minute, coming back in fits</li>
 *   <li>the glow: whoever was caught out in it and lived - you, other players, the animals - glows a sickly green
 *       for minutes after, a faint light about them, motes rising off them</li>
 * </ul>
 */
public final class TsarAftermath {
	private static final RandomSource R = RandomSource.create();
	private static final int GLOW_TICKS = 20 * 60 * 8;
	private static final int GLOW = 0x9CFF6A;
	private static long pulseStart = -1L;
	private static float pulsePower;
	private static long glowStart = -1L;
	private static Vec3 groundZero;
	/** Who was out there when it went off (their ids). */
	private static final Set<Integer> CAUGHT = new HashSet<>();

	private TsarAftermath() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(TsarAftermath::tick);
	}

	/** A Tsar Bomba went off at {@code pos}. */
	public static void detonated(Vec3 pos) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		long now = mc.level.getGameTime();
		double d = mc.gameRenderer.getMainCamera().position().distanceTo(pos);
		if (d < 1600.0) {
			pulseStart = now;
			pulsePower = (float) Mth.clamp(1.2 - d / 1600.0 * 0.6, 0.0, 1.0);
		}
		groundZero = pos;
		glowStart = now;
		CAUGHT.clear();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof LivingEntity && (e instanceof Player || e instanceof Animal) && e.position().distanceTo(pos) < 1500.0) {
				CAUGHT.add(e.getId());
			}
		}
	}

	private static float pulse(Minecraft mc) {
		if (pulseStart < 0L || mc.level == null) {
			return 0.0F;
		}
		float t = mc.level.getGameTime() - pulseStart;
		if (t > 1200.0F) {
			pulseStart = -1L;
			return 0.0F;
		}
		return pulsePower * (float) Math.exp(-t / 500.0);
	}

	/** Electronics now (0 dead .. 1 working): fits of flicker and dropout while the pulse lasts. */
	public static float electronics() {
		Minecraft mc = Minecraft.getInstance();
		float p = pulse(mc);
		if (p <= 0.0F) {
			return 1.0F;
		}
		float t = (System.nanoTime() % 100_000_000_000L) / 1.0E9F;
		// out completely at first, then on and off, flickering, steadier as it fades
		float fits = 0.5F + 0.5F * Mth.sin(t * 2.3F) * Mth.sin(t * 0.71F + 1.0F);
		float flicker = R.nextFloat() < 0.3F + 0.5F * p ? R.nextFloat() * 0.6F : 1.0F;
		float on = p > 0.75F ? 0.0F : Math.min(1.0F, fits * 1.6F) * flicker;
		return Mth.lerp(Math.min(1.0F, p * 1.3F), 1.0F, on);
	}

	/** Static over the picture (0-1). */
	public static float noise() {
		return Mth.clamp(pulse(Minecraft.getInstance()) * 1.3F, 0.0F, 1.0F) * (0.6F + 0.4F * R.nextFloat());
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null) {
			glowStart = pulseStart = -1L;
			CAUGHT.clear();
			return;
		}
		if (glowStart < 0L || mc.isPaused()) {
			return;
		}
		long age = mc.level.getGameTime() - glowStart;
		if (age > GLOW_TICKS) {
			glowStart = -1L;
			CAUGHT.clear();
			return;
		}
		// it comes up over the first half minute, then slowly fades
		float k = Mth.clamp(age / 600.0F, 0.0F, 1.0F) * (1.0F - (float) age / GLOW_TICKS);
		Vec3 cam = mc.gameRenderer.getMainCamera().position();
		int lamps = 0;
		for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, new AABB(cam, cam).inflate(64.0))) {
			if (!e.isAlive() || !CAUGHT.contains(e.getId())) {
				continue;
			}
			float near = (float) Mth.clamp(1.4 - e.position().distanceTo(groundZero) / 1500.0, 0.3, 1.0);
			float glow = k * near;
			if (lamps < 24) {
				lamps++;
				DynamicLights.glow(0x7A5A0000L + e.getId(), e.position().add(0.0, e.getBbHeight() * 0.6, 0.0), GLOW, 0.9F * glow, 3.5F + 2.5F * glow);
			}
			if (R.nextFloat() < 0.6F * glow) {
				double x = e.getX() + (R.nextDouble() - 0.5) * e.getBbWidth() * 1.4;
				double y = e.getY() + R.nextDouble() * e.getBbHeight();
				double z = e.getZ() + (R.nextDouble() - 0.5) * e.getBbWidth() * 1.4;
				mc.level.addParticle(ParticleTypes.GLOW, x, y, z, 0.0, 0.015 + R.nextDouble() * 0.02, 0.0);
			}
		}
	}
}
