package de.rcm.ballistic.client.render;

/**
 * Carried on a living thing's render state: how long it has lain dead, in ticks (negative: not a body left
 * lying), and how it is tumbling as it is thrown about (degrees about the world's x and z) and how much its
 * limbs flop (0..1).
 */
public interface CorpseAge {
	float ballisticmissiles$corpseAge();

	void ballisticmissiles$setCorpseAge(float ticks);

	float ballisticmissiles$tumbleX();

	float ballisticmissiles$tumbleZ();

	float ballisticmissiles$flail();

	void ballisticmissiles$setTumble(float x, float z, float flail);
}
