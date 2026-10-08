package de.rcm.ballistic.bunker;

import de.rcm.ballistic.ModRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * Fallout shelter in a box. Placed on the ground, it digs and builds a complete buried bunker in the
 * direction you face: a hatch and a ladder shaft down eight metres, an airlock between two blast
 * doors, and a reinforced-concrete room with lights, bunks, a supply chest (radiation suit, Geiger
 * counter, food), a workbench and stove, the NBC air filter and the emergency generator that runs it.
 */
public class BunkerBlock extends Block {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	/** Floor of the bunker below the hatch (the hatch is the ground block under the kit). */
	private static final int FLOOR = -8;

	public BunkerBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection());
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (!level.isClientSide() && !oldState.is(this)) {
			level.scheduleTick(pos, this, 2);
		}
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		level.removeBlock(pos, false);
		build(level, pos.below(), state.getValue(FACING));
		level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0F, 0.6F);
		for (var player : level.players()) {
			if (player.blockPosition().distSqr(pos) < 32 * 32) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.bunker_built").withStyle(ChatFormatting.GREEN), true);
			}
		}
	}

	/** Position {@code along} blocks forward, {@code side} to the right and {@code up} up from the hatch. */
	private static BlockPos at(BlockPos hatch, Direction f, int along, int side, int up) {
		return hatch.relative(f, along).relative(f.getClockWise(), side).above(up);
	}

	private static void set(ServerLevel level, BlockPos p, BlockState state) {
		BlockState old = level.getBlockState(p);
		if (old.getDestroySpeed(level, p) < 0.0F || old.hasBlockEntity() && !state.hasBlockEntity()) {
			return; // bedrock, or someone's chest: leave it
		}
		level.setBlock(p, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
	}

	private static void fill(ServerLevel level, BlockPos hatch, Direction f, int a0, int a1, int s0, int s1, int u0, int u1, BlockState state) {
		for (int a = a0; a <= a1; a++) {
			for (int s = s0; s <= s1; s++) {
				for (int u = u0; u <= u1; u++) {
					set(level, at(hatch, f, a, s, u), state);
				}
			}
		}
	}

	public static void build(ServerLevel level, BlockPos hatch, Direction f) {
		BlockState concrete = ModRegistry.REINFORCED_CONCRETE.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		int floor = FLOOR;
		// ---- shell: room, airlock corridor, shaft collar
		fill(level, hatch, f, 4, 14, -4, 4, floor - 1, floor + 4, concrete);
		fill(level, hatch, f, -1, 4, -1, 1, floor - 1, floor + 2, concrete);
		fill(level, hatch, f, -1, 1, -1, 1, floor + 3, 0, concrete);
		// ---- hollow it out
		fill(level, hatch, f, 5, 13, -3, 3, floor, floor + 3, air);
		fill(level, hatch, f, 0, 4, 0, 0, floor, floor + 1, air);
		fill(level, hatch, f, 0, 0, 0, 0, floor + 2, -1, air);
		// ---- hatch and ladder
		Direction back = f.getOpposite();
		set(level, hatch, Blocks.IRON_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, back).setValue(TrapDoorBlock.HALF, Half.TOP));
		// the steel hatch is opened with a lever beside it, in reach from outside and from the ladder
		set(level, at(hatch, f, 0, 1, 0), Blocks.LEVER.defaultBlockState()
			.setValue(net.minecraft.world.level.block.LeverBlock.FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
			.setValue(net.minecraft.world.level.block.LeverBlock.FACING, f));
		for (int u = floor; u <= -1; u++) {
			set(level, at(hatch, f, 0, 0, u), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, f));
		}
		// ---- airlock: two blast doors with the decontamination space between them
		for (int a : new int[] {1, 4}) {
			BlockState door = ModRegistry.BLAST_DOOR.defaultBlockState().setValue(DoorBlock.FACING, f);
			set(level, at(hatch, f, a, 0, floor), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
			set(level, at(hatch, f, a, 0, floor + 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		}
		set(level, at(hatch, f, 2, 0, floor + 2), Blocks.SEA_LANTERN.defaultBlockState()); // airlock light
		// ---- room: lights, bunks, supplies, workbench, stove, filter and generator
		for (int a : new int[] {7, 11}) {
			for (int s : new int[] {-2, 2}) {
				set(level, at(hatch, f, a, s, floor + 4), Blocks.SEA_LANTERN.defaultBlockState());
			}
		}
		for (int a : new int[] {6, 9}) {
			BlockState bed = Blocks.GRAY_BED.defaultBlockState().setValue(BedBlock.FACING, back);
			set(level, at(hatch, f, a, -3, floor), bed.setValue(BedBlock.PART, BedPart.HEAD));
			set(level, at(hatch, f, a + 1, -3, floor), bed.setValue(BedBlock.PART, BedPart.FOOT));
		}
		Direction right = f.getClockWise();
		BlockPos chestPos = at(hatch, f, 12, -3, floor);
		set(level, chestPos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, right));
		if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
			chest.setItem(0, new ItemStack(ModRegistry.HAZMAT_HELMET));
			chest.setItem(1, new ItemStack(ModRegistry.HAZMAT_SUIT));
			chest.setItem(2, new ItemStack(ModRegistry.HAZMAT_LEGGINGS));
			chest.setItem(3, new ItemStack(ModRegistry.HAZMAT_BOOTS));
			chest.setItem(4, new ItemStack(ModRegistry.GEIGER_COUNTER));
			chest.setItem(9, new ItemStack(net.minecraft.world.item.Items.BREAD, 16));
			chest.setItem(10, new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET));
			chest.setItem(11, new ItemStack(net.minecraft.world.item.Items.TORCH, 16));
		}
		set(level, at(hatch, f, 12, -2, floor), Blocks.CRAFTING_TABLE.defaultBlockState());
		set(level, at(hatch, f, 12, 3, floor), Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING, right.getOpposite()));
		set(level, at(hatch, f, 13, 0, floor), ModRegistry.AIR_FILTER.defaultBlockState().setValue(AirFilterBlock.FACING, back));
		set(level, at(hatch, f, 13, 2, floor), ModRegistry.EMERGENCY_GENERATOR.defaultBlockState().setValue(GeneratorBlock.FACING, back));
		// exhaust pipe from the generator up through the roof
		for (int u = floor + 1; u <= floor + 4; u++) {
			set(level, at(hatch, f, 13, 2, u), Blocks.IRON_BARS.defaultBlockState());
		}
	}
}
