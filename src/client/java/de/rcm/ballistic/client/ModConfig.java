package de.rcm.ballistic.client;

import de.rcm.ballistic.BallisticMissiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * The client-side settings of the mod (the settings screen and {@code /volcloud} change them), kept in
 * {@code config/ballisticmissiles-client.properties}.
 */
public final class ModConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("ballisticmissiles-client.properties");
	/** Where the cloud settings lived before there was a settings screen. */
	private static final Path OLD_CLOUDS = FabricLoader.getInstance().getConfigDir().resolve("ballisticmissiles-clouds.properties");

	/** Cloud quality levels: how many steps the shader takes, how much detail, how far the light shafts reach. */
	public enum Quality {
		LOW,
		MEDIUM,
		HIGH,
		ULTRA;

		public Quality next() {
			return values()[(this.ordinal() + 1) % values().length];
		}

		public static Quality parse(String s, Quality fallback) {
			if (s == null) {
				return fallback;
			}
			return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
				case "low", "niedrig", "0" -> LOW;
				case "medium", "mittel", "1" -> MEDIUM;
				case "high", "hoch", "2" -> HIGH;
				case "ultra", "3" -> ULTRA;
				default -> fallback;
			};
		}
	}

	// ---- clouds
	public static boolean clouds = true;
	/** Fair-weather cloud cover, percent (rain and storms add to it). */
	public static int cloudAmount = 40;
	public static Quality cloudQuality = Quality.HIGH;
	public static boolean cloudShadows = true;
	public static boolean lightShafts = true;
	// ---- effects
	/** The screen effects of blasts (flash, haze, god rays, shell shock...), percent. */
	public static int screenEffects = 100;
	/** How much smoke the smoke system makes, percent. */
	public static int smokeAmount = 100;
	/** Loudness of explosions and other big bangs, percent. */
	public static int explosionVolume = 100;
	public static boolean bulletHoles = true;
	/** Muzzle flashes, rocket motors and explosions light up the world round them. */
	public static boolean dynamicLights = true;

	private ModConfig() {
	}

	public static void load() {
		try {
			Properties p = new Properties();
			if (Files.exists(FILE)) {
				try (var in = Files.newInputStream(FILE)) {
					p.load(in);
				}
			} else if (Files.exists(OLD_CLOUDS)) {
				try (var in = Files.newInputStream(OLD_CLOUDS)) {
					Properties old = new Properties();
					old.load(in);
					p.setProperty("clouds", old.getProperty("enabled", "true"));
					p.setProperty("cloudAmount", old.getProperty("amount", "40"));
				}
			}
			clouds = bool(p, "clouds", clouds);
			cloudAmount = percent(p, "cloudAmount", cloudAmount);
			cloudQuality = Quality.parse(p.getProperty("cloudQuality"), cloudQuality);
			cloudShadows = bool(p, "cloudShadows", cloudShadows);
			lightShafts = bool(p, "lightShafts", lightShafts);
			screenEffects = percent(p, "screenEffects", screenEffects);
			smokeAmount = percent(p, "smokeAmount", smokeAmount);
			explosionVolume = percent(p, "explosionVolume", explosionVolume);
			bulletHoles = bool(p, "bulletHoles", bulletHoles);
			dynamicLights = bool(p, "dynamicLights", dynamicLights);
		} catch (Exception e) {
			BallisticMissiles.LOGGER.warn("Could not read {}: {}", FILE, e.toString());
		}
	}

	public static void save() {
		try {
			Properties p = new Properties();
			p.setProperty("clouds", Boolean.toString(clouds));
			p.setProperty("cloudAmount", Integer.toString(cloudAmount));
			p.setProperty("cloudQuality", cloudQuality.name().toLowerCase(java.util.Locale.ROOT));
			p.setProperty("cloudShadows", Boolean.toString(cloudShadows));
			p.setProperty("lightShafts", Boolean.toString(lightShafts));
			p.setProperty("screenEffects", Integer.toString(screenEffects));
			p.setProperty("smokeAmount", Integer.toString(smokeAmount));
			p.setProperty("explosionVolume", Integer.toString(explosionVolume));
			p.setProperty("bulletHoles", Boolean.toString(bulletHoles));
			p.setProperty("dynamicLights", Boolean.toString(dynamicLights));
			Files.createDirectories(FILE.getParent());
			try (var out = Files.newOutputStream(FILE)) {
				p.store(out, "Ballistic Missiles - client settings (settings screen, /bmconfig, /volcloud)");
			}
		} catch (Exception e) {
			BallisticMissiles.LOGGER.warn("Could not write {}: {}", FILE, e.toString());
		}
	}

	private static boolean bool(Properties p, String key, boolean fallback) {
		String v = p.getProperty(key);
		return v == null ? fallback : Boolean.parseBoolean(v.trim());
	}

	private static int integer(Properties p, String key, int fallback) {
		try {
			String v = p.getProperty(key);
			return v == null ? fallback : Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int percent(Properties p, String key, int fallback) {
		return Mth.clamp(integer(p, key, fallback), 0, 100);
	}
}
