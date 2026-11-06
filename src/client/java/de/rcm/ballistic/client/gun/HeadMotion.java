package de.rcm.ballistic.client.gun;

/**
 * The head's own motion while you work a gun in first person: the eyes drawn down to the magazine well while the
 * gun is in close, the head leaning with the cant, a jolt through it when the magazine is slapped home or the bolt
 * slams forward, the eyes following a dropped magazine. It comes with each clip (the head channels Blender works
 * out, {@link AkClips#HEAD}); the gun's pose code adds up what is playing every frame and the camera
 * (CameraMixin) turns by it - the view only: where you aim stays where it was.
 */
public final class HeadMotion {
	/** Older than this (the gun not drawn any more, the hand hidden): nothing. */
	private static final long FRESH = 150_000_000L;
	private static float pitch;
	private static float yaw;
	private static float roll;
	private static long stamp;

	private HeadMotion() {
	}

	/** A new frame of the gun's pose: start from nothing. */
	static void begin() {
		pitch = yaw = roll = 0.0F;
		stamp = System.nanoTime();
	}

	/** A clip playing this frame: its head channels, {@code weight} times. */
	static void add(float[] sample, float weight) {
		pitch += sample[AkClips.HEAD] * weight;
		yaw += sample[AkClips.HEAD + 1] * weight;
		roll += sample[AkClips.HEAD + 2] * weight;
	}

	/** Pitch (+ down), yaw (+ right), roll in degrees, or null when no gun has set it lately. */
	public static float[] current() {
		if (System.nanoTime() - stamp > FRESH || pitch == 0.0F && yaw == 0.0F && roll == 0.0F) {
			return null;
		}
		return new float[] {pitch, yaw, roll};
	}
}
