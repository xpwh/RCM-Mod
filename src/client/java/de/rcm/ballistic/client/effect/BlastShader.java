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
		EMP
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

	private BlastShader() {
	}

	/** A detonation somewhere: sets the look and the fireball's heat glow. */
	public static void blast(Kind newKind, Vec3 pos, double scale, int color) {
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
		GameRenderer renderer = mc.gameRenderer;
		Identifier current = renderer.currentPostEffect();
		boolean ours = EFFECT.equals(current);
		if (current != null && !ours) {
			active = false;
			return;
		}
		boolean wanted = mc.level != null && (strength > 0.01F || exposure > 0.01F || heat > 0.02F || dust > 0.01F || shellShock() > 0.02F
			|| WinterClient.amount() > 0.01F);
		if (!wanted) {
			if (ours) {
				renderer.clearPostEffect();
			}
			active = false;
			return;
		}
		if (params == null) {
			// registered before the effect first loads, so the post chain picks this texture up
			params = new DynamicTexture(() -> "Blast shader parameters", 4, 1, false);
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
		int kindByte = Math.round(kind.ordinal() * 255.0F / 3.0F);
		img.setPixel(0, 0, argb(kindByte, strength, exposure, shellShock()));

		Camera camera = mc.gameRenderer.getMainCamera();
		Vec3 d = blastPos.add(0.0, 12.0, 0.0).subtract(camera.position());
		Vector3fc fwd = camera.forwardVector();
		Vector3fc up = camera.upVector();
		Vector3fc left = camera.leftVector();
		double z = d.x * fwd.x() + d.y * fwd.y() + d.z * fwd.z();
		double x = -(d.x * left.x() + d.y * left.y() + d.z * left.z());
		double y = d.x * up.x() + d.y * up.y() + d.z * up.z();
		double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) * 0.5);
		double aspect = (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
		float sx = 0.5F;
		float sy = 0.5F;
		boolean onScreen = false;
		if (z > 0.5) {
			sx = (float) (0.5 + 0.5 * x / (z * tanHalf * aspect));
			sy = (float) (0.5 + 0.5 * y / (z * tanHalf));
			onScreen = sx > -0.1F && sx < 1.1F && sy > -0.1F && sy < 1.1F;
		}
		// light streaming out of the fireball: only while it is in view and still glowing
		float rays = onScreen ? Mth.clamp(heat * 1.3F + exposure * 0.6F, 0.0F, 1.0F) : exposure * 0.25F;
		img.setPixel(1, 0, argb(byteOf(rays), (time & 0xFF) / 255.0F, ((time >> 8) & 0xFF) / 255.0F, heat));
		img.setPixel(2, 0, byteOf(WinterClient.amount()) << 24 | (tint & 0xFFFFFF)); // alpha: nuclear winter
		img.setPixel(3, 0, argb(byteOf(dust), Mth.clamp(sx, 0.0F, 1.0F), Mth.clamp(sy, 0.0F, 1.0F), onScreen ? 1.0F : 0.0F));
		params.upload();
	}

	private static int byteOf(float v) {
		return Math.round(Mth.clamp(v, 0.0F, 1.0F) * 255.0F);
	}

	private static int argb(int a, float r, float g, float b) {
		return (a << 24) | (byteOf(r) << 16) | (byteOf(g) << 8) | byteOf(b);
	}
}
