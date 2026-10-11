package de.rcm.ballistic.config;

import de.rcm.ballistic.BallisticMissiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;

/**
 * World-side settings of the mod (the same for everyone on a server), kept in
 * {@code config/ballisticmissiles-server.properties}. Operators change them from the settings screen
 * or with {@code /bmserver}.
 */
public final class ServerConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("ballisticmissiles-server.properties");
	/** The steps the settings screen offers for the misfire chance, percent. */
	public static final int[] MISFIRE_STEPS = {0, 2, 5, 12};

	/** Chance in percent that a launch goes wrong (0 = never). */
	public static int misfireChance = 2;
	/** Damage of the mod's guns (rounds, pellets), percent of normal. */
	public static int gunDamage = 100;
	/** Gore allowed on this server (off: nobody sees limbs torn off, gibs, open wounds - whatever they set). */
	public static boolean gore = true;
	/** Nuclear and antimatter weapons allowed (off: they fizzle, with a small conventional bang). */
	public static boolean nukes = true;

	private ServerConfig() {
	}

	public static void load() {
		try {
			if (Files.exists(FILE)) {
				Properties p = new Properties();
				try (var in = Files.newInputStream(FILE)) {
					p.load(in);
				}
				misfireChance = Mth.clamp(Integer.parseInt(p.getProperty("misfireChance", "2").trim()), 0, 100);
				gunDamage = Mth.clamp(Integer.parseInt(p.getProperty("gunDamage", "100").trim()), 0, 1000);
				gore = Boolean.parseBoolean(p.getProperty("gore", "true").trim());
				nukes = Boolean.parseBoolean(p.getProperty("nukes", "true").trim());
			}
		} catch (Exception e) {
			BallisticMissiles.LOGGER.warn("Could not read {}: {}", FILE, e.toString());
		}
	}

	public static void save() {
		try {
			Properties p = new Properties();
			p.setProperty("misfireChance", Integer.toString(misfireChance));
			p.setProperty("gunDamage", Integer.toString(gunDamage));
			p.setProperty("gore", Boolean.toString(gore));
			p.setProperty("nukes", Boolean.toString(nukes));
			Files.createDirectories(FILE.getParent());
			try (var out = Files.newOutputStream(FILE)) {
				p.store(out, "Ballistic Missiles - world settings (settings screen for operators, /bmserver)");
			}
		} catch (Exception e) {
			BallisticMissiles.LOGGER.warn("Could not write {}: {}", FILE, e.toString());
		}
	}
}
