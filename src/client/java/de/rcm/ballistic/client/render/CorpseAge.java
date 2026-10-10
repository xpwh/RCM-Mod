package de.rcm.ballistic.client.render;

/** Carried on a living thing's render state: how long it has lain dead, in ticks (negative: not a body left lying). */
public interface CorpseAge {
	float ballisticmissiles$corpseAge();

	void ballisticmissiles$setCorpseAge(float ticks);
}
