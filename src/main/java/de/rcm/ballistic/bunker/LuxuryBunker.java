package de.rcm.ballistic.bunker;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The luxury fallout shelter: ten metres down, a decontamination airlock between two blast doors, then a
 * 21 x 21 hall of reinforced concrete clad in white quartz around a central corridor - a living room with
 * a sofa, screen and bookshelves, a kitchen and dining table, a bedroom with a double bed, a bathroom, a
 * command room with glowing screens and an armoury, the plant room with generator, NBC filter and water
 * tank, and a hydroponic garden under grow lights - and an emergency exit shaft up to the surface at the
 * far end.
 * <p>
 * Laid out in "along / side / up" from the hatch: along is the way the player faced, side + is to their
 * right, up is from the hatch block. The basic shelter is {@link BunkerBlock#build}.
 */
final class LuxuryBunker {
	/** Floor of the shelter below the hatch. */
	private static final int F = -10;

	private final ServerLevel level;
	private final BlockPos hatch;
	private final Direction f;
	private final Direction back;
	private final Direction right;
	private final Direction left;

	private LuxuryBunker(ServerLevel level, BlockPos hatch, Direction f) {
		this.level = level;
		this.hatch = hatch;
		this.f = f;
		this.back = f.getOpposite();
		this.right = f.getClockWise();
		this.left = f.getCounterClockWise();
	}

	static void build(ServerLevel level, BlockPos hatch, Direction f) {
		new LuxuryBunker(level, hatch, f).build();
	}

	// ------------------------------------------------------------------ helpers

	private BlockPos at(int a, int s, int u) {
		return this.hatch.relative(this.f, a).relative(this.right, s).above(u);
	}

	private void put(int a, int s, int u, BlockState state) {
		BunkerBlock.set(this.level, this.at(a, s, u), state);
	}

	private void box(int a0, int a1, int s0, int s1, int u0, int u1, BlockState state) {
		for (int a = a0; a <= a1; a++) {
			for (int s = s0; s <= s1; s++) {
				for (int u = u0; u <= u1; u++) {
					this.put(a, s, u, state);
				}
			}
		}
	}

	private static BlockState b(Block block) {
		return block.defaultBlockState();
	}

	private void door(int a, int s, Block block, Direction facing) {
		BlockState d = block.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
		this.put(a, s, F, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		this.put(a, s, F + 1, d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
	}

	private void stairs(int a, int s, Block block, Direction facing) {
		this.put(a, s, F, block.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM));
	}

	private void tableTop(int a, int s, Block slab) {
		this.put(a, s, F, slab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
	}

	private void hangingLantern(int a, int s) {
		this.put(a, s, F + 4, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
	}

	/** A bed with its head at (a, s) and its foot one block back from {@code headward}. */
	private void bed(int a, int s, Block block, Direction headward) {
		BlockState bed = block.defaultBlockState().setValue(BedBlock.FACING, headward);
		BlockPos head = this.at(a, s, F);
		BunkerBlock.set(this.level, head, bed.setValue(BedBlock.PART, BedPart.HEAD));
		BunkerBlock.set(this.level, head.relative(headward.getOpposite()), bed.setValue(BedBlock.PART, BedPart.FOOT));
	}

	private void chest(int a, int s, int u, Direction facing, ItemStack... items) {
		BlockPos p = this.at(a, s, u);
		BunkerBlock.set(this.level, p, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing));
		if (this.level.getBlockEntity(p) instanceof ChestBlockEntity chest) {
			for (int i = 0; i < items.length && i < chest.getContainerSize(); i++) {
				chest.setItem(i, items[i]);
			}
		}
	}

	private void water(int a, int s, int u) {
		this.put(a, s, u, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
	}

	// ------------------------------------------------------------------ the shelter

	private void build() {
		BlockState concrete = b(ModRegistry.REINFORCED_CONCRETE);
		BlockState air = b(Blocks.AIR);
		BlockState wall = b(Blocks.SMOOTH_QUARTZ);
		BlockState trim = b(Blocks.POLISHED_DEEPSLATE);

		// ---- shells: the hall, the entrance corridor with its decontamination chamber, the shaft
		this.box(5, 29, -12, 12, F - 2, F + 6, concrete);
		this.box(-1, 6, -2, 2, F - 1, F + 3, concrete);
		this.box(-1, 1, -1, 1, F + 3, 0, concrete);
		// quartz cladding inside the hall's shell, then hollowed out (5 high)
		this.box(6, 28, -11, 11, F - 1, F + 5, wall);
		this.box(7, 27, -10, 10, F, F + 4, air);

		// ---- entrance: hatch, ladder, airlock with decontamination showers
		this.box(0, 6, 0, 0, F, F + 1, air);
		this.box(0, 0, 0, 0, F + 2, -1, air);
		this.box(2, 4, -1, 1, F, F + 2, air);
		this.put(0, 0, 0, Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, this.back).setValue(TrapDoorBlock.HALF, Half.TOP));
		this.put(0, 1, 0, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, this.f));
		for (int u = F; u <= -1; u++) {
			this.put(0, 0, u, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, this.f));
		}
		this.door(1, 0, ModRegistry.BLAST_DOOR, this.f);
		this.door(5, 0, ModRegistry.BLAST_DOOR, this.f);
		this.box(2, 4, -1, 1, F - 1, F - 1, b(Blocks.YELLOW_CONCRETE));
		this.box(2, 4, 0, 0, F - 1, F - 1, b(Blocks.IRON_BLOCK)); // drain grating
		for (int s : new int[] {-1, 1}) {
			this.water(3, s, F);
			this.put(3, s, F + 2, b(Blocks.IRON_BARS)); // shower heads
		}
		this.put(3, 0, F + 3, b(Blocks.SEA_LANTERN));

		// ---- floors
		this.box(7, 27, -1, 1, F - 1, F - 1, trim);                              // corridor
		this.box(7, 13, -10, -3, F - 1, F - 1, b(Blocks.DARK_OAK_PLANKS));       // living room
		for (int a = 15; a <= 20; a++) {                                          // kitchen: chequered tiles
			for (int s = -10; s <= -3; s++) {
				this.put(a, s, F - 1, b((a + s & 1) == 0 ? Blocks.WHITE_CONCRETE : Blocks.BLACK_CONCRETE));
			}
		}
		this.box(22, 24, -10, -3, F - 1, F - 1, b(Blocks.SPRUCE_PLANKS));        // bedroom
		this.box(26, 27, -10, -3, F - 1, F - 1, b(Blocks.POLISHED_DIORITE));     // bathroom
		this.box(7, 13, 3, 10, F - 1, F - 1, b(Blocks.GRAY_CONCRETE));           // command room
		for (int a = 15; a <= 20; a++) {                                          // plant room, hazard-striped
			for (int s = 3; s <= 10; s++) {
				boolean edge = s == 3 || a == 15 || a == 20 || s == 10;
				this.put(a, s, F - 1, b(edge ? ((a + s & 1) == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE) : Blocks.POLISHED_ANDESITE));
			}
		}

		// ---- partitions: the corridor walls and the rooms off it
		this.box(7, 27, -2, -2, F, F + 4, wall);
		this.box(7, 27, 2, 2, F, F + 4, wall);
		for (int a : new int[] {14, 21, 25}) {
			this.box(a, a, -10, -3, F, F + 4, wall);
		}
		for (int a : new int[] {14, 21}) {
			this.box(a, a, 3, 10, F, F + 4, wall);
		}
		// a deepslate skirting along the corridor
		this.box(7, 27, -2, -2, F, F, trim);
		this.box(7, 27, 2, 2, F, F, trim);
		// the living room opens onto the corridor through a wide arch; the others have doors
		this.box(9, 11, -2, -2, F, F + 2, air);
		this.door(17, -2, Blocks.DARK_OAK_DOOR, this.right);
		this.door(23, -2, Blocks.DARK_OAK_DOOR, this.right);
		this.door(26, -2, Blocks.DARK_OAK_DOOR, this.right);
		this.door(10, 2, Blocks.IRON_DOOR, this.left);
		// the command room's steel door opens with a button either side
		for (int s : new int[] {1, 3}) {
			this.put(11, s, F, Blocks.STONE_BUTTON.defaultBlockState()
				.setValue(net.minecraft.world.level.block.ButtonBlock.FACE, AttachFace.FLOOR).setValue(net.minecraft.world.level.block.ButtonBlock.FACING, this.left));
		}
		this.door(17, 2, Blocks.DARK_OAK_DOOR, this.left);
		this.door(24, 2, Blocks.DARK_OAK_DOOR, this.left);

		// ---- lights: the corridor's strip, warm light in the rooms
		for (int a = 8; a <= 26; a += 3) {
			this.put(a, 0, F + 5, b(Blocks.SEA_LANTERN));
		}
		for (int[] p : new int[][] {{9, -6}, {12, -6}, {17, -7}, {23, -6}, {26, -7}, {9, 6}, {12, 6}, {17, 6}, {18, 9}}) {
			this.put(p[0], p[1], F + 5, b(Blocks.OCHRE_FROGLIGHT));
		}

		this.livingRoom();
		this.kitchen();
		this.bedroom();
		this.bathroom();
		this.commandRoom();
		this.plantRoom();
		this.garden();
		this.emergencyExit();
	}

	private void livingRoom() {
		// the screen on the end wall over a low cabinet
		this.box(6, 6, -8, -5, F + 1, F + 2, b(Blocks.BLACK_CONCRETE));
		for (int s = -8; s <= -5; s++) {
			this.put(7, s, F, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, this.f));
		}
		// sofa facing it, a coffee table between, rug, reading lamps
		for (int s = -9; s <= -4; s++) {
			this.stairs(11, s, Blocks.DARK_OAK_STAIRS, this.f);
		}
		this.tableTop(9, -7, Blocks.DARK_OAK_SLAB);
		this.tableTop(9, -6, Blocks.DARK_OAK_SLAB);
		for (int a = 8; a <= 10; a++) {
			for (int s = -9; s <= -4; s++) {
				if (this.level.getBlockState(this.at(a, s, F)).isAir()) {
					this.put(a, s, F, b(Blocks.GRAY_CARPET));
				}
			}
		}
		// bookshelves along the side wall, plants in the corners
		this.box(7, 13, -10, -10, F, F + 2, b(Blocks.BOOKSHELF));
		this.put(12, -4, F, b(Blocks.POTTED_FERN));
		this.put(13, -9, F, b(Blocks.POTTED_BAMBOO));
		this.put(7, -4, F, b(Blocks.POTTED_FERN));
		this.hangingLantern(10, -9);
		this.hangingLantern(10, -4);
	}

	private void kitchen() {
		// a fitted kitchen along the wall: cooker, cupboards, sink, oven, the larder
		this.put(15, -10, F, Blocks.SMOKER.defaultBlockState().setValue(SmokerBlock.FACING, this.right));
		this.put(16, -10, F, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
		this.water(17, -10, F);
		this.put(18, -10, F, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
		this.put(19, -10, F, Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, this.right));
		this.chest(20, -10, F, this.right,
			new ItemStack(Items.BREAD, 64), new ItemStack(Items.COOKED_BEEF, 32), new ItemStack(Items.BAKED_POTATO, 32),
			new ItemStack(Items.APPLE, 32), new ItemStack(Items.CARROT, 32), new ItemStack(Items.HONEY_BOTTLE, 8),
			new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.COOKED_CHICKEN, 32));
		for (int a = 15; a <= 20; a++) {
			this.put(a, -10, F + 2, Blocks.DARK_OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, this.right)
				.setValue(TrapDoorBlock.HALF, Half.TOP).setValue(TrapDoorBlock.OPEN, true)); // wall cupboards
		}
		// dining table with chairs
		for (int a = 16; a <= 19; a++) {
			this.tableTop(a, -6, Blocks.SPRUCE_SLAB);
			this.stairs(a, -7, Blocks.SPRUCE_STAIRS, this.left);
			this.stairs(a, -5, Blocks.SPRUCE_STAIRS, this.right);
		}
		this.hangingLantern(16, -6);
		this.hangingLantern(19, -6);
	}

	private void bedroom() {
		// a double bed against the far wall, night stands, wardrobe, rug
		this.bed(22, -10, Blocks.RED_BED, this.left);
		this.bed(23, -10, Blocks.RED_BED, this.left);
		this.put(24, -10, F, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
		this.put(24, -10, F + 1, b(Blocks.LANTERN));
		this.box(22, 23, -7, -6, F, F, b(Blocks.WHITE_CARPET));
		this.chest(24, -5, F, this.back, new ItemStack(Items.LEATHER_CHESTPLATE), new ItemStack(Items.WHITE_WOOL, 16));
		this.chest(24, -4, F, this.back);
		this.put(22, -3, F, b(Blocks.POTTED_FERN));
	}

	private void bathroom() {
		// bath, sink with a mirror over it, toilet in its corner
		this.water(26, -10, F);
		this.water(27, -10, F);
		this.water(26, -9, F);
		this.water(27, -9, F);
		this.water(27, -6, F);
		this.put(28, -6, F + 1, b(Blocks.LIGHT_BLUE_STAINED_GLASS)); // mirror
		this.put(27, -4, F, Blocks.QUARTZ_STAIRS.defaultBlockState().setValue(StairBlock.FACING, this.f));
		this.put(27, -4, F + 1, Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, this.back).setValue(TrapDoorBlock.OPEN, true));
		this.put(26, -7, F + 3, b(Blocks.IRON_BARS)); // shower head over the bath
	}

	private void commandRoom() {
		// a wall of glowing screens over a long console, the map table, the radio and the armoury
		for (int a = 8; a <= 12; a++) {
			this.put(a, 11, F + 1, b(Blocks.SEA_LANTERN));
			this.put(a, 11, F + 2, b(Blocks.SEA_LANTERN));
			this.put(a, 10, F + 1, b(Blocks.LIGHT_BLUE_STAINED_GLASS));
			this.put(a, 10, F + 2, b(Blocks.CYAN_STAINED_GLASS));
			this.tableTop(a, 9, Blocks.SPRUCE_SLAB);
			this.stairs(a, 8, Blocks.SPRUCE_STAIRS, this.left);
		}
		this.put(10, 5, F, b(Blocks.CARTOGRAPHY_TABLE));
		this.put(7, 9, F, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, this.back));
		this.put(13, 9, F, b(Blocks.NOTE_BLOCK)); // the radio set
		this.chest(7, 4, F, this.f, new ItemStack(ModRegistry.AK47), new ItemStack(ModRegistry.AK_MAG, 4), new ItemStack(ModRegistry.GLOCK),
			new ItemStack(ModRegistry.GLOCK_MAG, 3), new ItemStack(ModRegistry.SHOTGUN), new ItemStack(ModRegistry.SHOTGUN_SHELL, 24),
			new ItemStack(ModRegistry.GRENADE, 4), new ItemStack(ModRegistry.TARGET_DESIGNATOR));
		this.chest(7, 5, F, this.f, new ItemStack(Items.IRON_HELMET), new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.IRON_LEGGINGS),
			new ItemStack(Items.IRON_BOOTS), new ItemStack(Items.SHIELD));
	}

	private void plantRoom() {
		// diesel generator and the NBC filter it powers, exhaust up through the roof
		this.put(20, 9, F, ModRegistry.EMERGENCY_GENERATOR.defaultBlockState().setValue(GeneratorBlock.FACING, this.left));
		// the filter by the corridor wall, central enough to keep the whole hall's air clean
		this.put(18, 3, F, ModRegistry.AIR_FILTER.defaultBlockState().setValue(AirFilterBlock.FACING, this.right));
		for (int u = F + 1; u <= F + 5; u++) {
			this.put(20, 9, u, b(Blocks.IRON_BARS));
		}
		for (int u = F + 1; u <= F + 4; u++) {
			this.put(18, 3, u, b(Blocks.IRON_BARS)); // its intake duct
		}
		this.put(19, 10, F + 3, b(Blocks.IRON_BARS));
		// the water tank in the corner, glazed on its open sides
		this.box(15, 16, 9, 10, F, F + 2, b(Blocks.WATER));
		this.box(17, 17, 8, 10, F, F + 3, b(Blocks.GLASS));
		this.box(15, 16, 8, 8, F, F + 3, b(Blocks.GLASS));
		this.box(15, 16, 9, 10, F + 3, F + 3, b(Blocks.GLASS));
		// switchboard, fuel, and the shelter's stores
		this.box(20, 20, 3, 4, F, F + 1, b(Blocks.IRON_BLOCK));
		this.put(19, 4, F + 1, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, this.back));
		this.put(15, 4, F, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
		this.put(15, 5, F, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
		this.chest(15, 3, F, this.right,
			new ItemStack(ModRegistry.HAZMAT_HELMET), new ItemStack(ModRegistry.HAZMAT_SUIT), new ItemStack(ModRegistry.HAZMAT_LEGGINGS),
			new ItemStack(ModRegistry.HAZMAT_BOOTS), new ItemStack(ModRegistry.HAZMAT_HELMET), new ItemStack(ModRegistry.HAZMAT_SUIT),
			new ItemStack(ModRegistry.HAZMAT_LEGGINGS), new ItemStack(ModRegistry.HAZMAT_BOOTS), new ItemStack(ModRegistry.GEIGER_COUNTER),
			new ItemStack(ModRegistry.FIRST_AID_KIT, 4), new ItemStack(ModRegistry.TOURNIQUET, 4), new ItemStack(Items.TORCH, 32));
		this.put(16, 3, F, b(Blocks.CRAFTING_TABLE));
	}

	private void garden() {
		// hydroponic beds under grow lights: crops either side of a water channel, a walkway by the door
		this.box(22, 27, 3, 3, F - 1, F - 1, b(Blocks.POLISHED_ANDESITE));
		this.box(22, 27, 6, 6, F - 1, F - 1, b(Blocks.WATER));
		Block[] crops = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS};
		int k = 0;
		for (int s : new int[] {4, 5, 7, 8, 9}) {
			Block crop = crops[k++ % crops.length];
			for (int a = 22; a <= 27; a++) {
				this.put(a, s, F - 1, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
				BlockState plant = crop.defaultBlockState();
				if (plant.hasProperty(CropBlock.AGE)) {
					plant = plant.setValue(CropBlock.AGE, 7);
				} else if (plant.hasProperty(net.minecraft.world.level.block.BeetrootBlock.AGE)) {
					plant = plant.setValue(net.minecraft.world.level.block.BeetrootBlock.AGE, 3);
				}
				this.put(a, s, F, plant);
			}
		}
		for (int a = 22; a <= 27; a += 2) {
			for (int s : new int[] {5, 8}) {
				this.put(a, s, F + 5, b(Blocks.GLOWSTONE));
			}
		}
	}

	/** The way out if the hatch is buried: a tunnel through the far wall and a ladder up to the surface. */
	private void emergencyExit() {
		BlockPos column = this.at(30, 0, 0);
		int top = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 1 - this.hatch.getY();
		top = Math.max(F + 4, Math.min(top, 24));
		this.box(29, 31, -1, 1, F - 1, top - 1, b(ModRegistry.REINFORCED_CONCRETE));
		this.box(28, 30, 0, 0, F, F + 1, b(Blocks.AIR));
		this.box(30, 30, 0, 0, F + 2, top - 1, b(Blocks.AIR));
		this.door(28, 0, ModRegistry.BLAST_DOOR, this.f);
		for (int u = F; u <= top - 1; u++) {
			this.put(30, 0, u, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, this.back));
		}
		this.put(30, 0, top, Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, this.f).setValue(TrapDoorBlock.HALF, Half.TOP));
		this.put(30, 1, top, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR).setValue(LeverBlock.FACING, this.back));
	}
}
