package de.rcm.ballistic.block;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The second silo variant, after the Soviet/Russian silo launchers: no headworks, just a round domed
 * armoured lid in a concrete collar flush with the ground. Opening runs in phases - the locking bolts
 * round the rim pull back, the lid cracks off its seal, then two hydraulic rams haul it up on its hinge
 * until it stands a little past vertical. Works exactly like the {@link MissileSiloBlock} (same shaft,
 * loading and launch); only the look, the timing and the walkable lid differ.
 */
public class HatchSiloBlock extends MissileSiloBlock {
	/** Ticks for the whole opening (or closing) travel. */
	public static final int TRAVEL_TICKS = 110;
	/** How many ticks before launch the lid starts opening (it must be fully open by then). */
	public static final int OPEN_BEFORE = TRAVEL_TICKS + 20;
	/** Travel fractions: bolts drawn by {@link #BOLTS_END}, seal cracked by {@link #CRACK_END}, then the swing. */
	public static final float BOLTS_END = 0.14F;
	public static final float CRACK_END = 0.24F;
	/** The lid's angle when cracked off the seal and when fully open (radians). */
	public static final float CRACK_ANGLE = 0.06F;
	public static final float OPEN_ANGLE = 1.86F;

	private static final VoxelShape RIM = Block.box(0, 12, 0, 16, 16, 16);
	/** The closed lid: collar and the domed cover stepped up towards the middle. */
	private static final VoxelShape LID = Shapes.or(
		Block.box(-16, 12, -16, 32, 17.6, 32), Block.box(-10, 12, -10, 26, 21.5, 26), Block.box(-4, 12, -4, 20, 24.5, 20)
	);

	public HatchSiloBlock(Properties properties) {
		super(properties);
	}

	/** How far the bolts are drawn back, 0-1, at travel {@code t}. */
	public static float bolts(float t) {
		return smooth(0.0F, BOLTS_END, t);
	}

	/** The lid's hinge angle at travel {@code t} (radians). */
	public static float lidAngle(float t) {
		float crack = smooth(BOLTS_END, CRACK_END, t);
		float swing = smooth(CRACK_END, 1.0F, t);
		return CRACK_ANGLE * crack + (OPEN_ANGLE - CRACK_ANGLE) * swing;
	}

	private static float smooth(float a, float b, float x) {
		float t = Mth.clamp((x - a) / (b - a), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return RIM;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(OPEN) ? Shapes.empty() : LID;
	}
}
