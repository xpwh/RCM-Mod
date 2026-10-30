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
 *       its top and becomes a flat bang, far away a dull pop and rumble - blended smoothly with distance,
 *       getting quieter as a real report does (about -5 dB per doubling), lower and more of it the space
 *       ringing the further off it is;</li>
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
		report(muzzle, own, ModRegistry.AK_SHOT, ModRegistry.AK_SHOT_MID, ModRegistry.AK_SHOT_FAR, 1.0F);
	}

	/** A 12-gauge going off at {@code muzzle}: a deeper, heavier boom than a rifle's crack, the same spaces and echoes. */
	public static void shotgun(Vec3 muzzle, boolean own) {
		report(muzzle, own, ModRegistry.SHOTGUN_SHOT, ModRegistry.SHOTGUN_SHOT_MID, ModRegistry.SHOTGUN_SHOT_FAR, 1.1F);
	}

	/** A 9 mm pistol at {@code muzzle}: a sharp, high crack, lighter than a rifle's, ringing in the same spaces. */
	public static void pistol(Vec3 muzzle, boolean own) {
		report(muzzle, own, ModRegistry.GLOCK_SHOT, ModRegistry.GLOCK_SHOT_MID, ModRegistry.GLOCK_SHOT_FAR, 0.75F);
	}

	/**
	 * A shot through a suppressor ({@code close}: the rifle's, pistol's or shotgun's recording): close by the
	 * thump and the action's clatter, a little way off a dull clap, past a hundred and fifty blocks nothing.
	 * No echoes off distant hills; a room or a cave still rings, softly.
	 */
	public static void suppressed(Vec3 muzzle, boolean own, net.minecraft.sounds.SoundEvent close) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		de.rcm.ballistic.client.effect.AmbientLife.shot(muzzle, true);
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		double d = own ? 0.0 : ear.distanceTo(muzzle);
		if (d > 160.0) {
			return;
		}
		int delay = (int) Math.round(d / SPEED_OF_SOUND);
		float pitch = 0.96F + RANDOM.nextFloat() * 0.08F;
		float loud = (float) Math.pow(1.0 + d / 10.0, -1.1);
		double nearW = 1.0 - smooth(8.0, 55.0, d);
		later(close, muzzle, loud * (float) Math.sqrt(nearW), pitch, delay);
		later(ModRegistry.SUPPRESSED_FAR, muzzle, loud * 1.4F * (float) Math.sqrt(1.0 - nearW), pitch, delay);
		switch (survey(mc.level, muzzle).space()) {
			case ROOM -> later(ModRegistry.AK_TAIL_INDOOR, muzzle, loud * 0.3F, pitch, delay);
			case CAVE -> later(ModRegistry.AK_TAIL_CAVE, muzzle, loud * 0.25F, pitch, delay);
			default -> {
			}
		}
	}

	private static void report(Vec3 muzzle, boolean own, net.minecraft.sounds.SoundEvent close, net.minecraft.sounds.SoundEvent mid,
		net.minecraft.sounds.SoundEvent far, float tailGain) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		de.rcm.ballistic.client.effect.AmbientLife.shot(muzzle, false);
		Vec3 ear = mc.gameRenderer.getMainCamera().position();
		double d = own ? 0.0 : ear.distanceTo(muzzle);
		if (d > 900.0) {
			return;
		}
		int delay = (int) Math.round(d / SPEED_OF_SOUND);
		float jitter = 0.95F + RANDOM.nextFloat() * 0.1F;
		Acoustics room = survey(mc.level, muzzle);
		// how loud it is here: falling off with distance like a real report (about -5 dB each time the
		// distance doubles past the first few blocks), never quite gone within earshot
		float loud = (float) Math.pow(1.0 + d / 40.0, -0.75);
		// the air takes the top off with distance: the crack fades into the flat bang, the bang into the
		// dull far-off pop - blended smoothly, no step where one recording takes over from the other
		double nearW = 1.0 - smooth(12.0, 75.0, d);
		double farW = smooth(110.0, 420.0, d);
		double midW = Math.max(0.0, 1.0 - nearW - farW);
		// and a distant shot sounds a touch lower and duller
		float pitch = jitter * (float) (1.0 - 0.05 * Math.min(1.0, d / 600.0));
		later(close, muzzle, loud * (float) Math.sqrt(nearW), pitch, delay);
		later(mid, muzzle, loud * 1.1F * (float) Math.sqrt(midW), pitch, delay);
		later(far, muzzle, loud * 1.35F * (float) Math.sqrt(farW), pitch, delay);
		// what the surroundings make of it: far off, more of what you hear is the space ringing than the shot
		float tail = loud * (float) (0.55 + 0.6 * smooth(30.0, 300.0, d)) * tailGain;
		switch (room.space()) {
			case ROOM -> later(ModRegistry.AK_TAIL_INDOOR, muzzle, loud * tailGain, pitch, delay);
			case CAVE -> later(ModRegistry.AK_TAIL_CAVE, muzzle, loud * tailGain, pitch, delay);
			default -> {
				later(ModRegistry.AK_TAIL_OUTDOOR, muzzle, tail * 0.6F, pitch, delay);
				// echoes off hills and buildings: for whoever hears the shot from far off, not for the shooter
				// or anyone standing by them (to them the real tail already carries the space)
				for (int i = 0; i < (own || d < 48.0 ? 0 : room.echoes().length); i++) {
					// the echo travels to the wall and from there to the listener
					Vec3 wall = muzzle.add(room.echoDirs()[i].scale(room.echoes()[i]));
					double path = room.echoes()[i] + wall.distanceTo(ear);
					float v = (float) (Math.pow(1.0 + path / 40.0, -0.75) * 0.55);
					later(far, wall, v, pitch * 0.95F, (int) Math.round(path / SPEED_OF_SOUND));
				}
			}
		}
	}

	/** 0 below {@code a}, 1 above {@code b}, smoothly between. */
	private static double smooth(double a, double b, double x) {
		double t = Mth.clamp((x - a) / (b - a), 0.0, 1.0);
		return t * t * (3.0 - 2.0 * t);
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
