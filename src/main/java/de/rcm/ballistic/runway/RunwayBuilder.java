package de.rcm.ballistic.runway;

import de.rcm.ballistic.ModRegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lays a military runway, a few rows a tick so the server keeps up: 600 m of asphalt, 27 m wide, on
 * an embankment where the ground falls away and cut through hills and forest where it rises, with
 * paved shoulders, grass strips and the standard ICAO markings and lights:
 * <ul>
 * <li>threshold "piano keys" at each end, the runway designator (its magnetic heading in tens of
 * degrees: a runway pointing east is "09", its other end "27"),</li>
 * <li>aiming-point and touchdown-zone bars, a dashed centreline, solid white side stripes,</li>
 * <li>white edge lights every 10 m, a green threshold bar at the end you start from and land over,
 * a red one across the far end.</li>
 * </ul>
 * On it a jet rolls freely; on grass and dirt it bogs down and barely gets to flying speed.
 */
public final class RunwayBuilder {
	public static final int LENGTH = 600;
	/** Half width of the paved runway (27 m). */
	private static final int HALF = 13;
	/** Half width of everything the builder touches: paved shoulder, then grass with the edge lights. */
	private static final int OUTER = 17;
	private static final int OVERRUN = 6;
	private static final int CLEAR = 32;
	private static final int ROWS_PER_TICK = 3;

	private static final List<Job> JOBS = new ArrayList<>();

	private static final class Job {
		final ServerLevel level;
		final BlockPos origin;
		final Direction f;
		int row = -OVERRUN;

		Job(ServerLevel level, BlockPos origin, Direction f) {
			this.level = level;
			this.origin = origin;
			this.f = f;
		}
	}

	private RunwayBuilder() {
	}

	public static void start(ServerLevel level, BlockPos origin, Direction f) {
		JOBS.add(new Job(level, origin, f));
		level.playSound(null, origin, ModRegistry.TRUCK_DIESEL, SoundSource.BLOCKS, 2.0F, 0.9F);
		message(level, origin, Component.translatable("message.ballisticmissiles.runway_started", designator(f), designator(f.getOpposite()))
			.withStyle(ChatFormatting.YELLOW));
	}

	public static void tick(ServerLevel level) {
		for (Iterator<Job> it = JOBS.iterator(); it.hasNext();) {
			Job job = it.next();
			if (job.level != level) {
				continue;
			}
			for (int i = 0; i < ROWS_PER_TICK && job.row < LENGTH + OVERRUN; i++, job.row++) {
				row(job, job.row);
			}
			if (job.row >= LENGTH + OVERRUN) {
				it.remove();
				message(level, job.origin, Component.translatable("message.ballisticmissiles.runway_built").withStyle(ChatFormatting.GREEN));
				level.playSound(null, job.origin, ModRegistry.RADIO_SQUELCH, SoundSource.BLOCKS, 1.5F, 1.0F);
			}
		}
	}

	public static void clear() {
		JOBS.clear();
	}

	private static void message(ServerLevel level, BlockPos pos, Component text) {
		for (var player : level.players()) {
			if (player.blockPosition().distSqr(pos) < 96 * 96) {
				player.displayClientMessage(text, true);
			}
		}
	}

	/** The runway number for taking off towards {@code f}: its heading in tens of degrees. */
	static int designator(Direction f) {
		return switch (f) {
			case EAST -> 9;
			case SOUTH -> 18;
			case WEST -> 27;
			default -> 36;
		};
	}

	private static void row(Job job, int a) {
		ServerLevel level = job.level;
		Direction right = job.f.getClockWise();
		BlockState asphalt = ModRegistry.RUNWAY_ASPHALT.defaultBlockState();
		BlockState paint = ModRegistry.RUNWAY_MARKING.defaultBlockState();
		boolean overrun = a < 0 || a >= LENGTH;
		for (int s = -OUTER; s <= OUTER; s++) {
			BlockPos surface = job.origin.relative(job.f, a).relative(right, s);
			level.getChunk(surface.getX() >> 4, surface.getZ() >> 4); // load (or generate) what lies ahead
			// cut: everything above the runway goes, trees and hills alike
			for (int u = 1; u <= CLEAR; u++) {
				BlockPos p = surface.above(u);
				if (!level.getBlockState(p).isAir()) {
					set(level, p, Blocks.AIR.defaultBlockState());
				}
			}
			// fill: an embankment of packed earth and gravel where the ground falls away
			for (int u = 1; u <= 24; u++) {
				BlockPos p = surface.below(u);
				BlockState old = level.getBlockState(p);
				if (!old.canBeReplaced() && old.getFluidState().isEmpty()) {
					break;
				}
				set(level, p, u <= 2 ? Blocks.GRAVEL.defaultBlockState() : Blocks.PACKED_MUD.defaultBlockState());
			}
			int abs = Math.abs(s);
			BlockState top;
			if (abs <= HALF + 1) {
				top = !overrun && painted(a, s, job.f) ? paint : asphalt;
			} else {
				top = Blocks.GRASS_BLOCK.defaultBlockState();
				set(level, surface.below(), Blocks.DIRT.defaultBlockState());
			}
			set(level, surface, top);
			// lights: white edge lights every 10 m, the threshold bars across both ends
			BlockState light = null;
			if (abs == HALF + 3 && !overrun && Math.floorMod(a, 10) == 0) {
				light = light(RunwayLightBlock.WHITE);
			} else if (a == -1 && abs <= HALF + 3 && abs % 2 == 1) {
				light = light(RunwayLightBlock.GREEN);
			} else if (a == LENGTH && abs <= HALF + 3 && abs % 2 == 1) {
				light = light(RunwayLightBlock.RED);
			}
			if (light != null) {
				set(level, surface.above(), light);
			}
		}
	}

	private static BlockState light(int color) {
		return ModRegistry.RUNWAY_LIGHT.defaultBlockState().setValue(RunwayLightBlock.COLOR, color);
	}

	/** Whether the runway is painted white at row {@code a}, column {@code s} (each end marked the same way). */
	private static boolean painted(int a, int s, Direction f) {
		int abs = Math.abs(s);
		if (abs == HALF) {
			return true; // side stripe
		}
		boolean near = a < LENGTH / 2;
		int d = near ? a : LENGTH - 1 - a;
		int x = near ? s : -s; // across, left to right as the pilot rolling in from that end sees it
		// threshold piano keys
		if (d >= 4 && d <= 21 && abs >= 3 && abs <= 12 && (abs - 3) % 3 != 2) {
			return true;
		}
		// runway designator, read from the threshold: two digits, 6 m wide and 10 m long each
		if (d >= 27 && d <= 36) {
			int number = designator(near ? f : f.getOpposite());
			int row = (36 - d) / 2; // glyph row 0 at the far edge (the top of the figures)
			if (x >= -8 && x <= -3) {
				return glyph(number / 10, (x + 8) / 2, row);
			}
			if (x >= 3 && x <= 8) {
				return glyph(number % 10, (x - 3) / 2, row);
			}
			return false;
		}
		// aiming point: two broad bars
		if (d >= 75 && d <= 92 && abs >= 5 && abs <= 7) {
			return true;
		}
		// touchdown zone: pairs of bars
		if ((d >= 110 && d <= 116 || d >= 140 && d <= 145) && (abs >= 4 && abs <= 5 || abs >= 8 && abs <= 9)) {
			return true;
		}
		// centreline: 12 m dashes, 8 m gaps
		return d >= 45 && s == 0 && Math.floorMod(d - 45, 20) < 12;
	}

	private static final String[] DIGITS = {
		"111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001",
		"111100111001111", "111100111101111", "111001001001001", "111101111101111", "111101111001111"
	};

	/** A 3x5 figure: is cell (column, row) of digit {@code n} painted? */
	private static boolean glyph(int n, int column, int row) {
		if (column < 0 || column > 2 || row < 0 || row > 4) {
			return false;
		}
		return DIGITS[n].charAt(row * 3 + column) == '1';
	}

	private static void set(ServerLevel level, BlockPos p, BlockState state) {
		BlockState old = level.getBlockState(p);
		if (old == state || old.getDestroySpeed(level, p) < 0.0F || old.hasBlockEntity()) {
			return; // bedrock, or someone's chest: leave it
		}
		level.setBlock(p, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
	}

	/** Whether a jet's wheels at {@code state} roll on a runway. */
	public static boolean isRunway(BlockState state) {
		return state.is(ModRegistry.RUNWAY_ASPHALT) || state.is(ModRegistry.RUNWAY_MARKING) || state.is(ModRegistry.RUNWAY_KIT);
	}
}
