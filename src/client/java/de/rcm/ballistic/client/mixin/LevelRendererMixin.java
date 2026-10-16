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

	@Redirect(
		method = "extractVisibleEntities",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;entity()Lnet/minecraft/world/entity/Entity;", ordinal = 3),
		require = 0
	)
	private Entity ballisticmissiles$showPilot(Camera camera) {
		Minecraft mc = Minecraft.getInstance();
		if ((DroneClient.isFlying() || de.rcm.ballistic.client.sun.SunCutscene.playing()
			|| de.rcm.ballistic.client.effect.KillcamClient.playing()) && mc.player != null) {
			return mc.player; // "the camera is in you": your body is drawn
		}
		return camera.entity();
	}
}
