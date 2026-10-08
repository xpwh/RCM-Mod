package de.rcm.ballistic;

import de.rcm.ballistic.block.AirDefenseBlock;
import de.rcm.ballistic.block.AirDefenseBlockEntity;
import de.rcm.ballistic.block.CiwsBlock;
import de.rcm.ballistic.block.CiwsBlockEntity;
import de.rcm.ballistic.block.JammerBlock;
import de.rcm.ballistic.block.JammerBlockEntity;
import de.rcm.ballistic.block.LaserDefenseBlock;
import de.rcm.ballistic.block.LaserDefenseBlockEntity;
import de.rcm.ballistic.block.LaunchPadBlock;
import de.rcm.ballistic.block.LaunchPadBlockEntity;
import de.rcm.ballistic.block.MissileSiloBlock;
import de.rcm.ballistic.block.MissileSiloBlockEntity;
import de.rcm.ballistic.block.RadarBlock;
import de.rcm.ballistic.block.RadarBlockEntity;
import de.rcm.ballistic.block.SubmarineBlock;
import de.rcm.ballistic.entity.AerialBombEntity;
import de.rcm.ballistic.entity.BombletEntity;
import de.rcm.ballistic.entity.InterceptorEntity;
import de.rcm.ballistic.entity.JetEntity;
import de.rcm.ballistic.entity.MeteorEntity;
import de.rcm.ballistic.entity.ReentryVehicleEntity;
import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.MissileType;
import de.rcm.ballistic.entity.MobileLauncherEntity;
import de.rcm.ballistic.item.AirstrikeRadioItem;
import de.rcm.ballistic.item.GeigerCounterItem;
import de.rcm.ballistic.item.LauncherLink;
import de.rcm.ballistic.item.MissileItem;
import de.rcm.ballistic.item.MobileLauncherItem;
import de.rcm.ballistic.item.SavedTarget;
import de.rcm.ballistic.item.TargetData;
import de.rcm.ballistic.item.TargetDesignatorItem;
import com.mojang.serialization.Codec;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

public final class ModRegistry {
	// ---------- Data components ----------
	public static final DataComponentType<TargetData> TARGET = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		BallisticMissiles.id("target"),
		DataComponentType.<TargetData>builder().persistent(TargetData.CODEC).networkSynchronized(TargetData.STREAM_CODEC).build()
	);
	public static final DataComponentType<List<SavedTarget>> SAVED_TARGETS = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		BallisticMissiles.id("saved_targets"),
		DataComponentType.<List<SavedTarget>>builder()
			.persistent(SavedTarget.CODEC.listOf())
			.networkSynchronized(SavedTarget.STREAM_CODEC.apply(ByteBufCodecs.list()))
			.build()
	);
	/** Which aircraft the airstrike radio calls (index into {@link de.rcm.ballistic.item.AirstrikeRadioItem.Mode}). */
	public static final DataComponentType<Integer> AIRSTRIKE_MODE = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		BallisticMissiles.id("airstrike_mode"),
		DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build()
	);
	public static final DataComponentType<List<LauncherLink>> LINKS = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		BallisticMissiles.id("linked_launchers"),
		DataComponentType.<List<LauncherLink>>builder()
			.persistent(LauncherLink.CODEC.listOf())
			.networkSynchronized(LauncherLink.STREAM_CODEC.apply(ByteBufCodecs.list()))
			.build()
	);

	// ---------- Entities ----------
	private static final Map<MissileType, EntityType<MissileEntity>> MISSILE_ENTITIES = new EnumMap<>(MissileType.class);

	static {
		for (MissileType type : MissileType.values()) {
			MISSILE_ENTITIES.put(
				type,
				registerEntity(
					type.id,
					EntityType.Builder.<MissileEntity>of((entityType, level) -> new MissileEntity(entityType, level, type), MobCategory.MISC)
						.sized(type.width, type.height)
						.clientTrackingRange(32)
						.updateInterval(1)
						.fireImmune()
						.noLootTable()
				)
			);
		}
	}

	public static final EntityType<BombletEntity> BOMBLET = registerEntity(
		"bomblet",
		EntityType.Builder.<BombletEntity>of(BombletEntity::new, MobCategory.MISC).sized(0.35F, 0.35F).clientTrackingRange(16).updateInterval(1).noLootTable()
	);

	public static final EntityType<ReentryVehicleEntity> REENTRY_VEHICLE = registerEntity(
		"reentry_vehicle",
		EntityType.Builder.<ReentryVehicleEntity>of(ReentryVehicleEntity::new, MobCategory.MISC).sized(0.8F, 0.8F).clientTrackingRange(32).updateInterval(1).noLootTable()
	);
	public static final EntityType<InterceptorEntity> INTERCEPTOR = registerEntity(
		"interceptor",
		EntityType.Builder.<InterceptorEntity>of(InterceptorEntity::new, MobCategory.MISC).sized(0.4F, 0.4F).clientTrackingRange(24).updateInterval(1).noLootTable()
	);
	public static final EntityType<MobileLauncherEntity> MOBILE_LAUNCHER = registerEntity(
		"mobile_launcher",
		EntityType.Builder.<MobileLauncherEntity>of(MobileLauncherEntity::new, MobCategory.MISC).sized(3.0F, 2.8F).clientTrackingRange(16).updateInterval(1).noLootTable()
	);

	public static final EntityType<JetEntity> JET = registerEntity(
		"strike_jet",
		EntityType.Builder.<JetEntity>of(JetEntity::new, MobCategory.MISC).sized(4.0F, 1.6F).clientTrackingRange(48).updateInterval(1).fireImmune().noLootTable()
	);
	public static final EntityType<MeteorEntity> METEOR = registerEntity(
		"meteor",
		EntityType.Builder.<MeteorEntity>of(MeteorEntity::new, MobCategory.MISC).sized(1.2F, 1.2F).clientTrackingRange(32).updateInterval(1).fireImmune().noLootTable()
	);
	public static final EntityType<de.rcm.ballistic.entity.RocketEntity> ROCKET = registerEntity(
		"rocket",
		EntityType.Builder.<de.rcm.ballistic.entity.RocketEntity>of(de.rcm.ballistic.entity.RocketEntity::new, MobCategory.MISC).sized(0.4F, 0.4F).clientTrackingRange(24).updateInterval(1).noLootTable()
	);
	public static final EntityType<de.rcm.ballistic.entity.SpentStageEntity> SPENT_STAGE = registerEntity(
		"spent_stage",
		EntityType.Builder.<de.rcm.ballistic.entity.SpentStageEntity>of(de.rcm.ballistic.entity.SpentStageEntity::new, MobCategory.MISC).sized(1.0F, 1.0F)
			.clientTrackingRange(32).updateInterval(1).fireImmune().noLootTable()
	);
	public static final EntityType<de.rcm.ballistic.entity.DestroyerEntity> DESTROYER = registerEntity(
		"destroyer",
		EntityType.Builder.<de.rcm.ballistic.entity.DestroyerEntity>of(de.rcm.ballistic.entity.DestroyerEntity::new, MobCategory.MISC).sized(5.0F, 6.0F)
			.clientTrackingRange(32).updateInterval(1).fireImmune()
	);
	public static final EntityType<de.rcm.ballistic.entity.RpgRocketEntity> RPG_GRENADE = registerEntity(
		"rpg_grenade",
		EntityType.Builder.<de.rcm.ballistic.entity.RpgRocketEntity>of(de.rcm.ballistic.entity.RpgRocketEntity::new, MobCategory.MISC).sized(0.25F, 0.25F)
			.clientTrackingRange(16).updateInterval(1).noLootTable()
	);
	public static final EntityType<AerialBombEntity> AERIAL_BOMB = registerEntity(
		"aerial_bomb",
		EntityType.Builder.<AerialBombEntity>of(AerialBombEntity::new, MobCategory.MISC).sized(0.45F, 0.45F).clientTrackingRange(24).updateInterval(1).noLootTable()
	);

	// ---------- Blocks ----------
	public static final Block LAUNCH_PAD = registerBlock(
		"launch_pad",
		LaunchPadBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 1200.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion()
	);

	public static final Block RADAR = registerBlock(
		"radar",
		RadarBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4.0F, 600.0F).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()
	);
	public static final Block AIR_DEFENSE = registerBlock(
		"air_defense",
		AirDefenseBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(5.0F, 1200.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion()
	);

	public static final Block CIWS = registerBlock(
		"ciws",
		CiwsBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 1200.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion()
	);

	public static final Block LASER_DEFENSE = registerBlock(
		"laser_defense",
		LaserDefenseBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 1200.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion()
	);
	public static final Block SEA_MINE = registerBlock(
		"sea_mine",
		de.rcm.ballistic.block.SeaMineBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.5F, 6.0F).sound(SoundType.METAL).noOcclusion()
	);
	public static final Block IRON_DOME = registerBlock(
		"iron_dome",
		props -> new de.rcm.ballistic.block.DefenseSiteBlock(props, () -> ModRegistry.IRON_DOME_BE, de.rcm.ballistic.block.IronDomeBlockEntity::new),
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(5.0F, 1200.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion()
	);
	public static final Block COMMAND_CENTER = registerBlock(
		"command_center",
		de.rcm.ballistic.block.CommandCenterBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 1200.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops().noOcclusion().lightLevel(s -> 7)
	);
	public static final Block DECOY_LAUNCHER = registerBlock(
		"decoy_launcher",
		props -> new de.rcm.ballistic.block.DefenseSiteBlock(props, () -> ModRegistry.DECOY_LAUNCHER_BE, de.rcm.ballistic.block.DecoyLauncherBlockEntity::new),
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(4.0F, 600.0F).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()
	);
	public static final Block JAMMER = registerBlock(
		"jammer",
		JammerBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(4.0F, 600.0F).sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()
	);

	/** Blast-rated steel door: opens by hand, survives a nuclear blast outside the crater core. */
	public static final BlockSetType BLAST_DOOR_TYPE = new BlockSetType(
		"ballisticmissiles_blast", true, false, false, BlockSetType.PressurePlateSensitivity.MOBS, SoundType.NETHERITE_BLOCK,
		SoundEvents.IRON_DOOR_CLOSE, SoundEvents.IRON_DOOR_OPEN, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundEvents.IRON_TRAPDOOR_OPEN,
		SoundEvents.STONE_PRESSURE_PLATE_CLICK_OFF, SoundEvents.STONE_PRESSURE_PLATE_CLICK_ON, SoundEvents.STONE_BUTTON_CLICK_OFF,
		SoundEvents.STONE_BUTTON_CLICK_ON
	);
	public static final Block REINFORCED_CONCRETE = registerBlock(
		"reinforced_concrete",
		Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(25.0F, 3600.0F).sound(SoundType.STONE).requiresCorrectToolForDrops()
	);
	public static final Block BLAST_DOOR = registerBlock(
		"blast_door",
		props -> new DoorBlock(BLAST_DOOR_TYPE, props),
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(25.0F, 3600.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops()
			.noOcclusion().pushReaction(PushReaction.BLOCK)
	);

	public static final Block BUNKER = registerBlock(
		"bunker", de.rcm.ballistic.bunker.BunkerBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(5.0F, 600.0F).sound(SoundType.STONE)
	);
	public static final Block AIR_FILTER = registerBlock(
		"air_filter", de.rcm.ballistic.bunker.AirFilterBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(4.0F, 600.0F).sound(SoundType.METAL).requiresCorrectToolForDrops()
			.lightLevel(s -> s.getValue(de.rcm.ballistic.bunker.AirFilterBlock.LIT) ? 5 : 0)
	);
	public static final Block EMERGENCY_GENERATOR = registerBlock(
		"emergency_generator", de.rcm.ballistic.bunker.GeneratorBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(4.0F, 600.0F).sound(SoundType.METAL).requiresCorrectToolForDrops()
			.lightLevel(s -> s.getValue(de.rcm.ballistic.bunker.GeneratorBlock.LIT) ? 7 : 0)
	);

	public static final Block MISSILE_SILO = registerBlock(
		"missile_silo",
		MissileSiloBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(8.0F, 3600.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops()
	);

	/** Ballistic missile submarine lying on the sea floor; works like a silo and fires from underwater. */
	public static final Block SUBMARINE = registerBlock(
		"submarine",
		SubmarineBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(8.0F, 3600.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops()
			.noOcclusion()
	);

	// ---------- Wasteland: what blasts, fireballs and fallout leave behind ----------
	public static final Block SCORCHED_EARTH = registerBlock(
		"scorched_earth", Block::new, BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(0.6F).sound(SoundType.GRAVEL)
	);
	public static final Block SMOLDERING_EARTH = registerBlock(
		"smoldering_earth", de.rcm.ballistic.block.SmolderingBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(0.6F).sound(SoundType.GRAVEL)
			.lightLevel(de.rcm.ballistic.block.SmolderingBlock::light).emissiveRendering((s, l, p) -> true)
	);
	public static final Block CHARRED_LOG = registerBlock(
		"charred_log", net.minecraft.world.level.block.RotatedPillarBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.2F).sound(SoundType.WOOD).ignitedByLava()
	);
	public static final Block CRATER_GLASS = registerBlock(
		"crater_glass", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(6.0F, 40.0F).sound(SoundType.GLASS).requiresCorrectToolForDrops()
	);
	public static final Block MOLTEN_ROCK = registerBlock(
		"molten_rock", de.rcm.ballistic.block.MoltenRockBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(3.0F, 20.0F).sound(SoundType.BASALT).requiresCorrectToolForDrops()
			.lightLevel(s -> 12).emissiveRendering((s, l, p) -> true).isValidSpawn((s, l, p, e) -> false)
	);
	public static final Block TRINITITE = registerBlock(
		"trinitite", Block::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GREEN).strength(1.5F, 6.0F).sound(SoundType.GLASS).requiresCorrectToolForDrops()
	);
	public static final Block FALLOUT = registerBlock(
		"fallout", props -> new de.rcm.ballistic.block.DustLayerBlock(true, props),
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY).strength(0.1F).sound(SoundType.SAND).replaceable().pushReaction(PushReaction.DESTROY)
	);
	public static final Block ASH = registerBlock(
		"ash", props -> new de.rcm.ballistic.block.DustLayerBlock(false, props),
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(0.1F).sound(SoundType.SAND).replaceable().pushReaction(PushReaction.DESTROY)
	);

	public static final BlockEntityType<LaunchPadBlockEntity> LAUNCH_PAD_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("launch_pad"), FabricBlockEntityTypeBuilder.create(LaunchPadBlockEntity::new, LAUNCH_PAD).build()
	);
	public static final BlockEntityType<RadarBlockEntity> RADAR_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("radar"), FabricBlockEntityTypeBuilder.create(RadarBlockEntity::new, RADAR).build()
	);
	public static final BlockEntityType<AirDefenseBlockEntity> AIR_DEFENSE_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("air_defense"), FabricBlockEntityTypeBuilder.create(AirDefenseBlockEntity::new, AIR_DEFENSE).build()
	);
	public static final BlockEntityType<de.rcm.ballistic.block.IronDomeBlockEntity> IRON_DOME_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("iron_dome"), FabricBlockEntityTypeBuilder.create(de.rcm.ballistic.block.IronDomeBlockEntity::new, IRON_DOME).build()
	);
	public static final BlockEntityType<de.rcm.ballistic.block.CommandCenterBlockEntity> COMMAND_CENTER_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("command_center"), FabricBlockEntityTypeBuilder.create(de.rcm.ballistic.block.CommandCenterBlockEntity::new, COMMAND_CENTER).build()
	);
	public static final BlockEntityType<de.rcm.ballistic.bunker.AirFilterBlockEntity> AIR_FILTER_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("air_filter"), FabricBlockEntityTypeBuilder.create(de.rcm.ballistic.bunker.AirFilterBlockEntity::new, AIR_FILTER).build()
	);
	public static final BlockEntityType<de.rcm.ballistic.block.SeaMineBlockEntity> SEA_MINE_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("sea_mine"), FabricBlockEntityTypeBuilder.create(de.rcm.ballistic.block.SeaMineBlockEntity::new, SEA_MINE).build()
	);
	public static final BlockEntityType<de.rcm.ballistic.block.DecoyLauncherBlockEntity> DECOY_LAUNCHER_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("decoy_launcher"), FabricBlockEntityTypeBuilder.create(de.rcm.ballistic.block.DecoyLauncherBlockEntity::new, DECOY_LAUNCHER).build()
	);
	public static final BlockEntityType<CiwsBlockEntity> CIWS_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("ciws"), FabricBlockEntityTypeBuilder.create(CiwsBlockEntity::new, CIWS).build()
	);
	public static final BlockEntityType<LaserDefenseBlockEntity> LASER_DEFENSE_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("laser_defense"), FabricBlockEntityTypeBuilder.create(LaserDefenseBlockEntity::new, LASER_DEFENSE).build()
	);
	public static final BlockEntityType<JammerBlockEntity> JAMMER_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("jammer"), FabricBlockEntityTypeBuilder.create(JammerBlockEntity::new, JAMMER).build()
	);
	public static final BlockEntityType<MissileSiloBlockEntity> MISSILE_SILO_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("missile_silo"), FabricBlockEntityTypeBuilder.create(MissileSiloBlockEntity::new, MISSILE_SILO, SUBMARINE).build()
	);

	// ---------- Items ----------
	public static final Item LAUNCH_PAD_ITEM = registerItem(
		"launch_pad", props -> new BlockItem(LAUNCH_PAD, props), new Item.Properties().useBlockDescriptionPrefix()
	);
	private static final Map<MissileType, Item> MISSILE_ITEMS = new EnumMap<>(MissileType.class);

	static {
		for (MissileType type : MissileType.values()) {
			MISSILE_ITEMS.put(type, registerItem(type.id, props -> new MissileItem(type, props), new Item.Properties().stacksTo(1).rarity(type.rarity)));
		}
	}

	public static final Item TARGET_DESIGNATOR = registerItem(
		"target_designator", TargetDesignatorItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.RARE)
	);
	public static final Item RADAR_ITEM = registerItem("radar", props -> new de.rcm.ballistic.item.DefenseBlockItem(RADAR, props, "radar"), new Item.Properties().useBlockDescriptionPrefix());
	public static final Item AIR_DEFENSE_ITEM = registerItem(
		"air_defense", props -> new de.rcm.ballistic.item.DefenseBlockItem(AIR_DEFENSE, props, "air_defense"), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.RARE)
	);
	public static final Item CIWS_ITEM = registerItem(
		"ciws", props -> new de.rcm.ballistic.item.DefenseBlockItem(CIWS, props, "ciws"), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.RARE)
	);
	public static final Item LASER_DEFENSE_ITEM = registerItem(
		"laser_defense", props -> new de.rcm.ballistic.item.DefenseBlockItem(LASER_DEFENSE, props, "laser_defense"), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.EPIC)
	);
	public static final Item IRON_DOME_ITEM = registerItem(
		"iron_dome", props -> new de.rcm.ballistic.item.DefenseBlockItem(IRON_DOME, props, "iron_dome"), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.EPIC)
	);
	public static final Item COMMAND_CENTER_ITEM = registerItem(
		"command_center", props -> new BlockItem(COMMAND_CENTER, props), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.EPIC)
	);
	public static final Item DECOY_LAUNCHER_ITEM = registerItem(
		"decoy_launcher", props -> new de.rcm.ballistic.item.DefenseBlockItem(DECOY_LAUNCHER, props, "decoy_launcher"), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.RARE)
	);
	public static final Item SEA_MINE_ITEM = registerItem(
		"sea_mine", props -> new de.rcm.ballistic.item.DefenseBlockItem(SEA_MINE, props, "sea_mine"), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.UNCOMMON)
	);
	public static final Item JAMMER_ITEM = registerItem(
		"jammer", props -> new de.rcm.ballistic.item.DefenseBlockItem(JAMMER, props, "jammer"), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.RARE)
	);
	public static final Item AIRSTRIKE_RADIO = registerItem(
		"airstrike_radio", AirstrikeRadioItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)
	);
	public static final Item REINFORCED_CONCRETE_ITEM = registerItem(
		"reinforced_concrete", props -> new BlockItem(REINFORCED_CONCRETE, props), new Item.Properties().useBlockDescriptionPrefix()
	);
	public static final Item BLAST_DOOR_ITEM = registerItem(
		"blast_door", props -> new DoubleHighBlockItem(BLAST_DOOR, props), new Item.Properties().useBlockDescriptionPrefix()
	);

	public static final Item BUNKER_ITEM = registerItem(
		"bunker", props -> new de.rcm.ballistic.item.DefenseBlockItem(BUNKER, props, "bunker"), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.EPIC)
	);
	public static final Item AIR_FILTER_ITEM = registerItem("air_filter", props -> new BlockItem(AIR_FILTER, props), new Item.Properties().useBlockDescriptionPrefix());
	public static final Item EMERGENCY_GENERATOR_ITEM = registerItem(
		"emergency_generator", props -> new BlockItem(EMERGENCY_GENERATOR, props), new Item.Properties().useBlockDescriptionPrefix()
	);
	public static final Item SCORCHED_EARTH_ITEM = registerItem("scorched_earth", props -> new BlockItem(SCORCHED_EARTH, props), new Item.Properties().useBlockDescriptionPrefix());
	public static final Item SMOLDERING_EARTH_ITEM = registerItem("smoldering_earth", props -> new BlockItem(SMOLDERING_EARTH, props), new Item.Properties().useBlockDescriptionPrefix());
	public static final Item CHARRED_LOG_ITEM = registerItem("charred_log", props -> new BlockItem(CHARRED_LOG, props), new Item.Properties().useBlockDescriptionPrefix());
	public static final Item CRATER_GLASS_ITEM = registerItem("crater_glass", props -> new BlockItem(CRATER_GLASS, props), new Item.Properties().useBlockDescriptionPrefix());
	public static final Item MOLTEN_ROCK_ITEM = registerItem("molten_rock", props -> new BlockItem(MOLTEN_ROCK, props), new Item.Properties().useBlockDescriptionPrefix());
	public static final Item TRINITITE_ITEM = registerItem(
		"trinitite", props -> new BlockItem(TRINITITE, props), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.UNCOMMON)
	);
	public static final Item FALLOUT_ITEM = registerItem("fallout", props -> new BlockItem(FALLOUT, props), new Item.Properties().useBlockDescriptionPrefix());
	public static final Item ASH_ITEM = registerItem("ash", props -> new BlockItem(ASH, props), new Item.Properties().useBlockDescriptionPrefix());

	public static final ArmorMaterial HAZMAT_MATERIAL = new ArmorMaterial(
		15, Map.of(ArmorType.HELMET, 1, ArmorType.CHESTPLATE, 3, ArmorType.LEGGINGS, 2, ArmorType.BOOTS, 1, ArmorType.BODY, 3), 9,
		SoundEvents.ARMOR_EQUIP_LEATHER, 0.0F, 0.0F, ItemTags.REPAIRS_LEATHER_ARMOR,
		ResourceKey.create(EquipmentAssets.ROOT_ID, BallisticMissiles.id("hazmat"))
	);
	public static final Item HAZMAT_HELMET = registerItem("hazmat_helmet", Item::new, new Item.Properties().humanoidArmor(HAZMAT_MATERIAL, ArmorType.HELMET));
	public static final Item HAZMAT_SUIT = registerItem("hazmat_suit", Item::new, new Item.Properties().humanoidArmor(HAZMAT_MATERIAL, ArmorType.CHESTPLATE));
	public static final Item HAZMAT_LEGGINGS = registerItem("hazmat_leggings", Item::new, new Item.Properties().humanoidArmor(HAZMAT_MATERIAL, ArmorType.LEGGINGS));
	public static final Item HAZMAT_BOOTS = registerItem("hazmat_boots", Item::new, new Item.Properties().humanoidArmor(HAZMAT_MATERIAL, ArmorType.BOOTS));

	public static final Item GEIGER_COUNTER = registerItem("geiger_counter", GeigerCounterItem::new, new Item.Properties().stacksTo(1));
	public static final Item MISSILE_SILO_ITEM = registerItem(
		"missile_silo", props -> new BlockItem(MISSILE_SILO, props), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.EPIC)
	);
	public static final Item SUBMARINE_ITEM = registerItem(
		"submarine", props -> new BlockItem(SUBMARINE, props), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.EPIC)
	);
	public static final Item DESTROYER_ITEM = registerItem(
		"destroyer", de.rcm.ballistic.item.DestroyerItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)
	);
	public static final Item ROCKET_LAUNCHER = registerItem(
		"rocket_launcher", de.rcm.ballistic.item.RocketLauncherItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.RARE)
	);
	public static final Item RPG_ROCKET = registerItem("rpg_rocket", Item::new, new Item.Properties().stacksTo(16));
	public static final Item MOBILE_LAUNCHER_ITEM = registerItem(
		"mobile_launcher", MobileLauncherItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)
	);

	// ---------- Sounds ----------
	public static final SoundEvent SIREN = sound("missile.siren");
	public static final SoundEvent COUNTDOWN_BEEP = sound("missile.beep");
	public static final SoundEvent IGNITION = sound("missile.ignition");
	public static final SoundEvent IGNITION_SUB = sound("missile.ignition_sub");
	public static final SoundEvent ENGINE_CRACKLE = sound("missile.crackle");
	public static final SoundEvent ENGINE_LOOP = sound("missile.engine");
	public static final SoundEvent JET_LOOP = sound("missile.jet");
	public static final SoundEvent CLUSTER_POP = sound("missile.cluster_pop");
	public static final SoundEvent INCOMING = sound("missile.incoming");
	public static final SoundEvent EXPLOSION_NEAR = sound("explosion.near");
	public static final SoundEvent EXPLOSION_FAR = sound("explosion.far");
	public static final SoundEvent EXPLOSION_SUB = sound("explosion.sub");
	public static final SoundEvent EXPLOSION_DEBRIS = sound("explosion.debris");
	public static final SoundEvent EXPLOSION_THERMOBARIC = sound("explosion.thermobaric");
	public static final SoundEvent EXPLOSION_BUNKER = sound("explosion.bunker");
	public static final SoundEvent NUKE_NEAR = sound("nuke.near");
	public static final SoundEvent NUKE_FAR = sound("nuke.far");
	public static final SoundEvent NUKE_SUB = sound("nuke.sub");
	public static final SoundEvent NUKE_WIND = sound("nuke.wind");
	public static final SoundEvent TARGET_LOCK = sound("designator.lock");
	public static final SoundEvent EXPLOSION_MID = sound("explosion.mid");
	public static final SoundEvent NUKE_MID = sound("nuke.mid");
	public static final SoundEvent EAR_RINGING = sound("ear.ringing");
	public static final SoundEvent SONIC_BOOM = sound("missile.sonic_boom");
	public static final SoundEvent RADAR_ALARM = sound("radar.alarm");
	public static final SoundEvent SAM_LAUNCH = sound("air_defense.launch");
	public static final SoundEvent GEIGER_CLICK = sound("geiger.click");
	public static final SoundEvent CIWS_FIRE = sound("ciws.fire");
	public static final SoundEvent JET_FIGHTER = sound("jet.fighter");
	public static final SoundEvent JET_FIGHTER_FAR = sound("jet.fighter_far");
	public static final SoundEvent JET_AFTERBURNER = sound("jet.afterburner");
	public static final SoundEvent JET_BOOM = sound("jet.boom");
	public static final SoundEvent JET_SUB = sound("jet.sub");
	public static final SoundEvent A10_GUN = sound("a10.gun");
	public static final SoundEvent A10_ENGINE = sound("a10.engine");
	public static final SoundEvent A10_GUN_TAIL = sound("a10.gun_tail");
	public static final SoundEvent A10_FLYBY = sound("a10.flyby");
	public static final SoundEvent B2_ENGINE = sound("b2.engine");
	public static final SoundEvent SHOCK_HE = sound("explosion.shock_he");
	public static final SoundEvent SHOCK_HEAVY = sound("explosion.shock_heavy");
	public static final SoundEvent SHOCK_THERMO = sound("explosion.shock_thermo");
	public static final SoundEvent SHOCK_BUNKER = sound("explosion.shock_bunker");
	public static final SoundEvent SHOCK_NUKE = sound("explosion.shock_nuke");
	public static final SoundEvent SHOCK_EMP = sound("explosion.shock_emp");
	public static final SoundEvent SHOCK_ANTIMATTER = sound("explosion.shock_antimatter");
	public static final SoundEvent AC130_ENGINE = sound("ac130.engine");
	public static final SoundEvent REAPER_ENGINE = sound("reaper.engine");
	public static final SoundEvent APACHE_ROTOR = sound("apache.rotor");
	public static final SoundEvent GUN_105 = sound("ac130.gun_105");
	public static final SoundEvent GUN_40 = sound("ac130.gun_40");
	public static final SoundEvent CHAIN_GUN = sound("apache.chain_gun");
	public static final SoundEvent BOMB_WHISTLE = sound("bomb.whistle");
	public static final SoundEvent LASER_BEAM = sound("laser.beam");

	// ---------- Particles ----------
	public static final SimpleParticleType SMOKE = particle("smoke");
	public static final SimpleParticleType FIRE = particle("fire");

	// ---------- Creative tabs ----------
	/** Every missile and warhead. */
	public static final CreativeModeTab TAB = tab("main", () -> missileItem(MissileType.NUCLEAR), output -> {
		for (MissileType type : MissileType.values()) {
			output.accept(missileItem(type));
		}
	});
	/** Launchers, targeting and command: everything needed to fire. */
	public static final CreativeModeTab TAB_LAUNCH = tab("launch", () -> LAUNCH_PAD_ITEM, output -> {
		output.accept(LAUNCH_PAD_ITEM);
		output.accept(MISSILE_SILO_ITEM);
		output.accept(SUBMARINE_ITEM);
		output.accept(MOBILE_LAUNCHER_ITEM);
		output.accept(DESTROYER_ITEM);
		output.accept(ROCKET_LAUNCHER);
		output.accept(RPG_ROCKET);
		output.accept(TARGET_DESIGNATOR);
		output.accept(COMMAND_CENTER_ITEM);
		output.accept(AIRSTRIKE_RADIO);
	});
	/** Air defense: radar, interceptors, guns, lasers, decoys, jammers, mines. */
	public static final CreativeModeTab TAB_DEFENSE = tab("defense", () -> AIR_DEFENSE_ITEM, output -> {
		output.accept(RADAR_ITEM);
		output.accept(AIR_DEFENSE_ITEM);
		output.accept(CIWS_ITEM);
		output.accept(IRON_DOME_ITEM);
		output.accept(DECOY_LAUNCHER_ITEM);
		output.accept(LASER_DEFENSE_ITEM);
		output.accept(JAMMER_ITEM);
		output.accept(SEA_MINE_ITEM);
	});
	/** Shelter and radiation protection. */
	public static final CreativeModeTab TAB_PROTECTION = tab("protection", () -> HAZMAT_HELMET, output -> {
		output.accept(BUNKER_ITEM);
		output.accept(AIR_FILTER_ITEM);
		output.accept(EMERGENCY_GENERATOR_ITEM);
		output.accept(REINFORCED_CONCRETE_ITEM);
		output.accept(BLAST_DOOR_ITEM);
		output.accept(HAZMAT_HELMET);
		output.accept(HAZMAT_SUIT);
		output.accept(HAZMAT_LEGGINGS);
		output.accept(HAZMAT_BOOTS);
		output.accept(GEIGER_COUNTER);
	});
	/** Building blocks of the wasteland blasts leave behind. */
	public static final CreativeModeTab TAB_BLOCKS = tab("blocks", () -> SMOLDERING_EARTH_ITEM, output -> {
		output.accept(SCORCHED_EARTH_ITEM);
		output.accept(SMOLDERING_EARTH_ITEM);
		output.accept(CHARRED_LOG_ITEM);
		output.accept(ASH_ITEM);
		output.accept(CRATER_GLASS_ITEM);
		output.accept(MOLTEN_ROCK_ITEM);
		output.accept(TRINITITE_ITEM);
		output.accept(FALLOUT_ITEM);
	});

	private ModRegistry() {
	}

	public static void init() {
		// Static initializers do the work.
	}

	public static EntityType<MissileEntity> missileEntity(MissileType type) {
		return MISSILE_ENTITIES.get(type);
	}

	public static Item missileItem(MissileType type) {
		return MISSILE_ITEMS.get(type);
	}

	private static <T extends net.minecraft.world.entity.Entity> EntityType<T> registerEntity(String name, EntityType.Builder<T> builder) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, BallisticMissiles.id(name));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
	}

	private static Block registerBlock(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties props) {
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, BallisticMissiles.id(name));
		return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props.setId(key)));
	}

	private static Item registerItem(String name, Function<Item.Properties, Item> factory, Item.Properties props) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, BallisticMissiles.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(props.setId(key)));
	}

	private static SoundEvent sound(String name) {
		var id = BallisticMissiles.id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	private static CreativeModeTab tab(String name, java.util.function.Supplier<Item> icon, java.util.function.Consumer<CreativeModeTab.Output> items) {
		return Registry.register(
			BuiltInRegistries.CREATIVE_MODE_TAB,
			BallisticMissiles.id(name),
			FabricItemGroup.builder()
				.title(Component.translatable("itemGroup.ballisticmissiles." + name))
				.icon(() -> new ItemStack(icon.get()))
				.displayItems((params, output) -> items.accept(output))
				.build()
		);
	}

	private static SimpleParticleType particle(String name) {
		return Registry.register(BuiltInRegistries.PARTICLE_TYPE, BallisticMissiles.id(name), FabricParticleTypes.simple(true));
	}
}
