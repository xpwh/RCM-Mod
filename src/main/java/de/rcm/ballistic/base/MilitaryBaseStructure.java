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
			default -> context.random().nextInt(MilitaryBasePiece.VARIANTS);
		};
		Rotation rotation = Rotation.getRandom(context.random());
		long seed = context.random().nextLong();
		boolean big = variant < MilitaryBasePiece.CHECKPOINT;
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

	@Override
	public StructureType<?> type() {
		return BaseRegistry.MILITARY_BASE;
	}
}
