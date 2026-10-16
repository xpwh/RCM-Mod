package de.rcm.ballistic.client.sun;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Where things stand at the Helios launch complex, in its own frame (+Z towards the way the block faces, origin at
 * the block's bottom centre), for the model and for the effects that come out of it.
 */
public final class SunLayout {
	/** The concrete plinth the accelerator stands on, and the shaft in it the round comes up from. */
	public static final float PLINTH = 5.0F;
	public static final float PLINTH_HALF = 9.0F;
	public static final float SHAFT_HALF = 2.4F;
	/** Half the spacing of the tower's four legs. */
	public static final float LEG = 4.4F;
	public static final float BARREL_R = 1.25F;
	/** The cooling towers: centre (x, z), height. */
	public static final float[][] TOWERS = {{-17.0F, 13.0F}, {17.0F, 13.0F}};
	public static final float TOWER_H = 21.0F;
	/** The tower crane: where its mast stands, how high the jib is, how long. */
	public static final float CRANE_X = 13.5F;
	public static final float CRANE_Z = -3.0F;
	public static final float CRANE_H = 58.0F;
	public static final float JIB = 17.0F;

	private SunLayout() {
	}

	public static float angle(Direction facing) {
		return (float) Mth.atan2(facing.getStepX(), facing.getStepZ());
	}

	/** A point of the complex's frame, turned to how the block faces (relative to its bottom centre). */
	public static Vec3 turn(Direction facing, double x, double y, double z) {
		float a = angle(facing);
		double c = Math.cos(a);
		double s = Math.sin(a);
		return new Vec3(x * c + z * s, y, -x * s + z * c);
	}

	/** The tops of the cooling towers, where their steam comes out. */
	public static Vec3[] towerTops(Direction facing) {
		Vec3[] out = new Vec3[TOWERS.length];
		for (int i = 0; i < TOWERS.length; i++) {
			out[i] = turn(facing, TOWERS[i][0], TOWER_H + 0.5, TOWERS[i][1]);
		}
		return out;
	}
}
