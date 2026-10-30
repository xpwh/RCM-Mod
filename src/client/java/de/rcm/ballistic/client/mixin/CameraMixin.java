package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.fighter.FighterClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In a fighter's cockpit the camera is fixed to the jet: it rolls, pitches and turns with the airframe,
 * from the pilot's seat, and the mouse only turns the pilot's head. Vanilla's camera knows only yaw and
 * pitch, so in a bank or a loop the cockpit would swing around the view.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	@Final
	private Vector3f forwards;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;

	@Shadow
	protected abstract void setPosition(Vec3 pos);

	@Inject(method = "setup", at = @At("HEAD"))
	private void ballisticmissiles$aimHead(Level level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
		this.ballisticmissiles$cockpit = FighterClient.cockpitCamera(Minecraft.getInstance(), partialTick);
	}

	private Quaternionf ballisticmissiles$cockpit;

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	/** Steps and landings eased (see SmoothCamera); the cutscenes and the cockpit below set the camera themselves. */
	@Inject(method = "setup", at = @At("TAIL"))
	private void ballisticmissiles$smoothMove(Level level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
		if (detached) {
			return;
		}
		double dy = de.rcm.ballistic.client.effect.SmoothCamera.offset(entity, partialTick);
		if (dy != 0.0) {
			this.setPosition(((net.minecraft.client.Camera) (Object) this).position().add(0.0, dy, 0.0));
		}
	}

	/** The new-moon cutscene: the eyes go up to the sky and come back. */
	@Inject(method = "setup", at = @At("TAIL"))
	private void ballisticmissiles$cutsceneView(Level level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
		if (de.rcm.ballistic.client.effect.NewMoonCutscene.playing() && !detached) {
			this.setRotation(de.rcm.ballistic.client.effect.NewMoonCutscene.yaw(entity.getViewYRot(partialTick), partialTick),
				de.rcm.ballistic.client.effect.NewMoonCutscene.pitch(entity.getViewXRot(partialTick), partialTick));
		}
	}

	/** The killcam: the camera is wherever the shot is, far from you. */
	@Inject(method = "setup", at = @At("TAIL"))
	private void ballisticmissiles$killcam(Level level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
		double[] view = de.rcm.ballistic.client.effect.KillcamClient.camera(partialTick);
		if (view != null) {
			this.setPosition(new Vec3(view[0], view[1], view[2]));
			this.setRotation((float) view[3], (float) view[4]);
		}
	}

	@Inject(method = "setup", at = @At("TAIL"))
	private void ballisticmissiles$cockpitView(Level level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
		Quaternionf q = this.ballisticmissiles$cockpit;
		if (q == null || detached || !(entity.getVehicle() instanceof de.rcm.ballistic.entity.FighterEntity jet)) {
			return;
		}
		this.rotation.set(q);
		this.rotation.transform(new Vector3f(0.0F, 0.0F, -1.0F), this.forwards);
		this.rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F), this.up);
		this.rotation.transform(new Vector3f(-1.0F, 0.0F, 0.0F), this.left);
		this.xRot = entity.getXRot();
		this.yRot = entity.getYRot();
		this.setPosition(FighterClient.cockpitEye(jet, partialTick));
	}
}
