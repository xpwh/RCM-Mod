package de.rcm.ballistic.client.render;

/** Carried on a player's render state: which leg is shot away below the knee (0 none, else {@code Wounds.LEFT/RIGHT}). */
public interface LostLeg {
	int ballisticmissiles$lostLeg();

	void ballisticmissiles$setLostLeg(int side);
}
