package de.rcm.ballistic.client.effect;

import com.mojang.blaze3d.platform.NativeImage;
import de.rcm.ballistic.BallisticMissiles;
import de.rcm.ballistic.client.mixin.GameRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Drives the {@code blast} screen post effect (dust haze, heat haze, god rays, exposure flash, bloom,
 * shell shock, weapon-specific looks). Post effect uniforms are fixed in their JSON, so the live
 * values are written every frame into a tiny 4x1 texture the shader reads
 * ({@code ballisticmissiles:blast_params}). A post effect that something else switched on
 * (spectating a creeper, other mods) is never replaced.
 */
public final class BlastShader {
	/** How the screen reacts, by weapon. */
	public enum Kind {
		FIRE,
		NUCLEAR,
		ANTIMATTER,
		EMP,
		/** Buried burst: brown, dusty, the picture shaken by the earthquake. */
		UNDERGROUND,
		/** Kinetic impact: dust and a hard shock, no flash. */
		KINETIC,
		/** Under the sea: cold white light and mist. */
		WATER
	}

	private static final Identifier EFFECT = BallisticMissiles.id("blast");
	private static final Identifier PARAMS = BallisticMissiles.id("textures/effect/blast_params.png");

	private static DynamicTexture params;
	/** Shock hitting the camera, flash exposure, fireball heat (0-1). */
	private static float strength;
	private static float exposure;
	private static float heat;
	/** Per-tick fade of the fireball glow: small blasts are gone in a second, nukes burn on. */
	private static float heatDecay = 0.97F;
	private static Kind kind = Kind.FIRE;
	private static int tint = 0xFFB060;
	private static Vec3 blastPos = Vec3.ZERO;
	/** Dust and smoke haze kicked up by the shock wave (0-1), lingers a few seconds. */
	private static float dust;
	private static boolean active;
	/** Earthquake: the picture bounces and rolls (0-1). */
	private static float quake;
	/** Water running down the lens after the spray or the wave (0-1). */
	private static float drench;
	/** Under fire: rounds cracking past close (0-1). */
	private static float suppression;
	/** Negative afterimage burnt into the eye by a flash, fixed on screen (0-1). */
	private static float afterimage;
	private static float afterX = 0.5F;
	private static float afterY = 0.5F;

	private BlastShader() {
	}

	/** A detonation somewhere: sets the look and the fireball's heat glow. */
	public static void blast(Kind newKind, Vec3 pos, double scale, int color) {
		// the shock of a really big one reaches the clouds: it blows a ring clear, and its smoke rises into the layer
		if (newKind == Kind.NUCLEAR || newKind == Kind.ANTIMATTER) {
			VolumetricClouds.blast(pos, (float) Math.min(450.0, 90.0 * scale), 0.9F);
		} else if (scale >= 3.0) {
			VolumetricClouds.blast(pos, (float) Math.min(160.0, 22.0 * scale), 0.5F);
		}
		double distance = ClientEffects.distanceToCamera(Minecraft.getInstance(), pos);
		double reach = 260.0 * Math.max(0.3, scale);
		if (distance > reach * 3.0) {
			return;
		}
		float near = (float) Mth.clamp(1.0 - distance / (reach * 3.0), 0.0, 1.0);
		if (near * scale >= heat * 0.8 || heat < 0.05F) {
			kind = newKind;
			tint = color;
			blastPos = pos;
			heat = Math.max(heat, (float) Math.min(1.0, near * (0.35 + 0.45 * scale)));
			heatDecay = (float) Mth.clamp(0.9 + 0.045 * scale, 0.9, 0.993);
		}
	}

	/** Ground shock: the picture jolts and rolls. */
	public static void quake(float amount) {
		quake = Math.max(quake, Mth.clamp(amount, 0.0F, 1.0F));
	}

	/** Spray or the wave hitting the camera: drops run down the lens. */
	/** A round cracking past close by: the picture closes in for a moment. */
	public static void suppress(float amount) {
		suppression = Math.min(1.0F, suppression + Mth.clamp(amount, 0.0F, 1.0F));
	}

	public static float suppression() {
		return suppression;
	}

	public static void drench(float amount) {
		drench = Math.max(drench, Mth.clamp(amount, 0.0F, 1.0F));
	}

	/** Shock arriving at the camera (from camera shake). */
	public static void kick(float amount) {
		float s = Mth.clamp((amount - 0.1F) / 0.9F, 0.0F, 1.0F);
		strength = Math.max(strength, s);
		dust = Math.max(dust, s * 0.9F);
	}

	/** Flash reaching the camera. */
	public static void flash(float amount) {
		exposure = Math.max(exposure, Mth.clamp(amount, 0.0F, 1.0F));
	}

	private static float shellShock() {
		return Mth.clamp((1.0F - ClientEffects.hearing()) / 0.9F, 0.0F, 1.0F);
	}

	public static void tick(Minecraft mc) {
		strength = Math.max(0.0F, strength * 0.93F - 0.004F);
		exposure = Math.max(0.0F, exposure * 0.9F - 0.004F);
		heat = Math.max(0.0F, heat * heatDecay - 0.0015F);
		dust = Math.max(0.0F, dust * 0.985F - 0.002F);
		quake = Math.max(0.0F, quake * 0.985F - 0.002F);
		drench = Math.max(0.0F, drench * 0.996F - 0.0008F);
		suppression = Math.max(0.0F, suppression * 0.96F - 0.006F);
		afterimage = Math.max(0.0F, afterimage * 0.991F - 0.0008F);
		GameRenderer renderer = mc.gameRenderer;
		Identifier current = renderer.currentPostEffect();
		boolean ours = EFFECT.equals(current);
		if (current != null && !ours) {
			active = false;
			return;
		}
		boolean wanted = mc.level != null && (strength > 0.01F || exposure > 0.01F || heat > 0.02F || dust > 0.01F || shellShock() > 0.02F
			|| WinterClient.amount() > 0.01F || quake > 0.01F || drench > 0.01F || afterimage > 0.01F || SmokeField.fog() > 0.01F || suppression > 0.01F);
		if (!wanted) {
			if (ours) {
				renderer.clearPostEffect();
			}
			active = false;
			return;
		}
		if (params == null) {
			// registered before the effect first loads, so the post chain picks this texture up
			params = new DynamicTexture(() -> "Blast shader parameters", 8, 1, false);
			mc.getTextureManager().register(PARAMS, params);
		}
		if (!ours) {
			((GameRendererAccessor) renderer).ballisticmissiles$setPostEffect(EFFECT);
		}
		active = true;
	}

	/** Every frame: writes the current values into the parameter texture. */
	public static void frame(Minecraft mc, float partialTick) {
		if (!active || params == null) {
			return;
		}
		NativeImage img = params.getPixels();
		if (img == null) {
			return;
		}
		int time = (int) ((System.nanoTime() / 10_000_000L) % 65536L); // centiseconds, 16 bit
		int kindByte = kind.ordinal() * 36;
		img.setPixel(0, 0, argb(kindByte, strength, exposure, shellShock()));

		Camera camera = mc.gameRenderer.getMainCamera();
		double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) * 0.5);
		double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		float[] screen = project(camera, blastPos.add(0.0, 12.0, 0.0), tanHalf, aspect);
		float sx = screen[0];
		float sy = screen[1];
		boolean onScreen = screen[2] > 0.5F;
		// a flash looked at directly leaves its mark on the eye
		if (onScreen && exposure > 0.5F && (kind == Kind.NUCLEAR || kind == Kind.ANTIMATTER || kind == Kind.WATER) && exposure > afterimage) {
			afterimage = exposure;
			afterX = Mth.clamp(sx, 0.0F, 1.0F);
			afterY = Mth.clamp(sy, 0.0F, 1.0F);
		}
		// light streaming out of the fireball: only while it is in view and still glowing
		float rays = onScreen ? Mth.clamp(heat * 1.3F + exposure * 0.6F, 0.0F, 1.0F) : exposure * 0.25F;
		img.setPixel(1, 0, argb(byteOf(rays), (time & 0xFF) / 255.0F, ((time >> 8) & 0xFF) / 255.0F, heat));
		img.setPixel(2, 0, byteOf(WinterClient.amount()) << 24 | (tint & 0xFFFFFF)); // alpha: nuclear winter
		img.setPixel(3, 0, argb(byteOf(dust), Mth.clamp(sx, 0.0F, 1.0F), Mth.clamp(sy, 0.0F, 1.0F), onScreen ? 1.0F : 0.0F));
		img.setPixel(4, 0, argb(byteOf(drench), suppression, 0.0F, quake));
		img.setPixel(5, 0, byteOf(SmokeField.fog()) << 24 | (SmokeField.fogColor() & 0xFFFFFF)); // standing in thick smoke
		img.setPixel(6, 0, argb(255, afterX, afterY, afterimage));
		img.setPixel(7, 0, 0);
		params.upload();
	}

	/**
	 * Screen position (0-1, may lie outside) of a world point: {sx, sy, on screen 1/0, depth along the
	 * view direction or 0 if behind the camera}.
	 */
	private static float[] project(Camera camera, Vec3 pos, double tanHalf, double aspect) {
		Vec3 d = pos.subtract(camera.position());
		Vector3fc fwd = camera.forwardVector();
		Vector3fc up = camera.upVector();
		Vector3fc left = camera.leftVector();
		double z = d.x * fwd.x() + d.y * fwd.y() + d.z * fwd.z();
		double x = -(d.x * left.x() + d.y * left.y() + d.z * left.z());
		double y = d.x * up.x() + d.y * up.y() + d.z * up.z();
		if (z <= 0.5) {
			return new float[] {0.5F, 0.5F, 0.0F, 0.0F};
		}
		float sx = (float) (0.5 + 0.5 * x / (z * tanHalf * aspect));
		float sy = (float) (0.5 + 0.5 * y / (z * tanHalf));
		boolean onScreen = sx > -0.1F && sx < 1.1F && sy > -0.1F && sy < 1.1F;
		return new float[] {sx, sy, onScreen ? 1.0F : 0.0F, (float) z};
	}

	private static int byteOf(float v) {
		return Math.round(Mth.clamp(v, 0.0F, 1.0F) * 255.0F);
	}

	private static int argb(int a, float r, float g, float b) {
		return (a << 24) | (byteOf(r) << 16) | (byteOf(g) << 8) | byteOf(b);
	}
}
