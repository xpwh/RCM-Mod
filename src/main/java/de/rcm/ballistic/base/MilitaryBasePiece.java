package de.rcm.ballistic.base;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.ai.SoldierEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
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
	/** The large set pieces: a carrier at sea, a ruined town, a crash site, a submarine pen, a secret lab, a trench line. */
	public static final int CARRIER = 13;
	public static final int WARZONE = 14;
	public static final int CRASH = 15;
	public static final int SUB_PEN = 16;
	public static final int LAB = 17;
	public static final int TRENCH = 18;
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
			case CARRIER -> new int[] {-16, 16, -71, 67};
			case WARZONE -> new int[] {-40, 40, -40, 40};
			case CRASH -> new int[] {-20, 20, -24, 24};
			case SUB_PEN -> new int[] {-18, 18, -46, 22};
			case LAB -> new int[] {-20, 20, -20, 20};
			case TRENCH -> new int[] {-48, 48, -30, 30};
			default -> new int[] {-42, 42, -42, 50};
		};
	}

	/** Half the side of the gravelled yard. */
	private static int yard(int variant) {
		return switch (variant) {
			case CHECKPOINT -> 17;
			case SAM_SITE -> 20;
			case CACHE -> 12;
			case LAB -> 10;
			case SUB_PEN -> 16;
			case WARZONE, CRASH, TRENCH, CARRIER -> -1;
			default -> F;
		};
	}

	/** Where the flag stands (local x, z). */
	private static int[] flagAt(int variant) {
		return switch (variant) {
			case CHECKPOINT -> new int[] {-9, 0};
			case SAM_SITE -> new int[] {-12, 12};
			case CACHE -> new int[] {7, 7};
			case CARRIER -> new int[] {5, 16};
			case WARZONE -> new int[] {6, 6};
			case CRASH -> new int[] {9, -7};
			case SUB_PEN -> new int[] {10, 6};
			case LAB -> new int[] {9, -9};
			case TRENCH -> new int[] {-12, 14};
			default -> new int[] {0, 0};
		};
	}

	/** The height the flag stands at (its plinth is one below). */
	private static int flagY(int variant) {
		return variant == CARRIER ? 9 : 2;
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
			s.setPost(v, radius, this.at(f[0], flagY(MilitaryBasePiece.this.variant), f[1]));
			this.level.addFreshEntity(s);
		}

		// ------------------------------------------------------------------ the plan

		void run() {
			if (MilitaryBasePiece.this.variant >= CHECKPOINT) {
				if (MilitaryBasePiece.this.variant != CARRIER) {
					this.terrain();
				}
				switch (MilitaryBasePiece.this.variant) {
					case CHECKPOINT -> this.checkpoint();
					case SAM_SITE -> this.samSite();
					case CARRIER -> this.carrier();
					case WARZONE -> this.warzone();
					case CRASH -> this.crash();
					case SUB_PEN -> this.subPen();
					case LAB -> this.lab();
					case TRENCH -> this.trench();
					default -> this.cache();
				}
				int[] f = flagAt(MilitaryBasePiece.this.variant);
				this.plinth(f[0], flagY(MilitaryBasePiece.this.variant) - 1, f[1]);
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
			this.details();
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
					if (MilitaryBasePiece.this.variant == SUB_PEN && lz < -15) {
						this.channel(wx, wz, lx, oy);
						continue;
					}
					this.column(wx, wz, lx, lz, oy);
				}
			}
		}

		/** Off the pen's sea face: the channel to the sea is dredged to seven deep; the shore either side is left alone. */
		private void channel(int wx, int wz, int lx, int oy) {
			if (Math.abs(lx) > 5) {
				return;
			}
			for (int y = oy - 7; y <= oy + 26; y++) {
				BlockState s = this.level.getBlockState(this.m.set(wx, y, wz));
				BlockState want = y < oy ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
				if (s != want && (y < oy || !s.isAir())) {
					this.level.setBlock(this.m, want, Block.UPDATE_CLIENTS);
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
			boolean yard = yr >= 0 && Math.abs(lx) <= yr && Math.abs(lz) <= yr;
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

		/** A small stone plinth for the flag (the flag stands on it, one above). */
		void plinth(int x, int y, int z) {
			this.fill(x - 1, y, z - 1, x + 1, y, z + 1, Blocks.POLISHED_ANDESITE);
			this.set(x - 1, y, z - 1, Blocks.POLISHED_ANDESITE_SLAB);
			this.set(x + 1, y, z - 1, Blocks.POLISHED_ANDESITE_SLAB);
			this.set(x - 1, y, z + 1, Blocks.POLISHED_ANDESITE_SLAB);
			this.set(x + 1, y, z + 1, Blocks.POLISHED_ANDESITE_SLAB);
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

		// ------------------------------------------------------------------ helpers for the larger set pieces

		/** A standing sign with a two-line warning on both faces, in red. */
		void sign(int x, int y, int z, int rotation, String key) {
			this.set(x, y, z, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rotation));
			if (this.inside(x, y, z) && this.level.getBlockEntity(this.at(x, y, z)) instanceof SignBlockEntity sign) {
				SignText text = new SignText().setMessage(1, Component.translatable(key + ".1")).setMessage(2, Component.translatable(key + ".2"))
					.setColor(DyeColor.RED);
				sign.setText(text, true);
				sign.setText(text, false);
			}
		}

		/** A column of smoke: a smouldering fire on a bale (a signal fire's smoke climbs high). */
		void smoke(int x, int y, int z) {
			this.set(x, y - 1, z, Blocks.HAY_BLOCK);
			this.set(x, y, z, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, true));
		}

		/** Open flame that keeps burning (on netherrack). */
		void blaze(int x, int y, int z) {
			this.set(x, y - 1, z, Blocks.NETHERRACK);
			this.set(x, y, z, Blocks.FIRE);
		}

		/** A barrel with the keycard in it (and nothing else). */
		void keycard(int x, int y, int z) {
			this.set(x, y, z, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
			if (this.inside(x, y, z) && this.level.getBlockEntity(this.at(x, y, z)) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity b) {
				b.setItem(13, new ItemStack(BaseRegistry.KEYCARD));
			}
		}

		/** A shell crater: a scorched bowl, earth thrown up round the rim, ash on it. */
		void crater(int cx, int cz, float r, int salt) {
			int ir = (int) Math.ceil(r) + 2;
			float depth = r * 0.55F;
			for (int dx = -ir; dx <= ir; dx++) {
				for (int dz = -ir; dz <= ir; dz++) {
					int x = cx + dx;
					int z = cz + dz;
					float rr = r * (0.85F + 0.3F * this.patch(x, z, 2, salt));
					float d = Mth.sqrt(dx * dx + dz * dz);
					if (d <= rr) {
						float u = d / rr;
						int h = Math.max(1, Math.round(depth * (1.0F - u * u)));
						this.air(x, 1 - h, z, x, 1, z);
						this.set(x, -h, z, this.hash(x, z, salt + 1) < 0.18F ? ModRegistry.SMOLDERING_EARTH : ModRegistry.SCORCHED_EARTH);
					} else if (d <= rr + 1.6F) {
						this.set(x, 0, z, this.hash(x, z, salt + 2) < 0.55F ? ModRegistry.SCORCHED_EARTH.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
						if (this.hash(x, z, salt + 3) < 0.4F) {
							this.set(x, 1, z, ModRegistry.ASH);
						}
					}
				}
			}
		}

		/** A burnt-out car, 2 x 4, along z (or x): a blackened shell, the cabin's frame, half its roof. */
		void wreck(int x, int z, boolean alongZ, int salt) {
			BlockState slab = Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState();
			for (int i = 0; i < 4; i++) {
				for (int j = 0; j < 2; j++) {
					int px = alongZ ? x + j : x + i;
					int pz = alongZ ? z + i : z + j;
					float h = this.hash(px, pz, salt);
					this.set(px, 1, pz, h < 0.4F ? Blocks.BLACKSTONE : h < 0.75F ? Blocks.POLISHED_BLACKSTONE : Blocks.BROWN_TERRACOTTA);
					if (i == 0 || i == 3) {
						this.set(px, 2, pz, slab);
					} else {
						this.set(px, 2, pz, Blocks.IRON_BARS);
						if (this.hash(px, pz, salt + 1) < 0.6F) {
							this.set(px, 3, pz, slab);
						}
					}
				}
			}
		}

		/**
		 * A ruined building, x0..x1 by z0..z1, {@code floors} storeys of four: walls broken off along a ragged
		 * line and blown through by shells, the floors fallen in, the windows empty, rubble inside and spilled
		 * out into the street, and a ladder up through it all. With {@code keepTop} the top floor stands.
		 */
		void ruin(int x0, int z0, int x1, int z1, int floors, BlockState wall, BlockState dark, int salt, boolean keepTop, int doorX, int doorZ) {
			int top = floors * 4;
			for (int k = 0; k <= floors; k++) {
				int y = k * 4;
				for (int x = x0 + 1; x < x1; x++) {
					for (int z = z0 + 1; z < z1; z++) {
						if (k == 0) {
							this.set(x, 0, z, this.hash(x, z, salt) < 0.5F ? Blocks.STONE_BRICKS : Blocks.CRACKED_STONE_BRICKS);
						} else if (k < floors && (this.patch(x, z, 3, salt + k) < 0.6F || keepTop && k == floors - 1)) {
							this.set(x, y, z, this.hash(x, z, salt + k) < 0.15F ? dark : wall);
						}
					}
				}
			}
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					if (x != x0 && x != x1 && z != z0 && z != z1) {
						continue;
					}
					boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
					int along = z == z0 || z == z1 ? x - x0 : z - z0;
					int h = keepTop ? top - (int) (this.hash(x, z, salt + 7) * 2.0F)
						: corner ? top - (int) (this.patch(x, z, 3, salt) * top * 0.6F)
						: top - (int) (this.patch(x, z, 3, salt) * top * 0.95F);
					for (int y = 1; y <= h; y++) {
						boolean window = !corner && (y % 4 == 2 || y % 4 == 3) && along % 3 == 1 && y > 3;
						boolean door = y <= 2 && (z == doorZ && x == x0 + (x1 - x0) / 2 || x == doorX && z == z0 + (z1 - z0) / 2);
						if (window || door) {
							continue;
						}
						this.set(x, y, z, this.hash(x * 7 + y, z, salt + 3) < 0.13F ? dark : wall);
					}
				}
			}
			// shell holes
			int holes = 1 + (int) (this.hash(x0, z0, salt + 5) * 3.0F);
			for (int i = 0; i < holes; i++) {
				float a = this.hash(i, salt, 81);
				boolean xWall = this.hash(i, salt, 82) < 0.5F;
				int hx = xWall ? (this.hash(i, salt, 83) < 0.5F ? x0 : x1) : x0 + (int) (a * (x1 - x0));
				int hz = xWall ? z0 + (int) (a * (z1 - z0)) : (this.hash(i, salt, 83) < 0.5F ? z0 : z1);
				int hy = 2 + (int) (this.hash(i, salt, 84) * Math.max(1, top - 4));
				float r = 1.8F + this.hash(i, salt, 85) * 1.8F;
				int ir = (int) Math.ceil(r);
				for (int dx = -ir; dx <= ir; dx++) {
					for (int dy = -ir; dy <= ir; dy++) {
						for (int dz = -ir; dz <= ir; dz++) {
							int x = hx + dx;
							int z = hz + dz;
							if (dx * dx + dy * dy + dz * dz <= r * r && x >= x0 && x <= x1 && z >= z0 && z <= z1 && hy + dy >= 1) {
								this.set(x, hy + dy, z, Blocks.AIR);
							}
						}
					}
				}
			}
			// a ladder all the way up, with its wall behind it kept standing
			this.fill(x0, 1, z0 + 1, x0, top, z0 + 1, wall);
			this.ladder(x0 + 1, 1, top - 1, z0 + 1, Direction.EAST);
			// rubble: heaps inside and spilled out round the walls
			for (int x = x0 - 3; x <= x1 + 3; x++) {
				for (int z = z0 - 3; z <= z1 + 3; z++) {
					boolean in = x > x0 && x < x1 && z > z0 && z < z1;
					boolean wallCell = x >= x0 && x <= x1 && z >= z0 && z <= z1 && !in;
					if (wallCell || in && x == x0 + 1 && z == z0 + 1) {
						continue;
					}
					float p = this.patch(x, z, 2, salt + 9);
					float edge = in ? 0.0F : Math.max(Math.max(x0 - x, x - x1), Math.max(z0 - z, z - z1)) * 0.08F;
					if (p - edge > 0.55F) {
						float h = this.hash(x, z, salt + 10);
						BlockState rubble = h < 0.3F ? Blocks.GRAVEL.defaultBlockState() : h < 0.55F ? Blocks.COBBLESTONE.defaultBlockState()
							: h < 0.75F ? wall : Blocks.ANDESITE.defaultBlockState();
						this.set(x, 1, z, rubble);
						if (p - edge > 0.72F) {
							this.set(x, 2, z, h < 0.5F ? Blocks.GRAVEL : Blocks.COBBLESTONE_SLAB);
						}
					}
				}
			}
		}

		// ------------------------------------------------------------------ the aircraft carrier

		/** Half the carrier's beam at the deck, along her length (bow at -z). */
		private int beam(int z) {
			if (z < -52) {
				return Math.max(1, Math.round(11.0F * (float) Math.pow((z + 71) / 19.0, 0.55)));
			}
			return 11;
		}

		/**
		 * A carrier at anchor: a grey hull with a red bottom and a black boot-top at the waterline, a hangar the
		 * length of her, the flight deck with its angled landing area, catapults and arresting wires, the island
		 * with the bridge, radar and mast. Jets on deck and in the hangar, guns on the corners, guards on watch.
		 */
		void carrier() {
			BlockState hull = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
			BlockState grey = Blocks.GRAY_CONCRETE.defaultBlockState();
			BlockState red = Blocks.RED_TERRACOTTA.defaultBlockState();
			BlockState boot = Blocks.BLACK_CONCRETE.defaultBlockState();
			BlockState deck = ModRegistry.RUNWAY_ASPHALT.defaultBlockState();
			for (int z = -70; z <= 66; z++) {
				int w = this.beam(z);
				for (int y = -7; y <= 7; y++) {
					int hw = y < 0 ? Math.max(1, w - Math.round(-y * 0.7F)) : w;
					BlockState skin = y < -1 ? red : y < 1 ? boot : hull;
					// the hull's skin
					this.fill(-hw, y, z, -hw, y, z, skin);
					this.fill(hw, y, z, hw, y, z, skin);
					boolean hangar = y >= 1 && z >= -40 && z <= 50;
					if (hangar) {
						this.air(-hw + 2, y, z, hw - 2, y, z);
						this.fill(-hw + 1, y, z, -hw + 1, y, z, grey);
						this.fill(hw - 1, y, z, hw - 1, y, z, grey);
					} else {
						this.fill(-hw + 1, y, z, hw - 1, y, z, y == 0 && z >= -40 && z <= 50 ? grey : y < -1 ? red : grey);
					}
				}
				// the flight deck
				int port = z >= -6 && z <= 54 ? -w - 4 : -w;
				int star = z >= -8 && z <= 14 ? w + 3 : w;
				this.fill(port, 8, z, star, 8, z, deck);
				if (port < -w) {
					this.fill(port, 7, z, -w - 1, 7, z, grey);
				}
				if (star > w) {
					this.fill(w + 1, 7, z, star, 7, z, grey);
				}
			}
			// the angled deck's centreline and foul lines, the catapults, the wires
			for (int z = -6; z <= 54; z++) {
				int cx = Math.round(-2.0F - (z + 6) * 0.13F);
				if (Math.floorMod(z, 6) < 3) {
					this.set(cx, 8, z, ModRegistry.RUNWAY_MARKING);
				}
				this.set(cx - 6, 8, z, Blocks.YELLOW_CONCRETE);
				this.set(cx + 6, 8, z, Blocks.YELLOW_CONCRETE);
			}
			for (int x = -4; x <= 4; x += 8) {
				this.fill(x, 8, -66, x, 8, -30, BaseRegistry.STEEL_GRATE);
				this.fill(x - 2, 9, -28, x + 2, 9, -28, Blocks.IRON_BLOCK);
			}
			BlockState wire = Blocks.IRON_CHAIN.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
			for (int z = 40; z <= 49; z += 3) {
				int cx = Math.round(-2.0F - (z + 6) * 0.13F);
				this.fill(cx - 5, 9, z, cx + 5, 9, z, wire);
			}
			BlockState edgeLight = ModRegistry.RUNWAY_LIGHT.defaultBlockState();
			for (int z = -60; z <= 62; z += 8) {
				this.set(-this.beam(z) - (z >= -6 && z <= 54 ? 4 : 0), 9, z, edgeLight);
			}
			// the island: three decks of rooms, the bridge on top with windows all round, radar and mast above
			int x0 = 9;
			int x1 = 13;
			int z0 = -6;
			int z1 = 12;
			this.walls(x0, 9, z0, x1, 21, z1, hull);
			this.fill(x0, 21, z0, x1, 21, z1, hull);
			this.air(x0 + 1, 9, z0 + 1, x1 - 1, 20, z1 - 1);
			this.fill(x0 + 1, 13, z0 + 1, x1 - 1, 13, z1 - 1, grey);
			this.fill(x0 + 1, 17, z0 + 1, x1 - 1, 17, z1 - 1, grey);
			BlockState glass = Blocks.GRAY_STAINED_GLASS.defaultBlockState();
			this.walls(x0, 19, z0, x1, 19, z1, glass);
			this.fill(x0, 18, z0, x1, 18, z0, glass);
			for (int z = z0 + 2; z < z1; z += 3) {
				this.set(x0, 11, z, glass);
				this.set(x0, 15, z, glass);
			}
			this.door(x0, 9, 2, Blocks.IRON_DOOR, Direction.WEST, DoorHingeSide.LEFT);
			this.set(x0, 10, 4, Blocks.STONE_BUTTON.defaultBlockState().setValue(net.minecraft.world.level.block.ButtonBlock.FACING, Direction.WEST));
			this.ladder(x1 - 1, 9, 20, z1 - 1, Direction.WEST);
			this.set(x1 - 1, 13, z1 - 1, Blocks.AIR);
			this.set(x1 - 1, 17, z1 - 1, Blocks.AIR);
			this.ladder(x1 - 1, 13, 13, z1 - 1, Direction.WEST);
			this.ladder(x1 - 1, 17, 17, z1 - 1, Direction.WEST);
			// the bridge
			this.set(11, 18, -5, this.facing(ModRegistry.COMMAND_CENTER, Direction.NORTH));
			this.set(11, 18, -3, Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
			this.set(10, 18, -4, Blocks.CARTOGRAPHY_TABLE);
			this.set(12, 18, -4, Blocks.LECTERN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
			this.barrel(10, 18, 8, Direction.UP);
			this.set(11, 20, 3, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			this.set(11, 16, 3, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			this.set(11, 12, 3, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			for (int z = z0 + 1; z <= z1 - 3; z += 3) {
				this.bed(x1 - 1, 14, z, Direction.NORTH);
			}
			this.barrel(10, 10, 9, Direction.UP);
			this.set(10, 10, -5, BaseRegistry.MILITARY_CRATE);
			// on the roof
			this.set(11, 22, 1, ModRegistry.RADAR);
			this.fill(11, 22, 7, 11, 27, 7, Blocks.IRON_BARS);
			this.set(11, 28, 7, Blocks.LIGHTNING_ROD);
			this.fill(10, 25, 7, 12, 25, 7, Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true));
			this.alarm(11, 22, 10);
			this.floodlight(x0, 22, z0, Direction.NORTH);
			this.floodlight(x0, 22, 3, Direction.WEST);
			this.floodlight(x0, 22, z1, Direction.SOUTH);
			// guns on the corners
			this.set(-9, 9, -54, ModRegistry.CIWS);
			this.set(9, 9, 62, ModRegistry.CIWS);
			this.set(-14, 9, 50, ModRegistry.AIR_DEFENSE);
			this.set(13, 9, -7, ModRegistry.CIWS);
			// the hangar: a ladder down from the deck, lights, stores
			this.fill(8, 1, -2, 8, 7, -2, grey);
			this.set(7, 8, -2, Blocks.AIR);
			this.ladder(7, 1, 8, -2, Direction.WEST);
			for (int z = -36; z <= 48; z += 7) {
				this.set(-4, 7, z, Blocks.SEA_LANTERN);
				this.set(4, 7, z, Blocks.SEA_LANTERN);
			}
			for (int z = 38; z <= 48; z += 2) {
				this.set(-8, 1, z, BaseRegistry.MILITARY_CRATE);
				if (this.hash(-8, z, 5) < 0.6F) {
					this.set(-8, 2, z, BaseRegistry.MILITARY_CRATE);
				}
				this.set(8, 1, z, BaseRegistry.FUEL_DRUM);
			}
			this.barrel(-8, 1, 30, Direction.UP);
			this.barrel(-8, 1, 31, Direction.UP);
			this.set(7, 1, 30, this.facing(ModRegistry.WEAPONS_WORKBENCH, Direction.WEST));
			this.set(-8, 1, -30, this.facing(BaseRegistry.FIELD_GENERATOR, Direction.EAST));
			this.generators.add(this.at(-8, 1, -30));
			this.barracks = this.at(0, 1, 20);
			// jets: two on the catapults, two parked aft of the island, one in the hangar
			this.jet(4, 9, -42, 180.0F, 1);
			this.jet(-4, 9, -48, 180.0F, 2);
			this.jet(6, 9, 24, 135.0F, 3);
			this.jet(6, 9, 36, 135.0F, 4);
			this.jet(0, 1, 6, 180.0F, 5);
			// the watch
			this.guard(0, 9, -20, 6.0F);
			this.guard(-8, 9, 10, 6.0F);
			this.guard(3, 9, 44, 6.0F);
			this.guard(11, 18, 0, 0.0F);
			this.guard(0, 1, -10, 8.0F);
			this.guard(-4, 1, 30, 6.0F);
			this.guard(-9, 9, -52, 0.0F);
		}

		/** A fighter parked at (x, y, z), nose to {@code yaw} (local: 180 north, -90 east). */
		void jet(int x, int y, int z, float yaw, int salt) {
			if (!this.inside(x, y, z) || this.level.getLevel() == null) {
				return;
			}
			var jet = de.rcm.ballistic.entity.FighterEntity.parked(this.level.getLevel(), Vec3.atBottomCenterOf(this.at(x, y, z)),
				yaw + MilitaryBasePiece.this.rot.ordinal() * 90.0F,
				this.hash(salt, 1, 3) < 0.5F ? de.rcm.ballistic.entity.FighterType.F35 : de.rcm.ballistic.entity.FighterType.F22);
			if (jet != null) {
				this.level.addFreshEntity(jet);
			}
		}

		// ------------------------------------------------------------------ the war zone

		/**
		 * A town fought over: two streets crossing, the blocks round them shelled to ruins - walls broken off,
		 * floors fallen in, rubble in the street - burnt-out cars, shell craters, fires still burning and smoke
		 * rising. Snipers hold the top floors of the two tallest ruins.
		 */
		void warzone() {
			// the streets and pavements, cracked and holed
			this.fill(-5, 0, -40, 5, 0, 40, Blocks.SMOOTH_STONE);
			this.fill(-40, 0, -5, 40, 0, 5, Blocks.SMOOTH_STONE);
			this.fill(-3, 0, -40, 3, 0, 40, ModRegistry.RUNWAY_ASPHALT);
			this.fill(-40, 0, -3, 40, 0, 3, ModRegistry.RUNWAY_ASPHALT);
			for (int t = -40; t <= 40; t++) {
				for (int w = -5; w <= 5; w++) {
					if (this.hash(t, w, 51) < 0.08F) {
						this.set(w, 0, t, this.hash(t, w, 52) < 0.5F ? Blocks.GRAVEL : Blocks.COARSE_DIRT);
					}
					if (this.hash(w, t, 53) < 0.08F) {
						this.set(t, 0, w, this.hash(w, t, 54) < 0.5F ? Blocks.GRAVEL : Blocks.COARSE_DIRT);
					}
				}
				if (Math.floorMod(t, 6) < 3 && Math.abs(t) > 6) {
					this.set(0, 0, t, ModRegistry.RUNWAY_MARKING);
					this.set(t, 0, 0, ModRegistry.RUNWAY_MARKING);
				}
			}
			// the blocks: four plots in each quarter; most a ruined building, some a crater field or burnt garden
			int[][] plots = {{7, 19, 7, 19}, {7, 19, 23, 37}, {23, 37, 7, 19}, {23, 37, 23, 37}};
			BlockState[][] stone = {
				{Blocks.BRICKS.defaultBlockState(), Blocks.BLACKSTONE.defaultBlockState()},
				{Blocks.STONE_BRICKS.defaultBlockState(), Blocks.CRACKED_STONE_BRICKS.defaultBlockState()},
				{Blocks.WHITE_CONCRETE.defaultBlockState(), Blocks.GRAY_CONCRETE.defaultBlockState()},
				{Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(), Blocks.GRAY_CONCRETE.defaultBlockState()},
				{Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.BROWN_TERRACOTTA.defaultBlockState()},
			};
			int[][] plan = new int[16][];
			int best = -1;
			int second = -1;
			for (int q = 0; q < 16; q++) {
				int qx = (q & 1) == 0 ? -1 : 1;
				int qz = (q & 2) == 0 ? -1 : 1;
				int[] p = plots[q >> 2];
				int salt = 200 + q * 13;
				int a = p[0] + (int) (this.hash(q, 1, salt) * 2.0F);
				int b = p[1] - (int) (this.hash(q, 2, salt) * 2.0F);
				int c = p[2] + (int) (this.hash(q, 3, salt) * 2.0F);
				int d = p[3] - (int) (this.hash(q, 4, salt) * 2.0F);
				int x0 = qx > 0 ? a : -b;
				int x1 = qx > 0 ? b : -a;
				int z0 = qz > 0 ? c : -d;
				int z1 = qz > 0 ? d : -c;
				float kind = this.hash(q, 5, salt);
				int floors = 2 + (int) (this.hash(q, 6, salt) * 4.0F);
				plan[q] = new int[] {x0, z0, x1, z1, kind < 0.74F ? 0 : kind < 0.87F ? 1 : 2, floors, salt, qx, qz};
				if (plan[q][4] == 0) {
					if (best < 0 || floors > plan[best][5]) {
						second = best;
						best = q;
					} else if (second < 0 || floors > plan[second][5]) {
						second = q;
					}
				}
			}
			for (int q = 0; q < 16; q++) {
				int[] p = plan[q];
				int x0 = p[0];
				int z0 = p[1];
				int x1 = p[2];
				int z1 = p[3];
				switch (p[4]) {
					case 0 -> {
						BlockState[] m = stone[(int) (this.hash(q, 7, p[6]) * stone.length)];
						this.ruin(x0, z0, x1, z1, p[5], m[0], m[1], p[6], q == best || q == second, p[7] > 0 ? x0 : x1, p[8] > 0 ? z0 : z1);
						this.barrel((x0 + x1) / 2, 1, (z0 + z1) / 2, Direction.UP);
					}
					case 1 -> {
						this.crater((x0 + x1) / 2, (z0 + z1) / 2, 4.2F, p[6]);
						this.crater(x0 + 2, z1 - 2, 2.4F, p[6] + 1);
					}
					default -> {
						for (int x = x0; x <= x1; x++) {
							for (int z = z0; z <= z1; z++) {
								float h = this.hash(x, z, p[6]);
								this.set(x, 0, z, h < 0.45F ? ModRegistry.SCORCHED_EARTH : Blocks.COARSE_DIRT);
								if (h > 0.8F) {
									this.set(x, 1, z, ModRegistry.ASH);
								}
								if (h < 0.025F) {
									this.fill(x, 1, z, x, 2 + (int) (this.hash(z, x, p[6]) * 3.0F), z, ModRegistry.CHARRED_LOG);
								}
							}
						}
					}
				}
			}
			// shell holes in the streets, burnt-out cars, a barricade
			this.crater(0, -26, 3.4F, 61);
			this.crater(-23, 1, 2.8F, 62);
			this.crater(19, -1, 2.4F, 63);
			this.wreck(-3, -35, true, 71);
			this.wreck(1, 13, true, 72);
			this.wreck(27, -3, false, 73);
			this.wreck(-32, 1, false, 74);
			this.wreck(-2, 30, true, 75);
			this.fill(-3, 1, -9, -1, 2, -9, BaseRegistry.SANDBAGS);
			this.fill(1, 1, -11, 3, 1, -11, this.facing(BaseRegistry.JERSEY_BARRIER, Direction.NORTH));
			this.fill(9, 1, -3, 9, 1, -1, this.facing(BaseRegistry.JERSEY_BARRIER, Direction.EAST));
			// fires that are still burning, and smoke
			this.blaze(2, 1, 14);
			this.blaze(-31, 1, 2);
			this.blaze(-1, 1, -24);
			this.smoke(-6, 1, -20);
			this.smoke(6, 1, 25);
			this.smoke(21, 1, -6);
			this.smoke(-20, 1, 6);
			// a supply point at the crossing
			this.set(4, 1, -5, BaseRegistry.MILITARY_CRATE);
			this.set(5, 1, -5, BaseRegistry.MILITARY_CRATE);
			this.set(5, 2, -5, BaseRegistry.MILITARY_CRATE);
			this.barrel(5, 1, -4, Direction.UP);
			this.set(-4, 1, 5, BaseRegistry.FUEL_DRUM);
			this.barracks = this.at(4, 1, 4);
			this.floodlight(-5, 1, -9, Direction.NORTH);
			// the snipers, and men in the streets
			for (int q : new int[] {best, second}) {
				if (q < 0) {
					continue;
				}
				int[] p = plan[q];
				int sx = p[7] > 0 ? p[0] + 1 : p[2] - 1;
				int sz = p[8] > 0 ? p[1] + 2 : p[3] - 2;
				this.guard(sx, (p[5] - 1) * 4 + 1, sz, 0.0F);
			}
			this.guard(-2, 1, -10, 0.0F);
			this.guard(0, 1, -18, 4.0F);
			this.guard(-18, 1, 0, 4.0F);
			this.guard(18, 1, 1, 4.0F);
			this.guard(1, 1, 20, 4.0F);
		}

		// ------------------------------------------------------------------ the crash site

		/**
		 * A helicopter (or a jet) come down hard: the furrow it ploughed, the wreck broken up with the tail torn
		 * off, fires burning in it, black smoke climbing from the engine, debris strewn about - and the squad sent
		 * to secure it, with their tent and a gun nest. Barrels of loot in and round the wreck.
		 */
		void crash() {
			boolean heli = this.hash(3, 3, 90) < 0.55F;
			// the furrow, coming in from the north
			for (int z = -23; z <= -2; z++) {
				int w = 1 + (z + 23) / 8;
				for (int x = -w - 1; x <= w + 1; x++) {
					if (Math.abs(x) <= w) {
						boolean deep = z > -14 && Math.abs(x) < w;
						if (deep) {
							this.set(x, 0, z, Blocks.AIR);
						}
						this.set(x, deep ? -1 : 0, z, this.hash(x, z, 91) < 0.6F ? ModRegistry.SCORCHED_EARTH : Blocks.COARSE_DIRT);
					} else if (this.hash(x, z, 92) < 0.6F) {
						this.set(x, 1, z, this.hash(x, z, 93) < 0.5F ? Blocks.COARSE_DIRT : Blocks.DIRT);
					}
				}
			}
			// the scorched ground round the wreck
			for (int x = -10; x <= 10; x++) {
				for (int z = -6; z <= 14; z++) {
					float d = Mth.sqrt(x * x + (z - 4) * (z - 4));
					float p = this.patch(x, z, 3, 94);
					if (d < 10.0F * (0.6F + 0.5F * p)) {
						this.set(x, 0, z, this.hash(x, z, 95) < 0.65F ? ModRegistry.SCORCHED_EARTH : Blocks.COARSE_DIRT);
						if (this.hash(x, z, 96) < 0.22F) {
							this.set(x, 1, z, ModRegistry.ASH);
						}
					}
				}
			}
			if (heli) {
				this.helicopter();
			} else {
				this.jetWreck();
			}
			// debris along the furrow and round about
			BlockState[] junk = {Blocks.IRON_BARS.defaultBlockState(), Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState(),
				Blocks.DAMAGED_ANVIL.defaultBlockState(), Blocks.IRON_CHAIN.defaultBlockState(), Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState(),
				Blocks.BLACKSTONE_SLAB.defaultBlockState()};
			for (int i = 0; i < 22; i++) {
				int x = Math.round((this.hash(i, 0, 97) - 0.5F) * 22.0F);
				int z = Math.round(this.hash(i, 1, 97) * 34.0F) - 22;
				if (Math.abs(x) <= 3 && z >= -2 && z <= 12) {
					continue;
				}
				this.set(x, 1, z, junk[(int) (this.hash(i, 2, 97) * junk.length)]);
			}
			this.set(5, 1, -2, BaseRegistry.FUEL_DRUM);
			this.set(6, 1, -1, BaseRegistry.FUEL_DRUM);
			this.blaze(-5, 1, 3);
			this.blaze(4, 1, 9);
			this.smoke(-3, 1, -7);
			// the squad: a tent under netting, a gun nest, lights
			for (int x = -16; x <= -11; x += 5) {
				for (int z = 8; z <= 13; z += 5) {
					this.fill(x, 1, z, x, 3, z, Blocks.SPRUCE_FENCE);
				}
			}
			this.fill(-16, 4, 8, -11, 4, 13, BaseRegistry.CAMO_NET);
			this.barrel(-15, 1, 10, Direction.UP);
			this.barrel(-15, 1, 11, Direction.UP);
			this.set(-13, 1, 12, BaseRegistry.MILITARY_CRATE);
			this.set(-12, 1, 12, BaseRegistry.MILITARY_CRATE);
			this.set(-12, 2, 12, BaseRegistry.MILITARY_CRATE);
			this.set(-14, 1, 9, this.facing(BaseRegistry.FIELD_GENERATOR, Direction.EAST));
			this.generators.add(this.at(-14, 1, 9));
			this.barracks = this.at(-13, 1, 10);
			this.nest(12, -4, Direction.SOUTH);
			this.pole(-8, -10, 5, Direction.NORTH);
			this.pole(9, 15, 5, Direction.SOUTH);
			this.guard(-6, 1, 5, 3.0F);
			this.guard(7, 1, 8, 3.0F);
			this.guard(-13, 1, 10, 2.0F);
			this.guard(0, 1, -17, 4.0F);
		}

		/** A transport helicopter on its belly, its doors open, rotor broken, tail torn off and lying behind. */
		void helicopter() {
			int[] half = {1, 2, 2, 2, 1};
			for (int z = -1; z <= 8; z++) {
				for (int y = 0; y <= 4; y++) {
					int hw = half[y] - (z == -1 || z == 8 ? 1 : 0);
					for (int x = -hw; x <= hw; x++) {
						boolean shell = Math.abs(x) == hw || y == 0 || y == 4 || z == -1 || z == 8;
						if (!shell) {
							this.set(x, y, z, Blocks.AIR);
							continue;
						}
						float h = this.hash(x * 5 + y, z, 98);
						BlockState b;
						if (z <= 0 && y >= 2) {
							b = h < 0.5F ? Blocks.AIR.defaultBlockState() : Blocks.BLACK_STAINED_GLASS.defaultBlockState(); // the shattered canopy
						} else {
							b = h < 0.12F ? Blocks.POLISHED_BLACKSTONE.defaultBlockState() : BaseRegistry.CORRUGATED_OLIVE.defaultBlockState();
						}
						this.set(x, y, z, b);
					}
				}
			}
			// sliding doors open both sides
			this.air(-2, 2, 2, -2, 3, 4);
			this.air(2, 2, 2, 2, 3, 4);
			this.barrel(0, 1, 6, Direction.UP);
			this.barrel(-1, 1, 7, Direction.UP);
			this.set(1, 1, 7, BaseRegistry.MILITARY_CRATE);
			this.set(1, 1, 3, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
			this.set(-1, 1, 3, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
			// rotor head and what is left of the blades; two more lie in the field
			this.set(0, 5, 4, Blocks.IRON_BLOCK);
			BlockState blade = Blocks.POLISHED_BLACKSTONE_SLAB.defaultBlockState();
			this.fill(1, 5, 4, 6, 5, 4, blade);
			this.fill(0, 5, 5, 0, 5, 8, blade);
			this.fill(-9, 1, -2, -5, 1, -2, blade);
			this.fill(7, 1, 12, 7, 1, 17, blade);
			// the engine is burning: black smoke from the top
			this.smoke(1, 5, 1);
			this.blaze(0, 1, 1);
			// the tail boom, broken off at the root, its end lying further back
			this.fill(0, 3, 9, 0, 3, 11, BaseRegistry.CORRUGATED_OLIVE);
			this.fill(0, 2, 9, 0, 2, 10, BaseRegistry.CORRUGATED_OLIVE);
			this.fill(3, 1, 14, 3, 1, 19, BaseRegistry.CORRUGATED_OLIVE);
			this.fill(3, 2, 19, 3, 3, 19, BaseRegistry.CORRUGATED_OLIVE);
			this.set(2, 2, 19, Blocks.IRON_BARS);
			this.set(4, 2, 19, Blocks.IRON_BARS);
			this.set(3, 4, 19, Blocks.IRON_BARS);
		}

		/** A fighter broken in two on landing: the nose and a wing in front, the rear with its fins behind. */
		void jetWreck() {
			BlockState skin = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
			BlockState dark = Blocks.GRAY_CONCRETE.defaultBlockState();
			for (int z = -4; z <= 11; z++) {
				if (z == 4 || z == 5) {
					continue;
				}
				for (int x = -1; x <= 1; x++) {
					for (int y = 0; y <= 1; y++) {
						this.set(x, y, z, this.hash(x + y * 3, z, 99) < 0.15F ? Blocks.BLACKSTONE.defaultBlockState() : this.hash(x, z, 100) < 0.3F ? dark : skin);
					}
				}
				this.set(0, 2, z, z >= -3 && z <= -1 ? (this.hash(0, z, 101) < 0.5F ? Blocks.AIR.defaultBlockState() : Blocks.BLACK_STAINED_GLASS.defaultBlockState()) : skin);
			}
			this.set(0, 1, -5, dark);
			this.set(0, 0, -5, dark);
			// the left wing, swept; the right broken off at the root, its outer part thrown ahead
			for (int x = 2; x <= 7; x++) {
				for (int z = 1 + (x - 2) / 2; z <= 3; z++) {
					this.set(-x, 1, z, skin);
				}
			}
			this.set(2, 1, 2, skin);
			this.set(2, 1, 3, skin);
			this.set(3, 1, 3, skin);
			this.fill(8, 1, -4, 11, 1, -3, skin);
			this.fill(9, 1, -2, 10, 1, -2, skin);
			// the fins and nozzles
			this.fill(-1, 2, 10, -1, 4, 11, skin);
			this.fill(1, 2, 10, 1, 4, 11, skin);
			this.set(-1, 1, 12, Blocks.BLACKSTONE);
			this.set(1, 1, 12, Blocks.BLACKSTONE);
			// burning in the break
			this.blaze(0, 1, 4);
			this.blaze(-1, 1, 5);
			this.smoke(1, 1, 5);
			this.barrel(3, 1, 7, Direction.UP);
			this.barrel(-3, 1, -6, Direction.UP);
			this.set(2, 1, -6, BaseRegistry.MILITARY_CRATE);
		}

		// ------------------------------------------------------------------ the submarine pen

		/**
		 * A submarine pen on the coast: a massive concrete hall over a flooded basin, its sea gate open onto a
		 * dredged channel. A boat lies in the basin; quays either side with bollards, a gantry crane, torpedoes
		 * on racks and stores; below the west quay an underwater observation room looks into the basin.
		 */
		void subPen() {
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			BlockState water = Blocks.WATER.defaultBlockState();
			// the hall
			this.fill(-14, -8, -14, 14, 13, 14, concrete);
			this.air(-13, 1, -13, 13, 11, 13);
			// the basin, open through the sea gate
			this.fill(-5, -7, -14, 5, -1, 13, water);
			this.air(-5, 0, -14, 5, 8, -14);
			this.fill(-6, 0, -14, -6, 8, -14, concrete);
			this.fill(6, 0, -14, 6, 8, -14, concrete);
			this.fill(-5, -7, -15, 5, -1, -15, water);
			// quays: concrete with a yellow edge, bollards
			this.fill(-13, 0, -13, -6, 0, 13, Blocks.SMOOTH_STONE);
			this.fill(6, 0, -13, 13, 0, 13, Blocks.SMOOTH_STONE);
			this.fill(-6, 0, -13, -6, 0, 13, Blocks.YELLOW_CONCRETE);
			this.fill(6, 0, -13, 6, 0, 13, Blocks.YELLOW_CONCRETE);
			for (int z = -11; z <= 11; z += 5) {
				this.set(-7, 1, z, Blocks.ANDESITE_WALL);
				this.set(7, 1, z, Blocks.ANDESITE_WALL);
			}
			this.fill(-5, -1, 13, 5, 0, 13, concrete);
			// the boat
			this.set(0, -4, -5, ModRegistry.SUBMARINE.defaultBlockState().setValue(de.rcm.ballistic.block.SubmarineBlock.FACING, Direction.NORTH));
			// a gantry crane across the basin
			BlockState yellow = Blocks.YELLOW_CONCRETE.defaultBlockState();
			for (int x = -9; x <= 9; x += 18) {
				this.fill(x, 1, -4, x, 9, -4, yellow);
				this.fill(x, 1, -2, x, 9, -2, yellow);
				this.fill(x, 10, -4, x, 10, -2, yellow);
			}
			this.fill(-9, 10, -3, 9, 10, -3, yellow);
			this.set(0, 9, -3, Blocks.IRON_BLOCK);
			this.fill(0, 5, -3, 0, 8, -3, Blocks.IRON_CHAIN);
			// torpedoes on racks, stores, the generator
			for (int z = 2; z <= 10; z += 2) {
				this.fill(9, 1, z, 12, 1, z, Blocks.SPRUCE_SLAB);
				this.fill(9, 2, z, 12, 2, z, Blocks.POLISHED_ANDESITE);
				this.set(12, 2, z, Blocks.GRAY_CONCRETE);
			}
			for (int z = -12; z <= -8; z++) {
				this.set(12, 1, z, BaseRegistry.MILITARY_CRATE);
				if (this.hash(12, z, 30) < 0.6F) {
					this.set(12, 2, z, BaseRegistry.MILITARY_CRATE);
				}
			}
			this.barrel(11, 1, -12, Direction.UP);
			this.barrel(-12, 1, 11, Direction.UP);
			this.barrel(-11, 1, 11, Direction.UP);
			for (int z = 5; z <= 9; z++) {
				this.set(-12, 1, z, BaseRegistry.FUEL_DRUM);
			}
			this.set(-12, 1, -11, this.facing(BaseRegistry.FIELD_GENERATOR, Direction.EAST));
			this.generators.add(this.at(-12, 1, -11));
			this.set(-10, 1, -12, this.facing(ModRegistry.WEAPONS_WORKBENCH, Direction.SOUTH));
			// lights under the roof, floods on the sea face
			for (int x = -9; x <= 9; x += 6) {
				for (int z = -10; z <= 10; z += 5) {
					this.set(x, 11, z, Blocks.SEA_LANTERN);
				}
			}
			this.floodlight(-8, 9, -15, Direction.NORTH);
			this.floodlight(8, 9, -15, Direction.NORTH);
			this.alarm(0, 9, -13);
			// ways in from the land: a door and a vehicle gate
			this.door(10, 1, 14, Blocks.IRON_DOOR, Direction.NORTH, DoorHingeSide.LEFT);
			this.set(11, 2, 15, Blocks.STONE_BUTTON.defaultBlockState().setValue(net.minecraft.world.level.block.ButtonBlock.FACING, Direction.SOUTH));
			this.set(11, 2, 13, Blocks.STONE_BUTTON.defaultBlockState().setValue(net.minecraft.world.level.block.ButtonBlock.FACING, Direction.NORTH));
			this.air(-11, 1, 14, -8, 4, 14);
			this.fill(-11, 0, 15, -8, 0, 21, ModRegistry.RUNWAY_ASPHALT);
			this.fill(9, 0, 15, 11, 0, 21, Blocks.GRAVEL);
			// the underwater room below the west quay, looking into the basin
			this.fill(-13, -8, -6, -6, -1, 3, concrete);
			this.air(-12, -7, -5, -7, -3, 2);
			this.fill(-6, -6, -4, -6, -4, 1, Blocks.GLASS);
			this.set(-10, -3, -1, Blocks.SEA_LANTERN);
			this.set(-10, -3, -4, Blocks.SEA_LANTERN);
			this.fill(-11, -2, 2, -11, 0, 2, Blocks.AIR);
			this.ladder(-11, -7, 0, 2, Direction.NORTH);
			this.fill(-11, -7, 3, -11, 0, 3, concrete);
			this.barrel(-12, -7, -5, Direction.UP);
			this.set(-12, -7, -4, Blocks.CHEST);
			this.set(-8, -7, -5, Blocks.LECTERN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
			// on the roof: two gun nests
			this.walls(-14, 14, -14, 14, 14, 14, concrete);
			this.walls(-10, 14, 4, -6, 15, 8, BaseRegistry.SANDBAGS.defaultBlockState());
			this.walls(6, 14, -10, 10, 15, -6, BaseRegistry.SANDBAGS.defaultBlockState());
			this.ladder(13, 1, 13, 13, Direction.WEST);
			this.set(13, 13, 13, Blocks.AIR);
			this.set(13, 12, 13, Blocks.AIR);
			this.ladder(13, 12, 13, 13, Direction.WEST);
			this.guard(-8, 14, 6, 0.0F);
			this.guard(8, 14, -8, 0.0F);
			this.barracks = this.at(10, 1, 12);
			this.guard(-9, 1, -6, 4.0F);
			this.guard(9, 1, 6, 4.0F);
			this.guard(-8, 1, 10, 3.0F);
			this.guard(10, 1, 18, 2.0F);
			this.guard(-10, -7, -2, 1.5F);
		}

		// ------------------------------------------------------------------ the secret lab

		/**
		 * A secret laboratory: above ground a plain concrete blockhouse behind a fence, with a guard hut where
		 * the keycard is kept; below, three floors deep in the rock - security, laboratories, the reactor and
		 * the vault - reached by the lift (and an emergency ladder). The important rooms are behind iron doors
		 * that only open to a keycard swiped at their reader.
		 */
		void lab() {
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			// the fence round the compound, a gate on the south side
			BlockState chainX = this.chainlink(true, false);
			BlockState chainZ = this.chainlink(false, true);
			for (int t = -17; t <= 17; t++) {
				this.set(t, 1, -17, chainX);
				this.set(t, 2, -17, chainX);
				this.set(-17, 1, t, chainZ);
				this.set(-17, 2, t, chainZ);
				this.set(17, 1, t, chainZ);
				this.set(17, 2, t, chainZ);
				if (Math.abs(t) > 2) {
					this.set(t, 1, 17, chainX);
					this.set(t, 2, 17, chainX);
					this.set(t, 3, 17, BaseRegistry.BARBED_WIRE);
				}
				this.set(t, 3, -17, BaseRegistry.BARBED_WIRE);
				this.set(-17, 3, t, BaseRegistry.BARBED_WIRE);
				this.set(17, 3, t, BaseRegistry.BARBED_WIRE);
			}
			this.fill(-2, 0, 5, 2, 0, 20, ModRegistry.RUNWAY_ASPHALT);
			this.sign(4, 1, 18, 0, "sign.ballisticmissiles.restricted");
			// the blockhouse: a card reader at its only door
			this.fill(-5, 0, -5, 5, 0, 5, Blocks.SMOOTH_STONE);
			this.walls(-4, 1, -4, 4, 4, 4, concrete);
			this.fill(-4, 5, -4, 4, 5, 4, concrete);
			this.air(-3, 1, -3, 3, 4, 3);
			this.door(0, 1, 4, Blocks.IRON_DOOR, Direction.NORTH, DoorHingeSide.LEFT);
			this.set(1, 2, 4, this.facing(BaseRegistry.CARD_READER, Direction.SOUTH));
			this.set(0, 4, -1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			this.floodlight(-1, 5, 4, Direction.SOUTH);
			this.fill(3, 6, -3, 3, 9, -3, Blocks.IRON_BARS);
			this.set(3, 10, -3, Blocks.LIGHTNING_ROD);
			this.set(-2, 6, 2, ModRegistry.RADAR);
			// the guard hut, where the keycard is
			this.hut(9, 10);
			this.keycard(10, 1, 8);
			this.barracks = this.at(11, 1, 9);
			this.pole(-15, -15, 5, Direction.NORTH);
			this.pole(15, -15, 5, Direction.NORTH);
			this.pole(-15, 15, 5, Direction.SOUTH);
			// the lift's panel at every floor, and the emergency ladder beside it
			this.set(2, 1, -3, this.facing(BaseRegistry.ELEVATOR_PANEL, Direction.SOUTH));
			this.fill(-3, -28, -3, -1, -1, -1, concrete);
			this.fill(-2, 1, -3, -2, 4, -3, concrete);
			int[] floors = {-12, -20, -28};
			for (int k = 0; k < floors.length; k++) {
				this.labFloor(floors[k], k);
			}
			this.air(-2, -27, -2, -2, 0, -2);
			this.ladder(-2, -27, 0, -2, Direction.SOUTH);
			this.guard(0, 1, 8, 3.0F);
			this.guard(11, 1, 8, 1.5F);
			this.guard(-12, 1, 12, 5.0F);
		}

		/** One underground floor: a cross of corridors, four rooms off it, some behind keycard doors. */
		void labFloor(int f, int k) {
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			BlockState white = Blocks.WHITE_CONCRETE.defaultBlockState();
			this.fill(-16, f, -16, 16, f + 5, 16, concrete);
			this.air(-15, f + 1, -15, 15, f + 4, 15);
			this.fill(-15, f, -15, 15, f, 15, k == 1 ? white : Blocks.SMOOTH_STONE.defaultBlockState());
			this.fill(-2, f, -15, 2, f, 15, Blocks.LIGHT_GRAY_CONCRETE);
			this.fill(-15, f, -2, 15, f, 2, Blocks.LIGHT_GRAY_CONCRETE);
			for (int t = -15; t <= 15; t++) {
				if (Math.abs(t) > 2) {
					this.fill(3, f + 1, t, 3, f + 4, t, white);
					this.fill(-3, f + 1, t, -3, f + 4, t, white);
					this.fill(t, f + 1, 3, t, f + 4, 3, white);
					this.fill(t, f + 1, -3, t, f + 4, -3, white);
				}
			}
			for (int x = -12; x <= 12; x += 6) {
				for (int z = -12; z <= 12; z += 6) {
					this.set(x, f + 5, z, Blocks.SEA_LANTERN);
				}
			}
			this.alarm(0, f + 4, 0);
			this.fill(-2, f + 1, -3, -2, f + 4, -3, concrete);
			this.set(2, f + 1, -3, this.facing(BaseRegistry.ELEVATOR_PANEL, Direction.SOUTH));
			// the rooms' doors: on the x walls at z = +-8; locked ones get a reader beside them
			boolean[] locked = switch (k) {
				case 0 -> new boolean[] {false, true, false, false}; // NE guard room, NW armoury, SE office, SW decontamination
				case 1 -> new boolean[] {false, true, false, true};  // NE lab, NW isolation lab, SE cells, SW chemicals
				default -> new boolean[] {false, true, false, false}; // NE servers, NW vault, SE reactor, SW control
			};
			int[][] doors = {{3, -8}, {-3, -8}, {3, 8}, {-3, 8}};
			for (int i = 0; i < 4; i++) {
				int x = doors[i][0];
				int z = doors[i][1];
				if (locked[i]) {
					this.door(x, f + 1, z, Blocks.IRON_DOOR, x > 0 ? Direction.EAST : Direction.WEST, DoorHingeSide.LEFT);
					this.set(x, f + 2, z + 2, this.facing(BaseRegistry.CARD_READER, x > 0 ? Direction.WEST : Direction.EAST));
				} else {
					this.air(x, f + 1, z, x, f + 2, z);
				}
			}
			int y = f + 1;
			switch (k) {
				case 0 -> {
					for (int z = -14; z <= -10; z += 2) {
						this.bed(14, y, z, Direction.EAST);
					}
					this.barrel(5, y, -14, Direction.UP);
					this.keycard(5, y, -5);
					for (int z = -14; z <= -5; z++) {
						this.set(-14, y, z, BaseRegistry.MILITARY_CRATE);
						if (this.hash(-14, z, 40) < 0.6F) {
							this.set(-14, y + 1, z, BaseRegistry.MILITARY_CRATE);
						}
					}
					this.barrel(-5, y, -14, Direction.UP);
					this.barrel(-6, y, -14, Direction.UP);
					this.set(-9, y, -14, this.facing(ModRegistry.WEAPONS_WORKBENCH, Direction.SOUTH));
					this.set(10, y, 10, Blocks.LECTERN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
					this.set(12, y, 12, Blocks.CARTOGRAPHY_TABLE);
					this.fill(14, y, 5, 14, y + 2, 14, Blocks.BOOKSHELF);
					for (int z = 6; z <= 14; z += 4) {
						this.set(-10, y, z, Blocks.WATER_CAULDRON.defaultBlockState().setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));
					}
					this.fill(-14, y, 4, -14, y + 2, 14, Blocks.LIGHT_BLUE_STAINED_GLASS);
					this.guard(0, y, 10, 4.0F);
					this.guard(9, y, -8, 2.0F);
				}
				case 1 -> {
					for (int x = 6; x <= 14; x += 4) {
						this.tank(x, y, -12);
						this.set(x, y, -5, Blocks.BREWING_STAND);
					}
					this.fill(5, y, -7, 14, y, -7, Blocks.SMOOTH_QUARTZ_SLAB);
					this.fill(-14, y, -14, -14, y + 3, -4, Blocks.GREEN_STAINED_GLASS);
					this.set(-10, y, -10, Blocks.BREWING_STAND);
					this.set(-8, y, -10, Blocks.CAULDRON);
					this.tank(-10, y, -6);
					this.barrel(-6, y, -14, Direction.UP);
					// holding cells
					for (int x = 6; x <= 14; x += 4) {
						this.fill(x - 2, y, 10, x - 2, y + 3, 15, Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.NORTH, true).setValue(IronBarsBlock.SOUTH, true));
						this.fill(x - 1, y, 10, x + 1, y + 3, 10, Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true));
						this.set(x, y + 3, 13, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
					}
					for (int z = 5; z <= 14; z++) {
						this.set(-14, y, z, z % 3 == 0 ? BaseRegistry.FUEL_DRUM.defaultBlockState() : Blocks.BARREL.defaultBlockState());
					}
					this.barrel(-6, y, 14, Direction.UP);
					this.guard(0, y, -10, 4.0F);
					this.guard(-9, y, 0, 3.0F);
				}
				default -> {
					for (int x = 6; x <= 14; x += 2) {
						for (int z = -14; z <= -6; z++) {
							if (z != -10) {
								this.set(x, y, z, z % 2 == 0 ? Blocks.OBSERVER.defaultBlockState()
									: Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true));
								this.set(x, y + 1, z, Blocks.OBSERVER);
							}
						}
					}
					// the vault
					for (int x = -14; x <= -6; x += 2) {
						this.barrel(x, y, -14, Direction.UP);
					}
					this.fill(-14, y, -12, -14, y, -10, Blocks.GOLD_BLOCK);
					this.set(-13, y, -12, Blocks.DIAMOND_BLOCK);
					this.set(-10, y, -10, BaseRegistry.MILITARY_CRATE);
					// the reactor room
					this.set(9, y, 9, this.facing(ModRegistry.EMERGENCY_GENERATOR, Direction.WEST).setValue(BlockStateProperties.LIT, true));
					this.set(12, y, 12, this.facing(ModRegistry.AIR_FILTER, Direction.WEST));
					this.set(13, y, 6, this.facing(BaseRegistry.FIELD_GENERATOR, Direction.WEST));
					this.generators.add(this.at(13, y, 6));
					this.fill(9, y + 1, 12, 9, y + 3, 12, Blocks.LIGHT_BLUE_STAINED_GLASS);
					this.set(9, y, 12, Blocks.BEACON);
					// the control room
					this.set(-9, y, 14, this.facing(ModRegistry.COMMAND_CENTER, Direction.NORTH));
					this.set(-12, y, 14, Blocks.LECTERN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
					this.guard(0, y, 10, 4.0F);
					this.guard(-9, y, 9, 2.0F);
				}
			}
		}

		/** A specimen tank: a glass column of water with something in it, on a steel base under a steel cap. */
		void tank(int x, int y, int z) {
			this.set(x, y, z, Blocks.IRON_BLOCK);
			this.set(x, y + 1, z, Blocks.ZOMBIE_HEAD);
			this.set(x, y + 2, z, Blocks.WATER);
			this.set(x, y + 3, z, Blocks.IRON_BLOCK);
			for (Direction d : Direction.Plane.HORIZONTAL) {
				this.set(x + d.getStepX(), y + 1, z + d.getStepZ(), Blocks.GLASS);
				this.set(x + d.getStepX(), y + 2, z + d.getStepZ(), Blocks.GLASS);
			}
		}

		// ------------------------------------------------------------------ the border trench line

		/** The fire trench's line at x: bays and traverses, stepped back two blocks every eight. */
		private int trenchZ(int x) {
			return Math.floorMod(Math.floorDiv(x + 48, 8), 2) == 0 ? 0 : -2;
		}

		/**
		 * A border held by a trench line: the fire trench in bays and traverses, revetted with planks, a fire
		 * step and a sandbag parapet; communication trenches back to dug-outs; two concrete pillboxes in the
		 * line; two belts of wire in front, then a minefield (with its warning signs), then the border itself -
		 * striped posts and a fence - and a watchtower behind.
		 */
		void trench() {
			BlockState plank = Blocks.SPRUCE_PLANKS.defaultBlockState();
			BlockState post = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			// revetments and parapet first, then dig
			for (int x = -46; x <= 46; x++) {
				int zc = this.trenchZ(x);
				int prev = this.trenchZ(x - 1);
				int lo = Math.min(zc, x > -46 ? prev : zc);
				int hi = Math.max(zc, x > -46 ? prev : zc);
				BlockState side = x % 3 == 0 ? post : plank;
				this.fill(x, -2, lo - 2, x, 0, lo - 2, side);
				this.fill(x, -2, hi + 2, x, 0, hi + 2, side);
				if (Math.floorMod(x, 5) != 0) {
					this.set(x, 1, zc - 2, BaseRegistry.SANDBAGS);
				}
				this.set(x, 1, zc + 2, this.hash(x, 0, 20) < 0.5F ? BaseRegistry.SANDBAGS.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
			}
			for (int x = -46; x <= 46; x++) {
				int zc = this.trenchZ(x);
				int prev = this.trenchZ(x - 1);
				int lo = Math.min(zc, x > -46 ? prev : zc);
				int hi = Math.max(zc, x > -46 ? prev : zc);
				this.fill(x, -2, lo - 1, x, -2, hi + 1, plank);
				this.air(x, -1, lo - 1, x, 0, hi + 1);
				this.set(x, -1, zc - 1, Blocks.SPRUCE_SLAB);
			}
			this.fill(-47, -2, -4, -47, 0, 2, plank);
			this.fill(47, -2, -4, 47, 0, 2, plank);
			// communication trenches back to the dug-outs
			for (int cx = -30; cx <= 30; cx += 30) {
				int start = this.trenchZ(cx) + 2;
				this.fill(cx - 1, -2, start, cx - 1, 0, 17, plank);
				this.fill(cx + 2, -2, start, cx + 2, 0, 17, plank);
				this.fill(cx, -2, start, cx + 1, -2, 17, plank);
				this.air(cx, -1, start, cx + 1, 0, 17);
				this.dugout(cx, 18);
			}
			// pillboxes in the line
			for (int px = -20; px <= 20; px += 40) {
				int zc = this.trenchZ(px);
				this.fill(px - 2, -2, zc - 7, px + 2, 2, zc - 2, concrete);
				this.air(px - 1, -1, zc - 6, px + 1, 1, zc - 3);
				this.air(px - 1, 1, zc - 7, px + 1, 1, zc - 7);
				this.air(px, -1, zc - 2, px, 0, zc - 2);
				this.set(px, 1, zc - 4, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
				this.barrel(px + 1, -1, zc - 3, Direction.UP);
				this.guard(px, -1, zc - 5, 0.0F);
			}
			// two belts of wire on pickets
			for (int x = -47; x <= 47; x++) {
				if (this.hash(x, 9, 31) > 0.08F) {
					this.set(x, 1, -10, BaseRegistry.BARBED_WIRE);
				}
				if (this.hash(x, 12, 32) > 0.1F) {
					this.set(x, 1, -13, BaseRegistry.BARBED_WIRE);
				}
				if (Math.floorMod(x, 2) == 0) {
					this.set(x, 1, -11, BaseRegistry.BARBED_WIRE);
				}
				if (Math.floorMod(x, 6) == 0) {
					this.set(x, 1, -12, Blocks.SPRUCE_FENCE);
				}
			}
			// the minefield, signed on both sides
			for (int x = -47; x <= 47; x++) {
				for (int z = -26; z <= -16; z++) {
					if (this.hash(x, z, 33) < 0.065F) {
						this.set(x, 1, z, BaseRegistry.LANDMINE);
					}
				}
			}
			for (int x = -42; x <= 42; x += 12) {
				this.sign(x, 1, -15, 0, "sign.ballisticmissiles.mines");
				this.sign(x + 6, 1, -27, 8, "sign.ballisticmissiles.mines");
			}
			// the border: a fence with wire on top, striped posts
			BlockState chainX = this.chainlink(true, false);
			for (int x = -47; x <= 47; x++) {
				this.set(x, 1, -29, chainX);
				this.set(x, 2, -29, chainX);
				this.set(x, 3, -29, BaseRegistry.BARBED_WIRE);
			}
			for (int x = -44; x <= 44; x += 11) {
				for (int y = 1; y <= 3; y++) {
					this.set(x, y, -28, y % 2 == 1 ? Blocks.RED_CONCRETE : Blocks.WHITE_CONCRETE);
				}
				this.set(x, 4, -28, Blocks.WHITE_CONCRETE);
			}
			// a watchtower behind the line, lights
			this.tower(40, 14, 1, 1);
			this.guard(41, 8, 15, 0.0F);
			this.pole(-12, 9, 5, Direction.NORTH);
			this.pole(14, 9, 5, Direction.NORTH);
			this.barracks = this.at(0, -1, 21);
			for (int x : new int[] {-40, -28, -10, 6, 24, 38}) {
				this.guard(x, -1, this.trenchZ(x), 1.5F);
			}
		}

		/** A dug-out: a concrete room under an earth mound at the end of a communication trench (x .. x+1). */
		void dugout(int x, int z) {
			BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
			this.fill(x - 4, -2, z, x + 5, 2, z + 7, concrete);
			this.air(x - 3, -1, z + 1, x + 4, 1, z + 6);
			this.air(x, -1, z, x + 1, 0, z);
			this.fill(x - 4, 3, z, x + 5, 3, z + 7, Blocks.GRASS_BLOCK);
			for (int bx = x - 3; bx <= x + 3; bx += 2) {
				this.bed(bx, -1, z + 6, Direction.SOUTH);
			}
			this.set(x, 1, z + 3, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			this.barrel(x + 4, -1, z + 1, Direction.UP);
			this.set(x + 4, -1, z + 2, BaseRegistry.MILITARY_CRATE);
			this.set(x - 3, -1, z + 1, BaseRegistry.MILITARY_CRATE);
			this.guard(x + 1, -1, z + 3, 2.0F);
		}

		/** The guards: in the towers, the gun nests, round the yard and at each site. */
		/**
		 * What makes the compound lived in: trucks parked along the main road, a mess and aid tent, a firing range
		 * behind the command bunker, steel hedgehogs on the approach.
		 */
		void details() {
			this.truck(5, 13, 1);
			this.truck(5, 21, 2);
			this.tent(-11, 14, -5, 25);
			this.range();
			for (int s = -1; s <= 1; s += 2) {
				this.hedgehog(s * 10, 42);
				this.hedgehog(s * 13, 45);
				this.hedgehog(s * 9, 47);
				this.hedgehog(s * 15, 40);
			}
		}

		/** An army truck, 3 x 7, its cab towards +z: wheels, an olive body, a canvas tilt over the load bed. */
		void truck(int x0, int z0, int salt) {
			BlockState body = Blocks.GREEN_TERRACOTTA.defaultBlockState();
			BlockState canvas = (this.hash(x0, z0, salt) < 0.5F ? Blocks.GREEN_WOOL : Blocks.BROWN_WOOL).defaultBlockState();
			for (int z : new int[] {z0 + 1, z0 + 4, z0 + 5}) {
				this.set(x0, 1, z, Blocks.BLACK_CONCRETE);
				this.set(x0 + 2, 1, z, Blocks.BLACK_CONCRETE);
			}
			this.fill(x0 + 1, 1, z0, x0 + 1, 1, z0 + 6, Blocks.GRAY_CONCRETE);
			this.fill(x0, 2, z0, x0 + 2, 2, z0 + 6, body);
			// the cab: windscreen in front, side windows, a roof
			this.fill(x0, 3, z0 + 5, x0 + 2, 3, z0 + 6, body);
			this.fill(x0, 4, z0 + 6, x0 + 2, 4, z0 + 6, Blocks.GLASS);
			this.set(x0, 4, z0 + 5, Blocks.GLASS);
			this.set(x0 + 2, 4, z0 + 5, Blocks.GLASS);
			this.set(x0 + 1, 4, z0 + 5, body);
			this.fill(x0, 5, z0 + 5, x0 + 2, 5, z0 + 6, body);
			// the tilt over the bed, open at the back, a crate inside
			this.fill(x0, 3, z0, x0, 4, z0 + 3, canvas);
			this.fill(x0 + 2, 3, z0, x0 + 2, 4, z0 + 3, canvas);
			this.fill(x0, 5, z0, x0 + 2, 5, z0 + 3, canvas);
			this.air(x0 + 1, 3, z0, x0 + 1, 4, z0 + 3);
			this.barrel(x0 + 1, 3, z0 + 2, Direction.UP);
		}

		/** A long army tent: canvas sides sloping to a ridge, the mess at one end, cots and a red cross at the other. */
		void tent(int x1, int z1, int x2, int z2) {
			BlockState canvas = Blocks.GREEN_WOOL.defaultBlockState();
			int mid = (x1 + x2) / 2;
			this.fill(x1 + 1, 0, z1, x2 - 1, 0, z2, Blocks.SPRUCE_PLANKS);
			this.fill(x1, 1, z1, x1, 2, z2, canvas);
			this.fill(x2, 1, z1, x2, 2, z2, canvas);
			this.fill(x1 + 1, 3, z1, x1 + 1, 3, z2, canvas);
			this.fill(x2 - 1, 3, z1, x2 - 1, 3, z2, canvas);
			this.fill(x1 + 2, 4, z1, x1 + 2, 4, z2, canvas);
			this.fill(x2 - 2, 4, z1, x2 - 2, 4, z2, canvas);
			this.fill(mid, 5, z1, mid, 5, z2, canvas);
			this.air(x1 + 1, 1, z1, x2 - 1, 2, z2);
			this.air(x1 + 2, 3, z1, x2 - 2, 3, z2);
			this.air(mid, 4, z1, mid, 4, z2);
			// guy ropes' pegs
			for (int z = z1; z <= z2; z += 4) {
				this.set(x1 - 1, 1, z, Blocks.SPRUCE_FENCE);
				this.set(x2 + 1, 1, z, Blocks.SPRUCE_FENCE);
			}
			// the mess: tables with stools
			for (int z = z1 + 2; z <= z1 + 5; z += 3) {
				this.set(mid, 1, z, Blocks.SPRUCE_FENCE);
				this.set(mid, 2, z, Blocks.SPRUCE_PRESSURE_PLATE);
				this.set(mid - 1, 1, z, this.facing(Blocks.SPRUCE_STAIRS, Direction.EAST));
				this.set(mid + 1, 1, z, this.facing(Blocks.SPRUCE_STAIRS, Direction.WEST));
			}
			this.set(x2 - 1, 1, z1 + 1, Blocks.SMOKER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
			this.barrel(x2 - 1, 1, z1 + 2, Direction.WEST);
			// the aid post: cots, a cabinet, the red cross on the ridge
			this.bed(x1 + 1, 1, z2 - 1, Direction.SOUTH);
			this.bed(x1 + 1, 1, z2 - 4, Direction.SOUTH);
			this.bed(x2 - 1, 1, z2 - 1, Direction.SOUTH);
			this.set(x2 - 1, 1, z2 - 4, Blocks.BREWING_STAND);
			this.set(mid, 5, z2 - 3, Blocks.RED_WOOL);
			this.set(mid, 5, z2 - 2, Blocks.RED_WOOL);
			this.set(mid, 5, z2 - 4, Blocks.RED_WOOL);
			for (int z = z1 + 2; z < z2; z += 4) {
				this.set(mid, 4, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
			}
		}

		/** The firing range: a sandbag firing line, targets on hay in front of an earth stop butt. */
		void range() {
			this.fill(-6, 0, -34, 7, 0, -29, Blocks.GRAVEL);
			this.fill(-6, 1, -29, 7, 1, -29, BaseRegistry.SANDBAGS);
			this.fill(-7, 1, -35, 8, 3, -35, Blocks.COARSE_DIRT);
			this.fill(-7, 3, -35, 8, 3, -35, Blocks.GRASS_BLOCK);
			for (int x = -4; x <= 6; x += 5) {
				this.set(x, 1, -33, Blocks.HAY_BLOCK);
				this.set(x, 2, -33, Blocks.TARGET);
			}
			this.set(7, 2, -29, BaseRegistry.MILITARY_CRATE);
			this.pole(-7, -29, 5, Direction.NORTH);
		}

		/** A Czech hedgehog: crossed steel beams against vehicles. */
		void hedgehog(int x, int z) {
			BlockState cross = Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true)
				.setValue(IronBarsBlock.NORTH, true).setValue(IronBarsBlock.SOUTH, true);
			this.set(x, 1, z, cross);
			this.set(x, 2, z, Blocks.IRON_BARS);
			this.set(x + 1, 1, z, Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.WEST, true));
			this.set(x - 1, 1, z, Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.EAST, true));
		}

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
			int fy = flagY(MilitaryBasePiece.this.variant);
			if (!this.inside(f[0], fy, f[1])) {
				return;
			}
			this.set(f[0], fy, f[1], BaseRegistry.BASE_FLAG);
			if (this.level.getBlockEntity(this.at(f[0], fy, f[1])) instanceof BaseFlagBlockEntity flag) {
				flag.setup(MilitaryBasePiece.this.variant, this.lights, this.alarms, this.generators, this.barracks);
			}
		}
	}
}
