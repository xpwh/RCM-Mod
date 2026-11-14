import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.rcm.ballistic.client.gun.ShotgunMotion;
import de.rcm.ballistic.client.render.BoxMesh;
import de.rcm.ballistic.gun.ShotgunItem;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Offline animation studio: scripted scenes through ShotgunMotion, out as camera-space quads per frame. */
public class Studio {
	static BufferedImage atlas;
	static BoxMesh GUN, FOREND, BOLT, SHELL, SPENT;
	static final Vector3f RIGHT_SHOULDER = new Vector3f(0.34F, -0.48F, 0.52F), LEFT_SHOULDER = new Vector3f(-1.1F, -0.45F, 0.1F);
	static final Vector3f RIGHT_POLE = new Vector3f(0.65F, -1.0F, 0.25F), LEFT_POLE = new Vector3f(-0.65F, -1.0F, 0.2F);
	static final float FOREARM = 10.5F / 16.0F, RIGHT_UPPER = 0.36F, LEFT_UPPER = 0.6F, THICK = 0.5F;
	static DataOutputStream out;
	static int quads;
	static ByteArrayOutputStream buf;

	public static void main(String[] a) throws Exception {
		atlas = ImageIO.read(new File(a[0]));
		Class<?> r = Class.forName("de.rcm.ballistic.client.render.ShotgunItemRenderer");
		GUN = mesh(r, "GUN"); FOREND = mesh(r, "FOREND"); BOLT = mesh(r, "BOLT"); SHELL = mesh(r, "SHELL"); SPENT = mesh(r, "SPENT");
		String dir = a[1];
		for (String scene : new String[] {"aim_shoot", "hip_shoot", "reload", "dry", "draw", "sprint", "walk", "inspect", "land"}) {
			try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(dir + "/" + scene + ".bin")))) {
				out = o;
				run(scene);
			}
		}
	}

	static BoxMesh mesh(Class<?> c, String n) throws Exception {
		Field f = c.getDeclaredField(n); f.setAccessible(true); return (BoxMesh) f.get(null);
	}

	static float smooth(float x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }

	/** Scripted state, advanced tick by tick like ShotgunClient does it. */
	static void run(String scene) throws IOException {
		ShotgunMotion m = new ShotgunMotion();
		float aim = 0, prevAim = 0, load = 0, prevLoad = 0, sprint = 0, prevSprint = 0, walk = 0, prevWalk = 0;
		float lastShot = -100, dryTick = -100, equipTick = -100, landTick = -100, reloadStart = -1, rackStart = -1, stride = 0;
		boolean racking = false;
		int length = switch (scene) { case "reload" -> 70; case "inspect" -> 70; case "draw" -> 30; case "sprint", "walk" -> 40; default -> 42; };
		float fps = 3.0F; // frames per tick (60 fps)
		int frames = (int) (length * fps);
		out.writeInt(frames);
		float prevT = 0;
		for (int i = 0; i < frames; i++) {
			float t = i / fps;
			// ---- per-tick logic at tick boundaries
			int tick = (int) Math.floor(t);
			if (i == 0 || (int) Math.floor(prevT) != tick || i == 0) {
				prevAim = aim; prevLoad = load; prevSprint = sprint; prevWalk = walk;
				boolean wantAim = switch (scene) { case "aim_shoot" -> tick >= 4; default -> false; };
				aim = wantAim ? Math.min(1, aim + 0.2F) : Math.max(0, aim - 0.22F);
				boolean loading = scene.equals("reload") && tick >= 2 && tick < 2 + 3 * ShotgunItem.SHELL_CYCLE;
				if (loading && reloadStart < 0) reloadStart = 2;
				load = loading ? Math.min(1, load + 0.17F) : Math.max(0, load - 0.14F);
				float wantRun = scene.equals("sprint") && tick >= 3 ? 1 : 0;
				sprint += (wantRun - sprint) * 0.25F;
				float wantWalk = scene.equals("walk") && tick >= 3 ? 1 : 0;
				walk += (wantWalk - walk) * 0.2F;
				if (scene.equals("aim_shoot") && tick == 14 && lastShot < 0) { lastShot = 14; m.kick(1 - 0.3F * smooth(aim), 0.4F, 0.5F); }
				if (scene.equals("hip_shoot") && (tick == 4 || tick == 20) && lastShot != tick) { lastShot = tick; m.kick(1, -0.3F, 0.6F); }
				if (scene.equals("dry") && tick == 5) dryTick = 5;
				if (scene.equals("draw") && tick == 2) equipTick = 2;
				if (scene.equals("land") && tick == 6) landTick = 6;
			}
			float dt = i == 0 ? 0 : t - prevT;
			prevT = t;
			float partial = t - (float) Math.floor(t);
			float aimE = smooth(prevAim + (aim - prevAim) * partial);
			m.step(dt, 0, 0, aimE);
			if (scene.equals("sprint") || scene.equals("walk")) stride += dt * 0.65F * (scene.equals("sprint") ? 1.3F : 1.0F);
			float slide = t - lastShot;
			float pump = ShotgunMotion.stroke(slide, ShotgunItem.PUMP_BACK, ShotgunItem.PUMP_FORWARD);
			float shell = reloadStart >= 0 && t >= reloadStart && t < reloadStart + 3 * ShotgunItem.SHELL_CYCLE ? t - reloadStart : -1;
			float idle = scene.equals("inspect") ? 236 + t : 0;
			ShotgunMotion.Frame f = new ShotgunMotion.Frame(t, aimE, pump, slide, racking, t - dryTick, smooth(prevLoad + (load - prevLoad) * partial), shell,
				idle, t - equipTick, smooth(prevSprint + (sprint - prevSprint) * partial), stride, prevWalk + (walk - prevWalk) * partial, 0,
				t - landTick, 0.8F, 0, 0, 0);
			frame(m, f, t - lastShot - (ShotgunItem.PUMP_BACK - 0.4F));
		}
	}

	static void frame(ShotgunMotion m, ShotgunMotion.Frame f, float eject) throws IOException {
		buf = new ByteArrayOutputStream();
		DataOutputStream b = new DataOutputStream(buf);
		quads = 0;
		PoseStack ps = new PoseStack();
		ps.translate(0.56F, -0.52F, -0.72F); // vanilla: the right hand's place in view
		Matrix4f base = new Matrix4f(ps.last().pose());
		m.apply(ps, f);
		// the item's first-person display transform
		ps.translate(-3.2F / 16, 2.4F / 16, 0.5F / 16);
		ps.mulPose(Axis.YP.rotationDegrees(3.0F));
		Matrix4f item = new Matrix4f(ps.last().pose());
		// gun
		emit(b, item, GUN, null);
		float back = f.pump();
		float face = -0.138F + back * 0.085F, len = -0.052F - face;
		if (len > 0.002F) emit(b, new Matrix4f(item).translate(0, 0, face).scale(1, 1, len), BOLT, null);
		if (eject >= 0 && eject < 7) {
			Matrix4f e = new Matrix4f(item).translate(0.0125F + 0.11F * eject, 0.135F + 0.05F * eject - 0.012F * eject * eject, -0.06F + 0.012F * eject)
				.rotate(Axis.YP.rotationDegrees(-75 * eject)).rotate(Axis.XP.rotationDegrees(25 * eject)).translate(0, 0, 0.032F);
			emit(b, e, SPENT, null);
		}
		emit(b, new Matrix4f(item).translate(0, 0, back * 0.085F), FOREND, null);
		ShotgunMotion.Hands h = ShotgunMotion.hands(f);
		if (h.shell() != null) emit(b, new Matrix4f(item).translate(h.shell()).rotate(Axis.XP.rotationDegrees(h.shellTilt())), SHELL, null);
		// arms (IK as AkArms)
		Matrix4f toItem = new Matrix4f(item).invert().mul(base);
		float scale = toItem.transformDirection(new Vector3f(1, 0, 0)).length();
		Vector3f rs = toItem.transformPosition(h.rightShoulder(), new Vector3f()), ls = toItem.transformPosition(LEFT_SHOULDER, new Vector3f());
		Vector3f re = elbow(rs, h.right(), RIGHT_UPPER * scale, FOREARM * scale, toItem.transformDirection(new Vector3f(h.rightPole())));
		Vector3f le = elbow(ls, h.left(), LEFT_UPPER * scale, FOREARM * scale, toItem.transformDirection(new Vector3f(LEFT_POLE)));
		arm(b, item, h.right(), re, -1, 1, false);
		arm(b, item, h.left(), le, 1, 1, false);
		arm(b, item, re, rs, -1, re.distance(rs) / 0.75F, true);
		arm(b, item, le, ls, 1, le.distance(ls) / 0.75F, true);
		out.writeInt(quads);
		out.write(buf.toByteArray());
	}

	static Vector3f elbow(Vector3f shoulder, Vector3f hand, float upper, float fore, Vector3f pole) {
		Vector3f d = new Vector3f(hand).sub(shoulder);
		float dist = d.length();
		if (dist < 1e-4F) return new Vector3f(hand);
		d.div(dist);
		if (dist >= upper + fore - 1e-3F) return new Vector3f(hand).sub(new Vector3f(d).mul(fore));
		float a = (upper * upper - fore * fore + dist * dist) / (2 * dist);
		float hh = (float) Math.sqrt(Math.max(upper * upper - a * a, 0));
		Vector3f side = new Vector3f(pole).sub(new Vector3f(d).mul(pole.dot(d)));
		if (side.lengthSquared() < 1e-6F) side.set(0, -1, 0).sub(new Vector3f(d).mul(-d.y));
		side.normalize();
		return new Vector3f(shoulder).add(new Vector3f(d).mul(a)).add(side.mul(hh));
	}

	static void arm(DataOutputStream b, Matrix4f item, Vector3f from, Vector3f to, float fistX, float length, boolean reversed) throws IOException {
		Vector3f down = reversed ? new Vector3f(to).sub(from) : new Vector3f(from).sub(to);
		if (down.lengthSquared() < 1e-6F) return;
		down.normalize();
		Vector3f side = new Vector3f(-1, 0, 0);
		side.sub(new Vector3f(down).mul(side.dot(down)));
		if (side.lengthSquared() < 1e-4F) side.set(0, 0, 1).sub(new Vector3f(down).mul(down.z));
		side.normalize();
		Vector3f back = new Vector3f(side).cross(down);
		Quaternionf rot = new Quaternionf().setFromNormalized(new Matrix3f(side, down, back));
		Matrix4f mm = new Matrix4f(item).translate(from).rotate(rot).scale(THICK, Math.max(0.05F, length), THICK);
		if (reversed) mm.translate(-fistX / 16, 1 / 16F, 0); else mm.translate(-fistX / 16, -8.5F / 16, 0);
		// the vanilla arm cube: 4x12x4 px from 2 px above the pivot; skin at the fist end, sleeve above
		float x0 = (fistX < 0 ? -3 : -1) / 16F, x1 = x0 + 4 / 16F;
		box(b, mm, x0, -2 / 16F, -2 / 16F, x1, 6 / 16F, 2 / 16F, 0x2E5C8A); // sleeve
		box(b, mm, x0, 6 / 16F, -2 / 16F, x1, 10 / 16F, 2 / 16F, 0xC6926B); // hand
	}

	static void box(DataOutputStream b, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, int rgb) throws IOException {
		float[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
		int[][] faces = {{0, 1, 2, 3}, {5, 4, 7, 6}, {4, 0, 3, 7}, {1, 5, 6, 2}, {3, 2, 6, 7}, {4, 5, 1, 0}};
		for (int[] fc : faces) {
			Vector3f[] v = new Vector3f[4];
			for (int k = 0; k < 4; k++) v[k] = m.transformPosition(new Vector3f(c[fc[k]][0], c[fc[k]][1], c[fc[k]][2]));
			quad(b, v, rgb);
		}
	}

	static void emit(DataOutputStream b, Matrix4f m, BoxMesh mesh, Integer tint) throws IOException {
		float[] d;
		try { Method vm = BoxMesh.class.getDeclaredMethod("vertices"); vm.setAccessible(true); d = (float[]) vm.invoke(mesh); } catch (Exception e) { throw new RuntimeException(e); }
		for (int i = 0; i + 32 <= d.length; i += 32) {
			Vector3f[] v = new Vector3f[4];
			float u = 0, w = 0;
			for (int k = 0; k < 4; k++) { int j = i + k * 8; v[k] = m.transformPosition(new Vector3f(d[j], d[j + 1], d[j + 2])); u += d[j + 3]; w += d[j + 4]; }
			int px = Math.min(127, Math.max(0, (int) (u / 4 * 128))), py = Math.min(127, Math.max(0, (int) (w / 4 * 128)));
			quad(b, v, atlas.getRGB(px, py) & 0xFFFFFF);
		}
	}

	static void quad(DataOutputStream b, Vector3f[] v, int rgb) throws IOException {
		for (Vector3f p : v) { b.writeFloat(p.x); b.writeFloat(p.y); b.writeFloat(p.z); }
		b.writeInt(rgb);
		quads++;
	}
}
