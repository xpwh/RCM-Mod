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
import net.minecraft.core.BlockPos;
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
 * <p>
 * Besides the round zone around ground zero, a nuclear burst leaves a fallout plume: the mushroom
 * cloud is carried off by the high-altitude wind and rains out radioactive dust in a long,
 * widening tongue downwind. Its front moves with the wind, so the fallout arrives some time after
 * the blast, and settled fallout and trinitite on the ground add to the dose nearby.
 */
public final class RadiationManager {
	/** Natural background radiation in microsievert per hour. */
	public static final double BACKGROUND = 0.12;
	private static final String FILE = "ballisticmissiles_radiation.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private record Zone(ResourceKey<Level> dimension, Vec3 center, double radius, double peak, long start, long duration) {
	}

	private static final List<Zone> ZONES = new ArrayList<>();

	/** Fallout plume carried downwind from {@code origin} along {@code (dirX, dirZ)}. */
	public record Plume(ResourceKey<Level> dimension, Vec3 origin, double dirX, double dirZ, double length, double width, double peak, long start, long duration) {
		/** How far downwind the fallout has come, blocks. */
		public double front(long now) {
			return Math.min(this.length, (now - this.start) * FRONT_SPEED);
		}

		/** Fresh fallout is by far the worst. */
		public double decay(long now) {
			double age = (double) (now - this.start) / this.duration;
			return age >= 1.0 ? 0.0 : Math.pow(1.0 - age, 2.0);
		}

		/** Strength of the plume at a position, 0-1, before decay. */
		public double strength(double x, double z, long now) {
			double rx = x - this.origin.x;
			double rz = z - this.origin.z;
			double along = rx * this.dirX + rz * this.dirZ;
			double across = Math.abs(-rx * this.dirZ + rz * this.dirX);
			double front = this.front(now);
			if (along < -this.width * 0.5 || along > front) {
				return 0.0;
			}
			double s = Math.max(0.0, along) / this.length;
			double halfWidth = this.width * (0.35 + 0.65 * s);
			double side = Math.exp(-2.0 * (across / halfWidth) * (across / halfWidth));
			double edge = Math.min(1.0, (front - along) / 40.0 + 0.15); // the arriving front is thin
			return Math.pow(1.0 - s, 1.3) * side * Math.min(1.0, edge);
		}
	}

	/** Speed of the fallout front: the high-altitude wind, blocks per tick (about 20 m/s). */
	public static final double FRONT_SPEED = 1.0;

	private static final List<Plume> PLUMES = new ArrayList<>();
	/** Players already warned about a plume (by plume start time). */
	private static final java.util.Map<java.util.UUID, Long> WARNED = new java.util.HashMap<>();

	private RadiationManager() {
	}

	public static void addZone(ServerLevel level, Vec3 center, double radius, double peakMicroSievert, long durationTicks) {
		ZONES.add(new Zone(level.dimension(), center, radius, peakMicroSievert, level.getGameTime(), durationTicks));
	}

	/** Adds a fallout plume blown downwind from ground zero, and tells the players in that dimension. */
	public static Plume addPlume(ServerLevel level, Vec3 origin, double direction, double length, double width, double peakMicroSievert, long durationTicks) {
		Plume plume = new Plume(level.dimension(), origin, Math.cos(direction), Math.sin(direction), length, width, peakMicroSievert, level.getGameTime(), durationTicks);
		PLUMES.add(plume);
		sync(level);
		return plume;
	}

	public static List<Plume> plumes(ServerLevel level) {
		long now = level.getGameTime();
		PLUMES.removeIf(p -> now - p.start() > p.duration());
		return PLUMES.stream().filter(p -> p.dimension() == level.dimension()).toList();
	}

	/** Sends the plumes of the level to everyone in it. */
	public static void sync(ServerLevel level) {
		for (net.minecraft.server.level.ServerPlayer player : level.players()) {
			sync(level, player);
		}
	}

	public static void sync(ServerLevel level, net.minecraft.server.level.ServerPlayer player) {
		long now = level.getGameTime();
		List<de.rcm.ballistic.network.ModNetworking.FalloutPlume> list = new ArrayList<>();
		for (Plume p : plumes(level)) {
			list.add(new de.rcm.ballistic.network.ModNetworking.FalloutPlume((float) p.origin().x, (float) p.origin().z, (float) p.dirX(), (float) p.dirZ(),
				(float) p.length(), (float) p.width(), (float) p.peak(), (int) (now - p.start()), (int) p.duration()));
		}
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new de.rcm.ballistic.network.ModNetworking.FalloutPayload(list));
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
		PLUMES.removeIf(p -> now - p.start() > p.duration());
		double fallout = 0.0;
		for (Plume p : PLUMES) {
			if (p.dimension() == level.dimension()) {
				fallout += p.peak() * p.strength(pos.x, pos.z, now) * p.decay(now);
			}
		}
		if (fallout > 0.0 && !level.canSeeSky(BlockPos.containing(pos.x, pos.y + 1.0, pos.z))) {
			fallout *= 0.15; // a roof overhead keeps the falling dust off
		}
		return rate + fallout + groundDose(level, pos);
	}

	/** Settled fallout and trinitite close by: each block adds a little, more the closer it is. */
	private static double groundDose(ServerLevel level, Vec3 pos) {
		BlockPos center = BlockPos.containing(pos);
		double rate = 0.0;
		for (BlockPos p : BlockPos.betweenClosed(center.offset(-3, -2, -3), center.offset(3, 2, 3))) {
			net.minecraft.world.level.block.state.BlockState state = level.getBlockState(p);
			double strength = state.is(ModRegistry.FALLOUT) ? 40.0 : state.is(ModRegistry.TRINITITE) ? 6.0 : 0.0;
			if (strength > 0.0) {
				rate += strength / (1.0 + p.distToCenterSqr(pos));
			}
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
		if (level.getGameTime() % 400 == 0 && !PLUMES.isEmpty()) {
			sync(level);
		}
		List<LivingEntity> exposed = new ArrayList<>();
		for (Zone z : ZONES) {
			if (z.dimension() == level.dimension()) {
				exposed.addAll(level.getEntitiesOfClass(LivingEntity.class, new AABB(z.center(), z.center()).inflate(z.radius(), 96.0, z.radius())));
			}
		}
		long now = level.getGameTime();
		for (Plume p : plumes(level)) {
			double front = p.front(now);
			Vec3 tip = p.origin().add(p.dirX() * front, 0, p.dirZ() * front);
			AABB box = new AABB(p.origin(), tip).inflate(p.width() * 1.2, 160.0, p.width() * 1.2);
			for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, box)) {
				exposed.add(living);
				if (living instanceof Player player && p.strength(living.getX(), living.getZ(), now) * p.decay(now) > 0.05
					&& !Long.valueOf(p.start()).equals(WARNED.get(player.getUUID()))) {
					WARNED.put(player.getUUID(), p.start());
					player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.ballisticmissiles.fallout_warning")
						.withStyle(net.minecraft.ChatFormatting.YELLOW, net.minecraft.ChatFormatting.BOLD), false);
				}
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
		PLUMES.clear();
		Path path = file(server);
		if (!Files.exists(path)) {
			return;
		}
		try {
			JsonObject root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
			JsonArray zones = root.getAsJsonArray("zones");
			for (var element : zones) {
				JsonObject o = element.getAsJsonObject();
				ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString()));
				ZONES.add(new Zone(dim, new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()),
					o.get("radius").getAsDouble(), o.get("peak").getAsDouble(), o.get("start").getAsLong(), o.get("duration").getAsLong()));
			}
			JsonArray plumes = root.getAsJsonArray("plumes");
			if (plumes != null) {
				for (var element : plumes) {
					JsonObject o = element.getAsJsonObject();
					ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(o.get("dimension").getAsString()));
					PLUMES.add(new Plume(dim, new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()),
						o.get("dirX").getAsDouble(), o.get("dirZ").getAsDouble(), o.get("length").getAsDouble(), o.get("width").getAsDouble(),
						o.get("peak").getAsDouble(), o.get("start").getAsLong(), o.get("duration").getAsLong()));
				}
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
		JsonArray plumes = new JsonArray();
		for (Plume p : PLUMES) {
			JsonObject o = new JsonObject();
			o.addProperty("dimension", p.dimension().identifier().toString());
			o.addProperty("x", p.origin().x);
			o.addProperty("y", p.origin().y);
			o.addProperty("z", p.origin().z);
			o.addProperty("dirX", p.dirX());
			o.addProperty("dirZ", p.dirZ());
			o.addProperty("length", p.length());
			o.addProperty("width", p.width());
			o.addProperty("peak", p.peak());
			o.addProperty("start", p.start());
			o.addProperty("duration", p.duration());
			plumes.add(o);
		}
		JsonObject root = new JsonObject();
		root.add("zones", zones);
		root.add("plumes", plumes);
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
		PLUMES.clear();
		WARNED.clear();
	}
}
