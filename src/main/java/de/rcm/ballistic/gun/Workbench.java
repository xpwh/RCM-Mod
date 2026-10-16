package de.rcm.ballistic.gun;

import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The weapons workbench: a heavy steel bench with a vise and a tool board. Right-click opens its menu of
 * what can be made on it - attachments (the suppressor among them), ammunition and grenades - each from
 * plain materials, a little more economically than on a crafting table (the bench has the proper tools).
 * Making something takes the materials from the inventory and you hear it being made.
 */
public final class Workbench {
	public static final int ATTACHMENTS = 0;
	public static final int AMMO = 1;
	public static final int GRENADES = 2;
	/** Which sound the job makes. */
	public static final int METAL = 0;
	public static final int PRESS = 1;
	public static final int FUSE = 2;

	public record Ingredient(Supplier<Item> item, int count) {
	}

	public record Recipe(String id, int category, Supplier<Item> result, int count, int sound, List<Ingredient> ingredients) {
		public ItemStack output() {
			return new ItemStack(this.result.get(), this.count);
		}
	}

	private static final List<Recipe> RECIPES = new ArrayList<>();

	private Workbench() {
	}

	private static Ingredient of(Item item, int count) {
		return new Ingredient(() -> item, count);
	}

	private static Ingredient mod(Supplier<Item> item, int count) {
		return new Ingredient(item, count);
	}

	public static List<Recipe> recipes() {
		if (RECIPES.isEmpty()) {
			// attachments
			add("suppressor", ATTACHMENTS, () -> ModRegistry.SUPPRESSOR, 1, METAL, of(Items.IRON_INGOT, 4), of(Items.COAL, 2), of(Items.WHITE_WOOL, 1));
			add("weapon_light", ATTACHMENTS, () -> ModRegistry.WEAPON_LIGHT, 1, METAL, of(Items.IRON_INGOT, 2), of(Items.GLOWSTONE_DUST, 2), of(Items.REDSTONE, 1),
				of(Items.GLASS_PANE, 1));
			add("laser_module", ATTACHMENTS, () -> ModRegistry.LASER_MODULE, 1, METAL, of(Items.IRON_INGOT, 2), of(Items.AMETHYST_SHARD, 1), of(Items.REDSTONE, 1),
				of(Items.RED_STAINED_GLASS_PANE, 1));
			add("ir_laser_module", ATTACHMENTS, () -> ModRegistry.IR_LASER_MODULE, 1, METAL, of(Items.IRON_INGOT, 2), of(Items.AMETHYST_SHARD, 1),
				of(Items.REDSTONE, 1), of(Items.TINTED_GLASS, 1));
			// ammunition
			add("ak_magazine", AMMO, () -> ModRegistry.AK_MAG, 1, PRESS, of(Items.IRON_INGOT, 3), of(Items.COPPER_INGOT, 1), of(Items.GUNPOWDER, 2));
			add("ak_magazine_tracer", AMMO, () -> ModRegistry.AK_MAG_TRACER, 1, PRESS, of(Items.IRON_INGOT, 3), of(Items.COPPER_INGOT, 1),
				of(Items.GUNPOWDER, 2), of(Items.GLOWSTONE_DUST, 1));
			add("glock_magazine", AMMO, () -> ModRegistry.GLOCK_MAG, 1, PRESS, of(Items.IRON_NUGGET, 4), of(Items.COPPER_INGOT, 1), of(Items.GUNPOWDER, 1));
			add("glock_magazine_extended", AMMO, () -> ModRegistry.GLOCK_MAG_EXTENDED, 1, PRESS, of(Items.IRON_INGOT, 3), of(Items.COPPER_INGOT, 1),
				of(Items.GUNPOWDER, 2));
			add("shotgun_shell", AMMO, () -> ModRegistry.SHOTGUN_SHELL, 12, PRESS, of(Items.COPPER_INGOT, 1), of(Items.PAPER, 2), of(Items.GUNPOWDER, 1),
				of(Items.IRON_NUGGET, 3));
			add("rpg_rocket", AMMO, () -> ModRegistry.RPG_ROCKET, 3, FUSE, of(Items.TNT, 1), of(Items.GUNPOWDER, 1), of(Items.IRON_INGOT, 1));
			// grenades
			add("grenade", GRENADES, () -> ModRegistry.GRENADE, 3, FUSE, of(Items.IRON_INGOT, 2), of(Items.IRON_NUGGET, 1), of(Items.GUNPOWDER, 1));
			add("smoke_grenade", GRENADES, () -> ModRegistry.SMOKE_GRENADE, 3, FUSE, of(Items.IRON_INGOT, 1), of(Items.CHARCOAL, 2), of(Items.GUNPOWDER, 1));
			add("flashbang", GRENADES, () -> ModRegistry.FLASHBANG, 3, FUSE, of(Items.IRON_INGOT, 2), of(Items.GLOWSTONE_DUST, 1), of(Items.GUNPOWDER, 1));
		}
		return RECIPES;
	}

	private static void add(String id, int category, Supplier<Item> result, int count, int sound, Ingredient... ingredients) {
		RECIPES.add(new Recipe(id, category, result, count, sound, List.of(ingredients)));
	}

	/** How many of {@code item} the player carries. */
	public static int carried(Inventory inv, Item item) {
		int n = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.is(item)) {
				n += s.getCount();
			}
		}
		return n;
	}

	public static boolean canMake(Player player, Recipe r) {
		if (player.getAbilities().instabuild) {
			return true;
		}
		for (Ingredient in : r.ingredients()) {
			if (carried(player.getInventory(), in.item().get()) < in.count()) {
				return false;
			}
		}
		return true;
	}

	/** The player at the bench at {@code pos} makes recipe {@code index}. */
	static void make(ServerPlayer player, BlockPos pos, int index) {
		List<Recipe> all = recipes();
		if (index < 0 || index >= all.size() || player.distanceToSqr(pos.getCenter()) > 36.0
			|| !player.level().getBlockState(pos).is(ModRegistry.WEAPONS_WORKBENCH)) {
			return;
		}
		Recipe r = all.get(index);
		if (!canMake(player, r)) {
			return;
		}
		if (!player.getAbilities().instabuild) {
			Inventory inv = player.getInventory();
			for (Ingredient in : r.ingredients()) {
				int left = in.count();
				for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
					ItemStack s = inv.getItem(i);
					if (s.is(in.item().get())) {
						int take = Math.min(left, s.getCount());
						s.shrink(take);
						left -= take;
					}
				}
			}
		}
		player.getInventory().placeItemBackInInventory(r.output());
		SoundEvent sound = switch (r.sound()) {
			case PRESS -> ModRegistry.WORKBENCH_AMMO;
			case FUSE -> ModRegistry.WORKBENCH_GRENADE;
			default -> ModRegistry.WORKBENCH_METAL;
		};
		player.level().playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 0.95F + player.getRandom().nextFloat() * 0.1F);
	}

	// ------------------------------------------------------------------ the request from the menu

	public record MakePayload(BlockPos pos, int recipe) implements CustomPacketPayload {
		public static final Type<MakePayload> TYPE = new Type<>(BallisticMissiles.id("workbench_make"));
		public static final StreamCodec<RegistryFriendlyByteBuf, MakePayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, MakePayload::pos,
			ByteBufCodecs.VAR_INT, MakePayload::recipe,
			MakePayload::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.playC2S().register(MakePayload.TYPE, MakePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(MakePayload.TYPE, (payload, context) -> make(context.player(), payload.pos(), payload.recipe()));
	}

	// ------------------------------------------------------------------ the block

	public static class BenchBlock extends Block {
		public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
		private static final VoxelShape SHAPE = Shapes.or(Block.box(0, 11, 0, 16, 14, 16), Block.box(1, 0, 1, 3, 11, 3), Block.box(13, 0, 1, 15, 11, 3),
			Block.box(1, 0, 13, 3, 11, 15), Block.box(13, 0, 13, 15, 11, 15), Block.box(1, 3, 1, 15, 4, 15));

		public BenchBlock(Properties properties) {
			super(properties);
			this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(FACING);
		}

		@Override
		public BlockState getStateForPlacement(BlockPlaceContext context) {
			return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return SHAPE;
		}

		@Override
		protected BlockState rotate(BlockState state, Rotation rotation) {
			return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
		}

		@Override
		protected BlockState mirror(BlockState state, Mirror mirror) {
			return state.rotate(mirror.getRotation(state.getValue(FACING)));
		}

		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			if (level.isClientSide()) {
				de.rcm.ballistic.ClientHooks.openWorkbench.accept(pos.immutable());
			} else {
				level.playSound(null, pos, ModRegistry.WORKBENCH_OPEN, SoundSource.BLOCKS, 0.8F, 1.0F);
			}
			return InteractionResult.SUCCESS;
		}
	}
}
