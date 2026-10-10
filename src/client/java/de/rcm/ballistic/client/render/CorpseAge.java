package de.rcm.ballistic.client.render;

/**
 * Carried on a living thing's render state: how long it has lain dead, in ticks (negative: not a body left
 * lying), and how it lies just now (see {@code CorpsePose}): {turn x, y, z, w, middle x, y, z, lift, flop}
 * - the turn about its middle, how far it is lifted off the ground, how much its limbs flop (0..1) - or null.
 */
public interface CorpseAge {
	float ballisticmissiles$corpseAge();

	void ballisticmissiles$setCorpseAge(float ticks);

	float[] ballisticmissiles$pose();

	void ballisticmissiles$setPose(float[] pose);

	/** How much the limbs flop (0..1). */
	default float ballisticmissiles$flail() {
		float[] p = this.ballisticmissiles$pose();
		return p == null ? 0.0F : p[8];
	}
}
