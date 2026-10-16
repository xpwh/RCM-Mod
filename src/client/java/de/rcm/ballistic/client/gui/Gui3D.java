package de.rcm.ballistic.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import de.rcm.ballistic.BallisticMissiles;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix3x2f;
import org.joml.Vector3f;

/**
 * A very small 3D renderer for menu screens: solid models made of flat-shaded convex parts, turned, lit (a key
 * light with a highlight, a rim light from behind) and projected in perspective, then drawn back to front as
 * triangles - each one the corner of a right-triangle texture stretched onto the screen by an affine transform.
 * Flat things on a face (text) are laid onto it the same way.
 */
public final class Gui3D {
	private static final Identifier TRI = BallisticMissiles.id("dynamic/ui_tri3d");
	private static final Vector3f LIGHT = new Vector3f(-0.45F, 0.6F, 0.65F).normalize();
	private static final Vector3f RIM = new Vector3f(0.7F, 0.2F, -0.7F).normalize();
	private static boolean made;

	private Gui3D() {
	}

	private static void init() {
		if (made) {
			return;
		}
		made = true;
		// a right triangle filling the lower-left half of the texture: (0,0) (S,0) (0,S)
		int s = 128;
		NativeImage image = new NativeImage(s, s, false);
		for (int y = 0; y < s; y++) {
			for (int x = 0; x < s; x++) {
				double sum = 0.0;
				for (int sy = 0; sy < 4; sy++) {
					for (int sx = 0; sx < 4; sx++) {
						double px = x + (sx + 0.5) / 4.0;
						double py = y + (sy + 0.5) / 4.0;
						sum += px + py <= s + 1.5 ? 1.0 : 0.0; // a hair past the diagonal, so neighbours close up
					}
				}
				image.setPixel(x, y, (int) Math.round(sum / 16.0 * 255.0) << 24 | 0xFFFFFF);
			}
		}
		Minecraft.getInstance().getTextureManager().register(TRI, new DynamicTexture(() -> "ui tri3d", image) {
			{
				this.sampler = RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR,
					FilterMode.LINEAR, false);
			}
		});
	}

	// ------------------------------------------------------------------ models

	/** A convex solid: its faces (each a convex polygon, any winding) and a colour per face. */
	public static final class Part {
		final List<Vector3f[]> faces = new ArrayList<>();
		final List<Integer> colors = new ArrayList<>();
		final Vector3f center = new Vector3f();

		Part face(int rgb, Vector3f... v) {
			this.faces.add(v);
			this.colors.add(rgb);
			return this;
		}

		/** A prism: the polygon {@code outline} (x, y) from z0 to z1, front face, back face and sides in their colours. */
		static Part prism(float[][] outline, float z0, float z1, int front, int back, int side) {
			Part p = new Part();
			int n = outline.length;
			Vector3f[] f = new Vector3f[n];
			Vector3f[] b = new Vector3f[n];
			for (int i = 0; i < n; i++) {
				f[i] = new Vector3f(outline[i][0], outline[i][1], z1);
				b[i] = new Vector3f(outline[i][0], outline[i][1], z0);
				p.center.add(outline[i][0], outline[i][1], (z0 + z1) * 0.5F);
			}
			p.center.div(n);
			p.face(front, f);
			p.face(back, b);
			for (int i = 0; i < n; i++) {
				int j = (i + 1) % n;
				p.face(side, f[i], f[j], b[j], b[i]);
			}
			return p;
		}

		static Part box(float x0, float y0, float x1, float y1, float z0, float z1, int rgb, int side) {
			return prism(new float[][] {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}}, z0, z1, rgb, side, side);
		}

		static Part disc(float r, int segments, float z0, float z1, int front, int back, int side, int side2) {
			float[][] o = new float[segments][];
			for (int i = 0; i < segments; i++) {
				double a = Mth.TWO_PI * i / segments;
				o[i] = new float[] {(float) Math.cos(a) * r, (float) Math.sin(a) * r};
			}
			Part p = prism(o, z0, z1, front, back, side);
			// a reeded edge: every other side a shade apart
			for (int i = 0; i < segments; i += 2) {
				p.colors.set(2 + i, side2);
			}
			return p;
		}
	}

	/** The warning sign: a red triangle with cut corners, a white face set into it and a black "!" standing off it. */
	public static List<Part> warningSign() {
		float[][] tri = cut(new float[][] {{0.0F, 1.0F}, {-1.12F, -0.86F}, {1.12F, -0.86F}}, 0.13F);
		float cy = (1.0F - 0.86F - 0.86F) / 3.0F;
		float[][] inner = new float[tri.length][];
		for (int i = 0; i < tri.length; i++) {
			inner[i] = new float[] {tri[i][0] * 0.68F, cy + (tri[i][1] - cy) * 0.68F};
		}
		List<Part> parts = new ArrayList<>();
		parts.add(Part.prism(tri, -0.17F, 0.17F, 0xD9262C, 0x5A0C0F, 0x8E1418));
		parts.add(Part.prism(inner, 0.17F, 0.2F, 0xF3EEE6, 0xF3EEE6, 0xBDB6AC));
		parts.add(Part.box(-0.075F, -0.24F, 0.075F, 0.36F, 0.2F, 0.27F, 0x161414, 0x2A2626));
		parts.add(Part.box(-0.075F, -0.5F, 0.075F, -0.36F, 0.2F, 0.27F, 0x161414, 0x2A2626));
		return parts;
	}

	/** A thick red coin with a white ring round its face (the "18+" is laid on it by {@link #faceText}). */
	public static List<Part> coin() {
		List<Part> parts = new ArrayList<>();
		parts.add(Part.disc(1.0F, 32, -0.13F, 0.13F, 0xC91F25, 0x6E1013, 0x7E1216, 0x9A181D));
		parts.add(Part.disc(0.86F, 32, 0.13F, 0.15F, 0xF3EEE6, 0xF3EEE6, 0xCFC8BE, 0xCFC8BE));
		parts.add(Part.disc(0.77F, 32, 0.15F, 0.17F, 0xD9262C, 0xD9262C, 0xA01B20, 0xA01B20));
		// and the same on the back
		parts.add(Part.disc(0.86F, 32, -0.15F, -0.13F, 0xF3EEE6, 0xF3EEE6, 0xCFC8BE, 0xCFC8BE));
		parts.add(Part.disc(0.77F, 32, -0.17F, -0.15F, 0xD9262C, 0xD9262C, 0xA01B20, 0xA01B20));
		return parts;
	}

	/** The polygon with each corner cut off {@code c} along its edges. */
	private static float[][] cut(float[][] poly, float c) {
		List<float[]> out = new ArrayList<>();
		int n = poly.length;
		for (int i = 0; i < n; i++) {
			float[] p = poly[i];
			float[] prev = poly[(i + n - 1) % n];
			float[] next = poly[(i + 1) % n];
			out.add(toward(p, prev, c));
			out.add(toward(p, next, c));
		}
		return out.toArray(new float[0][]);
	}

	private static float[] toward(float[] a, float[] b, float d) {
		float dx = b[0] - a[0];
		float dy = b[1] - a[1];
		float l = Mth.sqrt(dx * dx + dy * dy);
		return new float[] {a[0] + dx / l * d, a[1] + dy / l * d};
	}

	// ------------------------------------------------------------------ drawing

	/** Where and how a model is shown: its centre on the screen, how big one unit is, how it is turned. */
	public record View(float x, float y, float scale, float yaw, float pitch, float roll) {
		Matrix3f rotation() {
			return new Matrix3f().rotateY(this.yaw).rotateX(this.pitch).rotateZ(this.roll);
		}
	}

	/** How far the eye is from the model's centre, in model units: close enough for some perspective. */
	private static final float EYE = 6.0F;

	record Tri(float x0, float y0, float x1, float y1, float x2, float y2, float depth, int argb) {
	}

	/** Draws the parts turned and lit, in {@code alpha}; the rim light behind them in {@code glow}, {@code glowAmount} strong (0 none). */
	public static void draw(GuiGraphics g, List<Part> parts, View v, float alpha, int glow, float glowAmount) {
		init();
		if (alpha <= 0.005F || v.scale() < 0.5F) {
			return;
		}
		for (Tri t : triangles(parts, v, alpha, glow, glowAmount)) {
			triangle(g, t.x0, t.y0, t.x1, t.y1, t.x2, t.y2, t.argb);
		}
	}

	/** The model as screen triangles, back to front. */
	static List<Tri> triangles(List<Part> parts, View v, float alpha, int glow, float glowAmount) {
		Matrix3f rot = v.rotation();
		List<Tri> tris = new ArrayList<>();
		Vector3f eye = new Vector3f(0.0F, 0.0F, EYE);
		for (Part part : parts) {
			Vector3f c = rot.transform(new Vector3f(part.center));
			for (int f = 0; f < part.faces.size(); f++) {
				Vector3f[] face = part.faces.get(f);
				Vector3f[] w = new Vector3f[face.length];
				Vector3f mid = new Vector3f();
				for (int i = 0; i < face.length; i++) {
					w[i] = rot.transform(new Vector3f(face[i]));
					mid.add(w[i]);
				}
				mid.div(face.length);
				Vector3f n = new Vector3f(w[1]).sub(w[0]).cross(new Vector3f(w[2]).sub(w[0]));
				if (n.lengthSquared() < 1.0E-10F) {
					continue;
				}
				n.normalize();
				if (n.dot(new Vector3f(mid).sub(c)) < 0.0F) {
					n.negate(); // outward
				}
				Vector3f toEye = new Vector3f(eye).sub(mid).normalize();
				if (n.dot(toEye) <= 0.0F) {
					continue; // facing away
				}
				int color = shade(part.colors.get(f), n, toEye, glow, glowAmount);
				int argb = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F) << 24 | color;
				float[] sx = new float[face.length];
				float[] sy = new float[face.length];
				for (int i = 0; i < face.length; i++) {
					float k = EYE / (EYE - w[i].z);
					sx[i] = v.x() + w[i].x * k * v.scale();
					sy[i] = v.y() - w[i].y * k * v.scale();
				}
				float depth = EYE - mid.z;
				for (int i = 1; i < face.length - 1; i++) {
					tris.add(new Tri(sx[0], sy[0], sx[i], sy[i], sx[i + 1], sy[i + 1], depth, argb));
				}
			}
		}
		tris.sort((a, b) -> Float.compare(b.depth, a.depth));
		return tris;
	}

	/** Lays text flat onto the model's plane z = {@code z} (centred on x, y there), if that side faces the eye. */
	public static void faceText(GuiGraphics g, Font font, Component text, View v, float x, float y, float z, float height, int argb) {
		Matrix3f rot = v.rotation();
		float side = z < 0.0F ? -1.0F : 1.0F;
		Vector3f o = rot.transform(new Vector3f(x, y, z));
		Vector3f n = rot.transform(new Vector3f(0.0F, 0.0F, side));
		if (n.dot(new Vector3f(0.0F, 0.0F, EYE).sub(o).normalize()) <= 0.05F) {
			return;
		}
		// seen from the back, the text's right is the model's left
		float[] p0 = project(o, v);
		float[] px = project(rot.transform(new Vector3f(x + side, y, z)), v);
		float[] py = project(rot.transform(new Vector3f(x, y + 1.0F, z)), v);
		float k = height / 8.0F; // the font is 8 px high: model units per font pixel
		g.pose().pushMatrix();
		g.pose().mul(new Matrix3x2f((px[0] - p0[0]) * k, (px[1] - p0[1]) * k, (p0[0] - py[0]) * k, (p0[1] - py[1]) * k, p0[0], p0[1]));
		g.drawString(font, text, -font.width(text) / 2, -4, argb, false);
		g.pose().popMatrix();
	}

	private static float[] project(Vector3f w, View v) {
		float k = EYE / (EYE - w.z);
		return new float[] {v.x() + w.x * k * v.scale(), v.y() - w.y * k * v.scale()};
	}

	/** Screen position of a model-space point. */
	public static float[] screen(Vector3f model, View v) {
		return project(v.rotation().transform(new Vector3f(model)), v);
	}

	private static int shade(int rgb, Vector3f n, Vector3f toEye, int glow, float glowAmount) {
		float diffuse = Math.max(0.0F, n.dot(LIGHT));
		Vector3f h = new Vector3f(LIGHT).add(toEye).normalize();
		float spec = (float) Math.pow(Math.max(0.0F, n.dot(h)), 24.0) * 0.55F;
		float rim = (float) Math.pow(1.0F - Math.max(0.0F, n.dot(toEye)), 2.0) * Math.max(0.0F, n.dot(RIM) + 0.3F) * 0.6F;
		float light = 0.4F + 0.72F * diffuse;
		int r = channel((rgb >> 16) & 255, light, spec, rim * ((glow >> 16) & 255) / 255.0F * glowAmount);
		int gg = channel((rgb >> 8) & 255, light, spec, rim * ((glow >> 8) & 255) / 255.0F * glowAmount);
		int b = channel(rgb & 255, light, spec, rim * (glow & 255) / 255.0F * glowAmount);
		return r << 16 | gg << 8 | b;
	}

	private static int channel(int c, float light, float spec, float rim) {
		return Mth.clamp(Math.round(c * light + 255.0F * (spec + rim)), 0, 255);
	}

	/** One flat triangle on the screen: the texture's right triangle carried onto it by an affine transform. */
	private static void triangle(GuiGraphics g, float x0, float y0, float x1, float y1, float x2, float y2, int argb) {
		// the GUI culls quads wound the wrong way round: always map the texture with a positive determinant
		if ((x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0) < 0.0F) {
			float tx = x1;
			float ty = y1;
			x1 = x2;
			y1 = y2;
			x2 = tx;
			y2 = ty;
		}
		// pushed out half a pixel from its middle so neighbouring triangles leave no hairline between them
		float mx = (x0 + x1 + x2) / 3.0F;
		float my = (y0 + y1 + y2) / 3.0F;
		float[] a = grow(x0, y0, mx, my);
		float[] b = grow(x1, y1, mx, my);
		float[] c = grow(x2, y2, mx, my);
		g.pose().pushMatrix();
		g.pose().mul(new Matrix3x2f((b[0] - a[0]) / 128.0F, (b[1] - a[1]) / 128.0F, (c[0] - a[0]) / 128.0F, (c[1] - a[1]) / 128.0F, a[0], a[1]));
		g.blit(RenderPipelines.GUI_TEXTURED, TRI, 0, 0, 0.0F, 0.0F, 128, 128, 128, 128, 128, 128, argb);
		g.pose().popMatrix();
	}

	private static float[] grow(float x, float y, float mx, float my) {
		float dx = x - mx;
		float dy = y - my;
		float l = Mth.sqrt(dx * dx + dy * dy);
		return l < 1.0E-4F ? new float[] {x, y} : new float[] {x + dx / l * 0.5F, y + dy / l * 0.5F};
	}
}
