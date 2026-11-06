package de.rcm.ballistic.mixin;

import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Writes a sign's text without telling the world: during world generation the sign is not in a world yet, and
 * {@link SignBlockEntity#setText} sends a block update to it (and crashes the chunk's generation).
 */
@Mixin(SignBlockEntity.class)
public interface SignBlockEntityAccessor {
	@Accessor("frontText")
	void ballisticmissiles$setFrontText(SignText text);

	@Accessor("backText")
	void ballisticmissiles$setBackText(SignText text);
}
