package de.rcm.ballistic.base;

import com.mojang.serialization.MapCodec;
import java.util.Arrays;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

/**
 * A big city on open, fairly level land, about 200 x 200 blocks: a grid of streets with sidewalks and street
 * lamps, glass skyscrapers in the middle, office towers and apartment blocks round them, large villas with
 * gardens and pools, parks and car parks towards the edge. See {@link CityPiece}.
 */
public class CityStructure extends Structure {
	public static final MapCodec<CityStructure> CODEC = simpleCodec(CityStructure::new);

	public CityStructure(StructureSettings settings) {
		super(settings);
	}

	@Override
	protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
		int x = context.chunkPos().getMiddleBlockX();
		int z = context.chunkPos().getMiddleBlockZ();
		// a 7 x 7 grid of samples over the whole city: dry, and not too rough
		int[] heights = new int[49];
		int n = 0;
		int wet = 0;
		for (int i = -3; i <= 3; i++) {
			for (int j = -3; j <= 3; j++) {
				int sx = x + i * 32;
				int sz = z + j * 32;
				int top = context.chunkGenerator().getFirstOccupiedHeight(sx, sz, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
				int floor = context.chunkGenerator().getFirstOccupiedHeight(sx, sz, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
				if (top - floor > 3) {
					return Optional.empty();
				}
				if (top != floor) {
					wet++;
				}
				heights[n++] = floor;
			}
		}
		Arrays.sort(heights);
		if (wet > 3 || heights[44] - heights[4] > 14 || heights[48] - heights[0] > 26) {
			return Optional.empty();
		}
		int y = heights[24];
		if (y < context.chunkGenerator().getSeaLevel()) {
			return Optional.empty();
		}
		long seed = context.random().nextLong();
		BlockPos origin = new BlockPos(x, y, z);
		return Optional.of(new GenerationStub(origin, builder -> builder.addPiece(new CityPiece(origin, seed))));
	}

	@Override
	public StructureType<?> type() {
		return BaseRegistry.CITY;
	}
}
