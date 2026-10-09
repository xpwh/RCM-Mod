package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.drone.DroneClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Minecraft only draws your own body when the camera is in it. Flying a drone, the camera is in the
 * drone - and you want to see yourself standing there with the goggles on, like everyone else does.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Redirect(
		method = "extractVisibleEntities",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;entity()Lnet/minecraft/world/entity/Entity;", ordinal = 3),
		require = 0
	)
	private Entity ballisticmissiles$showPilot(Camera camera) {
		Minecraft mc = Minecraft.getInstance();
		if (DroneClient.isFlying() && mc.player != null) {
			return mc.player; // "the camera is in you": your body is drawn
		}
		return camera.entity();
	}
}
