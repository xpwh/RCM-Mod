package de.rcm.ballistic.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.client.effect.Stress;
import de.rcm.ballistic.client.gun.AkArms;
import de.rcm.ballistic.client.gun.GunAudio;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The karambit in your own hand. Index finger through the ring, the handle across the fist, the claw curving out
 * under the little finger and away from the wrist, edge leading. The knife is always laid in the fist from the
 * way the forearm actually runs (the same elbow the arms are drawn with), so it never cuts through the hand or
 * the arm; for the spins the index finger comes out to the side and the knife wheels round it clear of the arm.
 * <ul>
 *   <li>Drawn: up from the hip, a spin backwards round the finger, snapped into the fist.</li>
 *   <li>Idle: it rides your breathing, the wrist drifts a little, and now and then it turns to look at the edge.</li>
 *   <li>Swung (attack): cocked back, then a fast backhand slash across and down, the edge leading, and back.</li>
 *   <li>Inspected (the inspect key): raised into the light, one flat then a flick over to the other and back,
 *       the finger comes out - two spins round it, one back the other way - tossed up off the finger turning
 *       over, caught on the finger, and snapped back into the fist.</li>
 * </ul>
 * Poses are laid out in camera space (x right, y up, -z ahead) and drawn in the hand space vanilla sets up, so
 * they sway and bob like any held item. Every move is eased in and out, the spins whip and the catches give.
 */
public final class KarambitClient {
	public static final int DRAW_LEN = 18;
	public static final int INSPECT_LEN = 136;

	private static final Vector3f REST = new Vector3f(0.26F, -0.28F, -0.6F);
	private static final Vector3f LOW = new Vector3f(0.42F, -0.8F, -0.5F);
	private static final Vector3f SHOW = new Vector3f(0.18F, -0.2F, -0.6F);
	private static final Vector3f SPIN = new Vector3f(0.2F, -0.22F, -0.64F);
	private static final Vector3f WIND = new Vector3f(0.4F, -0.18F, -0.6F);
	private static final Vector3f CUT = new Vector3f(-0.04F, -0.38F, -0.7F);
	private static final Vector3f REST_L = new Vector3f(-0.48F, -0.62F, -0.36F);
	/** The shoulder for the knife arm (hand space): low and out to the right, so the upper arm stays out of view. */
	static final Vector3f SHOULDER = HandSpace.hand(new Vector3f(0.62F, -0.86F, 0.1F));
	/** The elbow out to the lower right, so the forearm comes in from the corner of the view. */
	static final Vector3f POLE = new Vector3f(1.0F, -0.6F, 0.2F);
	/** The knife a little larger in your own view than in the world, as viewmodels are. */
	private static final float VIEW = 1.3F;
	/** The wrist's tilt at rest: the claw tipped towards you so its curve shows. */
	private static final float BEND_REST = 0.3F;
	/** The wrist's turn about the forearm at rest: the claw a little towards you so its curve shows. */
	private static final float ROLL_REST = 1.0F;

	private static long drawStart = -1000L;
	private static long inspectStart = -1000L;
	private static boolean held;
	private static int heldSlot = -1;
	private static int lastSwing;

	private KarambitClient() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(KarambitClient::tick);
	}

	public static boolean holding(Player player) {
		return player.getMainHandItem().is(ModRegistry.KARAMBIT);
	}

	/** The inspect key with the knife in hand. */
	public static void inspect(LocalPlayer player) {
		long now = player.level().getGameTime();
		if (holding(player) && now - inspectStart > INSPECT_LEN && now - drawStart > DRAW_LEN && player.getAttackAnim(0.0F) <= 0.0F) {
			inspectStart = now;
		}
	}

	private static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.isPaused()) {
			return;
		}
		long now = mc.level.getGameTime();
		boolean holds = holding(p);
		int slot = p.getInventory().getSelectedSlot();
		if (holds && (!held || slot != heldSlot)) {
			drawStart = now;
			inspectStart = -1000L;
			sound(ModRegistry.KNIFE_DRAW, p, 0.55F);
		}
		held = holds;
		heldSlot = slot;
		if (!holds) {
			return;
		}
		long d = now - drawStart;
		if (d == 4) {
			sound(ModRegistry.KNIFE_SPIN, p, 0.4F);
		} else if (d == 13) {
			sound(ModRegistry.KNIFE_CATCH, p, 0.45F);
		}
		// the swing: whoosh as it starts (and it breaks off any inspecting)
		if (p.swinging && p.swingTime <= 1 && lastSwing != p.tickCount) {
			lastSwing = p.tickCount;
			inspectStart = -1000L;
			sound(ModRegistry.KNIFE_SWISH, p, 0.6F);
		}
		long t = now - inspectStart;
		if (t >= 0 && t < INSPECT_LEN) {
			if (p.isSprinting() || p.isUsingItem()) {
				inspectStart = -1000L;
				return;
			}
			switch ((int) t) {
				case 3, 37, 59, 108 -> sound(ModRegistry.KNIFE_SWISH, p, 0.3F);
				case 75, 81, 87, 93, 99 -> sound(ModRegistry.KNIFE_SPIN, p, 0.4F);
				case 46, 120, 132 -> sound(ModRegistry.KNIFE_CATCH, p, 0.4F);
				default -> {
				}
			}
		}
	}

	private static void sound(SoundEvent s, Player p, float volume) {
		GunAudio.play(s, p.getEyePosition().add(p.getViewVector(1.0F).scale(0.4)), volume, 0.95F + p.getRandom().nextFloat() * 0.1F);
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * x * (x * (6.0F * x - 15.0F) + 10.0F);
	}

	private static float span(float t, float a, float b) {
		return smooth((t - a) / (b - a));
	}

	/** Eases in and out harder: for spins, which whip round and stop dead. */
	private static float whip(float t, float a, float b) {
		float x = Mth.clamp((t - a) / (b - a), 0.0F, 1.0F);
		return x < 0.5F ? 4.0F * x * x * x : 1.0F - (float) Math.pow(-2.0F * x + 2.0F, 3.0) / 2.0F;
	}

	/** Overshoots a little and settles: for things snapping into place. */
	private static float snap(float t, float a, float b) {
		float x = Mth.clamp((t - a) / (b - a), 0.0F, 1.0F);
		float c = 1.6F;
		return 1.0F + (c + 1.0F) * (float) Math.pow(x - 1.0F, 3.0) + c * (float) Math.pow(x - 1.0F, 2.0);
	}

	/** 0 .. 1 .. 0 between a and b, smooth at both ends. */
	private static float bump(float t, float a, float b) {
		float x = Mth.clamp((t - a) / (b - a), 0.0F, 1.0F);
		return Mth.sin(x * Mth.PI) * Mth.sin(x * Mth.PI);
	}

	/** Where the fist is, where the ring is, and how the knife is turned (camera space). */
	record Frame(Vector3f hand, Vector3f ring, Quaternionf q) {
	}

	/** One moment of a move: the fist, how far the knife faces you, the wrist's extra turn and tilt, the spin round the finger, the finger out, a toss. */
	private static final class Move {
		Vector3f hand;
		float face;
		float roll;
		float bend;
		float spin;
		float finger;
		float toss;

		Move(Vector3f hand) {
			this.hand = hand;
		}
	}

	/**
	 * @param draw    ticks since it was drawn (large: long since)
	 * @param inspect ticks into looking it over, or -1
	 * @param swing   the attack swing, 0..1 (0: not swinging)
	 */
	static Frame frame(float draw, float inspect, float swing, float time, float stress) {
		Move m = new Move(new Vector3f(REST));
		// idle: breathing, the wrist drifting, and now and then a turn to look at the edge
		m.hand.add(0.003F * Mth.sin(time * 0.08F), 0.005F * Mth.sin(time * 0.13F), 0.002F * Mth.sin(time * 0.05F));
		m.roll += 0.06F * Mth.sin(time * 0.06F);
		m.bend += 0.04F * Mth.sin(time * 0.045F + 1.0F);
		float glance = time % 300.0F;
		m.roll += 0.85F * bump(glance, 260.0F, 300.0F);
		m.bend -= 0.25F * bump(glance, 262.0F, 298.0F);
		if (draw < DRAW_LEN) {
			// up from the hip spinning back round the finger, snapped into the fist
			m.hand = new Vector3f(LOW).lerp(m.hand, snap(draw, 0.0F, 13.0F));
			m.spin = -Mth.TWO_PI * whip(draw, 1.0F, 13.0F);
			m.finger = bump(draw, 0.5F, 14.0F);
		} else if (inspect >= 0.0F && inspect < INSPECT_LEN) {
			inspect(inspect, m);
		}
		if (swing > 0.0F) {
			slash(swing, m);
		}
		float nerves = stress * stress * 0.004F;
		m.hand.add(nerves * Mth.sin(time * 1.9F), nerves * Mth.sin(time * 2.7F + 1.0F), 0.0F);
		return assemble(m);
	}

	private static void slash(float p, Move m) {
		// cocked back and up, then hard across and down to the left, the wrist rolling the edge round to lead, and back
		float wind = smooth(p / 0.2F) * (1.0F - smooth((p - 0.2F) / 0.3F));
		float cut = whip(p, 0.18F, 0.5F) * (1.0F - smooth((p - 0.55F) / 0.45F));
		Vector3f h = new Vector3f(m.hand);
		h.lerp(WIND, wind);
		h.lerp(CUT, cut);
		m.hand = h;
		m.roll += 0.6F * wind - 0.9F * cut;
		m.bend += -0.35F * wind + 0.45F * cut;
	}

	private static void inspect(float t, Move m) {
		// up into the light, the flat turned to you, tilted to catch it
		float up = span(t, 0.0F, 18.0F) * (1.0F - span(t, 122.0F, 136.0F));
		m.hand = new Vector3f(m.hand).lerp(SHOW, up);
		m.face = up;
		m.bend -= 0.22F * up;
		m.roll += 0.2F * Mth.sin(Math.max(0.0F, t - 16.0F) * 0.22F) * span(t, 16.0F, 22.0F) * (1.0F - span(t, 30.0F, 36.0F));
		// a flick of the wrist to the other flat, and back over
		m.roll += Mth.PI * snap(t, 36.0F, 46.0F) + Mth.PI * snap(t, 58.0F, 67.0F);
		m.roll += 0.12F * Mth.sin(Math.max(0.0F, t - 46.0F) * 0.3F) * span(t, 46.0F, 48.0F) * (1.0F - span(t, 54.0F, 58.0F));
		// the finger comes out, a little wind-up, two spins round it and one back
		float spinPose = span(t, 64.0F, 72.0F) * (1.0F - span(t, 122.0F, 136.0F));
		m.hand.lerp(SPIN, spinPose);
		// held out on the finger to show it off, then right out for the spins
		m.finger = Math.max(0.6F * up, span(t, 64.0F, 71.0F) * (1.0F - span(t, 124.0F, 132.0F)));
		m.spin = -0.35F * bump(t, 66.0F, 76.0F) + 2.0F * Mth.TWO_PI * whip(t, 72.0F, 96.0F) - Mth.TWO_PI * whip(t, 96.0F, 106.0F);
		// the hand circles a little with the spinning knife
		float circling = span(t, 72.0F, 76.0F) * (1.0F - span(t, 102.0F, 106.0F));
		m.hand.add(0.01F * Mth.sin(t * 0.55F) * circling, 0.01F * Mth.cos(t * 0.55F) * circling, 0.0F);
		// tossed up off the finger, turning over once, and caught
		m.hand.add(0.0F, -0.016F * bump(t, 105.0F, 110.0F) - 0.014F * bump(t, 118.0F, 124.0F), 0.0F);
		m.toss = 0.13F * Mth.sin(Mth.clamp((t - 109.0F) / 10.0F, 0.0F, 1.0F) * Mth.PI);
		m.spin += Mth.TWO_PI * smooth((t - 109.0F) / 10.0F);
		// caught with a little give, then snapped back into the fist
		m.spin += 0.3F * bump(t, 119.0F, 126.0F) - 0.15F * bump(t, 126.0F, 134.0F);
	}

	/** The turn about the forearm that brings the knife's flat square to the camera. */
	private static float faceRoll(Vector3f x0, Vector3f y0) {
		return (float) Math.atan2(y0.z, x0.z);
	}

	private static Frame assemble(Move m) {
		Vector3f d = AkArms.rightForearm(HandSpace.hand(m.hand), SHOULDER, POLE);
		Vector3f y = new Vector3f(0.0F, 1.0F, 0.0F).sub(new Vector3f(d).mul(d.y));
		if (y.lengthSquared() < 1.0E-4F) {
			y.set(0.0F, 0.0F, 1.0F).sub(new Vector3f(d).mul(d.z));
		}
		y.normalize();
		Vector3f x = new Vector3f(y).cross(d);
		float roll = Mth.lerp(m.face, ROLL_REST, faceRoll(x, y)) + m.roll;
		Quaternionf fist = new Quaternionf().setFromNormalized(new org.joml.Matrix3f(x, y, d)).rotateZ(roll).rotateX(BEND_REST + m.bend);
		Vector3f center = new Vector3f(m.hand).add(new Vector3f(d).mul(0.03F));
		Vector3f ring = new Vector3f(center).sub(fist.transform(new Vector3f(KarambitModel.GRIP).mul(VIEW)));
		// the finger out to the side, towards you, clear of the arm
		Vector3f side = fist.transform(new Vector3f(1.0F, 0.0F, 0.0F));
		if (side.z < 0.0F) {
			side.negate();
		}
		ring.add(side.mul(0.09F * m.finger)).add(new Vector3f(d).mul(0.035F * m.finger)).add(0.0F, m.toss, 0.0F);
		return new Frame(m.hand, ring, new Quaternionf(fist).rotateX(m.spin));
	}

	public static void render(AbstractClientPlayer player, PoseStack poseStack, SubmitNodeCollector collector, int light, float partialTick) {
		Matrix4f base = HandSpace.enter(poseStack);
		long now = player.level().getGameTime();
		float time = now + partialTick;
		float draw = now - drawStart + partialTick;
		float inspect = inspectStart < 0 ? -1.0F : now - inspectStart + partialTick;
		Frame f = frame(draw, inspect, player.getAttackAnim(partialTick), time, Stress.level());
		AkArms.draw(player, base, poseStack, collector, light, HandSpace.hand(f.hand()), HandSpace.hand(REST_L), SHOULDER, AkArms.LEFT_SHOULDER, POLE,
			AkArms.LEFT_POLE);
		Vector3f h = HandSpace.hand(f.ring());
		poseStack.pushPose();
		poseStack.translate(h.x, h.y, h.z);
		poseStack.mulPose(f.q());
		poseStack.scale(VIEW, VIEW, VIEW);
		KarambitModel.submit(poseStack, collector, light);
		poseStack.popPose();
		poseStack.popPose();
	}
}
