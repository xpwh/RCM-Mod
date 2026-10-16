package de.rcm.ballistic.gun;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.network.ModNetworking.FlashbangPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * The stun grenade going off: a flash of some millions of candela and a bang of 170 dB, no fragments. Whoever
 * has it in sight is blinded - for longer the nearer it was and the more squarely they were looking at it -
 * and everyone near is deafened, their ears ringing, whether they saw it or not. Players get the white-out,
 * the burnt-in afterimage and the muffled ringing on their screen; animals, monsters and soldiers are blinded
 * and slowed and lose their target.
 */
public final class Flashbang {
	private static final double SIGHT_RANGE = 22.0;
	private static final double HEARING_RANGE = 16.0;

	private Flashbang() {
	}

	public static void detonate(ServerLevel level, Vec3 pos, @Nullable Entity thrower) {
		level.playSound(null, pos.x, pos.y, pos.z, ModRegistry.FLASHBANG_BANG, SoundSource.PLAYERS, 4.0F, 0.95F + level.getRandom().nextFloat() * 0.1F);
		level.playSound(null, pos.x, pos.y, pos.z, ModRegistry.FLASHBANG_FAR, SoundSource.PLAYERS, 8.0F, 1.0F);
		level.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 1, 0.0, 0.0, 0.0, 0.0);
		level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 24, 0.1, 0.1, 0.1, 0.25);
		level.sendParticles(ParticleTypes.POOF, pos.x, pos.y, pos.z, 10, 0.25, 0.15, 0.25, 0.02);
		double reach = Math.max(SIGHT_RANGE, HEARING_RANGE);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(reach))) {
			Vec3 eye = e.getEyePosition();
			double d = eye.distanceTo(pos);
			boolean seen = level.clip(new ClipContext(pos, eye, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty())).getType()
				== HitResult.Type.MISS;
			// how hard the light hits: falls off with distance; full when looking at it, much less with it behind you
			Vec3 toward = pos.subtract(eye).normalize();
			double facing = e.getViewVector(1.0F).dot(toward);
			double view = 0.2 + 0.8 * Mth.clamp((facing + 0.3) / 1.1, 0.0, 1.0);
			double near = Mth.clamp(1.0 - d / SIGHT_RANGE, 0.0, 1.0);
			float blind = seen ? (float) (Math.pow(near, 0.6) * view) : 0.0F;
			// the bang: through walls too, a little less
			float deaf = (float) Mth.clamp((1.0 - d / HEARING_RANGE) * (seen ? 1.0 : 0.6), 0.0, 1.0);
			if (d < 1.5) {
				e.hurtServer(level, level.damageSources().explosion(thrower, thrower), 2.0F);
			}
			if (e instanceof ServerPlayer player) {
				if (player.isCreative() || player.isSpectator()) {
					continue;
				}
				if (blind > 0.02F || deaf > 0.02F) {
					ServerPlayNetworking.send(player, new FlashbangPayload(pos, blind, deaf));
				}
			} else if (blind > 0.15F) {
				int ticks = (int) (60 + 180 * blind);
				e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, ticks, 0, false, false));
				e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 2, false, false));
				e.addEffect(new MobEffectInstance(MobEffects.NAUSEA, ticks, 0, false, false));
				if (e instanceof Mob mob) {
					mob.setTarget(null);
					mob.getNavigation().stop();
				}
			}
		}
	}
}
