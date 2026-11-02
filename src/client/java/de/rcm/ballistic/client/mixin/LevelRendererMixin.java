package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.drone.DroneClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.systems.RenderSystem;
import de.rcm.ballistic.client.effect.LatePass;
import net.minecraft.client.renderer.LevelTargetBundle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft only draws your own body when the camera is in it. Flying a drone, the camera is in the
 * drone - and you want to see yourself standing there with the goggles on, like everyone else does.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelTargetBundle targets;

	/** Smoke and volumetric clouds are drawn after the particles, so explosions behind them stay hidden. */
	@Inject(method = "addParticlesPass", at = @At("TAIL"))
	private void ballisticmissiles$afterParticles(FrameGraphBuilder graph, GpuBufferSlice fog, CallbackInfo ci) {
		FramePass pass = graph.addPass("ballisticmissiles_late");
		this.targets.main = pass.readsAndWrites(this.targets.main);
		if (this.targets.particles != null) {
			pass.reads(this.targets.particles);
		}
		pass.executes(() -> {
			RenderSystem.setShaderFog(fog);
			LatePass.run();
		});
	}

	/** Your own body in first person (FirstPersonBody): the world draws it as if the camera were outside it. */
	@Redirect(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z"), require = 0)
	private boolean ballisticmissiles$ownBody(Camera camera) {
		return camera.isDetached() || de.rcm.ballistic.client.render.FirstPersonBody.wanted(camera);
	}

	/** ... stripped of what would sit in your face (the head, the helmet, the arms and what is in your hands). */
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;extractEntity(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
	private net.minecraft.client.renderer.entity.state.EntityRenderState ballisticmissiles$stripOwnBody(LevelRenderer self, Entity entity, float partial,
		com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.client.renderer.entity.state.EntityRenderState> original) {
		net.minecraft.client.renderer.entity.state.EntityRenderState state = original.call(self, entity, partial);
		de.rcm.ballistic.client.render.FirstPersonBody.extracted(entity, state);
		return state;
	}

	@Redirect(
		method = "extractVisibleEntities",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;entity()Lnet/minecraft/world/entity/Entity;", ordinal = 3),
		require = 0
	)
	private Entity ballisticmissiles$showPilot(Camera camera) {
		Minecraft mc = Minecraft.getInstance();
		if ((DroneClient.isFlying()
			|| de.rcm.ballistic.client.effect.KillcamClient.playing()) && mc.player != null) {
			return mc.player; // "the camera is in you": your body is drawn
		}
		return camera.entity();
	}
}
