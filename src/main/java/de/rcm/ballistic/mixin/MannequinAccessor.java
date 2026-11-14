package de.rcm.ballistic.mixin;

import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The mannequin's own setters, for laying out a body that looks like the player who died. */
@Mixin(Mannequin.class)
public interface MannequinAccessor {
	@Invoker("setProfile")
	void ballisticmissiles$setProfile(ResolvableProfile profile);

	@Invoker("setImmovable")
	void ballisticmissiles$setImmovable(boolean immovable);

	@Invoker("setHideDescription")
	void ballisticmissiles$setHideDescription(boolean hide);
}
