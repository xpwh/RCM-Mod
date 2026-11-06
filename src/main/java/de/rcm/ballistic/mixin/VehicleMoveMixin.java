package de.rcm.ballistic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.rcm.ballistic.entity.FighterEntity;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The server refuses a vehicle move of more than 10 blocks beyond the vehicle's last known velocity
 * ("moved too quickly"). A fighter covers up to 34 blocks a tick and its pilot's packets can bunch up,
 * so for a jet the allowance is a few ticks' worth of its top speed.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class VehicleMoveMixin {
	@WrapOperation(method = "handleMoveVehicle", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getDeltaMovement()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 ballisticmissiles$jetAllowance(Entity vehicle, Operation<Vec3> original) {
		Vec3 v = original.call(vehicle);
		if (vehicle instanceof FighterEntity jet) {
			return new Vec3(0.0, Math.max(v.length(), jet.type().maxSpeed * 3.0), 0.0);
		}
		return v;
	}
}
