package de.rcm.ballistic.client.gun;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;

/**
 * Follow-through: a gun is a weight on the ends of two arms, it does not stop dead where the animation stops it.
 * Every sudden change in the clip's motion (a swing coming to rest, the slap of a magazine, the jolt of the slide)
 * carries on into the gun a little - it swings past and settles back, rings after a jolt. Springs on the clip's
 * pose, kicked by its changes of speed.
 */
final class FollowThrough {
	private static final class Spring {
		final float stiffness;
		final float damping;
		float x;
		float v;

		Spring(float frequency, float ratio) {
			this.stiffness = frequency * frequency;
			this.damping = 2.0F * ratio * frequency;
		}

		void step(float dt) {
			int n = Math.max(1, (int) Math.ceil(dt / 0.1F));
			float h = dt / n;
			for (int i = 0; i < n; i++) {
				this.v += (-this.stiffness * this.x - this.damping * this.v) * h;
				this.x += this.v * h;
			}
		}
	}

	/** Roll, pitch, yaw (degrees) and the lift (metres): how far the clip's pose carries on. */
	private final Spring[] springs = {new Spring(1.3F, 0.32F), new Spring(1.5F, 0.3F), new Spring(1.3F, 0.32F), new Spring(1.6F, 0.35F)};
	private static final float[] GAIN = {0.3F, 0.32F, 0.3F, 0.35F};
	private static final float[] MAX = {9.0F, 7.0F, 9.0F, 0.03F};
	/** Channels of the clip pose followed: roll, pitch, yaw, y. */
	private static final int[] CHANNEL = {3, 4, 5, 1};
	private final float[] last = new float[4];
	private final float[] lastSpeed = new float[4];
	private boolean primed;

	/** {@code pose}: the clip pose applied this frame (gun channels 0-5, zero when none); {@code dt} ticks since the last frame. */
	void step(float[] pose, float dt) {
		if (dt <= 1.0E-4F) {
			return;
		}
		if (!this.primed || dt > 2.0F) {
			for (int i = 0; i < 4; i++) {
				this.last[i] = pose[CHANNEL[i]];
				this.lastSpeed[i] = 0.0F;
			}
			this.primed = true;
			return;
		}
		for (int i = 0; i < 4; i++) {
			float p = pose[CHANNEL[i]];
			float limit = i == 3 ? 0.05F : 25.0F;
			if (Math.abs(p - this.last[i]) > (i == 3 ? 0.04F : 10.0F)) {
				// a cut (a clip starting away from rest): nothing to carry on
				this.last[i] = p;
				this.lastSpeed[i] = 0.0F;
				continue;
			}
			float speed = Mth.clamp((p - this.last[i]) / dt, -limit, limit);
			// inertia: the clip speeds up, the weight lags; it slows (or stops), the weight carries on the way it was going
			float change = speed - this.lastSpeed[i];
			this.springs[i].v -= Mth.clamp(change, -limit, limit) * GAIN[i];
			this.springs[i].step(dt);
			this.springs[i].x = Mth.clamp(this.springs[i].x, -MAX[i], MAX[i]);
			this.last[i] = p;
			this.lastSpeed[i] = speed;
		}
	}

	/** The carry-on, about the grip. */
	void apply(PoseStack poseStack) {
		poseStack.translate(0.0F, this.springs[3].x, 0.0F);
		poseStack.mulPose(Axis.ZP.rotationDegrees(this.springs[0].x));
		poseStack.mulPose(Axis.XP.rotationDegrees(this.springs[1].x));
		poseStack.mulPose(Axis.YP.rotationDegrees(this.springs[2].x));
	}
}
