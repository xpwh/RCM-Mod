package de.rcm.ballistic.client.gun;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The first-person motion of C4 and its firing device, as plain maths (no game state) so the offline animation
 * studio (tools/anim_studio) can render exactly what the game draws. Times in ticks.
 * <p>
 * Spaces: {@link #chargeHold} / {@link #deviceHold} are applied in the hand's space vanilla sets up (the right hand
 * held out low on the right); after them and the item's display offset (2/16 up) comes item space, where the model
 * is placed by {@link #chargeModel} / {@link #deviceModel} and the fists sit ({@link Hands}).
 */
public final class C4Motion {
	/** Ticks of pressing a charge on: reach, push, tape, key in the code, let go. */
	public static final int PLACE = de.rcm.ballistic.gun.C4Item.PLACE_TICKS;
	public static final float T_PUSH = 7.0F;
	public static final float T_TAPE = 10.0F;
	public static final float T_KEYS = 18.0F;
	public static final float[] KEY_TAPS = {19.0F, 21.5F, 24.0F};
	public static final float T_LETGO = 26.5F;
	/** The firing device: raised, switch to ARMED, cover up, FIRE pressed. */
	public static final int FIRE = 12;
	public static final float T_ARM = 3.5F;
	public static final float T_COVER = 5.0F;
	public static final float T_PRESS = 9.5F;
	/** Ticks of bringing an item up when it is taken in hand. */
	public static final float DRAW = 9.0F;

	/** Where the fists are (item space), and whether the left one is in view at all. */
	public record Hands(Vector3f right, Vector3f left) {
	}

	private C4Motion() {
	}

	static float smooth(float x) {
		x = Math.max(0.0F, Math.min(1.0F, x));
		return x * x * (3.0F - 2.0F * x);
	}

	/** 0 .. 1 .. 0 over [a, b]. */
	static float bump(float t, float a, float b) {
		return t <= a || t >= b ? 0.0F : (float) Math.sin((t - a) / (b - a) * Math.PI);
	}

	private static float rad(float deg) {
		return (float) Math.toRadians(deg);
	}

	/** Hands out of sight, low on the left. */
	public static final Vector3f LEFT_REST = new Vector3f(-0.5F, -0.55F, 0.15F);

	// ------------------------------------------------------------------ the charge

	/**
	 * The hold: in both hands in front of you, low, its face with the receiver tipped up towards your eyes; breathing
	 * a little. Placing ({@code use} ticks, or -1): brought up and out to the wall and turned over against it, pushed
	 * on - it stays there - while the hands work on it. Drawing ({@code draw} ticks since taken in hand) brings it up.
	 */
	public static Matrix4f chargeHold(float time, float draw, float use) {
		Matrix4f m = new Matrix4f();
		float d = 1.0F - smooth(draw / DRAW);
		m.translate(-0.4F, 0.15F - 0.45F * d, 0.12F);
		m.rotateX(rad(-35.0F * d));
		// breathing
		float breathe = (float) Math.sin(time * 0.09F);
		m.translate(0.003F * (float) Math.sin(time * 0.05F), 0.004F * breathe, 0.0F);
		m.rotateZ(rad(0.8F * (float) Math.sin(time * 0.06F)));
		if (use >= 0.0F) {
			float reach = smooth(use / T_PUSH);
			float push = bump(use, T_PUSH - 1.5F, T_PUSH + 2.5F);
			// out to the wall, up to eye height, pressed on with a jolt
			m.translate(0.16F * reach, 0.1F * reach, -0.2F * reach - 0.05F * push);
			m.rotateZ(rad(4.0F * push * (float) Math.sin(use * 3.0F)));
		}
		return m;
	}

	/** The charge in item space: lying across the hands, its top tipped towards you - or, placing, turned flat onto the wall. */
	public static Matrix4f chargeModel(float use) {
		float onto = use < 0.0F ? 0.0F : smooth(use / T_PUSH);
		Matrix4f m = new Matrix4f();
		m.translate(0.0F, -0.02F, 0.0F);
		m.rotateY(rad(10.0F * (1.0F - onto)));
		// its back to the wall in front: the top (+y) turned to face you
		m.rotateX(rad(40.0F + 50.0F * onto));
		m.scale(1.2F);
		m.translate(0.0F, -0.025F, 0.0F);
		return m;
	}

	public static Hands chargeHands(float use) {
		Matrix4f model = chargeModel(use);
		// holding: under each end of the bundle
		Vector3f right = model.transformPosition(new Vector3f(0.165F, 0.01F, 0.0F));
		Vector3f left = model.transformPosition(new Vector3f(-0.165F, 0.01F, 0.0F));
		if (use < 0.0F) {
			return new Hands(right, left);
		}
		// the left hand: up off the end, the tape smoothed down along it, then away
		float tapeIn = smooth((use - T_TAPE + 1.0F) / 2.0F);
		float along = smooth((use - T_TAPE) / (T_KEYS - T_TAPE - 1.0F));
		Vector3f swipe = model.transformPosition(new Vector3f(-0.13F + 0.26F * along, 0.07F + 0.012F * (float) Math.sin(along * 9.0F), 0.0F));
		left.lerp(swipe, tapeIn);
		float leftAway = smooth((use - T_KEYS) / 3.0F);
		left.lerp(LEFT_REST, leftAway);
		// the right hand: from under its end to the keypad, a finger in for each digit, then away
		float toKeys = smooth((use - T_KEYS + 1.5F) / 2.5F);
		Vector3f keypad = model.transformPosition(new Vector3f(0.085F, 0.095F, 0.005F));
		float tap = 0.0F;
		for (float k : KEY_TAPS) {
			tap = Math.max(tap, bump(use, k - 0.6F, k + 1.0F));
		}
		Vector3f in = model.transformDirection(new Vector3f(-0.03F, -0.02F, 0.0F));
		keypad.add(in.mul(tap));
		right.lerp(keypad, toKeys);
		float rightAway = smooth((use - T_LETGO) / 3.0F);
		right.lerp(new Vector3f(0.25F, -0.55F, 0.2F), rightAway);
		return new Hands(right, left);
	}

	// ------------------------------------------------------------------ the firing device

	/**
	 * The hold: in the right hand, low and in towards the middle, its face tipped up at you, breathing. FIRE ({@code fire}
	 * ticks, or -1): raised in front of the face, a nudge as the thumb throws the switch, a flick as the cover goes up,
	 * a hard press on FIRE that shakes the hand; held up a moment, then lowered. The selector ({@code select}): tipped
	 * up to read the display.
	 */
	public static Matrix4f deviceHold(float time, float draw, float fire, float select) {
		Matrix4f m = new Matrix4f();
		float d = 1.0F - smooth(draw / DRAW);
		m.translate(-0.22F, 0.2F - 0.45F * d, 0.2F);
		m.rotateX(rad(-30.0F * d));
		float breathe = (float) Math.sin(time * 0.09F);
		m.translate(0.003F * (float) Math.sin(time * 0.05F), 0.004F * breathe, 0.0F);
		m.rotateZ(rad(1.0F * (float) Math.sin(time * 0.06F)));
		if (fire >= 0.0F) {
			float up = smooth(fire / T_ARM) * (1.0F - smooth((fire - 32.0F) / 8.0F));
			m.translate(-0.09F * up, 0.09F * up, 0.06F * up);
			m.rotateX(rad(12.0F * up));
			float flick = bump(fire, T_ARM - 0.5F, T_ARM + 1.5F);
			float cover = bump(fire, T_COVER - 0.5F, T_COVER + 2.0F) * 1.6F;
			float press = bump(fire, T_PRESS - 1.0F, T_PRESS + 2.5F);
			float shake = fire > T_PRESS && fire < T_PRESS + 8.0F ? (1.0F - (fire - T_PRESS) / 8.0F) : 0.0F;
			m.translate(0.0F, -0.006F * flick - 0.02F * press, 0.0F);
			m.rotateX(rad(3.0F * flick - 4.0F * cover - 8.0F * press));
			m.rotateZ(rad(2.5F * cover + 1.5F * shake * (float) Math.sin(fire * 5.0F)));
			m.translate(0.003F * shake * (float) Math.sin(fire * 7.0F), 0.003F * shake * (float) Math.sin(fire * 6.0F), 0.0F);
		}
		if (select >= 0.0F && select < 10.0F) {
			float k = bump(select, 0.0F, 10.0F);
			m.translate(-0.02F * k, 0.04F * k, 0.02F * k);
			m.rotateX(rad(14.0F * k));
		}
		return m;
	}

	/** The device in item space: its face tipped up at you, the antenna away, a little larger than life so the fist does not hide it. */
	public static Matrix4f deviceModel() {
		Matrix4f m = new Matrix4f();
		m.translate(0.0F, 0.0F, 0.0F);
		m.rotateY(rad(-14.0F));
		m.rotateX(rad(52.0F));
		m.scale(1.9F);
		return m;
	}

	public static Hands deviceHands(float fire) {
		Matrix4f model = deviceModel();
		// the fist round the lower end, under it
		Vector3f right = model.transformPosition(new Vector3f(0.0F, -0.04F, 0.11F));
		return new Hands(right, new Vector3f(LEFT_REST));
	}

	/** How the device's parts stand {@code fire} ticks after FIRE (-1: at rest). */
	public static C4Client.DevicePose devicePose(float fire) {
		if (fire < 0.0F || fire > 44.0F) {
			return C4Client.DevicePose.REST;
		}
		boolean armed = fire > T_ARM && fire < 38.0F;
		float cover = 110.0F * smooth((fire - T_COVER) / 2.0F) * (1.0F - smooth((fire - 34.0F) / 4.0F));
		float press = 0.0035F * bump(fire, T_PRESS - 1.0F, T_PRESS + 2.5F);
		return new C4Client.DevicePose(cover, press, armed);
	}
}
