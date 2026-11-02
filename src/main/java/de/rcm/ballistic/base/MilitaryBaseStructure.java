package de.rcm.ballistic.base;

import com.mojang.serialization.MapCodec;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

/**
 * A military base: a fenced compound of about 75 x 75 blocks on open, level ground - watchtowers, a gate,
 * barracks, a command bunker, a fuel depot with the generators, an ammunition dump, a helipad - round one of
 * four kinds of site: a radar station, a missile silo field, an airfield (with a runway outside the fence and
 * a jet in the hangar) or a deep bunker. Guarded by soldiers; see {@link BaseFlagBlockEntity}.
 */
public class MilitaryBaseStructure extends Structure {
	public static final MapCodec<MilitaryBaseStructure> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(i -> i.group(
		settingsCodec(i),
		com.mojang.serialization.Codec.STRING.optionalFieldOf("site", "base").forGetter(s -> s.site)
	).apply(i, MilitaryBaseStructure::new));

	/** Which kind: "base" (the full compound, one of four variants), "checkpoint", "sam_site" or "cache". */
	private final String site;

	public MilitaryBaseStructure(StructureSettings settings, String site) {
		super(settings);
		this.site = site;
	}

	@Override
	protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
		int x = context.chunkPos().getMiddleBlockX();
		int z = context.chunkPos().getMiddleBlockZ();
		int variant = switch (this.site) {
			case "checkpoint" -> MilitaryBasePiece.CHECKPOINT;
			case "sam_site" -> MilitaryBasePiece.SAM_SITE;
			case "cache" -> MilitaryBasePiece.CACHE;
			case "carrier" -> MilitaryBasePiece.CARRIER;
			case "warzone" -> MilitaryBasePiece.WARZONE;
			case "crash" -> MilitaryBasePiece.CRASH;
			case "sub_pen" -> MilitaryBasePiece.SUB_PEN;
			case "lab" -> MilitaryBasePiece.LAB;
			case "trench" -> MilitaryBasePiece.TRENCH;
			default -> context.random().nextInt(MilitaryBasePiece.VARIANTS);
		};
		Rotation rotation = Rotation.getRandom(context.random());
		long seed = context.random().nextLong();
		if (variant == MilitaryBasePiece.CARRIER) {
			return this.atSea(context, x, z, rotation, seed);
		}
		if (variant == MilitaryBasePiece.SUB_PEN) {
			return this.onCoast(context, x, z, rotation, seed);
		}
		boolean big = variant < MilitaryBasePiece.CHECKPOINT || variant == MilitaryBasePiece.WARZONE || variant == MilitaryBasePiece.TRENCH;
		int step = big ? 18 : 9;
		// reasonably level ground; a pond or two is filled in, but not a lake or the sea
		int[] heights = new int[25];
		int n = 0;
		int wet = 0;
		for (int i = -2; i <= 2; i++) {
			for (int j = -2; j <= 2; j++) {
				int sx = x + i * step;
				int sz = z + j * step;
				int top = context.chunkGenerator().getFirstOccupiedHeight(sx, sz, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
				int floor = context.chunkGenerator().getFirstOccupiedHeight(sx, sz, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
				if (top - floor > 3 || top != floor && (i == 0 && j == 0)) {
					return Optional.empty(); // deep water, or water right in the middle
				}
				if (top != floor) {
					wet++;
				}
				heights[n++] = floor;
			}
		}
		Arrays.sort(heights);
		int spread = heights[21] - heights[3];
		if (wet > 4 || spread > (big ? 16 : 12) || heights[24] - heights[0] > (big ? 30 : 22)) {
			return Optional.empty();
		}
		int y = heights[12];
		if (y < context.chunkGenerator().getSeaLevel()) {
			return Optional.empty();
		}
		BlockPos origin = new BlockPos(x, y, z);
		return Optional.of(new GenerationStub(origin, builder -> builder.addPiece(new MilitaryBasePiece(origin, rotation, variant, seed))));
	}

	private static int lx(int x, Rotation rot, int a, int b) {
		return switch (rot) {
			case NONE -> x + a;
			case CLOCKWISE_90 -> x - b;
			case CLOCKWISE_180 -> x - a;
			case COUNTERCLOCKWISE_90 -> x + b;
		};
	}

	private static int lz(int z, Rotation rot, int a, int b) {
		return switch (rot) {
			case NONE -> z + b;
			case CLOCKWISE_90 -> z + a;
			case CLOCKWISE_180 -> z - b;
			case COUNTERCLOCKWISE_90 -> z - a;
		};
	}

	/** Water depth at a spot: surface minus floor (0 on land), and the floor itself. */
	private static int[] probe(GenerationContext c, int x, int z) {
		int top = c.chunkGenerator().getFirstOccupiedHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, c.heightAccessor(), c.randomState());
		int floor = c.chunkGenerator().getFirstOccupiedHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, c.heightAccessor(), c.randomState());
		return new int[] {top - floor, floor, top};
	}

	/** The carrier: open, deep sea under the whole of her. */
	private Optional<GenerationStub> atSea(GenerationContext c, int x, int z, Rotation rot, long seed) {
		int sea = c.chunkGenerator().getSeaLevel();
		for (int a = -12; a <= 12; a += 12) {
			for (int b = -68; b <= 64; b += 11) {
				int[] p = probe(c, lx(x, rot, a, b), lz(z, rot, a, b));
				if (p[0] < 10 || p[2] > sea + 1) {
					return Optional.empty();
				}
			}
		}
		BlockPos origin = new BlockPos(x, sea, z);
		return Optional.of(new GenerationStub(origin, builder -> builder.addPiece(new MilitaryBasePiece(origin, rot, MilitaryBasePiece.CARRIER, seed))));
	}

	/** The submarine pen: dry, low land behind, the sea in front - turned so its gate faces the water. */
	private Optional<GenerationStub> onCoast(GenerationContext c, int x, int z, Rotation first, long seed) {
		int sea = c.chunkGenerator().getSeaLevel();
		Rotation[] all = Rotation.values();
		search:
		for (int i = 0; i < 4; i++) {
			Rotation rot = all[(first.ordinal() + i) % 4];
			for (int[] s : new int[][] {{0, -42}, {-5, -36}, {5, -36}, {0, -30}}) {
				if (probe(c, lx(x, rot, s[0], s[1]), lz(z, rot, s[0], s[1]))[0] < 4) {
					continue search;
				}
			}
			for (int[] s : new int[][] {{0, 0}, {0, 8}, {-11, 10}, {11, 10}, {0, 18}, {-11, -6}, {11, -6}}) {
				int[] p = probe(c, lx(x, rot, s[0], s[1]), lz(z, rot, s[0], s[1]));
				if (p[0] != 0 || p[1] < sea || p[1] > sea + 9) {
					continue search;
				}
			}
			BlockPos origin = new BlockPos(x, sea, z);
			return Optional.of(new GenerationStub(origin, builder -> builder.addPiece(new MilitaryBasePiece(origin, rot, MilitaryBasePiece.SUB_PEN, seed))));
		}
		return Optional.empty();
	}

	@Override
	public StructureType<?> type() {
		return BaseRegistry.MILITARY_BASE;
	}
}
