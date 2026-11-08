package de.rcm.ballistic.client.render;

import net.minecraft.world.item.Item;

/** Added to the dropped item's render state: the entity and item it shows. */
public interface GroundPropState {
	void ballisticmissiles$set(int id, Item item, float partialTick);

	int ballisticmissiles$id();

	Item ballisticmissiles$item();

	float ballisticmissiles$partial();
}
