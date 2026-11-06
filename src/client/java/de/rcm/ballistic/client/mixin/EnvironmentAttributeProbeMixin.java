package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.effect.NewMoonClient;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A new-moon night: no stars, no light from the sky (the moon itself fades out in {@link SkyRendererMixin}) - the camera's view of the world's
 * environment is darkened as the night falls ({@link NewMoonClient}).
 */
@Mixin(EnvironmentAttributeProbe.class)
public abstract class EnvironmentAttributeProbeMixin {
	@Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
	private <V> void ballisticmissiles$newMoon(EnvironmentAttribute<V> attribute, float partialTick, CallbackInfoReturnable<V> cir) {
		float heat = de.rcm.ballistic.client.effect.Vision.thermal(partialTick);
		if (heat > 0.0F && attribute == EnvironmentAttributes.SKY_LIGHT_FACTOR) {
			// a thermal camera sees the land the same by day and by night: a flat, even grey
			@SuppressWarnings("unchecked")
			V flat = (V) (Object) net.minecraft.util.Mth.lerp(heat, (Float) cir.getReturnValue(), 0.55F);
			cir.setReturnValue(flat);
			return;
		}
		float e = NewMoonClient.amount();
		if (e <= 0.0F) {
			return;
		}
		Object v = cir.getReturnValue();
		Object out = v;
		if (attribute == EnvironmentAttributes.STAR_BRIGHTNESS || attribute == EnvironmentAttributes.SKY_LIGHT_FACTOR) {
			out = (Float) v * (1.0F - e);
		} else if (attribute == EnvironmentAttributes.SKY_COLOR || attribute == EnvironmentAttributes.FOG_COLOR
			|| attribute == EnvironmentAttributes.CLOUD_COLOR) {
			int c = (Integer) v;
			out = ARGB.color(ARGB.alpha(c), ARGB.scaleRGB(c, 1.0F - e));
		}
		if (out != v) {
			@SuppressWarnings("unchecked")
			V cast = (V) out;
			cir.setReturnValue(cast);
		}
	}
}
