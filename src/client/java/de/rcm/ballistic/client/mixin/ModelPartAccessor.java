package de.rcm.ballistic.client.mixin;

import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A model part's own boxes, to draw them with a hole cut out of them ({@code HoledBox}). */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {
	@Accessor("cubes")
	List<ModelPart.Cube> ballisticmissiles$cubes();
}
