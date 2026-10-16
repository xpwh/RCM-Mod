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
	public static final MapCodec<MilitaryBaseStructure> CODEC = simpleCodec(MilitaryBaseStructure::new);

	public MilitaryBaseStructure(StructureSettings settings) {
		super(settings);
	}

	@Override
	protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
		int x = context.chunkPos().getMiddleBlockX();
		int z = context.chunkPos().getMiddleBlockZ();
		int variant = context.random().nextInt(MilitaryBasePiece.VARIANTS);
		Rotation rotation = Rotation.getRandom(context.random());
		long seed = context.random().nextLong();
		// level, dry ground under the whole compound
		int[] heights = new int[25];
		int n = 0;
		for (int i = -2; i <= 2; i++) {
			for (int j = -2; j <= 2; j++) {
				int sx = x + i * 18;
				int sz = z + j * 18;
				int top = context.chunkGenerator().getFirstOccupiedHeight(sx, sz, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
				int floor = context.chunkGenerator().getFirstOccupiedHeight(sx, sz, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
				if (top != floor) {
					return Optional.empty(); // water
				}
				heights[n++] = top;
			}
		}
		Arrays.sort(heights);
		if (heights[22] - heights[2] > 10 || heights[24] - heights[0] > 18) {
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
