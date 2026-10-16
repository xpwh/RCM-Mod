package de.rcm.ballistic.base;

import de.rcm.ballistic.BallisticMissiles;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.material.MapColor;

/** Everything military bases add: their blocks, block entities, the structure and its piece. */
public final class BaseRegistry {
	private static final List<Item> ITEMS = new ArrayList<>();

	public static final Block SANDBAGS = block("sandbags", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.SAND).strength(1.5F, 14.0F).sound(SoundType.SAND));
	public static final Block HESCO = block("hesco_barrier", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.SAND).strength(3.0F, 30.0F).sound(SoundType.GRAVEL));
	public static final Block JERSEY_BARRIER = block("jersey_barrier", BaseBlocks::jersey,
		BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(6.0F, 40.0F).sound(SoundType.STONE).requiresCorrectToolForDrops().noOcclusion());
	public static final Block CHAINLINK_FENCE = block("chainlink_fence", IronBarsBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.0F, 6.0F).sound(SoundType.CHAIN).requiresCorrectToolForDrops().noOcclusion());
	public static final Block BARBED_WIRE = block("barbed_wire", BaseBlocks.BarbedWire::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(1.0F, 4.0F).sound(SoundType.CHAIN).noOcclusion().forceSolidOn());
	public static final Block CAMO_NET = block("camo_net", BaseBlocks.CamoNet::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(0.3F).sound(SoundType.WOOL).noOcclusion().ignitedByLava());
	public static final Block MILITARY_CRATE = block("military_crate", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(2.0F, 4.0F).sound(SoundType.WOOD).ignitedByLava());
	public static final Block FLOODLIGHT = block("floodlight", p -> new BaseBlocks.Lamp(p, false, Block.box(3, 0, 3, 13, 14, 13)),
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.0F, 6.0F).sound(SoundType.METAL).noOcclusion());
	public static final Block ALARM_LIGHT = block("alarm_light", p -> new BaseBlocks.Lamp(p, true, Block.box(4, 0, 4, 12, 9, 12)),
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(1.0F, 4.0F).sound(SoundType.GLASS).noOcclusion());
	public static final Block CORRUGATED_OLIVE = block("corrugated_olive", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(3.0F, 8.0F).sound(SoundType.COPPER).requiresCorrectToolForDrops());
	public static final Block CORRUGATED_GREY = block("corrugated_grey", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY).strength(3.0F, 8.0F).sound(SoundType.COPPER).requiresCorrectToolForDrops());
	public static final Block STEEL_GRATE = block("steel_grate", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4.0F, 10.0F).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion());
	public static final Block FUEL_DRUM = block("fuel_drum", BaseBlocks.FuelDrum::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(1.5F, 2.0F).sound(SoundType.METAL).noOcclusion());
	public static final Block FIELD_GENERATOR = block("field_generator", BaseBlocks.FieldGenerator::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(4.0F, 8.0F).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion());
	public static final Block BASE_FLAG = block("base_flag", BaseBlocks.Flag::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(6.0F, 1200.0F).sound(SoundType.METAL).noOcclusion());

	public static final Block LANDMINE = block("landmine", SiteBlocks.Landmine::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(0.8F, 1.0F).sound(SoundType.METAL).noOcclusion().pushReaction(
			net.minecraft.world.level.material.PushReaction.DESTROY));
	public static final Block CARD_READER = block("card_reader", SiteBlocks.CardReader::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 1200.0F).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()
			.lightLevel(s -> 3));
	public static final Block ELEVATOR_PANEL = block("elevator_panel", SiteBlocks.ElevatorPanel::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 1200.0F).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()
			.lightLevel(s -> 5));
	public static final Item KEYCARD = item("keycard", new Item.Properties().stacksTo(1));

	public static final BlockEntityType<BaseBlocks.LampBlockEntity> LAMP_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("base_lamp"),
		FabricBlockEntityTypeBuilder.create(BaseBlocks.LampBlockEntity::new, FLOODLIGHT, ALARM_LIGHT).build()
	);
	public static final BlockEntityType<BaseFlagBlockEntity> FLAG_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("base_flag"),
		FabricBlockEntityTypeBuilder.create(BaseFlagBlockEntity::new, BASE_FLAG).build()
	);

	public static final StructureType<MilitaryBaseStructure> MILITARY_BASE = Registry.register(
		BuiltInRegistries.STRUCTURE_TYPE, BallisticMissiles.id("military_base"), () -> MilitaryBaseStructure.CODEC
	);
	public static final StructurePieceType MILITARY_BASE_PIECE = Registry.register(
		BuiltInRegistries.STRUCTURE_PIECE, BallisticMissiles.id("military_base"), (StructurePieceType.ContextlessType) MilitaryBasePiece::new
	);

	public static final CreativeModeTab TAB = Registry.register(
		BuiltInRegistries.CREATIVE_MODE_TAB,
		BallisticMissiles.id("base"),
		FabricItemGroup.builder()
			.title(Component.translatable("itemGroup.ballisticmissiles.base"))
			.icon(() -> new ItemStack(FLOODLIGHT))
			.displayItems((params, output) -> ITEMS.forEach(output::accept))
			.build()
	);

	private BaseRegistry() {
	}

	public static void init() {
		// Static initializers do the work.
	}

	private static Item item(String name, Item.Properties props) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, BallisticMissiles.id(name));
		Item item = Registry.register(BuiltInRegistries.ITEM, key, new Item(props.setId(key)));
		ITEMS.add(item);
		return item;
	}

	private static Block block(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties props) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, BallisticMissiles.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, BallisticMissiles.id(name));
		ITEMS.add(Registry.register(BuiltInRegistries.ITEM, itemKey,
			new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(itemKey))));
		return block;
	}
}
