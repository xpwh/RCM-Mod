package de.rcm.ballistic.item;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.MissileType;
import de.rcm.ballistic.explosion.DetonationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Fire missions the field radio can call that are not aircraft: a Tomahawk salvo and an artillery barrage. */
public final class FireSupport {
	private FireSupport() {
	}

	/**
	 * Tomahawk salvo: four cruise missiles ripple-launched from a battery behind the caller, a second
	 * apart, each aimed at a slightly different point around the target.
	 */
	public static boolean tomahawkSalvo(ServerLevel level, ServerPlayer caller, Vec3 target) {
		Vec3 from = caller.position();
		Vec3 heading = new Vec3(target.x - from.x, 0, target.z - from.z);
		if (heading.lengthSqr() < 1.0) {
			return false;
		}
		Vec3 dir = heading.normalize();
		Vec3 side = new Vec3(-dir.z, 0, dir.x);
		RandomSource random = level.getRandom();
		for (int i = 0; i < 4; i++) {
			int n = i;
			DetonationManager.later(level, 1 + i * 20, () -> {
				double lateral = (n - 1.5) * 9.0;
				Vec3 at = from.subtract(dir.scale(30.0 + n * 4.0)).add(side.scale(lateral));
				int x = Mth.floor(at.x);
				int z = Mth.floor(at.z);
				if (!level.hasChunk(x >> 4, z >> 4)) {
					return;
				}
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				MissileEntity missile = ModRegistry.missileEntity(MissileType.CRUISE).create(level, EntitySpawnReason.TRIGGERED);
				if (missile == null) {
					return;
				}
				missile.setPos(x + 0.5, y + 0.2, z + 0.5);
				level.addFreshEntity(missile);
				BlockPos aim = BlockPos.containing(target.add(side.scale((n - 1.5) * 4.0)).add(random.nextGaussian() * 2.0, 0, random.nextGaussian() * 2.0));
				missile.launchNow(new TargetData(aim, false));
			});
		}
		return true;
	}

	/**
	 * Artillery barrage: a battery far behind the lines fires; some seconds later about twenty
	 * 155 mm shells come down in and around the target area over ten seconds, each announced by its
	 * whistle a moment before it hits.
	 */
	public static void artilleryBarrage(ServerLevel level, ServerPlayer caller, Vec3 target) {
		RandomSource random = level.getRandom();
		Vec3 from = caller.position();
		Vec3 back = new Vec3(from.x - target.x, 0, from.z - target.z);
		back = back.lengthSqr() > 1.0 ? back.normalize() : new Vec3(1, 0, 0);
		Vec3 guns = from.add(back.scale(60.0)).add(0, 4, 0);
		int rounds = 20;
		for (int i = 0; i < rounds; i++) {
			int fire = i / 4 * 22 + random.nextInt(8); // salvos of four
			int flight = 90 + random.nextInt(12);
			double spread = i < 4 ? 26.0 : 14.0; // ranging shots first, then fire for effect
			double x = target.x + random.nextGaussian() * spread * 0.6;
			double z = target.z + random.nextGaussian() * spread * 0.6;
			DetonationManager.later(level, 1 + fire, () ->
				level.playSound(null, guns.x, guns.y, guns.z, ModRegistry.GUN_105, SoundSource.HOSTILE, 18.0F, 0.6F + random.nextFloat() * 0.1F));
			// the incoming shriek: the whistle (four seconds at pitch 1) sped up, ending as the shell lands
			float shriek = 1.25F + random.nextFloat() * 0.1F;
			DetonationManager.later(level, 1 + fire + flight - (int) (80 / shriek), () ->
				level.playSound(null, x, target.y + 20, z, ModRegistry.BOMB_WHISTLE, SoundSource.HOSTILE, 6.0F, shriek));
			DetonationManager.later(level, 1 + fire + flight, () -> {
				int bx = Mth.floor(x);
				int bz = Mth.floor(z);
				if (!level.hasChunk(bx >> 4, bz >> 4)) {
					return;
				}
				double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
				DetonationManager.detonateArtilleryShell(level, new Vec3(x, y, z));
			});
		}
	}
}
