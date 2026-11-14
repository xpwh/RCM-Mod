package de.rcm.ballistic.client.ai;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.ai.SoldierEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;

/** The rifleman: a man in camouflage, helmet and plate carrier, holding his AK at the shoulder - or giving up. */
public class SoldierRenderer extends HumanoidMobRenderer<SoldierEntity, SoldierRenderer.State, SoldierRenderer.Model> {
	private static final Identifier TEXTURE = BallisticMissiles.id("textures/entity/soldier.png");

	public SoldierRenderer(EntityRendererProvider.Context context) {
		super(context, new Model(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
	}

	public static class State extends HumanoidRenderState {
		/** {@link SoldierEntity#FREE}, {@link SoldierEntity#SURRENDERED} or {@link SoldierEntity#CAPTURED}. */
		public int captive;
		/** Seconds since he gave up (or was tied). */
		public float since;
		public int id;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(SoldierEntity soldier, State state, float partialTick) {
		super.extractRenderState(soldier, state, partialTick);
		state.captive = soldier.captive();
		state.since = (soldier.level().getGameTime() - soldier.captiveSince() + partialTick) / 20.0F;
		state.id = soldier.getId();
	}

	@Override
	public Identifier getTextureLocation(State state) {
		return TEXTURE;
	}

	@Override
	protected HumanoidModel.ArmPose getArmPose(SoldierEntity soldier, HumanoidArm arm) {
		if (!soldier.isCaptive() && soldier.getMainHandItem().is(ModRegistry.AK47)) {
			return HumanoidModel.ArmPose.CROSSBOW_HOLD; // both hands on the rifle, at the shoulder
		}
		return super.getArmPose(soldier, arm);
	}

	@Override
	protected boolean shouldShowName(SoldierEntity soldier, double distanceSqr) {
		return AiDebugClient.text(soldier.getId()) != null || super.shouldShowName(soldier, distanceSqr);
	}

	@Override
	protected Component getNameTag(SoldierEntity soldier) {
		Component debug = AiDebugClient.text(soldier.getId());
		return debug != null ? debug : super.getNameTag(soldier);
	}

	/**
	 * The soldier's body, with the surrender played through: the rifle falls from his hands, the hands go up
	 * (a moment's hesitation, then all the way), he sinks to his knees, and lays his hands on his head, eyes
	 * down, trembling a little. Taken prisoner, his hands are pulled behind his back and tied there; walked
	 * along by his captor he keeps them there and his head down.
	 */
	public static class Model extends HumanoidModel<State> {
		public Model(ModelPart root) {
			super(root);
		}

		@Override
		public void setupAnim(State s) {
			super.setupAnim(s);
			if (s.captive == SoldierEntity.FREE) {
				// reloading: the same clip as a player's, read for his arms and head
				de.rcm.ballistic.client.gun.ThirdPersonReload.pose(this, s.id,
					net.minecraft.client.Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
				return;
			}
			float t = s.since;
			boolean walking = s.walkAnimationSpeed > 0.08F;
			float shake = Mth.sin(s.ageInTicks * 1.7F) * 0.02F + Mth.sin(s.ageInTicks * 2.9F) * 0.012F;
			if (s.captive == SoldierEntity.SURRENDERED) {
				// hands up: a first hesitant lift, then straight up, palms out
				float up = smooth((t - 0.15F) / 0.35F) * 0.55F + smooth((t - 0.45F) / 0.45F) * 0.45F;
				// then onto his knees, and the hands go onto his head
				float kneel = smooth((t - 1.1F) / 0.9F);
				float head = smooth((t - 2.1F) / 0.7F);
				this.arms(Mth.lerp(head, -2.95F, -2.6F) * up, Mth.lerp(head, -0.22F, 0.55F) * up, shake * up);
				this.kneel(kneel);
				this.head.xRot = Mth.lerp(kneel, this.head.xRot, 0.3F) + shake;
				return;
			}
			// a prisoner: hands behind the back (from wherever they were), head down
			float tie = smooth(t / 0.7F);
			this.rightArm.xRot = Mth.lerp(tie, -2.6F, 0.52F);
			this.leftArm.xRot = Mth.lerp(tie, -2.6F, 0.52F);
			this.rightArm.zRot = Mth.lerp(tie, 0.55F, -0.28F);
			this.leftArm.zRot = Mth.lerp(tie, -0.55F, 0.28F);
			this.rightArm.yRot = 0.0F;
			this.leftArm.yRot = 0.0F;
			if (!walking) {
				this.kneel(1.0F); // tied up he stays on his knees until he is walked off
			}
			this.head.xRot = 0.42F + shake;
		}

		/** Both arms raised to {@code x} (pitch) and spread by {@code z}, with a tremble. */
		private void arms(float x, float z, float shake) {
			this.rightArm.xRot = Mth.lerp(Math.abs(x) / 2.95F, this.rightArm.xRot, x) + shake;
			this.leftArm.xRot = Mth.lerp(Math.abs(x) / 2.95F, this.leftArm.xRot, x) - shake;
			this.rightArm.zRot = z;
			this.leftArm.zRot = -z;
			this.rightArm.yRot = 0.0F;
			this.leftArm.yRot = 0.0F;
		}

		/** Down on his knees: the whole body sinks, the legs fold back along the ground. */
		private void kneel(float k) {
			if (k <= 0.0F) {
				return;
			}
			float drop = 10.0F * k;
			this.head.y += drop;
			this.body.y += drop;
			this.rightArm.y += drop;
			this.leftArm.y += drop;
			this.rightLeg.y += drop;
			this.leftLeg.y += drop;
			this.rightLeg.xRot = Mth.lerp(k, this.rightLeg.xRot, 1.45F);
			this.leftLeg.xRot = Mth.lerp(k, this.leftLeg.xRot, 1.45F);
			this.rightLeg.yRot = Mth.lerp(k, this.rightLeg.yRot, 0.08F);
			this.leftLeg.yRot = Mth.lerp(k, this.leftLeg.yRot, -0.08F);
			this.rightLeg.zRot = 0.0F;
			this.leftLeg.zRot = 0.0F;
		}

		private static float smooth(float x) {
			x = Mth.clamp(x, 0.0F, 1.0F);
			return x * x * (3.0F - 2.0F * x);
		}
	}
}
