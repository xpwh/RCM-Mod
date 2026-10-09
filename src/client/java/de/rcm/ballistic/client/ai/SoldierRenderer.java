package de.rcm.ballistic.client.ai;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.ai.SoldierEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;

/** The rifleman: a man in camouflage, helmet and plate carrier, holding his AK at the shoulder. */
public class SoldierRenderer extends HumanoidMobRenderer<SoldierEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
	private static final Identifier TEXTURE = BallisticMissiles.id("textures/entity/soldier.png");

	public SoldierRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public Identifier getTextureLocation(HumanoidRenderState state) {
		return TEXTURE;
	}

	@Override
	protected HumanoidModel.ArmPose getArmPose(SoldierEntity soldier, HumanoidArm arm) {
		if (soldier.getMainHandItem().is(ModRegistry.AK47)) {
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
}
