package de.rcm.ballistic.explosion;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's explosion - the block damage, the push, the hurt - without Minecraft's look and sound:
 * no TNT bang (the mod plays its own recorded blasts), no pixel puffs (a small fireball rolling into
 * smoke instead), and grit instead of the white poofs flying out of the torn-up blocks.
 */
public final class Blasts {
	private static final WeightedList<ExplosionParticleInfo> BLOCK_PARTICLES = WeightedList.<ExplosionParticleInfo>builder()
		.add(new ExplosionParticleInfo(ModRegistry.DUST, 0.6F, 1.0F))
		.build();

	private Blasts() {
	}

	public static void explode(ServerLevel level, @Nullable Entity source, double x, double y, double z, float power, boolean fire,
		Level.ExplosionInteraction interaction) {
		level.explode(source, Explosion.getDefaultDamageSource(level, source), null, x, y, z, power, fire, interaction,
			ModRegistry.BURST, ModRegistry.BURST, BLOCK_PARTICLES, Holder.direct(ModRegistry.SILENCE));
	}
}
