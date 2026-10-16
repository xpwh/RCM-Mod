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
 * The karambit in your own hand. Index finger through the ring, the handle in the fist, the claw curving
 * out under the little finger, edge forward.
 * <ul>
 *   <li>Drawn: it comes up from the hip spinning once round the finger and is caught in the fist.</li>
 *   <li>Idle: it rides your breathing, sways a little, and every so often the wrist turns it to glance at
 *       the edge.</li>
 *   <li>Swung (attack): a backhand slash across from the right, the edge leading.</li>
 *   <li>Inspected (the inspect key): brought up to show one flat, turned to show the other, then two
 *       spins round the ring finger, one back the other way, a twirl round the handle, a last spin and
 *       caught.</li>
 * </ul>
 * Everything is laid out in camera space (x right, y up, -z ahead) and carried into the renderer's hand
 * space, as for the cigarettes.
 */
public final class KarambitClient {
	public static final int DRAW_LEN = 16;
	public static final int INSPECT_LEN = 112;

	private static final Vector3f REST = new Vector3f(0.24F, -0.17F, -0.31F);
	private static final Vector3f LOW = new Vector3f(0.34F, -0.66F, -0.34F);
	private static final Vector3f SHOW = new Vector3f(0.15F, -0.07F, -0.38F);
	private static final Vector3f SHOW2 = new Vector3f(-0.02F, -0.07F, -0.38F);
	private static final Vector3f SPIN = new Vector3f(0.1F, -0.02F, -0.42F);
	private static final Vector3f WIND = new Vector3f(0.36F, -0.08F, -0.38F);
	private static final Vector3f CUT = new Vector3f(-0.1F, -0.27F, -0.42F);
	private static final Vector3f REST_L = new Vector3f(-0.48F, -0.62F, -0.36F);

	/** Held: the handle runs away from you, the claw curving down ahead of the fist, edge down and in. */
	private static final Quaternionf Q_REST = axes(new Vector3f(0.3F, 0.45F, 0.85F), new Vector3f(-0.2F, -1.0F, 0.3F));
	/** Shown side on, the way it lies in a photograph: ring to the right, claw to the left, edge down. */
	private static final Quaternionf Q_SHOW = axes(new Vector3f(1.0F, 0.0F, 0.0F), new Vector3f(0.0F, -1.0F, 0.0F));
	/** Turned round to show the other flat: ring to the left. */
	private static final Quaternionf Q_SHOW2 = axes(new Vector3f(-1.0F, 0.0F, 0.15F), new Vector3f(0.0F, -1.0F, 0.0F));
	/** Cocked back for the slash, and at its end: the edge leading across. */
	private static final Quaternionf Q_WIND = axes(new Vector3f(0.2F, 0.35F, 1.0F), new Vector3f(-0.6F, 0.75F, 0.0F));
	private static final Quaternionf Q_CUT = axes(new Vector3f(-0.15F, 0.25F, 1.0F), new Vector3f(-1.0F, -0.3F, 0.0F));

	/** The turn that lays the knife's +y (from the blade up to the ring) along {@code y} and its edge side along {@code z} (camera space). */
	private static Quaternionf axes(Vector3f y, Vector3f z) {
		Vector3f ay = new Vector3f(y).normalize();
		Vector3f az = new Vector3f(z).sub(new Vector3f(ay).mul(ay.dot(z))).normalize();
		Vector3f ax = new Vector3f(ay).cross(az);
		return new Quaternionf().setFromNormalized(new org.joml.Matrix3f(ax, ay, az));
	}

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
		} else if (d == 12) {
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
				case 2, 28, 82 -> sound(ModRegistry.KNIFE_SWISH, p, 0.3F);
				case 47, 53, 59, 65, 72, 98 -> sound(ModRegistry.KNIFE_SPIN, p, 0.4F);
				case 80, 94, 109 -> sound(ModRegistry.KNIFE_CATCH, p, 0.4F);
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
		return x * x * (3.0F - 2.0F * x);
	}

	private static float span(float t, float a, float b) {
		return smooth((t - a) / (b - a));
	}

	/** Eases in and out harder than smooth: for the spins, which whip round and stop dead in the fist. */
	private static float whip(float t, float a, float b) {
		float x = Mth.clamp((t - a) / (b - a), 0.0F, 1.0F);
		return x < 0.5F ? 4.0F * x * x * x : 1.0F - (float) Math.pow(-2.0F * x + 2.0F, 3.0) / 2.0F;
	}

	/** Where the ring sits, how the knife is turned, and how far the hand has opened to let it spin round the finger (camera space). */
	record Frame(Vector3f ring, Quaternionf q, float onFinger) {
	}

	/**
	 * @param draw    ticks since it was drawn (large: long since)
	 * @param inspect ticks into looking it over, or -1
	 * @param swing   the attack swing, 0..1 (0: not swinging)
	 */
	static Frame frame(float draw, float inspect, float swing, float time, float stress) {
		Vector3f pos = new Vector3f(REST);
		Quaternionf q = new Quaternionf(Q_REST);
		float finger = 0.0F;
		// idle: breathing, a slow sway, and now and then a turn of the wrist to look at the edge
		pos.add(0.003F * Mth.sin(time * 0.08F), 0.005F * Mth.sin(time * 0.13F), 0.0F);
		q.rotateZ(0.035F * Mth.sin(time * 0.07F)).rotateY(0.05F * Mth.sin(time * 0.05F));
		float glance = (time % 260.0F) / 260.0F;
		if (glance > 0.88F) {
			float u = (glance - 0.88F) / 0.12F;
			q.rotateY(0.75F * Mth.sin(u * Mth.PI) * (1.0F - 0.3F * u)).rotateX(-0.15F * Mth.sin(u * Mth.PI));
		}
		if (draw < DRAW_LEN) {
			// up from the hip, spinning once round the finger, and caught
			float u = span(draw, 0.0F, 11.0F);
			pos = new Vector3f(LOW).lerp(pos, u);
			q.rotateX(-Mth.TWO_PI * whip(draw, 1.0F, 12.0F));
			finger = Mth.clamp(Math.min((draw - 1.0F) / 2.0F, (13.0F - draw) / 2.0F), 0.0F, 1.0F);
		} else if (inspect >= 0.0F && inspect < INSPECT_LEN) {
			Inspect in = inspect(inspect, pos, q);
			pos = in.pos;
			q = in.q;
			finger = in.finger;
		}
		if (swing > 0.0F) {
			// a backhand slash: drawn back to the right, then hard across and down to the left, and back
			Vector3f p;
			Quaternionf r;
			if (swing < 0.18F) {
				float u = smooth(swing / 0.18F);
				p = new Vector3f(pos).lerp(WIND, u);
				r = new Quaternionf(q).slerp(Q_WIND, u);
			} else if (swing < 0.5F) {
				float u = whip(swing, 0.18F, 0.5F);
				p = new Vector3f(WIND).lerp(CUT, u);
				r = new Quaternionf(Q_WIND).slerp(Q_CUT, u);
			} else {
				float u = smooth((swing - 0.5F) / 0.5F);
				p = new Vector3f(CUT).lerp(pos, u);
				r = new Quaternionf(Q_CUT).slerp(q, u);
			}
			pos = p;
			q = r;
		}
		float nerves = stress * stress * 0.004F;
		pos.add(nerves * Mth.sin(time * 1.9F), nerves * Mth.sin(time * 2.7F + 1.0F), 0.0F);
		return new Frame(pos, q, finger);
	}

	private record Inspect(Vector3f pos, Quaternionf q, float finger) {
	}

	private static Inspect inspect(float t, Vector3f rest, Quaternionf qRest) {
		Vector3f pos;
		Quaternionf q;
		float finger = 0.0F;
		if (t < 26.0F) {
			// up into the light, one flat towards you, turned a little this way and that
			float u = span(t, 0.0F, 12.0F);
			pos = new Vector3f(rest).lerp(SHOW, u);
			q = new Quaternionf(qRest).slerp(Q_SHOW, u).rotateY(0.18F * Mth.sin(Math.max(0.0F, t - 10.0F) * 0.35F) * span(t, 10.0F, 16.0F));
		} else if (t < 38.0F) {
			// the wrist turns it over: the other flat
			float u = span(t, 26.0F, 38.0F);
			pos = new Vector3f(SHOW).lerp(SHOW2, u);
			q = new Quaternionf(Q_SHOW).slerp(Q_SHOW2, u);
		} else if (t < 80.0F) {
			// back square to you, the hand opens, and it spins round the finger: twice over, then once back
			float u = span(t, 38.0F, 46.0F);
			pos = new Vector3f(SHOW2).lerp(SPIN, u);
			q = new Quaternionf(Q_SHOW2).slerp(Q_SHOW, u);
			float spin = 2.0F * Mth.TWO_PI * whip(t, 46.0F, 69.0F) - Mth.TWO_PI * whip(t, 70.0F, 79.0F);
			q.rotateX(spin);
			finger = span(t, 42.0F, 46.0F);
			pos.add(0.0F, 0.012F * Mth.sin(whip(t, 46.0F, 69.0F) * Mth.PI), 0.0F);
		} else if (t < 94.0F) {
			// a flick up and a twirl round the handle, caught again
			float u = Mth.sin(span(t, 80.0F, 94.0F) * Mth.PI);
			pos = new Vector3f(SPIN).add(0.0F, 0.045F * u, 0.02F * u);
			q = new Quaternionf(Q_SHOW).rotateY(Mth.TWO_PI * whip(t, 81.0F, 93.0F));
			finger = 1.0F;
		} else {
			// home: one last spin on the way back down into the fist
			float u = span(t, 94.0F, 110.0F);
			pos = new Vector3f(SPIN).lerp(rest, u);
			q = new Quaternionf(Q_SHOW).slerp(qRest, u).rotateX(-Mth.TWO_PI * whip(t, 95.0F, 108.0F));
			finger = Mth.clamp((110.0F - t) / 4.0F, 0.0F, 1.0F);
		}
		return new Inspect(pos, q, finger);
	}

	public static void render(AbstractClientPlayer player, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light, float partialTick) {
		long now = player.level().getGameTime();
		float time = now + partialTick;
		float draw = now - drawStart + partialTick;
		float inspect = inspectStart < 0 ? -1.0F : now - inspectStart + partialTick;
		Frame f = frame(draw, inspect, player.getAttackAnim(partialTick), time, Stress.level());
		Matrix4f inv = new Matrix4f(base).invert();
		Quaternionf undo = base.getNormalizedRotation(new Quaternionf()).conjugate();
		// the fist closes round the handle - or, while it spins, only the finger is in the ring
		Vector3f hand = new Vector3f(f.ring()).add(f.q().transform(new Vector3f(KarambitModel.GRIP).mul(1.0F - f.onFinger())));
		AkArms.draw(player, base, poseStack, collector, light, inv.transformPosition(hand), inv.transformPosition(new Vector3f(REST_L)), AkArms.RIGHT_SHOULDER,
			AkArms.LEFT_SHOULDER);
		Vector3f h = inv.transformPosition(new Vector3f(f.ring()));
		poseStack.pushPose();
		poseStack.translate(h.x, h.y, h.z);
		poseStack.mulPose(new Quaternionf(undo).mul(f.q()));
		KarambitModel.submit(poseStack, collector, light);
		poseStack.popPose();
	}
}
