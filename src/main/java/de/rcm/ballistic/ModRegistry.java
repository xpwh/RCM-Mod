package de.rcm.ballistic;

import de.rcm.ballistic.block.AirDefenseBlock;
import de.rcm.ballistic.block.AirDefenseBlockEntity;
import de.rcm.ballistic.block.LaunchPadBlock;
import de.rcm.ballistic.block.MissileSiloBlock;
import de.rcm.ballistic.block.MissileSiloBlockEntity;
import de.rcm.ballistic.block.RadarBlock;
import de.rcm.ballistic.block.RadarBlockEntity;
import de.rcm.ballistic.entity.BombletEntity;
import de.rcm.ballistic.entity.InterceptorEntity;
import de.rcm.ballistic.entity.ReentryVehicleEntity;
import de.rcm.ballistic.entity.MissileEntity;
import de.rcm.ballistic.entity.MissileType;
import de.rcm.ballistic.entity.MobileLauncherEntity;
import de.rcm.ballistic.item.GeigerCounterItem;
import de.rcm.ballistic.item.LauncherLink;
import de.rcm.ballistic.item.MissileItem;
import de.rcm.ballistic.item.MobileLauncherItem;
import de.rcm.ballistic.item.SavedTarget;
import de.rcm.ballistic.item.TargetData;
import de.rcm.ballistic.item.TargetDesignatorItem;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

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

	public static final Block MISSILE_SILO = registerBlock(
		"missile_silo",
		MissileSiloBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(8.0F, 3600.0F).sound(SoundType.NETHERITE_BLOCK).requiresCorrectToolForDrops()
	);

	public static final BlockEntityType<RadarBlockEntity> RADAR_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("radar"), FabricBlockEntityTypeBuilder.create(RadarBlockEntity::new, RADAR).build()
	);
	public static final BlockEntityType<AirDefenseBlockEntity> AIR_DEFENSE_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("air_defense"), FabricBlockEntityTypeBuilder.create(AirDefenseBlockEntity::new, AIR_DEFENSE).build()
	);
	public static final BlockEntityType<MissileSiloBlockEntity> MISSILE_SILO_BE = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, BallisticMissiles.id("missile_silo"), FabricBlockEntityTypeBuilder.create(MissileSiloBlockEntity::new, MISSILE_SILO).build()
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
	public static final Item RADAR_ITEM = registerItem("radar", props -> new BlockItem(RADAR, props), new Item.Properties().useBlockDescriptionPrefix());
	public static final Item AIR_DEFENSE_ITEM = registerItem(
		"air_defense", props -> new BlockItem(AIR_DEFENSE, props), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.RARE)
	);
	public static final Item GEIGER_COUNTER = registerItem("geiger_counter", GeigerCounterItem::new, new Item.Properties().stacksTo(1));
	public static final Item MISSILE_SILO_ITEM = registerItem(
		"missile_silo", props -> new BlockItem(MISSILE_SILO, props), new Item.Properties().useBlockDescriptionPrefix().rarity(Rarity.EPIC)
	);
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

	// ---------- Particles ----------
	public static final SimpleParticleType SMOKE = particle("smoke");
	public static final SimpleParticleType FIRE = particle("fire");

	// ---------- Creative tab ----------
	public static final CreativeModeTab TAB = Registry.register(
		BuiltInRegistries.CREATIVE_MODE_TAB,
		BallisticMissiles.id("main"),
		FabricItemGroup.builder()
			.title(Component.translatable("itemGroup.ballisticmissiles.main"))
			.icon(() -> new ItemStack(missileItem(MissileType.NUCLEAR)))
			.displayItems((params, output) -> {
				output.accept(LAUNCH_PAD_ITEM);
				output.accept(MISSILE_SILO_ITEM);
				output.accept(MOBILE_LAUNCHER_ITEM);
				output.accept(TARGET_DESIGNATOR);
				output.accept(RADAR_ITEM);
				output.accept(AIR_DEFENSE_ITEM);
				output.accept(GEIGER_COUNTER);
				for (MissileType type : MissileType.values()) {
					output.accept(missileItem(type));
				}
			})
			.build()
	);

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

	private static SimpleParticleType particle(String name) {
		return Registry.register(BuiltInRegistries.PARTICLE_TYPE, BallisticMissiles.id(name), FabricParticleTypes.simple(true));
	}
}
