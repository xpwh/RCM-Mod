package de.rcm.ballistic.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * The light on wet blood: fresh blood, raw meat, gut and organs are wet and mirror the light - the sun as a
 * hard, bright point where its reflection meets your eye, the sky as a sheen that grows towards grazing
 * angles, a lamp or a torch nearby as a softer glint. Worked out per corner from where you look from, the
 * surface's facing and the light it stands in; drawn as a second, white layer over the gore whose alpha is
 * that reflection times how wet the texel is (the gloss maps, tools/gen_wet_gloss.py) and how fresh the
 * blood still is - dried blood has no shine left.
 */
public final class WetShine {
	private static final Vector3f SUN = new Vector3f(0, 1, 0);
	private static float sun;
	private static float moon;
	private static float sky;
	private static long frame = Long.MIN_VALUE;
	private static float framePartial = -1.0F;

	private WetShine() {
	}

	/** Where the sun stands and how strong it and the sky are, this frame. */
	private static void update() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		long now = mc.level.getGameTime();
		if (now == frame && partial == framePartial) {
			return;
		}
		frame = now;
		framePartial = partial;
		// day time 0 is sunrise in the east (+x), 6000 noon overhead, 12000 sunset in the west
		float a = ((mc.level.getDayTime() % 24000L) + partial) / 24000.0F * Mth.TWO_PI;
		SUN.set(Mth.cos(a), Mth.sin(a), 0.0F).normalize();
		float rain = mc.level.getRainLevel(partial);
		float thunder = mc.level.getThunderLevel(partial);
		float overcast = 1.0F - 0.75F * rain - 0.2F * thunder;
		sun = Mth.clamp(SUN.y * 4.0F, 0.0F, 1.0F) * overcast;
		moon = Mth.clamp(-SUN.y * 4.0F, 0.0F, 1.0F) * 0.3F * overcast;
		sky = Mth.clamp(SUN.y * 2.0F + 0.35F, 0.12F, 1.0F) * (1.0F - 0.4F * rain);
		if (!mc.level.dimensionType().hasSkyLight()) {
			sun = 0.0F;
			moon = 0.0F;
			sky = 0.25F;
		}
	}

	/**
	 * How much light a wet surface at {@code p} (relative to the camera, in the world's directions) facing
	 * {@code n} reflects into the eye, 0..1, in the light {@code light} (packed block and sky light).
	 */
	public static float at(Vector3f p, Vector3f n, int light) {
		update();
		float skyLight = LightTexture.sky(light) / 15.0F;
		float blockLight = LightTexture.block(light) / 15.0F;
		float dist = p.length();
		float vx = dist > 1.0E-4F ? -p.x / dist : n.x;
		float vy = dist > 1.0E-4F ? -p.y / dist : n.y;
		float vz = dist > 1.0E-4F ? -p.z / dist : n.z;
		float nx = n.x;
		float ny = n.y;
		float nz = n.z;
		float ndv = nx * vx + ny * vy + nz * vz;
		if (ndv < 0.0F) {
			// a thin piece seen from its other side
			nx = -nx;
			ny = -ny;
			nz = -nz;
			ndv = -ndv;
		}
		// the eye's ray, mirrored in the surface
		float rx = 2.0F * ndv * nx - vx;
		float ry = 2.0F * ndv * ny - vy;
		float rz = 2.0F * ndv * nz - vz;
		float open = skyLight * skyLight;
		float toSun = Math.max(0.0F, rx * SUN.x + ry * SUN.y + rz * SUN.z);
		float toMoon = Math.max(0.0F, -(rx * SUN.x + ry * SUN.y + rz * SUN.z));
		float s = open * (sun * (pow(toSun, 80) * 2.4F + pow(toSun, 12) * 0.3F) + moon * (pow(toMoon, 60) * 1.6F + pow(toMoon, 10) * 0.15F));
		// the sky in it: Schlick's Fresnel - little looking straight in, a mirror at a glancing angle
		float fresnel = 0.03F + 0.97F * pow(1.0F - ndv, 5);
		s += fresnel * open * sky * 0.6F * Mth.clamp(ry * 0.6F + 0.5F, 0.0F, 1.0F);
		// a lamp or torch: from above and about, a broader glint
		float lamp = blockLight * blockLight;
		s += lamp * (pow(Math.max(0.0F, ry), 14) * 0.45F + fresnel * 0.25F);
		// even in poor light a wet surface holds a dull sheen
		s += 0.05F * Math.max(skyLight * sky, blockLight) * (0.3F + fresnel);
		return Mth.clamp(s, 0.0F, 1.0F);
	}

	private static float pow(float x, int n) {
		float r = 1.0F;
		float b = x;
		while (n > 0) {
			if ((n & 1) != 0) {
				r *= b;
			}
			b *= b;
			n >>= 1;
		}
		return r;
	}
}
