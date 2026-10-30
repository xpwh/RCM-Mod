package de.rcm.ballistic.base;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The power in the cities. A warhead, a bomb, an EMP knocks the grid out round where it went off: every powered
 * light in reach (room lights, neon, street lamps, traffic lights) is told, nearest first and with a little
 * scatter, so the dark spreads out block by block from the blast - each light stuttering a few times before it
 * dies, a few emergency lights coming on red. When the outage is over the power comes back district by
 * district, the lamps flickering on again.
 * <p>
 * Kept in memory: after a restart the city has power again.
 */
public final class CityPower {
	/** A light that lives on the grid; it reads {@link #outage} and {@link #restoring} on its ticks. */
	public interface Powered {
	}

	/** An outage: a circle round where the blast was, from {@code start} to {@code end} (game time). */
	public record Outage(BlockPos center, double radius, long start, long end) {
		boolean covers(BlockPos p) {
			return this.center.distSqr(p) <= this.radius * this.radius;
		}
	}

	/** How long after power is back a lamp may still stutter. */
	static final int RESTORE_FLICKER = 260;
	/** How long after the blast a lamp stutters before it gives up. */
	static final int FAIL_FLICKER = 140;

	private static final Map<ResourceKey<Level>, List<Outage>> ACTIVE = new HashMap<>();
	private static final Map<ResourceKey<Level>, List<Outage>> ENDED = new HashMap<>();

	private CityPower() {
	}

	public static void init() {
		ServerTickEvents.END_WORLD_TICK.register(CityPower::tick);
	}

	/**
	 * Knocks the power out within {@code radius} of {@code pos} for {@code ticks} (a little more or less), and
	 * starts the lights going dark.
	 */
	public static void strike(ServerLevel level, Vec3 pos, double radius, int ticks) {
		long now = level.getGameTime();
		BlockPos c = BlockPos.containing(pos);
		int length = (int) (ticks * (0.8 + 0.4 * level.getRandom().nextDouble()));
		ACTIVE.computeIfAbsent(level.dimension(), k -> new ArrayList<>()).add(new Outage(c, radius, now, now + length));
		wake(level, c, radius, true);
	}

	/** The outage the spot is in now (null: power is on). */
	public static @Nullable Outage outage(Level level, BlockPos pos) {
		List<Outage> list = ACTIVE.get(level.dimension());
		if (list == null) {
			return null;
		}
		long now = level.getGameTime();
		for (Outage o : list) {
			if (now >= o.start() && now < o.end() && o.covers(pos)) {
				return o;
			}
		}
		return null;
	}

	/** An outage over the spot that has just ended (the power coming back, the lamps stuttering on); null if none. */
	public static @Nullable Outage restoring(Level level, BlockPos pos) {
		List<Outage> list = ENDED.get(level.dimension());
		if (list == null) {
			return null;
		}
		long now = level.getGameTime();
		for (Outage o : list) {
			if (now - o.end() < RESTORE_FLICKER && o.covers(pos)) {
				return o;
			}
		}
		return null;
	}

	/**
	 * What a powered light should do on a tick while its power fails or comes back: true when it has just
	 * flickered (it changed its own state and asked for the next tick), false when it should settle.
	 */
	public static boolean flicker(ServerLevel level, BlockPos pos, RandomSource random, boolean lit, Runnable toggle) {
		long now = level.getGameTime();
		Outage o = outage(level, pos);
		boolean stutter;
		if (o != null) {
			// the first moments after the blast: the lights stutter, more at first, then die
			float left = 1.0F - (now - o.start()) / (float) FAIL_FLICKER;
			stutter = left > 0.0F && random.nextFloat() < 0.35F + 0.45F * left;
		} else {
			Outage r = restoring(level, pos);
			float left = r == null ? 0.0F : 1.0F - (now - r.end()) / (float) RESTORE_FLICKER;
			stutter = left > 0.0F && random.nextFloat() < 0.5F * left;
		}
		if (!stutter) {
			return false;
		}
		toggle.run();
		level.scheduleTick(pos, level.getBlockState(pos).getBlock(), lit ? 3 + random.nextInt(9) : 1 + random.nextInt(4));
		return true;
	}

	private static void tick(ServerLevel level) {
		List<Outage> list = ACTIVE.get(level.dimension());
		long now = level.getGameTime();
		List<Outage> ended = ENDED.computeIfAbsent(level.dimension(), k -> new ArrayList<>());
		ended.removeIf(o -> now - o.end() > RESTORE_FLICKER + 40);
		if (list == null || list.isEmpty()) {
			return;
		}
		for (Iterator<Outage> it = list.iterator(); it.hasNext(); ) {
			Outage o = it.next();
			if (now >= o.end()) {
				it.remove();
				ended.add(o);
				wake(level, o.center(), o.radius(), false);
			}
		}
	}

	/**
	 * Tells every powered light in reach (in the loaded chunks) to look again: when the power fails the
	 * nearest go first, a wave spreading out from the blast at a few dozen blocks a second; when it comes back
	 * the city lights up district by district.
	 */
	private static void wake(ServerLevel level, BlockPos c, double radius, boolean failing) {
		RandomSource random = level.getRandom();
		int r = Mth.ceil(radius);
		int minCX = SectionPos.blockToSectionCoord(c.getX() - r);
		int maxCX = SectionPos.blockToSectionCoord(c.getX() + r);
		int minCZ = SectionPos.blockToSectionCoord(c.getZ() - r);
		int maxCZ = SectionPos.blockToSectionCoord(c.getZ() + r);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int cx = minCX; cx <= maxCX; cx++) {
			for (int cz = minCZ; cz <= maxCZ; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				// a district's power comes back all at once, give or take
				float district = random.nextFloat();
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section.hasOnlyAir() || !section.maybeHas(s -> s.getBlock() instanceof Powered)) {
						continue;
					}
					int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								BlockState s = section.getBlockState(x, y, z);
								if (!(s.getBlock() instanceof Powered)) {
									continue;
								}
								m.set(SectionPos.sectionToBlockCoord(cx) + x, baseY + y, SectionPos.sectionToBlockCoord(cz) + z);
								double d = Math.sqrt(m.distSqr(c));
								if (d > radius) {
									continue;
								}
								int delay = failing ? 1 + (int) (d / 6.0) + random.nextInt(12) : 1 + (int) (district * 300.0F) + random.nextInt(60);
								level.scheduleTick(m.immutable(), s.getBlock(), delay);
							}
						}
					}
				}
			}
		}
	}
}
