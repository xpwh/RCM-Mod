package de.rcm.ballistic.client.mixin;

import de.rcm.ballistic.client.render.GroundPropState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Which entity and item a dropped item's render state belongs to, for {@link de.rcm.ballistic.client.render.GroundProps}. */
@Mixin(ItemEntityRenderState.class)
public class ItemEntityRenderStateMixin implements GroundPropState {
	@Unique
	private int ballisticmissiles$id;
	@Unique
	private Item ballisticmissiles$item = Items.AIR;
	@Unique
	private float ballisticmissiles$partial;

	@Override
	public void ballisticmissiles$set(int id, Item item, float partialTick) {
		this.ballisticmissiles$id = id;
		this.ballisticmissiles$item = item;
		this.ballisticmissiles$partial = partialTick;
	}

	@Override
	public int ballisticmissiles$id() {
		return this.ballisticmissiles$id;
	}

	@Override
	public Item ballisticmissiles$item() {
		return this.ballisticmissiles$item;
	}

	@Override
	public float ballisticmissiles$partial() {
		return this.ballisticmissiles$partial;
	}
}
