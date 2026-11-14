package de.rcm.ballistic.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import de.rcm.ballistic.client.effect.Storm;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LightningBoltRenderer;
import net.minecraft.client.renderer.entity.state.LightningBoltRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;

/** Minecraft's lightning bolts: with the storms on we draw them ourselves (Storm), so Minecraft's own blocky one is not drawn. */
public final class StormLightningRenderer extends LightningBoltRenderer {
	public StormLightningRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public void submit(LightningBoltRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!Storm.enabled()) {
			super.submit(state, poseStack, collector, camera);
		}
	}
}
