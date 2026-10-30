package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.platform.NativeImage;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The cloud layer's density on the CPU: a port of the coarse shape of {@code clouds_common.glsl} (the same
 * noise atlas, coverage, wind, both styles and the holes rockets punched, without the finest detail), so the
 * game can tell how much cloud lies between two points - what smoke beyond the layer is hidden by.
 */
public final class CloudDensity {
	private static final int ATLAS = 528;
	/** The noise atlas, RGBA as floats 0-1 (null: not loaded, or failed). */
	private static float[] atlas;
	private static boolean tried;
	/** The same light extinction per block of density as the cloud shader. */
	private static final float SIGMA = 0.11F;

	private CloudDensity() {
	}

	private static boolean load() {
		if (tried) {
			return atlas != null;
		}
		tried = true;
		try (var in = Minecraft.getInstance().getResourceManager().open(BallisticMissiles.id("textures/environment/cloud_noise.png"));
			NativeImage img = NativeImage.read(in)) {
			if (img.getWidth() != ATLAS || img.getHeight() != ATLAS) {
				return false;
			}
			float[] a = new float[ATLAS * ATLAS * 4];
			for (int y = 0; y < ATLAS; y++) {
				for (int x = 0; x < ATLAS; x++) {
					int c = img.getPixel(x, y); // ARGB
					int i = (y * ATLAS + x) * 4;
					a[i] = (c >> 16 & 255) / 255.0F;
					a[i + 1] = (c >> 8 & 255) / 255.0F;
					a[i + 2] = (c & 255) / 255.0F;
					a[i + 3] = (c >>> 24) / 255.0F;
				}
			}
			atlas = a;
			return true;
		} catch (Exception e) {
			BallisticMissiles.LOGGER.warn("Cloud noise for smoke occlusion not loaded: {}", e.toString());
			return false;
		}
	}

	/** One channel, bilinear in a slice (u, v in atlas texels). */
	private static float texel(float u, float v, int ch) {
		float x = u - 0.5F;
		float y = v - 0.5F;
		int x0 = Mth.floor(x);
		int y0 = Mth.floor(y);
		float fx = x - x0;
		float fy = y - y0;
		int x1 = Mth.clamp(x0 + 1, 0, ATLAS - 1);
		int y1 = Mth.clamp(y0 + 1, 0, ATLAS - 1);
		x0 = Mth.clamp(x0, 0, ATLAS - 1);
		y0 = Mth.clamp(y0, 0, ATLAS - 1);
		float a = atlas[(y0 * ATLAS + x0) * 4 + ch];
		float b = atlas[(y0 * ATLAS + x1) * 4 + ch];
		float c = atlas[(y1 * ATLAS + x0) * 4 + ch];
		float d = atlas[(y1 * ATLAS + x1) * 4 + ch];
		return Mth.lerp(fy, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
	}

	/** The shader's {@code cloudNoise}: one channel, p in noise periods. */
	private static float noise(double px, double py, double pz, int ch) {
		float x = (float) (px - Math.floor(px)) * 64.0F;
		float y = (float) (py - Math.floor(py)) * 64.0F;
		float z = (float) (pz - Math.floor(pz)) * 64.0F - 0.5F;
		float z0 = (float) Math.floor(z);
		float f = z - z0;
		int s0 = Math.floorMod((int) z0, 64);
		int s1 = (s0 + 1) % 64;
		float a = texel((s0 % 8) * 66 + 1 + x, (s0 / 8) * 66 + 1 + y, ch);
		float b = texel((s1 % 8) * 66 + 1 + x, (s1 / 8) * 66 + 1 + y, ch);
		return Mth.lerp(f, a, b);
	}

	private static float remap(float v, float a, float b, float c, float d) {
		return c + (v - a) / (b - a) * (d - c);
	}

	private static float smooth(float a, float b, float x) {
		float t = Mth.clamp((x - a) / (b - a), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	/** Density at a point; x and z already wrapped as the shader has them. */
	private static float density(double x, double y, double z, float bottom, float thick) {
		float h = (float) ((y - bottom) / thick);
		if (h <= 0.0F || h >= 1.0F) {
			return 0.0F;
		}
		float coverage = VolumetricClouds.coverageNow;
		float storm = VolumetricClouds.stormNow;
		double qx = x + VolumetricClouds.windXNow;
		double qy = z + VolumetricClouds.windZNow;
		float d;
		if (ModConfig.cumulusClouds) {
			double qz = y;
			float bigR = noise(qx / 1600.0, qy / 1600.0, qz / 6000.0 + 0.3, 0);
			float bigB = noise(qx / 1600.0, qy / 1600.0, qz / 6000.0 + 0.3, 2);
			float nr = noise(qx / 700.0, qy / 700.0, qz / 700.0, 0);
			float cov = Mth.clamp(coverage * 0.95F + (bigB - 0.5F) * 0.55F, 0.0F, 1.0F);
			float s = Mth.clamp(remap(bigR * 0.62F + nr * 0.38F, 1.0F - cov, 1.0F, 0.0F, 1.0F), 0.0F, 1.0F);
			if (s <= 0.0F) {
				return 0.0F;
			}
			float tall = Math.min(1.0F, 0.12F + 1.15F * (float) Math.pow(s, 1.3) + storm * 0.45F);
			float hf = h / tall;
			if (hf >= 1.0F) {
				return 0.0F;
			}
			d = (s * 1.35F - 0.62F * hf * hf - 0.08F) * smooth(0.0F, 0.02F, h);
			// the billows' average bite, without sampling them
			d -= 0.5F * (0.18F + 0.24F * hf);
			d = Mth.clamp(d * 2.2F, 0.0F, 1.0F);
		} else {
			double qz = y * 2.5;
			float nr = noise(qx / 1024.0, qy / 1024.0, qz / 1024.0, 0);
			float nb = noise(qx / 1024.0, qy / 1024.0, qz / 1024.0, 2);
			float cov = Mth.clamp(coverage + (nb - 0.5F) * 0.5F, 0.0F, 1.0F);
			d = Mth.clamp(remap(nr, 1.0F - cov, 1.0F, 0.0F, 1.0F), 0.0F, 1.0F);
			float tall = Math.min(1.0F, 0.2F + 0.8F * d + storm * 0.35F);
			d *= smooth(0.0F, 0.07F, h) * (1.0F - smooth(tall - 0.3F, tall, h));
		}
		return d * (1.0F - VolumetricClouds.hole(x, z));
	}

	/**
	 * How cloudy the sky is over (x, z), 0-1, as a satellite would see it: the thickest the cloud is at three
	 * heights through the layer (null layer or noise not loaded: -1).
	 */
	public static float cover(double x, double z) {
		float bottom = VolumetricClouds.base();
		if (Float.isNaN(bottom) || !load()) {
			return -1.0F;
		}
		float thick = VolumetricClouds.thickness();
		double wx = x - Math.floorDiv(Mth.floor(x), 4096) * 4096.0;
		double wz = z - Math.floorDiv(Mth.floor(z), 4096) * 4096.0;
		float d = 0.0F;
		for (int i = 1; i <= 3; i++) {
			d = Math.max(d, density(wx, bottom + thick * (i * 0.22F), wz, bottom, thick));
		}
		return d;
	}

	/**
	 * How much light gets through the cloud on the straight line from {@code from} to {@code to} (1: no cloud
	 * in the way, 0: none gets through), in {@code samples} steps over the part of the line inside the layer.
	 */
	public static float transmittance(Vec3 from, Vec3 to, int samples) {
		float bottom = VolumetricClouds.base();
		if (Float.isNaN(bottom) || !load()) {
			return 1.0F;
		}
		float thick = VolumetricClouds.thickness();
		float top = bottom + thick;
		double y0 = from.y;
		double y1 = to.y;
		if (Math.max(y0, y1) <= bottom || Math.min(y0, y1) >= top) {
			return 1.0F;
		}
		// the part of the line within the layer
		double t0 = 0.0;
		double t1 = 1.0;
		double dy = y1 - y0;
		if (Math.abs(dy) > 1.0E-6) {
			double ta = (bottom - y0) / dy;
			double tb = (top - y0) / dy;
			t0 = Math.max(0.0, Math.min(ta, tb));
			t1 = Math.min(1.0, Math.max(ta, tb));
		}
		if (t1 <= t0) {
			return 1.0F;
		}
		// wrap x and z the way the shader's camera does (within 4096 blocks)
		double wx = Math.floorDiv(Mth.floor(from.x), 4096) * 4096.0;
		double wz = Math.floorDiv(Mth.floor(from.z), 4096) * 4096.0;
		double length = from.distanceTo(to) * (t1 - t0);
		double seg = (t1 - t0) / samples;
		float od = 0.0F;
		for (int i = 0; i < samples; i++) {
			double t = t0 + seg * (i + 0.5);
			od += density(Mth.lerp(t, from.x, to.x) - wx, Mth.lerp(t, from.y, to.y), Mth.lerp(t, from.z, to.z) - wz, bottom, thick);
		}
		od *= (float) (length / samples);
		return (float) Math.exp(-od * SIGMA);
	}
}
