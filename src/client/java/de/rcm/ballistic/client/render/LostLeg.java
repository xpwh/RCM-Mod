package de.rcm.ballistic.client.render;

/**
 * Carried on a player's render state: the legs and arms gone (bit sets of {@code Wounds.LEFT/RIGHT}) and
 * the head wound ({@code Wounds.GRAZED}, {@code Wounds.SHATTERED}).
 */
public interface LostLeg {
	int ballisticmissiles$lostLeg();

	void ballisticmissiles$setLostLeg(int side);

	int ballisticmissiles$lostArm();

	void ballisticmissiles$setLostArm(int side);

	int ballisticmissiles$head();

	void ballisticmissiles$setHead(int head);

	/** Ticks into the collapse after a shot through the head (-1 none), and which way the body falls. */
	float ballisticmissiles$collapse();

	int ballisticmissiles$fall();

	void ballisticmissiles$setCollapse(float t, int fall);
}
