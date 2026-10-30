package de.rcm.ballistic.base;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * The city ({@link CityStructure}), built a chunk at a time: everything is worked out from the city's seed and
 * the spot, so each chunk builds its share without knowing the others.
 * <p>
 * Streets run every 32 blocks both ways (seven wide, a dashed centre line, zebra crossings, sidewalks with
 * street lamps, a car here and there); between them are 36 lots of 21 x 21. The four in the middle carry the
 * tallest glass towers (up to 140 blocks, stepped back twice, antennas with warning lights); round them come
 * more skyscrapers, office towers and the odd plaza; the outer ring has large villas with gardens and pools,
 * apartment blocks with balconies, parks with fountains, plazas with a statue, petrol stations, building sites
 * with a tower crane and car parks. Every tower has lit floors and a ladder shaft up its core to the roof, and
 * every building is furnished: lobbies, offices, shops (cafes, grocers, boutiques), flats and the villas' rooms.
 * The streets have traffic lights, trees, benches, bins, hydrants, bus shelters, cars and buses.
 */
public class CityPiece extends StructurePiece {
	/** The outer streets' centre lines, from the middle. */
	private static final int R = 96;
	/** Half the side of the levelled ground. */
	private static final int EDGE = R + 7;
	private static final int CELL = 32;
	private static final int TALL = 172;

	private final int ox;
	private final int oy;
	private final int oz;
	private final long seed;

	public CityPiece(BlockPos origin, long seed) {
		super(BaseRegistry.CITY_PIECE, 0, new BoundingBox(origin.getX() - EDGE, origin.getY(), origin.getZ() - EDGE, origin.getX() + EDGE,
			origin.getY() + TALL, origin.getZ() + EDGE));
		this.ox = origin.getX();
		this.oy = origin.getY();
		this.oz = origin.getZ();
		this.seed = seed;
	}

	public CityPiece(CompoundTag tag) {
		super(BaseRegistry.CITY_PIECE, tag);
		this.ox = tag.getIntOr("OX", 0);
		this.oy = tag.getIntOr("OY", 64);
		this.oz = tag.getIntOr("OZ", 0);
		this.seed = tag.getLongOr("Seed", 0L);
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		tag.putInt("OX", this.ox);
		tag.putInt("OY", this.oy);
		tag.putInt("OZ", this.oz);
		tag.putLong("Seed", this.seed);
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random, BoundingBox chunkBox,
		ChunkPos chunkPos, BlockPos pivot) {
		new Build(level, chunkBox).run();
	}

	private static final BlockState[] FRAMES = {Blocks.WHITE_CONCRETE.defaultBlockState(), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(),
		Blocks.GRAY_CONCRETE.defaultBlockState(), Blocks.BLACK_CONCRETE.defaultBlockState(), Blocks.QUARTZ_BLOCK.defaultBlockState(),
		Blocks.SMOOTH_STONE.defaultBlockState()};
	private static final BlockState[] GLASS = {Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), Blocks.CYAN_STAINED_GLASS.defaultBlockState(),
		Blocks.GRAY_STAINED_GLASS.defaultBlockState(), Blocks.BLACK_STAINED_GLASS.defaultBlockState(), Blocks.BLUE_STAINED_GLASS.defaultBlockState(),
		Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState()};
	private static final BlockState[] MASONRY = {Blocks.BRICKS.defaultBlockState(), Blocks.WHITE_TERRACOTTA.defaultBlockState(),
		Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState(), Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.POLISHED_ANDESITE.defaultBlockState(),
		Blocks.STONE_BRICKS.defaultBlockState(), Blocks.ORANGE_TERRACOTTA.defaultBlockState(), Blocks.YELLOW_TERRACOTTA.defaultBlockState(),
		Blocks.CYAN_TERRACOTTA.defaultBlockState()};
	private static final BlockState[] PAINT = {Blocks.RED_CONCRETE.defaultBlockState(), Blocks.WHITE_CONCRETE.defaultBlockState(),
		Blocks.BLACK_CONCRETE.defaultBlockState(), Blocks.BLUE_CONCRETE.defaultBlockState(), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(),
		Blocks.YELLOW_CONCRETE.defaultBlockState(), Blocks.GREEN_CONCRETE.defaultBlockState()};

	private static final BlockState[] RUGS = {Blocks.RED_CARPET.defaultBlockState(), Blocks.LIGHT_GRAY_CARPET.defaultBlockState(),
		Blocks.BLUE_CARPET.defaultBlockState(), Blocks.BROWN_CARPET.defaultBlockState(), Blocks.CYAN_CARPET.defaultBlockState(),
		Blocks.WHITE_CARPET.defaultBlockState()};
	private static final BlockState[] BEDS = {Blocks.WHITE_BED.defaultBlockState(), Blocks.LIGHT_GRAY_BED.defaultBlockState(),
		Blocks.BLUE_BED.defaultBlockState(), Blocks.RED_BED.defaultBlockState(), Blocks.GRAY_BED.defaultBlockState()};
	/** The shops' fascia boards and their awnings. */
	private static final BlockState[] SIGNS = {Blocks.RED_CONCRETE.defaultBlockState(), Blocks.BLUE_CONCRETE.defaultBlockState(),
		Blocks.GREEN_CONCRETE.defaultBlockState(), Blocks.BLACK_CONCRETE.defaultBlockState(), Blocks.ORANGE_CONCRETE.defaultBlockState(),
		Blocks.PURPLE_CONCRETE.defaultBlockState()};
	private static final BlockState[] AWNINGS = {Blocks.RED_WOOL.defaultBlockState(), Blocks.GREEN_WOOL.defaultBlockState(),
		Blocks.BLUE_WOOL.defaultBlockState(), Blocks.YELLOW_WOOL.defaultBlockState(), Blocks.ORANGE_WOOL.defaultBlockState()};

	private final class Build {
		private final WorldGenLevel level;
		private final BoundingBox clip;
		private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		/** The chunk's columns in the city's own coordinates. */
		private final int cx0;
		private final int cx1;
		private final int cz0;
		private final int cz1;

		Build(WorldGenLevel level, BoundingBox clip) {
			this.level = level;
			this.clip = clip;
			this.cx0 = clip.minX() - CityPiece.this.ox;
			this.cx1 = clip.maxX() - CityPiece.this.ox;
			this.cz0 = clip.minZ() - CityPiece.this.oz;
			this.cz1 = clip.maxZ() - CityPiece.this.oz;
		}

		float hash(int x, int z, int salt) {
			long h = CityPiece.this.seed ^ x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
			h ^= h >>> 33;
			h *= 0xff51afd7ed558ccdL;
			h ^= h >>> 33;
			h *= 0xc4ceb9fe1a85ec53L;
			h ^= h >>> 33;
			return (h >>> 40) / (float) (1 << 24);
		}

		<T> T pick(T[] from, int x, int z, int salt) {
			return from[Math.min(from.length - 1, (int) (this.hash(x, z, salt) * from.length))];
		}

		void set(int x, int y, int z, BlockState s) {
			int wx = CityPiece.this.ox + x;
			int wy = CityPiece.this.oy + y;
			int wz = CityPiece.this.oz + z;
			if (this.clip.isInside(wx, wy, wz)) {
				this.level.setBlock(this.m.set(wx, wy, wz), s, Block.UPDATE_CLIENTS);
			}
		}

		void set(int x, int y, int z, Block b) {
			this.set(x, y, z, b.defaultBlockState());
		}

		void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
			for (int x = Math.max(x1, this.cx0); x <= Math.min(x2, this.cx1); x++) {
				for (int z = Math.max(z1, this.cz0); z <= Math.min(z2, this.cz1); z++) {
					for (int y = y1; y <= y2; y++) {
						this.set(x, y, z, s);
					}
				}
			}
		}

		void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block b) {
			this.fill(x1, y1, z1, x2, y2, z2, b.defaultBlockState());
		}

		boolean overlaps(int x1, int z1, int x2, int z2) {
			return x2 >= this.cx0 && x1 <= this.cx1 && z2 >= this.cz0 && z1 <= this.cz1;
		}

		/** Offset from the nearest street's centre line. */
		int off(int v) {
			return v - Math.round(v / (float) CELL) * CELL;
		}

		// ------------------------------------------------------------------ the plan

		void run() {
			this.terrain();
			this.streets();
			for (int k = -3; k <= 2; k++) {
				for (int j = -3; j <= 2; j++) {
					int x0 = k * CELL + 6;
					int z0 = j * CELL + 6;
					if (this.overlaps(x0, z0, x0 + 20, z0 + 20)) {
						this.lot(k, j, x0, z0, x0 + 20, z0 + 20);
					}
				}
			}
		}

		private boolean loose(BlockState s) {
			return s.isAir() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || !s.getFluidState().isEmpty();
		}

		/** Levels the ground: everything above cut away, holes below filled, streets and sidewalks laid. */
		void terrain() {
			int oy = CityPiece.this.oy;
			for (int x = Math.max(-EDGE, this.cx0); x <= Math.min(EDGE, this.cx1); x++) {
				for (int z = Math.max(-EDGE, this.cz0); z <= Math.min(EDGE, this.cz1); z++) {
					int wx = CityPiece.this.ox + x;
					int wz = CityPiece.this.oz + z;
					for (int y = oy + 1; y <= oy + 48; y++) {
						if (!this.level.getBlockState(this.m.set(wx, y, wz)).isAir()) {
							this.level.setBlock(this.m, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
						}
					}
					for (int y = oy - 1; y >= oy - 24; y--) {
						if (!this.loose(this.level.getBlockState(this.m.set(wx, y, wz)))) {
							break;
						}
						this.level.setBlock(this.m, y >= oy - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
					}
					this.level.setBlock(this.m.set(wx, oy, wz), this.ground(x, z), Block.UPDATE_CLIENTS);
				}
			}
		}

		BlockState ground(int x, int z) {
			int ax = Math.abs(x);
			int az = Math.abs(z);
			if (ax > R + 5 || az > R + 5) {
				return Blocks.GRASS_BLOCK.defaultBlockState();
			}
			int rx = this.off(x);
			int rz = this.off(z);
			boolean alongZ = Math.abs(rx) <= 3 && az <= R + 3;
			boolean alongX = Math.abs(rz) <= 3 && ax <= R + 3;
			if (alongZ || alongX) {
				boolean crossing = alongZ && alongX;
				// the dashed centre line, and zebra stripes just before each crossing
				if (!crossing && alongZ && rx == 0 && Math.floorMod(z, 6) < 3 && Math.abs(rz) > 7) {
					return ModRegistry.RUNWAY_MARKING.defaultBlockState();
				}
				if (!crossing && alongX && rz == 0 && Math.floorMod(x, 6) < 3 && Math.abs(rx) > 7) {
					return ModRegistry.RUNWAY_MARKING.defaultBlockState();
				}
				if (alongZ && !alongX && (Math.abs(rz) == 6 || Math.abs(rz) == 7) && Math.floorMod(x, 2) == 0) {
					return ModRegistry.RUNWAY_MARKING.defaultBlockState();
				}
				if (alongX && !alongZ && (Math.abs(rx) == 6 || Math.abs(rx) == 7) && Math.floorMod(z, 2) == 0) {
					return ModRegistry.RUNWAY_MARKING.defaultBlockState();
				}
				return ModRegistry.RUNWAY_ASPHALT.defaultBlockState();
			}
			if (Math.abs(rx) <= 5 || Math.abs(rz) <= 5) {
				// the kerb in andesite, the pavement in smooth stone
				return Math.abs(rx) == 4 || Math.abs(rz) == 4 ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.SMOOTH_STONE.defaultBlockState();
			}
			return Blocks.GRASS_BLOCK.defaultBlockState();
		}

		/**
		 * The street furniture: traffic lights at every crossing, lamps, trees in their pits, benches, bins and
		 * hydrants along the sidewalks, bus shelters here and there, cars and buses on the roads.
		 */
		void streets() {
			for (int x = Math.max(-R - 5, this.cx0); x <= Math.min(R + 5, this.cx1); x++) {
				for (int z = Math.max(-R - 5, this.cz0); z <= Math.min(R + 5, this.cz1); z++) {
					int rx = this.off(x);
					int rz = this.off(z);
					int ax = Math.abs(rx);
					int az = Math.abs(rz);
					if (ax == 4 && az == 4) {
						this.trafficLight(x, z);
						continue;
					}
					// beside a street running in z the sidewalk is at |rx| 4..5 and the spot along it is z (the other way round likewise)
					boolean sideZ = ax >= 4 && ax <= 5 && az > 7;
					boolean sideX = az >= 4 && az <= 5 && ax > 7;
					if (!sideZ && !sideX) {
						continue;
					}
					int along = sideZ ? z : x;
					int line = sideZ ? x - rx : z - rz;
					int across = sideZ ? rx : rz;
					Direction out = sideZ ? (rx > 0 ? Direction.EAST : Direction.WEST) : (rz > 0 ? Direction.SOUTH : Direction.NORTH);
					int seg = Math.floorDiv(along, CELL);
					int inSeg = Math.floorMod(along, CELL);
					if (this.hash(line * 2 + (across > 0 ? 1 : 0), seg * 2 + (sideZ ? 1 : 0), 44) < 0.3F && inSeg >= 13 && inSeg <= 18) {
						this.busStop(x, z, Math.abs(across) == 5, inSeg == 13 || inSeg == 18, out);
						continue;
					}
					if (Math.abs(across) == 5) {
						int m12 = Math.floorMod(along, 12);
						if (m12 == 6) {
							this.fill(x, 1, z, x, 4, z, Blocks.ANDESITE_WALL.defaultBlockState());
							this.set(x, 5, z, Blocks.LANTERN.defaultBlockState());
						} else if (m12 == 0) {
							this.streetTree(x, z);
						} else if (m12 == 3) {
							this.set(x, 1, z, this.stairs(Blocks.SPRUCE_STAIRS, out));
						} else if (m12 == 9) {
							this.set(x, 1, z, this.hash(x, z, 45) < 0.5F ? Blocks.CAULDRON.defaultBlockState() : Blocks.COMPOSTER.defaultBlockState());
						}
					} else if (Math.floorMod(along, 24) == 15) {
						this.set(x, 1, z, Blocks.RED_NETHER_BRICK_WALL.defaultBlockState()); // a hydrant at the kerb
					}
				}
			}
			for (int c = -R; c <= R; c += CELL) {
				for (int k = -3; k <= 2; k++) {
					for (int dir = 0; dir < 2; dir++) {
						for (int lanePick = 0; lanePick < 2; lanePick++) {
							float h = this.hash(c, k * 7 + dir * 2 + lanePick, 41);
							if (h > 0.6F) {
								continue;
							}
							int lane = lanePick == 0 ? 1 : -2;
							BlockState paint = this.pick(PAINT, c, k * 3 + dir + lanePick * 11, 43);
							if (this.hash(c, k + lanePick * 13, 46 + dir) < 0.12F) {
								this.bus(c + lane, k * CELL + 8, dir == 0);
								continue;
							}
							int along = k * CELL + 7 + (int) (this.hash(c, k * 5 + lanePick, 42 + dir) * 13);
							if (dir == 0) {
								this.car(c + lane, along, true, paint);
							} else {
								this.car(along, c + lane, false, paint);
							}
						}
					}
				}
			}
		}

		/** A traffic light on the corner: a dark pole, the signal head with green, amber and red. */
		void trafficLight(int x, int z) {
			if (Math.abs(x) > R + 4 || Math.abs(z) > R + 4) {
				return;
			}
			this.fill(x, 1, z, x, 3, z, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
			this.set(x, 4, z, Blocks.LIME_CONCRETE.defaultBlockState());
			this.set(x, 5, z, Blocks.YELLOW_CONCRETE.defaultBlockState());
			this.set(x, 6, z, Blocks.RED_CONCRETE.defaultBlockState());
			this.set(x, 7, z, Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState());
		}

		/** A street tree in its pit: a short trunk, a round crown. */
		void streetTree(int x, int z) {
			BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
			this.set(x, 0, z, Blocks.COARSE_DIRT.defaultBlockState());
			this.fill(x, 1, z, x, 4, z, Blocks.OAK_LOG.defaultBlockState());
			for (int dy = 3; dy <= 6; dy++) {
				int r = dy == 3 || dy == 6 ? 1 : 2;
				for (int a = -r; a <= r; a++) {
					for (int b = -r; b <= r; b++) {
						if (a == 0 && b == 0 && dy <= 4 || Math.abs(a) == 2 && Math.abs(b) == 2) {
							continue;
						}
						this.set(x + a, dy, z + b, leaves);
					}
				}
			}
		}

		/** A piece of a bus shelter: glass at the back, a bench in front of it, a roof over both, a lit sign at the ends. */
		void busStop(int x, int z, boolean back, boolean end, Direction out) {
			if (back) {
				this.fill(x, 1, z, x, 2, z, end ? Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState() : Blocks.GLASS.defaultBlockState());
			} else if (!end) {
				this.set(x, 1, z, this.stairs(Blocks.DARK_OAK_STAIRS, out));
			}
			this.set(x, 3, z, end && back ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
		}

		/** A city bus, 2 wide and 9 long: a yellow body, a band of windows, a white roof. */
		void bus(int x, int z, boolean alongZ) {
			for (int a = 0; a < 2; a++) {
				for (int b = 0; b < 9; b++) {
					int px = alongZ ? x + a : z + b;
					int pz = alongZ ? z + b : x + a;
					this.set(px, 1, pz, b == 1 || b == 7 ? Blocks.BLACK_CONCRETE.defaultBlockState() : Blocks.YELLOW_CONCRETE.defaultBlockState());
					this.set(px, 2, pz, b == 0 || b == 8 ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.BLACK_STAINED_GLASS.defaultBlockState());
					this.set(px, 3, pz, Blocks.WHITE_CONCRETE.defaultBlockState());
				}
			}
		}

		/** A plaza: a paved square, a bronze statue on a plinth ringed by flagpoles, planters, benches and lamps. */
		void plaza(int k, int j, int x0, int z0, int x1, int z1) {
			int mx = (x0 + x1) / 2;
			int mz = (z0 + z1) / 2;
			BlockState bush = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
			for (int x = Math.max(x0, this.cx0); x <= Math.min(x1, this.cx1); x++) {
				for (int z = Math.max(z0, this.cz0); z <= Math.min(z1, this.cz1); z++) {
					int dx = x - mx;
					int dz = z - mz;
					boolean ring = Math.max(Math.abs(dx), Math.abs(dz)) == 6;
					this.set(x, 0, z, ring ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : (x + z) % 2 == 0 ? Blocks.SMOOTH_STONE.defaultBlockState()
						: Blocks.POLISHED_ANDESITE.defaultBlockState());
					// planters along the edge of the lot with bushes in them
					boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
					if (edge && Math.floorMod(dx + dz, 6) != 0) {
						this.set(x, 1, z, Blocks.STONE_BRICK_WALL.defaultBlockState());
						this.set(x, 2, z, bush);
					}
				}
			}
			// the plinth and the statue: a figure in weathered copper, one arm raised
			this.fill(mx - 1, 1, mz - 1, mx + 1, 1, mz + 1, Blocks.POLISHED_ANDESITE.defaultBlockState());
			this.fill(mx - 1, 2, mz - 1, mx + 1, 2, mz + 1, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
			BlockState bronze = Blocks.OXIDIZED_COPPER.defaultBlockState();
			this.set(mx, 3, mz, Blocks.OXIDIZED_CUT_COPPER.defaultBlockState());
			this.set(mx, 4, mz, bronze);
			this.set(mx, 5, mz, bronze);
			this.set(mx - 1, 5, mz, Blocks.OXIDIZED_CUT_COPPER_SLAB.defaultBlockState());
			this.set(mx + 1, 5, mz, bronze);
			this.set(mx + 1, 6, mz, bronze);
			this.set(mx + 1, 7, mz, Blocks.LIGHTNING_ROD.defaultBlockState());
			this.set(mx, 6, mz, Blocks.OXIDIZED_CHISELED_COPPER.defaultBlockState());
			// flagpoles round it, benches facing it, lamps at the corners
			Block[] flags = {Blocks.RED_BANNER, Blocks.BLUE_BANNER, Blocks.WHITE_BANNER, Blocks.YELLOW_BANNER};
			int f = 0;
			for (int s = -1; s <= 1; s += 2) {
				for (int t = -1; t <= 1; t += 2) {
					this.fill(mx + s * 4, 1, mz + t * 4, mx + s * 4, 7, mz + t * 4, Blocks.IRON_BARS.defaultBlockState());
					this.set(mx + s * 4, 8, mz + t * 4, flags[f++ % flags.length].defaultBlockState());
					this.fill(mx + s * 8, 1, mz + t * 8, mx + s * 8, 3, mz + t * 8, Blocks.ANDESITE_WALL.defaultBlockState());
					this.set(mx + s * 8, 4, mz + t * 8, Blocks.LANTERN.defaultBlockState());
				}
				this.set(mx + s * 6, 1, mz - 1, this.stairs(Blocks.SPRUCE_STAIRS, s > 0 ? Direction.EAST : Direction.WEST));
				this.set(mx + s * 6, 1, mz + 1, this.stairs(Blocks.SPRUCE_STAIRS, s > 0 ? Direction.EAST : Direction.WEST));
				this.set(mx - 1, 1, mz + s * 6, this.stairs(Blocks.SPRUCE_STAIRS, s > 0 ? Direction.SOUTH : Direction.NORTH));
				this.set(mx + 1, 1, mz + s * 6, this.stairs(Blocks.SPRUCE_STAIRS, s > 0 ? Direction.SOUTH : Direction.NORTH));
			}
		}

		/**
		 * A petrol station: a lit canopy on pillars over two islands of pumps, the shop at the back with its
		 * shelves and till, the price board on a tall pole at the corner.
		 */
		void gasStation(int k, int j, int x0, int z0, int x1, int z1) {
			this.paveLot(x0, z0, x1, z1, ModRegistry.RUNWAY_ASPHALT.defaultBlockState());
			BlockState brand = this.pick(new BlockState[] {Blocks.RED_CONCRETE.defaultBlockState(), Blocks.GREEN_CONCRETE.defaultBlockState(),
				Blocks.BLUE_CONCRETE.defaultBlockState(), Blocks.ORANGE_CONCRETE.defaultBlockState()}, k, j, 50);
			int ax0 = x0 + 2;
			int ax1 = x1 - 2;
			int az0 = z0 + 2;
			int az1 = z0 + 11;
			// the canopy
			for (int x = ax0; x <= ax1; x++) {
				for (int z = az0; z <= az1; z++) {
					boolean rim = x == ax0 || x == ax1 || z == az0 || z == az1;
					boolean light = !rim && Math.floorMod(x - ax0, 3) == 1 && Math.floorMod(z - az0, 3) == 1;
					this.set(x, 5, z, rim ? brand : light ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState());
					if (rim) {
						this.set(x, 6, z, Blocks.WHITE_CONCRETE.defaultBlockState());
					}
				}
			}
			for (int x : new int[] {ax0 + 2, ax1 - 2}) {
				for (int z : new int[] {az0 + 2, az1 - 2}) {
					this.fill(x, 1, z, x, 4, z, Blocks.QUARTZ_PILLAR.defaultBlockState());
				}
				// the island: a kerb, two pumps with their hoses
				this.fill(x - 1, 1, az0 + 4, x + 1, 1, az1 - 4, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
				for (int z = az0 + 4; z <= az1 - 4; z += az1 - az0 - 8) {
					this.set(x, 1, z, Blocks.GRAY_CONCRETE.defaultBlockState());
					this.set(x, 2, z, brand);
					this.set(x, 3, z, Blocks.WHITE_CONCRETE.defaultBlockState());
					this.set(x + 1, 2, z, Blocks.IRON_CHAIN.defaultBlockState());
				}
			}
			this.car(ax0 + 4, az0 + 3, true, this.pick(PAINT, k, j, 51));
			// the shop
			int sz0 = z1 - 6;
			for (int x = Math.max(x0 + 1, this.cx0); x <= Math.min(x1 - 1, this.cx1); x++) {
				for (int z = Math.max(sz0, this.cz0); z <= Math.min(z1 - 1, this.cz1); z++) {
					boolean edge = x == x0 + 1 || x == x1 - 1 || z == sz0 || z == z1 - 1;
					this.set(x, 0, z, Blocks.POLISHED_DIORITE.defaultBlockState());
					for (int y = 1; y <= 5; y++) {
						BlockState b;
						if (y == 5) {
							b = edge ? brand : Blocks.WHITE_CONCRETE.defaultBlockState();
						} else if (y == 4) {
							b = edge ? Blocks.WHITE_CONCRETE.defaultBlockState() : (x + z) % 3 == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.AIR.defaultBlockState();
						} else if (edge) {
							boolean front = z == sz0;
							boolean door = front && Math.abs(x - (x0 + x1) / 2) <= 1 && y <= 2;
							b = door ? Blocks.AIR.defaultBlockState() : front ? Blocks.GLASS.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState();
						} else if (z == z1 - 2 && y <= 2) {
							b = y == 1 ? Blocks.BARREL.defaultBlockState() : Blocks.BOOKSHELF.defaultBlockState(); // shelves along the back
						} else if (z == sz0 + 2 && x == x0 + 3 && y == 1) {
							b = Blocks.SMOOTH_QUARTZ.defaultBlockState(); // the till
						} else if (Math.floorMod(x, 4) == 0 && z == sz0 + 3 && y <= 2) {
							b = Blocks.BARREL.defaultBlockState();
						} else {
							b = Blocks.AIR.defaultBlockState();
						}
						this.set(x, y, z, b);
					}
				}
			}
			// the price board
			int px = x1;
			int pz = z0;
			this.fill(px, 1, pz, px, 9, pz, Blocks.ANDESITE_WALL.defaultBlockState());
			this.fill(px, 10, pz, px, 13, pz, brand);
			this.set(px, 11, pz, Blocks.SEA_LANTERN.defaultBlockState());
			this.set(px, 12, pz, Blocks.WHITE_CONCRETE.defaultBlockState());
		}

		/**
		 * A building site: a hoarding round it, the concrete frame of a tower going up (the top floor half done,
		 * rebar sticking out), scaffolding up one face, a tower crane with its jib over the street and a load on
		 * the hook, site huts stacked in a corner, piles of materials.
		 */
		void construction(int k, int j, int x0, int z0, int x1, int z1) {
			this.paveLot(x0, z0, x1, z1, Blocks.COARSE_DIRT.defaultBlockState());
			int bx0 = x0 + 4;
			int bz0 = z0 + 3;
			int bx1 = x1 - 5;
			int bz1 = z1 - 6;
			int floors = 3 + (int) (this.hash(k, j, 52) * 6);
			int top = floors * 4;
			int midX = (bx0 + bx1) / 2;
			for (int x = Math.max(x0, this.cx0); x <= Math.min(x1, this.cx1); x++) {
				for (int z = Math.max(z0, this.cz0); z <= Math.min(z1, this.cz1); z++) {
					boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
					boolean gate = z == z1 && Math.abs(x - (x0 + x1) / 2) <= 2;
					if (edge && !gate) {
						this.fill(x, 1, z, x, 2, z, Math.floorMod(x + z, 6) == 0 ? Blocks.SPRUCE_LOG.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState());
					}
					if (Math.floorMod(x * 7 + z * 3, 5) == 0 && !edge) {
						this.set(x, 0, z, Blocks.GRAVEL.defaultBlockState());
					}
					if (x < bx0 || x > bx1 || z < bz0 || z > bz1) {
						continue;
					}
					boolean column = Math.floorMod(x - bx0, 4) == 0 && Math.floorMod(z - bz0, 4) == 0;
					this.set(x, 0, z, Blocks.SMOOTH_STONE.defaultBlockState());
					for (int y = 1; y <= top + 2; y++) {
						if (column && y <= top) {
							this.set(x, y, z, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState());
						} else if (column && y <= top + 2) {
							this.set(x, y, z, Blocks.IRON_BARS.defaultBlockState()); // rebar
						} else if (y % 4 == 0 && y < top) {
							this.set(x, y, z, Blocks.SMOOTH_STONE.defaultBlockState());
						} else if (y == top && x <= midX) {
							this.set(x, y, z, Blocks.SMOOTH_STONE.defaultBlockState());
						}
					}
					// scaffolding up the front, a ladder in it
					if (z == bz1 && Math.floorMod(x - bx0, 4) != 0) {
						for (int y = 1; y <= top; y++) {
							this.set(x, y, z + 1, Blocks.SCAFFOLDING.defaultBlockState());
						}
					}
				}
			}
			// the crane: a lattice tower, the cab, the jib out over the street, the counter-jib with its weights, the hook
			int tx = x1 - 2;
			int tz = z0 + 2;
			int ht = top + 14;
			for (int y = 1; y <= ht; y++) {
				BlockState b = y % 4 == 0 ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState();
				this.set(tx, y, tz, b);
				this.set(tx + 1, y, tz, b);
				this.set(tx, y, tz + 1, b);
				this.set(tx + 1, y, tz + 1, b);
			}
			this.fill(tx, ht + 1, tz, tx + 1, ht + 2, tz + 1, Blocks.YELLOW_CONCRETE.defaultBlockState());
			this.set(tx, ht + 2, tz + 1, Blocks.GLASS.defaultBlockState());
			this.set(tx + 1, ht + 2, tz + 1, Blocks.GLASS.defaultBlockState());
			int len = 24;
			for (int d = 1; d <= len; d++) {
				this.set(tx - d, ht + 1, tz, d % 3 == 0 ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState());
				this.set(tx - d, ht + 1, tz + 1, d % 3 == 0 ? Blocks.YELLOW_CONCRETE.defaultBlockState() : Blocks.IRON_BARS.defaultBlockState());
			}
			for (int d = 2; d <= 7; d++) {
				this.set(tx + d, ht + 1, tz, Blocks.YELLOW_CONCRETE.defaultBlockState());
				this.set(tx + d, ht + 1, tz + 1, Blocks.YELLOW_CONCRETE.defaultBlockState());
			}
			this.fill(tx + 6, ht, tz, tx + 7, ht, tz + 1, Blocks.GRAY_CONCRETE.defaultBlockState());
			this.fill(tx + 6, ht - 1, tz, tx + 7, ht - 1, tz + 1, Blocks.GRAY_CONCRETE.defaultBlockState());
			this.fill(tx, ht + 3, tz, tx, ht + 6, tz, Blocks.IRON_BARS.defaultBlockState());
			this.set(tx, ht + 7, tz, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true));
			int hx = tx - 14;
			int hookY = top + 5;
			this.fill(hx, hookY + 1, tz, hx, ht, tz, Blocks.IRON_CHAIN.defaultBlockState());
			this.set(hx, hookY, tz, Blocks.ANVIL.defaultBlockState());
			this.fill(hx - 1, hookY - 2, tz - 1, hx + 1, hookY - 1, tz + 1, Blocks.BRICKS.defaultBlockState());
			this.fill(hx - 1, hookY - 3, tz - 1, hx + 1, hookY - 3, tz + 1, Blocks.SPRUCE_SLAB.defaultBlockState());
			// site huts, stacked, and the materials
			this.fill(x0 + 1, 1, z1 - 4, x0 + 6, 3, z1 - 2, Blocks.ORANGE_CONCRETE.defaultBlockState());
			this.fill(x0 + 2, 2, z1 - 4, x0 + 5, 2, z1 - 4, Blocks.GLASS.defaultBlockState());
			this.fill(x0 + 1, 4, z1 - 4, x0 + 6, 6, z1 - 2, Blocks.BLUE_CONCRETE.defaultBlockState());
			this.fill(x0 + 2, 5, z1 - 4, x0 + 5, 5, z1 - 4, Blocks.GLASS.defaultBlockState());
			this.fill(x1 - 4, 1, z1 - 3, x1 - 3, 2, z1 - 2, Blocks.BRICKS.defaultBlockState());
			this.fill(x1 - 2, 1, z1 - 3, x1 - 1, 1, z1 - 2, Blocks.SPRUCE_PLANKS.defaultBlockState());
			this.set(x1 - 6, 1, z1 - 2, Blocks.SAND.defaultBlockState());
			this.set(x1 - 7, 1, z1 - 2, Blocks.SAND.defaultBlockState());
			this.set(x1 - 6, 2, z1 - 2, Blocks.SAND.defaultBlockState());
		}

		/** A car, 2 wide and 4 long: the body in its paint, the glasshouse in the middle, dark wheels at the ends. */
		void car(int x, int z, boolean alongZ, BlockState paint) {
			for (int a = 0; a < 2; a++) {
				for (int b = 0; b < 4; b++) {
					int px = alongZ ? x + a : x + b;
					int pz = alongZ ? z + b : z + a;
					this.set(px, 1, pz, b == 0 || b == 3 ? Blocks.BLACK_CONCRETE.defaultBlockState() : paint);
					this.set(px, 2, pz, b == 1 || b == 2 ? Blocks.BLACK_STAINED_GLASS.defaultBlockState() : paint);
				}
			}
		}

		// ------------------------------------------------------------------ the lots

		void lot(int k, int j, int x0, int z0, int x1, int z1) {
			float ring = Math.max(Math.abs(k + 0.5F), Math.abs(j + 0.5F));
			float h = this.hash(k, j, 1);
			if (ring < 1.0F) {
				this.tower(k, j, x0, z0, x1, z1, 96 + (int) (this.hash(k, j, 2) * 44));
			} else if (ring < 2.0F) {
				if (h < 0.5F) {
					this.tower(k, j, x0, z0, x1, z1, 52 + (int) (this.hash(k, j, 2) * 40));
				} else if (h < 0.78F) {
					this.block(k, j, x0, z0, x1, z1, 24 + (int) (this.hash(k, j, 2) * 22), false);
				} else if (h < 0.9F) {
					this.plaza(k, j, x0, z0, x1, z1);
				} else {
					this.construction(k, j, x0, z0, x1, z1);
				}
			} else if (h < 0.38F) {
				this.villa(k, j, x0, z0, x1, z1);
			} else if (h < 0.6F) {
				this.block(k, j, x0, z0, x1, z1, 14 + (int) (this.hash(k, j, 2) * 16), true);
			} else if (h < 0.72F) {
				this.park(k, j, x0, z0, x1, z1);
			} else if (h < 0.81F) {
				this.parking(k, j, x0, z0, x1, z1);
			} else if (h < 0.92F) {
				this.gasStation(k, j, x0, z0, x1, z1);
			} else {
				this.construction(k, j, x0, z0, x1, z1);
			}
		}

		/** The lot's floor: the same block all over. */
		void paveLot(int x0, int z0, int x1, int z1, BlockState s) {
			this.fill(x0, 0, z0, x1, 0, z1, s);
		}

		/**
		 * A floor plan's wall, floors, lights and the ladder shaft, column by column for one level of a tower:
		 * walls on the rectangle's edge (corners and mullions in {@code frame}, the rest {@code glass}, a
		 * spandrel at every floor), a slab every four blocks inside with lights let into it, the core in the
		 * middle with its ladder and a way in on every floor.
		 */
		void storey(int x, int y, int z, int bx0, int bz0, int bx1, int bz1, int cx, int cz, BlockState frame, BlockState glass, BlockState slab,
			int mull, boolean windowBands, int use) {
			boolean edgeX = x == bx0 || x == bx1;
			boolean edgeZ = z == bz0 || z == bz1;
			if (edgeX || edgeZ) {
				int along = edgeX ? z - bz0 : x - bx0;
				int span = edgeX ? bz1 - bz0 : bx1 - bx0;
				BlockState s;
				if (edgeX && edgeZ || y % 4 == 0 || along % mull == 0) {
					s = frame;
				} else if (windowBands && y % 4 == 1) {
					s = frame;
				} else if (y <= 3 && Math.abs(along - span / 2) <= 1) {
					s = Blocks.AIR.defaultBlockState(); // the way in, in the middle of every face
				} else {
					s = glass;
				}
				this.set(x, y, z, s);
				return;
			}
			if (Math.abs(x - cx) <= 1 && Math.abs(z - cz) <= 1) {
				if (x == cx && z == cz) {
					this.set(x, y, z, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
				} else if (x == cx && z == cz + 1 && y % 4 != 0 && y % 4 != 3) {
					this.set(x, y, z, Blocks.AIR.defaultBlockState());
				} else {
					this.set(x, y, z, frame);
				}
				return;
			}
			if (y % 4 == 0) {
				boolean light = Math.floorMod(x - bx0, 4) == 2 && Math.floorMod(z - bz0, 4) == 2;
				this.set(x, y, z, light ? Blocks.SEA_LANTERN.defaultBlockState() : slab);
			} else {
				BlockState f = Math.abs(x - cx) <= 2 && Math.abs(z - cz) <= 3 ? null : this.furnish(x, y, z, bx0, bz0, bx1, bz1, cx, cz, use);
				this.set(x, y, z, f != null ? f : Blocks.AIR.defaultBlockState());
			}
		}

		static final int LOBBY = 0;
		static final int OFFICES = 1;
		static final int FLATS = 2;
		static final int HOME = 3;

		BlockState stairs(Block b, Direction d) {
			return b.defaultBlockState().setValue(StairBlock.FACING, d);
		}

		BlockState topSlab(Block b) {
			return b.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
		}

		/**
		 * What stands in a room (null: nothing), by the building's use: a tower's lobby (marble pillars, the
		 * reception, plants) and offices above (desks with screens and chairs, plants, cabinets); shops on the
		 * ground floor of the blocks (a cafe, a grocer's or a boutique); flats and the villas' rooms (beds, a
		 * kitchen, a table with chairs on a rug, a sofa, plants, bookshelves).
		 */
		BlockState furnish(int x, int y, int z, int bx0, int bz0, int bx1, int bz1, int cx, int cz, int use) {
			int lvl = y % 4;
			if (y <= 3 && use != HOME) {
				return use == LOBBY ? this.lobby(x, lvl, z, bx0, bz0, bx1, bz1, cx, cz) : this.shop(x, lvl, z, bx0, bz0, bx1, bz1);
			}
			if (use == LOBBY || use == OFFICES) {
				int mx = Math.floorMod(x - bx0 - 1, 5);
				int mz = Math.floorMod(z - bz0 - 1, 5);
				if (lvl == 1) {
					if ((mx == 1 || mx == 2) && mz == 1) {
						return this.topSlab(Blocks.DARK_OAK_SLAB);
					}
					if ((mx == 1 || mx == 2) && mz == 2) {
						return this.stairs(Blocks.POLISHED_BLACKSTONE_STAIRS, Direction.SOUTH);
					}
					if (mx == 4 && mz == 4 && this.hash(x, z, y) < 0.4F) {
						return Blocks.POTTED_FERN.defaultBlockState();
					}
					if (mx == 4 && mz == 0 && this.hash(x, z, y + 1) < 0.15F) {
						return Blocks.BARREL.defaultBlockState(); // a filing cabinet
					}
				} else if (lvl == 2 && mx == 1 && mz == 1) {
					return Blocks.BLACK_STAINED_GLASS_PANE.defaultBlockState(); // the screen
				}
				return null;
			}
			int mx = Math.floorMod(x - bx0 - 1, 7);
			int mz = Math.floorMod(z - bz0 - 1, 7);
			if (lvl == 1) {
				if (mx == 1 && mz == 1 && z + 1 < bz1) {
					return this.bed(x, z, BedPart.HEAD);
				}
				if (mx == 1 && mz == 2) {
					return this.bed(x, z - 1, BedPart.FOOT);
				}
				if (mz == 1 && mx == 3) {
					return Blocks.CRAFTING_TABLE.defaultBlockState();
				}
				if (mz == 1 && mx == 4) {
					return Blocks.SMOKER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
				}
				if (mz == 1 && mx == 5) {
					return Blocks.CAULDRON.defaultBlockState(); // the sink
				}
				if (mx == 3 && mz == 4) {
					return Blocks.OAK_FENCE.defaultBlockState();
				}
				if (mx == 2 && mz == 4) {
					return this.stairs(Blocks.OAK_STAIRS, Direction.WEST);
				}
				if (mx == 4 && mz == 4) {
					return this.stairs(Blocks.OAK_STAIRS, Direction.EAST);
				}
				if ((mx == 3 || mx == 4) && mz == 6) {
					return this.stairs(this.hash(bx0, bz0, 30) < 0.5F ? Blocks.DARK_OAK_STAIRS : Blocks.SPRUCE_STAIRS, Direction.SOUTH); // the sofa
				}
				if (mx == 6 && mz == 6) {
					return Blocks.POTTED_FLOWERING_AZALEA.defaultBlockState();
				}
				if (use == HOME && mx == 0 && (mz == 5 || mz == 6)) {
					return Blocks.BOOKSHELF.defaultBlockState();
				}
				if (mx >= 2 && mx <= 5 && mz >= 3 && mz <= 5) {
					return this.pick(RUGS, bx0 + Math.floorDiv(x - bx0 - 1, 7), bz0 + Math.floorDiv(z - bz0 - 1, 7) + y, 31); // the rug
				}
			} else if (lvl == 2) {
				if (mx == 3 && mz == 4) {
					return Blocks.OAK_PRESSURE_PLATE.defaultBlockState();
				}
				if (mz == 1 && mx == 3 && this.hash(x, z, y) < 0.5F) {
					return Blocks.FLOWER_POT.defaultBlockState();
				}
				if (use == HOME && mx == 0 && (mz == 5 || mz == 6)) {
					return Blocks.BOOKSHELF.defaultBlockState();
				}
			}
			return null;
		}

		BlockState bed(int x, int zHead, BedPart part) {
			Block b = this.pick(BEDS, x, zHead, 32).getBlock();
			return b.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, part);
		}

		/** A tower's lobby: marble pillars, the reception desk in front of the core, plants in the corners, benches. */
		BlockState lobby(int x, int lvl, int z, int bx0, int bz0, int bx1, int bz1, int cx, int cz) {
			if (Math.floorMod(x - bx0, 6) == 3 && Math.floorMod(z - bz0, 6) == 3) {
				return Blocks.QUARTZ_PILLAR.defaultBlockState();
			}
			if (lvl == 1 && z == cz + 5 && Math.abs(x - cx) <= 3 && x != cx) {
				return Blocks.SMOOTH_QUARTZ.defaultBlockState();
			}
			if (lvl == 1 && (x == bx0 + 1 || x == bx1 - 1) && (z == bz0 + 1 || z == bz1 - 1)) {
				return Blocks.POTTED_BAMBOO.defaultBlockState();
			}
			if (lvl == 1 && (z == bz0 + 1 || z == bz1 - 1) && Math.floorMod(x - bx0, 6) == 1) {
				return this.stairs(Blocks.QUARTZ_STAIRS, z == bz0 + 1 ? Direction.NORTH : Direction.SOUTH);
			}
			return null;
		}

		/** A shop on a block's ground floor: a cafe, a grocer's or a boutique, by the block. */
		BlockState shop(int x, int lvl, int z, int bx0, int bz0, int bx1, int bz1) {
			int kind = (int) (this.hash(bx0, bz0, 33) * 3);
			if (lvl == 3) {
				return null;
			}
			switch (kind) {
				case 0 -> {
					// the cafe: the counter along the back with the coffee machines and cakes, tables with chairs
					if (z == bz0 + 1) {
						return lvl == 1 ? Blocks.SMOOTH_QUARTZ.defaultBlockState()
							: Math.floorMod(x, 4) == 0 ? Blocks.BREWING_STAND.defaultBlockState() : Math.floorMod(x, 4) == 2 ? Blocks.CAKE.defaultBlockState() : null;
					}
					int mx = Math.floorMod(x - bx0 - 1, 4);
					int mz = Math.floorMod(z - bz0 - 3, 4);
					if (mx == 1 && mz == 1) {
						return lvl == 1 ? Blocks.SPRUCE_FENCE.defaultBlockState() : Blocks.SPRUCE_PRESSURE_PLATE.defaultBlockState();
					}
					if (lvl == 1 && mz == 1 && (mx == 0 || mx == 2)) {
						return this.stairs(Blocks.SPRUCE_STAIRS, mx == 0 ? Direction.WEST : Direction.EAST);
					}
					return null;
				}
				case 1 -> {
					// the grocer's: shelves in rows, produce on them, the tills at the front
					if (Math.floorMod(x - bx0, 3) == 0 && z > bz0 + 1 && z < bz1 - 3) {
						return lvl == 1 ? Blocks.BARREL.defaultBlockState()
							: this.pick(new BlockState[] {Blocks.MELON.defaultBlockState(), Blocks.PUMPKIN.defaultBlockState(),
								Blocks.HAY_BLOCK.defaultBlockState(), Blocks.BOOKSHELF.defaultBlockState()}, x, z, 34);
					}
					if (z == bz1 - 2 && Math.floorMod(x - bx0, 4) == 2 && lvl == 1) {
						return Blocks.SMOOTH_QUARTZ.defaultBlockState();
					}
					return null;
				}
				default -> {
					// the boutique: wardrobes along the walls, glass counters in the middle
					if (x == bx0 + 1 || x == bx1 - 1) {
						return Blocks.BOOKSHELF.defaultBlockState();
					}
					if (lvl == 1 && Math.floorMod(z - bz0, 4) == 2 && Math.floorMod(x - bx0, 5) == 2) {
						return Blocks.GLASS.defaultBlockState();
					}
					return null;
				}
			}
		}

		/** A glass skyscraper: stepped back twice, a parapet round each roof, an antenna with a warning light, maybe a helipad. */
		void tower(int k, int j, int x0, int z0, int x1, int z1, int height) {
			int inset = 1 + (int) (this.hash(k, j, 3) * 3);
			int bx0 = x0 + inset;
			int bz0 = z0 + inset;
			int bx1 = x1 - inset;
			int bz1 = z1 - inset;
			int cx = (bx0 + bx1) / 2;
			int cz = (bz0 + bz1) / 2;
			int top = height / 4 * 4;
			int h1 = Math.round(top * (0.45F + 0.2F * this.hash(k, j, 4)) / 4.0F) * 4;
			int h2 = Math.round(top * (0.72F + 0.12F * this.hash(k, j, 5)) / 4.0F) * 4;
			int step = bx1 - bx0 >= 16 ? 2 : 1;
			BlockState frame = this.pick(FRAMES, k, j, 6);
			BlockState glass = this.pick(GLASS, k, j, 7);
			int mull = this.hash(k, j, 8) < 0.5F ? 3 : 4;
			boolean bands = this.hash(k, j, 9) < 0.3F;
			boolean crown = top >= 80 || this.hash(k, j, 22) < 0.35F;
			this.paveLot(x0, z0, x1, z1, Blocks.POLISHED_ANDESITE.defaultBlockState());
			for (int x = Math.max(x0, this.cx0); x <= Math.min(x1, this.cx1); x++) {
				for (int z = Math.max(z0, this.cz0); z <= Math.min(z1, this.cz1); z++) {
					// a planter round the foot of the tower, the plaza paved in a checker
					if (x < bx0 || x > bx1 || z < bz0 || z > bz1) {
						boolean ring = x == bx0 - 1 || x == bx1 + 1 || z == bz0 - 1 || z == bz1 + 1;
						if ((x + z) % 2 == 0) {
							this.set(x, 0, z, Blocks.SMOOTH_STONE.defaultBlockState());
						}
						if (ring && Math.floorMod(x + z, 5) == 0 && inset > 1) {
							this.set(x, 1, z, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
						}
						continue;
					}
					this.set(x, 0, z, Blocks.POLISHED_DIORITE.defaultBlockState());
					for (int y = 1; y <= top + 1; y++) {
						int s = y <= h1 ? 0 : y <= h2 ? step : 2 * step;
						int lower = y - 1 <= h1 ? 0 : y - 1 <= h2 ? step : 2 * step;
						boolean in = x >= bx0 + s && x <= bx1 - s && z >= bz0 + s && z <= bz1 - s;
						if (in && y <= top) {
							if (y == top) {
								boolean hatch = x == cx && z == cz;
								this.set(x, y, z, hatch ? Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH) : frame);
							} else {
								this.storey(x, y, z, bx0 + s, bz0 + s, bx1 - s, bz1 - s, cx, cz, frame, glass, Blocks.SMOOTH_STONE.defaultBlockState(), mull, bands, LOBBY);
							}
						} else if (x >= bx0 + lower && x <= bx1 - lower && z >= bz0 + lower && z <= bz1 - lower
							&& (x == bx0 + lower || x == bx1 - lower || z == bz0 + lower || z == bz1 - lower) && (lower != s || y == top + 1)) {
							// the parapet round a roof; the tall towers' crowns lit
							this.set(x, y, z, y == top + 1 && crown && Math.floorMod(x + z, 3) == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : frame);
						}
					}
				}
			}
			// on the roof: a helipad on the big ones, the antenna with its warning light in a corner, plant boxes
			int s = 2 * step;
			int rx0 = bx0 + s;
			int rz0 = bz0 + s;
			int rx1 = bx1 - s;
			int rz1 = bz1 - s;
			if (rx1 - rx0 >= 10 && this.hash(k, j, 10) < 0.5F) {
				int hx = cx + 3;
				int hz = cz;
				for (int d = -1; d <= 1; d++) {
					this.set(hx - 1, top, hz + d, ModRegistry.RUNWAY_MARKING.defaultBlockState());
					this.set(hx + 1, top, hz + d, ModRegistry.RUNWAY_MARKING.defaultBlockState());
				}
				this.set(hx, top, hz, ModRegistry.RUNWAY_MARKING.defaultBlockState());
			}
			if (top >= 112) {
				// a glass spire stepping in on the roof, a mast on top of it
				for (int l = 0; l < 6; l++) {
					int half = 2 - l / 2;
					for (int a = -half; a <= half; a++) {
						for (int b = -half; b <= half; b++) {
							boolean edge = Math.abs(a) == half && Math.abs(b) == half;
							this.set(cx + a, top + 1 + l, cz + b - 4, edge ? frame : glass);
						}
					}
				}
				this.fill(cx, top + 7, cz - 4, cx, top + 20, cz - 4, Blocks.IRON_BARS.defaultBlockState());
				this.set(cx, top + 21, cz - 4, Blocks.LIGHTNING_ROD.defaultBlockState());
				this.set(cx, top + 22, cz - 4, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true));
			}
			int ax = rx0 + 1;
			int az = rz0 + 1;
			int mast = 8 + (int) (this.hash(k, j, 11) * 10);
			this.fill(ax, top + 1, az, ax, top + mast, az, Blocks.IRON_BARS);
			this.set(ax, top + mast + 1, az, Blocks.LIGHTNING_ROD);
			this.set(ax, top + mast + 2, az, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(RedstoneLampBlock.LIT, true));
			this.fill(rx1 - 3, top + 1, rz1 - 2, rx1 - 1, top + 2, rz1 - 1, Blocks.LIGHT_GRAY_CONCRETE);
			this.fill(rx1 - 3, top + 3, rz1 - 2, rx1 - 1, top + 3, rz1 - 1, Blocks.IRON_TRAPDOOR);
		}

		/** An office tower or (with balconies) an apartment block: masonry, rows of windows, shops on the ground floor. */
		void block(int k, int j, int x0, int z0, int x1, int z1, int height, boolean flats) {
			int inset = flats ? 3 : 2;
			int bx0 = x0 + inset;
			int bz0 = z0 + inset;
			int bx1 = x1 - inset;
			int bz1 = z1 - inset;
			int cx = (bx0 + bx1) / 2;
			int cz = (bz0 + bz1) / 2;
			int top = height / 4 * 4;
			BlockState wall = this.pick(MASONRY, k, j, 12);
			BlockState glass = this.hash(k, j, 13) < 0.5F ? Blocks.GLASS.defaultBlockState() : Blocks.LIGHT_GRAY_STAINED_GLASS.defaultBlockState();
			BlockState trim = this.pick(FRAMES, k, j, 14);
			this.paveLot(x0, z0, x1, z1, Blocks.SMOOTH_STONE.defaultBlockState());
			BlockState railing = Blocks.IRON_BARS.defaultBlockState();
			BlockState sign = this.pick(SIGNS, k, j, 35);
			BlockState awning = this.pick(AWNINGS, k, j, 36);
			for (int x = Math.max(x0, this.cx0); x <= Math.min(x1, this.cx1); x++) {
				for (int z = Math.max(z0, this.cz0); z <= Math.min(z1, this.cz1); z++) {
					boolean in = x >= bx0 && x <= bx1 && z >= bz0 && z <= bz1;
					if (!in) {
						// balconies on every floor of the flats, a hedge round the offices
						boolean skin = x >= bx0 - 1 && x <= bx1 + 1 && z >= bz0 - 1 && z <= bz1 + 1;
						boolean corner = (x == bx0 - 1 || x == bx1 + 1) && (z == bz0 - 1 || z == bz1 + 1);
						if (flats && skin && !corner) {
							int along = x == bx0 - 1 || x == bx1 + 1 ? z - bz0 : x - bx0;
							if (Math.floorMod(along, 6) >= 1 && Math.floorMod(along, 6) <= 3) {
								for (int y = 4; y < top; y += 4) {
									this.set(x, y, z, trim);
									this.set(x, y + 1, z, railing);
								}
							}
						} else if (!flats && skin && !corner) {
							// striped awnings over the shop windows, flowers under them
							int along = x == bx0 - 1 || x == bx1 + 1 ? z - bz0 : x - bx0;
							this.set(x, 4, z, Math.floorMod(along, 2) == 0 ? awning : Blocks.WHITE_WOOL.defaultBlockState());
							if (Math.floorMod(along, 5) == 2) {
								this.set(x, 1, z, Blocks.POTTED_RED_TULIP.defaultBlockState());
							}
						} else if (!flats && (x == x0 || x == x1 || z == z0 || z == z1) && Math.floorMod(x + z, 4) != 0) {
							this.set(x, 1, z, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
						}
						continue;
					}
					this.set(x, 0, z, Blocks.POLISHED_ANDESITE.defaultBlockState());
					for (int y = 1; y <= top + 1; y++) {
						boolean edge = x == bx0 || x == bx1 || z == bz0 || z == bz1;
						if (y == top + 1) {
							if (edge) {
								this.set(x, y, z, trim);
							}
						} else if (y == top) {
							this.set(x, y, z, x == cx && z == cz ? Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH) : trim);
						} else if (edge && y > 3 && y % 4 == 3) {
							this.set(x, y, z, glass); // window tops
						} else if (edge && y > 3 && y % 4 == 2) {
							int along = x == bx0 || x == bx1 ? z - bz0 : x - bx0;
							boolean cornerCol = (x == bx0 || x == bx1) && (z == bz0 || z == bz1);
							this.set(x, y, z, cornerCol || along % 3 == 0 ? wall : glass);
						} else if (edge && y == 4) {
							this.set(x, y, z, sign); // the shops' fascia
						} else if (edge && y > 3) {
							this.set(x, y, z, y % 4 == 0 ? trim : wall);
						} else {
							this.storey(x, y, z, bx0, bz0, bx1, bz1, cx, cz, wall, glass, Blocks.SPRUCE_PLANKS.defaultBlockState(), 4, false, flats ? FLATS : OFFICES);
						}
					}
				}
			}
			// a water tank on legs or air conditioning on the roof
			if (this.hash(k, j, 15) < 0.5F) {
				int tx = bx0 + 2;
				int tz = bz0 + 2;
				for (int a = 0; a <= 2; a += 2) {
					for (int b = 0; b <= 2; b += 2) {
						this.fill(tx + a, top + 1, tz + b, tx + a, top + 3, tz + b, Blocks.SPRUCE_FENCE);
					}
				}
				this.fill(tx, top + 4, tz, tx + 2, top + 6, tz + 2, Blocks.SPRUCE_PLANKS);
				this.fill(tx, top + 7, tz, tx + 2, top + 7, tz + 2, Blocks.SPRUCE_SLAB);
			} else {
				this.fill(bx1 - 4, top + 1, bz1 - 3, bx1 - 2, top + 1, bz1 - 2, Blocks.LIGHT_GRAY_CONCRETE);
				this.set(bx1 - 3, top + 2, bz1 - 3, Blocks.IRON_TRAPDOOR);
			}
		}

		/**
		 * A large villa: four floors of white walls and big windows, the top floor stepped back behind a terrace, a
		 * ladder up through the house to the roof terrace; a hedged garden with a drive and a pool.
		 */
		void villa(int k, int j, int x0, int z0, int x1, int z1) {
			BlockState wall = this.pick(new BlockState[] {Blocks.QUARTZ_BLOCK.defaultBlockState(), Blocks.WHITE_CONCRETE.defaultBlockState(),
				Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.CALCITE.defaultBlockState(), Blocks.MUD_BRICKS.defaultBlockState()}, k, j, 16);
			BlockState trim = this.pick(new BlockState[] {Blocks.DARK_OAK_PLANKS.defaultBlockState(), Blocks.GRAY_CONCRETE.defaultBlockState(),
				Blocks.POLISHED_DEEPSLATE.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState()}, k, j, 17);
			BlockState glass = Blocks.GLASS.defaultBlockState();
			BlockState hedge = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
			this.paveLot(x0, z0, x1, z1, Blocks.GRASS_BLOCK.defaultBlockState());
			int hx0 = x0 + 3;
			int hx1 = x1 - 3;
			int hz0 = z0 + 2;
			int hz1 = z0 + 12;
			int cx = hx0 + 2;
			int cz = hz0 + 2;
			int top = 16;
			for (int x = Math.max(x0, this.cx0); x <= Math.min(x1, this.cx1); x++) {
				for (int z = Math.max(z0, this.cz0); z <= Math.min(z1, this.cz1); z++) {
					boolean border = x == x0 || x == x1 || z == z0 || z == z1;
					boolean gate = z == z1 && Math.abs(x - (x0 + x1) / 2) <= 1;
					if (border && !gate) {
						this.set(x, 1, z, hedge);
						this.set(x, 2, z, hedge);
					}
					// the drive from the gate to the door, the pool behind a quartz rim
					if (Math.abs(x - (x0 + x1) / 2) <= 1 && z > hz1 && z < z1) {
						this.set(x, 0, z, Blocks.GRAVEL.defaultBlockState());
					}
					int px0 = x0 + 3;
					int px1 = x0 + 7;
					int pz0 = hz1 + 3;
					int pz1 = z1 - 2;
					if (x >= px0 - 1 && x <= px1 + 1 && z >= pz0 - 1 && z <= pz1 + 1) {
						boolean rim = x == px0 - 1 || x == px1 + 1 || z == pz0 - 1 || z == pz1 + 1;
						this.set(x, -1, z, Blocks.QUARTZ_BLOCK.defaultBlockState());
						this.set(x, 0, z, rim ? Blocks.SMOOTH_QUARTZ.defaultBlockState() : Blocks.WATER.defaultBlockState());
						if (!rim && (x == px0 || x == px1 || z == pz0 || z == pz1)) {
							this.set(x, -1, z, Blocks.SEA_LANTERN.defaultBlockState());
						}
					}
					if (x < hx0 || x > hx1 || z < hz0 || z > hz1) {
						continue;
					}
					// the house
					this.set(x, 0, z, Blocks.POLISHED_ANDESITE.defaultBlockState());
					boolean upper = z <= hz1 - 4; // the top floor is set back from the front: a terrace
					for (int y = 1; y <= top + 1; y++) {
						int zEnd = y > 12 ? hz1 - 4 : hz1;
						if (z > zEnd) {
							if (y == 13 && z == hz1 || y == 13 && (x == hx0 || x == hx1)) {
								this.set(x, y, z, Blocks.GLASS_PANE.defaultBlockState()); // the terrace's glass railing
							}
							continue;
						}
						boolean edge = x == hx0 || x == hx1 || z == hz0 || z == zEnd;
						if (y == top + 1) {
							if (edge) {
								this.set(x, y, z, trim);
							}
							continue;
						}
						if (y == top || y == 12 && !upper) {
							this.set(x, y, z, x == cx && z == cz ? Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH) : trim);
							continue;
						}
						if (edge) {
							int along = x == hx0 || x == hx1 ? z - hz0 : x - hx0;
							int span = x == hx0 || x == hx1 ? zEnd - hz0 : hx1 - hx0;
							boolean cornerCol = (x == hx0 || x == hx1) && (z == hz0 || z == zEnd);
							BlockState s;
							if (y % 4 == 0 || cornerCol) {
								s = y % 4 == 0 ? trim : wall;
							} else if (z == hz1 && y <= 2 && Math.abs(x - (hx0 + hx1) / 2) <= 1) {
								s = Blocks.AIR.defaultBlockState(); // the front door
							} else if (along % 4 == 0) {
								s = wall;
							} else {
								s = y % 4 == 1 && along % 4 == 2 && span > 6 ? wall : glass;
							}
							this.set(x, y, z, s);
						} else {
							this.storey(x, y, z, hx0, hz0, hx1, zEnd, cx, cz, wall, glass, Blocks.OAK_PLANKS.defaultBlockState(), 4, false, HOME);
						}
					}
				}
			}
			// sun loungers by the pool, lanterns along the drive
			for (int dz = 0; dz < 3; dz++) {
				this.set(x1 - 2, 1, hz1 + 4 + dz * 2, Blocks.WHITE_BED.defaultBlockState()
					.setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.EAST)
					.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD));
				this.set(x1 - 3, 1, hz1 + 4 + dz * 2, Blocks.WHITE_BED.defaultBlockState()
					.setValue(net.minecraft.world.level.block.BedBlock.FACING, Direction.EAST)
					.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT));
			}
			this.car((x0 + x1) / 2 - 1, hz1 + 2, true, this.pick(PAINT, k, j, 37));
			int px = x0 + 10;
			int pz = z1 - 4;
			this.fill(px, 1, pz, px, 3, pz, Blocks.OAK_FENCE.defaultBlockState());
			this.fill(px - 1, 4, pz - 1, px + 1, 4, pz + 1, this.pick(new BlockState[] {Blocks.WHITE_CARPET.defaultBlockState(),
				Blocks.RED_CARPET.defaultBlockState(), Blocks.BLUE_CARPET.defaultBlockState()}, k, j, 38));
			for (int z = hz1 + 2; z < z1; z += 3) {
				this.set((x0 + x1) / 2 + 2, 1, z, Blocks.LANTERN.defaultBlockState());
			}
		}

		/** A park: paths crossing at a fountain, flower beds, bushes, benches and lamps. */
		void park(int k, int j, int x0, int z0, int x1, int z1) {
			int mx = (x0 + x1) / 2;
			int mz = (z0 + z1) / 2;
			BlockState[] flowers = {Blocks.POPPY.defaultBlockState(), Blocks.DANDELION.defaultBlockState(), Blocks.CORNFLOWER.defaultBlockState(),
				Blocks.OXEYE_DAISY.defaultBlockState(), Blocks.AZURE_BLUET.defaultBlockState(), Blocks.ALLIUM.defaultBlockState()};
			BlockState bush = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
			for (int x = Math.max(x0, this.cx0); x <= Math.min(x1, this.cx1); x++) {
				for (int z = Math.max(z0, this.cz0); z <= Math.min(z1, this.cz1); z++) {
					int dx = x - mx;
					int dz = z - mz;
					double d = Math.sqrt(dx * dx + dz * dz);
					boolean path = Math.abs(dx) <= 1 || Math.abs(dz) <= 1 || d >= 6.5 && d < 8.5;
					this.set(x, 0, z, path ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState());
					if (d < 3.5) {
						// the fountain: a quartz basin, a column in the middle
						boolean rim = d >= 2.5;
						this.set(x, -1, z, Blocks.QUARTZ_BLOCK.defaultBlockState());
						this.set(x, 0, z, rim ? Blocks.SMOOTH_QUARTZ.defaultBlockState() : Blocks.WATER.defaultBlockState());
						if (rim) {
							this.set(x, 1, z, Blocks.QUARTZ_SLAB.defaultBlockState());
						}
						if (dx == 0 && dz == 0) {
							this.set(x, 0, z, Blocks.QUARTZ_PILLAR.defaultBlockState());
							this.set(x, 1, z, Blocks.QUARTZ_PILLAR.defaultBlockState());
							this.set(x, 2, z, Blocks.SEA_LANTERN.defaultBlockState());
						}
						continue;
					}
					if (path) {
						continue;
					}
					float r = this.hash(x, z, 18);
					if (r < 0.06F) {
						this.set(x, 1, z, bush);
						if (r < 0.025F) {
							this.set(x, 2, z, bush);
						}
					} else if (r < 0.3F) {
						this.set(x, 1, z, this.pick(flowers, x, z, 19));
					} else if (r < 0.75F) {
						this.set(x, 1, z, Blocks.SHORT_GRASS.defaultBlockState());
					}
				}
			}
			// benches facing the fountain along the ring, lamps at the four corners of it
			for (int s = -1; s <= 1; s += 2) {
				this.set(mx + 4, 1, mz + s * 9, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, s > 0 ? Direction.SOUTH : Direction.NORTH));
				this.set(mx - 4, 1, mz + s * 9, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, s > 0 ? Direction.SOUTH : Direction.NORTH));
				this.set(mx + s * 9, 1, mz + 4, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, s > 0 ? Direction.EAST : Direction.WEST));
				this.set(mx + s * 9, 1, mz - 4, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, s > 0 ? Direction.EAST : Direction.WEST));
				for (int t = -1; t <= 1; t += 2) {
					this.fill(mx + s * 5, 1, mz + t * 5, mx + s * 5, 3, mz + t * 5, Blocks.ANDESITE_WALL);
					this.set(mx + s * 5, 4, mz + t * 5, Blocks.LANTERN);
				}
			}
		}

		/** A car park: bays marked out on asphalt, half of them taken. */
		void parking(int k, int j, int x0, int z0, int x1, int z1) {
			this.paveLot(x0, z0, x1, z1, ModRegistry.RUNWAY_ASPHALT.defaultBlockState());
			for (int row = 0; row < 2; row++) {
				int zr = row == 0 ? z0 + 1 : z1 - 4;
				for (int x = x0 + 1; x <= x1 - 1; x += 3) {
					this.fill(x, 0, zr, x, 0, zr + 3, ModRegistry.RUNWAY_MARKING);
					if (x + 2 < x1 && this.hash(x, zr, 20 + k * 7 + j) < 0.55F) {
						this.car(x + 1, zr, true, this.pick(PAINT, x, zr, 21));
					}
				}
			}
			for (int x = x0 + 3; x <= x1 - 3; x += 7) {
				this.fill(x, 1, (z0 + z1) / 2, x, 4, (z0 + z1) / 2, Blocks.ANDESITE_WALL);
				this.set(x, 5, (z0 + z1) / 2, Blocks.LANTERN);
			}
		}
	}
}
