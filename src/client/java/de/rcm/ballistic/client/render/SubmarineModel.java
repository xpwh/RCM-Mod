package de.rcm.ballistic.client.render;

import static de.rcm.ballistic.client.render.StructureKit.RED_LAMP;
import static de.rcm.ballistic.client.render.StructureKit.v;

/**
 * Ohio-class-style ballistic missile submarine, 34 blocks long, lying on the sea floor with the silo block in its
 * keel, modelled in Blender (tools/structures/submarine.py): the pressure hull under its anechoic tiles, the long
 * turtleback with 24 missile tubes in two rows behind flush hatches, limber holes along its flanks, the sail with
 * fairwater planes and masts, the cruciform stern with end plates and a seven-blade propeller, silt and growth low
 * down, draft marks at bow and stern.
 * <p>
 * Sub space: +Z is the bow, the keel at y = 0.1, origin at the block's bottom centre. The live missile tube (the
 * block's) rises through the hull to its hatch at the origin's x/z; it is one of the starboard row, so the hull's
 * centreline runs {@link #HULL_X} to the side.
 */
final class SubmarineModel {
	static final float HULL_RADIUS = 2.1F;
	static final float AXIS_Y = 2.2F;
	static final float DECK_Y = AXIS_Y + HULL_RADIUS + 0.35F;
	/** The hull's centreline (the tube rows stand at x = 0 and x = -1.1). */
	static final float HULL_X = -0.55F;
	/** The live hatch's hinge along its aft edge, at deck height. */
	static final float HATCH_HINGE_Z = -0.45F;

	/** The boat as built in Blender: the hull with everything fixed, and the live tube's hatch about its hinge. */
	static final StructureModel MODEL = StructureModel.load("submarine");
	static final BoxMesh HULL = MODEL.part("hull");
	/** The live tube's hatch, hinged at its aft edge (0, DECK_Y, -0.45), drawn closed about that hinge. */
	static final BoxMesh HATCH = MODEL.part("hatch");
	/** The red light on top of the communications mast. */
	static final BoxMesh MAST_LIGHT = new BoxMesh.Builder()
		.revolve(RED_LAMP, v(HULL_X, DECK_Y + 4.545F, 8.2F), v(0, 1, 0), new float[][] {{0.0F, 0.07F}, {0.095F, 0.07F}, {0.14F, 0.042F}, {0.155F, 0.0F}}, 8)
		.build();

	private SubmarineModel() {
	}
}
