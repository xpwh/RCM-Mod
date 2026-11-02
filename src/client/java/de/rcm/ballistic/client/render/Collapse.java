package de.rcm.ballistic.client.render;

import de.rcm.ballistic.injury.Injuries;
import de.rcm.ballistic.injury.Wounds;
import net.minecraft.util.Mth;

/**
 * Going down after a round through the head, in ticks since the hit ({@code t}, 0 to
 * {@link Injuries#COLLAPSE}): the body stays up a moment, rocking forward and back as the legs go, the
 * rocking growing, then it topples - slowly at first, then all at once - and hits the ground, a small
 * bounce, a twitch, still.
 */
public final class Collapse {
	/** Swaying until this tick, falling until the next, then lying. */
	public static final float SWAY_END = 28.0F;
	public static final float FALL_END = 38.0F;

	private Collapse() {
	}

	/** Ticks into the collapse for {@code wounds}, or -1 when not collapsing (and the full time when fallen). */
	public static float time(Wounds wounds, float partialTick) {
		if (wounds.collapse() > 0) {
			return Math.min(Injuries.COLLAPSE, Injuries.COLLAPSE - wounds.collapse() + partialTick);
		}
		return wounds.fall() > 0 ? Injuries.COLLAPSE : -1.0F;
	}

	private static float sway(float t) {
		float amp = 2.0F + 11.0F * (float) Math.pow(Mth.clamp(t / SWAY_END, 0.0F, 1.0F), 1.3);
		// a slow, uneven rocking: the body trying and failing to keep itself up
		return amp * (Mth.sin(t * 0.42F) * 0.8F + Mth.sin(t * 0.17F + 1.0F) * 0.35F);
	}

	/** How far the body leans (degrees): towards the face (negative) or the back (positive) when it falls {@code fall}. */
	public static float pitch(float t, int fall) {
		if (t < 0.0F) {
			return 0.0F;
		}
		float dir = fall == Wounds.FORWARD ? -1.0F : 1.0F;
		if (t < SWAY_END) {
			// drifting towards the side it will go
			return sway(t) + dir * 4.0F * (t / SWAY_END);
		}
		float start = sway(SWAY_END) + dir * 4.0F;
		if (t < FALL_END) {
			float u = (t - SWAY_END) / (FALL_END - SWAY_END);
			return start + (dir * 90.0F - start) * u * u;
		}
		// the ground: a bounce off it, a last twitch
		float after = t - FALL_END;
		float bounce = after < 5.0F ? Mth.sin(after / 5.0F * Mth.PI) * 7.0F * (1.0F - after / 5.0F) : 0.0F;
		float twitch = after > 6.0F && after < 9.0F ? Mth.sin((after - 6.0F) / 3.0F * Mth.TWO_PI) * 1.5F : 0.0F;
		return dir * (90.0F - bounce) + twitch;
	}

	/** 0 standing up to 1 lying on the ground. */
	public static float down(float t) {
		if (t < SWAY_END) {
			return 0.0F;
		}
		return Mth.clamp((t - SWAY_END) / (FALL_END - SWAY_END), 0.0F, 1.0F);
	}

	/** A stagger to the side, as the knees go. */
	public static float roll(float t) {
		if (t < 0.0F || t > FALL_END) {
			return 0.0F;
		}
		return Mth.sin(t * 0.23F + 0.6F) * 4.0F * Mth.clamp(t / SWAY_END, 0.0F, 1.0F);
	}
}
