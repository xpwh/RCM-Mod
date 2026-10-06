package de.rcm.ballistic.explosion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.ModRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Radioactive contamination left behind by nuclear detonations. Dose rate falls off with distance
 * from ground zero and decays over time; read by the Geiger counter. Anyone standing in it gets
 * radiation sickness that worsens with the dose rate - a radiation suit blocks most of it. The zones
 * are saved with the world, so craters stay hot across restarts.
 */
public final class RadiationManager {
	/** Natural background radiation in microsievert per hour. */
	public static final double BACKGROUND = 0.12;
	private static final String FILE = "ballisticmissiles_radiation.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private record Zone(ResourceKey<Level> dimension, Vec3 center, double radius, double peak, long start, long duration) {
	}

	private static final List<Zone> ZONES = new ArrayList<>();

	private RadiationManager() {
	}

	public static void addZone(ServerLevel level, Vec3 center, double radius, double peakMicroSievert, long durationTicks) {
		ZONES.add(new Zone(level.dimension(), center, radius, peakMicroSievert, level.getGameTime(), durationTicks));
	}

	/** Dose rate in microsievert per hour at a position. */
	public static double doseRate(ServerLevel level, Vec3 pos) {
		long now = level.getGameTime();
		ZONES.removeIf(z -> now - z.start() > z.duration());
		double rate = BACKGROUND;
		for (Zone z : ZONES) {
			if (z.dimension() != level.dimension()) {
				continue;
			}
			double d = pos.distanceTo(z.center());
			if (d >= z.radius()) {
				continue;
			}
			double age = (double) (now - z.start()) / z.duration();
			double decay = Math.pow(1.0 - age, 2.0); // fresh fallout is by far the worst
			double falloff = Math.pow(1.0 - d / z.radius(), 2.2);
			rate += z.peak() * falloff * decay;
		}
		return rate;
	}

	/** Fraction of the dose a radiation suit keeps out: 24% per piece, 96% for the full suit. */
	public static float protection(LivingEntity entity) {
		int pieces = 0;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			Item item = entity.getItemBySlot(slot).getItem();
			if (item == ModRegistry.HAZMAT_HELMET || item == ModRegistry.HAZMAT_SUIT || item == ModRegistry.HAZMAT_LEGGINGS || item == ModRegistry.HAZMAT_BOOTS) {
				pieces++;
			}
		}
		return pieces * 0.24F;
	}

	/** Radiation sickness for everything standing in contaminated ground, checked every two seconds. */
	public static void tick(ServerLevel level) {
		if (level.getGameTime() % 40 != 0 || ZONES.isEmpty()) {
			return;
		}
		List<LivingEntity> exposed = new ArrayList<>();
		for (Zone z : ZONES) {
			if (z.dimension() == level.dimension()) {
				exposed.addAll(level.getEntitiesOfClass(LivingEntity.class, new AABB(z.center(), z.center()).inflate(z.radius(), 96.0, z.radius())));
			}
		}
		for (LivingEntity living : exposed.stream().distinct().toList()) {
			if (living instanceof Player player && (player.isCreative() || player.isSpectator())) {
				continue;
			}
			double rate = doseRate(level, living.position()) * (1.0F - protection(living));
			if (rate >= 50.0) {
				living.addEffect(new MobEffectInstance(MobEffects.HUNGER, 80, rate >= 2000.0 ? 1 : 0));
			}
			if (rate >= 400.0) {
				living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 80, 0));
				if (level.getRandom().nextFloat() < 0.25F) {
					living.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 140, 0));
				}
			}
			if (rate >= 2000.0) {
				living.addEffect(new MobEffectInstance(MobEffects.WITHER, 80, rate >= 8000.0 ? 1 : 0));
			}
		}
	}

	// ------------------------------------------------------------------ persistence

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
	}

	public static void load(MinecraftServer server) {
		ZONES.clear();
		Path path = file(server);
		if (!Files.exists(path)) {
			return;
		}
		try {
			JsonArray zones = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class).getAsJsonArray("zones");
			for (var element : zones) {
				JsonObject o = element.getAsJsonObject();
				ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString()));
				ZONES.add(new Zone(dim, new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()),
					o.get("radius").getAsDouble(), o.get("peak").getAsDouble(), o.get("start").getAsLong(), o.get("duration").getAsLong()));
			}
		} catch (IOException | RuntimeException e) {
			BallisticMissiles.LOGGER.warn("Could not read radiation zones from {}", path, e);
		}
	}

	public static void save(MinecraftServer server) {
		JsonArray zones = new JsonArray();
		for (Zone z : ZONES) {
			JsonObject o = new JsonObject();
			o.addProperty("dimension", z.dimension().identifier().toString());
			o.addProperty("x", z.center().x);
			o.addProperty("y", z.center().y);
			o.addProperty("z", z.center().z);
			o.addProperty("radius", z.radius());
			o.addProperty("peak", z.peak());
			o.addProperty("start", z.start());
			o.addProperty("duration", z.duration());
			zones.add(o);
		}
		JsonObject root = new JsonObject();
		root.add("zones", zones);
		Path path = file(server);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BallisticMissiles.LOGGER.warn("Could not save radiation zones to {}", path, e);
		}
	}

	public static void clear() {
		ZONES.clear();
	}
}
