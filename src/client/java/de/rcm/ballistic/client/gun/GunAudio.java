package de.rcm.ballistic.client.gun;

import de.rcm.ballistic.ModRegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * How a gunshot sounds where you are:
 * <ul>
 *   <li>the report travels at the speed of sound - a shot 300 blocks away is heard almost a second
 *       after the muzzle flash, after the bullet's crack if it came your way;</li>
 *   <li>close it is a hard, deafening crack with the rifle's mechanical clatter; further off it loses
 *       its top and becomes a flat bang, far away a dull pop and rumble;</li>
 *   <li>the place it is fired in rings after it: a short, hard slap in a room, a long dense roar in a
 *       cave or a big hall, and out in the open the echoes thrown back by hills and buildings, each
 *       arriving after its own detour.</li>
 * </ul>
 */
public final class GunAudio {
	private static final double SPEED_OF_SOUND = 17.15;
	private static final RandomSource RANDOM = RandomSource.create();

	private record Pending(SoundEvent sound, Vec3 at, float volume, float pitch, int[] delay) {
	}

	private static final List<Pending> PENDING = new ArrayList<>();

	/** Acoustic surroundings of a firing position. */
	enum Space {
		OUTDOOR,
		ROOM,
		CAVE
	}

	record Acoustics(Space space, double[] echoes, Vec3[] echoDirs) {
	}

	private GunAudio() {
	}

	/** Casts rays round {@code pos} to find walls and ceiling: room, cave or open air with echoing walls. */
	static Acoustics survey(Level level, Vec3 pos) {
		Vec3 from = pos.add(0, 0.3, 0);
		double up = cast(level, from, new Vec3(0, 1, 0), 48.0);
		int near = 0;
		int mid = 0;
		double[] echoes = new double[3];
		Vec3[] dirs = new Vec3[3];
		int found = 0;
		int rays = 12;
		for (int i = 0; i < rays; i++) {
			double a = i * Mth.TWO_PI / rays;
			for (double elev : new double[] {0.0, 0.45}) {
				Vec3 d = new Vec3(Math.cos(a) * Math.cos(elev), Math.sin(elev), Math.sin(a) * Math.cos(elev));
				double hit = cast(level, from, d, 140.0);
				if (hit < 9.0) {
					near++;
				} else if (hit < 40.0) {
					mid++;
				}
				// a distant face in the open: an echo, if it is far enough to be heard apart from the shot
				if (elev == 0.0 && hit > 18.0 && hit < 140.0 && found < 3 && RANDOM.nextFloat() < 0.6F) {
					echoes[found] = hit;
					dirs[found] = d;
					found++;
				}
			}
		}
		double[] e = new double[found];
		Vec3[] ed = new Vec3[found];
		System.arraycopy(echoes, 0, e, 0, found);
		System.arraycopy(dirs, 0, ed, 0, found);
		if (up < 12.0 && near >= rays) {
			return new Acoustics(Space.ROOM, new double[0], new Vec3[0]);
		}
		if (up < 48.0 && near + mid >= rays + rays / 2) {
			return new Acoustics(Space.CAVE, new double[0], new Vec3[0]);
		}
		return new Acoustics(Space.OUTDOOR, e, ed);
	}

	private static double cast(Level level, Vec3 from, Vec3 dir, double range) {
		BlockHitResult hit = level.clip(new ClipContext(from, from.add(dir.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
			CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : hit.getLocation().distanceTo(from);
	}

	/** A shot fired at {@code muzzle}; {@code own} when the local player fired it. */
	public static void shot(Vec3 muzzle, boolean own) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		double d = own ? 0.0 : ear.distanceTo(muzzle);
		if (d > 900.0) {
			return;
		}
		int delay = (int) Math.round(d / SPEED_OF_SOUND);
		float jitter = 0.95F + RANDOM.nextFloat() * 0.1F;
		Acoustics room = survey(mc.level, muzzle);
		if (d < 48.0) {
			// the report itself: a real AK, recorded beside the shooter
			later(ModRegistry.AK_SHOT, muzzle, 1.0F, jitter, delay);
		} else if (d < 260.0) {
			float v = (float) (1.15 - d / 330.0);
			later(ModRegistry.AK_SHOT_MID, muzzle, v, jitter, delay);
		} else {
			later(ModRegistry.AK_SHOT_FAR, muzzle, (float) (1.2 - d / 1000.0), jitter, delay);
		}
		// what the surroundings make of it
		float tail = (float) Mth.clamp(1.0 - d / 700.0, 0.15, 1.0);
		switch (room.space()) {
			case ROOM -> later(ModRegistry.AK_TAIL_INDOOR, muzzle, tail, jitter, delay);
			case CAVE -> later(ModRegistry.AK_TAIL_CAVE, muzzle, tail, jitter, delay);
			default -> {
				later(ModRegistry.AK_TAIL_OUTDOOR, muzzle, tail * 0.5F, jitter, delay);
				// echoes off hills and buildings: for whoever hears the shot from far off, not for the shooter
				// or anyone standing by them (to them the real tail already carries the space)
				for (int i = 0; i < (own || d < 48.0 ? 0 : room.echoes().length); i++) {
					// the echo travels to the wall and from there to the listener
					Vec3 wall = muzzle.add(room.echoDirs()[i].scale(room.echoes()[i]));
					double path = room.echoes()[i] + wall.distanceTo(ear);
					float v = (float) Mth.clamp(0.55 - path / 500.0, 0.05, 0.5);
					later(ModRegistry.AK_SHOT_FAR, wall, v, jitter * 0.95F, (int) Math.round(path / SPEED_OF_SOUND));
				}
			}
		}
	}

	/** Plays {@code sound} from {@code at} after {@code delay} ticks. */
	public static void later(SoundEvent sound, Vec3 at, float volume, float pitch, int delay) {
		if (volume <= 0.01F) {
			return;
		}
		if (delay <= 0) {
			play(sound, at, volume, pitch);
			return;
		}
		PENDING.add(new Pending(sound, at, volume, pitch, new int[] {delay}));
	}

	/**
	 * Plays a sound from the direction of {@code at} with no distance roll-off of its own (the volume
	 * already says how far it is): placed close to the listener towards the source.
	 */
	public static void play(SoundEvent sound, Vec3 at, float volume, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		Vec3 dir = at.subtract(ear);
		double len = dir.length();
		Vec3 p = len < 4.0 ? at : ear.add(dir.scale(4.0 / len));
		mc.getSoundManager().play(new SimpleSoundInstance(sound.location(), SoundSource.PLAYERS, Mth.clamp(volume, 0.0F, 1.0F), pitch, RANDOM, false, 0,
			SoundInstance.Attenuation.NONE, p.x, p.y, p.z, false));
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null) {
			PENDING.clear();
			return;
		}
		if (PENDING.isEmpty()) {
			return;
		}
		List<Pending> due = new ArrayList<>();
		Iterator<Pending> it = PENDING.iterator();
		while (it.hasNext()) {
			Pending p = it.next();
			if (--p.delay()[0] <= 0) {
				it.remove();
				due.add(p);
			}
		}
		for (Pending p : due) {
			play(p.sound(), p.at(), p.volume(), p.pitch());
		}
	}
}
