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
	/** The cloud style: false the layered clouds, true realistic 3D cumulus heaps. */
	public static boolean cumulusClouds = false;
	public static boolean lightShafts = true;
	public static boolean realSky = true;
	/** The Milky Way, finer stars and shooting stars at night. */
	public static boolean nightSky = true;
	/** The eye adapting to the dark slowly, dazzled by flashes at night. */
	public static boolean eyeAdaptation = true;
	/** Water mirrors the sky and what is round it, with waves (screen-space reflections). */
	public static boolean realWater = true;
	/** Rain as thin falling streaks, slanted by the wind, with splashes, rings and spray (instead of Minecraft's blue sheets). */
	public static boolean realRain = true;
	/** Thunderstorms: lightning in the clouds and to the ground, delayed thunder, heavy rain, wet ground and puddles. */
	public static boolean storms = true;
	/** Sunlight casts shadows (traced through the depth of the picture). */
	public static boolean sunShadows = true;
	/** Soft darkening in corners and crevices (screen-space ambient occlusion). */
	public static boolean ambientOcclusion = true;
	/** The sun's lens flare when you look near it. */
	public static boolean lensFlare = true;
	/** Bright lights glow over what is round them, 0-100. */
	public static int bloom = 40;
	/** Stepping out of the dark into daylight blinds you for a moment. */
	public static boolean autoExposure = true;
	public static boolean blood = true;
	/** Limbs shot off shown as they are (the stump, the shortened leg); off: they stay whole to look at. */
	public static boolean gore = true;
	/** The 18+ warning on the title screen at every start. */
	public static boolean goreWarning = true;
	/** The note after it that updates come now and then, when the developer wants. */
	public static boolean updateNotice = true;
	/** The hotbar slides away when it has not been used for a while. */
	public static boolean hotbarAutoHide = true;
	public static int hotbarHideSeconds = 5;
	/** The mouse turns the view through a very short smoothing filter. */
	public static boolean smoothLook = true;
	/** Steps up are eased and landings sink and spring back. */
	public static boolean smoothMove = true;
	/** Your own body (legs, chest) seen when you look down in first person. */
	public static boolean firstPersonBody = true;
	/** The mod version last started (for the patch notes after an update) and whether to show them. */
	public static String lastSeenVersion = "";
	public static boolean patchNotes = true;
	// ---- effects
	/** The screen effects of blasts (flash, haze, god rays, shell shock...), percent. */
	public static int screenEffects = 100;
	/** How much smoke the smoke system makes, percent. */
	public static int smokeAmount = 100;
	/**
	 * How smoke goes away. true: fast and realistic - it spreads out, thins and is gone in a fraction of the
	 * time, like real smoke in moving air; false: the long-hanging smoke as before.
	 */
	public static boolean fastSmoke = false;
	/** How smoke is drawn: raymarched as a volume near the camera, flat sprites, or blocky voxels. */
	public enum SmokeStyle {
		VOLUME,
		SPRITES,
		VOXEL,
		/** "Voxel 2": flat pixel-art puffs. */
		PIXEL;

		public String key() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		public static SmokeStyle parse(String s, SmokeStyle fallback) {
			if (s == null) {
				return fallback;
			}
			return switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
				case "volume", "volumetric", "true" -> VOLUME;
				case "sprites", "classic", "false" -> SPRITES;
				case "voxel", "voxels" -> VOXEL;
				case "pixel", "voxel2", "voxel 2" -> PIXEL;
				default -> fallback;
			};
		}
	}

	public static SmokeStyle smokeStyle = SmokeStyle.VOLUME;
	/** Loudness of explosions and other big bangs, percent. */
	public static int explosionVolume = 100;
	public static boolean bulletHoles = true;
	/** Footprints and tyre tracks in snow, sand and mud. */
	public static boolean tracks = true;
	/** Muzzle flashes, rocket motors and explosions light up the world round them. */
	public static boolean dynamicLights = true;
	/** Dynamic light quality: 0 off, 1 low (few lights, hard shadows), 2 high, 3 ultra (many, soft shadows). */
	public static int lightQuality = 2;
	/** The connected server's misfire setting, as it last told us (shown in the settings, not saved here). */
	// ---- world and nature (all on by default)
	/** How hard the wind blows the grass and how far blast waves press it, percent (0: still). */
	public static int windAmount = 100;
	/** How much the living world costs: low, medium, high (how many animals, ash flakes, how fine the End sky). */
	public static Quality natureQuality = Quality.HIGH;
	public static boolean wildlife = true;
	public static boolean nightSounds = true;
	public static boolean stormFront = true;
	public static boolean rainbow = true;
	public static boolean endSky = true;
	public static boolean endEffects = true;
	public static boolean heatShimmer = true;
	public static boolean breath = true;
	public static boolean temperatureHud = true;
	/** The server's living systems (ServerConfig.nature), mirrored for the screen. */
	public static int serverNature = 0xFF;

	public static int serverMisfireChance;
	/** The connected server's other world settings, as it last told us. */
	public static int serverGunDamage = 100;
	public static boolean serverGore = true;
	public static boolean serverNukes = true;
	public static boolean serverKillcam = true;

	/** Whether gore is shown: switched on here, and allowed by the server. */
	public static boolean gore() {
		return gore && serverGore;
	}

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
			cumulusClouds = "cumulus".equals(p.getProperty("cloudStyle", cumulusClouds ? "cumulus" : "layer"));
			lightShafts = bool(p, "lightShafts", lightShafts);
			realSky = bool(p, "realSky", realSky);
			// older configs had only volumetric on/off
			smokeStyle = SmokeStyle.parse(p.getProperty("smokeStyle", p.getProperty("volumetricSmoke")), smokeStyle);
			nightSky = bool(p, "nightSky", nightSky);
			eyeAdaptation = bool(p, "eyeAdaptation", eyeAdaptation);
			realWater = bool(p, "realWater", realWater);
			realRain = bool(p, "realRain", realRain);
			storms = bool(p, "storms", storms);
			sunShadows = bool(p, "sunShadows", sunShadows);
			ambientOcclusion = bool(p, "ambientOcclusion", ambientOcclusion);
			lensFlare = bool(p, "lensFlare", lensFlare);
			bloom = percent(p, "bloom", bloom);
			autoExposure = bool(p, "autoExposure", autoExposure);
			blood = bool(p, "blood", blood);
			gore = bool(p, "gore", gore);
			goreWarning = bool(p, "goreWarning", goreWarning);
			updateNotice = bool(p, "updateNotice", updateNotice);
			hotbarAutoHide = bool(p, "hotbarAutoHide", hotbarAutoHide);
			hotbarHideSeconds = net.minecraft.util.Mth.clamp(percent(p, "hotbarHideSeconds", hotbarHideSeconds), 1, 60);
			smoothLook = bool(p, "smoothLook", smoothLook);
			smoothMove = bool(p, "smoothMove", smoothMove);
			firstPersonBody = bool(p, "firstPersonBody", firstPersonBody);
			lastSeenVersion = p.getProperty("lastSeenVersion", lastSeenVersion).trim();
			patchNotes = bool(p, "patchNotes", patchNotes);
			screenEffects = percent(p, "screenEffects", screenEffects);
			smokeAmount = percent(p, "smokeAmount", smokeAmount);
			fastSmoke = "fast".equals(p.getProperty("smokeFade", fastSmoke ? "fast" : "long"));
			explosionVolume = percent(p, "explosionVolume", explosionVolume);
			bulletHoles = bool(p, "bulletHoles", bulletHoles);
			tracks = bool(p, "tracks", tracks);
			dynamicLights = bool(p, "dynamicLights", dynamicLights);
			try {
				lightQuality = net.minecraft.util.Mth.clamp(Integer.parseInt(p.getProperty("lightQuality", dynamicLights ? "2" : "0").trim()), 0, 3);
			} catch (NumberFormatException e) {
				lightQuality = 2;
			}
			dynamicLights = lightQuality > 0;
			windAmount = Mth.clamp(integer(p, "windAmount", windAmount), 0, 200);
			natureQuality = Quality.parse(p.getProperty("natureQuality"), natureQuality);
			if (natureQuality == Quality.ULTRA) {
				natureQuality = Quality.HIGH;
			}
			wildlife = bool(p, "wildlife", wildlife);
			nightSounds = bool(p, "nightSounds", nightSounds);
			stormFront = bool(p, "stormFront", stormFront);
			rainbow = bool(p, "rainbow", rainbow);
			endSky = bool(p, "endSky", endSky);
			endEffects = bool(p, "endEffects", endEffects);
			heatShimmer = bool(p, "heatShimmer", heatShimmer);
			breath = bool(p, "breath", breath);
			temperatureHud = bool(p, "temperatureHud", temperatureHud);
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
			p.setProperty("cloudStyle", cumulusClouds ? "cumulus" : "layer");
			p.setProperty("lightShafts", Boolean.toString(lightShafts));
			p.setProperty("realSky", Boolean.toString(realSky));
			p.setProperty("smokeStyle", smokeStyle.key());
			p.setProperty("nightSky", Boolean.toString(nightSky));
			p.setProperty("eyeAdaptation", Boolean.toString(eyeAdaptation));
			p.setProperty("realWater", Boolean.toString(realWater));
			p.setProperty("realRain", Boolean.toString(realRain));
			p.setProperty("storms", Boolean.toString(storms));
			p.setProperty("sunShadows", Boolean.toString(sunShadows));
			p.setProperty("ambientOcclusion", Boolean.toString(ambientOcclusion));
			p.setProperty("lensFlare", Boolean.toString(lensFlare));
			p.setProperty("bloom", Integer.toString(bloom));
			p.setProperty("autoExposure", Boolean.toString(autoExposure));
			p.setProperty("blood", Boolean.toString(blood));
			p.setProperty("gore", Boolean.toString(gore));
			p.setProperty("goreWarning", Boolean.toString(goreWarning));
			p.setProperty("updateNotice", Boolean.toString(updateNotice));
			p.setProperty("hotbarAutoHide", Boolean.toString(hotbarAutoHide));
			p.setProperty("hotbarHideSeconds", Integer.toString(hotbarHideSeconds));
			p.setProperty("smoothLook", Boolean.toString(smoothLook));
			p.setProperty("smoothMove", Boolean.toString(smoothMove));
			p.setProperty("firstPersonBody", Boolean.toString(firstPersonBody));
			p.setProperty("lastSeenVersion", lastSeenVersion);
			p.setProperty("patchNotes", Boolean.toString(patchNotes));
			p.setProperty("screenEffects", Integer.toString(screenEffects));
			p.setProperty("smokeAmount", Integer.toString(smokeAmount));
			p.setProperty("smokeFade", fastSmoke ? "fast" : "long");
			p.setProperty("explosionVolume", Integer.toString(explosionVolume));
			p.setProperty("bulletHoles", Boolean.toString(bulletHoles));
			p.setProperty("tracks", Boolean.toString(tracks));
			p.setProperty("dynamicLights", Boolean.toString(dynamicLights));
			p.setProperty("lightQuality", Integer.toString(lightQuality));
			p.setProperty("windAmount", Integer.toString(windAmount));
			p.setProperty("natureQuality", natureQuality.name().toLowerCase(java.util.Locale.ROOT));
			p.setProperty("wildlife", Boolean.toString(wildlife));
			p.setProperty("nightSounds", Boolean.toString(nightSounds));
			p.setProperty("stormFront", Boolean.toString(stormFront));
			p.setProperty("rainbow", Boolean.toString(rainbow));
			p.setProperty("endSky", Boolean.toString(endSky));
			p.setProperty("endEffects", Boolean.toString(endEffects));
			p.setProperty("heatShimmer", Boolean.toString(heatShimmer));
			p.setProperty("breath", Boolean.toString(breath));
			p.setProperty("temperatureHud", Boolean.toString(temperatureHud));
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
