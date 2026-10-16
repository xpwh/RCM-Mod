package de.rcm.ballistic.base;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.ai.SoldierEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/**
 * The whole base as one piece. It is built from a plan in local coordinates (x east, z south, y = 0 the
 * ground) turned by the base's rotation; each chunk runs the plan and keeps only what falls inside it, so the
 * plan never needs storing - the origin, rotation, kind and seed rebuild it exactly.
 */
public class MilitaryBasePiece extends StructurePiece {
	public static final int VARIANTS = 4;
	public static final int RADAR = 0;
	public static final int SILO = 1;
	public static final int AIRFIELD = 2;
	public static final int BUNKER = 3;
	/** The smaller sites: a road checkpoint, a SAM site, a hidden weapons cache. */
	public static final int CHECKPOINT = 10;
	public static final int SAM_SITE = 11;
	public static final int CACHE = 12;
	/** Half the fence's side. */
	private static final int F = 36;
	private static final ResourceKey<LootTable> LOOT = ResourceKey.create(Registries.LOOT_TABLE, BallisticMissiles.id("chests/military_base"));

	private final int ox;
	private final int oy;
	private final int oz;
	private final Rotation rot;
	private final int variant;
	private final long seed;

	public MilitaryBasePiece(BlockPos origin, Rotation rot, int variant, long seed) {
		super(BaseRegistry.MILITARY_BASE_PIECE, 0, box(origin, rot, variant));
		this.ox = origin.getX();
		this.oy = origin.getY();
		this.oz = origin.getZ();
		this.rot = rot;
		this.variant = variant;
		this.seed = seed;
	}

	public MilitaryBasePiece(CompoundTag tag) {
		super(BaseRegistry.MILITARY_BASE_PIECE, tag);
		this.ox = tag.getIntOr("OX", 0);
		this.oy = tag.getIntOr("OY", 64);
		this.oz = tag.getIntOr("OZ", 0);
		this.rot = Rotation.values()[Mth.clamp(tag.getIntOr("Rot", 0), 0, 3)];
		this.variant = tag.getIntOr("Variant", 0);
		this.seed = tag.getLongOr("Seed", 0L);
	}

	@Override
	protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		tag.putInt("OX", this.ox);
		tag.putInt("OY", this.oy);
		tag.putInt("OZ", this.oz);
		tag.putInt("Rot", this.rot.ordinal());
		tag.putInt("Variant", this.variant);
		tag.putLong("Seed", this.seed);
	}

	/** The local area the base covers: x min, x max, z min, z max. */
	private static int[] area(int variant) {
		return switch (variant) {
			case AIRFIELD -> new int[] {-42, 57, -66, 66};
			case CHECKPOINT -> new int[] {-22, 22, -26, 26};
			case SAM_SITE -> new int[] {-25, 25, -25, 25};
			case CACHE -> new int[] {-16, 16, -16, 16};
			default -> new int[] {-42, 42, -42, 50};
		};
	}

	/** Half the side of the gravelled yard. */
	private static int yard(int variant) {
		return switch (variant) {
			case CHECKPOINT -> 17;
			case SAM_SITE -> 20;
			case CACHE -> 12;
			default -> F;
		};
	}

	/** Where the flag stands (local x, z). */
	private static int[] flagAt(int variant) {
		return switch (variant) {
			case CHECKPOINT -> new int[] {-9, 0};
			case SAM_SITE -> new int[] {-12, 12};
			case CACHE -> new int[] {7, 7};
			default -> new int[] {0, 0};
		};
	}

	private static BoundingBox box(BlockPos o, Rotation rot, int variant) {
		int[] a = area(variant);
		int x1 = wx(o.getX(), rot, a[0], a[2]);
		int z1 = wz(o.getZ(), rot, a[0], a[2]);
		int x2 = wx(o.getX(), rot, a[1], a[3]);
		int z2 = wz(o.getZ(), rot, a[1], a[3]);
		// the ground is at the box's floor: that is where the terrain is levelled to
		return new BoundingBox(Math.min(x1, x2), o.getY(), Math.min(z1, z2), Math.max(x1, x2), o.getY() + 24, Math.max(z1, z2));
	}

	private static int wx(int ox, Rotation rot, int x, int z) {
		return switch (rot) {
			case NONE -> ox + x;
			case CLOCKWISE_90 -> ox - z;
			case CLOCKWISE_180 -> ox - x;
			case COUNTERCLOCKWISE_90 -> ox + z;
		};
	}

	private static int wz(int oz, Rotation rot, int x, int z) {
		return switch (rot) {
			case NONE -> oz + z;
			case CLOCKWISE_90 -> oz + x;
			case CLOCKWISE_180 -> oz - z;
			case COUNTERCLOCKWISE_90 -> oz - x;
		};
	}

	@Override
	public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random, BoundingBox chunkBox,
		ChunkPos chunkPos, BlockPos pivot) {
		new Build(level, chunkBox, random).run();
	}

	// ------------------------------------------------------------------ the builder

	private final class Build {
		private final WorldGenLevel level;
		private final BoundingBox clip;
		private final RandomSource random;
		private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		private final List<BlockPos> lights = new ArrayList<>();
		private final List<BlockPos> alarms = new ArrayList<>();
		private final List<BlockPos> generators = new ArrayList<>();
		private BlockPos barracks = BlockPos.ZERO;

		Build(WorldGenLevel level, BoundingBox clip, RandomSource random) {
			this.level = level;
			this.clip = clip;
			this.random = random;
		}

		// --- coordinates

		int x(int x, int z) {
			return wx(MilitaryBasePiece.this.ox, MilitaryBasePiece.this.rot, x, z);
		}

		int z(int x, int z) {
			return wz(MilitaryBasePiece.this.oz, MilitaryBasePiece.this.rot, x, z);
		}

		BlockPos at(int x, int y, int z) {
			return new BlockPos(this.x(x, z), MilitaryBasePiece.this.oy + y, this.z(x, z));
		}

		boolean inside(int x, int y, int z) {
			return this.clip.isInside(this.x(x, z), MilitaryBasePiece.this.oy + y, this.z(x, z));
		}

		/** A number in [0, 1) fixed for a spot and a purpose. */
		float hash(int x, int z, int salt) {
			long h = MilitaryBasePiece.this.seed ^ x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
			h ^= h >>> 33;
			h *= 0xff51afd7ed558ccdL;
			h ^= h >>> 33;
			h *= 0xc4ceb9fe1a85ec53L;
			h ^= h >>> 33;
			return (h >>> 40) / (float) (1 << 24);
		}

		/** Smooth patches (value noise, cells of {@code cell} blocks). */
		float patch(int x, int z, int cell, int salt) {
			int cx = Math.floorDiv(x, cell);
			int cz = Math.floorDiv(z, cell);
			float fx = (x - cx * cell) / (float) cell;
			float fz = (z - cz * cell) / (float) cell;
			fx = fx * fx * (3 - 2 * fx);
			fz = fz * fz * (3 - 2 * fz);
			float a = Mth.lerp(fx, this.hash(cx, cz, salt), this.hash(cx + 1, cz, salt));
			float b = Mth.lerp(fx, this.hash(cx, cz + 1, salt), this.hash(cx + 1, cz + 1, salt));
			return Mth.lerp(fz, a, b);
		}

		// --- placing

		void set(int x, int y, int z, BlockState s) {
			int wx = this.x(x, z);
			int wy = MilitaryBasePiece.this.oy + y;
			int wz = this.z(x, z);
			if (this.clip.isInside(wx, wy, wz)) {
				this.m.set(wx, wy, wz);
				this.level.setBlock(this.m, s.rotate(MilitaryBasePiece.this.rot), Block.UPDATE_CLIENTS);
			}
		}

		void set(int x, int y, int z, Block b) {
			this.set(x, y, z, b.defaultBlockState());
		}

		void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
			BlockState r = s.rotate(MilitaryBasePiece.this.rot);
			int ax = this.x(x1, z1);
			int az = this.z(x1, z1);
			int bx = this.x(x2, z2);
			int bz = this.z(x2, z2);
			int minX = Math.max(Math.min(ax, bx), this.clip.minX());
			int maxX = Math.min(Math.max(ax, bx), this.clip.maxX());
			int minZ = Math.max(Math.min(az, bz), this.clip.minZ());
			int maxZ = Math.min(Math.max(az, bz), this.clip.maxZ());
			int minY = Math.max(MilitaryBasePiece.this.oy + Math.min(y1, y2), this.clip.minY());
			int maxY = Math.min(MilitaryBasePiece.this.oy + Math.max(y1, y2), this.clip.maxY());
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					for (int y = minY; y <= maxY; y++) {
						this.m.set(x, y, z);
						this.level.setBlock(this.m, r, Block.UPDATE_CLIENTS);
					}
				}
			}
		}

		void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block b) {
			this.fill(x1, y1, z1, x2, y2, z2, b.defaultBlockState());
		}

		/** The walls of a box (its four sides, not top or bottom). */
		void walls(int x1, int y1, int z1, int x2, int y2, int z2, BlockState s) {
			this.fill(x1, y1, z1, x2, y2, z1, s);
			this.fill(x1, y1, z2, x2, y2, z2, s);
			this.fill(x1, y1, z1, x1, y2, z2, s);
			this.fill(x2, y1, z1, x2, y2, z2, s);
		}

		void air(int x1, int y1, int z1, int x2, int y2, int z2) {
			this.fill(x1, y1, z1, x2, y2, z2, Blocks.AIR.defaultBlockState());
		}

		BlockState facing(Block b, Direction d) {
			return b.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, d);
		}

		void floodlight(int x, int y, int z, Direction d) {
			this.set(x, y, z, this.facing(BaseRegistry.FLOODLIGHT, d).setValue(BaseBlocks.Lamp.LIT, true));
			this.lights.add(this.at(x, y, z));
		}

		void alarm(int x, int y, int z) {
			this.set(x, y, z, BaseRegistry.ALARM_LIGHT.defaultBlockState().setValue(BaseBlocks.Lamp.LIT, false));
			this.alarms.add(this.at(x, y, z));
		}

		/** A lamp post: a concrete pole with a floodlight on top. */
		void pole(int x, int z, int height, Direction d) {
			this.fill(x, 1, z, x, height, z, Blocks.ANDESITE_WALL);
			this.floodlight(x, height + 1, z, d);
		}

		void barrel(int x, int y, int z, Direction d) {
			this.set(x, y, z, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, d));
			if (this.inside(x, y, z)) {
				RandomizableContainer.setBlockEntityLootTable(this.level, this.random, this.at(x, y, z), LOOT);
			}
		}

		void door(int x, int y, int z, Block b, Direction d, DoorHingeSide hinge) {
			BlockState s = b.defaultBlockState().setValue(DoorBlock.FACING, d).setValue(DoorBlock.HINGE, hinge);
			this.set(x, y, z, s.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
			this.set(x, y + 1, z, s.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		}

		/** A bed whose head is at (x, z) and foot one step against {@code d}. */
		void bed(int x, int y, int z, Direction d) {
			BlockState s = Blocks.GREEN_BED.defaultBlockState().setValue(BedBlock.FACING, d);
			this.set(x, y, z, s.setValue(BedBlock.PART, BedPart.HEAD));
			this.set(x - d.getStepX(), y, z - d.getStepZ(), s.setValue(BedBlock.PART, BedPart.FOOT));
		}

		void ladder(int x, int y1, int y2, int z, Direction d) {
			this.fill(x, y1, z, x, y2, z, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, d));
		}

		BlockState chainlink(boolean alongX, boolean alongZ) {
			return BaseRegistry.CHAINLINK_FENCE.defaultBlockState()
				.setValue(IronBarsBlock.EAST, alongX).setValue(IronBarsBlock.WEST, alongX)
				.setValue(IronBarsBlock.NORTH, alongZ).setValue(IronBarsBlock.SOUTH, alongZ);
		}

		void guard(int x, int y, int z, float radius) {
			if (!this.inside(x, y, z) || this.level.getLevel() == null) {
				return; // (no world behind the level: an offline preview of the plan)
			}
			SoldierEntity s = ModRegistry.SOLDIER.create(this.level.getLevel(), EntitySpawnReason.STRUCTURE);
			if (s == null) {
				return;
			}
			BlockPos p = this.at(x, y, z);
			Vec3 v = Vec3.atBottomCenterOf(p);
			s.snapTo(v.x, v.y, v.z, this.hash(x, z, 77) * 360.0F, 0.0F);
			s.setTeam(SoldierEntity.TEAM_HOSTILE);
			int[] f = flagAt(MilitaryBasePiece.this.variant);
			s.setPost(v, radius, this.at(f[0], 2, f[1]));
			this.level.addFreshEntity(s);
		}

		// ------------------------------------------------------------------ the plan

		void run() {
			if (MilitaryBasePiece.this.variant >= CHECKPOINT) {
				this.terrain();
				switch (MilitaryBasePiece.this.variant) {
					case CHECKPOINT -> this.checkpoint();
					case SAM_SITE -> this.samSite();
					default -> this.cache();
				}
				int[] f = flagAt(MilitaryBasePiece.this.variant);
				this.plinth(f[0], f[1]);
				this.flag();
				return;
			}
			this.terrain();
			this.fence();
			this.gate();
			this.roads();
			for (int sx = -1; sx <= 1; sx += 2) {
				for (int sz = -1; sz <= 1; sz += 2) {
					this.tower(sx * 31, sz * 31, sx, sz);
				}
			}
			this.barracks();
			this.commandBunker();
			this.helipad();
			this.fuelDepot();
			this.ammoDump();
			switch (MilitaryBasePiece.this.variant) {
				case RADAR -> this.radarSite();
				case SILO -> this.siloField();
				case AIRFIELD -> this.airfield();
				default -> this.deepBunker();
			}
			this.garrison();
			this.flag();
		}

		/** Levels the ground: cut what stands above, fill what is missing below, gravel the yard. */
		void terrain() {
			int[] a = area(MilitaryBasePiece.this.variant);
			int oy = MilitaryBasePiece.this.oy;
			for (int wx = this.clip.minX(); wx <= this.clip.maxX(); wx++) {
				for (int wz = this.clip.minZ(); wz <= this.clip.maxZ(); wz++) {
					int dx = wx - MilitaryBasePiece.this.ox;
					int dz = wz - MilitaryBasePiece.this.oz;
					int lx;
					int lz;
					switch (MilitaryBasePiece.this.rot) {
						case NONE -> {
							lx = dx;
							lz = dz;
						}
						case CLOCKWISE_90 -> {
							lx = dz;
							lz = -dx;
						}
						case CLOCKWISE_180 -> {
							lx = -dx;
							lz = -dz;
						}
						default -> {
							lx = -dz;
							lz = dx;
						}
					}
					if (lx < a[0] || lx > a[1] || lz < a[2] || lz > a[3]) {
						continue;
					}
					this.column(wx, wz, lx, lz, oy);
				}
			}
		}

		private boolean loose(BlockState s) {
			return s.isAir() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || !s.getFluidState().isEmpty();
		}

		private void column(int wx, int wz, int lx, int lz, int oy) {
			// what the ground here is made of
			BlockState natural = null;
			for (int y = oy + 26; y >= oy - 20; y--) {
				BlockState s = this.level.getBlockState(this.m.set(wx, y, wz));
				if (!this.loose(s)) {
					natural = s;
					break;
				}
			}
			boolean sandy = natural != null && (natural.is(BlockTags.SAND) || natural.is(Blocks.SANDSTONE) || natural.is(Blocks.RED_SANDSTONE));
			BlockState top = sandy ? natural.getBlock().defaultBlockState()
				: natural != null && natural.is(BlockTags.DIRT) || natural == null ? Blocks.GRASS_BLOCK.defaultBlockState() : natural;
			BlockState sub = sandy ? (natural.is(Blocks.RED_SAND) ? Blocks.RED_SAND : Blocks.SAND).defaultBlockState() : Blocks.DIRT.defaultBlockState();
			for (int y = oy + 1; y <= oy + 26; y++) {
				if (!this.level.getBlockState(this.m.set(wx, y, wz)).isAir()) {
					this.level.setBlock(this.m, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
				}
			}
			for (int y = oy - 1; y >= oy - 20; y--) {
				if (!this.loose(this.level.getBlockState(this.m.set(wx, y, wz)))) {
					break;
				}
				this.level.setBlock(this.m, y >= oy - 3 ? sub : Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
			int yr = yard(MilitaryBasePiece.this.variant);
			boolean yard = Math.abs(lx) <= yr && Math.abs(lz) <= yr;
			BlockState ground = top;
			if (yard) {
				float p = this.patch(lx, lz, 5, 1) * 0.75F + this.hash(lx, lz, 2) * 0.25F;
				ground = p < 0.42F ? Blocks.GRAVEL.defaultBlockState() : p < 0.62F ? Blocks.COARSE_DIRT.defaultBlockState()
					: p < 0.7F ? Blocks.PACKED_MUD.defaultBlockState() : top;
			}
			this.level.setBlock(this.m.set(wx, oy, wz), ground, Block.UPDATE_CLIENTS);
		}

		/** Chain-link round the compound, razor wire along the top. */
		void fence() {
			boolean taxi = MilitaryBasePiece.this.variant == AIRFIELD;
			for (int t = -F; t <= F; t++) {
				boolean post = t % 6 == 0;
				boolean corner = Math.abs(t) == F;
				// north and south sides run along x
				for (int side = -1; side <= 1; side += 2) {
					int z = side * F;
					if (!(side > 0 && Math.abs(t) <= 4)) {
						BlockState s = corner ? this.chainlink(true, true) : this.chainlink(true, false);
						this.fill(t, 1, z, t, 3, z, s);
						this.set(t, 4, z, post || corner ? s : BaseRegistry.BARBED_WIRE.defaultBlockState());
					}
					int x = side * F;
					if (!corner && !(taxi && side > 0 && t >= -24 && t <= -16)) {
						BlockState s = this.chainlink(false, true);
						this.fill(x, 1, t, x, 3, t, s);
						this.set(x, 4, t, post ? s : BaseRegistry.BARBED_WIRE.defaultBlockState());
					}
				}
			}
		}

		/** The gate: a concrete frame with lights, a guard hut, a chicane of barriers and two gun nests. */
		void gate() {
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			for (int s = -1; s <= 1; s += 2) {
				this.fill(s * 5, 1, F, s * 5, 5, F, concrete);
				this.floodlight(s * 4, 6, F, Direction.SOUTH);
			}
			this.fill(-5, 6, F, 5, 6, F, BaseRegistry.CORRUGATED_OLIVE);
			this.floodlight(-4, 7, F, Direction.SOUTH);
			this.floodlight(4, 7, F, Direction.SOUTH);
			this.alarm(0, 7, F);
			// the road out and the chicane
			this.fill(-3, 0, F, 3, 0, 48, ModRegistry.RUNWAY_ASPHALT);
			BlockState jersey = this.facing(BaseRegistry.JERSEY_BARRIER, Direction.NORTH);
			this.fill(-6, 1, 40, -1, 1, 40, jersey);
			this.fill(1, 1, 44, 6, 1, 44, jersey);
			this.fill(-6, 1, 48, -1, 1, 48, jersey);
			// guard hut, east of the road
			this.fill(6, 0, 30, 10, 0, 34, Blocks.SMOOTH_STONE);
			this.walls(6, 1, 30, 10, 3, 34, BaseRegistry.CORRUGATED_OLIVE.defaultBlockState());
			this.air(7, 1, 31, 9, 3, 33);
			this.fill(7, 2, 30, 9, 2, 30, Blocks.GLASS_PANE.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true));
			this.fill(7, 2, 34, 9, 2, 34, Blocks.GLASS_PANE.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true));
			this.fill(10, 2, 31, 10, 2, 33, Blocks.GLASS_PANE.defaultBlockState().setValue(IronBarsBlock.NORTH, true).setValue(IronBarsBlock.SOUTH, true));
			this.door(6, 1, 32, Blocks.SPRUCE_DOOR, Direction.EAST, DoorHingeSide.LEFT);
			this.fill(5, 4, 29, 11, 4, 35, BaseRegistry.CORRUGATED_GREY);
			this.set(8, 3, 32, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			this.barrel(9, 1, 33, Direction.UP);
			this.set(9, 1, 31, this.facing(Blocks.SPRUCE_STAIRS, Direction.EAST));
			// sandbagged gun nests either side of the road inside
			for (int s = -1; s <= 1; s += 2) {
				int cx = s * 13;
				int cz = 31;
				this.walls(cx - 1, 1, cz - 1, cx + 1, 1, cz + 1, BaseRegistry.SANDBAGS.defaultBlockState());
				this.fill(cx - 1, 2, cz + 1, cx + 1, 2, cz + 1, BaseRegistry.SANDBAGS);
				this.set(cx, 1, cz - 1, Blocks.AIR); // way in
			}
		}

		void roads() {
			Block asphalt = ModRegistry.RUNWAY_ASPHALT;
			this.fill(-3, 0, 9, 3, 0, F, asphalt);
			for (int z = 10; z < F; z += 4) {
				this.fill(0, 0, z, 0, 0, z + 1, ModRegistry.RUNWAY_MARKING);
			}
			this.fill(-17, 0, -2, -9, 0, 2, asphalt);
			this.fill(9, 0, -2, 17, 0, 2, asphalt);
			this.fill(-2, 0, -15, 2, 0, -9, asphalt);
			this.fill(-20, 0, -14, 8, 0, -10, asphalt);
			this.fill(-20, 0, -17, -17, 0, -15, asphalt);
			// parade square round the flag
			this.fill(-8, 0, -8, 8, 0, 8, Blocks.SMOOTH_STONE);
			this.walls(-8, 0, -8, 8, 0, 8, Blocks.POLISHED_ANDESITE.defaultBlockState());
			this.fill(-1, 1, -1, 1, 1, 1, Blocks.POLISHED_ANDESITE);
			this.set(-1, 1, -1, Blocks.POLISHED_ANDESITE_SLAB);
			this.set(1, 1, -1, Blocks.POLISHED_ANDESITE_SLAB);
			this.set(-1, 1, 1, Blocks.POLISHED_ANDESITE_SLAB);
			this.set(1, 1, 1, Blocks.POLISHED_ANDESITE_SLAB);
			for (int sx = -1; sx <= 1; sx += 2) {
				for (int sz = -1; sz <= 1; sz += 2) {
					this.pole(sx * 9, sz * 9, 6, sz > 0 ? Direction.NORTH : Direction.SOUTH);
				}
			}
			this.pole(-11, 24, 6, Direction.WEST);
			this.pole(12, 11, 6, Direction.SOUTH);
			this.pole(-13, -7, 6, Direction.WEST);
			this.pole(4, 26, 6, Direction.SOUTH);
		}

		/** A wooden watchtower: legs, a sandbagged platform with a roof, floodlights and a beacon. */
		void tower(int cx, int cz, int sx, int sz) {
			BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
			for (int a = -2; a <= 2; a += 4) {
				for (int b = -2; b <= 2; b += 4) {
					this.fill(cx + a, 1, cz + b, cx + a, 6, cz + b, log);
					this.fill(cx + a, 8, cz + b, cx + a, 10, cz + b, Blocks.SPRUCE_FENCE);
				}
			}
			// cross bracing
			for (int y = 2; y <= 5; y += 3) {
				this.fill(cx - 1, y, cz - 2, cx + 1, y, cz - 2, Blocks.SPRUCE_FENCE.defaultBlockState().setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.WEST, true));
				this.fill(cx - 1, y, cz + 2, cx + 1, y, cz + 2, Blocks.SPRUCE_FENCE.defaultBlockState().setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.WEST, true));
			}
			this.fill(cx - 2, 7, cz - 2, cx + 2, 7, cz + 2, Blocks.SPRUCE_PLANKS);
			this.fill(cx - 2, 1, cz - 1, cx - 2, 6, cz - 1, Blocks.SPRUCE_PLANKS);
			this.ladder(cx - 1, 1, 7, cz - 1, Direction.EAST);
			for (int t = -1; t <= 1; t++) {
				this.set(cx + t, 8, cz - 2, BaseRegistry.SANDBAGS);
				this.set(cx + t, 8, cz + 2, BaseRegistry.SANDBAGS);
				this.set(cx - 2, 8, cz + t, BaseRegistry.SANDBAGS);
				this.set(cx + 2, 8, cz + t, BaseRegistry.SANDBAGS);
			}
			this.fill(cx - 2, 11, cz - 2, cx + 2, 11, cz + 2, BaseRegistry.CORRUGATED_OLIVE);
			this.alarm(cx, 12, cz);
			this.floodlight(cx + sx * 2, 9, cz, sx > 0 ? Direction.EAST : Direction.WEST);
			this.floodlight(cx, 9, cz + sz * 2, sz > 0 ? Direction.SOUTH : Direction.NORTH);
			this.floodlight(cx - sx * 2, 9, cz, sx > 0 ? Direction.WEST : Direction.EAST);
		}

		/** The barracks: a long tin hut with bunks, lockers and lamps. */
		void barracks() {
			int x1 = -31;
			int x2 = -17;
			this.fill(x1, 0, -5, x2, 0, 5, Blocks.SPRUCE_PLANKS);
			this.walls(x1, 1, -5, x2, 4, 5, BaseRegistry.CORRUGATED_OLIVE.defaultBlockState());
			this.air(x1 + 1, 1, -4, x2 - 1, 4, 4);
			this.fill(x1 - 1, 5, -6, x2 + 1, 5, 6, BaseRegistry.CORRUGATED_GREY);
			this.fill(x1 - 1, 6, -3, x2 + 1, 6, 3, BaseRegistry.CORRUGATED_GREY);
			this.fill(x1 - 1, 7, -1, x2 + 1, 7, 1, BaseRegistry.CORRUGATED_GREY);
			BlockState pane = Blocks.GLASS_PANE.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true);
			for (int x = x1 + 2; x <= x2 - 2; x += 3) {
				this.set(x, 2, -5, pane);
				this.set(x, 3, -5, pane);
				this.set(x, 2, 5, pane);
				this.set(x, 3, 5, pane);
			}
			this.door(x2, 1, 0, Blocks.SPRUCE_DOOR, Direction.WEST, DoorHingeSide.LEFT);
			for (int x = x1 + 1; x <= x2 - 3; x += 2) {
				this.bed(x, 1, -4, Direction.NORTH);
				this.bed(x, 1, 4, Direction.SOUTH);
			}
			this.barrel(x1 + 1, 1, -1, Direction.EAST);
			this.barrel(x1 + 1, 1, 1, Direction.EAST);
			this.set(x1 + 1, 1, 0, Blocks.CRAFTING_TABLE);
			for (int x = x1 + 3; x <= x2 - 1; x += 4) {
				this.set(x, 4, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			}
			this.alarm(-24, 8, 0);
			this.barracks = this.at(x2 + 2, 1, 0);
		}

		/** The command bunker: half sunk under an earth bank, blast doors, a command post and a radio mast. */
		void commandBunker() {
			int x1 = -25;
			int x2 = -11;
			int z1 = -26;
			int z2 = -18;
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			// earth bank against three sides
			for (int r = 3; r >= 1; r--) {
				int h = 4 - r;
				this.fill(x1 - r, 1, z1 - r, x2 + r, h, z1 - r, Blocks.COARSE_DIRT);
				this.fill(x1 - r, 1, z1 - r, x1 - r, h, z2, Blocks.COARSE_DIRT);
				this.fill(x2 + r, 1, z1 - r, x2 + r, h, z2, Blocks.COARSE_DIRT);
				this.fill(x1 - r, h, z1 - r, x2 + r, h, z1 - r, Blocks.GRASS_BLOCK);
				this.fill(x1 - r, h, z1 - r, x1 - r, h, z2, Blocks.GRASS_BLOCK);
				this.fill(x2 + r, h, z1 - r, x2 + r, h, z2, Blocks.GRASS_BLOCK);
			}
			this.fill(x1, 0, z1, x2, 0, z2, Blocks.POLISHED_ANDESITE);
			this.walls(x1, 1, z1, x2, 4, z2, concrete);
			this.fill(x1, 5, z1, x2, 5, z2, concrete);
			this.air(x1 + 1, 1, z1 + 1, x2 - 1, 4, z2 - 1);
			// sandbags round the roof's edge
			this.walls(x1, 6, z1, x2, 6, z2, BaseRegistry.SANDBAGS.defaultBlockState());
			// the entrance: twin blast doors with a sandbag screen either side
			this.door(-19, 1, z2, ModRegistry.BLAST_DOOR, Direction.NORTH, DoorHingeSide.LEFT);
			this.door(-18, 1, z2, ModRegistry.BLAST_DOOR, Direction.NORTH, DoorHingeSide.RIGHT);
			this.fill(-23, 1, z2 + 1, -21, 2, z2 + 1, BaseRegistry.SANDBAGS);
			this.fill(-16, 1, z2 + 1, -14, 2, z2 + 1, BaseRegistry.SANDBAGS);
			this.floodlight(-20, 3, z2 + 1, Direction.SOUTH);
			this.floodlight(-17, 3, z2 + 1, Direction.SOUTH);
			// inside: the command post, maps, the plant room
			this.set(-18, 1, z1 + 1, this.facing(ModRegistry.COMMAND_CENTER, Direction.SOUTH));
			this.set(-21, 1, -22, Blocks.CARTOGRAPHY_TABLE);
			this.set(-15, 1, -22, Blocks.CARTOGRAPHY_TABLE);
			this.set(-21, 1, -21, this.facing(Blocks.SPRUCE_STAIRS, Direction.NORTH));
			this.set(-15, 1, -21, this.facing(Blocks.SPRUCE_STAIRS, Direction.NORTH));
			this.set(x2 - 1, 1, z1 + 1, this.facing(ModRegistry.AIR_FILTER, Direction.WEST));
			this.set(x2 - 1, 1, z1 + 2, this.facing(ModRegistry.EMERGENCY_GENERATOR, Direction.WEST).setValue(BlockStateProperties.LIT, true));
			this.barrel(x1 + 1, 1, z1 + 1, Direction.UP);
			this.barrel(x1 + 1, 1, z1 + 2, Direction.UP);
			this.set(x1 + 1, 1, z1 + 3, BaseRegistry.MILITARY_CRATE);
			this.set(x1 + 1, 2, z1 + 1, BaseRegistry.MILITARY_CRATE);
			for (int x = x1 + 3; x < x2; x += 4) {
				for (int z = z1 + 2; z < z2; z += 4) {
					this.set(x, 5, z, Blocks.SEA_LANTERN);
				}
			}
			// radio mast with its beacon
			this.fill(x2 - 2, 6, z1 + 2, x2 - 2, 16, z1 + 2, Blocks.IRON_BARS);
			this.set(x2 - 2, 6, z1 + 2, concrete);
			this.alarm(x2 - 2, 17, z1 + 2);
			this.fill(x2 - 5, 6, z1 + 2, x2 - 5, 9, z1 + 2, Blocks.LIGHTNING_ROD);
		}

		void helipad() {
			int cx = 24;
			int cz = 2;
			this.fill(cx - 6, 0, cz - 6, cx + 6, 0, cz + 6, ModRegistry.RUNWAY_ASPHALT);
			for (int x = -6; x <= 6; x++) {
				for (int z = -6; z <= 6; z++) {
					double d = Math.sqrt(x * x + z * z);
					if (d >= 4.6 && d < 5.6) {
						this.set(cx + x, 0, cz + z, ModRegistry.RUNWAY_MARKING);
					}
				}
			}
			this.fill(cx - 2, 0, cz - 2, cx - 2, 0, cz + 2, ModRegistry.RUNWAY_MARKING);
			this.fill(cx + 2, 0, cz - 2, cx + 2, 0, cz + 2, ModRegistry.RUNWAY_MARKING);
			this.fill(cx - 1, 0, cz, cx + 1, 0, cz, ModRegistry.RUNWAY_MARKING);
			BlockState green = ModRegistry.RUNWAY_LIGHT.defaultBlockState().setValue(de.rcm.ballistic.runway.RunwayLightBlock.COLOR, 1);
			for (int s = -1; s <= 1; s += 2) {
				for (int t = -1; t <= 1; t += 2) {
					this.set(cx + s * 6, 1, cz + t * 6, green);
				}
			}
			this.set(cx, 1, cz - 6, green);
			this.set(cx, 1, cz + 6, green);
			this.set(cx - 6, 1, cz, green);
			this.set(cx + 6, 1, cz, green);
		}

		/** The fuel depot: drums in a pen of blast barriers, and the generators that power the base. */
		void fuelDepot() {
			this.fill(10, 0, 14, 18, 0, 21, Blocks.SMOOTH_STONE);
			this.walls(10, 1, 14, 18, 1, 21, BaseRegistry.SANDBAGS.defaultBlockState());
			this.air(13, 1, 21, 15, 1, 21);
			for (int x = 12; x <= 16; x += 4) {
				this.set(x, 1, 17, this.facing(BaseRegistry.FIELD_GENERATOR, Direction.SOUTH));
				this.generators.add(this.at(x, 1, 17));
			}
			this.set(14, 1, 17, BaseRegistry.FUEL_DRUM);
			this.set(14, 1, 16, BaseRegistry.FUEL_DRUM);
			this.pole(11, 20, 5, Direction.EAST);
			// the pen
			this.fill(21, 0, 13, 27, 0, 26, Blocks.PACKED_MUD);
			this.walls(20, 1, 12, 28, 2, 27, BaseRegistry.HESCO.defaultBlockState());
			this.air(20, 1, 18, 20, 2, 20);
			for (int x = 22; x <= 27; x++) {
				for (int z = 14; z <= 25; z++) {
					if ((x - 22) % 3 == 2 || (z - 14) % 4 == 3) {
						continue;
					}
					this.set(x, 1, z, BaseRegistry.FUEL_DRUM);
					if (this.hash(x, z, 5) < 0.35F) {
						this.set(x, 2, z, BaseRegistry.FUEL_DRUM);
					}
				}
			}
			this.pole(24, 10, 6, Direction.SOUTH);
		}

		/** The ammunition dump: crates behind a sandbag wall under camouflage netting. */
		void ammoDump() {
			int x1 = -27;
			int x2 = -13;
			int z1 = 13;
			int z2 = 27;
			this.walls(x1, 1, z1, x2, 2, z2, BaseRegistry.SANDBAGS.defaultBlockState());
			this.air(x2, 1, 19, x2, 2, 21);
			for (int x = x1 + 2; x <= x2 - 3; x++) {
				for (int z = z1 + 2; z <= z2 - 2; z++) {
					if (Math.floorMod(x, 4) >= 2 || Math.floorMod(z, 4) >= 2) {
						continue;
					}
					this.set(x, 1, z, BaseRegistry.MILITARY_CRATE);
					if (this.hash(x, z, 9) < 0.55F) {
						this.set(x, 2, z, BaseRegistry.MILITARY_CRATE);
					}
				}
			}
			for (int x = x1 + 1; x <= x2 - 1; x += 6) {
				for (int z = z1 + 1; z <= z2 - 1; z += 6) {
					this.fill(x, 1, z, x, 3, z, Blocks.SPRUCE_FENCE);
				}
			}
			this.fill(x1 + 1, 4, z1 + 1, x2 - 1, 4, z2 - 1, BaseRegistry.CAMO_NET);
		}

		// ------------------------------------------------------------------ the four kinds of site

		/** A radar on a concrete platform, guarded by air defence and a CIWS. */
		void radarSite() {
			this.fill(9, 0, -31, 31, 0, -8, Blocks.SMOOTH_STONE);
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			this.fill(18, 1, -22, 22, 3, -18, concrete);
			this.set(20, 4, -20, ModRegistry.RADAR);
			this.ladder(20, 1, 3, -17, Direction.SOUTH);
			for (int t = -2; t <= 2; t++) {
				this.set(18 + t + 2, 4, -22, this.chainlink(true, false));
				this.set(18, 4, -20 + t, this.chainlink(false, true));
				this.set(22, 4, -20 + t, this.chainlink(false, true));
				if (t != 0) {
					this.set(20 + t, 4, -18, this.chainlink(true, false));
				}
			}
			this.floodlight(18, 5, -22, Direction.NORTH);
			this.floodlight(22, 5, -22, Direction.EAST);
			this.defence(12, -27, ModRegistry.AIR_DEFENSE);
			this.defence(25, -27, ModRegistry.AIR_DEFENSE);
			this.defence(27, -11, ModRegistry.CIWS);
			this.hut(10, -13);
			this.pole(15, -20, 6, Direction.EAST);
		}

		/** Two missile silos in concrete aprons, an Iron Dome battery and a launch control hut. */
		void siloField() {
			this.fill(9, 0, -31, 31, 0, -8, Blocks.SMOOTH_STONE);
			for (int cx = 14; cx <= 26; cx += 12) {
				int cz = -20;
				this.fill(cx - 4, 0, cz - 4, cx + 4, 0, cz + 4, ModRegistry.REINFORCED_CONCRETE);
				for (int t = -4; t <= 4; t++) {
					Block c = Math.floorMod(t, 2) == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE;
					this.set(cx + t, 0, cz - 4, c);
					this.set(cx + t, 0, cz + 4, c);
					this.set(cx - 4, 0, cz + t, c);
					this.set(cx + 4, 0, cz + t, c);
				}
				// the launch tube (as a silo digs its own)
				int depth = de.rcm.ballistic.block.MissileSiloBlock.SHAFT_DEPTH;
				this.fill(cx - 2, -depth - 1, cz - 2, cx + 2, -1, cz + 2, ModRegistry.REINFORCED_CONCRETE);
				this.air(cx - 1, -depth, cz - 1, cx + 1, -1, cz + 1);
				this.set(cx, 0, cz, ModRegistry.MISSILE_SILO);
				this.floodlight(cx - 4, 1, cz - 4, Direction.SOUTH);
				this.floodlight(cx + 4, 1, cz + 4, Direction.NORTH);
			}
			BlockState jersey = this.facing(BaseRegistry.JERSEY_BARRIER, Direction.NORTH);
			this.fill(9, 1, -15, 18, 1, -15, jersey);
			this.fill(22, 1, -15, 30, 1, -15, jersey);
			this.defence(12, -29, ModRegistry.IRON_DOME);
			this.defence(22, -29, ModRegistry.AIR_DEFENSE);
			this.hut(25, -11);
		}

		/** A hangar with a jet inside, a taxiway through the fence and a lit runway outside it. */
		void airfield() {
			int x1 = 9;
			int x2 = 28;
			int z1 = -29;
			int z2 = -12;
			this.fill(x1, 0, z1, x2, 0, z2, Blocks.SMOOTH_STONE);
			BlockState tin = BaseRegistry.CORRUGATED_GREY.defaultBlockState();
			for (int z = z1; z <= z2; z++) {
				double u = (z - (z1 + z2) * 0.5) / 9.0;
				int h = 7 + (int) Math.round(3.5 * (1.0 - u * u));
				this.fill(x1, h, z, x2, h, z, tin);
				this.fill(x1, 1, z, x1, h, z, tin);
				this.fill(x2, 7, z, x2, h, z, tin);
				this.air(x1 + 1, 1, z, x2, h - 1, z);
				if (z % 4 == 0) {
					this.set((x1 + x2) / 2, h - 1, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
				}
			}
			this.fill(x1, 1, z1, x2, 7, z1, tin);
			this.fill(x1, 1, z2, x2, 7, z2, tin);
			this.fill(x2, 1, z1, x2, 6, z1 + 1, tin);
			this.fill(x2, 1, z2 - 1, x2, 6, z2, tin);
			this.floodlight(x2 + 1, 7, z1 + 2, Direction.EAST);
			this.floodlight(x2 + 1, 7, z2 - 2, Direction.EAST);
			// stores along the back
			for (int z = z1 + 1; z <= z2 - 1; z += 2) {
				this.set(x1 + 1, 1, z, BaseRegistry.FUEL_DRUM);
			}
			this.barrel(x1 + 2, 1, z1 + 1, Direction.UP);
			this.set(x1 + 3, 1, z1 + 1, BaseRegistry.MILITARY_CRATE);
			this.set(x1 + 3, 2, z1 + 1, BaseRegistry.MILITARY_CRATE);
			this.set(x1 + 2, 1, z2 - 1, BaseRegistry.MILITARY_CRATE);
			// the jet, nose to the door
			if (this.inside(18, 1, -20)) {
				BlockPos p = this.at(18, 1, -20);
				float yaw = -90.0F + MilitaryBasePiece.this.rot.ordinal() * 90.0F;
				var jet = de.rcm.ballistic.entity.FighterEntity.parked(this.level.getLevel(), Vec3.atBottomCenterOf(p), yaw,
					this.hash(1, 1, 3) < 0.5F ? de.rcm.ballistic.entity.FighterType.F35 : de.rcm.ballistic.entity.FighterType.F22);
				if (jet != null) {
					this.level.addFreshEntity(jet);
				}
			}
			// taxiway and runway
			this.fill(x2 + 1, 0, -24, 43, 0, -16, ModRegistry.RUNWAY_ASPHALT);
			this.fill(44, 0, -63, 52, 0, 63, ModRegistry.RUNWAY_ASPHALT);
			for (int z = -54; z <= 54; z += 8) {
				this.fill(48, 0, z, 48, 0, z + 3, ModRegistry.RUNWAY_MARKING);
			}
			for (int x = 45; x <= 51; x += 2) {
				this.fill(x, 0, -61, x, 0, -57, ModRegistry.RUNWAY_MARKING);
				this.fill(x, 0, 57, x, 0, 61, ModRegistry.RUNWAY_MARKING);
			}
			BlockState white = ModRegistry.RUNWAY_LIGHT.defaultBlockState();
			for (int z = -60; z <= 60; z += 6) {
				if (z < -24 || z > -16) {
					this.set(43, 1, z, white);
				}
				this.set(53, 1, z, white);
			}
			for (int x = 44; x <= 52; x += 2) {
				this.set(x, 1, -64, white.setValue(de.rcm.ballistic.runway.RunwayLightBlock.COLOR, 1));
				this.set(x, 1, 64, white.setValue(de.rcm.ballistic.runway.RunwayLightBlock.COLOR, 2));
			}
			this.pole(42, -27, 6, Direction.EAST);
			this.pole(42, -15, 6, Direction.EAST);
		}

		/** A deep shelter: a blockhouse over a ladder shaft down to a hall ten metres under the yard. */
		void deepBunker() {
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			this.fill(9, 0, -31, 31, 0, -8, Blocks.SMOOTH_STONE);
			// blockhouse
			this.walls(15, 1, -23, 23, 4, -15, concrete);
			this.fill(15, 5, -23, 23, 5, -15, concrete);
			this.air(16, 1, -22, 22, 4, -16);
			this.door(19, 1, -15, ModRegistry.BLAST_DOOR, Direction.NORTH, DoorHingeSide.LEFT);
			this.door(20, 1, -15, ModRegistry.BLAST_DOOR, Direction.NORTH, DoorHingeSide.RIGHT);
			this.set(19, 5, -18, Blocks.SEA_LANTERN);
			this.fill(19, 0, -14, 20, 0, -9, ModRegistry.RUNWAY_ASPHALT);
			this.floodlight(18, 5, -14, Direction.SOUTH);
			this.floodlight(21, 5, -14, Direction.SOUTH);
			this.walls(15, 6, -23, 23, 6, -15, BaseRegistry.SANDBAGS.defaultBlockState());
			// the hall
			this.fill(8, -12, -31, 30, -7, -9, concrete);
			this.air(9, -11, -30, 29, -8, -10);
			// shaft
			this.fill(18, -6, -22, 20, 0, -20, concrete);
			this.air(19, -11, -20, 19, 0, -20);
			this.fill(19, -11, -21, 19, -8, -21, concrete);
			this.ladder(19, -11, 0, -20, Direction.SOUTH);
			// fittings
			this.set(19, -11, -29, this.facing(ModRegistry.COMMAND_CENTER, Direction.SOUTH));
			this.set(28, -11, -30, this.facing(ModRegistry.EMERGENCY_GENERATOR, Direction.WEST).setValue(BlockStateProperties.LIT, true));
			this.set(29, -11, -29, this.facing(ModRegistry.AIR_FILTER, Direction.WEST));
			for (int z = -29; z <= -13; z += 2) {
				this.bed(9, -11, z, Direction.WEST);
			}
			for (int x = 24; x <= 28; x++) {
				this.set(x, -11, -11, BaseRegistry.MILITARY_CRATE);
				if (this.hash(x, 0, 11) < 0.6F) {
					this.set(x, -10, -11, BaseRegistry.MILITARY_CRATE);
				}
			}
			this.barrel(29, -11, -12, Direction.UP);
			this.barrel(29, -11, -13, Direction.UP);
			this.set(14, -11, -22, Blocks.CARTOGRAPHY_TABLE);
			for (int x = 11; x <= 28; x += 4) {
				for (int z = -28; z <= -11; z += 4) {
					this.set(x, -7, z, Blocks.SEA_LANTERN);
				}
			}
		}

		/** A gun on a concrete pad behind a ring of sandbags. */
		void defence(int x, int z, Block gun) {
			this.fill(x - 1, 0, z - 1, x + 1, 0, z + 1, ModRegistry.REINFORCED_CONCRETE);
			this.set(x, 1, z, gun);
			for (int t = -2; t <= 2; t++) {
				if (t != 0) {
					this.set(x + t, 1, z - 2, BaseRegistry.SANDBAGS);
					this.set(x + t, 1, z + 2, BaseRegistry.SANDBAGS);
					this.set(x - 2, 1, z + t, BaseRegistry.SANDBAGS);
					this.set(x + 2, 1, z + t, BaseRegistry.SANDBAGS);
				}
			}
		}

		/** A small tin control hut (5 x 4) with its door on the south side. */
		void hut(int x, int z) {
			this.fill(x, 0, z - 3, x + 5, 0, z, Blocks.SMOOTH_STONE);
			this.walls(x, 1, z - 3, x + 5, 3, z, BaseRegistry.CORRUGATED_GREY.defaultBlockState());
			this.air(x + 1, 1, z - 2, x + 4, 3, z - 1);
			this.fill(x - 1, 4, z - 4, x + 6, 4, z + 1, BaseRegistry.CORRUGATED_OLIVE);
			this.door(x + 2, 1, z, Blocks.IRON_DOOR, Direction.NORTH, DoorHingeSide.LEFT);
			this.barrel(x + 4, 1, z - 2, Direction.UP);
			this.set(x + 1, 1, z - 2, BaseRegistry.MILITARY_CRATE);
			this.set(x + 3, 3, z - 2, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			this.set(x + 1, 2, z, Blocks.GLASS_PANE.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true));
			this.set(x + 4, 2, z, Blocks.GLASS_PANE.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true));
		}

		/** A small stone plinth for the flag (the flag stands on it at y = 2). */
		void plinth(int x, int z) {
			this.fill(x - 1, 1, z - 1, x + 1, 1, z + 1, Blocks.POLISHED_ANDESITE);
			this.set(x - 1, 1, z - 1, Blocks.POLISHED_ANDESITE_SLAB);
			this.set(x + 1, 1, z - 1, Blocks.POLISHED_ANDESITE_SLAB);
			this.set(x - 1, 1, z + 1, Blocks.POLISHED_ANDESITE_SLAB);
			this.set(x + 1, 1, z + 1, Blocks.POLISHED_ANDESITE_SLAB);
		}

		/** A sandbagged gun nest (3 x 3, open at the back) with a guard in it. */
		void nest(int cx, int cz, Direction back) {
			this.walls(cx - 1, 1, cz - 1, cx + 1, 1, cz + 1, BaseRegistry.SANDBAGS.defaultBlockState());
			this.set(cx + back.getStepX(), 1, cz + back.getStepZ(), Blocks.AIR);
			Direction front = back.getOpposite();
			for (int t = -1; t <= 1; t++) {
				int x = cx + front.getStepX() + (front.getStepX() == 0 ? t : 0);
				int z = cz + front.getStepZ() + (front.getStepZ() == 0 ? t : 0);
				this.set(x, 2, z, BaseRegistry.SANDBAGS);
			}
			this.guard(cx, 1, cz, 0.0F);
		}

		/**
		 * A road checkpoint: an asphalt road through a gate of concrete pillars, a chicane of barriers, a guard
		 * hut, two gun nests, a watchtower, blast walls and razor wire either side.
		 */
		void checkpoint() {
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			this.fill(-3, 0, -26, 3, 0, 26, ModRegistry.RUNWAY_ASPHALT);
			for (int z = -24; z <= 24; z += 4) {
				this.fill(0, 0, z, 0, 0, z + 1, ModRegistry.RUNWAY_MARKING);
			}
			// the gate
			for (int s = -1; s <= 1; s += 2) {
				this.fill(s * 5, 1, 0, s * 5, 5, 0, concrete);
			}
			this.fill(-5, 6, 0, 5, 6, 0, BaseRegistry.CORRUGATED_OLIVE);
			this.floodlight(-3, 7, 0, Direction.NORTH);
			this.floodlight(3, 7, 0, Direction.SOUTH);
			this.alarm(0, 7, 0);
			// the chicane: staggered barriers on both approaches
			BlockState jersey = this.facing(BaseRegistry.JERSEY_BARRIER, Direction.NORTH);
			this.fill(-3, 1, -7, 0, 1, -7, jersey);
			this.fill(0, 1, -12, 3, 1, -12, jersey);
			this.fill(0, 1, 7, 3, 1, 7, jersey);
			this.fill(-3, 1, 12, 0, 1, 12, jersey);
			// guard hut east of the road
			this.fill(7, 0, -3, 11, 0, 2, Blocks.SMOOTH_STONE);
			this.walls(7, 1, -3, 11, 3, 2, BaseRegistry.CORRUGATED_OLIVE.defaultBlockState());
			this.air(8, 1, -2, 10, 3, 1);
			BlockState paneZ = Blocks.GLASS_PANE.defaultBlockState().setValue(IronBarsBlock.NORTH, true).setValue(IronBarsBlock.SOUTH, true);
			this.fill(7, 2, -2, 7, 2, -1, paneZ);
			this.door(7, 1, 1, Blocks.SPRUCE_DOOR, Direction.EAST, DoorHingeSide.LEFT);
			this.fill(6, 4, -4, 12, 4, 3, BaseRegistry.CORRUGATED_GREY);
			this.set(9, 3, -1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			this.barrel(10, 1, -2, Direction.UP);
			this.set(10, 1, 1, BaseRegistry.MILITARY_CRATE);
			this.barracks = this.at(6, 1, 1);
			this.guard(9, 1, 0, 1.5F);
			// gun nests covering both approaches, a watchtower behind the hut
			this.nest(-8, -8, Direction.SOUTH);
			this.nest(-8, 8, Direction.NORTH);
			this.tower(13, 11, 1, 1);
			this.guard(14, 8, 12, 0.0F);
			// blast walls and wire either side of the road
			for (int s = -1; s <= 1; s += 2) {
				this.fill(s * 18, 1, -14, s * 18, 2, -4, BaseRegistry.HESCO);
				this.fill(s * 18, 1, 4, s * 18, 2, 7, BaseRegistry.HESCO);
				this.fill(s * 6, 1, -20, s * 18, 1, -20, BaseRegistry.BARBED_WIRE);
				this.fill(s * 6, 1, 20, s * 18, 1, 20, BaseRegistry.BARBED_WIRE);
			}
			// stores and lights
			for (int z = 4; z <= 6; z++) {
				this.set(11, 1, z, BaseRegistry.FUEL_DRUM);
			}
			this.set(12, 1, 5, BaseRegistry.FUEL_DRUM);
			this.set(-11, 1, -1, BaseRegistry.MILITARY_CRATE);
			this.set(-11, 1, 1, BaseRegistry.MILITARY_CRATE);
			this.set(-11, 2, 1, BaseRegistry.MILITARY_CRATE);
			this.pole(-5, -14, 6, Direction.NORTH);
			this.pole(5, 14, 6, Direction.SOUTH);
			this.guard(0, 1, -16, 3.0F);
			this.guard(0, 1, 16, 3.0F);
			this.guard(-6, 1, 3, 2.0F);
		}

		/**
		 * A surface-to-air missile site: a radar on a concrete mound, two launchers and a CIWS in sandbagged pits,
		 * a command hut and the generator that feeds it all, inside a ring of blast walls and wire.
		 */
		void samSite() {
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			// perimeter: blast walls with a way in on the south, razor wire outside
			this.walls(-19, 1, -19, 19, 2, 19, BaseRegistry.HESCO.defaultBlockState());
			this.air(-2, 1, 19, 2, 2, 19);
			this.fill(-2, 0, 19, 2, 0, 25, ModRegistry.RUNWAY_ASPHALT);
			for (int t = -21; t <= 21; t++) {
				if (Math.abs(t) > 3) {
					this.set(t, 1, 21, BaseRegistry.BARBED_WIRE);
				}
				this.set(t, 1, -21, BaseRegistry.BARBED_WIRE);
				this.set(-21, 1, t, BaseRegistry.BARBED_WIRE);
				this.set(21, 1, t, BaseRegistry.BARBED_WIRE);
			}
			// the radar on its mound
			this.fill(-2, 1, -10, 2, 2, -6, concrete);
			this.set(0, 3, -8, ModRegistry.RADAR);
			this.ladder(0, 1, 2, -5, Direction.SOUTH);
			this.floodlight(-2, 3, -10, Direction.NORTH);
			this.floodlight(2, 3, -10, Direction.EAST);
			// the guns
			this.defence(-10, 4, ModRegistry.AIR_DEFENSE);
			this.defence(10, 4, ModRegistry.AIR_DEFENSE);
			this.defence(10, -12, ModRegistry.CIWS);
			this.fill(-12, 4, 2, -8, 4, 6, BaseRegistry.CAMO_NET);
			this.fill(8, 4, 2, 12, 4, 6, BaseRegistry.CAMO_NET);
			// command hut and generator
			this.hut(-16, -12);
			this.barracks = this.at(-14, 1, -11);
			this.fill(13, 0, 12, 17, 0, 16, Blocks.SMOOTH_STONE);
			this.walls(12, 1, 11, 18, 1, 17, BaseRegistry.SANDBAGS.defaultBlockState());
			this.air(12, 1, 14, 12, 1, 14);
			this.set(15, 1, 14, this.facing(BaseRegistry.FIELD_GENERATOR, Direction.WEST));
			this.generators.add(this.at(15, 1, 14));
			this.set(16, 1, 16, BaseRegistry.FUEL_DRUM);
			this.set(17 - 1, 1, 12, BaseRegistry.FUEL_DRUM);
			// ammunition under a net
			for (int x = 5; x <= 8; x++) {
				this.set(x, 1, 14, BaseRegistry.MILITARY_CRATE);
				if (this.hash(x, 14, 4) < 0.5F) {
					this.set(x, 2, 14, BaseRegistry.MILITARY_CRATE);
				}
			}
			this.fill(4, 3, 13, 9, 3, 15, BaseRegistry.CAMO_NET);
			this.fill(-1, 0, -5, 1, 0, 18, Blocks.GRAVEL);
			this.pole(-17, 17, 6, Direction.EAST);
			this.pole(17, -17, 6, Direction.WEST);
			this.pole(-17, -2, 6, Direction.EAST);
			this.guard(0, 3, -9, 0.0F);
			this.guard(-6, 1, 0, -1.0F);
			this.guard(6, 1, 10, 4.0F);
			this.guard(0, 1, 17, 2.0F);
			this.guard(-14, 1, -9, 2.0F);
		}

		/**
		 * A hidden weapons cache: a small concrete blockhouse under camouflage, a ladder down to a store room
		 * stacked with crates, guarded by a few men.
		 */
		void cache() {
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			// the blockhouse
			this.walls(-3, 1, -3, 3, 3, 3, concrete);
			this.fill(-3, 4, -3, 3, 4, 3, concrete);
			this.air(-2, 1, -2, 2, 3, 2);
			this.door(0, 1, 3, ModRegistry.BLAST_DOOR, Direction.NORTH, DoorHingeSide.LEFT);
			this.walls(-3, 5, -3, 3, 5, 3, BaseRegistry.SANDBAGS.defaultBlockState());
			this.fill(-5, 6, -5, 5, 6, 5, BaseRegistry.CAMO_NET);
			for (int sx = -5; sx <= 5; sx += 10) {
				for (int sz = -5; sz <= 5; sz += 10) {
					this.fill(sx, 1, sz, sx, 5, sz, Blocks.SPRUCE_FENCE);
				}
			}
			this.fill(-2, 1, 4, -1, 2, 4, BaseRegistry.SANDBAGS);
			this.fill(1, 1, 4, 2, 2, 4, BaseRegistry.SANDBAGS);
			this.set(1, 3, 4, Blocks.AIR);
			this.floodlight(-1, 3, 4, Direction.SOUTH);
			this.set(0, 3, -1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			// the shaft and the store room under it
			this.fill(-7, -11, -9, 7, -6, 0, concrete);
			this.air(-6, -10, -8, 6, -7, -1);
			this.fill(-1, -5, -2, 1, 0, 0, concrete);
			this.fill(0, -10, -2, 0, -7, -2, concrete);
			this.air(0, -10, -1, 0, 0, -1);
			this.ladder(0, -10, 0, -1, Direction.SOUTH);
			for (int x = -5; x <= 5; x++) {
				for (int z = -8; z <= -6; z++) {
					if (x == 0 && z == -6) {
						continue;
					}
					if ((x + 6) % 3 != 2) {
						this.set(x, -10, z, BaseRegistry.MILITARY_CRATE);
						if (this.hash(x, z, 6) < 0.55F) {
							this.set(x, -9, z, BaseRegistry.MILITARY_CRATE);
						}
					}
				}
			}
			this.barrel(-6, -10, -3, Direction.UP);
			this.barrel(6, -10, -3, Direction.UP);
			this.barrel(-6, -10, -4, Direction.UP);
			this.set(5, -10, -3, BaseRegistry.FUEL_DRUM);
			this.set(4, -10, -3, BaseRegistry.FUEL_DRUM);
			for (int x = -4; x <= 4; x += 4) {
				this.set(x, -6, -5, Blocks.SEA_LANTERN);
			}
			// outside: sandbags, a fuel stack, a lamp post
			this.nest(-9, 6, Direction.NORTH);
			this.fill(8, 1, -6, 9, 1, -4, BaseRegistry.FUEL_DRUM);
			this.pole(9, 2, 5, Direction.WEST);
			this.barracks = this.at(0, 1, 5);
			this.guard(2, 1, 7, 3.0F);
			this.guard(-1, 1, 0, 1.5F);
			this.guard(0, -10, -4, 3.0F);
		}

		/** The guards: in the towers, the gun nests, round the yard and at each site. */
		void garrison() {
			for (int sx = -1; sx <= 1; sx += 2) {
				for (int sz = -1; sz <= 1; sz += 2) {
					this.guard(sx * 31 + 1, 8, sz * 31 + 1, 0.0F);
				}
				this.guard(sx * 13, 1, 31, 0.0F);
			}
			this.guard(8, 1, 32, 2.0F);
			this.guard(4, 1, 5, -1.0F);
			this.guard(-5, 1, -4, -1.0F);
			this.guard(-18, 1, -15, 3.0F);
			this.guard(19, 1, 23, 4.0F);
			this.guard(-11, 1, 20, 3.0F);
			switch (MilitaryBasePiece.this.variant) {
				case RADAR -> {
					this.guard(20, 1, -14, 6.0F);
					this.guard(20, 4, -21, 0.0F);
				}
				case SILO -> {
					this.guard(20, 1, -18, 6.0F);
					this.guard(20, 1, -27, 4.0F);
				}
				case AIRFIELD -> {
					this.guard(20, 1, -15, 5.0F);
					this.guard(48, 1, -10, -1.0F);
				}
				default -> {
					this.guard(19, 1, -12, 3.0F);
					this.guard(19, -11, -24, 6.0F);
					this.guard(24, -11, -15, 5.0F);
				}
			}
		}

		/** The flag, set up with all the base's lights, beacons and generators. */
		void flag() {
			int[] f = flagAt(MilitaryBasePiece.this.variant);
			if (!this.inside(f[0], 2, f[1])) {
				return;
			}
			this.set(f[0], 2, f[1], BaseRegistry.BASE_FLAG);
			if (this.level.getBlockEntity(this.at(f[0], 2, f[1])) instanceof BaseFlagBlockEntity flag) {
				flag.setup(MilitaryBasePiece.this.variant, this.lights, this.alarms, this.generators, this.barracks);
			}
		}
	}
}
