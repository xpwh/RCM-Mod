package de.rcm.ballistic.client.item;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.client.effect.DynamicLights;
import de.rcm.ballistic.client.effect.Stress;
import de.rcm.ballistic.client.gun.AkArms;
import de.rcm.ballistic.item.CigarettesItem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Lighting up, in your own hands. Everything is laid out in camera space (x right, y up, -z ahead) and
 * carried into the hand space the renderer gives us, so the cigarette lands exactly where it then stays in
 * the mouth. The right hand brings the pack up and the thumb flips the lid; the left hand draws a
 * cigarette out of the row of filters, turns it and puts it between the lips; the pack drops out of view and
 * the right hand comes back with the Zippo - flicked open and struck - lifts the flame to the tip for the
 * first drag, the ember catches, the lid snaps shut and the hand goes back down with the pack.
 */
public final class CigaretteHands {
	private static final Vector3f REST_R = new Vector3f(0.24F, -0.25F, -0.5F);
	private static final Vector3f LIFT_R = new Vector3f(0.1F, -0.2F, -0.42F);
	private static final Vector3f AWAY_R = new Vector3f(0.3F, -0.62F, -0.36F);
	private static final Vector3f ZIP_R = new Vector3f(0.08F, -0.25F, -0.38F);
	private static final Vector3f REST_L = new Vector3f(-0.48F, -0.62F, -0.36F);
	private static final Vector3f AT_PACK_L = new Vector3f(0.065F, -0.085F, -0.405F);
	private static final Vector3f PULLED_L = new Vector3f(0.05F, -0.025F, -0.4F);
	/** The object sits this far below the fist holding it. */
	private static final float GRIP = 0.025F;
	/** The cigarette between the lips (camera space), the way {@link SmokingClient} draws it once it is lit. */
	private static final Quaternionf IN_MOUTH = new Quaternionf().rotationY(Mth.PI + 0.1F).rotateX(0.05F);
	private static final Quaternionf UPRIGHT = new Quaternionf().rotationX(Mth.HALF_PI);
	private static final Quaternionf PACK_TURN = new Quaternionf().rotationY(-0.28F).rotateX(0.12F);
	private static final Quaternionf ZIPPO_TURN = new Quaternionf().rotationY(-0.35F).rotateX(0.05F);

	private CigaretteHands() {
	}

	private static float smooth(float x) {
		x = Mth.clamp(x, 0.0F, 1.0F);
		return x * x * (3.0F - 2.0F * x);
	}

	private static float span(float t, float a, float b) {
		return smooth((t - a) / (b - a));
	}

	private static Vector3f lerp(Vector3f a, Vector3f b, float f) {
		return new Vector3f(a).lerp(b, f);
	}

	/** Where the filter end sits between the lips, and the tip of the cigarette, in camera space. */
	private static Vector3f mouth() {
		// the filter end is between the lips: most of the filter out of sight behind them
		return new Vector3f(SmokingClient.OWN_MOUTH).add(IN_MOUTH.transform(new Vector3f(0.0F, 0.0F, -SmokingClient.IN_LIPS)));
	}

	private static Vector3f tip() {
		return mouth().add(IN_MOUTH.transform(new Vector3f(0.0F, 0.0F, SmokeKit.tipAt(0.0F))));
	}

	public static void render(AbstractClientPlayer player, Matrix4f base, PoseStack poseStack, SubmitNodeCollector collector, int light, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		float t = player.isUsingItem() && player.getUseItem().getItem() instanceof CigarettesItem ? player.getTicksUsingItem() + partialTick : -1.0F;
		float time = (player.level().getGameTime() + partialTick);
		Matrix4f inv = new Matrix4f(base).invert();
		Quaternionf undo = base.getNormalizedRotation(new Quaternionf()).conjugate();

		Frame f = frame(t, time, Stress.level());

		// the arms (positions in the renderer's hand space)
		AkArms.draw(player, base, poseStack, collector, light, inv.transformPosition(new Vector3f(f.r())), inv.transformPosition(new Vector3f(f.l())),
			AkArms.RIGHT_SHOULDER, AkArms.LEFT_SHOULDER);

		// what the right hand holds
		Vector3f obj = new Vector3f(f.r()).add(0.0F, -GRIP, 0.0F);
		place(poseStack, inv, undo, obj, f.zippo() ? ZIPPO_TURN : PACK_TURN);
		if (f.zippo()) {
			SmokeKit.zippo(poseStack, collector, light, f.lid(), f.flame(), time);
		} else {
			SmokeKit.pack(poseStack, collector, light, f.lid());
		}
		poseStack.popPose();
		if (f.flame() > 0.0F) {
			flameLight(mc, player, ZIPPO_TURN.transform(new Vector3f(0.0F, SmokeKit.ZIP_H + 0.03F, 0.0F)).add(obj), f.flame());
		}

		// the cigarette: in the pack's row, between the fingers, then between the lips
		if (f.cigAt() != null) {
			place(poseStack, inv, undo, f.cigAt(), f.cigQ());
			SmokeKit.submit(poseStack, collector, SmokeKit.cigarette(0.0F), light);
			if (f.glow() > 0.0F) {
				SmokeKit.ember(poseStack, collector, 0.0F, f.glow() * (0.9F + 0.1F * Mth.sin(time * 1.7F)));
			}
			poseStack.popPose();
		}
	}

	/**
	 * Where everything is at {@code t} ticks into lighting up (-1: just holding the pack), in camera space:
	 * the hands, what the right one holds and how open its lid is, the flame, the cigarette.
	 */
	record Frame(Vector3f r, Vector3f l, boolean zippo, float lid, float flame, float cig, float glow, Vector3f cigAt, Quaternionf cigQ) {
	}

	static Frame frame(float t, float time, float stress) {
		// the Zippo under the tip: the flame just reaching it
		Vector3f lightR = new Vector3f(tip()).add(0.0F, -(SmokeKit.ZIP_H + 0.03F) + GRIP, 0.0F);

		Vector3f r;
		Vector3f l = new Vector3f(REST_L);
		boolean zippo = false;
		float lid = 0.0F;
		float flame = 0.0F;
		float cig = -1.0F; // < 0 not shown, 0..1 from the pack to the lips, 2 in the mouth
		float glow = 0.0F;
		if (t < 0.0F) {
			r = new Vector3f(REST_R);
		} else if (t < 28.0F) {
			r = t < 19.0F ? lerp(REST_R, LIFT_R, span(t, 0.0F, 8.0F)) : lerp(LIFT_R, AWAY_R, span(t, 19.0F, 28.0F));
			lid = t < 16.0F ? span(t, 7.0F, 10.0F) : 1.0F - span(t, 16.0F, 19.0F);
			// the left hand: to the pack, drawing one out, to the lips, away
			Vector3f lips = new Vector3f(mouth()).add(-0.035F, -0.045F, -0.05F);
			if (t < 10.0F) {
				l = lerp(REST_L, AT_PACK_L, span(t, 2.0F, 10.0F));
			} else if (t < 16.0F) {
				l = lerp(AT_PACK_L, PULLED_L, span(t, 10.0F, 16.0F));
				cig = 0.0F;
			} else if (t < 26.0F) {
				l = lerp(PULLED_L, lips, span(t, 16.0F, 26.0F));
				cig = span(t, 16.0F, 26.0F);
			} else {
				l = lerp(lips, REST_L, span(t, 26.0F, 34.0F));
				cig = 2.0F;
			}
		} else {
			cig = 2.0F;
			if (t < 34.0F) {
				l = lerp(new Vector3f(mouth()).add(-0.035F, -0.045F, -0.05F), REST_L, span(t, 26.0F, 34.0F));
			}
			if (t < 65.0F) {
				zippo = true;
				if (t < 40.0F) {
					r = lerp(AWAY_R, ZIP_R, span(t, 28.0F, 36.0F));
				} else if (t < 54.0F) {
					r = lerp(ZIP_R, lightR, span(t, 40.0F, 46.0F));
				} else if (t < 59.0F) {
					r = lerp(lightR, ZIP_R, span(t, 54.0F, 58.0F));
				} else {
					r = lerp(ZIP_R, AWAY_R, span(t, 59.0F, 65.0F));
				}
				lid = t < 57.0F ? span(t, 37.0F, 39.5F) : 1.0F - span(t, 57.0F, 58.5F);
				flame = t >= 40.0F && t < 58.0F ? Mth.clamp((t - 40.0F) / 2.0F, 0.0F, 1.0F) : 0.0F;
				if (t >= 46.0F && t < 54.0F) {
					flame *= 1.25F; // drawn up into the tip by the drag
				}
			} else {
				r = lerp(AWAY_R, REST_R, span(t, 65.0F, CigarettesItem.LIGHT_TIME));
			}
			glow = t >= CigarettesItem.T_DRAG ? (t < 56.0F ? Math.min(1.0F, (t - CigarettesItem.T_DRAG) / 3.0F) : 0.35F) : 0.0F;
		}
		// nerves: the hands are not quite steady
		float nerves = stress * stress * 0.004F;
		r.add(nerves * Mth.sin(time * 1.9F), nerves * Mth.sin(time * 2.7F + 1.0F), 0.0F);
		l.add(nerves * Mth.sin(time * 2.3F + 2.0F), nerves * Mth.sin(time * 1.7F), 0.0F);

		Vector3f cigAt = null;
		Quaternionf cigQ = null;
		if (cig >= 2.0F) {
			cigAt = mouth();
			cigQ = new Quaternionf(IN_MOUTH);
		} else if (cig >= 0.0F) {
			// held by the filter end: coming up out of the pack filter first, then turned and carried to the lips
			Vector3f fingers = new Vector3f(l).add(0.0F, 0.008F, 0.0F);
			cigAt = lerp(fingers, mouth(), cig);
			cigQ = new Quaternionf(UPRIGHT).slerp(IN_MOUTH, cig);
		}
		return new Frame(r, l, zippo, lid, flame, cig, glow, cigAt, cigQ);
	}

	/** Pushes a pose that puts the origin at camera-space {@code at}, turned by camera-space {@code q}. */
	private static void place(PoseStack poseStack, Matrix4f inv, Quaternionf undo, Vector3f at, Quaternionf q) {
		Vector3f h = inv.transformPosition(new Vector3f(at));
		poseStack.pushPose();
		poseStack.translate(h.x, h.y, h.z);
		poseStack.mulPose(new Quaternionf(undo).mul(q));
	}

	/** The flame lights up the hands and whatever is near, in the world (from the camera-space flame). */
	private static void flameLight(Minecraft mc, AbstractClientPlayer player, Vector3f at, float flame) {
		Camera camera = mc.gameRenderer.getMainCamera();
		float fov = mc.options.fov().get().floatValue() * player.getFieldOfViewModifier(true, mc.options.fovEffectScale().get().floatValue());
		float k = (float) (Math.tan(Math.toRadians(fov * 0.5)) / Math.tan(Math.toRadians(35.0)));
		Vector3f w = camera.rotation().transform(new Vector3f(at.x * k, at.y * k, at.z));
		Vec3 p = camera.position().add(w.x, w.y, w.z);
		DynamicLights.lamp(player.getId() * 16L + 11L, p, 0xFFB050, 0.5F * Math.min(1.0F, flame), 3.5F);
	}
}
