package de.rcm.ballistic.block;

import de.rcm.ballistic.ModRegistry;
import de.rcm.ballistic.entity.MissileEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Inclined canister launcher for the slim missiles (Harpoon / Kh-35 coastal launcher style): a sealed
 * launch canister on a raised frame, tilted up at {@link #PITCH} degrees towards where the block was
 * placed facing. The missile lies in the canister; the booster fires it out up the rail. The block is the
 * concrete footing; frame and canister are drawn by its renderer.
 */
public class InclinedLauncherBlock extends Block implements EntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	/** Elevation of the canister, degrees. */
	public static final float PITCH = 32.0F;
	/** The canister: its rear end (above the block's middle, set back) and its length. */
	public static final double REAR_BACK = 1.6;
	public static final double REAR_HEIGHT = 0.95;
	public static final double CANISTER_LENGTH = 5.6;
	/** How far up the canister the missile's tail lies. */
	public static final double TAIL_IN = 0.55;

	private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 5, 16);

	public InclinedLauncherBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.NORTH));
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
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new InclinedLauncherBlockEntity(pos, state);
	}

	/** The missile lying in this launcher's canister, if any. */
	public static @Nullable MissileEntity loaded(Level level, BlockPos pos, Direction facing) {
		Vec3 tail = missileTail(pos, facing);
		for (MissileEntity m : level.getEntitiesOfClass(MissileEntity.class, new AABB(tail, tail).inflate(0.8))) {
			if (m.inCanister()) {
				return m;
			}
		}
		return null;
	}

	/** Clicking the footing with the designator (or empty-handed, sneaking) works on the missile in the canister. */
	@Override
	protected InteractionResult useItemOn(
		ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit
	) {
		if (!stack.is(ModRegistry.TARGET_DESIGNATOR) && !(stack.isEmpty() && player.isShiftKeyDown())) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		MissileEntity missile = loaded(level, pos, state.getValue(FACING));
		if (missile == null) {
			if (stack.is(ModRegistry.TARGET_DESIGNATOR) && !level.isClientSide()) {
				player.displayClientMessage(Component.translatable("message.ballisticmissiles.canister_empty").withStyle(ChatFormatting.YELLOW), true);
			}
			return stack.is(ModRegistry.TARGET_DESIGNATOR) ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		return missile.interact(player, hand);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	/** Up the canister (unit vector). */
	public static Vec3 railDirection(Direction facing) {
		double p = Math.toRadians(PITCH);
		return new Vec3(facing.getStepX() * Math.cos(p), Math.sin(p), facing.getStepZ() * Math.cos(p));
	}

	/** The canister's rear end (world space). */
	public static Vec3 rear(BlockPos pos, Direction facing) {
		return new Vec3(pos.getX() + 0.5 - facing.getStepX() * REAR_BACK, pos.getY() + REAR_HEIGHT, pos.getZ() + 0.5 - facing.getStepZ() * REAR_BACK);
	}

	/** Where a missile's tail lies in the canister. */
	public static Vec3 missileTail(BlockPos pos, Direction facing) {
		return rear(pos, facing).add(railDirection(facing).scale(TAIL_IN));
	}
}
